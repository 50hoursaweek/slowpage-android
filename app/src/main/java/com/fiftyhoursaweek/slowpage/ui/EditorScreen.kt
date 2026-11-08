package com.fiftyhoursaweek.slowpage.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fiftyhoursaweek.slowpage.Dialog
import com.fiftyhoursaweek.slowpage.R
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.editor.Command
import com.fiftyhoursaweek.slowpage.editor.EditorText
import com.fiftyhoursaweek.slowpage.editor.Look
import com.fiftyhoursaweek.slowpage.editor.PageMeasure
import com.fiftyhoursaweek.slowpage.editor.Paragraphs
import com.fiftyhoursaweek.slowpage.editor.ReaderList
import com.fiftyhoursaweek.slowpage.preview.PreviewList
import com.fiftyhoursaweek.slowpage.preview.PreviewLook
import uniffi.hushhour.FocusMode
import uniffi.hushhour.Theme
import uniffi.hushhour.ViewMode
import uniffi.hushhour.focusLabel
import uniffi.hushhour.fontCharEm
import uniffi.hushhour.fontLabel
import uniffi.hushhour.Backdrop
import uniffi.hushhour.limits
import uniffi.hushhour.palette
import uniffi.hushhour.themeLabel
import uniffi.hushhour.viewLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The page above and below the text, in dp (the top line sits above it). */
private const val TOP_INSET_DP = 24f
/** Room above a read-only text for its notice, in dp. */
private const val READ_ONLY_NOTICE_DP = 72f

/**
 * The page (phase 4): the editor, the preview, or both (V1, V2), with the top line (back to
 * Documents, the name, the save state, the menu, Export…) and the status line (statistics and
 * the four switches, as on Linux and the iPad). The column is the 66-character measure of
 * the window's base size, centred (E1, E2); the size follows the window and the zoom (E3).
 */
