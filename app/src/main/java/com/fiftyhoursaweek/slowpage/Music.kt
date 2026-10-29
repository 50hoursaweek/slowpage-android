package com.fiftyhoursaweek.slowpage

import android.content.Context
import android.util.Log
import uniffi.hushhour.AudioEngine
import uniffi.hushhour.MusicChoice
import kotlin.concurrent.thread

/**
 * The background music as Gate A left it: one bundled track through the Rust mixer and Oboe,
 * started in onStart, stopped in onStop. Audio rules (start silent, interruptions, lifecycle,
 * rotation) are phase 5. Called on the main thread only.
 */
class Music(private val context: Context) {
    private var generation = 0

    /** Setup runs off the main thread; the stream starts only if no [stop] came meanwhile. */
    fun start(volume: Float, onResult: (String) -> Unit) {
        val current = ++generation
        thread(name = "music-start") {
            val prepared = runCatching {
                val engine = engine(context.applicationContext)
                engine.setMusicVolume(volume)
                engine.setMusic(MusicChoice.Bundled(TRACK))
            }
            prepared.exceptionOrNull()?.let { Log.e(TAG, "music setup failed", it) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (current != generation) return@post
                onResult(
                    when {
                        prepared.isFailure -> "Music could not start: ${prepared.exceptionOrNull()}"
                        NativeAudio.start() -> "Playing ${TRACK.removeSuffix(".ogg")}"
                        else -> "Music could not start: no audio output"
                    },
                )
            }
        }
    }

    fun stop() {
        generation++
        NativeAudio.stop()
    }

    fun setVolume(volume: Float) {
        shared?.setMusicVolume(volume)
    }

    companion object {
        private const val TAG = "SlowpageMusic"
        private const val TRACK = "Late cafe.ogg"

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
