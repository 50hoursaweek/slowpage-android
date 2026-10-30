package com.fiftyhoursaweek.slowpage

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.fiftyhoursaweek.slowpage.editor.Command
import com.fiftyhoursaweek.slowpage.editor.EditorHost
import com.fiftyhoursaweek.slowpage.editor.EditorSession
import com.fiftyhoursaweek.slowpage.editor.EditorSink
import com.fiftyhoursaweek.slowpage.editor.Edited
import com.fiftyhoursaweek.slowpage.editor.HighlightModel
import com.fiftyhoursaweek.slowpage.editor.Paragraphs
import com.fiftyhoursaweek.slowpage.editor.ReadOnlyText
import com.fiftyhoursaweek.slowpage.editor.diff
import com.fiftyhoursaweek.slowpage.preview.PreviewLook
import com.fiftyhoursaweek.slowpage.preview.PreviewRenderer
import com.fiftyhoursaweek.slowpage.preview.RenderedPreview
import com.fiftyhoursaweek.slowpage.ui.Faces
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
import uniffi.hushhour.TextChange
import uniffi.hushhour.ViewMode
import uniffi.hushhour.defaultPreferences
import uniffi.hushhour.limits
import uniffi.hushhour.nextFocus
import uniffi.hushhour.nextFont
import uniffi.hushhour.nextTheme
import uniffi.hushhour.nextView
import uniffi.hushhour.preview
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
    /** Its highlighting state (fence states, line table); survives rotation with the view model. */
    var highlight: HighlightModel? = null
        private set
    /** The editor on screen, while there is one; it applies undo and redo and redraws the spotlight. */
    private var sink: EditorSink? = null
    /** The selection in the editor (UTF-16), kept across rotation and process death. */
    var selectionStart = 0
        private set
    var selectionEnd = 0
        private set
    /** Bumped when the caret moves or the text changes, so the preview keeps level (V5). */
    var caretTick by mutableStateOf(0)
        private set
    /** Editor, Split or Preview (V1): not a preference, every opened document starts in Editor. */
    var viewMode by mutableStateOf(ViewMode.EDITOR)
        private set
    /** Words, characters, reading time (E15). */
    var stats by mutableStateOf("")
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** The editor's menu (Ctrl+, opens and closes it). */
    var menuOpen by mutableStateOf(false)
    /** The latest preview for the current text (V3, V5): never one parsed from an older revision. */
    var preview by mutableStateOf<RenderedPreview?>(null)
        private set
    /** The look the preview is rendered in; the screen sets it, as it depends on the window. */
    var previewLook: PreviewLook? = null
        set(value) {
            if (field == value) return
            field = value
            refreshPreview(reparse = false)
        }
    /** A short line when someone types or taps in the preview (V6), or null. */
    var hint by mutableStateOf<String?>(null)
        private set
    val faces = Faces(application.assets)
    private val previewLane = Dispatchers.Default.limitedParallelism(1)
    private var previewGeneration = 0
    private var previewJob: Job? = null
    private var previewRevision: Pair<ULong, ULong>? = null
    private var previewBlocks: List<uniffi.hushhour.PreviewBlock>? = null
    private var renderedLook: PreviewLook? = null
    private var statsJob: Job? = null
    /**
     * The text just opened and the revision it is, for the editor about to be made; dropped once
     * it has it (not held twice), and not used once the text has changed.
     */
    private var openedText: Pair<String, Pair<ULong, ULong>>? = null
    private var hintJob: Job? = null
    var status by mutableStateOf("")
        private set
    /** Read-only while a close is writing the text (D4); editable again if the close fails. */
    var closing by mutableStateOf(false)
        private set
    /** A long text is going into the editor: input, undo and redo wait, and the screen says so (round 2). */
    var editorBusy by mutableStateOf(false)
        private set
    /**
     * The open document when its longest paragraph is over [Paragraphs.CAP] (ruling B): shown
     * read-only, never changed; there is no editor and no preview. Null for any other document.
     */
    var readOnly by mutableStateOf<ReadOnlyText?>(null)
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
                    val font = preferences.font
                    // Off the main thread: a 20 MiB copy out of the core (round 1, F3). Over the
                    // paragraph cap, the text is only shown (ruling B): nothing to highlight.
                    val (text, model) = withContext(io) {
                        val t = s.text
                        if (Paragraphs.longest(t) > Paragraphs.CAP) t to null else t to HighlightModel(font).apply { reset(t) }
                    }
                    if (model == null) {
                        readOnly = withContext(io) { ReadOnlyText(text) }
                    } else {
                        highlight = model
                        openedText = text to s.revision()
                    }
                    selectionStart = caret?.getInt(START)?.coerceIn(0, text.length) ?: 0
                    selectionEnd = caret?.getInt(END)?.coerceIn(0, text.length) ?: 0
                    viewMode = ViewMode.EDITOR
                    preview = null
                    previewBlocks = null
                    previewRevision = null
                    updateEditState()
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

    fun attach(editor: EditorSink) {
        sink = editor
    }

    fun detach(editor: EditorSink) {
        if (sink === editor) sink = null
    }

    /** The editor's view of the open document: every change through the core first (E13). */
    val host = object : EditorHost {
        override fun fits(start: Int, end: Int, insert: String): Boolean {
            val s = session ?: return false
            if (closing || readOnly != null) return false  // read-only while a close writes the text (D4)
            // A paragraph over the cap could not be edited smoothly (ruling B): refused, as at the limit.
            val lines = highlight?.lines
            if (lines != null && Paragraphs.longestAfter(lines, start, end, insert) > Paragraphs.CAP) {
                notice = "A paragraph cannot be longer than ${"%,d".format(Paragraphs.CAP)} characters; start a new line first."
                return false
            }
            if (s.fits(start, end, insert)) return true
            notice = "The document cannot grow past ${s.limitMiB()} MiB."
            return false
        }

        override fun edited(start: Int, oldEnd: Int, insert: String, text: CharSequence): Edited? {
            val s = session ?: return null
            val m = highlight ?: return null
            val edited = when (val change = s.edit(start, oldEnd, insert)) {
                is EditorSession.Change.Applied -> change.result?.let { r ->
                    val redraw = m.didEdit(text, start, oldEnd, insert.length, r.line.toInt(), r.removedLines.toInt(), r.insertedLines.toInt())
                    Edited(r.line.toInt(), r.removedLines.toInt(), r.insertedLines.toInt(), redraw)
                }
                is EditorSession.Change.Refused -> {
                    // A change that bypassed the gate: the editor's text is the truth now.
                    Log.w(TAG, "the core refused a change the editor made (${change.reason}); resyncing")
                    s.resync(text.toString())
                    m.reset(text)
                    null
                }
            }
            afterChange(s)
            return edited
        }

        override fun selectionChanged(start: Int, end: Int, text: CharSequence): List<IntRange> {
            selectionStart = start
            selectionEnd = end
            caretTick++
            val m = highlight ?: return emptyList()
            if (text.length != m.lines.length) return emptyList()
            return m.spotlight(preferences.focus, text, end)
        }

        override fun undo() = command(Command.UNDO)
        override fun redo() = command(Command.REDO)
        override fun coreText() = session?.text ?: ""
        override fun loadingChanged(loading: Boolean) {
            editorBusy = loading
        }
    }

    /** The open document's text, for an editor being made (open, rotation, a change of view). */
    fun editorText(): String {
        val s = session ?: return ""
        val opened = openedText
        openedText = null
        return opened?.takeIf { it.second == s.revision() }?.first ?: s.text
    }

    /** A user edit that makes the text [new] (tests: what the editor reports); false when refused. */
    internal fun accept(new: String): Boolean {
        val s = session ?: return false
        val edit = diff(s.text, new) ?: return true
        if (!host.fits(edit.start, edit.end, edit.insert)) return false
        host.edited(edit.start, edit.end, edit.insert, new)
        return true
    }

    private fun afterChange(s: EditorSession) {
        if (s.isDirty()) scheduleSave()
        updateStatus()
        updateEditState()
        caretTick++
        refreshPreview(reparse = true)
    }

    /** Undo state and statistics, the statistics after a pause (they count the whole text). */
    private fun updateEditState() {
        val s = session ?: return
        // A read-only document has a history only from a recovery (round 3): never offered.
        canUndo = readOnly == null && s.canUndo()
        canRedo = readOnly == null && s.canRedo()
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            delay(STATS_DELAY_MS)
            // Off the main thread (the whole text is counted); null when a close came first (round 1, BLOCKING 2).
            val st = withContext(Dispatchers.Default) { s.statsUnlessClosed() } ?: return@launch
            if (session === s) stats = "${st.words} words · ${st.characters} characters · ${st.readingTime}"
        }
    }

    /** Apply a change from the core's history to the editor and the highlighting (E12). */
    private fun applyCoreChange(s: EditorSession, change: TextChange) {
        val editor = sink
        if (editor != null) {
            editor.apply(change)
        } else {
            val start = change.utf16.start.toInt()
            highlight?.didEdit(s.text, start, change.utf16.end.toInt(), change.text.length, change.line.toInt(),
                change.removedLines.toInt(), change.insertedLines.toInt())
            selectionStart = change.caretUtf16.toInt()
            selectionEnd = selectionStart
        }
        afterChange(s)
    }

    /** A keyboard shortcut or a menu item (K1). */
    fun command(c: Command) {
        val p = preferences
        when (c) {
            Command.MENU -> menuOpen = !menuOpen
            Command.SPOTLIGHT -> updatePreferences(p.copy(focus = nextFocus(p.focus)))
            Command.ANCHOR -> updatePreferences(p.copy(typewriter = !p.typewriter))
            Command.THEME -> updatePreferences(p.copy(theme = nextTheme(p.theme)))
            Command.FONT -> updatePreferences(p.copy(font = nextFont(p.font)))
            Command.ZOOM_IN -> zoom(1f)
            Command.ZOOM_OUT -> zoom(-1f)
            Command.VIEW -> setView(nextView(viewMode))
            Command.UNDO -> session?.let { s -> if (historyOpen()) s.undo()?.let { applyCoreChange(s, it) } }
            Command.REDO -> session?.let { s -> if (historyOpen()) s.redo()?.let { applyCoreChange(s, it) } }
            Command.NEW -> if (session == null) newDocument() else closeEditor(then = ::newDocument)
            Command.OPEN, Command.CLOSE -> closeEditor()
            Command.IMPORT -> Unit  // the activity starts the system picker
        }
    }

    /**
     * Whether undo and redo may change the document, from the menu, a shortcut or the editor.
     * Not while a close writes the text (D4), nor while a text goes into the editor (round 2: the
     * change would need all of it). Never in a read-only document (round 3, Codex NEW-B1): one
     * opened with recovered text has that recovery in its history, and an undo would change the
     * hidden core and save the older text over the recovered one.
     */
    private fun historyOpen() = !closing && !editorBusy && readOnly == null

    /** One point larger or smaller, within the core's limits (E3). */
    private fun zoom(step: Float) {
        val l = limits()
        val size = Math.round(preferences.size + step).toFloat().coerceIn(l.minSize, l.maxSize)
        if (size != preferences.size) updatePreferences(preferences.copy(size = size))
    }

    fun setView(mode: ViewMode) {
        if (mode == viewMode || readOnly != null) return
        viewMode = mode
        hint = null
        refreshPreview(reparse = false)
    }

    /** Someone typed or tapped in the preview: say where writing happens (V6). */
    fun previewNeedsHint() {
        if (viewMode == ViewMode.EDITOR) return
        hint = if (viewMode == ViewMode.PREVIEW) "Preview: press Ctrl+Shift+P or tap Edit to write" else "This is the preview: type in the editor"
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            delay(HINT_MS)
            hint = null
        }
    }

    /**
     * Bring the preview up to date (V5). [reparse]: the text just changed, so any parse running
     * is stale from now on and is never shown; a new one follows a pause in typing. Blocks from
     * an older revision are never reused. Only the look changed: the blocks render again.
     * Nothing is parsed in Editor view.
     */
    fun refreshPreview(reparse: Boolean) {
        if (reparse) {
            previewGeneration++
            previewJob?.cancel()
        }
        val s = session
        val look = previewLook
        if (s == null || look == null || viewMode == ViewMode.EDITOR) {
            previewJob?.cancel()
            return
        }
        val revision = s.revision()
        val blocks = previewBlocks?.takeIf { previewRevision == revision }
        if (blocks != null && renderedLook == look && preview != null) return
        previewJob?.cancel()
        val generation = ++previewGeneration
        previewJob = viewModelScope.launch {
            if (reparse) delay(PREVIEW_DELAY_MS)
            val (parsed, rendered) = withContext(previewLane) {
                // The text is copied here, not on the main thread; a close since leaves nothing to parse.
                val b = blocks ?: preview(s.textUnlessClosed() ?: return@withContext null)
                b to PreviewRenderer.render(b, look, faces)
            } ?: return@launch
            // Published only if nothing newer started and the text is still the one parsed.
            if (generation != previewGeneration || session !== s || s.revision() != revision) return@launch
            previewBlocks = parsed
            previewRevision = revision
            renderedLook = look
            preview = rendered
        }
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

    /** Back to the start screen once the text is on disk, then [then]; a failed save keeps the document open (D3). */
    fun closeEditor(then: (() -> Unit)? = null) {
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
            statsJob?.cancel()
            previewJob?.cancel()
            openedText = null
            readOnly = null
            editorBusy = false
            s.close()
            highlight?.close()
            highlight = null
            preview = null
            menuOpen = false
            saved.remove<String>(OPEN_KEY)
            screen = Screen.Start
            refresh()
            then?.invoke()
        }
    }

    /** onStop: the text goes to disk now, on this thread (L1); the process may be killed after. */
    fun saveNow() {
        val s = session ?: return
        saveJob?.cancel()
        val state = s.save()
        if (!keepFiles(s, state)) warnUnkept(s, state)
        updateStatus()
    }

    // ---- Unsaved text (BLOCKING 1): a failed save keeps the text until a save works.

    /** Save [s] off the main thread, keeping the text when the save fails. */
    private suspend fun saveAndKeep(s: EditorSession): Persistence {
        val state = withContext(io) { s.save() }
        if (!withContext(io) { keepFiles(s, state) }) warnUnkept(s, state)
        return state
    }

    /**
     * After a save: a failed one writes the text to the recovery folder with the time, a good one
     * (nothing left unsaved) removes it. Blocking. False when the text is unsaved and the
     * recovery copy could not be written either; an older copy is left, as it is still newer
     * than the disk, and the saved state ([draftState]) gets the text. The text is copied out
     * of the core only for a failed save (a large one is not copied after every autosave).
     */
    private fun keepFiles(s: EditorSession, state: Persistence): Boolean = when {
        state is Persistence.Failed -> s.text.let { text ->
            (recovery.write(s.name, text, System.currentTimeMillis()) == null).also { if (it) keptText = text }
        }
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
            putInt(START, selectionStart)
            putInt(END, selectionEnd)
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
        val old = preferences
        preferences = new
        if (new.focus != old.focus) sink?.refreshSpotlight()
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
            keepFiles(it, it.save())
            it.close()
        }
        session = null
        highlight?.close()
        highlight = null
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
        private const val STATS_DELAY_MS = 150L
        private const val PREVIEW_DELAY_MS = 150L
        private const val HINT_MS = 2500L
        private const val TAG = "SlowpageShell"
    }
}
