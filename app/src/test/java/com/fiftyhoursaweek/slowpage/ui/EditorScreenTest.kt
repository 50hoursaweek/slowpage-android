package com.fiftyhoursaweek.slowpage.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.fiftyhoursaweek.slowpage.Screen
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.SlowpageApp
import com.fiftyhoursaweek.slowpage.editor.Command
import com.fiftyhoursaweek.slowpage.editor.EditorText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.hushhour.ViewMode
import java.time.Duration

/**
 * The page on the 11-inch tablet (V1, V2, V6, F1): the three views by tap and by keyboard,
 * the full Preview's label and Edit button, the hint, and focus on open. The emulator check
 * is the one that counts for focus and the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land")
class EditorScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: ShellViewModel

    private fun show() {
        val app = RuntimeEnvironment.getApplication() as SlowpageApp
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(app, SavedStateHandle()) as T
        }
        vm = ViewModelProvider(store, factory)[ShellViewModel::class.java]
        until("ready") { vm.preferences.size > 0 }
        vm.newDocument()
        until("a document opens") { vm.screen is Screen.Editor && vm.highlight != null }
        vm.accept("# Heading\n\nSome text with a [link](https://example.com).")
        compose.setContent {
            SlowpageTheme(vm.preferences.theme) {
                EditorScreen(vm, (vm.screen as Screen.Editor).name, vm.status, onExport = {}, onImport = {})
            }
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
    }

    @After fun tearDown() = store.clear()

    private fun editor(): EditorText = compose.activity.window.decorView.findEditor()!!

    private fun android.view.View.findEditor(): EditorText? = when (this) {
        is EditorText -> this
        is android.view.ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findEditor() }
        else -> null
    }

    @Test fun theEditorHasTheTextAndTheFocusWhenTheDocumentOpens() {
        show()
        idle()
        compose.runOnIdle {
            assertEquals(vm.editorText(), editor().text.toString())
            assertTrue("F1: the caret is in the text", editor().hasFocus())
        }
    }

    @Test fun theThreeViewsCycleByTapAndByKeyboard() {
        show()
        compose.onNodeWithTag(VIEW_TAG).performClick()
        compose.runOnIdle { assertEquals(ViewMode.SPLIT, vm.viewMode) }
        compose.onNodeWithTag(PREVIEW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(EDITOR_TAG).assertIsDisplayed()
        compose.runOnIdle { vm.command(Command.VIEW) }
        compose.runOnIdle { assertEquals(ViewMode.PREVIEW, vm.viewMode) }
        compose.onNodeWithTag(BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(VIEW_TAG).performClick()
        compose.runOnIdle { assertEquals(ViewMode.EDITOR, vm.viewMode) }
    }

    @Test fun splitSitsSideBySideAtTheTabletsWidth() {
        show()
        compose.runOnIdle { vm.setView(ViewMode.SPLIT) }
        val editor = compose.onNodeWithTag(EDITOR_TAG).getBoundsInRoot()
        val preview = compose.onNodeWithTag(PREVIEW_TAG).getBoundsInRoot()
        assertTrue("side by side above 1000 dp: $editor $preview", preview.left >= editor.right)
    }

    @Config(qualifiers = "w800dp-h1280dp-port")
    @Test fun splitStacksBelow1000Dp() {
        show()
        compose.runOnIdle { vm.setView(ViewMode.SPLIT) }
        val editor = compose.onNodeWithTag(EDITOR_TAG).getBoundsInRoot()
        val preview = compose.onNodeWithTag(PREVIEW_TAG).getBoundsInRoot()
        assertTrue("stacked below 1000 dp: $editor $preview", preview.top >= editor.bottom)
    }

    @Test fun fullPreviewShowsTheLabelTheHighlightedModeAndAnEditButtonThatGoesBack() {
        show()
        compose.runOnIdle { vm.setView(ViewMode.PREVIEW) }
        until("the preview renders") { vm.preview != null }
        compose.onNodeWithTag(BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(VIEW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(EDIT_TAG).performClick()
        compose.runOnIdle { assertEquals(ViewMode.EDITOR, vm.viewMode) }
    }

    @Test fun typingOrTappingInThePreviewShowsAHintNotNothing() {
        show()
        compose.runOnIdle { vm.setView(ViewMode.PREVIEW) }
        until("the preview renders") { vm.preview != null }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(Offset(centerX, bottom - 120f)) }
        compose.waitForIdle()
        compose.onNodeWithTag(HINT_TAG).assertIsDisplayed()
        compose.runOnIdle { assertTrue(vm.hint!!.contains("Edit")) }
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
