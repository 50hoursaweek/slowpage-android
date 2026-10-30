package com.fiftyhoursaweek.slowpage.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fiftyhoursaweek.slowpage.MusicFiles
import com.fiftyhoursaweek.slowpage.NativeAudio
import uniffi.hushhour.AudioEngine
import uniffi.hushhour.KeyKind
import uniffi.hushhour.MusicChoice
import uniffi.hushhour.Preferences
import uniffi.hushhour.SoundSet
import uniffi.hushhour.builtInMusic
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The core's mixer controls (`AudioEngine`); a seam so the rules can be tested without the core's audio. */
interface Mixer {
    fun playKey(kind: KeyKind, set: SoundSet, volume: Float)
    fun setMusic(music: MusicChoice)
    fun setMusicVolume(volume: Float)
    /** True while a key sound or a music layer (a fade too) is rendered. */
    fun isActive(): Boolean
    /** Bundled tracks, then the user's. */
    fun tracks(): List<MusicChoice>
}

/** The Oboe output stream (`cpp/audio.cpp`); a seam so the threading can be tested without audio. */
interface AudioOutput {
    /** Opens and starts the stream (blocking: the audio server may be slow); false if it could not. */
    fun start(): Boolean
    fun stop()
    /**
     * Whether the stream may be heard (the app is in front); from the main thread, at once and
     * without blocking. While not, a stream renders silence and is never reopened, so a start
     * still under way when the app leaves the screen is not heard (Codex phase 5, BLOCKING 1).
     */
    fun allow(allowed: Boolean)
}

/**
 * Typing sounds and music (phase 5, A1–A8), one for the process: the iPad's `AudioController`
 * on Android. The core's mixer renders into the Oboe stream through its C export (A1, in
 * `audio.cpp`); this decides what it plays and when the stream runs.
 * - **Starts silent (A4):** the music preference is never saved, and the route is silenced
 *   until a document opens.
 * - **Only with a document (A5):** [releaseMusic] when one opens, [silenceUntilDocument] when
 *   it closes; the checked track stays checked.
 * - **Idle (A7):** the stream starts on the first sound and stops three seconds after the
 *   mixer goes quiet.
 * - **In the background (A8):** [background] silences the output at once and stops the
 *   stream (onStop), also one still opening; [foreground] starts it again if music was
 *   playing, where it was. A rotation is neither: the activity skips both, so the music goes
 *   on (Gate A restarted it).
 * - **Threads:** the public calls are for the main thread. The core and the stream are used
 *   only on one audio thread ([lane]), in order: opening a stream can block in the audio
 *   server (Codex phase 3, ANR), and choosing a track opens a file.
 */
