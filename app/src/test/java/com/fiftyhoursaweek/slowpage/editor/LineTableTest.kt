package com.fiftyhoursaweek.slowpage.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.hushhour.Document

/** E8: the line table follows edits locally and stays equal to a rebuild. */
class LineTableTest {
    @Test fun editsKeepTheTableEqualToARebuild() {
        var text = "one\ntwo\nthree"
        val table = LineTable(text)
        assertEquals(3, table.lineCount)
        assertEquals(1, table.line(5))
        assertEquals(8, table.start(2))
        assertEquals(13, table.end(2))
        assertEquals("the caret at the very end is on the last line", 2, table.line(13))

        fun apply(start: Int, end: Int, insert: String, line: Int, removed: Int, inserted: Int) {
            text = text.substring(0, start) + insert + text.substring(end)
            table.apply(start, end, insert.length, line, removed, inserted, text)
            assertEquals("after ${insert.debugString()} at $start", LineTable(text).starts(), table.starts())
            assertEquals(text.length, table.length)
        }
        apply(3, 3, "\nhalf", 0, 0, 1)  // split a line
        apply(4, 9, "", 1, 1, 0)         // join two lines
        apply(0, 0, "a\nb\nc\n", 0, 0, 3)
        apply(0, text.length, "", 0, text.count { it == '\n' }, 0)
    }

    @Test fun randomEditsThroughTheCoreKeepTheTableEqualToARebuild() {
        val pieces = listOf("a", " ", "\n", "\n\n", "😀", "é", "# ", "```\n", "’")
        val random = kotlin.random.Random(8)
        Document().use { doc ->
            var text = ""
            val table = LineTable(text)
            repeat(3000) {
                val at = text.offsetByCodePoints(0, random.nextInt(text.codePointCount(0, text.length) + 1))
                val end = if (random.nextInt(3) == 0 && at < text.length) {
                    text.offsetByCodePoints(at, minOf(1 + random.nextInt(3), text.codePointCount(at, text.length)))
                } else at
                val insert = if (random.nextInt(4) == 0) "" else pieces.random(random)
                val r = doc.editUtf16(at.toULong(), end.toULong(), insert)
                text = text.substring(0, at) + insert + text.substring(end)
                table.apply(at, end, insert.length, r.line.toInt(), r.removedLines.toInt(), r.insertedLines.toInt(), text)
                assertEquals(LineTable(text).starts(), table.starts())
            }
            assertEquals(doc.text(), text)
        }
    }

    private fun String.debugString() = replace("\n", "\\n")
}
