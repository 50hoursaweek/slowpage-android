package com.fiftyhoursaweek.slowpage.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One document in app storage. */
data class DocumentEntry(val name: String, val bytes: Long, val modified: Long)

/** Why a document could not be read, written or imported. */
sealed class StoreError(message: String) : Exception(message) {
    class TooLarge(val limitBytes: Long) :
        StoreError("The file is larger than ${limitBytes / (1024 * 1024)} MiB.")
    class NotFound(val name: String) : StoreError("“$name” no longer exists.")
    class Io(cause: IOException) : StoreError(cause.message ?: cause.toString())
}

/**
 * The documents, kept in app-private storage (D1: other apps cannot see them; they leave the
 * device only through Export). Flat folder, file names are the documents' names. Reads are
 * bounded (D8), writes atomic (D2), New never overwrites (D10), import copies a stream in
 * (D9) and export copies the saved bytes out. Blocking: call off the main thread. Names are
 * the store's keys; the callers hold no paths.
 */
class DocumentStore(val folder: File, val limitBytes: Long) {
    private val lock = Any()

    fun file(name: String) = File(folder, name)

    /** The documents, newest first; hidden and temporary files are not documents. */
    fun list(): List<DocumentEntry> =
        folder.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { DocumentEntry(it.name, it.length(), it.lastModified()) }
            .sortedWith(compareByDescending<DocumentEntry> { it.modified }.thenBy { it.name })

    fun exists(name: String) = file(name).isFile

    /** Remove temporary files a killed write left behind (hidden, `.partial`). */
    fun removeLeftovers() {
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".partial") }
            .forEach { it.delete() }
    }

    /** Make an empty "Untitled.md", or "Untitled 2.md", …: never over an existing file (D10). */
    fun createUntitled(): String = createUnique("Untitled", Names.EXTENSION, ByteArray(0))

    /** The text of [name]: at most the limit, UTF-8 or else Latin-1, as the iPad reads it. */
    fun read(name: String): String {
        val f = file(name)
        if (!f.isFile) throw StoreError.NotFound(name)
        val bytes = try {
            f.inputStream().use { readAtMost(it, limitBytes) }
        } catch (e: IOException) {
            throw StoreError.Io(e)
        } ?: throw StoreError.TooLarge(limitBytes)
        return decode(bytes)
    }

    /** Replace the text of [name] atomically. */
    fun write(name: String, text: String) {
        try {
            writeAtomically(file(name), text.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw StoreError.Io(e)
        }
    }

    /** Remove [name]; false when it was already gone. */
    fun delete(name: String): Boolean = synchronized(lock) {
        val f = file(name)
        if (!f.isFile) return false
        if (!f.delete()) throw StoreError.Io(IOException("cannot delete $name"))
        true
    }

    /**
     * Copy [input] into a new document named after [displayName] (D9). The read is bounded and
     * the bytes are checked as text before anything is written; the new file never replaces
     * another. Returns the new document's name.
     */
    fun import(displayName: String?, input: InputStream): String {
        val bytes = try {
            readAtMost(input, limitBytes)
        } catch (e: IOException) {
            throw StoreError.Io(e)
        } ?: throw StoreError.TooLarge(limitBytes)
        val text = decode(bytes)
        val (base, ext) = Names.forImport(displayName)
        return createUnique(base, ext, text.toByteArray(Charsets.UTF_8))
    }

    /** Copy the saved bytes of [name] to [output]; a failure changes nothing in the store. */
    fun export(name: String, output: OutputStream) {
        val f = file(name)
        if (!f.isFile) throw StoreError.NotFound(name)
        try {
            f.inputStream().use { it.copyTo(output) }
            output.flush()
        } catch (e: IOException) {
            throw StoreError.Io(e)
        }
    }

    private fun createUnique(base: String, ext: String, bytes: ByteArray): String = synchronized(lock) {
        try {
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("cannot create $folder")
            // The bytes are written and synced under a temporary name first, then renamed to a
            // free name, so a file is never seen half written. Only this app writes the folder
            // and every create holds the lock, so a name free here stays free until the
            // rename. (No link(): SELinux may refuse hard links in app data [unverified].)
            val temp = File.createTempFile(".import.", ".partial", folder)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                for (n in 1..MAX_NUMBER) {
                    val name = Names.numbered(base, ext, n)
                    if (file(name).exists()) continue
                    if (!temp.renameTo(file(name))) throw IOException("cannot create $name")
                    return name
                }
                throw IOException("no free name for $base.$ext")
            } finally {
                temp.delete()
            }
        } catch (e: IOException) {
            throw StoreError.Io(e)
        }
    }

    companion object {
        private const val MAX_NUMBER = 99_999

        /** UTF-8 (a leading BOM dropped), or Latin-1 when the bytes are not UTF-8 (iPad `TextDocument`). */
        fun decode(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (_: CharacterCodingException) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
    }
}
