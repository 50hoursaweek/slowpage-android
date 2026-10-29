package com.fiftyhoursaweek.slowpage.editor

import android.view.KeyEvent

/** What a shortcut asks for. */
enum class Command { MENU, SPOTLIGHT, ANCHOR, THEME, FONT, VIEW, ZOOM_IN, ZOOM_OUT, UNDO, REDO, NEW, OPEN, CLOSE, IMPORT }

/**
 * The Linux shortcuts with Ctrl (K1), as the iPad has them with ⌘: Ctrl+, menu; Ctrl+D
 * spotlight; Ctrl+T anchor; Ctrl+Shift+T theme; Ctrl+Shift+F font; Ctrl+Shift+P view;
 * Ctrl+= / Ctrl++ / Ctrl+- zoom; Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y; Ctrl+N / Ctrl+O / Ctrl+W.
 * No Ctrl+S (saving is automatic). Ctrl+Shift+O imports (proposal in the parity matrix).
 */
object Shortcuts {
    /** The command for a key press with Ctrl (and maybe Shift), or null; Alt and Meta never match. */
    fun command(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean = false, meta: Boolean = false): Command? {
        if (!ctrl || alt || meta) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_COMMA -> Command.MENU.takeIf { !shift }
            KeyEvent.KEYCODE_D -> Command.SPOTLIGHT.takeIf { !shift }
            KeyEvent.KEYCODE_T -> if (shift) Command.THEME else Command.ANCHOR
            KeyEvent.KEYCODE_F -> Command.FONT.takeIf { shift }
            KeyEvent.KEYCODE_P -> Command.VIEW.takeIf { shift }
            // Ctrl+= and Ctrl++ (Shift+= on most layouts, or a key of its own).
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> Command.ZOOM_IN
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Command.ZOOM_OUT.takeIf { !shift }
            KeyEvent.KEYCODE_Z -> if (shift) Command.REDO else Command.UNDO
            KeyEvent.KEYCODE_Y -> Command.REDO.takeIf { !shift }
            KeyEvent.KEYCODE_N -> Command.NEW.takeIf { !shift }
            KeyEvent.KEYCODE_O -> if (shift) Command.IMPORT else Command.OPEN
            KeyEvent.KEYCODE_W -> Command.CLOSE.takeIf { !shift }
            else -> null
        }
    }

    fun command(event: KeyEvent): Command? =
        command(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed)

    private val lone = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.KEYCODE_FUNCTION,
    )
    private val navigation = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK,
    )

    /**
     * The general rule (V6, as on the iPad): in the preview every key press shows the hint except
     * navigation, selection, copy, the app's shortcuts and keys of the system (volume, media,
     * F1–F12). Delete and Forward Delete show it with any modifiers.
     */
    fun previewHint(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean = false, meta: Boolean = false, system: Boolean = false): Boolean {
        if (keyCode in lone || keyCode in navigation || system) return false
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return false
        if (ctrl && !alt && !meta && !shift && (keyCode == KeyEvent.KEYCODE_C || keyCode == KeyEvent.KEYCODE_A)) return false
        if (command(keyCode, ctrl, shift, alt, meta) != null) return false
        return true
    }

    fun previewHint(event: KeyEvent): Boolean =
        previewHint(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed, event.isSystem)
}
