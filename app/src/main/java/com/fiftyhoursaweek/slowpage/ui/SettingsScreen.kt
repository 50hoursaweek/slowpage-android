package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uniffi.hushhour.Preferences
import uniffi.hushhour.fontLabel
import uniffi.hushhour.limits
import uniffi.hushhour.nextFont
import uniffi.hushhour.nextTheme
import uniffi.hushhour.themeLabel

/**
 * Settings skeleton: the core's preferences (`prefs.rs`, same file format as Linux and iPad),
 * saved on every change. Theme, size, line height, typing sound and the volumes apply now; the
 * rest is stored for the phases that use it (dialogs 6).
 */
@Composable
fun SettingsScreen(
    preferences: Preferences,
    problems: List<String>,
    onChange: (Preferences) -> Unit,
    onBack: () -> Unit,
) {
    val limits = limits()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            TextButton(onClick = onBack) { Text("‹ Documents") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 12.dp))
            for (line in problems) Text(line, color = MaterialTheme.colorScheme.error)

            Choice("Theme", themeLabel(preferences.theme)) { onChange(preferences.copy(theme = nextTheme(preferences.theme))) }
            Choice("Font", fontLabel(preferences.font)) { onChange(preferences.copy(font = nextFont(preferences.font))) }
            Level("Text size: ${preferences.size.toInt()}", preferences.size, limits.minSize..limits.maxSize) {
                onChange(preferences.copy(size = it))
            }
            Level("Line height: %.2f".format(preferences.lineHeight), preferences.lineHeight, limits.minLineHeight..limits.maxLineHeight) {
                onChange(preferences.copy(lineHeight = it))
            }
            Toggle("Typing sound", preferences.typingSound) { onChange(preferences.copy(typingSound = it)) }
            Level("Typing sound volume", preferences.soundVolume, 0f..1f) { onChange(preferences.copy(soundVolume = it)) }
            Level("Music volume", preferences.musicVolume, 0f..1f) { onChange(preferences.copy(musicVolume = it)) }
        }
    }
}

@Composable
private fun Choice(label: String, value: String, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = onNext) { Text(value) }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Level(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}