class AudioController internal constructor(
    private val newMixer: () -> Mixer,
    private val output: AudioOutput,
    private val lane: ScheduledExecutorService,
    private val idleTickMillis: Long = IDLE_TICK_MS,
) {
    private val main = Handler(Looper.getMainLooper())

    // ---- Main thread
    private var preferences: Preferences? = null
    /** Silenced until the first document opens (A5). */
    private val route = MusicRoute().apply { silence() }
    /** What the core was last told to play (tests read it). */
    var playingMusic: MusicChoice = MusicChoice.Off
        private set
    /** Told when a sample starts, changes or ends (the tour's buttons, phase 6). */
    var onSampleChange: ((MusicChoice?) -> Unit)? = null
    /** Told once when sound cannot start (no output, or the core's engine failed). */
    var onProblem: ((String) -> Unit)? = null
    private val endSample = Runnable { stopSample() }

    // ---- Shared with the audio thread
    @Volatile private var foreground = true
    @Volatile private var musicVolume = 0.5f

    // ---- Audio thread only
    private var mixer: Mixer? = null
    private var mixerFailed = false
    private var running = false
    private var outputFailed = false
    private var idle: ScheduledFuture<*>? = null
    private var quiet = 0

    /** The preferences now (on start and after every change): music, volumes, the sound set previewed. */
    fun preferencesChanged(p: Preferences) {
        val last = preferences
        preferences = p
        musicVolume = p.musicVolume
        if (last == null) {
            onLane { it.setMusicVolume(p.musicVolume) }
            applyMusic()
            return
        }
        if (p.music != last.music) applyMusic()
        if (p.musicVolume != last.musicVolume) onLane { it.setMusicVolume(p.musicVolume) }
        // A new sound set or level is heard at once, as on Linux and the iPad.
        if (p.typingSound && p.soundVolume > 0f && (p.soundSet != last.soundSet || p.soundVolume != last.soundVolume || !last.typingSound)) {
            key(KeyKind.CHARACTER, p)
        }
    }

    /** One key sound, if typing sounds are on; silent keys are dropped (A2). */
    fun playKey(kind: KeyKind) {
        val p = preferences ?: return
        if (!p.typingSound || p.soundVolume <= 0f) return
        key(kind, p)
    }

    /** A document is open: the checked track plays (or nothing, if none is checked). */
    fun releaseMusic() {
        route.release()
        applyMusic()
    }

    /** No document is open: silence until one opens. The checked track stays checked. */
    fun silenceUntilDocument() {
        route.silence()
        applyMusic()
    }

    /** From now until [releaseMusic], a newly checked track waits; what plays keeps playing (the tour, phase 6). */
    fun holdMusic() {
        route.hold(playingMusic)
    }

    /** A short sample of [choice], one at a time, over everything; it ends by itself after [seconds]. */
    fun playSample(choice: MusicChoice, seconds: Double = SAMPLE_SECONDS) {
        main.removeCallbacks(endSample)
        route.sample = choice
        applyMusic()
        onSampleChange?.invoke(choice)
        main.postDelayed(endSample, (seconds * 1000).toLong())
    }

    fun stopSample() {
        main.removeCallbacks(endSample)
        if (route.sample == null) return
        route.sample = null
        applyMusic()
        onSampleChange?.invoke(null)
    }

    /** The app is in front again (onStart): music that was playing goes on where it was. */
    fun foreground() {
        foreground = true
        output.allow(true)
        val music = playingMusic
        lane.execute { if (music != MusicChoice.Off) ensureRunning() }
    }

    /**
     * The app left the screen (onStop, not a rotation): nothing is heard from now on, and the
     * stream stops (A8). Returns at once, even while the audio thread is opening a stream.
     */
    fun background() {
        foreground = false
        output.allow(false)
        lane.execute { stopStream() }
    }

    /** All choices for the picker, in the Linux order (Off, bundled loops, soundscapes, own tracks), given on the main thread. */
    fun choices(then: (List<MusicChoice>) -> Unit) {
        onLane { m ->
            val tracks = m.tracks()
            val all = listOf(MusicChoice.Off) + tracks.filterIsInstance<MusicChoice.Bundled>() +
                builtInMusic().filter { it != MusicChoice.Off } + tracks.filterIsInstance<MusicChoice.File>()
            main.post { then(all) }
        }
    }

    private fun applyMusic() {
        val p = preferences ?: return
        val target = route.target(p.music)
        if (target == playingMusic) return
        playingMusic = target
        onLane {
            it.setMusic(target)
            if (target != MusicChoice.Off) ensureRunning()
        }
    }

    private fun key(kind: KeyKind, p: Preferences) {
        val set = p.soundSet
        val volume = p.soundVolume
        onLane {
            it.playKey(kind, set, volume)
            ensureRunning()
        }
    }

    // ---- Audio thread

    private fun onLane(block: (Mixer) -> Unit) = lane.execute {
        val m = mixer() ?: return@execute
        try {
            block(m)
        } catch (e: Exception) {
            Log.e(TAG, "sound failed", e)
        }
    }

    private fun mixer(): Mixer? {
        mixer?.let { return it }
        if (mixerFailed) return null
        return try {
            newMixer().also {
                it.setMusicVolume(musicVolume)
                mixer = it
            }
        } catch (e: Throwable) {
            mixerFailed = true
            Log.e(TAG, "the sound engine could not start", e)
            main.post { onProblem?.invoke("Sound could not start: ${e.message ?: e}") }
            null
        }
    }

    private fun ensureRunning() {
        if (!foreground) return
        if (!running) {
            running = try {
                output.start()
            } catch (e: Throwable) {
                Log.e(TAG, "the audio output could not start", e)
                false
            }
            if (!running) {
                if (!outputFailed) {
                    outputFailed = true
                    main.post { onProblem?.invoke("Sound could not start: no audio output.") }
                }
                return
            }
            // The app left the screen while the stream opened: it was never heard, and goes now.
            if (!foreground) {
                stopStream()
                return
            }
            Log.i(TAG, "audio stream started")
        }
        quiet = 0
        if (idle == null) idle = lane.scheduleWithFixedDelay(::tick, idleTickMillis, idleTickMillis, TimeUnit.MILLISECONDS)
    }

    /** Once a second: the stream stops after three quiet seconds (A7). */
    private fun tick() {
        quiet = if (mixer?.isActive() == true) 0 else quiet + 1
        if (quiet >= IDLE_TICKS) {
            Log.i(TAG, "audio idle, stream stopped")
            stopStream()
        }
    }

    private fun stopStream() {
        idle?.cancel(false)
        idle = null
        quiet = 0
        if (!running) return
        running = false
        try {
            output.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "the audio output could not stop", e)
        }
    }

    companion object {
        private const val TAG = "SlowpageAudio"
        const val IDLE_TICK_MS = 1000L
        const val IDLE_TICKS = 3
        /** How long a sample from the welcome tour plays (Francesco: about 10 s). */
        const val SAMPLE_SECONDS = 10.0

        fun create(context: Context): AudioController {
            val app = context.applicationContext
            val lane = Executors.newSingleThreadScheduledExecutor { Thread(it, "audio") }
            return AudioController({ CoreMixer(app) }, NativeOutput, lane)
        }
    }
}

