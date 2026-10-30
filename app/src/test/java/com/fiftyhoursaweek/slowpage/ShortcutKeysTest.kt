package com.fiftyhoursaweek.slowpage

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import com.fiftyhoursaweek.slowpage.editor.EditorText
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.hushhour.ViewMode
import uniffi.hushhour.nextFocus
import java.time.Duration

/**
 * K1 through the activity, as a hardware keyboard sends it (the emulator has none): Ctrl
 * chords reach the commands before any view, so the editor's own Ctrl+Z never runs (E12).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land")
class ShortcutKeysTest {
    private fun chord(activity: MainActivity, key: Int, shift: Boolean = false) {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or (if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, meta))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, meta))
    }

    @Test fun ctrlChordsDriveTheEditor() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val vm = ViewModelProvider(activity)[ShellViewModel::class.java]
        until("ready") { vm.loaded }
        chord(activity, KeyEvent.KEYCODE_N)
        until("Ctrl+N opens a new document") { vm.screen is Screen.Editor && vm.highlight != null }
        idle()
        // Typed into the editor, as the keyboard does.
        val editor = activity.window.decorView.findEditor()!!
        editor.text.insert(0, "Hello")
        assertEquals("Hello", vm.editorText())
        chord(activity, KeyEvent.KEYCODE_Z)
        assertEquals("", vm.editorText())
        assertEquals("", editor.text.toString())
        chord(activity, KeyEvent.KEYCODE_Z, shift = true)
        assertEquals("Hello", vm.editorText())
        chord(activity, KeyEvent.KEYCODE_Z)
        chord(activity, KeyEvent.KEYCODE_Y)
        assertEquals("Hello", vm.editorText())
        chord(activity, KeyEvent.KEYCODE_P, shift = true)
        assertEquals(ViewMode.SPLIT, vm.viewMode)
        val focus = vm.preferences.focus
        chord(activity, KeyEvent.KEYCODE_D)
        assertEquals(nextFocus(focus), vm.preferences.focus)
        val size = vm.preferences.size
        chord(activity, KeyEvent.KEYCODE_EQUALS)
        assertEquals(size + 1, vm.preferences.size)
        chord(activity, KeyEvent.KEYCODE_MINUS)
        assertEquals(size, vm.preferences.size)
        chord(activity, KeyEvent.KEYCODE_COMMA)
        assertEquals(true, vm.menuOpen)
        chord(activity, KeyEvent.KEYCODE_COMMA)
        chord(activity, KeyEvent.KEYCODE_W)
        until("Ctrl+W closes") { vm.screen == Screen.Start }
    }

    private fun android.view.View.findEditor(): EditorText? = when (this) {
        is EditorText -> this
        is android.view.ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findEditor() }
        else -> null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(5)
        }
    }
}
