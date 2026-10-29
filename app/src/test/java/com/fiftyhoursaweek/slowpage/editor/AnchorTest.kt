package com.fiftyhoursaweek.slowpage.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** E11: the iPad has no unit test for the anchor; this is the rule. */
class AnchorTest {
    @Test fun theCaretLineSitsAtTheMiddleOfTheEditor() {
        // A 1000 px editor with 500 px of anchor space above the text: line 20 (40 px lines).
        val top = 20 * 40
        assertEquals(500 + top + 20 - 500, Anchor.scrollFor(top, top + 40, paddingTop = 500, viewHeight = 1000, contentHeight = 100_000))
    }

    @Test fun theFirstLineCanSitAtTheMiddleAndTheScrollStaysInTheContent() {
        assertEquals(20, Anchor.scrollFor(0, 40, paddingTop = 500, viewHeight = 1000, contentHeight = 2040))
        assertEquals(0, Anchor.scrollFor(0, 40, paddingTop = 0, viewHeight = 1000, contentHeight = 2040))
        assertEquals("never past the end", 1040, Anchor.scrollFor(5000, 5040, paddingTop = 500, viewHeight = 1000, contentHeight = 2040))
    }
}
