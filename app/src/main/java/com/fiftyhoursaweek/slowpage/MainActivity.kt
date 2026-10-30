package com.fiftyhoursaweek.slowpage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.fiftyhoursaweek.slowpage.editor.Command
import com.fiftyhoursaweek.slowpage.editor.Shortcuts
import com.fiftyhoursaweek.slowpage.store.Names
import com.fiftyhoursaweek.slowpage.ui.EditorScreen
import com.fiftyhoursaweek.slowpage.ui.SettingsScreen
import com.fiftyhoursaweek.slowpage.ui.SlowpageTheme
import com.fiftyhoursaweek.slowpage.ui.StartScreen

/** The one window (L3: `singleTask`, one document at a time). */
class MainActivity : ComponentActivity() {
    private val vm: ShellViewModel by viewModels()
    private val audio get() = (application as SlowpageApp).audio

    /** The document an Export… is for, while the system's Create document screen is up. */
    private var exporting: String? = null

    // SAF is import and export only (decided 2026-10-28). Any type: providers label Markdown
    // inconsistently, and the store checks the bytes as text itself.
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.import(contentResolver, it) }
    }
    private val markdownExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        finishExport(uri)
    }
    private val textExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        finishExport(uri)
    }
    /** A music file to copy in (A3): any audio type; the view model checks the extension the core plays. */
    private val musicImporter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importMusic(contentResolver, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No edge-to-edge (F4): the window is resized for the keyboard (adjustResize) instead of
        // relying on IME insets, which the Huawei keyboard may not report.
        super.onCreate(savedInstanceState)
        exporting = savedInstanceState?.getString(EXPORTING_KEY)
        setContent {
            SlowpageTheme(vm.preferences.theme) {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(vm.notice) {
                    vm.notice?.let {
                        snackbar.showSnackbar(it, withDismissAction = true)
                        vm.notice = null
                    }
                }
                BackHandler(enabled = vm.screen != Screen.Start) { vm.back() }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
                    when (val screen = vm.screen) {
                        Screen.Start -> StartScreen(
                            documents = vm.documents,
                            recent = vm.recent,
                            problems = vm.problems,
                            onNew = vm::newDocument,
                            onImport = ::startImport,
                            onSettings = vm::openSettings,
                            onOpen = vm::open,
                            onExport = ::startExport,
                            onDelete = vm::delete,
                        )
                        is Screen.Editor -> EditorScreen(
                            vm = vm,
                            name = screen.name,
                            status = (listOf(vm.status) + vm.problems).filter { it.isNotEmpty() }.joinToString(" · "),
                            onExport = { startExport(screen.name) },
                            onImport = ::startImport,
                            onImportMusic = { musicImporter.launch(arrayOf("audio/*", "application/ogg")) },
                        )
                        Screen.Settings -> SettingsScreen(
                            preferences = vm.preferences,
                            problems = vm.problems,
                            onChange = { p ->
                                vm.updatePreferences(p)
                            },
                            onBack = { vm.back() },
                        )
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    /** Import… from the editor closes the document first (one document at a time, L3). */
    private fun startImport() {
        val launch = { importer.launch(arrayOf("*/*")) }
        if (vm.screen is Screen.Editor) vm.closeEditor(then = launch) else launch()
    }

    /**
     * The shortcuts with Ctrl (K1), before any view sees the key: the editor's own Ctrl+Z
     * would reach the platform history, which is off (E12). Copy, cut, paste and select all
     * are not in the table and stay with the text views.
     * This overrides the framework's public Activity.dispatchKeyEvent; lint flags it only
     * because androidx's ComponentActivity marks its own override as restricted.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val command = Shortcuts.command(event) ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (vm.screen) {
            is Screen.Editor -> if (command == Command.IMPORT) startImport() else vm.command(command)
            Screen.Start -> when (command) {
                Command.NEW -> vm.newDocument()
                Command.IMPORT -> startImport()
                Command.MENU -> vm.openSettings()
                else -> return super.dispatchKeyEvent(event)
            }
            Screen.Settings -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /** The table in the system's Keyboard Shortcuts Helper (Meta+/), where the system has one (K1). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        val ctrl = KeyEvent.META_CTRL_ON
        val shift = KeyEvent.META_SHIFT_ON
        fun item(label: String, key: Int, meta: Int = ctrl) = KeyboardShortcutInfo(label, key, meta)
        data += KeyboardShortcutGroup("Slowpage", listOf(
            item("Menu", KeyEvent.KEYCODE_COMMA),
            item("New document", KeyEvent.KEYCODE_N),
            item("Documents", KeyEvent.KEYCODE_O),
            item("Import…", KeyEvent.KEYCODE_O, ctrl or shift),
            item("Close", KeyEvent.KEYCODE_W),
            item("Undo", KeyEvent.KEYCODE_Z),
            item("Redo", KeyEvent.KEYCODE_Z, ctrl or shift),
            item("Spotlight", KeyEvent.KEYCODE_D),
            item("Anchor", KeyEvent.KEYCODE_T),
            item("Theme", KeyEvent.KEYCODE_T, ctrl or shift),
            item("Font", KeyEvent.KEYCODE_F, ctrl or shift),
            item("Editor, Split, Preview", KeyEvent.KEYCODE_P, ctrl or shift),
            item("Larger text", KeyEvent.KEYCODE_EQUALS),
            item("Smaller text", KeyEvent.KEYCODE_MINUS),
        ))
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    private fun startExport(name: String) {
        exporting = name
        if (Names.split(name).second.lowercase() == "txt") textExporter.launch(name) else markdownExporter.launch(name)
    }

    private fun finishExport(uri: android.net.Uri?) {
        val name = exporting ?: return
        exporting = null
        uri?.let { vm.export(contentResolver, it, name) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXPORTING_KEY, exporting)
    }

    override fun onStart() {
        super.onStart()
        vm.refresh()
        audio.foreground()
    }

    override fun onStop() {
        vm.saveNow()
        // A rotation stops and starts the activity: the sound goes on through it (Gate A
        // restarted the music). Leaving the screen stops the stream (A8).
        if (!isChangingConfigurations) audio.background()
        super.onStop()
    }

    companion object {
        private const val EXPORTING_KEY = "exporting"
    }
}
