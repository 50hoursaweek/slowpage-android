package com.fiftyhoursaweek.slowpage.store

import java.io.File
import java.io.IOException

/**
 * Text that could not be saved, kept next to the documents until a save of the same document
 * works (Codex phase 3, BLOCKING 1): a failed save followed by process death or a closed task
 * must not lose the only copy. One file per document name, written atomically, read bounded.
 * Blocking: call off the main thread.
 */
class Recovery(val folder: File, private val limitBytes: Long) {
    private fun file(name: String) = File(folder, name)

    /** Keep [text] for [name]; the error is returned (the disk may be the reason the save failed). */
    fun write(name: String, text: String): IOException? = try {
        writeAtomically(file(name), text.toByteArray(Charsets.UTF_8))
        null
    } catch (e: IOException) {
        e
    }

    /** The kept text for [name], or null when there is none or it cannot be read. */
    fun read(name: String): String? {
        val f = file(name)
        if (!f.isFile) return null
        val bytes = try {
            f.inputStream().use { readAtMost(it, limitBytes) }
        } catch (_: IOException) {
            null
        } ?: return null
        return DocumentStore.decode(bytes)
    }

    /** Forget the kept text for [name]: its document is on disk, or was deleted. */
    fun clear(name: String) {
        file(name).delete()
    }

    fun has(name: String) = file(name).isFile
}
