package com.fiftyhoursaweek.slowpage.editor

import com.fiftyhoursaweek.slowpage.ui.Colours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hushhour.Document
import uniffi.hushhour.Theme
import uniffi.hushhour.palette
import uniffi.hushhour.stats

/** E15 (no test on the iPad), E12 and E6 against the core. */
class StatsAndUndoTest {
    @Test fun theStatisticsOfAFixedText() {
        val s = stats("# Title\n\nOne two three. Four five?\n\nSix.")
        assertEquals(7uL, s.words)
        assertEquals(3uL, s.sentences)
        assertTrue(s.readingTime, s.readingTime.endsWith("sec"))
    }

    @Test fun theCoreReportsUndoAndRedoAvailability() {
        Document().use { d ->
            assertFalse(d.canUndo())
            assertFalse(d.canRedo())
            d.editUtf16(0u, 0u, "a")
            assertTrue(d.canUndo())
            assertNotNull(d.undo())
            assertFalse(d.canUndo())
            assertTrue(d.canRedo())
            assertEquals("a", d.redo()!!.text)
        }
    }

    @Test fun theCaretIsTheTextColourAndTheSelectionAGreyBetweenPageAndText() {
        for (theme in Theme.entries) {
            val p = palette(theme)
            assertEquals(p.text, Colours.caret(p))
            val selection = Colours.selection(p)
            assertTrue(Colours.contrast(p.text, selection) > 1.5)
        }
    }
}
