package com.fiftyhoursaweek.slowpage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import uniffi.hushhour.AudioEngine
import uniffi.hushhour.MusicChoice
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The Oboe output stream; a seam so the threading can be tested without audio. */
interface MusicOutput {
    /** Opens and starts the stream (blocking: the audio server may be slow); false if it could not. */
    fun start(): Boolean
    fun stop()
}

private object NativeOutput : MusicOutput {
    override fun start() = NativeAudio.start()
    override fun stop() = NativeAudio.stop()
}

/**
 * The background music as Gate A left it: one bundled track through the Rust mixer and Oboe,
 * started in onStart, stopped in onStop. Audio rules (start silent, interruptions, lifecycle,
 * rotation) are phase 5. Called on the main thread only.
 *
 * Opening a stream can block in the audio server (Codex phase 3, ANR): setup, start and stop
 * all run in order on one music thread, never on the main thread. A [stop] makes any start
 * still queued a no-op, and the stop after it stops whatever did start.
 */
class Music internal constructor(
    private val prepare: (volume: () -> Float) -> Unit,
    private val output: MusicOutput,
    private val lane: ExecutorService,
) {
    constructor(context: Context) : this(
        prepare = { volume ->
            val engine = engine(context.applicationContext)
            engine.setMusic(MusicChoice.Bundled(TRACK))
            engine.setMusicVolume(volume())
        },
        output = NativeOutput,
        lane = LANE,
    )

    private val generation = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    /** The latest volume asked for; setup reads it after the engine exists, so none is lost. */
    @Volatile private var volume = 1f

    /** The stream starts on the music thread unless a [stop] came first; [onResult] runs on the main thread. */
    fun start(volume: Float, onResult: (String) -> Unit) {
        this.volume = volume
        val current = generation.incrementAndGet()
        lane.execute {
            if (current != generation.get()) return@execute
            val prepared = runCatching { prepare { this.volume } }
            prepared.exceptionOrNull()?.let { Log.e(TAG, "music setup failed", it) }
            if (current != generation.get()) return@execute
            val result = when {
                prepared.isFailure -> "Music could not start: ${prepared.exceptionOrNull()}"
                output.start() -> "Playing ${TRACK.removeSuffix(".ogg")}"
                else -> "Music could not start: no audio output"
            }
            main.post { if (current == generation.get()) onResult(result) }
        }
    }

    /** Returns at once; the stream stops on the music thread, after any start queued before. */
    fun stop() {
        generation.incrementAndGet()
        lane.execute { output.stop() }
    }

    fun setVolume(volume: Float) {
        this.volume = volume
        shared?.setMusicVolume(volume)
    }

    companion object {
        private const val TAG = "SlowpageMusic"
        private const val TRACK = "Late cafe.ogg"

        /** One for the process, like the native player it drives. */
        private val LANE: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "music") }

        // One engine for the process: creating one replaces the mixer behind the C render export.
        @Volatile private var shared: AudioEngine? = null

        private fun engine(context: Context): AudioEngine = shared ?: synchronized(this) {
            shared ?: AudioEngine(
                MusicFiles.install(context).path,
                MusicFiles.userDir(context).path,
            ).also { shared = it }
        }
    }
}
