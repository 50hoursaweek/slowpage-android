package com.fiftyhoursaweek.slowpage.audio

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import uniffi.hushhour.KeyKind
import uniffi.hushhour.MusicChoice
import uniffi.hushhour.SoundSet
import uniffi.hushhour.defaultPreferences
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Phase 5 (A2, A4, A5, A6, A7, A8): what plays, when the stream runs, and on which thread. */
@RunWith(RobolectricTestRunner::class)
class AudioControllerTest {
    private val lane = Executors.newSingleThreadScheduledExecutor { Thread(it, "audio-test") }
    private val mixer = FakeMixer()
    private val output = FakeOutput()
    private val audio = AudioController({ mixer }, output, lane, idleTickMillis = 20)
    private val rain = MusicChoice.Rain

    class FakeMixer : Mixer {
        val calls = mutableListOf<String>()
        val threads = mutableSetOf<String>()
        @Volatile var active = false
        private fun record(call: String) = synchronized(calls) {
            calls += call
            threads += Thread.currentThread().name
        }
        override fun playKey(kind: KeyKind, set: SoundSet, volume: Float) = record("key $kind $set $volume")
        override fun setMusic(music: MusicChoice) = record("music $music")
        override fun setMusicVolume(volume: Float) = record("volume $volume")
        override fun isActive() = active
        override fun tracks() = listOf(MusicChoice.Bundled("Late cafe.ogg"), MusicChoice.File("mine.mp3"))
        fun music() = synchronized(calls) { calls.filter { it.startsWith("music") } }
        fun keys() = synchronized(calls) { calls.filter { it.startsWith("key") } }
    }

    class FakeOutput(@Volatile var openMillis: Long = 0, @Volatile var works: Boolean = true) : AudioOutput {
        val calls = mutableListOf<String>()
        val threads = mutableSetOf<String>()
        override fun start(): Boolean {
            threads += Thread.currentThread().name
            Thread.sleep(openMillis)
            synchronized(calls) { calls += "start" }
            return works
        }
        override fun stop() {
            threads += Thread.currentThread().name
            synchronized(calls) { calls += "stop" }
        }
        fun all() = synchronized(calls) { calls.toList() }
    }

    @After fun tearDown() {
        lane.shutdownNow()
    }

