package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import uniffi.hushhour.Palette
import uniffi.hushhour.Rgb
import uniffi.hushhour.Theme
import uniffi.hushhour.palette

private fun Rgb.color() = Color(r, g, b)

/**
 * Material colours from the core palette (`palette.rs`), so Light, Sepia and Dark match the
 * iPad. Every role a screen uses gets a palette colour: foregrounds the text colour (secondary
 * text a blend of it), accents the link colour (the primary darkened to 4.5:1, E7), errors the
 * danger colour, containers the page.
 */
fun colorSchemeFor(theme: Theme, p: Palette = palette(theme)): ColorScheme {
    val page = p.background.color()
    val text = p.text.color()
    val secondary = blend(text, page, 0.72f)
    val accent = Colours.link(p).color()
    val base = if (theme == Theme.DARK) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = page,
        primaryContainer = page,
        onPrimaryContainer = text,
        secondary = accent,
        onSecondary = page,
        secondaryContainer = blend(accent, page, 0.18f),
        onSecondaryContainer = text,
        background = page,
        onBackground = text,
        surface = page,
        onSurface = text,
        surfaceVariant = blend(text, page, 0.08f),
        onSurfaceVariant = secondary,
        surfaceContainerLowest = page,
        surfaceContainerLow = page,
        surfaceContainer = page,
        surfaceContainerHigh = blend(text, page, 0.05f),
        surfaceContainerHighest = blend(text, page, 0.08f),
        inverseSurface = text,
        inverseOnSurface = page,
        inversePrimary = page,  // snackbar actions, on inverseSurface (the text colour)
        outline = blend(text, page, 0.5f),
        outlineVariant = blend(text, page, 0.2f),
        error = p.danger.color(),
        onError = page,
        errorContainer = page,
        onErrorContainer = p.danger.color(),
    )
}

/** [a] over [b] at [amount] (0 = b, 1 = a), opaque. */
fun blend(a: Color, b: Color, amount: Float) = Color(
    red = b.red + (a.red - b.red) * amount,
    green = b.green + (a.green - b.green) * amount,
    blue = b.blue + (a.blue - b.blue) * amount,
)

@Composable
fun SlowpageTheme(theme: Theme, content: @Composable () -> Unit) {
    val scheme = remember(theme) { colorSchemeFor(theme) }
    MaterialTheme(colorScheme = scheme) {
        // F2: Text outside a Surface takes LocalContentColor, which is black by default;
        // give it the page's text colour, so plain text is readable in every theme.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
