package com.fiftyhoursaweek.slowpage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.fiftyhoursaweek.slowpage.store.Names
import com.fiftyhoursaweek.slowpage.ui.EditorScreen
import com.fiftyhoursaweek.slowpage.ui.SettingsScreen
import com.fiftyhoursaweek.slowpage.ui.SlowpageTheme
import com.fiftyhoursaweek.slowpage.ui.StartScreen

/** The one window (L3: `singleTask`, one document at a time). */
class MainActivity : ComponentActivity() {
    private val vm: ShellViewModel by viewModels()
    private lateinit var music: Music

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        exporting = savedInstanceState?.getString(EXPORTING_KEY)
        music = Music(this)
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
                            music = vm.music,
                            onNew = vm::newDocument,
                            onImport = { importer.launch(arrayOf("*/*")) },
                            onSettings = vm::openSettings,
                            onOpen = vm::open,
                            onExport = ::startExport,
                            onDelete = vm::delete,
                        )
                        is Screen.Editor -> EditorScreen(
                            name = screen.name,
                            field = vm.field,
                            status = (listOf(vm.status) + vm.problems).filter { it.isNotEmpty() }.joinToString(" · "),
                            readOnly = vm.closing,
                            textSize = vm.preferences.size,
                            lineHeight = vm.preferences.lineHeight,
                            onChange = vm::onFieldChange,
                            onClose = vm::closeEditor,
                            onExport = { startExport(screen.name) },
                        )
                        Screen.Settings -> SettingsScreen(
                            preferences = vm.preferences,
                            problems = vm.problems,
                            onChange = { p ->
                                vm.updatePreferences(p)
                                music.setVolume(p.musicVolume)
                            },
                            onBack = { vm.back() },
                        )
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
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
        music.start(vm.preferences.musicVolume) { vm.music = it }
    }

    override fun onStop() {
        vm.saveNow()
        music.stop()
        super.onStop()
    }

    companion object {
        private const val EXPORTING_KEY = "exporting"
    }
}
