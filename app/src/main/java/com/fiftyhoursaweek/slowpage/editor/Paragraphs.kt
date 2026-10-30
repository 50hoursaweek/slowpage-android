package com.fiftyhoursaweek.slowpage.editor

/**
 * The paragraph cap (Francesco's ruling B, round 2): the platform lays out a paragraph as one
 * unit, again on every edit in it, so a very long one makes every key slow and a 20 MiB one
 * holds the main thread for over half a minute. A document whose longest paragraph is longer
 * than [CAP] opens read-only ([ReadOnlyText]); an edit that would make one is refused.
 */
object Paragraphs {
    /**
     * In UTF-16 units. A key costs the main thread about 0.14 ms per 1,000 units of the
     * paragraph it is in (emulator, round 2: 15 ms at 100,000, 36 ms at 256 Ki, 141 ms at
     * 1 MiB), and nothing more for the other paragraphs: at the cap a key still fits a frame.
     */
    const val CAP = 100_000

    /** The length of the longest paragraph of [text] (units between line breaks). */
    fun longest(text: CharSequence): Int {
        var longest = 0
        var start = 0
        while (true) {
            val end = text.indexOf('\n', start)
            if (end < 0) return maxOf(longest, text.length - start)
            longest = maxOf(longest, end - start)
            start = end + 1
        }
    }

    /**
     * The longest paragraph that replacing `start until end` with [insert] makes, in a text whose
     * lines are [lines]: the two paragraphs the change touches joined around it, and any the
     * insert brings whole.
     */
    fun longestAfter(lines: LineTable, start: Int, end: Int, insert: CharSequence): Int {
        val before = start - lines.start(lines.line(start))
        val after = lines.end(lines.line(end)) - end
        val first = insert.indexOf('\n')
        if (first < 0) return before + insert.length + after
        val last = insert.lastIndexOf('\n')
        return maxOf(before + first, insert.length - last - 1 + after, longest(insert.subSequence(first + 1, last + 1)))
    }
}

/**
 * A document shown read-only (ruling B): its text in parts of about [PART] units, each shown
 * on its own, so no paragraph is ever laid out whole. A part ends just after a line break when
 * there is one near; inside a very long paragraph it ends after a space, so the paragraph shows
 * as several. The text itself is never changed.
 */
class ReadOnlyText(val text: String) {
    /** Where each part ends; part `i` is `ends[i - 1] until ends[i]`. */
    val ends: IntArray = parts(text)

    val size get() = ends.size

    /** Part [index] as shown: without the line break it ends with (the next part starts on a new line anyway). */
    fun part(index: Int): CharSequence {
        val start = if (index == 0) 0 else ends[index - 1]
        var end = ends[index]
        if (end > start && text[end - 1] == '\n') end--
        return text.substring(start, end)
    }

    companion object {
        const val PART = 4096

        fun parts(text: String): IntArray {
            val out = ArrayList<Int>(text.length / PART + 1)
            var start = 0
            while (start < text.length) {
                start = partEnd(text, start)
                out.add(start)
            }
            if (out.isEmpty()) out.add(0)
            return out.toIntArray()
        }

        private fun partEnd(text: String, start: Int): Int {
            val limit = start + PART
            if (limit >= text.length) return text.length
            val line = lastIn(text, '\n', start, limit)
            if (line >= 0) return line + 1
            val space = lastIn(text, ' ', start, limit)
            if (space >= 0) return space + 1
            // No space either: cut, but never between the two halves of a character.
            return if (Character.isLowSurrogate(text[limit])) limit + 1 else limit
        }

        /** The last [c] in `start until end`, or -1: never searched further back (a 20 MiB paragraph has no line break to find). */
        private fun lastIn(text: String, c: Char, start: Int, end: Int): Int {
            for (i in end - 1 downTo start) if (text[i] == c) return i
            return -1
        }
    }
}
