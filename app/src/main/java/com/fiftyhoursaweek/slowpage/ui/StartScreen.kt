package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fiftyhoursaweek.slowpage.store.DocumentEntry
import java.text.DateFormat
import java.util.Date

/** New, Import…, Settings; the recent list; every document in app storage (D10). */
@Composable
fun StartScreen(
    documents: List<DocumentEntry>,
    recent: List<String>,
    problems: List<String>,
    music: String,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
            item {
                Text("Slowpage", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 32.dp))
                if (music.isNotEmpty()) {
                    Text(music, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onNew) { Text("New") }
                    OutlinedButton(onClick = onImport) { Text("Import…") }
                    OutlinedButton(onClick = onSettings) { Text("Settings") }
                }
                for (line in problems) {
                    Text(line, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            if (recent.isNotEmpty()) {
                item { Heading("Recent") }
                items(recent, key = { "recent/$it" }) { name ->
                    Text(
                        name,
                        Modifier.fillMaxWidth().clickable { onOpen(name) }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            item { Heading("Documents") }
            if (documents.isEmpty()) {
                item {
                    Text(
                        "No documents yet. New makes one; Import… brings in a file from elsewhere.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(documents, key = { "doc/${it.name}" }) { doc ->
                DocumentRow(doc, onOpen = { onOpen(doc.name) }, onExport = { onExport(doc.name) }, onDelete = { confirmDelete = doc.name })
                HorizontalDivider()
            }
        }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “$name”?") },
            text = { Text("It is removed from this tablet. Export it first to keep a copy elsewhere.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(name) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun DocumentRow(doc: DocumentEntry, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(doc.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(doc.modified))} · ${size(doc.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋯") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Export…") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Delete…") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
