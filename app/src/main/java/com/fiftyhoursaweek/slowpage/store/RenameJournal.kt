package com.fiftyhoursaweek.slowpage.store

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/**
 * The rename in progress (R1, Codex rename rounds 2 and 3). A rename moves the document, then
 * its kept unsaved text, then Recent names it: three steps, and the process can end between
 * them. The journal is written before anything moves and removed once the rename is over, so
 * at the next start [recover] finishes a rename whose document moved.
 *
 * [recover] never deletes a document or a kept copy, only moves a copy into an empty place,
 * and can run any number of times: whatever it cannot finish now, it leaves, with the journal,
 * for the next start.
 *
 * File format: the line `slowpage-rename 1`, the old name, the new name, and the document's
 * modification time (ms) when it moved, each on its own line.
 */
class RenameJournal(private val file: File) {
    /**
     * Record that [old], the file [document], is about to become [new], with its modification
     * time; throws when the time cannot be read or the journal written, so nothing moves
     * (Vibe, NEW-B3: never a time of 0 from a failed read).
     */
    fun begin(old: String, new: String, document: File) {
        val modified = try {
            (stat(document) ?: throw StoreError.NotFound(old)).lastModifiedTime().toMillis()
        } catch (e: IOException) {
            throw StoreError.Io(e)
        }
        StateFile.write(file, "$HEADER\n$old\n$new\n$modified\n".toByteArray(Charsets.UTF_8))?.let { throw StoreError.Io(it) }
    }

    /** The rename is over (done, refused or undone). */
    fun end() {
        file.delete()
    }

    /**
     * What [begin] wrote, exactly, or null when it is not that (damaged). Throws when it cannot
     * be read: an I/O failure is not damage (Vibe, NEW-B3).
     */
    @Throws(IOException::class)
    fun read(): Entry? {
        val bytes = file.inputStream().use { readAtMost(it, StateFile.READ_LIMIT.toLong()) } ?: return null
        val lines = bytes.toString(Charsets.UTF_8).split('\n')
        if (lines.size != 5 || lines[0] != HEADER || lines[4].isNotEmpty()) return null
        val (old, new) = lines[1] to lines[2]
        val modified = lines[3].toLongOrNull() ?: return null
        return if (isPlausible(old) && isPlausible(new) && old != new) Entry(old, new, modified) else null
    }

    data class Entry(val old: String, val new: String, val modified: Long)

    /**
     * At start, before anything lists or opens: a rename the process did not finish. Only when
     * its document is the one that moved (the new name is there, the old one is not, and the
     * file has the recorded time; anything else is a rename that never moved, or a journal
     * about other files) its kept text moves to the new name, if that has none, and Recent
     * follows it. The journal is removed when that is done, or there is nothing to do, or it
     * is damaged; else it stays for the next start. Any I/O failure, reading the journal or
     * any file's attributes, leaves everything as it is, the journal too (Vibe, NEW-B3): only
     * a file that is certainly not there counts as absent. Returns a failure to write Recent,
     * to be shown. Blocking.
     */
    fun recover(store: DocumentStore, recovery: Recovery, recents: Recents): IOException? {
        val (old, new) = try {
            if (stat(file) == null) return null
            val (old, new, modified) = read() ?: return null.also { end() }
            val moved = stat(store.file(old)) == null &&
                stat(store.file(new))?.takeIf { it.isRegularFile }?.lastModifiedTime()?.toMillis() == modified
            if (!moved) return null.also { end() }
            // A copy under the new name too is never replaced: both stay, and so does the journal.
            if (stat(File(recovery.folder, old)) != null && !recovery.moveIfAbsent(old, new)) return null
            old to new
        } catch (_: IOException) {
            return null  // uncertain: nothing changes, the journal stays for the next start
        }
        if (old in recents.names()) {
            recents.renamed(old, new)
            recents.persist()?.let { return it }
        }
        end()
        return null
    }

    companion object {
        const val HEADER = "slowpage-rename 1"

        /**
         * The attributes of [f], or null only when it certainly does not exist; any other
         * failure throws (Vibe, NEW-B3: `File.lastModified` and `File.isFile` answer 0 and false).
         */
        @Throws(IOException::class)
        private fun stat(f: File): BasicFileAttributes? = try {
            Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            null
        }

        /** A bare document name: never a path, so a damaged journal cannot point outside the store. */
        private fun isPlausible(name: String) =
            name.isNotEmpty() && '/' !in name && name.none { it.isISOControl() } && !name.startsWith(".")
    }
}
