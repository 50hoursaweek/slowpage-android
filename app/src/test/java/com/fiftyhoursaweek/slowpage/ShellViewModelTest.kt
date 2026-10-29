package com.fiftyhoursaweek.slowpage

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import com.fiftyhoursaweek.slowpage.store.Recovery
import com.fiftyhoursaweek.slowpage.editor.Command
import com.fiftyhoursaweek.slowpage.editor.LineTable
import com.fiftyhoursaweek.slowpage.preview.PreviewLook
import uniffi.hushhour.FontChoice
import uniffi.hushhour.Theme
import uniffi.hushhour.ViewMode
import uniffi.hushhour.defaultPreferences
import uniffi.hushhour.limits
import uniffi.hushhour.nextFocus
import uniffi.hushhour.nextTheme
import uniffi.hushhour.serializePreferences
import java.io.File
import java.time.Duration

/**
 * The shell's view model through its lifecycle: process death (a new view model from the
 * saved state, no onCleared), a closed task (onCleared, no saved state) and request races,
 * over the real stores and the core (host build). A failed save is a documents folder that
 * cannot be written.
 */
@RunWith(RobolectricTestRunner::class)
class ShellViewModelTest {
    private lateinit var app: SlowpageApp
    private val stores = mutableListOf<ViewModelStore>()
    private val made = mutableListOf<ShellViewModel>()
    private val seen get() = made.map { it.notice to it.screen }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as SlowpageApp
    }

    @After fun tearDown() {
        writable(app.store.folder, true)
        writable(app.recovery.folder, true)
        stores.forEach { it.clear() }
    }

    // ---- BLOCKING 1: unsaved text after a failed save

    @Test fun aFailedSaveThenProcessDeathKeepsTheTextAndSavesItOnReopen() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        val name = openNew(vm)
        assertTrue(vm.accept("Words that could not be saved"))
        writable(app.store.folder, false)
        vm.saveNow()  // onStop
        assertTrue(vm.status, vm.status.startsWith("Not saved"))
        assertEquals("Words that could not be saved", app.recovery.read(name)?.text)

        processDeath(vm)
        writable(app.store.folder, true)
        val again = create(restored(handle))
        until("the document reopens with its unsaved text") {
            again.screen == Screen.Editor(name) && again.editorText() == "Words that could not be saved"
        }
        assertNotNull(again.notice)
        until("the recovered text is saved") { app.store.read(name) == "Words that could not be saved" }
        until("the recovery copy is gone") { !app.recovery.has(name) }
    }

    @Test fun aFailedSaveThenAClosedTaskKeepsTheTextForTheNextOpen() {
        val vm = create(SavedStateHandle())
        val name = openNew(vm)
        assertTrue(vm.accept("Kept after the task closed"))
        writable(app.store.folder, false)
        vm.saveNow()
        stores.removeAt(0).clear()  // the task is removed: onCleared, the saved state is gone

        writable(app.store.folder, true)
        assertEquals("", app.store.read(name))
        val next = create(SavedStateHandle())
        settle()
        next.open(name)
        until("the next open shows the kept text") { next.editorText() == "Kept after the task closed" }
        until("and saves it") { app.store.read(name) == "Kept after the task closed" && !app.recovery.has(name) }
    }

    @Test fun whenEvenTheRecoveryCopyFailsTheTextIsInTheSavedState() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        val name = openNew(vm)
        assertTrue(vm.accept("Only in the saved state"))
        app.recovery.folder.mkdirs()
        writable(app.store.folder, false)
        writable(app.recovery.folder, false)  // a full disk: nothing can be written
        vm.saveNow()
        assertFalse(app.recovery.has(name))

        processDeath(vm)
        writable(app.store.folder, true)
        writable(app.recovery.folder, true)
        val again = create(restored(handle))
        until("the text comes back from the saved state") { again.editorText() == "Only in the saved state" }
        until("and is saved") { app.store.read(name) == "Only in the saved state" }
    }

    @Test fun aGoodSaveLeavesNoRecoveryCopy() {
        val vm = create(SavedStateHandle())
        val name = openNew(vm)
        assertTrue(vm.accept("Fine"))
        writable(app.store.folder, false)
        vm.saveNow()
        assertTrue(app.recovery.has(name))
        writable(app.store.folder, true)
        assertTrue(vm.accept("Fine again"))
        vm.saveNow()
        assertEquals("Saved", vm.status)
        assertFalse(app.recovery.has(name))
    }

    // ---- Round 2, BLOCKING 1: the newest copy wins; a stale one never does

    @Test fun anOlderRecoveryCopyLosesToANewerDraft() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        val name = openNew(vm)
        assertTrue(vm.accept("Version A"))
        writable(app.store.folder, false)
        vm.saveNow()
        assertEquals("Version A", app.recovery.read(name)?.text)
        Thread.sleep(5)  // B is kept later than A
        assertTrue(vm.accept("Version B"))
        writable(app.recovery.folder, false)  // the disk fills up: B's copy cannot be written
        vm.saveNow()
        assertEquals("Version A", app.recovery.read(name)?.text)

        processDeath(vm)
        val state = restored(handle)
        writable(app.store.folder, true)
        writable(app.recovery.folder, true)
        val again = create(state)
        until("the newer draft wins") { again.screen == Screen.Editor(name) && again.editorText().isNotEmpty() }
        assertEquals("Version B", again.editorText())
        until("B is saved") { app.store.read(name) == "Version B" }
        until("and the old copy is gone") { !app.recovery.has(name) }
    }

    @Test fun aRecoveryCopyOlderThanTheDocumentIsDroppedNotRestored() {
        val vm = create(SavedStateHandle())
        val name = openNew(vm)
        assertTrue(vm.accept("Saved later"))
        vm.closeEditor()
        until("closed") { vm.screen == Screen.Start }
        // A copy whose clear failed, from before the good save.
        assertTrue(app.recovery.write(name, "Older unsaved text", stamp = 1) == null)

        vm.open(name)
        until("the document opens") { vm.screen == Screen.Editor(name) }
        assertEquals("Saved later", vm.editorText())
        until("the stale copy is removed") { !app.recovery.has(name) }
    }

    @Test fun aLargeDraftSurvivesProcessDeathPacked() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        val name = openNew(vm)
        val big = (1..40_000).joinToString(" ") { "word$it" }  // about 390,000 characters
        assertTrue(vm.accept(big))
        app.recovery.folder.mkdirs()
        writable(app.store.folder, false)
        writable(app.recovery.folder, false)
        vm.saveNow()
        assertNotNull(vm.notice)  // nothing could be kept on disk: the writer is told
        assertFalse(vm.notice!!, "Export" in vm.notice!!)  // an export would save first, and fail (round 2, MINOR 3)

        processDeath(vm)
        val state = restored(handle)
        writable(app.store.folder, true)
        writable(app.recovery.folder, true)
        val again = create(state)
        until("the large text comes back") { again.editorText().length == big.length }
        assertEquals(big, again.editorText())
    }

    @Test fun recoveryLeftoversAreRemovedAtStart() {
        app.recovery.folder.mkdirs()
        val leftover = File(app.recovery.folder, ".Untitled.md.123.partial").apply { writeText("half") }
        create(SavedStateHandle())
        until("the leftover is gone") { !leftover.exists() }
    }

    // ---- MINOR 4: the caret after process death

    @Test fun processDeathKeepsTheCaret() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        val name = openNew(vm)
        assertTrue(vm.accept("Hello world"))
        vm.host.selectionChanged(5, 5, "Hello world")
        // Before Android 9 the state can be saved before onStop: no saveNow here.
        val state = restored(handle)
        vm.saveNow()

        processDeath(vm)
        val again = create(state)
        until("the document reopens") { again.screen == Screen.Editor(name) && again.editorText().isNotEmpty() }
        assertEquals(5 to 5, again.selectionStart to again.selectionEnd)
    }

    // ---- MINOR 1: requests that lose a race leave nothing behind

    @Test fun aDoubleTapOnNewMakesOneDocument() {
        val vm = create(SavedStateHandle())
        settle()
        vm.newDocument()
        vm.newDocument()
        until("a document opens") { vm.screen is Screen.Editor }
        settle()
        val names = app.store.list().map { it.name }
        assertEquals(listOf((vm.screen as Screen.Editor).name), names)
    }

    @Test fun anImportOvertakenByNewLeavesNoCopy() {
        val source = File(app.cacheDir, "outside.md").apply { writeText("from elsewhere") }
        val vm = create(SavedStateHandle())
        settle()
        vm.import(app.contentResolver, Uri.fromFile(source))
        vm.newDocument()
        until("the new document opens") { vm.screen is Screen.Editor }
        settle()
        assertEquals(listOf("Untitled.md"), app.store.list().map { it.name })
    }

    // ---- MINOR 2: a failed delete keeps the document in Recent

    @Test fun aFailedDeleteKeepsTheDocumentInRecent() {
        val vm = create(SavedStateHandle())
        val name = openNew(vm)
        vm.closeEditor()
        until("back on the start screen") { vm.screen == Screen.Start && vm.recent == listOf(name) }
        writable(app.store.folder, false)
        vm.delete(name)
        until("the failure is shown") { vm.notice != null }
        settle()
        assertTrue(app.store.exists(name))
        assertEquals(listOf(name), vm.recent)
    }

    // ---- MINOR 3: stored preferences on a cold start

    @Test fun storedPreferencesArriveOnAColdStart() {
        val stored = defaultPreferences().copy(musicVolume = 0.3f)
        app.preferencesFile.writeText(serializePreferences(stored))
        val vm = create(SavedStateHandle())
        until("the stored volume is loaded") { vm.preferences.musicVolume == 0.3f }
    }

    // ---- Phase 4: the editor through the view model

    @Test fun undoAndRedoComeFromTheCoresHistoryAndSurviveAnAutosave() {
        val vm = create(SavedStateHandle())
        openNew(vm)
        assertTrue(vm.accept("Hello"))
        until("autosaved") { vm.status == "Saved" }
        assertTrue(vm.canUndo)
        vm.command(Command.UNDO)
        assertEquals("", vm.editorText())
        assertTrue(vm.canRedo)
        vm.command(Command.REDO)
        assertEquals("Hello", vm.editorText())
        assertEquals(LineTable(vm.editorText()).starts(), vm.highlight!!.lines.starts())
    }

    @Test fun aChangePastTheLimitIsRefusedWithANotice() {
        val vm = create(SavedStateHandle())
        openNew(vm)
        val big = "a".repeat(20 * 1024 * 1024)
        assertTrue(vm.accept(big))
        assertFalse(vm.host.fits(big.length, big.length, "b"))
        assertTrue(vm.notice!!.contains("20 MiB"))
        assertTrue("deletions always fit", vm.host.fits(0, 1, ""))
    }

    @Test fun everyOpenedDocumentStartsInEditorAndTheViewIsNotSaved() {
        val stored = defaultPreferences().copy(view = ViewMode.PREVIEW)
        app.preferencesFile.writeText(serializePreferences(stored))
        val vm = create(SavedStateHandle())
        openNew(vm)
        assertEquals(ViewMode.EDITOR, vm.viewMode)
        vm.command(Command.VIEW)
        assertEquals(ViewMode.SPLIT, vm.viewMode)
        vm.command(Command.VIEW)
        assertEquals(ViewMode.PREVIEW, vm.viewMode)
        vm.command(Command.VIEW)
        assertEquals(ViewMode.EDITOR, vm.viewMode)
        vm.setView(ViewMode.PREVIEW)
        vm.closeEditor()
        until("closed") { vm.screen == Screen.Start }
        openNew(vm)
        assertEquals(ViewMode.EDITOR, vm.viewMode)
    }

    @Test fun editsShowWhenThePreviewComesBackAndAnOlderParseNeverPublishes() {
        val vm = create(SavedStateHandle())
        openNew(vm)
        vm.previewLook = PreviewLook(Theme.LIGHT, FontChoice.MONO, 36f, 1.5f)
        assertTrue(vm.accept("# One"))
        vm.setView(ViewMode.SPLIT)
        until("the preview shows the text") { vm.preview?.text?.startsWith("One") == true }
        // In Editor view nothing is parsed; back in Split the new text shows.
        vm.setView(ViewMode.EDITOR)
        assertTrue(vm.accept("# Two"))
        vm.setView(ViewMode.SPLIT)
        until("the edit shows") { vm.preview?.text?.startsWith("Two") == true }
        // Edits in quick succession: only the newest text is ever published.
        val published = mutableListOf<String>()
        for (word in listOf("Three", "Four", "Five")) {
            assertTrue(vm.accept("# $word"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            vm.preview?.text?.let { published += it.trim() }
        }
        until("the newest text shows") { vm.preview?.text?.startsWith("Five") == true }
        assertTrue("never an older parse after a newer edit: $published", published.none { it == "Three" || it == "Four" })
    }

    @Test fun thePreviewHintSaysWhereWritingHappens() {
        val vm = create(SavedStateHandle())
        openNew(vm)
        vm.previewNeedsHint()
        assertEquals("no hint in Editor view", null, vm.hint)
        vm.setView(ViewMode.PREVIEW)
        vm.previewNeedsHint()
        assertTrue(vm.hint!!.contains("tap Edit"))
        vm.setView(ViewMode.SPLIT)
        vm.previewNeedsHint()
        assertTrue(vm.hint!!.contains("type in the editor"))
        until("the hint goes") { vm.hint == null }
    }

    @Test fun zoomStepsAndTheSwitchesChangeThePreferences() {
        val vm = create(SavedStateHandle())
        openNew(vm)
        val size = vm.preferences.size
        vm.command(Command.ZOOM_IN)
        assertEquals(size + 1, vm.preferences.size)
        vm.command(Command.ZOOM_OUT)
        vm.command(Command.ZOOM_OUT)
        assertEquals(size - 1, vm.preferences.size)
        repeat(200) { vm.command(Command.ZOOM_IN) }
        assertEquals(limits().maxSize, vm.preferences.size)
        val focus = vm.preferences.focus
        vm.command(Command.SPOTLIGHT)
        assertEquals(nextFocus(focus), vm.preferences.focus)
        val anchor = vm.preferences.typewriter
        vm.command(Command.ANCHOR)
        assertEquals(!anchor, vm.preferences.typewriter)
        val theme = vm.preferences.theme
        vm.command(Command.THEME)
        assertEquals(nextTheme(theme), vm.preferences.theme)
    }

    @Test fun theStatisticsFollowTheText() {
        val vm = create(SavedStateHandle())
        openNew(vm)
        assertTrue(vm.accept("one two three"))
        until("the statistics show") { vm.stats.startsWith("3 words") }
    }

    // ---- helpers

    private fun create(handle: SavedStateHandle): ShellViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(app, handle) as T
        }
        return ViewModelProvider(store, factory)[ShellViewModel::class.java].also { made += it }
    }

    /** The process is killed: no onCleared, nothing more runs; only the saved state is left. */
    private fun processDeath(vm: ShellViewModel) {
        vm.viewModelScope.cancel()
        stores.removeAt(0)  // never cleared
    }

    /**
     * The saved state as a new process gets it back: saved through the handle's own provider,
     * as onSaveInstanceState does (the draft and caret providers run then), and restored.
     */
    @SuppressLint("RestrictedApi")
    private fun restored(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle.createHandle(handle.savedStateProvider().saveState(), null)

    private fun openNew(vm: ShellViewModel): String {
        settle()
        vm.newDocument()
        until("a new document opens") { vm.screen is Screen.Editor }
        return (vm.screen as Screen.Editor).name
    }

    private fun writable(folder: File, yes: Boolean) {
        folder.mkdirs()
        folder.setWritable(yes)
    }

    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(5)
        }
    }

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what; notices: $seen")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(5)
        }
    }
}
