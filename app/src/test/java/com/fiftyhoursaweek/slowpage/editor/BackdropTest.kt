package com.fiftyhoursaweek.slowpage.editor

import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.fiftyhoursaweek.slowpage.ui.Colours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import uniffi.hushhour.Backdrop
import uniffi.hushhour.Theme
import uniffi.hushhour.backdropTiming
import uniffi.hushhour.defaultPreferences
import uniffi.hushhour.palette
import uniffi.hushhour.parsePreferences
import uniffi.hushhour.serializePreferences
import com.fiftyhoursaweek.slowpage.ui.colorSchemeFor

/**
 * The background pattern (E16), the iPad's `BackdropTests` that need no GPU: off by default,
 * running only when it should, at rest while typing and moving again after a pause, and the
 * text over it keeping its contrast. The shader against the core's reference runs on the
 * emulator (androidTest `BackdropShaderTest`).
 */
@RunWith(RobolectricTestRunner::class)
class BackdropTest {
    private fun attached(view: BackdropView): BackdropView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContentView(FrameLayout(activity).apply { addView(view) })
        return view
    }

    @Test fun itIsOffByDefaultAndRunsOnlyWhenItShould() {
        assertEquals("off by default", Backdrop.OFF, defaultPreferences().backdrop)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val b = BackdropView(activity)
        assertFalse(b.isAnimating)
        activity.setContentView(FrameLayout(activity).apply { addView(b) })
        b.reduceMotion = false
        b.lowPower = false
        assertFalse("Off: no ticks", b.isAnimating)
        b.backdrop = Backdrop.WAVES
        assertTrue(b.isAnimating)
        assertEquals("15 frames a second while moving", backdropTiming().frameIntervalMs.toLong(), b.tickInterval)
        val drawn = b.frames
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertTrue("it draws on its own", b.frames >= drawn + 3)

        b.reduceMotion = true
        assertFalse("Remove animations: still", b.isAnimating)
        val still = b.frames
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertEquals("nothing drawn while still", still, b.frames)
        b.reduceMotion = false
        assertTrue(b.isAnimating)
        b.lowPower = true
        assertFalse("Battery saver: still", b.isAnimating)
        b.lowPower = false
        b.inFront = false
        assertFalse("the activity not in front: no ticks", b.isAnimating)
        b.inFront = true
        assertTrue(b.isAnimating)
        b.backdrop = Backdrop.OFF
        assertFalse(b.isAnimating)
        b.backdrop = Backdrop.DRIFT
        assertTrue(b.isAnimating)
        (b.parent as FrameLayout).removeView(b)
        assertFalse("off-screen: no ticks", b.isAnimating)
    }

    @Test fun typingBringsThePatternToRestAndItResumesAfterAPause() {
        var now = 1000.0
        var typed = 0L
        val b = attached(BackdropView(Robolectric.buildActivity(ComponentActivity::class.java).setup().get(), BackdropClock { now }))
        b.reduceMotion = false
        b.lowPower = false
        b.typedAt = { typed }
        b.backdrop = Backdrop.SWELL
        b.tick()  // the first tick only starts the clock
        val clock = { b.clockForTests() }
        val start = clock()
        now += 0.2
        b.tick()
        assertEquals("moves with real time", 0.2f, clock() - start, 1e-4f)

        // Typing every 66 ms for a second and a half.
        repeat(24) {
            now += 0.066
            typed += 66
            b.tick()
        }
        assertTrue("at rest while typing", b.restingForTests())
        assertEquals("and ticking slowly", backdropTiming().restingIntervalMs.toLong(), b.tickInterval)
        val rested = clock()
        val framesAtRest = b.frames
        repeat(50) { i ->
            now += 0.1
            typed += 100
            if (i % 2 == 1) b.tick()
        }
        assertEquals("standing still", rested, clock())
        assertEquals("typing at rest draws nothing", framesAtRest, b.frames)

        repeat(90) {
            now += 0.066
            b.tick()
        }
        assertFalse(b.restingForTests())
        assertEquals(backdropTiming().frameIntervalMs.toLong(), b.tickInterval)
        val moving = b.frames
        repeat(5) {
            now += 0.066
            b.tick()
        }
        assertEquals("one frame per tick while moving", 5, b.frames - moving)
    }

    /** WCAG contrast of the text and the secondary text over the pattern's peak (Waves 1, Swell 1.2, Drift 3, times the strength), and of the bars' muted text on their plain page colour. */
    @Test fun theTextKeepsItsContrastOverThePatternInEveryTheme() {
        val strength = backdropTiming().strength
        for (theme in listOf(Theme.LIGHT, Theme.SEPIA, Theme.DARK)) {
            val p = palette(theme)
            val muted = Colours.muted(p)
            assertTrue("$theme: muted UI text on the page reaches AA", Colours.contrast(muted, p.background) >= 4.5)
            // The top line's secondary text (the save state), on its page-coloured bar.
            val scheme = colorSchemeFor(theme, p)
            val top = scheme.onSurfaceVariant.let { uniffi.hushhour.Rgb(it.red, it.green, it.blue) }
            assertTrue("$theme: the top line", Colours.contrast(top, p.background) >= 4.5)
            for ((backdrop, peak) in listOf(Backdrop.WAVES to 1f, Backdrop.SWELL to 1.2f, Backdrop.DRIFT to 3f)) {
                val pattern = Colours.mix(p.background, p.text, peak * strength)
                assertTrue("$theme $backdrop: body text", Colours.contrast(p.text, pattern) >= 4.5)
                assertTrue("$theme $backdrop: secondary text", Colours.contrast(Colours.secondary(p), pattern) >= 4.5)
            }
        }
    }

    @Test fun theBackgroundIsSavedAndTheViewIsNot() {
        val p = defaultPreferences().copy(backdrop = Backdrop.DRIFT, view = uniffi.hushhour.ViewMode.PREVIEW)
        val back = parsePreferences(serializePreferences(p))
        assertEquals(Backdrop.DRIFT, back.backdrop)
        assertEquals(uniffi.hushhour.ViewMode.EDITOR, back.view)
    }

}
