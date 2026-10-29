package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hushhour.Rgb
import uniffi.hushhour.Theme
import uniffi.hushhour.palette

/** F2: every colour pair the screens use is readable in Light, Sepia and Dark (real core palette). */
class ThemeTest {
    private fun Color.rgb() = Rgb(red, green, blue)
    private fun ratio(a: Color, b: Color) = Colours.contrast(a.rgb(), b.rgb())

    private fun hex(c: Rgb) = "#%02X%02X%02X".format(Math.round(c.r * 255), Math.round(c.g * 255), Math.round(c.b * 255))

    @Test fun linksMatchLinuxAndReachAA() {
        val expected = mapOf(Theme.LIGHT to "#1673B6", Theme.SEPIA to "#965B24", Theme.DARK to "#1D99F3")
        for ((theme, value) in expected) {
            val p = palette(theme)
            assertEquals(theme.name, value, hex(Colours.link(p)))
            assertTrue(theme.name, Colours.contrast(Colours.link(p), p.background) >= 4.5)
        }
    }

    @Test fun everyTextColourIsReadableInEveryTheme() {
        for (theme in Theme.entries) {
            val s: ColorScheme = colorSchemeFor(theme)
            val pairs = mapOf(
                "text on page" to (s.onBackground to s.background),
                "text on surface" to (s.onSurface to s.surface),
                "secondary text" to (s.onSurfaceVariant to s.background),
                "accent (buttons, links) on page" to (s.primary to s.background),
                "label on filled button" to (s.onPrimary to s.primary),
                "error on page" to (s.error to s.background),
                "menu and dialog text" to (s.onSurface to s.surfaceContainerHigh),
                "dialog text (highest)" to (s.onSurface to s.surfaceContainerHighest),
                "snackbar" to (s.inverseOnSurface to s.inverseSurface),
                "snackbar action" to (s.inversePrimary to s.inverseSurface),
            )
            for ((what, pair) in pairs) {
                val r = ratio(pair.first, pair.second)
                assertTrue("$theme: $what is %.2f:1".format(r), r >= 4.5)
            }
        }
    }
}
