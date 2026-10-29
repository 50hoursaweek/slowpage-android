package com.fiftyhoursaweek.slowpage

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.text.TextRange
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
import uniffi.hushhour.defaultPreferences
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
        assertEquals("Words that could not be saved", app.recovery.read(name))

        processDeath(vm)
        writable(app.store.folder, true)
        val again = create(restored(handle))
        until("the document reopens with its unsaved text") {
            again.screen == Screen.Editor(name) && again.field.text.toString() == "Words that could not be saved"
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
        until("the next open shows the kept text") { next.field.text.toString() == "Kept after the task closed" }
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
        until("the text comes back from the saved state") { again.field.text.toString() == "Only in the saved state" }
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

    // ---- MINOR 4: the caret after process death

    @Test fun processDeathKeepsTheCaret() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        val name = openNew(vm)
        assertTrue(vm.accept("Hello world"))
        vm.field.edit {
            replace(0, length, "Hello world")
            selection = TextRange(5)
        }
        vm.saveNow()

        processDeath(vm)
        val again = create(restored(handle))
        until("the document reopens") { again.screen == Screen.Editor(name) && again.field.text.isNotEmpty() }
        assertEquals(TextRange(5), again.field.selection)
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

    /** The saved state as a new process gets it back. */
    private fun restored(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

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
