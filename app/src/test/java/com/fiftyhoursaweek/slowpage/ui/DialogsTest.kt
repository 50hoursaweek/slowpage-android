package com.fiftyhoursaweek.slowpage.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.fiftyhoursaweek.slowpage.BuildConfig
import com.fiftyhoursaweek.slowpage.Dialog
import com.fiftyhoursaweek.slowpage.Screen
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.SlowpageApp
import com.fiftyhoursaweek.slowpage.TourExit
import com.fiftyhoursaweek.slowpage.audio.AudioController
import com.fiftyhoursaweek.slowpage.audio.AudioControllerTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.hushhour.Backdrop
import uniffi.hushhour.FontChoice
import uniffi.hushhour.MusicChoice
import uniffi.hushhour.SoundSet
import uniffi.hushhour.Theme
import uniffi.hushhour.limits
import uniffi.hushhour.musicLabel
import java.time.Duration
import java.util.concurrent.Executors

/**
 * The dialogs (O5), the iPad's `Dialogs.swift`: every control is the real setting, by touch;
 * no frosted glass (E17); About with version, build, the link, credits and every licence text;
 * the editor's menu opens them and has no sound items of its own any more.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land")
class DialogsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: ShellViewModel
    private val lane = Executors.newSingleThreadScheduledExecutor { Thread(it, "audio-test") }
    private var imports = 0

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication() as SlowpageApp
        app.audio = AudioController({ AudioControllerTest.FakeMixer() }, AudioControllerTest.FakeOutput(), lane)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(app, SavedStateHandle()) as T
        }
        vm = ViewModelProvider(store, factory)[ShellViewModel::class.java]
        until("ready") { vm.loaded }
        vm.finishTour(TourExit.CLOSE)
    }

    @After fun tearDown() {
        store.clear()
        lane.shutdownNow()
    }

    private fun show() {
        compose.setContent {
            SlowpageTheme(vm.preferences.theme) {
                vm.dialog?.let { DialogSheet(it, vm, onImportMusic = { imports++ }) }
            }
        }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    @Test fun appearanceChangesTheRealSettingsAndHasNoFrostedGlass() {
        vm.showDialog(Dialog.APPEARANCE)
        show()
        compose.onNodeWithText("Appearance").assertExists()
        click("Theme-Dark")
        assertEquals(Theme.DARK, vm.preferences.theme)
        click("Font-Serif")
        assertEquals(FontChoice.SERIF, vm.preferences.font)
        click("Background-Drift")
        assertEquals(Backdrop.DRIFT, vm.preferences.backdrop)
        compose.onNodeWithText("Rests while you type; still with Remove animations.").assertExists()
        compose.onNodeWithText("${vm.preferences.size.toInt()} pt").assertExists()
        compose.onAllNodes(hasText("frost", substring = true, ignoreCase = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("glass", substring = true, ignoreCase = true)).assertCountEquals(0)
        click(DONE_TAG)
        assertNull(vm.dialog)
    }

    @Test fun theLineHeightMovesInSteps() {
        val l = limits()
        vm.setLineHeight(1.5321f)
        assertEquals(1.55f, vm.preferences.lineHeight, 1e-6f)
        vm.setLineHeight(99f)
        assertEquals(l.maxLineHeight, vm.preferences.lineHeight)
    }

    @Test fun soundAndMusicHasTheTypingSoundTheMusicAndTheirVolumes() {
        vm.showDialog(Dialog.SOUND)
        show()
        compose.onNodeWithText("Sound and music").assertExists()
        // F1: the iPad's defaults, music at 50%.
        compose.onAllNodesWithText("50%").assertCountEquals(1)
        compose.onAllNodesWithText("60%").assertCountEquals(1)
        click("toggle-Enabled")
        assertFalse(vm.preferences.typingSound)
        click("Sound-Keyboard")
        assertEquals(SoundSet.KEYBOARD, vm.preferences.soundSet)
        click("musicPicker")
        compose.onNodeWithText(musicLabel(MusicChoice.Rain)).performClick()
        compose.waitForIdle()
        assertEquals(MusicChoice.Rain, vm.preferences.music)
        click("importMusic")
        assertEquals(1, imports)
    }

    @Test fun aboutShowsTheVersionTheLinkTheCreditsAndEveryLicenceText() {
        vm.showDialog(Dialog.ABOUT)
        show()
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}").assertExists()
        compose.onNodeWithText("github.com/50hoursaweek/hushhour").assertExists()
        compose.onNodeWithText("© 2026 Grunt").assertExists()
        click("credits")
        compose.onNodeWithText("Credits and licences").assertExists()
        compose.onNodeWithText("‹ About").assertExists()
        compose.onNodeWithText("Licence texts").assertExists()
        val assets = compose.activity.assets
        for ((title, file) in LICENCES) {
            assertTrue("$title: $file is in the build", assets.open(file).use { it.read() } >= 0)
        }
        click("licence-Apache License 2.0")
        compose.onNodeWithText("TERMS AND CONDITIONS FOR USE", substring = true).assertExists()
        compose.onNodeWithText("‹ Credits and licences").performClick()
        compose.waitForIdle()
        click("licence-Android libraries")
        compose.onNodeWithText("Oboe (Google)", substring = true).assertExists()
        compose.onNodeWithText("‹ Credits and licences").performClick()
        compose.onNodeWithText("‹ About").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("aboutWelcome").assertExists()
    }

    /** On a phone the back link, the title and Done stay apart (Francesco's phone test). */
    @Config(qualifiers = "w360dp-h780dp-port")
    @Test fun atPhoneWidthTheTitleBarsDoNotOverlap() {
        vm.showDialog(Dialog.ABOUT)
        show()
        fun apart(back: String, title: String) {
            val boxes = listOf(back, title).map { compose.onNodeWithText(it, useUnmergedTree = true).getBoundsInRoot() } +
                compose.onNodeWithTag(DONE_TAG, useUnmergedTree = true).getBoundsInRoot()
            boxes.zipWithNext { a, b -> assertTrue("$title: $a ends before $b", a.right <= b.left) }
        }
        click("credits")
        apart("‹ About", "Credits and licences")
        click("licence-Apache License 2.0")
        apart("‹ Credits and licences", "Apache License 2.0")
    }

    @Test fun theEditorsMenuHasOnlyTheFileItemsUndoThePopUpsAndAbout() {
        vm.newDocument()
        until("a document") { vm.screen is Screen.Editor && vm.highlight != null }
        compose.setContent {
            SlowpageTheme(vm.preferences.theme) {
                EditorScreen(vm, (vm.screen as Screen.Editor).name, vm.status, onExport = {}, onImport = {})
                vm.dialog?.let { DialogSheet(it, vm, onImportMusic = {}) }
            }
        }
        compose.onNodeWithTag(MENU_TAG).performClick()
        compose.waitForIdle()
        for (item in listOf("Appearance…", "Sound and music…", "Welcome…", "About Slowpage…")) compose.onNodeWithText(item).assertExists()
        for (gone in listOf("Import music…", "Music: Rain", "Typing sound")) compose.onAllNodes(hasText(gone, substring = true)).assertCountEquals(0)
        // Exactly the file items, Undo and Redo, the two pop-ups, Welcome and About, in this order:
        // theme, font, view, spotlight, anchor and text size live only in Appearance, on the
        // status line and on the keyboard (Francesco's install test). Rename… is new in 1.0.1.
        val items = compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
        assertEquals(
            listOf("New", "Import…", "Rename…", "Export…", "Close", "Undo", "Redo", "Appearance…", "Sound and music…", "Welcome…", "About Slowpage…"),
            items,
        )
        compose.onNodeWithText("Sound and music…").performClick()
        compose.waitForIdle()
        assertEquals(Dialog.SOUND, vm.dialog)
        compose.onAllNodesWithText("Volume").assertCountEquals(2)
    }

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(5)
        }
    }
}
