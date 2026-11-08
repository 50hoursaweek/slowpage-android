package com.fiftyhoursaweek.slowpage.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.fiftyhoursaweek.slowpage.Screen
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.SlowpageApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Rename… (R1, Francesco for 1.0.1): first in a document's ⋯ menu on the start screen, and in
 * the editor's Menu after New and Import…. The dialog is the iPad's: the name without its
 * extension, selected, so typing replaces it; Rename or Return renames.
 *
 * At tablet width: at 360 dp portrait Robolectric lays the dialog out again and again until the
 * heap runs out. A phone emulator at 360 dp shows no such loop (no frames and no CPU while the
 * dialog waits), so phone width is checked there (audit/phase-7/rename.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class RenameDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: ShellViewModel
    private val app get() = RuntimeEnvironment.getApplication() as SlowpageApp

    private fun show() {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(app, SavedStateHandle()) as T
        }
        vm = ViewModelProvider(store, factory)[ShellViewModel::class.java]
        until("ready") { vm.loaded }
        vm.finishTour(com.fiftyhoursaweek.slowpage.TourExit.CLOSE)
        compose.setContent {
            SlowpageTheme(vm.preferences.theme) {
                when (val screen = vm.screen) {
                    Screen.Start -> StartScreen(
                        documents = vm.documents, recent = vm.recent, problems = vm.problems,
                        onNew = vm::newDocument, onImport = {}, onOpen = vm::open, onRename = vm::rename, onExport = {}, onDelete = {},
                    )
                    is Screen.Editor -> EditorScreen(vm, screen.name, vm.status, onExport = {}, onImport = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun tearDown() = store.clear()

    /** The field holds [text], all of it selected. */
    private fun assertFieldSelects(text: String) {
        val field = compose.onNodeWithTag(RENAME_FIELD_TAG).assertIsFocused().fetchSemanticsNode()
        assertEquals(text, field.config[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(0, text.length), field.config[SemanticsProperties.TextSelectionRange])
    }

    @Test fun renameIsFirstInTheStartScreensMenuAndReturnRenames() {
        app.store.write("Draft.md", "words")
        show()
        vm.refresh()
        until("the document is listed") { vm.documents.isNotEmpty() }
        compose.waitForIdle()
        compose.onAllNodesWithText("⋯")[0].performClick()
        compose.waitForIdle()
        // Rename… above Export… and Delete….
        val items = listOf("Rename…", "Export…", "Delete…").map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(items.sorted(), items)
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()
        assertFieldSelects("Draft")
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Letter")
        compose.onNodeWithTag(RENAME_FIELD_TAG).performImeAction()
        until("renamed") { app.store.exists("Letter.md") }
        assertFalse(app.store.exists("Draft.md"))
        until("listed under the new name") { vm.documents.map { it.name } == listOf("Letter.md") }
        compose.waitForIdle()
        compose.onNodeWithText("Letter.md").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Draft.md").fetchSemanticsNodes().size)
    }

    @Test fun renameFromTheEditorsMenuRetitlesTheOpenDocument() {
        show()
        vm.newDocument()
        until("a document") { vm.screen is Screen.Editor }
        compose.waitForIdle()
        compose.onNodeWithTag(MENU_TAG).performClick()
        compose.waitForIdle()
        val items = listOf("New", "Import…", "Rename…", "Export…", "Close").map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(items.sorted(), items)
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()
        assertFieldSelects("Untitled")
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Chapter one")
        compose.onNodeWithTag(RENAME_BUTTON_TAG).performClick()
        until("the editor is retitled") { vm.screen == Screen.Editor("Chapter one.md") }
        compose.waitForIdle()
        compose.onNodeWithText("Chapter one.md").assertIsDisplayed()
        assertFalse(app.store.exists("Untitled.md"))
    }

    @Test fun cancelLeavesTheNameAsItWas() {
        show()
        vm.newDocument()
        until("a document") { vm.screen is Screen.Editor }
        compose.waitForIdle()
        compose.onNodeWithTag(MENU_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Other")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Other").fetchSemanticsNodes().size)
        assertEquals(Screen.Editor("Untitled.md"), vm.screen)
        assertFalse(app.store.exists("Other.md"))
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
