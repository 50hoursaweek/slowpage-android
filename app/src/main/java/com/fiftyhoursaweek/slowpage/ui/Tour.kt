package com.fiftyhoursaweek.slowpage.ui

import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.TourExit
import kotlinx.coroutines.launch
import uniffi.hushhour.Backdrop
import uniffi.hushhour.FontChoice
import uniffi.hushhour.KeyKind
import uniffi.hushhour.MusicChoice
import uniffi.hushhour.SoundSet
import uniffi.hushhour.Theme
import uniffi.hushhour.backdropLabel
import uniffi.hushhour.fontLabel
import uniffi.hushhour.musicLabel
import uniffi.hushhour.palette
import uniffi.hushhour.soundSetLabel
import uniffi.hushhour.themeLabel

/*
 * The welcome tour (O1, Francesco 2026-10-14), the iPad's `Onboarding.swift` one to one: five
 * cards, swiped sideways, full screen on the page's own colours. Shown once at first launch and
 * again from Menu → Welcome… (and About). Every choice is the real setting at once. Nothing
 * sounds until the user acts: the key sounds play as the user types in the box on the Sound
 * card, and ▷ plays a short sample of one track. Samples stop when the card or the tour is
 * left; a checked track waits until a document is opened (A6), even when the tour was over one.
 * The background pattern runs only on its card, and stops with the tour.
 *
 * Android: the menu is the Menu button at the top right (☰ at the top left on the iPad), the
 * start screen's Open… is Import…, Reduce Motion is Remove animations, and the shortcuts are Ctrl.
 */

