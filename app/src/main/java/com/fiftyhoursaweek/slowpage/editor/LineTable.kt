package com.fiftyhoursaweek.slowpage.editor

/**
 * UTF-16 offsets of every line start (E8), so a text offset maps to a line in O(log n) and an
 * edit updates the table locally (the core reports how many lines came and went). A port of
 * the iPad's `LineTable`; Kotlin strings are UTF-16 like `NSString`.
 */
class LineTable(text: CharSequence = "") {
    /** `starts[i]` is the offset where line `i` begins; `starts[0] == 0`. */
    private var starts = IntArray(16)
    var lineCount = 1
        private set
    var length = 0
        private set

    init {
        rebuild(text)
    }

    fun starts(): List<Int> = starts.copyOf(lineCount).toList()

    fun rebuild(text: CharSequence) {
        var n = 1
        var a = IntArray(maxOf(16, text.length / 32))
        for (i in text.indices) {
            if (text[i] == '\n') {
                if (n == a.size) a = a.copyOf(a.size * 2)
                a[n++] = i + 1
            }
        }
        starts = a
        lineCount = n
        length = text.length
    }

    /** The line containing [offset] (a caret at the very end is on the last line). */
    fun line(offset: Int): Int {
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun start(index: Int) = starts[index]

    /** End of line [index], without its newline. */
    fun end(index: Int) = if (index + 1 < lineCount) starts[index + 1] - 1 else length

    /**
     * Units `start until oldEnd` of the old text were replaced by [insertedLength] units of
     * [text] (the new text), which removed [removedLines] newlines and inserted [insertedLines],
     * starting on [line].
     */
    fun apply(start: Int, oldEnd: Int, insertedLength: Int, line: Int, removedLines: Int, insertedLines: Int, text: CharSequence) {
        val delta = insertedLength - (oldEnd - start)
        val firstAfter = minOf(line + removedLines + 1, lineCount)
        val tail = lineCount - firstAfter
        val out = IntArray(maxOf(16, line + 1 + insertedLines + tail))
        System.arraycopy(starts, 0, out, 0, line + 1)
        var n = line + 1
        for (i in start until start + insertedLength) {
            if (text[i] == '\n' && n < line + 1 + insertedLines) out[n++] = i + 1
        }
        check(n == line + 1 + insertedLines) { "line table and core disagree on inserted lines" }
        for (i in 0 until tail) out[n + i] = starts[firstAfter + i] + delta
        starts = out
        lineCount = n + tail
        length += delta
    }
}