    private fun drain() {
        lane.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            Thread.sleep(10)
        }
    }

    @Test fun theAppStartsSilentAndACheckedTrackWaitsForADocument() {
        audio.preferencesChanged(defaultPreferences())
        // A4: nothing plays at start; A5: a track checked with no document open waits.
        audio.preferencesChanged(defaultPreferences().copy(music = rain))
        drain()
        assertEquals(MusicChoice.Off, audio.playingMusic)
        assertEquals(emptyList<String>(), mixer.music())
        assertEquals("no stream for silence", emptyList<String>(), output.all())

        audio.releaseMusic()  // a document opened
        drain()
        assertEquals(rain, audio.playingMusic)
        assertEquals(listOf("music $rain"), mixer.music())
        assertEquals(listOf("start"), output.all())

        audio.silenceUntilDocument()  // it closed
        drain()
        assertEquals(MusicChoice.Off, audio.playingMusic)
        audio.releaseMusic()  // the next one: the track is still checked
        drain()
        assertEquals(listOf("music $rain", "music ${MusicChoice.Off}", "music $rain"), mixer.music())
    }

    @Test fun leavingTheScreenStopsTheStreamAndComingBackGoesOnWithoutRestartingTheTrack() {
        mixer.active = true
        audio.preferencesChanged(defaultPreferences().copy(music = rain))
        audio.releaseMusic()
        drain()
        audio.background()  // onStop (A8)
        drain()
        assertEquals(listOf("start", "stop"), output.all())
        audio.foreground()  // onStart
        drain()
        assertEquals(listOf("start", "stop", "start"), output.all())
        assertEquals("the track is not chosen again: it goes on where it was", listOf("music $rain"), mixer.music())
        // A rotation is neither (the activity skips both); onStart alone changes nothing.
        audio.foreground()
        drain()
        assertEquals(listOf("start", "stop", "start"), output.all())
    }

    @Test fun keySoundsFollowThePreferencesAndTheStreamStopsWhenQuiet() {
        val p = defaultPreferences()
        audio.preferencesChanged(p.copy(typingSound = false))
        audio.playKey(KeyKind.CHARACTER)
        audio.preferencesChanged(p.copy(typingSound = true, soundVolume = 0f))
        audio.playKey(KeyKind.ENTER)
        drain()
        assertEquals("off, or silent: dropped (A2)", emptyList<String>(), mixer.keys())
        // Turning the sound on, or a new set or level, is heard at once.
        audio.preferencesChanged(p.copy(typingSound = true, soundSet = SoundSet.KEYBOARD, soundVolume = 0.6f))
        audio.playKey(KeyKind.BACKSPACE)
        drain()
        assertEquals(listOf("key CHARACTER KEYBOARD 0.6", "key BACKSPACE KEYBOARD 0.6"), mixer.keys())
        assertEquals(listOf("start"), output.all())
        // Quiet for three ticks: the stream stops (A7); the next key starts it again.
        until("the idle stop") { output.all() == listOf("start", "stop") }
        audio.playKey(KeyKind.CHARACTER)
        drain()
        assertEquals(listOf("start", "stop", "start"), output.all())
    }

    @Test fun musicKeepsTheStreamRunning() {
        mixer.active = true
        audio.preferencesChanged(defaultPreferences().copy(music = rain))
        audio.releaseMusic()
        drain()
        Thread.sleep(300)  // fifteen ticks
        drain()
        assertEquals(listOf("start"), output.all())
    }

    @Test fun aSamplePlaysAloneEndsByItselfAndIsReplacedByTheNext() {
        audio.preferencesChanged(defaultPreferences())
        val told = mutableListOf<MusicChoice?>()
        audio.onSampleChange = { told += it }
        audio.playSample(rain, seconds = 0.4)
        assertEquals("plays at once, over the silence before a document", rain, audio.playingMusic)
        audio.playSample(MusicChoice.Night, seconds = 0.4)
        assertEquals("one at a time", MusicChoice.Night, audio.playingMusic)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals("it ends by itself", MusicChoice.Off, audio.playingMusic)
        assertEquals(listOf(rain, MusicChoice.Night, null), told)
        audio.playSample(rain, seconds = 30.0)
        audio.stopSample()
        assertEquals(MusicChoice.Off, audio.playingMusic)
    }

    @Test fun theCoreAndTheStreamAreUsedOnlyOnTheAudioThreadAndASlowOpenNeverHoldsTheCaller() {
        output.openMillis = 2_000
        audio.preferencesChanged(defaultPreferences().copy(music = rain))
        val began = System.nanoTime()
        audio.releaseMusic()
        audio.playKey(KeyKind.CHARACTER)
        Thread.sleep(50)  // the open is under way
        audio.background()  // onStop must not wait for it
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        assertTrue("the calls took $took ms", took < 500)
        drain()
        assertEquals("the stop still comes after the open", listOf("start", "stop"), output.all())
        assertEquals(setOf("audio-test"), mixer.threads)
        assertEquals(setOf("audio-test"), output.threads)
    }

    @Test fun noAudioOutputIsReportedOnce() {
        output.works = false
        val problems = mutableListOf<String>()
        audio.onProblem = { problems += it }
        audio.preferencesChanged(defaultPreferences())
        repeat(3) { audio.playKey(KeyKind.CHARACTER) }
        drain()
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("no audio output"))
    }

    @Test fun thePickerListsOffTheLoopsTheSoundscapesAndOwnTracksInThatOrder() {
        audio.preferencesChanged(defaultPreferences())
        var choices: List<MusicChoice> = emptyList()
        audio.choices { choices = it }
        drain()
        assertEquals(MusicChoice.Off, choices.first())
        assertEquals(MusicChoice.Bundled("Late cafe.ogg"), choices[1])
        assertEquals(MusicChoice.File("mine.mp3"), choices.last())
        assertEquals("the soundscapes in the core's order", uniffi.hushhour.builtInMusic().filter { it != MusicChoice.Off }, choices.subList(2, 7))
        assertFalse(MusicChoice.Off in choices.drop(1))
    }
}
