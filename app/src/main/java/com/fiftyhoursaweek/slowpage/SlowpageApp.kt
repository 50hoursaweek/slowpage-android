package com.fiftyhoursaweek.slowpage

import android.app.Application
import com.fiftyhoursaweek.slowpage.audio.AudioController
import com.fiftyhoursaweek.slowpage.store.DocumentStore
import com.fiftyhoursaweek.slowpage.store.Recents
import com.fiftyhoursaweek.slowpage.store.Recovery
import com.fiftyhoursaweek.slowpage.store.StateProblems
import uniffi.hushhour.limits
import java.io.File

/** The process-wide stores: one of each, whatever the activity does (rotation, recreation). */
class SlowpageApp : Application() {
    lateinit var store: DocumentStore
        private set
    lateinit var recents: Recents
        private set
    /** Text whose save failed, until a save works (survives process death and a closed task). */
    lateinit var recovery: Recovery
        private set
    /** Typing sounds and music: one for the process, so a rotation never touches them (phase 5). Tests put in their own. */
    lateinit var audio: AudioController
        internal set
    val problems = StateProblems()
    val preferencesFile by lazy { File(filesDir, "preferences") }

    override fun onCreate() {
        super.onCreate()
        val limit = limits().maxDocumentBytes.toLong()
        store = DocumentStore(File(filesDir, "documents"), limit)
        recovery = Recovery(File(filesDir, "recovery"), limit)
        recents = Recents(File(filesDir, "recents"))
        audio = AudioController.create(this)
    }
}
