package com.fiftyhoursaweek.slowpage.ui

import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Full screen on every screen (Francesco, 2026-11-02): every window of the app, the activity
 * and each dialog, tour and menu over it, asks for the status and navigation bars hidden,
 * shown only for a moment by a swipe. The windows are composed as MainActivity composes them.
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
                    Screen.Start -> {}
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
        vm.menuOpen = false
        vm.showDialog(Dialog.APPEARANCE)
        compose.waitForIdle()
        assertEquals("Appearance", 1, dialogs())
        assertAllFullScreen("the editor and Appearance")
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
