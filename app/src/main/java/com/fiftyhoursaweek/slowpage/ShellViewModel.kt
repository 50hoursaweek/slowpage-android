package com.fiftyhoursaweek.slowpage

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.fiftyhoursaweek.slowpage.editor.EditorSession
import com.fiftyhoursaweek.slowpage.store.DocumentEntry
import com.fiftyhoursaweek.slowpage.store.StateFile
import com.fiftyhoursaweek.slowpage.store.StateProblems
import com.fiftyhoursaweek.slowpage.store.StoreError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.hushhour.DocumentException
import uniffi.hushhour.Persistence
import uniffi.hushhour.Preferences
import uniffi.hushhour.defaultPreferences
import uniffi.hushhour.parsePreferences
import uniffi.hushhour.serializePreferences
import java.io.IOException

sealed interface Screen {
    data object Start : Screen
    data class Editor(val name: String) : Screen
    data object Settings : Screen
}

/**
 * The shell: start screen, the open document, settings. Store work runs on one IO lane, in
 * order; state is read and written on the main thread. Survives rotation; after process death
 * the open document is reopened from [SavedStateHandle] (its text is on disk by onStop).
 */
class ShellViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val app = application as SlowpageApp
    private val store = app.store
    private val recentList = app.recents
    private val io = Dispatchers.IO.limitedParallelism(1)

    var screen by mutableStateOf<Screen>(Screen.Start)
        private set
    var documents by mutableStateOf(emptyList<DocumentEntry>())
        private set
    var recent by mutableStateOf(emptyList<String>())
        private set
    /** State-file failures (S3), shown on every screen until the next success. */
    var problems by mutableStateOf(emptyList<String>())
        private set
    /** A one-off message: an import that failed, an export that worked, a refused edit. */
    var notice by mutableStateOf<String?>(null)
    var preferences by mutableStateOf(defaultPreferences())
        private set
    var music by mutableStateOf("")

    // The open document.
    private var session: EditorSession? = null
    var field by mutableStateOf(TextFieldValue(""))
        private set
    var status by mutableStateOf("")
        private set
    /** Read-only while a close is writing the text (D4); editable again if the close fails. */
    var closing by mutableStateOf(false)
        private set

    private var saveJob: Job? = null
    /** Only the latest Open or New replaces the screen (D5: an older one finishing last is dropped). */
    private var openRequest = 0

    init {
        viewModelScope.launch {
            preferences = withContext(io) {
                store.removeLeftovers()
                StateFile.read(app.preferencesFile)?.let { parsePreferences(it.toString(Charsets.UTF_8)) }
                    ?: defaultPreferences()
            }
            refresh()
            saved.get<String>(OPEN_KEY)?.let { open(it) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val (docs, names) = withContext(io) {
                val docs = store.list()
                docs to recentList.existing { store.exists(it) }
            }
            documents = docs
            recent = names
            problems = app.problems.lines()
        }
    }

    // ---- Start screen

    fun newDocument() {
        val request = ++openRequest
        viewModelScope.launch {
            val created = withContext(io) { runCatching { store.createUntitled() } }
            if (request != openRequest) return@launch
            created.fold(
                onSuccess = { open(it) },
                onFailure = { notice = "A new document could not be made: ${it.message}" },
            )
        }
    }

    fun open(name: String) {
        if (session != null) return  // one window, one document (L3); close comes first
        val request = ++openRequest
        viewModelScope.launch {
            val opened = withContext(io) { runCatching { EditorSession.open(store, name) } }
            if (request != openRequest || session != null) {
                opened.getOrNull()?.close()
                return@launch
            }
            opened.fold(
                onSuccess = { s ->
                    session = s
                    field = TextFieldValue(s.text)
                    screen = Screen.Editor(name)
                    saved[OPEN_KEY] = name
                    updateStatus()
                    recentList.add(name)
                    persistRecents()
                },
                onFailure = { e ->
                    // A file that cannot be opened keeps the current screen (D6).
                    notice = "“$name” could not be opened: ${describe(e)}"
                    saved.remove<String>(OPEN_KEY)
                    refresh()
                },
            )
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            val result = withContext(io) { runCatching { store.delete(name) } }
            result.exceptionOrNull()?.let { notice = "“$name” could not be deleted: ${describe(it)}" }
            recentList.remove(name)
            persistRecents()
        }
    }

    fun import(resolver: ContentResolver, uri: Uri) {
        val request = ++openRequest
        viewModelScope.launch {
            val imported = withContext(io) {
                runCatching {
                    val display = displayName(resolver, uri)
                    val input = resolver.openInputStream(uri) ?: throw IOException("the file cannot be read")
                    input.use { store.import(display, it) }
                }
            }
            if (request != openRequest) {
                refresh()
                return@launch
            }
            imported.fold(
                onSuccess = { open(it) },
                onFailure = { notice = "The file could not be imported: ${describe(it)}" },
            )
        }
    }

    /** Copy the saved text of [name] to [uri] (made by the system's Create document screen). */
    fun export(resolver: ContentResolver, uri: Uri, name: String) {
        viewModelScope.launch {
            val current = session
            if (current != null && current.name == name) {
                val state = withContext(io) { current.save() }
                updateStatus()
                if (state is Persistence.Failed) {
                    notice = "Not exported: the text could not be saved first (${state.reason})."
                    removeQuietly(resolver, uri)
                    return@launch
                }
            }
            val result = withContext(io) {
                runCatching {
                    val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("the place cannot be written")
                    output.use { store.export(name, it) }
                }
            }
            notice = result.fold(
                onSuccess = { "Exported “$name”." },
                onFailure = {
                    removeQuietly(resolver, uri)
                    "“$name” could not be exported: ${describe(it)}. The document here is unchanged."
                },
            )
        }
    }

    fun openSettings() {
        if (session == null) screen = Screen.Settings
    }

    // ---- Editor

    fun onFieldChange(value: TextFieldValue) {
        val s = session ?: return
        if (closing) return
        when (val change = s.change(value.text)) {
            is EditorSession.Change.Applied -> {
                field = if (change.text == value.text) value else value.copy(
                    text = change.text,
                    selection = TextRange(value.selection.start.coerceAtMost(change.text.length)),
                )
                if (s.isDirty()) scheduleSave()
            }
            is EditorSession.Change.Refused -> notice = change.reason
        }
        updateStatus()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val s = session ?: return@launch
            withContext(io) { s.save() }
            updateStatus()
            if (s.isDirty() && s.persistence() !is Persistence.Failed) scheduleSave()
        }
    }

    /** Back to the start screen once the text is on disk; a failed save keeps the document open (D3). */
    fun closeEditor() {
        val s = session ?: return
        if (closing) return
        closing = true
        saveJob?.cancel()
        viewModelScope.launch {
            val state = withContext(io) { s.save() }
            closing = false
            if (state is Persistence.Failed) {
                updateStatus()
                notice = "Not closed: the text could not be saved (${state.reason}). It is still here."
                return@launch
            }
            session = null
            s.close()
            saved.remove<String>(OPEN_KEY)
            screen = Screen.Start
            refresh()
        }
    }

    /** onStop: the text goes to disk now, on this thread (L1); the process may be killed after. */
    fun saveNow() {
        val s = session ?: return
        saveJob?.cancel()
        s.save()
        updateStatus()
    }

    private fun updateStatus() {
        status = when (val p = session?.persistence()) {
            null -> ""
            Persistence.Clean -> "Saved"
            Persistence.Dirty -> "Edited"
            Persistence.Saving -> "Saving…"
            is Persistence.Failed -> "Not saved: ${p.reason}"
        }
    }

    // ---- Settings

    fun updatePreferences(new: Preferences) {
        preferences = new
        viewModelScope.launch {
            val failure = withContext(io) {
                StateFile.write(app.preferencesFile, serializePreferences(new).toByteArray(Charsets.UTF_8))
            }
            app.problems.report(StateProblems.Kind.PREFERENCES, failure)
            problems = app.problems.lines()
        }
    }

    // ---- Navigation

    /** System back: editor → close, settings → start. False on the start screen (the activity finishes). */
    fun back(): Boolean = when (screen) {
        is Screen.Editor -> { closeEditor(); true }
        Screen.Settings -> { screen = Screen.Start; true }
        Screen.Start -> false
    }

    private suspend fun persistRecents() {
        val failure = withContext(io) { recentList.persist() }
        app.problems.report(StateProblems.Kind.RECENTS, failure)
        refresh()
    }

    override fun onCleared() {
        session?.let {
            it.save()
            it.close()
        }
        session = null
    }

    private fun describe(e: Throwable): String = when (e) {
        is StoreError -> e.message.orEmpty()
        is DocumentException.TooLarge -> "it is larger than ${e.limitBytes.toLong() / (1024 * 1024)} MiB"
        is DocumentException -> "the core refused it"
        is SecurityException -> "access was refused"
        else -> e.message ?: e.toString()
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** A failed export leaves no half-written file at the destination, when the provider allows it. */
    private suspend fun removeQuietly(resolver: ContentResolver, uri: Uri) {
        withContext(io) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
    }

    companion object {
        private const val OPEN_KEY = "open"
        private const val AUTOSAVE_DELAY_MS = 800L
    }
}
