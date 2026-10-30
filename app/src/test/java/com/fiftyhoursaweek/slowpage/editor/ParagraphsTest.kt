package com.fiftyhoursaweek.slowpage.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The paragraph cap (round 2, ruling B): lengths, the length an edit would make, the read-only parts. */
class ParagraphsTest {
    @Test fun theLongestParagraphIsBetweenLineBreaks() {
        assertEquals(0, Paragraphs.longest(""))
        assertEquals(3, Paragraphs.longest("abc"))
        assertEquals(4, Paragraphs.longest("ab\nabcd\n\nx"))
        assertEquals(5, Paragraphs.longest("a\nabcde"))
    }

    @Test fun anEditJoinsTheParagraphsItTouches() {
        val text = "one\ntwo three\nfour"
        val lines = LineTable(text)
        fun after(start: Int, end: Int, insert: String): Int {
            val edited = text.substring(0, start) + insert + text.substring(end)
            // Only the paragraphs the edit touches, and only when they grow, matter to the gate;
            // whenever it answers it must not be less than the truth for those.
            val expected = Paragraphs.longest(edited)
            val answer = Paragraphs.longestAfter(lines, start, end, insert)
            assertTrue("$start..$end '$insert': $answer < $expected", answer <= expected)
            return answer
        }
        assertEquals("two three".length + 2, after(5, 5, "xx"))
        assertEquals("one".length + "two three".length, after(3, 4, ""))  // the line break deleted
        assertEquals(7, after(1, 1, "\n1234567\n"))
        assertEquals("one".length, after(0, 0, "\n"))
        assertEquals("x".length + "two three".length, after(4, 4, "abc\nx"))
    }

    @Test fun theReadOnlyPartsCoverTheTextAndKeepCharactersWhole() {
        val words = "word ".repeat(3000)
        val noSpaces = "😀".repeat(5000)
        val text = "# Title\n\n$words\nshort\n$noSpaces\n"
        val shown = ReadOnlyText(text)
        var start = 0
        for (i in 0 until shown.size) {
            val end = shown.ends[i]
            assertTrue("parts go forward", end > start)
            assertTrue("no part longer than PART + 1", end - start <= ReadOnlyText.PART + 1)
            assertTrue("never half a character", end == text.length || !Character.isLowSurrogate(text[end]))
            start = end
        }
        assertEquals(text.length, start)
        // A part ends after a line break when there is one, after a space inside a long paragraph,
        // and is shown without the line break it ends with.
        assertEquals("# Title\n\n", text.substring(0, shown.ends[0]))
        assertEquals("# Title\n", shown.part(0).toString())
        assertTrue(text.substring(shown.ends[0], shown.ends[1]).let { it.startsWith("word") && it.endsWith("word ") })
        assertEquals("", ReadOnlyText("").part(0).toString())
    }

    @Test fun aTwentyMebibyteParagraphIsCutIntoPartsInLinearTime() {
        // The emulator check found the first version searching back to the text's start for
        // each part: a 20 MiB paragraph never opened.
        val big = "word ".repeat(4 * 1024 * 1024)
        val began = System.nanoTime()
        val shown = ReadOnlyText(big)
        val ms = (System.nanoTime() - began) / 1_000_000
        assertTrue("$ms ms", ms < 2000)
        assertEquals(big.length, shown.ends.last())
        assertTrue(shown.size >= big.length / ReadOnlyText.PART)
    }
}
