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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import com.fiftyhoursaweek.slowpage.store.Recents
import com.fiftyhoursaweek.slowpage.store.Recovery
import com.fiftyhoursaweek.slowpage.audio.AudioController
import com.fiftyhoursaweek.slowpage.audio.AudioControllerTest
import com.fiftyhoursaweek.slowpage.editor.Command
import com.fiftyhoursaweek.slowpage.editor.LineTable
import com.fiftyhoursaweek.slowpage.editor.Paragraphs
import com.fiftyhoursaweek.slowpage.preview.PreviewLook
import uniffi.hushhour.FontChoice
import uniffi.hushhour.MusicChoice
import uniffi.hushhour.Theme
import uniffi.hushhour.ViewMode
import uniffi.hushhour.defaultPreferences
import uniffi.hushhour.limits
import uniffi.hushhour.musicLabel
import uniffi.hushhour.nextFocus
import uniffi.hushhour.nextTheme
import uniffi.hushhour.serializePreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    private val audioLane = Executors.newSingleThreadScheduledExecutor { Thread(it, "audio-test") }
    private val mixer = AudioControllerTest.FakeMixer()
    private val output = AudioControllerTest.FakeOutput()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as SlowpageApp
        app.audio = AudioController({ mixer }, output, audioLane)
    }

    @After fun tearDown() {
        writable(app.store.folder, true)
        writable(app.recovery.folder, true)
        stores.forEach { it.clear() }
        audioLane.shutdownNow()
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
        // About 390,000 characters, in paragraphs (one that long is over the paragraph cap).
        val big = (1..40_000).joinToString(" ") { if (it % 1000 == 0) "word$it\n" else "word$it" }
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
        val big = ("a".repeat(1023) + "\n").repeat(20 * 1024)
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

    @Test fun aCloseDuringTheStatisticsPauseNeverReachesTheClosedDocument() {
        // Round 1, BLOCKING 2: the statistics run 150 ms after a change; a close that comes
        // first used to leave them to run on the closed core document, a crash.
        val vm = create(SavedStateHandle())
        repeat(5) {
            openNew(vm)
            assertTrue(vm.accept("one two three ${it}"))
            vm.closeEditor()
            until("closed") { vm.screen == Screen.Start }
            settle()  // well past the pause
        }
        assertEquals(Screen.Start, vm.screen)
    }

    @Test fun aDocumentWithAParagraphOverTheCapOpensReadOnlyAndIsNeverChanged() {
        // Round 2, ruling B: a paragraph over the cap could not be edited smoothly.
        val vm = create(SavedStateHandle())
        settle()
        val over = "word ".repeat(Paragraphs.CAP / 5) + "x"
        val text = "# Title\n\n$over\nlast line\n"
        app.store.write("Long.md", text)
        vm.open("Long.md")
        until("it opens") { vm.screen is Screen.Editor }
        val shown = vm.readOnly!!
        assertEquals("shown whole", text, shown.text)
        assertNull("no editor", vm.highlight)
        assertFalse("nothing typed goes in", vm.accept(text + "more"))
        assertFalse(vm.host.fits(0, 0, "a"))
        vm.command(Command.UNDO)
        vm.setView(ViewMode.SPLIT)
        assertEquals("no preview to switch to", ViewMode.EDITOR, vm.viewMode)
        vm.closeEditor()
        until("closed") { vm.screen == Screen.Start }
        assertEquals("never changed", text, app.store.read("Long.md"))
        assertNull(vm.readOnly)
        // At the cap it is an ordinary document.
        app.store.write("AtCap.md", "a".repeat(Paragraphs.CAP))
        vm.open("AtCap.md")
        until("it opens") { vm.highlight != null }
        assertNull(vm.readOnly)
    }

    @Test fun aRecoveredDocumentOverTheCapKeepsTheRecoveredTextWhateverUndoIsTried() {
        // Round 3, Codex NEW-B1: the recovery is applied as an edit before the cap is checked,
        // so it is in the history; an undo used to put the older text back and save it.
        val vm = create(SavedStateHandle())
        settle()
        val older = "Older version\n" + "o".repeat(Paragraphs.CAP + 1)
        val recovered = "Recovered version\n" + "r".repeat(Paragraphs.CAP + 1)
        app.store.write("Kept.md", older)
        assertTrue(app.recovery.write("Kept.md", recovered, stamp = System.currentTimeMillis() + 60_000) == null)
        vm.open("Kept.md")
        until("it opens read-only") { vm.readOnly != null }
        assertEquals(recovered, vm.readOnly!!.text)
        assertFalse("no Undo offered", vm.canUndo)
        // The menu item, Ctrl+Z, Ctrl+Shift+Z and the editor all come here.
        vm.command(Command.UNDO)
        vm.command(Command.REDO)
        vm.host.undo()
        until("the recovered text is on disk") { app.store.read("Kept.md") == recovered }
        vm.closeEditor()
        until("closed") { vm.screen == Screen.Start }
        assertEquals("still the recovered text", recovered, app.store.read("Kept.md"))
        until("and the recovery copy is no longer needed") { !app.recovery.has("Kept.md") }
    }

    @Test fun anEditThatWouldMakeAParagraphOverTheCapIsRefusedWithANotice() {
        val vm = create(SavedStateHandle())
        openNew(vm)
        val half = "b".repeat(Paragraphs.CAP / 2)
        assertTrue(vm.accept("$half\n$half"))
        // Typing up to the cap is fine; one more character is not.
        assertTrue(vm.accept("$half\n$half" + "c".repeat(Paragraphs.CAP / 2)))
        val full = vm.editorText()
        assertFalse(vm.host.fits(full.length, full.length, "d"))
        assertTrue(vm.notice!!.contains("paragraph"))
        // Joining two paragraphs past it (deleting the line break) is refused too.
        assertFalse(vm.host.fits(half.length, half.length + 1, ""))
        // A paste that brings one over the cap, even between line breaks.
        assertFalse(vm.host.fits(0, 0, "x\n" + "y".repeat(Paragraphs.CAP + 1) + "\nz"))
        assertTrue("a paste of ordinary paragraphs", vm.host.fits(0, 0, "x\n" + "y".repeat(1000) + "\n"))
        assertTrue("deleting within the long one", vm.host.fits(full.length - 1, full.length, ""))
        assertEquals(full, vm.editorText())
    }

    // ---- Phase 5: the music follows the document (A5)

    @Test fun musicPlaysOnlyWhileADocumentIsOpenAndTheTrackStaysChecked() {
        val vm = create(SavedStateHandle())
        settle()
        assertEquals("the app starts silent (A4)", MusicChoice.Off, vm.preferences.music)
        // Checked on the start screen (the tour, phase 6): it waits for a document.
        vm.setMusic(MusicChoice.Rain)
        settle()
        assertEquals(MusicChoice.Off, app.audio.playingMusic)
        openNew(vm)
        assertEquals("plays with the document", MusicChoice.Rain, app.audio.playingMusic)
        // New over an open document is a switch: the music goes on.
        vm.command(Command.NEW)
        until("the next document") { vm.screen is Screen.Editor && mixer.music().size == 1 && vm.highlight != null }
        settle()
        assertEquals("switching keeps it playing", listOf("music ${MusicChoice.Rain}"), mixer.music())
        vm.closeEditor()
        until("closed") { vm.screen == Screen.Start }
        assertEquals("the start screen is silent", MusicChoice.Off, app.audio.playingMusic)
        assertEquals("the track stays checked", MusicChoice.Rain, vm.preferences.music)
        openNew(vm)
        assertEquals("and starts with the next document", MusicChoice.Rain, app.audio.playingMusic)
        // Not saved: a new start is silent.
        assertEquals(MusicChoice.Off, uniffi.hushhour.parsePreferences(app.preferencesFile.readText()).music)
    }

    @Test fun aRemovedTaskLeavesNoMusicForTheNextWindowAndNoViewModelBehind() {
        // Codex phase 5, BLOCKING 2 and MINOR 5: the process stays, the task goes.
        mixer.active = true
        val vm = create(SavedStateHandle())
        openNew(vm)
        vm.setMusic(MusicChoice.Rain)
        audioLane.submit {}.get()
        assertEquals(listOf("start"), output.all())
        app.audio.background()  // onStop
        stores.removeAt(0).clear()  // onCleared
        assertEquals(MusicChoice.Off, app.audio.playingMusic)
        assertNull("the controller keeps nothing of the cleared view model", app.audio.onProblem)
        app.audio.foreground()  // a new window, before its view model has read the preferences
        settle()
        audioLane.submit {}.get()
        assertEquals("nothing starts on the start screen", listOf("start", "stop"), output.all())
        assertEquals(listOf("music ${MusicChoice.Rain}", "music ${MusicChoice.Off}"), mixer.music())
        val next = create(SavedStateHandle())
        settle()
        assertEquals(MusicChoice.Off, next.preferences.music)
        assertNotNull(app.audio.onProblem)
    }

    @Test fun aQuickRelaunchWhileTheAudioThreadIsBusyNeverResumesTheOldTrack() {
        // Codex phase 6 audit, BLOCKING 2: the stop and the silence are still queued behind a
        // busy audio thread when the new window comes to the front. Nothing is drained between.
        mixer.active = true
        val vm = create(SavedStateHandle())
        openNew(vm)
        vm.setMusic(MusicChoice.Rain)
        audioLane.submit {}.get()
        assertTrue(output.audible)
        val busy = CountDownLatch(1)
        audioLane.execute { busy.await(10, TimeUnit.SECONDS) }
        app.audio.background()  // onStop
        stores.removeAt(0).clear()  // onCleared
        app.audio.foreground()  // the new window's onStart, at once
        assertFalse("the old stream, still running with Rain, is not heard", output.audible)
        busy.countDown()
        audioLane.submit {}.get()
        assertEquals(listOf("start", "allow false", "stop", "allow true"), output.log().filter { it != "opening" })
        assertEquals("the silence reached the core before the output was allowed", listOf("music ${MusicChoice.Rain}", "music ${MusicChoice.Off}"), mixer.music())
        assertFalse("and nothing starts on the start screen", output.running)
        // Home and back quickly, while busy: still silent, then allowed once in front.
        val busyAgain = CountDownLatch(1)
        audioLane.execute { busyAgain.await(10, TimeUnit.SECONDS) }
        app.audio.background()
        app.audio.foreground()
        app.audio.background()
        busyAgain.countDown()
        audioLane.submit {}.get()
        assertFalse("left again before the thread got to it: stays not allowed", output.allowed)
    }

    @Test fun anOlderForegroundStillQueuedNeverAllowsTheOutputBeforeALaterStopAndSilence() {
        // Codex phase 6 round 2: a foreground F0 already queued behind a busy thread, then
        // background and stop, onCleared and Off, foreground F1. Nothing is drained between.
        mixer.active = true
        val vm = create(SavedStateHandle())
        openNew(vm)
        vm.setMusic(MusicChoice.Rain)
        audioLane.submit {}.get()
        val busy = CountDownLatch(1)
        audioLane.execute { busy.await(10, TimeUnit.SECONDS) }
        app.audio.background()  // home
        app.audio.foreground()  // back: F0, queued
        app.audio.background()  // the task is removed
        stores.removeAt(0).clear()  // onCleared: Off, queued
        app.audio.foreground()  // a new window: F1
        assertFalse(output.audible)
        busy.countDown()
        audioLane.submit {}.get()
        assertEquals(
            "F0 does nothing: the only allow is F1's, after the stop",
            listOf("start", "allow false", "allow false", "stop", "allow true"),
            output.log().filter { it != "opening" },
        )
        assertEquals(listOf("music ${MusicChoice.Rain}", "music ${MusicChoice.Off}"), mixer.music())
        assertFalse("and nothing starts on the start screen", output.running)
    }

    @Test fun aFailedSampleOfATrackNotChosenLeavesTheMusicAndSaysOnlyThatItFailed() {
        // Codex phase 6 round 2, MINOR: the chosen track plays on, so the notice does not say Off.
        val vm = create(SavedStateHandle())
        openNew(vm)
        vm.setMusic(MusicChoice.Rain)
        audioLane.submit {}.get()
        val mine = MusicChoice.File("mine.mp3")
        mixer.active = false  // the sample's file fails at once
        vm.notice = null
        vm.playSample(mine)
        // Real time only: the quiet check runs on the audio thread's clock, and the sample's
        // own ten seconds must not pass on the main looper's meanwhile.
        val end = System.currentTimeMillis() + 10_000
        while (vm.notice == null) {
            if (System.currentTimeMillis() > end) fail("timed out: the failure reaches the view model")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals("“${musicLabel(mine)}” could not be played.", vm.notice)
        assertEquals(MusicChoice.Rain, vm.preferences.music)
        assertEquals(MusicChoice.Rain, app.audio.playingMusic)
        assertNull(vm.sample)
    }

    @Test fun aTrackThatCannotBeDecodedIsUntickedAndTheStatusLineSaysSo() {
        // Codex phase 5, MINOR 3: a damaged file with a valid header imports, then fails in the core.
        val vm = create(SavedStateHandle())
        openNew(vm)
        val mine = MusicChoice.File("mine.mp3")
        mixer.active = false  // the core dropped it at once
        vm.setMusic(mine)
        until("the failure reaches the view model") { vm.preferences.music == MusicChoice.Off }
        assertEquals("“${musicLabel(mine)}” could not be played, so Music is Off.", vm.notice)
        assertEquals(MusicChoice.Off, app.audio.playingMusic)
    }

    @Test fun typingMakesOneKeySoundPerChangeByKind() {
        val vm = create(SavedStateHandle())
        openNew(vm)
        vm.accept("a")
        vm.accept("a\n")
        vm.accept("a")
        settle()
        audioLane.submit {}.get()
        assertEquals(listOf("CHARACTER", "ENTER", "BACKSPACE"), mixer.keys().map { it.split(' ')[1] })
    }

    // ---- helpers

    // ---- Rename (R1, 1.0.1)

    @Test fun renamingTheOpenDocumentSavesItFirstAndEverythingFollowsTheNewName() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        val name = openNew(vm)
        assertTrue(vm.accept("Typed before the rename"))  // not saved yet: the autosave waits
        vm.rename(name, "  Letter.md ")  // the extension typed too: not doubled
        until("the editor names the new file") { vm.screen == Screen.Editor("Letter.md") }
        assertEquals("Renamed to Letter", vm.notice)
        assertEquals("Typed before the rename", app.store.read("Letter.md"))
        assertFalse(app.store.exists(name))
        until("Recent names it, and not the old name") { vm.recent == listOf("Letter.md") }
        assertEquals(listOf("Letter.md"), Recents(File(app.filesDir, "recents")).names())

        // Later text goes to the new name; nothing writes the old one again.
        assertTrue(vm.accept("Typed after the rename"))
        until("autosaved under the new name") { app.store.read("Letter.md") == "Typed after the rename" }
        assertTrue(vm.accept("Typed last"))
        vm.saveNow()  // onStop
        assertEquals("Typed last", app.store.read("Letter.md"))
        assertFalse(app.store.exists(name))

        // A failed save keeps the text under the new name, and process death reopens the new name.
        assertTrue(vm.accept("Kept under the new name"))
        writable(app.store.folder, false)
        vm.saveNow()
        assertEquals("Kept under the new name", app.recovery.read("Letter.md")?.text)
        assertFalse(app.recovery.has(name))
        processDeath(vm)
        writable(app.store.folder, true)
        val again = create(restored(handle))
        until("reopened under the new name, with the kept text") {
            again.screen == Screen.Editor("Letter.md") && again.editorText() == "Kept under the new name"
        }
        assertFalse(app.store.exists(name))
    }

    @Test fun aRefusedNameChangesNothingAndTheSameNameIsNoChange() {
        val vm = create(SavedStateHandle())
        val name = openNew(vm)
        app.store.write("Taken.md", "other")
        val refusals = listOf(
            "" to "The name is empty.",
            "   " to "The name is empty.",
            ".md" to "The name is empty.",
            "a/b" to "A name cannot contain “/” or “:”.",
            "a:b" to "A name cannot contain “/” or “:”.",
            ".hidden" to "A name cannot start with a dot.",
            "x".repeat(260) to "The name is too long.",
            "a\tb" to "A name cannot contain a line break, a tab or another control character.",
            "Taken" to "A document named “Taken” already exists here.",
            "Taken.md" to "A document named “Taken” already exists here.",
        )
        for ((input, said) in refusals) {
            vm.notice = null
            vm.rename(name, input)
            until("refused: $input") { vm.notice != null }
            assertEquals("Couldn't rename: $said", vm.notice)
            assertEquals(Screen.Editor(name), vm.screen)
        }
        assertEquals("other", app.store.read("Taken.md"))
        assertTrue(app.store.exists(name))
        vm.notice = null
        vm.rename(name, name.removeSuffix(".md"))  // the same name: nothing happens, nothing said
        settle()
        assertNull(vm.notice)
        assertEquals(Screen.Editor(name), vm.screen)
        assertTrue(app.store.exists(name))
    }

    @Test fun whenTheTextCannotBeSavedFirstNothingIsRenamed() {
        val vm = create(SavedStateHandle())
        val name = openNew(vm)
        assertTrue(vm.accept("Unsaved"))
        writable(app.store.folder, false)
        vm.rename(name, "Letter")
        until("refused") { vm.notice != null }
        assertTrue(vm.notice!!, vm.notice!!.startsWith("Couldn't rename: The text could not be saved first: "))
        assertEquals(Screen.Editor(name), vm.screen)
        assertTrue(vm.status, vm.status.startsWith("Not saved"))
        assertTrue(app.store.exists(name))
        assertFalse(app.store.exists("Letter.md"))
        assertEquals("Unsaved", app.recovery.read(name)?.text)  // kept, as after any failed save
        writable(app.store.folder, true)
        until("saved under its own name once the disk takes it") { vm.closeEditor(); vm.screen == Screen.Start }
        assertEquals("Unsaved", app.store.read(name))
    }

    @Test fun renamingFromTheStartScreenMovesTheDocumentItsRecentPlaceAndItsKeptText() {
        val vm = create(SavedStateHandle())
        settle()
        for (n in listOf("a.md", "b.md", "c.md")) app.store.write(n, n)
        listOf("c.md", "b.md", "a.md").forEach(app.recents::add)
        assertNull(app.recovery.write("b.md", "kept", stamp = 1))
        vm.rename("b.md", "Bee")
        until("Recent has the new name in the old one's place") { vm.recent == listOf("a.md", "Bee.md", "c.md") }
        assertEquals(listOf("a.md", "Bee.md", "c.md"), Recents(File(app.filesDir, "recents")).names())
        assertEquals("b.md", app.store.read("Bee.md"))
        assertFalse(app.store.exists("b.md"))
        assertEquals("kept", app.recovery.read("Bee.md")?.text)
        assertFalse(app.recovery.has("b.md"))
        assertTrue(vm.documents.any { it.name == "Bee.md" } && vm.documents.none { it.name == "b.md" })
        assertNull(vm.notice)  // as on the iPad: nothing to say when it worked
        assertEquals(Screen.Start, vm.screen)

        vm.rename("a.md", "c")
        until("refused") { vm.notice != null }
        assertEquals("Couldn't rename a: A document named “c” already exists here.", vm.notice)
        assertEquals("a.md", app.store.read("a.md"))
        assertEquals("c.md", app.store.read("c.md"))
    }

    /** The iPad's setEditingLocked: what is saved before the move is all there is (Codex rename round 1, BLOCKING 1). */
    @Test fun nothingCanBeTypedOrUndoneWhileTheOpenDocumentIsRenamed() {
        val handle = SavedStateHandle()
        val vm = create(handle)
        val name = openNew(vm)
        assertTrue(vm.accept("Before"))
        vm.rename(name, "Letter")
        assertTrue(vm.renaming)  // from the moment it is asked, before any work
        assertFalse(vm.accept("During"))
        vm.command(Command.UNDO)
        assertEquals("Before", vm.editorText())
        assertEquals("Letter.md", handle.get<String>("open"))
        assertEquals(name, handle.get<String>("renamedFrom"))
        until("renamed") { vm.screen == Screen.Editor("Letter.md") }
        assertFalse(vm.renaming)
        assertNull(handle.get<String>("renamedFrom"))
        assertEquals("Before", app.store.read("Letter.md"))
        assertTrue(vm.accept("After"))
    }

    /** Process death in the middle of a rename reopens the name the file has (Codex rename round 1, BLOCKING 1). */
    @Test fun anEndInTheMiddleOfARenameReopensTheNameThatExists() {
        app.store.write("Old.md", "words")
        val before = create(SavedStateHandle(mapOf("open" to "New.md", "renamedFrom" to "Old.md")))
        until("the old name, the move not done") { before.screen == Screen.Editor("Old.md") }
        before.closeEditor()
        until("closed") { before.screen == Screen.Start }

        app.store.rename("Old.md", "New.md")
        val after = create(SavedStateHandle(mapOf("open" to "New.md", "renamedFrom" to "Old.md")))
        until("the new name, the move done") { after.screen == Screen.Editor("New.md") }
        assertEquals("words", after.editorText())
    }

    /** Kept unsaved text that cannot follow: the document goes back, nothing is lost (Codex rename round 1, BLOCKING 2). */
    @Test fun whenTheKeptTextCannotFollowTheDocumentStaysUnderItsName() {
        val vm = create(SavedStateHandle())
        settle()
        app.store.write("a.md", "on disk")
        app.recents.add("a.md")
        assertNull(app.recovery.write("a.md", "kept", stamp = System.currentTimeMillis() + 60_000))
        // Something the kept copy cannot replace: a folder that is not empty.
        File(app.recovery.folder, "b.md/inside").apply { parentFile!!.mkdirs(); writeText("x") }
        vm.rename("a.md", "b")
        until("refused") { vm.notice != null }
        assertEquals("Couldn't rename a: Its kept unsaved text could not follow the new name, so nothing was moved.", vm.notice)
        assertEquals("on disk", app.store.read("a.md"))
        assertFalse(app.store.exists("b.md"))
        assertEquals("kept", app.recovery.read("a.md")?.text)
        until("still in Recent") { vm.recent == listOf("a.md") }
        vm.open("a.md")
        until("opens with the kept text") { vm.screen == Screen.Editor("a.md") && vm.editorText() == "kept" }
    }

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
