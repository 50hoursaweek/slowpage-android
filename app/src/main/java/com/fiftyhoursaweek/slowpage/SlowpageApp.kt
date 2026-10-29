package com.fiftyhoursaweek.slowpage

import android.app.Application
import com.fiftyhoursaweek.slowpage.store.DocumentStore
import com.fiftyhoursaweek.slowpage.store.Recents
import com.fiftyhoursaweek.slowpage.store.StateProblems
import uniffi.hushhour.limits
import java.io.File

/** The process-wide stores: one of each, whatever the activity does (rotation, recreation). */
class SlowpageApp : Application() {
    lateinit var store: DocumentStore
        private set
    lateinit var recents: Recents
        private set
    val problems = StateProblems()
    val preferencesFile by lazy { File(filesDir, "preferences") }

    override fun onCreate() {
        super.onCreate()
        store = DocumentStore(File(filesDir, "documents"), limits().maxDocumentBytes.toLong())
        recents = Recents(File(filesDir, "recents"))
    }
}
