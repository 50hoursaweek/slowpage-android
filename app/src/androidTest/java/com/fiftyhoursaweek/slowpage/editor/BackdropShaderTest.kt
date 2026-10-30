package com.fiftyhoursaweek.slowpage.editor

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.hushhour.Backdrop
import uniffi.hushhour.Theme
import uniffi.hushhour.backdropIntensity
import uniffi.hushhour.backdropTiming
import uniffi.hushhour.palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On a device or the emulator (E16): the GLSL port of the core's shader against the core's
 * reference, as the iPad's `BackdropTests` hold its Metal port to it, and the colour it shows.
 * Run with `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class BackdropShaderTest {
    private val renderer = BackdropRenderer()
    private val patterns = listOf(Backdrop.WAVES, Backdrop.SWELL, Backdrop.DRIFT)

    @After fun tearDown() = renderer.release()

    @Test fun theGlslPortMatchesTheCoreReferenceForEveryPattern() {
        val (w, h) = 480 to 320
        val page = palette(Theme.LIGHT)
        for (backdrop in patterns) {
            for ((time, scale) in listOf(0f to 2f, 7f to 2f, 61.3f to 1f, 3600.25f to 2f)) {
                val pixels = renderer.read(BackdropParams(backdrop, page.background, page.text, w, h, time, scale), raw = true)
                assertNotNull("OpenGL ES 3.0 is there", pixels)
                var worst = 0f
                var drawn = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = (y * w + x) * 4
                        val k = BackdropRenderer.intensity(pixels!!.get(i), pixels.get(i + 1))
                        // Fragments are shaded at pixel centres.
                        val reference = backdropIntensity(backdrop, x + 0.5f, y + 0.5f, w.toFloat(), h.toFloat(), time, scale)
                        worst = max(worst, abs(k - reference))
                        if (reference > 0) drawn++
                    }
                }
                Log.i("BackdropShaderTest", "BACKDROP PORT $backdrop t=$time scale=$scale: worst difference $worst")
                assertTrue("$backdrop at t = $time, scale $scale: worst difference $worst", worst < 0.01f)
                assertTrue("$backdrop draws something at t = $time", drawn > w * h / 100)
            }
        }
    }

    @Test fun theColourIsThePageMixedTowardsTheTextInSRGB() {
        val (w, h) = 240 to 160
        val strength = backdropTiming().strength
        for (theme in listOf(Theme.LIGHT, Theme.SEPIA, Theme.DARK)) {
            val p = palette(theme)
            for (backdrop in patterns) {
                val pixels = renderer.read(BackdropParams(backdrop, p.background, p.text, w, h, 7f, 2f), raw = false)!!
                var worst = 0
                for (y in 0 until h step 3) {
                    for (x in 0 until w step 3) {
                        val k = backdropIntensity(backdrop, x + 0.5f, y + 0.5f, w.toFloat(), h.toFloat(), 7f, 2f)
                        val t = (k * strength).coerceIn(0f, 1f)
                        val expected = listOf(
                            p.background.r + (p.text.r - p.background.r) * t,
                            p.background.g + (p.text.g - p.background.g) * t,
                            p.background.b + (p.text.b - p.background.b) * t,
                        )
                        for (c in 0 until 3) {
                            val got = pixels.get((y * w + x) * 4 + c).toInt() and 0xFF
                            worst = max(worst, abs(got - (expected[c] * 255).roundToInt()))
                        }
                    }
                }
                assertTrue("$theme $backdrop: off by $worst levels", worst <= 1)
            }
        }
    }
}
