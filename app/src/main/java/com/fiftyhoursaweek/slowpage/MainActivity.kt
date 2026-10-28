package com.fiftyhoursaweek.slowpage

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import uniffi.hushhour.AudioEngine
import uniffi.hushhour.MusicChoice
import kotlin.concurrent.thread

/**
 * Gate A spike, no UI: installs the bundled music, plays one track through the Rust mixer and
 * Oboe, and shows the stream facts and output level once a second (also in logcat, tag
 * "HushHourGateA").
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lines = listOf<String>()

    private val tick = object : Runnable {
        override fun run() {
            if (started) {
                val r = NativeAudio.read()
                val line = "stream ${r.streamRate} Hz (${r.api}), burst ${r.framesPerBurst}, " +
                    "max callback ${r.maxCallbackFrames}, frames ${r.frames}, " +
                    "peak %.3f, rms %.4f".format(r.peak, r.rms)
                Log.i(TAG, line)
                show(line)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(48, 48, 48, 48); textSize = 16f }
        setContentView(status)
    }

    override fun onStart() {
        super.onStart()
        thread(name = "gate-a-start") {
            val message = try {
                val engine = engine(applicationContext)
                engine.setMusicVolume(0.5f)  // the core's default (prefs.rs)
                engine.setMusic(MusicChoice.Bundled(TRACK))
                if (NativeAudio.start()) {
                    started = true
                    "playing $TRACK; tracks: ${engine.tracks().size}; device rate ${deviceRate()} Hz"
                } else {
                    "Oboe could not open the output stream (see logcat HushHourAudio)"
                }
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                "start failed: $e"
            }
            Log.i(TAG, message)
            runOnUiThread { header = message; show(null) }
        }
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        started = false
        NativeAudio.stop()
        super.onStop()
    }

    private var header = "starting"

    private fun show(line: String?) {
        if (line != null) lines = (lines + line).takeLast(12)
        status.text = (listOf("Slowpage Gate A", header, "") + lines).joinToString("\n")
    }

    private fun deviceRate(): String? =
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)

    companion object {
        private const val TAG = "HushHourGateA"
        private const val TRACK = "Late cafe.ogg"

        // One engine for the process: creating one replaces the mixer behind the C render export.
        @Volatile private var shared: AudioEngine? = null

        fun engine(context: Context): AudioEngine = shared ?: synchronized(this) {
            shared ?: AudioEngine(
                MusicFiles.install(context).path,
                MusicFiles.userDir(context).path,
            ).also { shared = it }
        }
    }
}
