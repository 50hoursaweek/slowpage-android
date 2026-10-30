package com.fiftyhoursaweek.slowpage.audio

import uniffi.hushhour.MusicChoice

/**
 * Which music plays (A5, A6; the iPad's `MusicRoute`): a sample if one was asked for; else,
 * while held, what played when the hold began; else the checked track. The welcome tour that
 * holds and samples comes in phase 6; the document rule (silence until a document opens) uses
 * it now.
 */
class MusicRoute {
    var held = false
        private set
    var heldMusic: MusicChoice = MusicChoice.Off
        private set
    var sample: MusicChoice? = null

    /** From now until [release], a newly checked track waits; what plays keeps playing. A second hold keeps the first. */
    fun hold(playing: MusicChoice) {
        if (held) return
        held = true
        heldMusic = playing
    }

    /** Held on silence, whatever was held before. */
    fun silence() {
        held = true
        heldMusic = MusicChoice.Off
    }

    fun release() {
        held = false
        heldMusic = MusicChoice.Off
    }

    fun target(checked: MusicChoice): MusicChoice = sample ?: if (held) heldMusic else checked
}
