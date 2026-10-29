package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F1: a tap anywhere on the page focuses the text; New and Open start with it focused. Taps
 * are placed on the window (the root), not on the field's own bounds, as a finger lands.
 * F4: typing past the bottom keeps the caret in view. These pass on the JVM; the emulator
 * check (audit/phase-3/emulator/) is the one that counts, since F1 passed here and failed on
 * the tablet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land")  // the 11-inch tablet, landscape
class EditorScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = TextFieldState("Hello")
    private val scroll = ScrollState(0)
    private lateinit var focusManager: FocusManager

    private fun show(name: String = "Untitled.md") {
        compose.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                EditorScreen(
                    name = name,
                    state = state,
                    guard = InputTransformation { },
                    status = "Saved",
                    readOnly = false,
                    fontFamily = FontFamily.Default,
                    textSize = 18f,
                    lineHeight = 1.5f,
                    onClose = {},
                    onExport = {},
                    scroll = scroll,
                )
            }
        }
        // As on a device: the window has focus (Robolectric's does not by default), which
        // the text field needs before it shows a caret and scrolls to it.
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
    }

    private fun unfocus() {
        compose.runOnIdle { focusManager.clearFocus() }
        compose.onNodeWithTag(EDITOR_TAG).assertIsNotFocused()
    }

    @Test fun theTextIsFocusedWhenTheDocumentOpens() {
        show()
        compose.onNodeWithTag(EDITOR_TAG).assertIsFocused()
    }

    @Test fun aTapBelowTheTextFocusesItAndPutsTheCaretAtTheEnd() {
        show()
        unfocus()
        compose.onRoot().performTouchInput { click(Offset(centerX, bottom - 40f)) }
        compose.onNodeWithTag(EDITOR_TAG).assertIsFocused()
        compose.runOnIdle { assertEquals(5, state.selection.start) }
    }

    @Test fun aTapInTheSideMarginFocusesTheText() {
        show()
        unfocus()
        compose.onRoot().performTouchInput { click(Offset(20f, centerY)) }
        compose.onNodeWithTag(EDITOR_TAG).assertIsFocused()
    }

    @Test fun anEmptyNewDocumentTakesTypingAfterATapAnywhere() {
        state.edit { replace(0, length, "") }
        show()
        unfocus()
        compose.onRoot().performTouchInput { click(Offset(right - 40f, bottom - 40f)) }
        compose.onNodeWithTag(EDITOR_TAG).assertIsFocused()
        compose.onNodeWithTag(EDITOR_TAG).performTextInput("First line")
        compose.runOnIdle { assertEquals("First line", state.text.toString()) }
    }

    // Robolectric does not run the field's bring-into-view: scroll stays 0 here, while on the
    // emulator the caret stays in view (audit/phase-3/emulator/10-f4-final.png). Kept for when it does.
    @Ignore("Robolectric does not scroll the field; checked on the emulator instead")
    @Test fun typingPastTheBottomScrollsToKeepTheCaretInView() {
        state.edit { replace(0, length, (1..200).joinToString("\n") { "Line $it" }) }
        show()
        compose.runOnIdle { assertEquals(0, scroll.value) }
        compose.runOnIdle { state.edit { placeCursorAtEnd() } }
        compose.onNodeWithTag(EDITOR_TAG).performTextInput("\nOne more")
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("max ${scroll.maxValue}", scroll.maxValue > 0)
            assertEquals(scroll.maxValue, scroll.value)
        }
    }
}
