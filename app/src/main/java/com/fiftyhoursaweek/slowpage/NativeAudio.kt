package com.fiftyhoursaweek.slowpage

/** The Oboe stream in `cpp/audio.cpp`, pulling from the Rust mixer's C export. */
object NativeAudio {
    init {
        System.loadLibrary("hushhour_audio")
    }

    /** Opens and starts the output stream; false if Oboe could not. */
    external fun start(): Boolean

    external fun stop()

    /** See `Player::report` in `audio.cpp`; reading it resets the meter. */
    private external fun report(): DoubleArray

    data class Report(
        val streamRate: Int,
        val framesPerBurst: Int,
        val maxCallbackFrames: Int,
        val frames: Long,
        val peak: Double,
        val rms: Double,
        val api: String,
    )

    fun read(): Report = report().let {
        Report(
            streamRate = it[0].toInt(),
            framesPerBurst = it[1].toInt(),
            maxCallbackFrames = it[2].toInt(),
            frames = it[3].toLong(),
            peak = it[4],
            rms = it[5],
            api = when (it[6].toInt()) { 1 -> "OpenSL ES"; 2 -> "AAudio"; else -> "unspecified" },
        )
    }
}
