package com.fiftyhoursaweek.slowpage

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
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
    private var generation = 0
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
        logApkHash()
    }

    /** Ties logcat evidence to the installed build: the SHA-256 of this APK file. */
    private fun logApkHash() {
        val apk = File(applicationInfo.sourceDir)
        thread(name = "gate-a-apk-hash") {
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            Log.i(TAG, "apk $packageName $version sha256 $hex")
        }
    }

    override fun onStart() {
        super.onStart()
        // Install and engine setup run off the main thread; the stream is started back on the
        // main thread only if this onStart is still current. onStop bumps the generation, so a
        // setup that finishes after it never starts audio. `generation` and `started` are
        // touched on the main thread only.
        val current = ++generation
        thread(name = "gate-a-start") {
            val prepared = try {
                val engine = engine(applicationContext)
                engine.setMusicVolume(0.5f)  // the core's default (prefs.rs)
                engine.setMusic(MusicChoice.Bundled(TRACK))
                Result.success(engine.tracks().size)
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                Result.failure(e)
            }
            runOnUiThread {
                if (current != generation) return@runOnUiThread
                val message = prepared.fold(
                    onSuccess = { tracks ->
                        if (NativeAudio.start()) {
                            started = true
                            "playing $TRACK; tracks: $tracks; device rate ${deviceRate()} Hz"
                        } else {
                            "Oboe could not open the output stream (see logcat HushHourAudio)"
                        }
                    },
                    onFailure = { "start failed: $it" },
                )
                Log.i(TAG, message)
                header = message
                show(null)
            }
        }
        handler.post(tick)
    }

    override fun onStop() {
        generation++
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
