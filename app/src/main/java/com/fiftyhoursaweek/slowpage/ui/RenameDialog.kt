package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.fiftyhoursaweek.slowpage.store.Names

const val RENAME_FIELD_TAG = "renameField"
const val RENAME_BUTTON_TAG = "renameButton"

/**
 * The Rename dialog of the start screen's ⋯ menu and the editor's Menu (R1), the iPad's
 * `DocumentRename.alert`: one field with the current name, without its extension, selected so
 * typing replaces it; Cancel, and Rename, which Return does too. The name is checked after.
 */
@Composable
fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val base = Names.split(current).first
    var field by rememberSaveable(current, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(base, TextRange(0, base.length)))
    }
    val focus = remember { FocusRequester() }
    val rename = { onDismiss(); onRename(field.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ImmersiveWindow(); Text("Rename") },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { rename() }),
                modifier = Modifier.focusRequester(focus).testTag(RENAME_FIELD_TAG),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = rename, modifier = Modifier.testTag(RENAME_BUTTON_TAG)) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
