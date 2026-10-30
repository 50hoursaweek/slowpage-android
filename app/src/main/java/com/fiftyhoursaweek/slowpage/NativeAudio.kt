package com.fiftyhoursaweek.slowpage

/** The Oboe stream in `cpp/audio.cpp`, pulling from the Rust mixer's C export. */
object NativeAudio {
    init {
        System.loadLibrary("hushhour_audio")
    }

    /** Opens and starts the output stream; false if Oboe could not. */
    external fun start(): Boolean

    external fun stop()
}
