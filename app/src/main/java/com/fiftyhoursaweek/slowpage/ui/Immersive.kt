package com.fiftyhoursaweek.slowpage.ui

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Full screen on every screen (Francesco, 2026-11-02: "when you enter the app, you enter
 * another room"). The status and navigation bars are hidden; a swipe from an edge shows them
 * for a moment and they hide again by themselves.
 *
 * Only the bars are hidden: the window still fits them and is still resized for the keyboard
 * (adjustResize, F4), so the layout and the caret above the keyboard are as before. The display
 * cutout stays clear as before (safeDrawingPadding in MainActivity).
 */
object Immersive {
    /** Hides the bars for the window [view] is attached to. */
    fun apply(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = view.windowInsetsController ?: return
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        } else {
            // Android 8 to 10. No LAYOUT_ flags: the window keeps fitting the bars' space, as above.
            @Suppress("DEPRECATION")
            view.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }
}

/**
 * Keeps the window this is composed in full screen: the activity, and each dialog, menu and
 * pop-up, which are windows of their own (a focused window that asks nothing would bring the
 * bars back). Applied again whenever the window regains focus: back from the background or a
 * system file picker, after a pop-up over it closes, after the keyboard.
 */
@Composable
fun ImmersiveWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        Immersive.apply(view)
        val refocused = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) Immersive.apply(view) }
        view.viewTreeObserver.addOnWindowFocusChangeListener(refocused)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(refocused) }
    }
}
