package com.fiftyhoursaweek.slowpage.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.hushhour.Theme

/** F2: plain Text (no Surface around it) takes the page's text colour, not Material's black. */
@RunWith(RobolectricTestRunner::class)
class ThemeContentColorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun plainTextTakesTheThemesTextColourInEveryTheme() {
        val seen = mutableMapOf<Theme, Color>()
        var theme by androidx.compose.runtime.mutableStateOf(Theme.LIGHT)
        compose.setContent {
            SlowpageTheme(theme) {
                seen[theme] = LocalContentColor.current
                Text("sample")
            }
        }
        for (t in Theme.entries) {
            compose.runOnIdle { theme = t }
            compose.waitForIdle()
            assertEquals(t.name, colorSchemeFor(t).onBackground, seen[t])
        }
    }
}
