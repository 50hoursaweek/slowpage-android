package com.fiftyhoursaweek.slowpage.store

import java.io.File

/**
 * The rename in progress (R1, Codex rename round 2, BLOCKING 2). A rename moves the document,
 * then its kept unsaved text, then Recent names it: three steps, and the process can end
 * between them. The journal is written before anything moves and removed once all three are
 * done, so at the next start [recover] finishes a rename whose document moved, or leaves
 * one that did not, and the kept text is never left under a name with no document.
 *
 * File format: the line `slowpage-rename 1`, then the old name, then the new name.
 */
class RenameJournal(private val file: File) {
    /** Record that [old] is about to become [new]; throws when it cannot be written, so nothing moves. */
    fun begin(old: String, new: String) {
        StateFile.write(file, "$HEADER\n$old\n$new\n".toByteArray(Charsets.UTF_8))?.let { throw StoreError.Io(it) }
    }

    /** The rename is over (done, refused or undone). */
    fun end() {
        file.delete()
    }

    /** The recorded (old, new) names, or null when there is none or it is damaged. */
    fun read(): Pair<String, String>? {
        val lines = StateFile.read(file)?.toString(Charsets.UTF_8)?.split('\n') ?: return null
        if (lines.size < 3 || lines[0] != HEADER) return null
        val (old, new) = lines[1] to lines[2]
        return if (isPlausible(old) && isPlausible(new) && old != new) old to new else null
    }

    /**
     * At start, before anything opens or lists: a rename the process did not finish. Its
     * document moved: the kept text and Recent follow it now (a copy that cannot follow stays
     * where it is, never deleted). Its document did not move: nothing moved, nothing to do.
     * The journal is removed either way. Blocking.
     */
    fun recover(store: DocumentStore, recovery: Recovery, recents: Recents) {
        val (old, new) = read() ?: return end()
        if (store.exists(new) && !store.exists(old)) {
            recovery.rename(old, new)
            if (old in recents.names()) {
                recents.renamed(old, new)
                recents.persist()
            }
        }
        end()
    }

    companion object {
        const val HEADER = "slowpage-rename 1"

        /** A bare document name: never a path, so a damaged journal cannot point outside the store. */
        private fun isPlausible(name: String) =
            name.isNotEmpty() && '/' !in name && name.none { it.isISOControl() } && !name.startsWith(".")
    }
}
