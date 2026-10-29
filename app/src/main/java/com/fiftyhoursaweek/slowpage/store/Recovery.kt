package com.fiftyhoursaweek.slowpage.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Unsaved text and when it was kept (wall clock, ms), so the newest copy wins (Codex round 2). */
data class Kept(val text: String, val stamp: Long)

/**
 * Text that could not be saved, kept next to the documents until a save of the same document
 * works (Codex phase 3, BLOCKING 1): a failed save followed by process death or a closed task
 * must not lose the only copy. One file per document name, written atomically, read bounded.
 * Each copy carries its stamp: a copy older than the document on disk, or than another copy,
 * is stale and never restored. Blocking: call off the main thread.
 *
 * File format: the line `slowpage-recovery 1 <stamp>`, then the text (UTF-8).
 */
class Recovery(val folder: File, private val limitBytes: Long) {
    private fun file(name: String) = File(folder, name)

    /** Keep [text] for [name] as of [stamp]; the error is returned (the disk may be why the save failed). */
    fun write(name: String, text: String, stamp: Long): IOException? = try {
        writeAtomically(file(name), "$HEADER $stamp\n$text".toByteArray(Charsets.UTF_8))
        null
    } catch (e: IOException) {
        e
    }

    /** The kept copy for [name], or null when there is none or it cannot be read or parsed. */
    fun read(name: String): Kept? {
        val f = file(name)
        if (!f.isFile) return null
        val bytes = try {
            f.inputStream().use { readAtMost(it, limitBytes + HEADER_ROOM) }
        } catch (_: IOException) {
            null
        } ?: return null
        val all = String(bytes, Charsets.UTF_8)
        val newline = all.indexOf('\n')
        if (newline < 0) return null
        val header = all.substring(0, newline).split(' ')
        if (header.size != 3 || "${header[0]} ${header[1]}" != HEADER) return null
        val stamp = header[2].toLongOrNull() ?: return null
        return Kept(all.substring(newline + 1), stamp)
    }

    /** Forget the kept text for [name]: its document is on disk, or was deleted. Best effort: a copy left behind is stale by its stamp. */
    fun clear(name: String) {
        file(name).delete()
    }

    fun has(name: String) = file(name).isFile

    /** Remove temporary files a killed write left behind (Codex round 2, MINOR 2). */
    fun removeLeftovers() {
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".partial") }
            .forEach { it.delete() }
    }

    companion object {
        const val HEADER = "slowpage-recovery 1"
        private const val HEADER_ROOM = 64L

        /** The kept copy to restore over [onDisk] (the document's modification time): the newest one newer than the document. */
        fun newest(onDisk: Long, vararg copies: Kept?): Kept? =
            copies.filterNotNull().filter { it.stamp > onDisk }.maxByOrNull { it.stamp }

        /** [text] gzipped, for the saved state (small: the whole saved state must stay well under 1 MB). */
        fun pack(text: String): ByteArray = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()

        fun unpack(bytes: ByteArray): String? = try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: IOException) {
            null
        }
    }
}
