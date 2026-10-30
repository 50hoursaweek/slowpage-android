package com.fiftyhoursaweek.slowpage

/** The Oboe stream in `cpp/audio.cpp`, pulling from the Rust mixer's C export. */
object NativeAudio {
    init {
        System.loadLibrary("hushhour_audio")
    }

    /** Loads the library (the first use of this object does). */
    fun load() = Unit

    /** Opens and starts the output stream; false if Oboe could not. Not while [allow] is false: then nothing opens. */
    external fun start(): Boolean

    external fun stop()

    /** Whether the stream may be heard: an atomic flag, never blocks. */
    external fun allow(allowed: Boolean)
}
