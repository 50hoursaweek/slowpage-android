package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import uniffi.hushhour.Rgb
import uniffi.hushhour.Theme
import uniffi.hushhour.palette

private fun Rgb.color() = Color(r, g, b)

/** Material colours from the core palette (`palette.rs`), so Light, Sepia and Dark match the iPad. */
@Composable
fun SlowpageTheme(theme: Theme, content: @Composable () -> Unit) {
    val scheme = remember(theme) {
        val p = palette(theme)
        val base = if (theme == Theme.DARK) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = p.primary.color(),
            onPrimary = p.background.color(),
            background = p.background.color(),
            onBackground = p.text.color(),
            surface = p.background.color(),
            onSurface = p.text.color(),
            surfaceVariant = p.background.color(),
            onSurfaceVariant = p.text.color().copy(alpha = 0.7f),
            surfaceContainer = p.background.color(),
            surfaceContainerHigh = p.background.color(),
            surfaceContainerHighest = p.background.color(),
            error = p.danger.color(),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
