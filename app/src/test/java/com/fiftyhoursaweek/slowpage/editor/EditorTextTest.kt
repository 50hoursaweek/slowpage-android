package com.fiftyhoursaweek.slowpage.editor

import android.os.Looper
import android.view.LayoutInflater
import android.view.KeyEvent
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
import org.junit.Assert.assertNull
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
import uniffi.hushhour.ByteRange
import uniffi.hushhour.FocusMode
import uniffi.hushhour.FontChoice
import uniffi.hushhour.Position
import uniffi.hushhour.TextChange
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
        val page = LayoutInflater.from(activity).inflate(R.layout.editor_text, null, false)
        editor = page.findViewById(R.id.editor)
        activity.setContentView(page)
        editor.bind(vm.host, vm.highlight!!, vm.faces)
        editor.setLook(Look(Theme.LIGHT, FontChoice.MONO, 36f, 1.5f), 100, 40)
        editor.load(vm.editorText(), 0, 0)
        vm.attach(editor)
        page.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, 1280, 800)
        editor.requestFocus()
        ic = editor.onCreateInputConnection(EditorInfo())!!
    }

    @After fun tearDown() = store.clear()

    private fun inStep() {
        assertEquals(editor.text.toString(), vm.editorText())
        assertEquals(LineTable(editor.text).starts(), vm.highlight!!.lines.starts())
        assertEquals("never half a character", wholeCharacters(editor.text), editor.text.toString())
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

    @Test fun aKeyboardDeletingHalfAnEmojiDeletesAllOfIt() {
        // Round 1, BLOCKING 1: deleteSurroundingText counts UTF-16 units, so (1, 0) after an
        // emoji removes only its low surrogate; the core would remove the whole emoji.
        ic.commitText("a😀", 1)
        ic.deleteSurroundingText(1, 0)
        assertEquals("a", editor.text.toString())
        assertEquals(1, editor.selectionEnd)
        inStep()
        ic.commitText("😀b", 1)
        editor.setSelection(1)
        ic.deleteSurroundingText(0, 1)  // the high surrogate, forward
        assertEquals("ab", editor.text.toString())
        inStep()
        // The caret is never left between the two halves; an insertion there goes after the emoji.
        ic.commitText("😀", 1)
        editor.setSelection(2)
        assertEquals(3, editor.selectionEnd)
        ic.commitText("x", 1)
        assertEquals("a😀xb", editor.text.toString())
        inStep()
        val emoji = editor.text.indexOf("😀")
        editor.text.insert(emoji + 1, "y")  // as a keyboard could, whatever the caret
        idle()
        assertEquals("a😀yxb", editor.text.toString())
        inStep()
        // Pasted half characters become U+FFFD.
        editor.setSelection(editor.text.length)
        ic.commitText("c\uD83D", 1)
        assertEquals("a😀yxbc\uFFFD", editor.text.toString())
        inStep()
        idle()
    }

    @Test fun aLongTextGoesInAPieceAtATimeAndTheEditorWaitsForIt() {
        // Round 1, F3 and BLOCKING 3: a long text is laid out a piece at a time.
        val line = "A line of prose for the long text, with **bold** and `code`.\n"
        val long = line.repeat(3 * 256 * 1024 / line.length + 7)
        assertTrue(vm.accept(long))
        var ready = false
        editor.load(vm.editorText(), 5, 5) { ready = true }
        assertTrue(editor.loading)
        assertTrue("only the first piece", editor.text.length < long.length)
        assertEquals("the first piece ends at a line break", '\n', editor.text[editor.text.length - 1])
        assertTrue("no input until it is all in", vm.editorBusy)
        ic.commitText("refused", 1)
        until("all in") { !editor.loading && ready }
        assertTrue(!vm.editorBusy)
        assertEquals(long, editor.text.toString())
        assertEquals(5, editor.selectionEnd)
        inStep()
        ic.commitText("x", 1)
        assertEquals(long.substring(0, 5) + "x" + long.substring(5), vm.editorText())
        inStep()
        // Round 2: undo waits while a text goes in, rather than putting the rest in at once.
        editor.load(vm.editorText(), 0, 0)
        assertTrue(editor.loading)
        vm.command(Command.UNDO)
        assertTrue("still going in, a piece at a time", editor.loading)
        until("all in") { !editor.loading }
        assertEquals("nothing undone", long.substring(0, 5) + "x" + long.substring(5), vm.editorText())
        inStep()
        // A change from the core that comes anyway starts the core's text again, still in pieces.
        editor.load(vm.editorText(), 0, 0)
        editor.apply(TextChange(ByteRange(0u, 0u), ByteRange(0u, 0u), "", Position(0u, 0u), 0u, 0u, 0u, 0u))
        assertTrue(editor.loading)
        until("all in") { !editor.loading }
        inStep()
    }

    @Test fun typingDuringALayoutAgainIsPausedVisiblyAndNeverLost() {
        // Round 2, BLOCKING: a focused editor laid out again (Split, zoom, a font) took keys
        // while its text went back in, and dropped them.
        val line = "Prose typed into while it is laid out again.\n"
        val long = line.repeat(2 * 256 * 1024 / line.length + 3)
        assertTrue(vm.accept(long))
        editor.load(vm.editorText(), 10, 10)
        until("all in") { !editor.loading }
        editor.requestFocus()
        assertTrue(editor.hasFocus())
        editor.setLook(Look(Theme.LIGHT, FontChoice.SERIF, 36f, 1.5f), 100, 40)  // a font: laid out again
        assertTrue(editor.loading)
        assertTrue("the screen says input waits", vm.editorBusy)
        assertNull("the keyboard is disconnected", editor.onCreateInputConnection(EditorInfo()))
        assertTrue("the focus stays (a button with it would take a hardware Enter)", editor.hasFocus())
        // Whatever a keyboard still sends changes neither the view nor the core.
        ic.commitText("lost?", 1)
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB)) {
            assertTrue(editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
            editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        assertEquals(long, vm.editorText())
        assertTrue(editor.hasFocus())
        until("all in") { !editor.loading }
        assertTrue("input is back, with the focus", editor.hasFocus() && !vm.editorBusy)
        assertEquals(10, editor.selectionEnd)
        ic = editor.onCreateInputConnection(EditorInfo())!!
        ic.commitText("typed", 1)
        assertEquals(long.substring(0, 10) + "typed" + long.substring(10), vm.editorText())
        inStep()
    }

    @Test fun aLongTextLaidOutAgainGoesBackInPiecesAndKeepsTheCaret() {
        // Round 1, F2: Split (a new width), zoom or a font lays a long text out again.
        val line = "Prose for the long text, laid out again at a new width.\n"
        val long = line.repeat(2 * 256 * 1024 / line.length + 3)
        assertTrue(vm.accept(long))
        editor.load(vm.editorText(), 1000, 1000)
        until("all in") { !editor.loading }
        editor.setLook(Look(Theme.LIGHT, FontChoice.MONO, 36f, 1.5f), 300, 40)  // narrower, as in Split
        assertTrue(editor.loading)
        editor.setLook(Look(Theme.DARK, FontChoice.MONO, 36f, 1.5f), 300, 40)  // a theme alone lays nothing out
        until("all in again") { !editor.loading }
        assertEquals(long, editor.text.toString())
        assertEquals(1000, editor.selectionEnd)
        inStep()
        // The anchor's space is the page's padding: the editor's own padding stays the insets.
        editor.typewriter = true
        assertEquals(40, editor.paddingTop)
        assertTrue(!editor.loading)
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
                when (random.nextInt(5)) {
                    0 -> ic.deleteSurroundingTextInCodePoints(1, 0)
                    // In UTF-16 units, as many keyboards send it: may split an emoji (BLOCKING 1).
                    1 -> ic.deleteSurroundingText(random.nextInt(3), random.nextInt(2))
                    2 -> { ic.setComposingText(pieces.random(random), 1); ic.finishComposingText() }
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
