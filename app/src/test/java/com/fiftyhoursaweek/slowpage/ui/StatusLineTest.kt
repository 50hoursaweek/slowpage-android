package com.fiftyhoursaweek.slowpage.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.fiftyhoursaweek.slowpage.Screen
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.SlowpageApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The status line on a phone (Francesco's phone test, 2026-11-02): at 360 dp it is one row that
 * scrolls sideways, by a swipe or by the ‹ and › at its ends, each shown only when there is more
 * that way, and no item wraps. On the tablet everything fits: no arrows, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-port")
class StatusLineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: ShellViewModel

    private val items = listOf(STATS_TAG, VIEW_TAG, "spotlight", "anchor", "font", "theme")

    private fun show() {
        val app = RuntimeEnvironment.getApplication() as SlowpageApp
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(app, SavedStateHandle()) as T
        }
        vm = ViewModelProvider(store, factory)[ShellViewModel::class.java]
        until("ready") { vm.loaded }
        vm.newDocument()
        until("a document opens") { vm.screen is Screen.Editor && vm.highlight != null }
        vm.accept("Some words to count.")
        until("the counts") { vm.stats.isNotEmpty() }
        compose.setContent {
            SlowpageTheme(vm.preferences.theme) {
                EditorScreen(vm, (vm.screen as Screen.Editor).name, vm.status, onExport = {}, onImport = {})
            }
        }
        compose.waitForIdle()
    }

    @After fun tearDown() = store.clear()

    /** Where the item lies, also past the row's edge (the bounds in root are clipped to it). */
    private fun bounds(tag: String): DpRect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val at = node.positionInRoot
        return with(compose.density) { DpRect(at.x.toDp(), at.y.toDp(), (at.x + node.size.width).toDp(), (at.y + node.size.height).toDp()) }
    }
    private fun shown(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun row() = bounds(STATUS_ROW_TAG)

    /** The items in one line, in order, none broken over lines. */
    private fun assertOneLine() {
        val boxes = items.map { it to bounds(it) }
        fun middle(box: DpRect) = (box.top + box.bottom) / 2
        val line = middle(boxes.first().second)
        for ((tag, box) in boxes) {
            assertTrue("$tag on the row's line: $box", (middle(box) - line).value in -4f..4f)
            assertTrue("$tag is one line, not wrapped: $box", box.bottom - box.top < 50.dp)
        }
        boxes.zipWithNext { (a, x), (b, y) -> assertTrue("$a before $b: $x $y", y.left >= x.right - 1.dp) }
    }

    @Test fun atPhoneWidthTheRowScrollsAndNothingWraps() {
        show()
        assertOneLine()
        assertTrue("the theme switch lies beyond the right edge at first", bounds("theme").right > row().right)
        assertTrue("› shown: more to the right", shown(STATUS_FORWARD_TAG))
        assertTrue("‹ hidden: nothing to the left", !shown(STATUS_BACK_TAG))
        assertTrue("the counts are visible", bounds(STATS_TAG).left >= row().left)

        // Each tap goes about a screen's width; the end is reached, then the arrows swap.
        val start = bounds("theme").left
        compose.onNodeWithTag(STATUS_FORWARD_TAG).performClick()
        compose.waitForIdle()
        assertTrue("› scrolled the row: ${bounds("theme").left} < $start", bounds("theme").left < start)
        assertTrue("‹ shown after scrolling", shown(STATUS_BACK_TAG))
        var taps = 0
        while (shown(STATUS_FORWARD_TAG) && taps++ < 5) {
            compose.onNodeWithTag(STATUS_FORWARD_TAG).performClick()
            compose.waitForIdle()
        }
        assertTrue("› hidden at the end", !shown(STATUS_FORWARD_TAG))
        compose.onNodeWithTag("theme").assertIsDisplayed()
        assertTrue("the theme switch inside the row at the end", bounds("theme").right <= row().right + 1.dp)
        assertOneLine()
        // The › place stays, blank: a tap there at the end does not reach the theme switch.
        val theme = vm.preferences.theme
        val right = compose.onRoot().getBoundsInRoot().right
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset((right - 20.dp).toPx(), bounds("theme").let { (it.top + it.bottom) / 2 }.toPx())) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("a tap where › was leaves the theme", theme, vm.preferences.theme) }

        while (shown(STATUS_BACK_TAG) && taps++ < 10) {
            compose.onNodeWithTag(STATUS_BACK_TAG).performClick()
            compose.waitForIdle()
        }
        assertTrue("‹ hidden back at the start", !shown(STATUS_BACK_TAG))
        assertTrue("› shown again", shown(STATUS_FORWARD_TAG))
        compose.onNodeWithTag(STATS_TAG).assertIsDisplayed()
    }

    @Test fun aSwipeScrollsTheRowToo() {
        show()
        compose.onNodeWithTag(STATUS_ROW_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        // At 360 dp the row runs only a little past the edge: one swipe takes it to the end.
        assertTrue("‹ shown after the swipe", shown(STATUS_BACK_TAG))
        assertTrue("› hidden at the end", !shown(STATUS_FORWARD_TAG))
        assertTrue("the theme switch inside the row: ${bounds("theme")} ${row()}", bounds("theme").right <= row().right + 1.dp)
    }

    @Test fun theSwitchesStillWorkAfterScrolling() {
        show()
        val theme = vm.preferences.theme
        compose.onNodeWithTag(STATUS_ROW_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("theme").performClick()
        compose.runOnIdle { assertTrue("the theme switched", vm.preferences.theme != theme) }
    }

    /** The 11-inch tablet in landscape: it all fits, so no arrows, counts left and switches right. */
    @Config(qualifiers = "w1280dp-h800dp-land")
    @Test fun onTheTabletItAllFitsWithNoArrows() {
        show()
        assertTrue(!shown(STATUS_FORWARD_TAG) && !shown(STATUS_BACK_TAG))
        assertOneLine()
        val root = compose.onRoot().getBoundsInRoot()
        assertEquals("counts at the left edge", 12f, (bounds(STATS_TAG).left - root.left).value, 1f)
        assertEquals("theme at the right edge", 12f, (root.right - bounds("theme").right).value, 1f)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            idle()
            Thread.sleep(5)
        }
    }
}
