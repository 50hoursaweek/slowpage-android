package com.fiftyhoursaweek.slowpage.editor

import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.text.style.CharacterStyle
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.fiftyhoursaweek.slowpage.R
import com.fiftyhoursaweek.slowpage.Screen
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.SlowpageApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.hushhour.FocusMode
import uniffi.hushhour.FontChoice
import uniffi.hushhour.Theme
import java.time.Duration

/**
 * E9, E12, E13: the editor view over the real view model and core, with input as a keyboard
 * sends it (an InputConnection: composing text, commits, deletes) and as paste does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land")
class EditorTextTest {
    private lateinit var app: SlowpageApp
    private val store = ViewModelStore()
    private lateinit var vm: ShellViewModel
    private lateinit var editor: EditorText
    private lateinit var ic: InputConnection

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as SlowpageApp
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(app, SavedStateHandle()) as T
        }
        vm = ViewModelProvider(store, factory)[ShellViewModel::class.java]
        idle()
        vm.newDocument()
        until("a document opens") { vm.screen is Screen.Editor && vm.highlight != null }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        editor = LayoutInflater.from(activity).inflate(R.layout.editor_text, null, false) as EditorText
        activity.setContentView(editor)
        editor.bind(vm.host, vm.highlight!!, vm.faces)
        editor.setLook(Look(Theme.LIGHT, FontChoice.MONO, 36f, 1.5f), 100, 40)
        editor.load(vm.editorText(), 0, 0)
        vm.attach(editor)
        editor.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        editor.layout(0, 0, 1280, 800)
        editor.requestFocus()
        ic = editor.onCreateInputConnection(EditorInfo())!!
    }

    @After fun tearDown() = store.clear()

    private fun inStep() {
        assertEquals(editor.text.toString(), vm.editorText())
        assertEquals(LineTable(editor.text).starts(), vm.highlight!!.lines.starts())
    }

    @Test fun composingCommitsAndDeletesFromTheKeyboardReachTheCore() {
        ic.setComposingText("Hel", 1)
        ic.setComposingText("Hello", 1)
        ic.finishComposingText()
        ic.commitText(" world", 1)
        inStep()
        ic.deleteSurroundingText(6, 0)
        ic.commitText("\n# Title", 1)
        assertEquals("Hello\n# Title", vm.editorText())
        inStep()
        // Autocorrect replaces a word around the caret.
        ic.setComposingRegion(0, 5)
        ic.setComposingText("Howdy", 1)
        ic.finishComposingText()
        assertEquals("Howdy\n# Title", vm.editorText())
        inStep()
    }

    @Test fun theHeadingIsDrawnWithTheCoresColours() {
        ic.commitText("# Title\nplain", 1)
        idle()
        val title = editor.text.indexOf("Title")
        val marks = editor.text.getSpans(0, 1, CharacterStyle::class.java)
        assertTrue("the marker is coloured", marks.isNotEmpty())
        assertTrue(editor.text.getSpans(title, title + 1, CharacterStyle::class.java).isNotEmpty())
        val plain = editor.text.indexOf("plain")
        assertTrue("plain text has no spans", editor.text.getSpans(plain, plain + 1, CharacterStyle::class.java).isEmpty())
    }

    @Test fun pastedLineEndingsAreNormalised() {
        ic.commitText("a\r\nb\rc d", 1)
        assertEquals("a\nb\nc\nd", editor.text.toString())
        inStep()
    }

    @Test fun undoAndRedoAreTheCoresAndThePlatformHistoryIsOff() {
        ic.commitText("one", 1)
        ic.commitText("\ntwo", 1)
        idle()
        // The menu's Undo (or the keyboard's) goes to the core.
        editor.onTextContextMenuItem(android.R.id.undo)
        inStep()
        assertTrue(editor.text.length < "one\ntwo".length)
        editor.onTextContextMenuItem(android.R.id.redo)
        assertEquals("one\ntwo", editor.text.toString())
        inStep()
        vm.command(Command.UNDO)
        inStep()
        assertEquals(editor.text.length, editor.selectionEnd)
    }

    @Test fun aChangeTheGateRefusesNeverReachesTheTextOrTheCore() {
        ic.commitText("kept", 1)
        // The view model's gate refuses what would pass the limit (tested with 20 MiB in
        // ShellViewModelTest); here a host that refuses everything stands in for it.
        val refusing = object : EditorHost by vm.host {
            override fun fits(start: Int, end: Int, insert: String) = false
        }
        editor.bind(refusing, vm.highlight!!, vm.faces)
        ic.commitText(" more", 1)
        ic.deleteSurroundingText(2, 0)
        editor.onTextContextMenuItem(android.R.id.selectAll)
        assertEquals("kept", editor.text.toString())
        inStep()
    }

    @Test fun randomKeyboardInputInEverySpotlightModeKeepsEverythingInStep() {
        val pieces = listOf("a", " ", ". ", "\n", "\n\n", "*", "`", "```", "# ", "é", "’", "😀")
        val random = kotlin.random.Random(4)
        for (focus in FocusMode.entries) {
            vm.updatePreferences(vm.preferences.copy(focus = focus))
            repeat(150) {
                val length = editor.text.length
                val at = random.nextInt(length + 1).let { if (it > 0 && Character.isLowSurrogate(editor.text[minOf(it, length - 1)]) && it < length) it - 1 else it }
                editor.setSelection(at)
                when (random.nextInt(4)) {
                    0 -> ic.deleteSurroundingTextInCodePoints(1, 0)
                    1 -> { ic.setComposingText(pieces.random(random), 1); ic.finishComposingText() }
                    else -> ic.commitText(pieces.random(random), 1)
                }
                inStep()
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            idle()
            Thread.sleep(5)
        }
    }
}