@Composable
fun EditorScreen(
    vm: ShellViewModel,
    name: String,
    status: String,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val prefs = vm.preferences
    val p = palette(prefs.theme)
    val muted = Colours.muted(p).compose()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val density = LocalDensity.current.density
        val sizePx = PageMeasure.bodySize(prefs.size, windowWidth, limits().maxSize) * density
        val mode = vm.viewMode
        val stacked = mode == ViewMode.SPLIT && windowWidth < PageMeasure.SIDE_BY_SIDE_MINIMUM_WIDTH
        val paneWidth = if (mode == ViewMode.SPLIT && !stacked) windowWidth / 2 else windowWidth
        val charEm = fontCharEm(prefs.font)
        val side = (PageMeasure.sideInset(paneWidth, windowWidth, charEm) * density).roundToInt()
        val top = (TOP_INSET_DP * density).roundToInt()
        val look = Look(prefs.theme, prefs.font, sizePx, prefs.lineHeight)
        SideEffect { vm.previewLook = PreviewLook(prefs.theme, prefs.font, sizePx, prefs.lineHeight) }
        val panes = remember { Panes() }

        // The background pattern, behind everything on the page (E16); not made at all while Off.
        if (prefs.backdrop != Backdrop.OFF) {
            BackdropLayer(prefs.backdrop, p, typedAt = { vm.lastEditAt }, modifier = Modifier.fillMaxSize().testTag(BACKDROP_TAG))
        }
        Column(Modifier.fillMaxSize()) {
            TopLine(vm, name, status, onExport, onImport)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val divider = blend(p.text.compose(), p.background.compose(), 0.12f)
                val gap = (TOP_INSET_DP * density).roundToInt()
                // The editor stays in one place in the tree whatever the view, so changing the view
                // never builds it again (on a 1 MB document that took seconds): only its frame changes,
                // and in Preview it is hidden under the preview.
                Layout(
                    content = {
                        if (vm.readOnly != null) ReaderPane(vm, look, side, top) else EditorPane(vm, panes, look, side, top, Modifier)
                        if (mode != ViewMode.EDITOR) {
                            Box(Modifier.background(divider))
                            PreviewPane(vm, panes, side, top, Modifier)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { parts, c ->
                    val w = c.maxWidth
                    val h = c.maxHeight
                    fun exactly(width: Int, height: Int) = Constraints.fixed(maxOf(0, width), maxOf(0, height))
                    layout(w, h) {
                        // Before the highlighting is ready there is no editor yet: nothing to place.
                        if (parts.size != (if (mode == ViewMode.EDITOR) 1 else 3)) return@layout
                        when {
                            mode == ViewMode.EDITOR -> parts[0].measure(exactly(w, h)).place(0, 0)
                            mode == ViewMode.PREVIEW -> {
                                parts[0].measure(exactly(w, h)).place(0, 0)
                                parts[2].measure(exactly(w, h)).place(0, 0)
                            }
                            stacked -> {
                                val half = h / 2
                                parts[0].measure(exactly(w, half)).place(0, 0)
                                parts[1].measure(exactly(w, 1)).place(0, half)
                                parts[2].measure(exactly(w, h - half - 1)).place(0, half + 1)
                            }
                            else -> {
                                val half = w / 2
                                parts[0].measure(exactly(half, h)).place(0, 0)
                                parts[1].measure(exactly(1, h - 2 * gap)).place(half, gap)
                                parts[2].measure(exactly(w - half - 1, h)).place(half + 1, 0)
                            }
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (mode == ViewMode.PREVIEW) PreviewBanner(prefs.theme) { vm.setView(ViewMode.EDITOR); panes.editor?.let(::focusEditor) }
                    if (vm.readOnly != null) {
                        Hint("Read only: a paragraph in this document is longer than ${"%,d".format(Paragraphs.CAP)} characters, " +
                            "too long to edit smoothly. It is shown as it is and never changed.", prefs.theme, READ_ONLY_TAG)
                    }
                    // Round 2: input waits while a long text goes into the editor, and says so.
                    if (vm.editorBusy) Hint("Laying out the text… typing resumes in a moment", prefs.theme, BUSY_TAG)
                    vm.hint?.let { Hint(it, prefs.theme) }
                }
            }
            StatusLine(vm, muted)
        }
        // The preview keeps level with the caret (V5): after an edit, a caret move, a new render.
        LaunchedEffect(vm.caretTick, vm.preview, mode) { panes.preview?.post { panes.keepLevel(vm, mode) } }
    }
}

/** The two platform views, so the preview can keep level with the editor's caret. */
private class Panes {
    var editor: EditorText? = null
    var preview: PreviewList? = null

    /** The block under the caret line at the caret's height in Split, at a third in Preview (V5). */
    fun keepLevel(vm: ShellViewModel, mode: ViewMode) {
        val rendered = vm.preview ?: return
        val list = preview ?: return
        val lines = vm.highlight?.lines ?: return
        if (mode == ViewMode.EDITOR || list.rendered !== rendered) return
        val index = rendered.blockIndex(lines.line(vm.selectionEnd.coerceIn(0, lines.length))) ?: return
        var fraction = 1f / 3f
        val editor = editor
        if (mode == ViewMode.SPLIT && editor != null) editor.caretFraction()?.let { fraction = it }
        list.keepLevel(index, fraction)
    }
}

@Composable
private fun EditorPane(vm: ShellViewModel, panes: Panes, look: Look, side: Int, top: Int, modifier: Modifier) {
    val typewriter = vm.preferences.typewriter
    val mode = vm.viewMode
    val model = vm.highlight ?: return
    // A new document gets a new editor.
    key(model) { AndroidView(
        // The editor's page is clipped to its pane: Compose does not clip a platform view.
        modifier = modifier.clipToBounds().testTag(EDITOR_TAG),
        factory = { context ->
            val page = LayoutInflater.from(context).inflate(R.layout.editor_text, null, false)
            val editor = page.findViewById<EditorText>(R.id.editor)
            editor.bind(vm.host, model, vm.faces)
            editor.setLook(look, side, top)
            // F1: on New and on Open the caret is in the text with the keyboard up, once the
            // text is all in (a long one goes in a piece at a time).
            editor.load(vm.editorText(), vm.selectionStart, vm.selectionEnd) {
                if (vm.viewMode != ViewMode.PREVIEW) focusEditor(editor)
            }
            vm.attach(editor)
            panes.editor = editor
            page
        },
        update = { page ->
            val editor = page.findViewById<EditorText>(R.id.editor)
            editor.setLook(look, side, top)
            editor.typewriter = typewriter
            // Hidden under the full Preview; back from it, the caret is in the text again.
            val hidden = mode == ViewMode.PREVIEW
            if (hidden != (page.visibility != View.VISIBLE)) {
                page.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
                if (!hidden) editor.post { focusEditor(editor) }
            }
        },
        onRelease = { page ->
            val editor = page.findViewById<EditorText>(R.id.editor)
            vm.detach(editor)
            editor.unbind()
            if (panes.editor === editor) panes.editor = null
        },
    ) }
}

/** A document over the paragraph cap (ruling B): its text, read-only, in parts. */
@Composable
private fun ReaderPane(vm: ShellViewModel, look: Look, side: Int, top: Int) {
    val text = vm.readOnly ?: return
    AndroidView(
        modifier = Modifier.clipToBounds().testTag(EDITOR_TAG),
        factory = { context -> ReaderList(context, vm.faces) },
        // Below the notice, which stays at the top.
        update = { it.show(text, look, side, top + (READ_ONLY_NOTICE_DP * it.resources.displayMetrics.density).roundToInt()) },
    )
}

fun focusEditor(editor: EditorText) {
    editor.requestFocus()
    val imm = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
}

@Composable
private fun PreviewPane(vm: ShellViewModel, panes: Panes, side: Int, top: Int, modifier: Modifier) {
    val rendered = vm.preview
    val mode = vm.viewMode
    AndroidView(
        modifier = modifier.clipToBounds().testTag(PREVIEW_TAG),
        factory = { context -> PreviewList(context) { vm.previewNeedsHint() }.also { panes.preview = it } },
        update = { list ->
            list.show(rendered, side, top)
            // In full Preview the keys go to the preview, so typing there gets the hint; there is
            // nothing to type into, so the soft keyboard goes.
            if (mode == ViewMode.PREVIEW && !list.hasFocus()) list.post {
                list.requestFocus()
                val imm = list.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(list.windowToken, 0)
            }
        },
        onRelease = { if (panes.preview === it) panes.preview = null },
    )
}

@Composable
private fun TopLine(vm: ShellViewModel, name: String, status: String, onExport: () -> Unit, onImport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // On the plain page colour, as the status line: the background pattern shows only behind the page.
    Row(Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.command(Command.CLOSE) }, enabled = !vm.closing) { Text("‹ Documents") }
        // A long name, cut short with "…", keeps a gap before the save status.
        Text(name, Modifier.weight(1f).padding(end = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Text(
            status,
            color = if (status.startsWith("Not saved")) colors.error else colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        var renaming by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { vm.menuOpen = true }, modifier = Modifier.testTag(MENU_TAG)) { Text("Menu") }
            EditorMenu(vm, onRename = { renaming = true }, onExport, onImport)
        }
        if (renaming) RenameDialog(name, onRename = { vm.rename(name, it) }, onDismiss = { renaming = false })
    }
}

@Composable
private fun EditorMenu(vm: ShellViewModel, onRename: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    fun run(action: () -> Unit) = { vm.menuOpen = false; action() }
    @Composable fun item(label: String, enabled: Boolean = true, action: () -> Unit) =
        DropdownMenuItem(text = { Text(label) }, onClick = run(action), enabled = enabled)
    DropdownMenu(expanded = vm.menuOpen, onDismissRequest = { vm.menuOpen = false }) {
        ImmersiveWindow()
        item("New") { vm.command(Command.NEW) }
        item("Import…") { onImport() }
        // After New and Open, as on the iPad's ☰ menu (Francesco, 1.0.1).
        item("Rename…") { onRename() }
        item("Export…") { onExport() }
        item("Close") { vm.command(Command.CLOSE) }
        HorizontalDivider()
        item("Undo", enabled = vm.canUndo && !vm.editorBusy) { vm.command(Command.UNDO) }
        item("Redo", enabled = vm.canRedo && !vm.editorBusy) { vm.command(Command.REDO) }
        HorizontalDivider()
        // The settings only in their pop-ups, as on the iPad (O5; Francesco's install test):
        // theme, font, size and line height in Appearance, View, Spotlight and Anchor on the
        // status line, and all of them on the keyboard shortcuts.
        item("Appearance…") { vm.showDialog(Dialog.APPEARANCE) }
        item("Sound and music…") { vm.showDialog(Dialog.SOUND) }
        HorizontalDivider()
        item("Welcome…") { vm.showWelcome() }
        item("About Slowpage…") { vm.showDialog(Dialog.ABOUT) }
    }
}

/**
 * Statistics and the switches, as the Linux status bar (E15). On a narrow screen (a phone) the
 * row scrolls sideways, by a swipe or by the ‹ and › at its ends, shown only when there is more
 * that way; nothing ever wraps (Francesco's phone test, 2026-11-02). When it all fits, as on the
 * tablet, there are no arrows: the statistics on the left, the switches on the right.
 */
@Composable
private fun StatusLine(vm: ShellViewModel, muted: Color) {
    val p = vm.preferences
    val pal = palette(p.theme)
    val text = pal.text.compose()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // While the row overflows both arrows keep their place, an arrow with nothing more that way
    // blank and not tappable, so a second tap at the end never lands on a switch. Once it has
    // overflowed at this width the arrows stay: a row that fits only without them would
    // otherwise show and hide them in turn. (Before its first layout the row has no viewport.)
    val overflowsNow = scroll.viewportSize > 0 && scroll.maxValue in 1 until Int.MAX_VALUE
    val overflowed = remember(LocalWindowInfo.current.containerSize.width) { mutableStateOf(false) }
    if (overflowsNow && !overflowed.value) SideEffect { overflowed.value = true }
    val overflows = overflowsNow || overflowed.value
    @Composable fun arrow(label: String, more: Boolean, tag: String, by: () -> Float) {
        val modifier = if (more) Modifier.testTag(tag).clickable { scope.launch { scroll.animateScrollBy(by()) } } else Modifier.alpha(0f)
        Text(label, color = muted, fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = modifier.padding(horizontal = 8.dp, vertical = 10.dp))
    }
    Row(
        // On the plain page colour, as on the iPad: nothing of the page shows behind the bar.
        Modifier.fillMaxWidth().background(pal.background.compose()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // About a screen's width per tap: the visible part of the row, less a little to keep context.
        if (overflows) arrow("‹", scroll.canScrollBackward, STATUS_BACK_TAG) { -scroll.viewportSize * 0.9f }
        BoxWithConstraints(Modifier.weight(1f).testTag(STATUS_ROW_TAG)) {
            Row(
                Modifier.horizontalScroll(scroll).widthIn(min = maxWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(vm.stats, color = muted, fontSize = 13.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(end = 8.dp).testTag(STATS_TAG))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    @Composable fun switch(label: String, active: Boolean, tag: String, onClick: () -> Unit) =
                        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag(tag)) {
                            Text(label, color = if (active) text else muted, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                    if (vm.readOnly != null) {
                        // Read-only (ruling B): no preview, so no view to switch to.
                    } else if (vm.viewMode == ViewMode.PREVIEW) {
                        // In full Preview the mode is highlighted: white on the darkened accent.
                        Surface(shape = RoundedCornerShape(50), color = Colours.accentFill(pal).compose(), onClick = { vm.command(Command.VIEW) }, modifier = Modifier.testTag(VIEW_TAG)) {
                            Text("View: Preview", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                        }
                    } else {
                        switch("View: ${viewLabel(vm.viewMode)}", vm.viewMode != ViewMode.EDITOR, VIEW_TAG) { vm.command(Command.VIEW) }
                    }
                    switch("Spotlight: ${focusLabel(p.focus)}", p.focus != FocusMode.OFF, "spotlight") { vm.command(Command.SPOTLIGHT) }
                    switch("Anchor: ${if (p.typewriter) "On" else "Off"}", p.typewriter, "anchor") { vm.command(Command.ANCHOR) }
                    switch(fontLabel(p.font), false, "font") { vm.command(Command.FONT) }
                    switch(themeLabel(p.theme), false, "theme") { vm.command(Command.THEME) }
                }
            }
        }
        if (overflows) arrow("›", scroll.canScrollForward, STATUS_FORWARD_TAG) { scroll.viewportSize * 0.9f }
    }
}

/** Full Preview says so, with the way back to writing (V6); the Edit capsule gets an edge in Dark (E18). */
@Composable
private fun PreviewBanner(theme: Theme, onEdit: () -> Unit) {
    val p = palette(theme)
    val banner = Colours.mix(p.text, p.background, 0.9f)
    val fill = Colours.accentFill(p)
    val edged = Colours.contrast(fill, banner) < 3
    Surface(shape = RoundedCornerShape(18.dp), color = banner.compose(), modifier = Modifier.testTag(BANNER_TAG)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Preview", color = p.text.compose(), fontSize = 15.sp)
            Box(Modifier.width(12.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = fill.compose(),
                border = if (edged) BorderStroke(Dp(1f), Colours.muted(p).compose()) else null,
                onClick = onEdit,
                modifier = Modifier.testTag(EDIT_TAG),
            ) {
                Text("Edit", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun Hint(text: String, theme: Theme, tag: String = HINT_TAG) {
    val p = palette(theme)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = p.text.compose().copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 10.dp).widthIn(max = 720.dp).testTag(tag),
    ) {
        Text(text, color = p.background.compose(), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

private fun uniffi.hushhour.Rgb.compose() = Color(r, g, b)

/** Test tags. */
const val EDITOR_TAG = "editor"
const val PREVIEW_TAG = "preview"
const val STATS_TAG = "stats"
const val STATUS_ROW_TAG = "statusRow"
const val STATUS_BACK_TAG = "statusBack"
const val STATUS_FORWARD_TAG = "statusForward"
const val VIEW_TAG = "viewMode"
const val BANNER_TAG = "previewBanner"
const val EDIT_TAG = "editButton"
const val HINT_TAG = "previewHint"
const val MENU_TAG = "menu"
const val READ_ONLY_TAG = "readOnly"
const val BUSY_TAG = "editorBusy"
const val BACKDROP_TAG = "backdrop"
