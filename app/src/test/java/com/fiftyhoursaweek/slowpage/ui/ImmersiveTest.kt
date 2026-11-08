package com.fiftyhoursaweek.slowpage.ui

import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
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
import com.fiftyhoursaweek.slowpage.Dialog
import com.fiftyhoursaweek.slowpage.Screen
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.SlowpageApp
import com.fiftyhoursaweek.slowpage.TourExit
import com.fiftyhoursaweek.slowpage.audio.AudioController
import com.fiftyhoursaweek.slowpage.audio.AudioControllerTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Full screen on every screen (Francesco, 2026-11-02). Composed as MainActivity composes them,
 * each window open at each step asks for the status and navigation bars hidden, shown only for a
 * moment by a swipe: the tour; the start screen, a document's ⋯ menu and the delete
 * confirmation; the editor, its Menu, Appearance, Sound and music with its music picker, and
 * About. Then the bars are brought back and the window loses and regains focus (as after the
 * background, a file picker, a closed pop-up or the keyboard): hidden again. Rotation recreates
 * the activity, which composes this again; the keyboard and the overlap of transient bars are
 * checked on the emulator, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class ImmersiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private val lane = Executors.newSingleThreadScheduledExecutor { Thread(it, "audio-test") }

    @After fun tearDown() {
        store.clear()
        lane.shutdownNow()
    }

    @Test fun everyWindowOfTheAppHidesTheBars() = walkThroughTheWindows()

    /** Android 10 and older (the Huawei tablet is Android 10-class): the system UI flags. */
    @Config(sdk = [Build.VERSION_CODES.Q])
    @Test fun everyWindowOfTheAppHidesTheBarsOnAndroid10() = walkThroughTheWindows()

    private fun walkThroughTheWindows() {
        val app = RuntimeEnvironment.getApplication() as SlowpageApp
        app.audio = AudioController({ AudioControllerTest.FakeMixer() }, AudioControllerTest.FakeOutput(), lane)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(app, SavedStateHandle()) as T
        }
        val vm = ViewModelProvider(store, factory)[ShellViewModel::class.java]
        until("ready") { vm.loaded }
        // The windows as MainActivity composes them.
        compose.setContent {
            SlowpageTheme(vm.preferences.theme) {
                ImmersiveWindow()
                when (val screen = vm.screen) {
                    Screen.Start -> StartScreen(
                        documents = vm.documents, recent = vm.recent, problems = vm.problems,
                        onNew = vm::newDocument, onImport = {}, onOpen = vm::open, onRename = vm::rename, onExport = {}, onDelete = vm::delete,
                    )
                    is Screen.Editor -> EditorScreen(vm, screen.name, vm.status, onExport = {}, onImport = {})
                }
                vm.dialog?.let { DialogSheet(it, vm, onImportMusic = {}) }
                if (vm.tourOffersStart != null) WelcomeTour(vm) { exit -> vm.finishTour(exit) }
            }
        }
        compose.waitForIdle()

        // The welcome tour on first launch, a window of its own.
        assertEquals("the tour", 1, dialogs())
        assertAllFullScreen("the activity and the tour")
        vm.finishTour(TourExit.CLOSE)
        compose.waitForIdle()
        assertEquals("the tour closed", 0, dialogs())
        assertAllFullScreen("the activity")

        vm.newDocument()
        until("a document") { vm.screen is Screen.Editor }
        vm.menuOpen = true
        compose.waitForIdle()
        assertEquals("the menu", 1, popups())
        assertAllFullScreen("the editor and its menu")
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()
        assertEquals("Rename from the editor", 1, dialogs())
        assertAllFullScreen("the editor and Rename")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        vm.menuOpen = false
        vm.showDialog(Dialog.APPEARANCE)
        compose.waitForIdle()
        assertEquals("Appearance", 1, dialogs())
        assertAllFullScreen("the editor and Appearance")
        vm.closeDialog()
        vm.showDialog(Dialog.SOUND)
        compose.waitForIdle()
        val menus = popups()
        compose.onNodeWithTag("musicPicker").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("the music picker", menus + 1, popups())
        assertAllFullScreen("Sound and music and its music picker")
        vm.closeDialog()
        vm.showDialog(Dialog.ABOUT)
        compose.waitForIdle()
        assertAllFullScreen("About")
        vm.closeDialog()
        compose.waitForIdle()

        // The start screen's ⋯ menu and the delete confirmation.
        vm.back()
        until("the start screen") { vm.screen == Screen.Start && vm.documents.isNotEmpty() }
        compose.waitForIdle()
        val before = popups()
        compose.onAllNodesWithText("⋯")[0].performClick()
        compose.waitForIdle()
        assertEquals("the ⋯ menu", before + 1, popups())
        assertAllFullScreen("the start screen and a document's ⋯ menu")
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()
        assertEquals("Rename from the start screen", 1, dialogs())
        assertAllFullScreen("Rename from the start screen")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("⋯")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete…").performClick()
        compose.waitForIdle()
        assertEquals("the delete confirmation", 1, dialogs())
        assertAllFullScreen("the delete confirmation")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        // The bars brought back (as by the system, a picker or the keyboard), then the window
        // loses and regains focus, as the platform reports it: hidden again. (Robolectric sends
        // no window focus on resume, so the focus change is dispatched as ViewRootImpl does.)
        val activity = compose.activity.window.decorView
        showBars(activity)
        focus(activity, false)
        focus(activity, true)
        compose.waitForIdle()
        assertAllFullScreen("the activity, back in front")
    }

    private fun focus(root: View, focused: Boolean) {
        ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnWindowFocusChange", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(root.viewTreeObserver, focused)
    }

    private fun showBars(root: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.windowInsetsController!!.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            tree(root).forEach { it.systemUiVisibility = 0 }
        }
        compose.waitForIdle()
    }

    private fun assertAllFullScreen(what: String) {
        for (root in windows()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // The flags sit on the view that asked, inside the window.
                @Suppress("DEPRECATION")
                val wanted = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                @Suppress("DEPRECATION")
                assertTrue("$what: $root asks for the bars hidden", tree(root).any { it.systemUiVisibility and wanted == wanted })
                continue
            }
            val bars = root.windowInsetsController ?: fail("$what: no controller for $root").let { return }
            assertEquals("$what: $root shows the bars by a swipe only",
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, bars.systemBarsBehavior)
            // The window's own request (the platform's InsetsController, not public API).
            val visible = bars.javaClass.getMethod("getRequestedVisibleTypes").invoke(bars) as Int
            assertTrue("$what: $root asks for the bars hidden", visible and WindowInsets.Type.systemBars() == 0)
        }
    }

    /** The app's windows: the activity's, and each dialog and pop-up over it. */
    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>
        return views.filter { it.isAttachedToWindow }
    }

    private fun tree(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(tree(view.getChildAt(i)))
    }

    /** Dialog windows over the activity's own, and menu pop-ups. */
    private fun dialogs() = windows().count { it.javaClass.simpleName == "DecorView" } - 1
    private fun popups() = windows().count { it.javaClass.simpleName == "PopupLayout" }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        lane.submit {}.get()
    }

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what (windows: ${windows().size})")
            idle()
            Thread.sleep(5)
        }
    }
}
