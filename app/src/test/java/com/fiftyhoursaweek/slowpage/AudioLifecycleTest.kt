package com.fiftyhoursaweek.slowpage

import android.content.res.Configuration
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.fiftyhoursaweek.slowpage.audio.AudioController
import com.fiftyhoursaweek.slowpage.audio.AudioControllerTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.hushhour.MusicChoice
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Phase 5: a rotation never interrupts or restarts the music (Gate A restarted it); leaving
 * the screen stops the stream (A8) and coming back goes on, through the real activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class AudioLifecycleTest {
    private val lane = Executors.newSingleThreadScheduledExecutor { Thread(it, "audio-test") }
    private val mixer = AudioControllerTest.FakeMixer().apply { active = true }
    private val output = AudioControllerTest.FakeOutput()

    @After fun tearDown() {
        lane.shutdownNow()
    }

    @Test fun aRotationLeavesTheMusicPlayingAndLeavingTheScreenStopsIt() {
        val app = RuntimeEnvironment.getApplication() as SlowpageApp
        app.audio = AudioController({ mixer }, output, lane)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val vm = ViewModelProvider(controller.get())[ShellViewModel::class.java]
        until("ready") { vm.loaded }
        vm.newDocument()
        until("a document") { vm.screen is Screen.Editor }
        vm.setMusic(MusicChoice.Rain)
        until("it plays") { output.all() == listOf("start") }

        val portrait = Configuration(controller.get().resources.configuration).apply { orientation = Configuration.ORIENTATION_PORTRAIT }
        val before = controller.get()
        controller.configurationChange(portrait)
        idle()
        assertTrue("the activity was made again, as on a tablet", controller.get() !== before)
        assertEquals("the rotation neither stopped nor restarted the stream", listOf("start"), output.all())
        assertEquals("nor chose the track again", listOf("music ${MusicChoice.Rain}"), mixer.music())

        controller.pause().stop()
        until("leaving the screen stops it") { output.all() == listOf("start", "stop") }
        controller.start().resume()
        until("coming back goes on") { output.all() == listOf("start", "stop", "start") }
        assertEquals(listOf("music ${MusicChoice.Rain}"), mixer.music())
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        lane.submit {}.get()
    }

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            idle()
            Thread.sleep(5)
        }
    }
}
