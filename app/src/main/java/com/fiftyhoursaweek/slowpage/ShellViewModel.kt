package com.fiftyhoursaweek.slowpage

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.fiftyhoursaweek.slowpage.editor.EditorSession
import com.fiftyhoursaweek.slowpage.store.DocumentEntry
import com.fiftyhoursaweek.slowpage.store.Kept
import com.fiftyhoursaweek.slowpage.store.Recovery
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
    private val recovery = app.recovery
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
    /** The text field's state; user edits reach it only through [guard]. */
    val field = TextFieldState()
    /** Every user edit goes to the core first; a refused one is reverted before it shows. */
    val guard = InputTransformation { guardEdit(this) }
    var status by mutableStateOf("")
        private set
    /** Read-only while a close is writing the text (D4); editable again if the close fails. */
    var closing by mutableStateOf(false)
        private set

    private var saveJob: Job? = null
    /** Only the latest Open or New replaces the screen (D5: an older one finishing last is dropped). */
    private var openRequest = 0

    /** The text the recovery folder holds for the open document, so the saved state need not hold it too. */
    @Volatile private var keptText: String? = null
    /** What process death left in the saved state, until the reopen uses it. */
    private var restoredDraft: Bundle? = saved.get<Bundle>(DRAFT_KEY)
    private var restoredCaret: Bundle? = saved.get<Bundle>(CARET_KEY)

    init {
        // Read when the state is saved, not at onStop: before Android 9 onSaveInstanceState can
        // come first (Codex round 2, MINOR 1).
        saved.setSavedStateProvider(DRAFT_KEY) { draftState() }
        saved.setSavedStateProvider(CARET_KEY) { caretState() }
        viewModelScope.launch {
            preferences = withContext(io) {
                store.removeLeftovers()
                recovery.removeLeftovers()
                StateFile.read(app.preferencesFile)?.let { parsePreferences(it.toString(Charsets.UTF_8)) }
                    ?: defaultPreferences()
            }
            refresh()
            saved.get<String>(OPEN_KEY)?.let { open(it, restoring = true) }
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
            if (request != openRequest) {
                // A later New or Open won (a double tap): the empty file it made is not wanted.
                created.getOrNull()?.let { withContext(io) { runCatching { store.delete(it) } } }
                return@launch
            }
            created.fold(
                onSuccess = { open(it) },
                onFailure = { notice = "A new document could not be made: ${it.message}" },
            )
        }
    }

    /**
     * Open [name]. Text kept from a failed save (a recovery file, or the saved-state draft when
     * [restoring] after process death) replaces the disk text and is saved again: the newest
     * copy, and only when it is newer than the document on disk (a stale copy is dropped).
     * [restoring] also puts the caret back where it was.
     */
    fun open(name: String, restoring: Boolean = false) {
        if (session != null) return  // one window, one document (L3); close comes first
        val request = ++openRequest
        viewModelScope.launch {
            val opened = withContext(io) {
                runCatching { Triple(EditorSession.open(store, name), recovery.read(name), store.file(name).lastModified()) }
            }
            if (request != openRequest || session != null) {
                opened.getOrNull()?.first?.close()
                return@launch
            }
            opened.fold(
                onSuccess = { (s, kept, onDisk) ->
                    session = s
                    val draft = restoredDraft?.takeIf { restoring }?.let { draftFrom(it, name) }
                    val caret = restoredCaret?.takeIf { restoring }
                    if (restoring) {
                        restoredDraft = null
                        restoredCaret = null
                    }
                    val newest = Recovery.newest(onDisk, kept, draft)
                    val recovered = newest != null && newest.text != s.text &&
                        s.change(newest.text) is EditorSession.Change.Applied
                    // A recovery copy that lost (older than the disk or the draft) or that
                    // matches the disk is not needed any more.
                    if (kept != null && (newest !== kept || kept.text == s.text) && !recovered) {
                        withContext(io) { recovery.clear(name) }
                    }
                    // Programmatic: not an edit, so it does not pass through the guard.
                    field.edit {
                        replace(0, length, s.text)
                        selection = if (caret == null) TextRange(0) else TextRange(
                            caret.getInt(START).coerceIn(0, length),
                            caret.getInt(END).coerceIn(0, length),
                        )
                    }
                    screen = Screen.Editor(name)
                    saved[OPEN_KEY] = name
                    updateStatus()
                    if (recovered) {
                        notice = "“$name” had text that was not saved; it is back, and is being saved again."
                        scheduleSave()
                    }
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
            val result = withContext(io) { runCatching { store.delete(name).also { recovery.clear(name) } } }
            result.fold(
                onSuccess = {
                    recentList.remove(name)
                    persistRecents()
                },
                // Still there: it stays in Recent too.
                onFailure = {
                    notice = "“$name” could not be deleted: ${describe(it)}"
                    refresh()
                },
            )
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
                // A later New or Open won: the copy is not opened, so it is not kept either
                // (the original is still where it came from).
                imported.getOrNull()?.let { withContext(io) { runCatching { store.delete(it) } } }
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
                val state = saveAndKeep(current)
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

    private fun guardEdit(buffer: TextFieldBuffer) {
        if (!accept(buffer.asCharSequence().toString())) buffer.revertAllChanges()
    }

    /** A user edit that makes the text [new]; false when it is refused and must be reverted. */
    internal fun accept(new: String): Boolean {
        val s = session
        if (s == null || closing) return false
        val change = s.change(new)
        when (change) {
            is EditorSession.Change.Applied -> if (s.isDirty()) scheduleSave()
            is EditorSession.Change.Refused -> notice = change.reason
        }
        updateStatus()
        return change is EditorSession.Change.Applied
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val s = session ?: return@launch
            saveAndKeep(s)
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
            val state = saveAndKeep(s)
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
        val state = s.save()
        if (!keepFiles(s, s.text, state)) warnUnkept(s, state)
        updateStatus()
    }

    // ---- Unsaved text (BLOCKING 1): a failed save keeps the text until a save works.

    /** Save [s] off the main thread, keeping the text when the save fails. */
    private suspend fun saveAndKeep(s: EditorSession): Persistence {
        val state = withContext(io) { s.save() }
        val text = s.text
        if (!withContext(io) { keepFiles(s, text, state) }) warnUnkept(s, state)
        return state
    }

    /**
     * After a save: a failed one writes [text] to the recovery folder with the time, a good one
     * (nothing left unsaved) removes it. Blocking. False when the text is unsaved and the
     * recovery copy could not be written either; an older copy is left, as it is still newer
     * than the disk, and the saved state ([draftState]) gets the text.
     */
    private fun keepFiles(s: EditorSession, text: String, state: Persistence): Boolean = when {
        state is Persistence.Failed -> (recovery.write(s.name, text, System.currentTimeMillis()) == null)
            .also { if (it) keptText = text }
        !s.isDirty() -> {
            recovery.clear(s.name)
            keptText = null
            true
        }
        else -> true
    }

    private fun warnUnkept(s: EditorSession, state: Persistence) {
        val reason = (state as? Persistence.Failed)?.reason ?: "the disk refused it"
        // Not "Export": an export saves first, and that is what fails (Codex round 2, MINOR 3).
        notice = "“${s.name}” could not be saved, and no copy could be kept ($reason). " +
            "Keep Slowpage open and free some space on the tablet; the text is saved again on your next change or when you close it."
    }

    /**
     * The saved-state draft: the unsaved text, when the recovery folder does not hold it (a full
     * disk), gzipped and capped so the saved state stays small (larger text: only [warnUnkept]
     * is left; the cap is Francesco's decision, Codex round 2). With no document open, what process death left, until it is used.
     */
    private fun draftState(): Bundle {
        val s = session ?: return restoredDraft ?: Bundle()
        if (!s.isDirty() || s.text == keptText) return Bundle()
        val packed = Recovery.pack(s.text)
        if (packed.size > DRAFT_MAX_BYTES) return Bundle()
        return Bundle().apply {
            putString(NAME, s.name)
            putByteArray(TEXT, packed)
            putLong(STAMP, System.currentTimeMillis())
        }
    }

    private fun draftFrom(b: Bundle, name: String): Kept? {
        if (b.getString(NAME) != name) return null
        val text = b.getByteArray(TEXT)?.let(Recovery::unpack) ?: return null
        return Kept(text, b.getLong(STAMP))
    }

    private fun caretState(): Bundle {
        if (session == null) return restoredCaret ?: Bundle()
        return Bundle().apply {
            putInt(START, field.selection.start)
            putInt(END, field.selection.end)
        }
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
            // The task is closing: a failed save leaves the recovery copy behind.
            keepFiles(it, it.text, it.save())
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
        private const val DRAFT_KEY = "draft"
        private const val CARET_KEY = "caret"
        /** Gzipped; prose packs about 3:1, so roughly 750 KB of text. */
        private const val DRAFT_MAX_BYTES = 256 * 1024
        private const val NAME = "name"
        private const val TEXT = "text"
        private const val STAMP = "stamp"
        private const val START = "start"
        private const val END = "end"
        private const val AUTOSAVE_DELAY_MS = 800L
    }
}
