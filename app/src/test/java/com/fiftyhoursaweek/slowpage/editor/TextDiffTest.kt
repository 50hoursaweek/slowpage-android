package com.fiftyhoursaweek.slowpage.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class TextDiffTest {
    private fun apply(old: String, e: TextEdit) = old.substring(0, e.start) + e.insert + old.substring(e.end)

    @Test fun typingDeletingAndReplacingAreOneRange() {
        assertEquals(TextEdit(5, 5, "!"), diff("hello", "hello!"))
        assertEquals(TextEdit(4, 5, ""), diff("hello", "hell"))
        assertEquals(TextEdit(1, 4, "EL"), diff("hello", "hELo"))
        assertNull(diff("same", "same"))
    }

    @Test fun aRepeatedLetterIsAddedAfterTheCommonStart() {
        assertEquals(TextEdit(3, 3, "l"), diff("hel", "hell"))
    }

    @Test fun aSurrogatePairIsNeverSplit() {
        // 😀 U+1F600 and 😁 U+1F601 share their high surrogate.
        val e = diff("a😀b", "a😁b")!!
        assertEquals(TextEdit(1, 3, "😁"), e)
        val d = diff("😀😀", "😀")!!
        assertEquals("😀", apply("😀😀", d))
        assert(d.start % 2 == 0 && d.end % 2 == 0)
    }

    @Test fun randomEditsAlwaysRebuildTheNewText() {
        val alphabet = listOf("a", "b", " ", "\n", "😀", "😁", "é")
        val random = Random(72)
        repeat(5000) {
            val old = buildString { repeat(random.nextInt(12)) { append(alphabet.random(random)) } }
            val new = buildString { repeat(random.nextInt(12)) { append(alphabet.random(random)) } }
            val e = diff(old, new) ?: return@repeat assertEquals(old, new)
            assertEquals(new, apply(old, e))
            assert(e.start == 0 || !Character.isHighSurrogate(old[e.start - 1])) { "split at start: $old -> $new" }
            assert(e.end == old.length || !Character.isLowSurrogate(old[e.end])) { "split at end: $old -> $new" }
        }
    }
}
