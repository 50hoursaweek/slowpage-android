package com.fiftyhoursaweek.slowpage.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import uniffi.hushhour.MusicChoice
import uniffi.hushhour.Theme
import uniffi.hushhour.backdropTiming
import uniffi.hushhour.musicLabel
import uniffi.hushhour.palette
import uniffi.hushhour.parsePreferences
import java.time.Duration
import java.util.concurrent.Executors

/**
 * The welcome tour (O1, O3, O4) and its sound (A6), the iPad's `OnboardingTests`: shown once,
 * five cards, one document for New document however often it is tapped, Start writing over a
 * document and About's link to it, the pattern only on its card, and a track checked in the
 * tour waiting for a document.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land")
class TourTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var app: SlowpageApp
    private val lane = Executors.newSingleThreadScheduledExecutor { Thread(it, "audio-test") }
    private val mixer = AudioControllerTest.FakeMixer()
    private val exits = mutableListOf<TourExit>()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as SlowpageApp
        app.audio = AudioController({ mixer }, AudioControllerTest.FakeOutput(), lane)
    }

    @After fun tearDown() {
        stores.forEach { it.clear() }
        lane.shutdownNow()
    }

    private fun create(): ShellViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(app, SavedStateHandle()) as T
        }
        return ViewModelProvider(store, factory)[ShellViewModel::class.java].also { vm -> until("ready") { vm.loaded } }
    }

    /** The tour and the dialogs as the activity shows them. */
    private fun show(vm: ShellViewModel) {
        compose.setContent {
            SlowpageTheme(vm.preferences.theme) {
                vm.dialog?.let { DialogSheet(it, vm, onImportMusic = {}) }
                if (vm.tourOffersStart != null) WelcomeTour(vm) { exit -> if (vm.finishTour(exit)) exits += exit }
            }
        }
        compose.waitForIdle()
    }

    private fun next(times: Int = 1) = repeat(times) {
        compose.onNodeWithTag("onboardingNext").performClick()
        compose.waitForIdle()
    }

    @Test fun theTourShowsOnceAtFirstLaunchAndWalksThroughFiveCards() {
        val vm = create()
        assertEquals("first launch: over the start screen", true, vm.tourOffersStart)
        until("the flag is claimed") { app.preferencesFile.exists() && !parsePreferences(app.preferencesFile.readText()).showWelcome }
        show(vm)
        compose.onNodeWithText("A quiet place to write.").assertExists()
        for (title in listOf("The look", "The background", "Sound", "Ready when you are")) {
            next()
            compose.onNodeWithText(title).assertExists()
        }
        assertEquals(4, vm.tourCard)
        compose.onNodeWithTag("onboardingStartWriting").assertDoesNotExist()
        compose.onNodeWithTag("onboardingImport").assertExists()
        compose.onNodeWithTag("onboardingNew").performClick()
        compose.waitForIdle()
        until("one new document") { vm.screen is Screen.Editor }
        assertNull(vm.tourOffersStart)

        // The next launch: no tour.
        stores.removeAt(0).clear()
        assertNull(create().tourOffersStart)
    }

    @Test fun newDocumentOnTheLastCardOpensOneDocumentEvenIfTappedTwice() {
        val vm = create()
        val before = app.store.list().size
        assertTrue(vm.finishTour(TourExit.NEW_DOCUMENT))
        assertFalse("a second tap does nothing", vm.finishTour(TourExit.NEW_DOCUMENT))
        until("the document") { vm.screen is Screen.Editor }
        settle()
        assertEquals(before + 1, app.store.list().size)
    }

    @Test fun overADocumentTheTourEndsWithStartWritingAndAboutLinksToIt() {
        val vm = create()
        vm.finishTour(TourExit.CLOSE)
        vm.newDocument()
        until("a document") { vm.screen is Screen.Editor }
        vm.showDialog(Dialog.ABOUT)
        show(vm)
        compose.onNodeWithTag("aboutWelcome").performClick()
        compose.waitForIdle()
        assertNull("About closes", vm.dialog)
        assertEquals("the tour is over the document", false, vm.tourOffersStart)
        next(4)
        compose.onNodeWithTag("onboardingNew").assertDoesNotExist()
        compose.onNodeWithTag("onboardingStartWriting").performClick()
        compose.waitForIdle()
        assertEquals(listOf(TourExit.CLOSE), exits)
        assertTrue(vm.screen is Screen.Editor)
    }

    @Test fun aTrackCheckedInTheTourWaitsUntilADocumentOpensAndASampleStopsWithTheTour() {
        val vm = create()
        show(vm)
        next(3)
        compose.onNodeWithTag("music-${musicLabel(MusicChoice.Rain)}").performClick()
        compose.waitForIdle()
        assertEquals(MusicChoice.Rain, vm.preferences.music)
        assertEquals("checked, but silent until a document", MusicChoice.Off, app.audio.playingMusic)
        compose.onNodeWithTag("sample-${musicLabel(MusicChoice.Night)}").performClick()
        compose.waitForIdle()
        assertEquals("a sample plays over the silence", MusicChoice.Night, app.audio.playingMusic)
        assertEquals(MusicChoice.Night, vm.sample)
        compose.onNodeWithTag("sample-${musicLabel(MusicChoice.Night)}").performClick()  // ■
        compose.waitForIdle()
        assertNull(vm.sample)
        compose.onNodeWithTag("sample-${musicLabel(MusicChoice.Night)}").performClick()
        compose.waitForIdle()
        // Leaving the card leaves its sound.
        next()
        assertNull(vm.sample)
        assertEquals(MusicChoice.Off, app.audio.playingMusic)
        compose.onNodeWithTag("onboardingPrevious").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sample-${musicLabel(MusicChoice.Night)}").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("onboardingSkip").performClick()
        compose.waitForIdle()
        assertEquals("and stops with the tour", MusicChoice.Off, app.audio.playingMusic)
        vm.newDocument()
        until("a document") { vm.screen is Screen.Editor }
        assertEquals("the checked track starts with the first document", MusicChoice.Rain, app.audio.playingMusic)
    }

    @Test fun overAnOpenDocumentATrackTickedInTheTourWaitsForTheNextDocument() {
        val vm = create()
        vm.finishTour(TourExit.CLOSE)
        vm.setMusic(MusicChoice.Night)
        vm.newDocument()
        until("a document") { vm.screen is Screen.Editor }
        assertEquals(MusicChoice.Night, app.audio.playingMusic)
        assertTrue(vm.showWelcome())
        vm.setMusic(MusicChoice.Rain)
        assertEquals("what plays goes on", MusicChoice.Night, app.audio.playingMusic)
        vm.finishTour(TourExit.CLOSE)
        assertEquals("also after the tour", MusicChoice.Night, app.audio.playingMusic)
        vm.closeEditor()
        until("closed") { vm.screen == Screen.Start }
        vm.newDocument()
        until("the next document") { vm.screen is Screen.Editor }
        assertEquals(MusicChoice.Rain, app.audio.playingMusic)
    }

    @Test fun theKeysOnTheSoundCardSoundAsTyped() {
        val vm = create()
        show(vm)
        next(3)
        compose.onNodeWithTag("keysTryout").performTextInput("ab")
        compose.waitForIdle()
        lane.submit {}.get()
        assertTrue(mixer.keys().isNotEmpty())
        assertTrue(mixer.keys().all { it.startsWith("key CHARACTER SOFT") })
        compose.onNodeWithTag("keys-Off").performClick()
        compose.waitForIdle()
        assertFalse(vm.preferences.typingSound)
    }

    @Test fun thePatternRunsOnlyOnItsCardAndStopsWithTheTour() {
        val vm = create()
        vm.updatePreferences(vm.preferences.copy(backdrop = Backdrop.DRIFT))
        show(vm)
        compose.onAllNodesWithTag(TOUR_BACKDROP_TAG).assertCountEquals(0)
        next(2)
        compose.onAllNodesWithTag(TOUR_BACKDROP_TAG).assertCountEquals(1)
        next()
        compose.onAllNodesWithTag(TOUR_BACKDROP_TAG).assertCountEquals(0)
        compose.onNodeWithTag("onboardingPrevious").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("background-Off").performClick()
        compose.waitForIdle()
        assertEquals("a chip is the real setting", Backdrop.OFF, vm.preferences.backdrop)
        compose.onAllNodesWithTag(TOUR_BACKDROP_TAG).assertCountEquals(0)
        compose.onNodeWithTag("background-Swell").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(TOUR_BACKDROP_TAG).assertCountEquals(1)
        compose.onNodeWithTag("onboardingSkip").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(TOUR_BACKDROP_TAG).assertCountEquals(0)
        compose.onAllNodesWithText("The background").assertCountEquals(0)
    }

    @Test fun everyTextInTheTourReachesAAInEveryThemeAndOverEveryPattern() {
        val strength = backdropTiming().strength
        val peaks = listOf(0f, 1 * strength, 1.2f * strength, 3 * strength)  // plain, Waves, Swell, Drift
        for (theme in listOf(Theme.LIGHT, Theme.SEPIA, Theme.DARK)) {
            val p = palette(theme)
            val quiet = Colours.secondary(p)
            for (peak in peaks) {
                val page = Colours.mix(p.background, p.text, peak)
                for ((name, colour) in listOf("text" to p.text, "quiet" to quiet)) {
                    val ratio = Colours.contrast(colour, page)
                    assertTrue("$theme $name at $peak: $ratio", ratio >= 4.5)
                }
            }
            assertTrue("$theme chosen chip", Colours.contrast(p.background, p.text) >= 4.5)
        }
    }

    private fun settle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        Thread.sleep(5)
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
