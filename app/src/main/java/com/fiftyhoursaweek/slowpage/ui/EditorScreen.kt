package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A plain text area for phase 3; the real editor (IME matrix, highlighting, measure,
 * typewriter, undo keys) is phase 4. The top line: back to Documents, the name, the save
 * state (D3: a failed save is shown with its reason), Export….
 */
@Composable
fun EditorScreen(
    name: String,
    field: TextFieldValue,
    status: String,
    readOnly: Boolean,
    textSize: Float,
    lineHeight: Float,
    onChange: (TextFieldValue) -> Unit,
    onClose: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
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
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            BasicTextField(
                value = field,
                onValueChange = onChange,
                readOnly = readOnly,
                textStyle = TextStyle(color = colors.onBackground, fontSize = textSize.sp, lineHeight = (textSize * lineHeight).sp),
                cursorBrush = SolidColor(colors.onBackground),
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            )
        }
    }
}