/**
 * The core's `AudioEngine`. The bundled loops are copied out of the APK only when one is first
 * played (A3); the picker lists them from the APK (Codex phase 5, MINOR 4).
 */
private class CoreMixer(private val context: Context) : Mixer {
    private val engine = AudioEngine(MusicFiles.bundledDir(context).path, MusicFiles.userDir(context).path)
    private var installed = false

    private fun install() {
        if (installed) return
        MusicFiles.install(context)
        installed = true
    }

    override fun playKey(kind: KeyKind, set: SoundSet, volume: Float) = engine.playKey(kind, set, volume)
    override fun setMusic(music: MusicChoice) {
        if (music is MusicChoice.Bundled) install()
        engine.setMusic(music)
    }
    override fun setMusicVolume(volume: Float) = engine.setMusicVolume(volume)
    override fun isActive() = engine.isActive()
    override fun tracks(): List<MusicChoice> =
        MusicFiles.bundledNames(context).map { MusicChoice.Bundled(it) } + engine.tracks().filterIsInstance<MusicChoice.File>()
}

/**
 * The Oboe stream. [allow] comes from the main thread and only sets a flag in the native
 * player; the library is loaded on the audio thread, by the first [start], and told the flag then.
 */
private object NativeOutput : AudioOutput {
    private var loaded = false
    private var allowed = true

    override fun start(): Boolean {
        NativeAudio.load()
        synchronized(this) {
            loaded = true
            NativeAudio.allow(allowed)
        }
        return NativeAudio.start()
    }

    override fun stop() = NativeAudio.stop()

    override fun allow(allowed: Boolean) = synchronized(this) {
        this.allowed = allowed
        if (loaded) NativeAudio.allow(allowed)
    }
}