/** The tour, in a full-screen window of its own over the screen; [exit] ends it. */
@Composable
fun WelcomeTour(vm: ShellViewModel, exit: (TourExit) -> Unit) {
    ComposeDialog(
        onDismissRequest = { exit(TourExit.CLOSE) },  // Back skips, as Esc does
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // Edge to edge, but above the on-screen keyboard: the Sound card's box stays in view.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        ImmersiveWindow()
        LaunchedEffect(window) {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window?.setWindowAnimations(0)
            window?.setDimAmount(0f)
        }
        TourCards(vm, exit)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TourCards(vm: ShellViewModel, exit: (TourExit) -> Unit) {
    val prefs = vm.preferences
    val p = palette(prefs.theme)
    val text = p.text.compose()
    val page = p.background.compose()
    // Secondary text: faded no further than keeps 4.5:1 over the pattern's peak, so the same
    // colour serves every card, the background one included (O4).
    val quiet = Colours.secondary(p).compose()
    val font = lato()
    val pager = rememberPagerState(initialPage = vm.tourCard) { ShellViewModel.TOUR_CARDS }
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun go(step: Int) {
        val target = (pager.currentPage + step).coerceIn(0, ShellViewModel.TOUR_CARDS - 1)
        if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
    }
    // Leaving a card leaves its sound.
    LaunchedEffect(pager.currentPage) {
        vm.tourCard = pager.currentPage
        vm.stopSample()
        typing = false
    }
    // A sample stops when the tour leaves the screen, however it goes.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) vm.stopSample() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.stopSample()
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(page).testTag(TOUR_TAG)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                val key = e.nativeKeyEvent
                if (key.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when {
                    key.keyCode == KeyEvent.KEYCODE_ESCAPE -> { exit(TourExit.CLOSE); true }
                    // In the box on the Sound card, ← → move its caret, not the cards.
                    typing -> false
                    key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { go(-1); true }
                    key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { go(1); true }
                    key.keyCode == KeyEvent.KEYCODE_ENTER && pager.currentPage == ShellViewModel.TOUR_CARDS - 1 -> {
                        exit(if (vm.tourOffersStart == true) TourExit.NEW_DOCUMENT else TourExit.CLOSE)
                        true
                    }
                    else -> false
                }
            },
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { index ->
            Box(Modifier.fillMaxSize()) {
                if (index == 2 && prefs.backdrop != Backdrop.OFF && pager.currentPage == 2) {
                    // The pattern runs only while its card is the one showing.
                    BackdropLayer(prefs.backdrop, p, typedAt = { 0L }, modifier = Modifier.fillMaxSize().testTag(TOUR_BACKDROP_TAG))
                }
                // One card: a column of at most 620 dp, centred, scrolling if it does not fit.
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(
                        Modifier.widthIn(max = 620.dp).fillMaxWidth().heightIn(min = 0.dp).padding(horizontal = 32.dp, vertical = 72.dp),
                    ) {
                        val pieces = Pieces(font, text, page, quiet)
                        when (index) {
                            0 -> pieces.Welcome()
                            1 -> pieces.Look(vm)
                            2 -> pieces.Background(vm)
                            3 -> pieces.Sound(vm, onTyping = { typing = it })
                            else -> pieces.Ready(vm.tourOffersStart == true, exit)
                        }
                    }
                }
            }
        }
        // Skip, top right.
        Text(
            "Skip",
            fontFamily = font,
            fontSize = 17.sp,
            color = quiet,
            modifier = Modifier.align(Alignment.TopEnd).clickable { exit(TourExit.CLOSE) }
                .padding(horizontal = 28.dp, vertical = 14.dp).testTag("onboardingSkip"),
        )
        // ‹ the dots ›, at the bottom; over the keyboard while typing, on the page colour.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .background(if (typing) page else Color.Transparent)
                .padding(top = 10.dp, bottom = if (typing) 10.dp else 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val last = ShellViewModel.TOUR_CARDS - 1
            Chevron(left = true, quiet, visible = pager.currentPage > 0, tag = "onboardingPrevious") { go(-1) }
            Spacer(Modifier.width(22.dp))
            Row(Modifier.semantics { contentDescription = "Page ${pager.currentPage + 1} of ${ShellViewModel.TOUR_CARDS}" }.testTag("onboardingPage")) {
                for (i in 0..last) {
                    Box(
                        Modifier.size(width = 20.dp, height = 44.dp).clickable { scope.launch { pager.animateScrollToPage(i) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(8.dp).background(if (i == pager.currentPage) text else quiet.copy(alpha = 0.5f), CircleShape))
                    }
                }
            }
            Spacer(Modifier.width(22.dp))
            Chevron(left = false, quiet, visible = pager.currentPage < last, tag = "onboardingNext") { go(1) }
        }
    }
}

@Composable
private fun Chevron(left: Boolean, colour: Color, visible: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).let { if (visible) it.clickable(onClick = onClick) else it }.testTag(tag)
            .semantics { contentDescription = if (left) "Previous" else "Next" },
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Canvas(Modifier.size(width = 10.dp, height = 18.dp)) {
                val path = Path().apply {
                    if (left) {
                        moveTo(size.width, 0f); lineTo(0f, size.height / 2); lineTo(size.width, size.height)
                    } else {
                        moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height)
                    }
                }
                drawPath(path, colour, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** The cards' pieces, in the page's colours. */
private class Pieces(val font: FontFamily, val text: Color, val page: Color, val quiet: Color) {
    @Composable
    fun Title(s: String) = Text(s, fontFamily = font, fontSize = 40.sp, color = text, modifier = Modifier.semantics { heading() })

    @Composable
    fun Paragraph(s: String) = Text(s, fontFamily = font, fontSize = 19.sp, color = quiet)

    /** A row of choices; the chosen one is filled with the text colour (PMO, accepted by Francesco). */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun <T> Chips(all: List<T>, selected: T, label: (T) -> String, id: String, face: (T) -> FontFamily = { font }, choose: (T) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (value in all) {
                val on = value == selected
                Surface(
                    onClick = { choose(value) },
                    shape = CircleShape,
                    color = if (on) text else Color.Transparent,
                    border = if (on) null else BorderStroke(1.dp, quiet.copy(alpha = 0.6f)),
                    modifier = Modifier.semantics { this.selected = on }.testTag("$id-${label(value)}"),
                ) {
                    Text(label(value), fontFamily = face(value), fontSize = 16.sp, color = if (on) page else text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
    }

    // 1 Slowpage
    @Composable
    fun Welcome() = Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Title("Slowpage")
        Text("A quiet place to write.", fontFamily = font, fontSize = 24.sp, color = text)
        Box(Modifier.padding(top = 10.dp)) { Paragraph("Plain Markdown files, saved as you type. Nothing on the page but your words until you ask for more.") }
        Text("Swipe, or press →, to look around. It takes a minute.", fontFamily = font, fontSize = 15.sp, color = quiet, modifier = Modifier.padding(top = 26.dp))
    }

    // 2 The look
    @Composable
    fun Look(vm: ShellViewModel) {
        val prefs = vm.preferences
        val assets = androidx.compose.ui.platform.LocalContext.current.assets
        val faces = remember(assets) { FontChoice.entries.associateWith { editorFontFamily(it, assets) } }
        val face = faces.getValue(prefs.font)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Title("The look")
            Paragraph("Three themes and three typefaces. Tap to try them: the change is real, and Menu → Appearance changes it again later, along with the size and line height.")
            Box(Modifier.padding(top = 6.dp)) {
                Chips(listOf(Theme.LIGHT, Theme.SEPIA, Theme.DARK), prefs.theme, ::themeLabel, "theme") { vm.updatePreferences(vm.preferences.copy(theme = it)) }
            }
            Chips(listOf(FontChoice.MONO, FontChoice.SANS, FontChoice.SERIF), prefs.font, ::fontLabel, "font", face = { faces.getValue(it) }) {
                vm.updatePreferences(vm.preferences.copy(font = it))
            }
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, quiet.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(22.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "A sample page in ${fontLabel(prefs.font)}, ${themeLabel(prefs.theme)}" },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = quiet)) { append("# ") }
                        append("Chapter one")
                    },
                    fontFamily = face, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = text,
                )
                Text(
                    buildAnnotatedString {
                        append("The kettle clicked off just as the ")
                        withStyle(SpanStyle(color = quiet)) { append("**") }
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("rain") }
                        withStyle(SpanStyle(color = quiet)) { append("**") }
                        append(" began. She left the tea where it was and kept writing.")
                    },
                    fontFamily = face, fontSize = 18.sp, lineHeight = 30.sp, color = text,
                )
            }
        }
    }

    // 3 The background
    @Composable
    fun Background(vm: ShellViewModel) = Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("The background")
        Paragraph("A slow pattern behind the page, if you like one. It rests while you type, and stays still with Remove animations.")
        Box(Modifier.padding(top = 6.dp)) {
            Chips(listOf(Backdrop.DRIFT, Backdrop.SWELL, Backdrop.WAVES, Backdrop.OFF), vm.preferences.backdrop, ::backdropLabel, "background") {
                vm.updatePreferences(vm.preferences.copy(backdrop = it))
            }
        }
    }

    // 4 Sound
    @Composable
    fun Sound(vm: ShellViewModel, onTyping: (Boolean) -> Unit) {
        val prefs = vm.preferences
        val off = "Off"
        val keys = if (prefs.typingSound) soundSetLabel(prefs.soundSet) else off
        // The bundled loops and the soundscapes; the user's own tracks stay in Sound and music.
        val music = vm.musicChoices.filter { it !is MusicChoice.File && it != MusicChoice.Off }
        var typed by rememberSaveable { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Title("Sound")
            Paragraph("Soft keys as you type, and music or a soundscape if you like. Nothing plays until you ask.")
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keys", fontFamily = font, fontSize = 16.sp, color = quiet, modifier = Modifier.width(56.dp))
                Spacer(Modifier.width(16.dp))
                Chips(listOf(off, soundSetLabel(SoundSet.SOFT), soundSetLabel(SoundSet.KEYBOARD)), keys, { it }, "keys") { value ->
                    vm.updatePreferences(
                        if (value == off) vm.preferences.copy(typingSound = false)
                        else vm.preferences.copy(typingSound = true, soundSet = if (value == soundSetLabel(SoundSet.KEYBOARD)) SoundSet.KEYBOARD else SoundSet.SOFT),
                    )
                }
            }
            BasicTextField(
                value = typed,
                onValueChange = { new ->
                    if (new == typed) return@BasicTextField
                    vm.tourKey(if (new.length < typed.length) KeyKind.BACKSPACE else KeyKind.CHARACTER)
                    typed = new
                },
                singleLine = true,
                textStyle = TextStyle(fontFamily = font, fontSize = 17.sp, color = text),
                cursorBrush = SolidColor(text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.tourKey(KeyKind.ENTER) }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { onTyping(it.isFocused) }
                    .onPreviewKeyEvent { e ->
                        // A hardware Enter: the key sound, and the box keeps the keyboard.
                        if (e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) vm.tourKey(KeyKind.ENTER)
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("keysTryout"),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().border(1.dp, quiet.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(14.dp)) {
                        if (typed.isEmpty()) Text("Type here to hear the keys…", fontFamily = font, fontSize = 17.sp, color = quiet)
                        inner()
                    }
                },
            )
            Row(verticalAlignment = Alignment.Top) {
                Text("Music", fontFamily = font, fontSize = 16.sp, color = quiet, modifier = Modifier.width(56.dp).padding(top = 10.dp))
                Spacer(Modifier.width(16.dp))
                FlowRowOfMusic(vm, music, prefs.music)
            }
            Text(
                "▷ plays a short sample. The checked one plays while you write. Menu → Sound and music has the volumes and your own tracks.",
                fontFamily = font, fontSize = 14.sp, color = quiet,
            )
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun FlowRowOfMusic(vm: ShellViewModel, music: List<MusicChoice>, checkedMusic: MusicChoice) {
        // Columns of at least 220 dp, as the iPad's adaptive grid.
        BoxWithConstraints {
            val width = maxWidth
            val columns = maxOf(1, (width / 220.dp).toInt())
            FlowRow(maxItemsInEachRow = columns) {
                for (choice in music) {
                    Box(Modifier.width(width / columns)) { MusicRow(vm, choice, choice == checkedMusic) }
                }
            }
        }
    }

    @Composable
    private fun MusicRow(vm: ShellViewModel, choice: MusicChoice, checked: Boolean) {
        val name = musicLabel(choice)
        val sampling = vm.sample == choice
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.heightIn(min = 40.dp).clickable { vm.setMusic(if (checked) MusicChoice.Off else choice) }
                    .semantics { selected = checked }.testTag("music-$name"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    if (checked) {
                        drawCircle(text)
                        val tick = Path().apply {
                            moveTo(size.width * 0.28f, size.height * 0.52f)
                            lineTo(size.width * 0.44f, size.height * 0.68f)
                            lineTo(size.width * 0.72f, size.height * 0.34f)
                        }
                        drawPath(tick, page, style = Stroke(width = 2.dp.toPx()))
                    } else {
                        drawCircle(quiet, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(name, fontFamily = font, fontSize = 16.sp, color = text)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(40.dp).clickable { if (sampling) vm.stopSample() else vm.playSample(choice) }
                    .semantics { contentDescription = if (sampling) "Stop the sample of $name" else "Play a sample of $name" }
                    .testTag("sample-$name"),
                contentAlignment = Alignment.Center,
            ) {
                val colour = if (sampling) text else quiet
                Canvas(Modifier.size(19.dp)) {
                    drawCircle(colour, style = Stroke(width = 1.5.dp.toPx()))
                    if (sampling) {
                        val s = size.width * 0.3f
                        drawRect(colour, topLeft = Offset(center.x - s / 2, center.y - s / 2), size = androidx.compose.ui.geometry.Size(s, s))
                    } else {
                        val tri = Path().apply {
                            moveTo(size.width * 0.40f, size.height * 0.30f)
                            lineTo(size.width * 0.72f, size.height * 0.50f)
                            lineTo(size.width * 0.40f, size.height * 0.70f)
                            close()
                        }
                        drawPath(tri, colour)
                    }
                }
            }
        }
    }

    // 5 Ready
    @Composable
    fun Ready(offersStart: Boolean, exit: (TourExit) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("Ready when you are")
        Paragraph("Everything is saved as you write. Menu at the top right has the rest, and this tour is there too, under Menu → Welcome.")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun capsule(label: String, tag: String, onClick: () -> Unit) = Surface(onClick = onClick, shape = CircleShape, color = text, modifier = Modifier.testTag(tag)) {
                Text(label, fontFamily = font, fontSize = 17.sp, color = page, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
            if (offersStart) {
                capsule("New document", "onboardingNew") { exit(TourExit.NEW_DOCUMENT) }
                Text(
                    "Import…", fontFamily = font, fontSize = 17.sp, color = text,
                    modifier = Modifier.clickable { exit(TourExit.IMPORT) }.padding(horizontal = 10.dp, vertical = 10.dp).testTag("onboardingImport"),
                )
            } else {
                capsule("Start writing", "onboardingStartWriting") { exit(TourExit.CLOSE) }
            }
        }
        Text(
            "Ctrl+N new  ·  Ctrl+O documents  ·  Ctrl+, menu  ·  Ctrl+D spotlight  ·  Ctrl+T anchor  ·  Ctrl+Shift+P preview",
            fontSize = 15.sp, color = quiet, modifier = Modifier.padding(top = 16.dp),
        )
    }
}

private fun uniffi.hushhour.Rgb.compose() = Color(r, g, b)

const val TOUR_TAG = "onboarding"
const val TOUR_BACKDROP_TAG = "tourBackdrop"
