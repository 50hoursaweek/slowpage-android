package com.fiftyhoursaweek.slowpage.store

import java.io.File
import java.io.IOException

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
    /** Record that [old] (modified at [modified]) is about to become [new]; throws when it cannot be written, so nothing moves. */
    fun begin(old: String, new: String, modified: Long) {
        StateFile.write(file, "$HEADER\n$old\n$new\n$modified\n".toByteArray(Charsets.UTF_8))?.let { throw StoreError.Io(it) }
    }

    /** The rename is over (done, refused or undone). */
    fun end() {
        file.delete()
    }

    fun exists() = file.exists()

    /** What [begin] wrote, exactly, or null when there is none or it is not that. */
    fun read(): Entry? {
        val lines = StateFile.read(file)?.toString(Charsets.UTF_8)?.split('\n') ?: return null
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
     * cannot be read; else it stays for the next start. Returns a failure to write Recent, to
     * be shown. Blocking.
     */
    fun recover(store: DocumentStore, recovery: Recovery, recents: Recents): IOException? {
        if (!exists()) return null
        val (old, new, modified) = read() ?: return null.also { end() }
        val moved = !store.exists(old) && store.exists(new) && store.file(new).lastModified() == modified
        if (moved) {
            // A copy under the new name too is never replaced: both stay, and so does the journal.
            if (recovery.has(old) && !recovery.moveIfAbsent(old, new)) return null
            if (old in recents.names()) {
                recents.renamed(old, new)
                recents.persist()?.let { return it }
            }
        }
        end()
        return null
    }

    companion object {
        const val HEADER = "slowpage-rename 1"

        /** A bare document name: never a path, so a damaged journal cannot point outside the store. */
        private fun isPlausible(name: String) =
            name.isNotEmpty() && '/' !in name && name.none { it.isISOControl() } && !name.startsWith(".")
    }
}
