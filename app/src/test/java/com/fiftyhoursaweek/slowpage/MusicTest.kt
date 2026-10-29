package com.fiftyhoursaweek.slowpage

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Codex phase 3 (ANR): the stream is opened and stopped off the main thread, in order. */
@RunWith(RobolectricTestRunner::class)
class MusicTest {
    private val lane = Executors.newSingleThreadExecutor { Thread(it, "music-test") }

    private class FakeOutput(private val openMillis: Long = 0) : MusicOutput {
        val calls = mutableListOf<String>()
        val threads = mutableSetOf<String>()
        override fun start(): Boolean {
            threads += Thread.currentThread().name
            Thread.sleep(openMillis)
            synchronized(calls) { calls += "start" }
            return true
        }
        override fun stop() {
            threads += Thread.currentThread().name
            synchronized(calls) { calls += "stop" }
        }
    }

    @After fun tearDown() {
        lane.shutdownNow()
    }

    private fun drain() {
        lane.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun startAndStopRunOnTheMusicThreadAndReportOnTheMainThread() {
        val out = FakeOutput()
        val music = Music({}, out, lane)
        var result: String? = null
        var resultThread: String? = null
        music.start(0.5f) {
            result = it
            resultThread = Thread.currentThread().name
        }
        drain()
        music.stop()
        drain()
        assertEquals(listOf("start", "stop"), out.calls)
        assertEquals(setOf("music-test"), out.threads)
        assertEquals("Playing Late cafe", result)
        assertEquals(Looper.getMainLooper().thread.name, resultThread)
    }

    @Test fun aSlowOpenDoesNotHoldTheCaller() {
        val out = FakeOutput(openMillis = 2_000)
        val music = Music({}, out, lane)
        val began = System.nanoTime()
        music.start(1f) {}
        Thread.sleep(50)  // the open is under way on the music thread
        music.stop()      // onStop: must not wait for it
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        assertTrue("start and stop took $took ms", took < 500)
        drain()
        assertEquals(listOf("start", "stop"), out.calls)  // the stop still comes after the open
    }

    @Test fun aStopBeforeTheStartRunsMeansNoStart() {
        val out = FakeOutput()
        val music = Music({}, out, lane)
        val hold = CountDownLatch(1)
        lane.execute { hold.await() }
        var reported = false
        music.start(1f) { reported = true }
        music.stop()
        hold.countDown()
        drain()
        assertEquals(listOf("stop"), out.calls)
        assertTrue(!reported)
    }

    @Test fun theLatestVolumeIsTheOneSetUp() {
        var seen = -1f
        val hold = CountDownLatch(1)
        lane.execute { hold.await() }
        val music = Music({ volume -> seen = volume() }, FakeOutput(), lane)
        music.start(0.2f) {}
        music.setVolume(0.7f)  // the stored volume arrives while setup waits
        hold.countDown()
        drain()
        assertEquals(0.7f, seen)
    }
}
