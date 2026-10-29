package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import android.util.Log
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A plain text area for phase 3; the real editor (IME matrix, highlighting, measure,
 * typewriter, undo keys) is phase 4. The top line: back to Documents, the name, the save
 * state (D3: a failed save is shown with its reason), Export….
 *
 * The field is the state-based `BasicTextField`: it scrolls itself and keeps the caret in
 * view while typing (F4), and [guard] sees every edit before it lands (the core's limit).
 * It covers the whole page, margins included, so a tap anywhere focuses it (F1).
 */
@Composable
fun EditorScreen(
    name: String,
    state: TextFieldState,
    guard: InputTransformation,
    status: String,
    readOnly: Boolean,
    fontFamily: FontFamily,
    textSize: Float,
    lineHeight: Float,
    onClose: () -> Unit,
    onExport: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    // F1: the Android view that hosts Compose must hold focus too, or the keyboard does not come
    // up; on the Huawei tablet a tap did not give it focus (arrow keys did). Ask for it directly.
    fun focusEditor(why: String) {
        view.isFocusableInTouchMode = true
        val viewTook = view.isFocused || view.requestFocus()
        focus.requestFocus()
        keyboard?.show()
        Log.i(TAG, "focus ($why): view=$viewTook touchMode=${view.isInTouchMode} window=${view.hasWindowFocus()}")
    }
    // F1: on New and on Open the caret is in the text with the keyboard up.
    LaunchedEffect(name) { focusEditor("open") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, enabled = !readOnly) { Text("‹ Documents") }
            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                status,
                color = if (status.startsWith("Not saved")) colors.error else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            TextButton(onClick = onExport) { Text("Export…") }
        }
        BasicTextField(
            state = state,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focus)
                // Watches each tap without taking it, so the field still places the caret.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (waitForUpOrCancellation(PointerEventPass.Initial) != null) focusEditor("tap")
                    }
                }
                .testTag(EDITOR_TAG),
            readOnly = readOnly,
            inputTransformation = guard,
            textStyle = TextStyle(
                color = colors.onBackground,
                fontFamily = fontFamily,
                fontSize = textSize.sp,
                lineHeight = (textSize * lineHeight).sp,
            ),
            cursorBrush = SolidColor(colors.onBackground),
            scrollState = scroll,
            decorator = TextFieldDecorator { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp)) {
                        inner()
                    }
                }
            },
        )
    }
}

private const val TAG = "SlowpageEditor"

/** Test tag of the text area. */
const val EDITOR_TAG = "editor"
