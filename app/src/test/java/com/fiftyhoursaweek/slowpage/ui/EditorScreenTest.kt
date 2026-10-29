package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F1: a tap anywhere on the page focuses the text; New and Open start with it focused. Taps
 * are placed on the window (the root), not on the field's own bounds, as a finger lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")  // the 11-inch tablet, landscape
class EditorScreenTest {
    @get:Rule val compose = createComposeRule()

    private var value by mutableStateOf(TextFieldValue("Hello"))
    private lateinit var focusManager: FocusManager

    private fun show(name: String = "Untitled.md") {
        compose.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                EditorScreen(
                    name = name,
                    field = value,
                    status = "Saved",
                    readOnly = false,
                    textSize = 18f,
                    lineHeight = 1.5f,
                    onChange = { value = it },
                    onClose = {},
                    onExport = {},
                )
            }
        }
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
        compose.runOnIdle { assertEquals(5, value.selection.start) }
    }

    @Test fun aTapInTheSideMarginFocusesTheText() {
        show()
        unfocus()
        compose.onRoot().performTouchInput { click(Offset(20f, centerY)) }
        compose.onNodeWithTag(EDITOR_TAG).assertIsFocused()
    }

    @Test fun anEmptyNewDocumentTakesTypingAfterATapAnywhere() {
        value = TextFieldValue("")
        show()
        unfocus()
        compose.onRoot().performTouchInput { click(Offset(right - 40f, bottom - 40f)) }
        compose.onNodeWithTag(EDITOR_TAG).assertIsFocused()
        compose.onNodeWithTag(EDITOR_TAG).performTextInput("First line")
        compose.runOnIdle { assertEquals("First line", value.text) }
    }
}
