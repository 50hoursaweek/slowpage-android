package com.fiftyhoursaweek.slowpage.editor

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K1 (the Linux table with Ctrl) and the preview's hint rule (V6). */
class ShortcutsTest {
    private fun ctrl(key: Int, shift: Boolean = false) = Shortcuts.command(key, ctrl = true, shift = shift)

    @Test fun theLinuxTableWithCtrl() {
        assertEquals(Command.MENU, ctrl(KeyEvent.KEYCODE_COMMA))
        assertEquals(Command.SPOTLIGHT, ctrl(KeyEvent.KEYCODE_D))
        assertEquals(Command.ANCHOR, ctrl(KeyEvent.KEYCODE_T))
        assertEquals(Command.THEME, ctrl(KeyEvent.KEYCODE_T, shift = true))
        assertEquals(Command.FONT, ctrl(KeyEvent.KEYCODE_F, shift = true))
        assertEquals(Command.VIEW, ctrl(KeyEvent.KEYCODE_P, shift = true))
        assertEquals(Command.ZOOM_IN, ctrl(KeyEvent.KEYCODE_EQUALS))
        assertEquals(Command.ZOOM_IN, ctrl(KeyEvent.KEYCODE_EQUALS, shift = true))  // Ctrl++ on a US layout
        assertEquals(Command.ZOOM_IN, ctrl(KeyEvent.KEYCODE_PLUS))
        assertEquals(Command.ZOOM_OUT, ctrl(KeyEvent.KEYCODE_MINUS))
        assertEquals(Command.UNDO, ctrl(KeyEvent.KEYCODE_Z))
        assertEquals(Command.REDO, ctrl(KeyEvent.KEYCODE_Z, shift = true))
        assertEquals(Command.REDO, ctrl(KeyEvent.KEYCODE_Y))
        assertEquals(Command.NEW, ctrl(KeyEvent.KEYCODE_N))
        assertEquals(Command.OPEN, ctrl(KeyEvent.KEYCODE_O))
        assertEquals(Command.IMPORT, ctrl(KeyEvent.KEYCODE_O, shift = true))
        assertEquals(Command.CLOSE, ctrl(KeyEvent.KEYCODE_W))
    }

    @Test fun noCtrlSAndTheTextKeysStayWithTheTextViews() {
        for (key in listOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_A)) {
            assertNull(ctrl(key))
        }
        assertNull("without Ctrl nothing matches", Shortcuts.command(KeyEvent.KEYCODE_D, ctrl = false, shift = false))
        assertNull("Ctrl+Alt is not Ctrl", Shortcuts.command(KeyEvent.KEYCODE_D, ctrl = true, shift = false, alt = true))
    }

    @Test fun inThePreviewEveryKeyShowsTheHintButNavigationCopyAndShortcuts() {
        fun hint(key: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
            Shortcuts.previewHint(key, ctrl, shift, alt)
        assertTrue(hint(KeyEvent.KEYCODE_A))
        assertTrue(hint(KeyEvent.KEYCODE_ENTER))
        assertTrue(hint(KeyEvent.KEYCODE_SPACE))
        // Every delete chord.
        for (key in listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)) {
            for (mask in 0 until 8) assertTrue(hint(key, mask and 1 != 0, mask and 2 != 0, mask and 4 != 0))
        }
        assertTrue("paste and cut would edit", hint(KeyEvent.KEYCODE_V, ctrl = true))
        assertTrue(hint(KeyEvent.KEYCODE_X, ctrl = true))
        assertFalse(hint(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(hint(KeyEvent.KEYCODE_DPAD_LEFT, shift = true))
        assertFalse(hint(KeyEvent.KEYCODE_PAGE_DOWN))
        assertFalse(hint(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertFalse(hint(KeyEvent.KEYCODE_F5))
        assertFalse(hint(KeyEvent.KEYCODE_C, ctrl = true))
        assertFalse(hint(KeyEvent.KEYCODE_A, ctrl = true))
        assertFalse(hint(KeyEvent.KEYCODE_P, ctrl = true, shift = true))
        assertFalse(Shortcuts.previewHint(KeyEvent.KEYCODE_VOLUME_UP, false, false, system = true))
    }
}
