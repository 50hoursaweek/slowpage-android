package com.fiftyhoursaweek.slowpage.store

import java.io.File
import java.io.IOException

/**
 * The eight most recently opened documents, newest first, no duplicates (S1). Entries are
 * document names in app storage: documents are app-private, so there are no SAF grants to
 * keep. In memory, and persisted separately (S2: `persist` writes, a failure is returned).
 *
 * File format (new on Android, nothing to migrate from the iPad `recents.plist`): UTF-8 text,
 * the header line `slowpage-recents 1`, then one name per line. A file with another header,
 * or larger than the state-file cap, is ignored.
 */
class Recents(private val file: File) {
    private val names = ArrayList<String>()

    init {
        val lines = StateFile.read(file)?.toString(Charsets.UTF_8)?.split('\n')
        if (lines != null && lines.firstOrNull() == HEADER) {
            for (line in lines.drop(1)) {
                if (line.isNotEmpty() && line !in names && names.size < LIMIT && isPlausible(line)) names += line
            }
        }
    }

    /** The entries, newest first. */
    fun names(): List<String> = names.toList()

    /** The entries whose documents still exist, newest first; the rest are dropped from memory. */
    fun existing(exists: (String) -> Boolean): List<String> {
        names.retainAll(exists)
        return names()
    }

    /** Put [name] first. Call [persist] to write the list. */
    fun add(name: String) {
        if (!isPlausible(name)) return  // a name that would break the one-per-line file
        names.remove(name)
        names.add(0, name)
        while (names.size > LIMIT) names.removeAt(names.size - 1)
    }

    /** [old] was renamed to [new]: the entry names [new] in the same place (first when [old] was not listed). */
    fun renamed(old: String, new: String) {
        val index = names.indexOf(old).takeIf { it >= 0 } ?: names.indexOf(new).takeIf { it >= 0 } ?: 0
        names.remove(old)
        names.remove(new)
        names.add(minOf(index, names.size), new)
        while (names.size > LIMIT) names.removeAt(names.size - 1)
    }

    fun remove(name: String) {
        names.remove(name)
    }

    /** Write the list; the error is returned for the caller to show. */
    fun persist(): IOException? =
        StateFile.write(file, (listOf(HEADER) + names).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))

    companion object {
        const val LIMIT = 8
        const val HEADER = "slowpage-recents 1"

        /** A bare file name on one line: never a path, so a damaged file cannot point outside the store. */
        private fun isPlausible(name: String) =
            '/' !in name && name.none { it.isISOControl() } && name != "." && name != ".." && !name.startsWith(".")
    }
}
