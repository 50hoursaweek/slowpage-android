package com.fiftyhoursaweek.slowpage.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fiftyhoursaweek.slowpage.BuildConfig
import com.fiftyhoursaweek.slowpage.Dialog
import com.fiftyhoursaweek.slowpage.ShellViewModel
import com.fiftyhoursaweek.slowpage.editor.BackdropView
import uniffi.hushhour.Backdrop
import uniffi.hushhour.FontChoice
import uniffi.hushhour.Palette
import uniffi.hushhour.SoundSet
import uniffi.hushhour.Theme
import uniffi.hushhour.backdropLabel
import uniffi.hushhour.fontLabel
import uniffi.hushhour.limits
import uniffi.hushhour.musicLabel
import uniffi.hushhour.soundSetLabel
import uniffi.hushhour.themeLabel
import kotlin.math.roundToInt

/*
 * The dialogs: Appearance, Sound and music, About with credits and licences (O5), the iPad's
 * `Dialogs.swift` one to one: same copy, controls, ranges and steps, in Lato as on Linux and
 * the iPad. A form sheet there; here a sheet of the same size over the screen, in the page's
 * colours, with Done and the page title at the top. Back and Esc close it, as Esc does there.
 */

/** Lato Regular, the face of the dialogs and the tour (as on Linux and the iPad). */
@Composable
fun lato(): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets) { FontFamily(Font("fonts/Lato-Regular.ttf", assets)) }
}

/** The sheet of [dialog] over the current screen. */
@Composable
fun DialogSheet(dialog: Dialog, vm: ShellViewModel, onImportMusic: () -> Unit) {
    // The iPad's preferred sizes: 480 × 640 (Appearance), 480 × 560 (Sound and music), 360 × 400 (About).
    val (width, height) = when (dialog) {
        Dialog.APPEARANCE -> 480 to 640
        Dialog.SOUND -> 480 to 560
        Dialog.ABOUT -> 360 to 400
    }
    // About's pages: About, then Credits and licences, then one licence text.
    var pages by rememberSaveable(dialog) { mutableStateOf(listOf<String>()) }
    val close = { vm.closeDialog() }
    ComposeDialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveWindow()
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = width.dp)
                .heightIn(max = height.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .testTag(DIALOG_TAG)
                .onPreviewKeyEvent {
                    if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE && it.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                        close()
                        true
                    } else {
                        false
                    }
                },
        ) {
            val font = lato()
            Column(Modifier.fillMaxSize()) {
                val title = when {
                    pages.isNotEmpty() -> pageTitle(pages.last())
                    dialog == Dialog.APPEARANCE -> "Appearance"
                    dialog == Dialog.SOUND -> "Sound and music"
                    else -> ""
                }
                val backTo = when (pages.size) {
                    0 -> null
                    1 -> "About"
                    else -> pageTitle(pages[pages.size - 2])
                }
                TitleBar(title, backTo, font, onBack = { pages = pages.dropLast(1) }, onDone = close)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        pages.isNotEmpty() && pages.last() == CREDITS -> Credits(font) { pages = pages + it }
                        pages.isNotEmpty() -> LicenceText(pages.last())
                        dialog == Dialog.APPEARANCE -> Appearance(vm, font)
                        dialog == Dialog.SOUND -> Sound(vm, font, onImportMusic)
                        else -> About(vm.preferences.theme, font, onCredits = { pages = listOf(CREDITS) }, onWelcome = vm::welcomeFromAbout)
                    }
                }
                // Whatever could not be saved (S3), whichever screen the dialog is over.
                for (line in vm.problems) {
                    Text(
                        line,
                        fontFamily = font,
                        fontSize = 13.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(Color(0.83f, 0.18f, 0.18f)).padding(8.dp).testTag("problems"),
                    )
                }
            }
        }
    }
}

private const val CREDITS = "credits"

private fun pageTitle(page: String) = if (page == CREDITS) "Credits and licences" else LICENCES.firstOrNull { it.second == page }?.first ?: page

/**
 * The back link, the title centred and Done. On a phone the three would overlap (Francesco's
 * phone test): the title is centred when it fits clear of both sides, else it takes the room
 * between them; the back link takes at most 40% of the bar; either is cut short with "…" rather
 * than run into the other.
 */
@Composable
private fun TitleBar(title: String, backTo: String?, font: FontFamily, onBack: () -> Unit, onDone: () -> Unit) {
    Layout(
        content = {
            if (backTo != null) {
                TextButton(onClick = onBack) {
                    Text("‹ $backTo", fontFamily = font, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(title, fontFamily = font, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onDone, modifier = Modifier.testTag(DONE_TAG)) {
                Text("Done", fontFamily = font, fontSize = 17.sp, maxLines = 1)
            }
        },
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
    ) { parts, c ->
        val w = c.maxWidth
        val h = c.maxHeight
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val back = if (backTo != null) parts[0].measure(loose.copy(maxWidth = w * 2 / 5)) else null
        val done = parts.last().measure(loose)
        val side = maxOf(back?.width ?: 0, done.width)
        val title = parts[if (backTo != null) 1 else 0]
        val centred = minOf(220.dp.roundToPx(), maxOf(0, w - 2 * side))
        // Centred when it fits there; otherwise in all the room between the back link and Done.
        val fits = title.maxIntrinsicWidth(h) <= centred
        val gap = maxOf(0, w - (back?.width ?: 0) - done.width)
        val text = title.measure(loose.copy(maxWidth = if (fits) centred else gap))
        layout(w, h) {
            back?.place(0, (h - back.height) / 2)
            text.place(if (fits) (w - text.width) / 2 else back?.width ?: 0, (h - text.height) / 2)
            done.place(w - done.width, (h - done.height) / 2)
        }
    }
}

// ---- Form pieces, after SwiftUI's Form: sections with a header and a footer, rows in a group.

@Composable
private fun Section(font: FontFamily, header: String? = null, footer: String? = null, rows: @Composable () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (header != null) {
            Text(header, fontFamily = font, fontSize = 13.sp, color = muted, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) { rows() }
        if (footer != null) {
            Text(footer, fontFamily = font, fontSize = 13.sp, color = muted, modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp))
        }
    }
}

/** A segmented picker: the label is not shown, as in a SwiftUI Form. */
@Composable
private fun <T> Segmented(label: String, all: List<T>, selected: T, name: (T) -> String, font: FontFamily, choose: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("segments-$label")) {
        all.forEachIndexed { i, value ->
            SegmentedButton(
                selected = value == selected,
                onClick = { choose(value) },
                shape = SegmentedButtonDefaults.itemShape(i, all.size),
                icon = {},
                modifier = Modifier.testTag("$label-${name(value)}"),
            ) { Text(name(value), fontFamily = font, fontSize = 14.sp, maxLines = 1) }
        }
    }
}

/** The iPad's `LabeledSlider`: the title and the value above, the slider below, on [step]s. */
@Composable
private fun LabeledSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, font: FontFamily, label: (Float) -> String, set: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, fontFamily = font, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(label(value), fontFamily = font, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
        Slider(
            value = value.coerceIn(range),
            onValueChange = { set(it) },
            valueRange = range,
            steps = maxOf(0, steps),
            // A plain track, as the iPad's: the steps are there, not drawn.
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.testTag("slider-$title"),
        )
    }
}

@Composable
private fun Toggle(title: String, on: Boolean, font: FontFamily, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontFamily = font, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = set, modifier = Modifier.testTag("toggle-$title"))
    }
}

private fun percent(v: Float) = "${(v * 100).roundToInt()}%"

/** On the 0.05 steps of the iPad's sliders. */
private fun twentieths(v: Float) = (Math.round(v * 20) / 20f).coerceIn(0f, 1f)

// ---- Appearance

@Composable
private fun Appearance(vm: ShellViewModel, font: FontFamily) {
    val p = vm.preferences
    val l = limits()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section(font) {
            Segmented("Theme", listOf(Theme.LIGHT, Theme.SEPIA, Theme.DARK), p.theme, ::themeLabel, font) { vm.updatePreferences(vm.preferences.copy(theme = it)) }
            Segmented("Font", listOf(FontChoice.MONO, FontChoice.SANS, FontChoice.SERIF), p.font, ::fontLabel, font) { vm.updatePreferences(vm.preferences.copy(font = it)) }
            LabeledSlider("Size", p.size, l.minSize..l.maxSize, 1f, font, { "${it.roundToInt()} pt" }) {
                val size = it.roundToInt().toFloat()
                if (size != vm.preferences.size) vm.updatePreferences(vm.preferences.copy(size = size))
            }
            LabeledSlider("Line height", p.lineHeight, l.minLineHeight..l.maxLineHeight, 0.05f, font, { "%.2f".format(it) }) { vm.setLineHeight(it) }
        }
        // No frosted glass, even with an old setting stored (E17).
        Section(font, header = "Background", footer = "Rests while you type; still with Remove animations.") {
            Segmented("Background", listOf(Backdrop.OFF, Backdrop.WAVES, Backdrop.SWELL, Backdrop.DRIFT), p.backdrop, ::backdropLabel, font) {
                vm.updatePreferences(vm.preferences.copy(backdrop = it))
            }
        }
    }
}

// ---- Sound and music

@Composable
private fun Sound(vm: ShellViewModel, font: FontFamily, onImportMusic: () -> Unit) {
    val p = vm.preferences
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section(font, header = "Typing sound") {
            Toggle("Enabled", p.typingSound, font) { vm.updatePreferences(vm.preferences.copy(typingSound = it)) }
            Segmented("Sound", listOf(SoundSet.SOFT, SoundSet.KEYBOARD), p.soundSet, ::soundSetLabel, font) { vm.updatePreferences(vm.preferences.copy(soundSet = it)) }
            LabeledSlider("Volume", p.soundVolume, 0f..1f, 0.05f, font, ::percent) {
                val v = twentieths(it)
                if (v != vm.preferences.soundVolume) vm.updatePreferences(vm.preferences.copy(soundVolume = v))
            }
        }
        Section(font, header = "Music") {
            MusicPicker(vm, font)
            LabeledSlider("Volume", p.musicVolume, 0f..1f, 0.05f, font, ::percent) {
                val v = twentieths(it)
                if (v != vm.preferences.musicVolume) vm.updatePreferences(vm.preferences.copy(musicVolume = v))
            }
            // Android has no Files folder of the app's to drop tracks in: they are copied in.
            TextButton(onClick = onImportMusic, modifier = Modifier.testTag("importMusic")) { Text("Import music…", fontFamily = font, fontSize = 16.sp) }
            Text(
                "Add your own tracks (mp3, ogg, flac, wav) with Import music…; they are copied into Slowpage.",
                fontFamily = font,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/** SwiftUI's menu-style Picker in a Form: the label, the choice, and a menu of all of them. */
@Composable
private fun MusicPicker(vm: ShellViewModel, font: FontFamily) {
    var open by remember { mutableStateOf(false) }
    val checked = vm.preferences.music
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 12.dp).testTag("musicPicker"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Music", fontFamily = font, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("${musicLabel(checked)}  ▾", fontFamily = font, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ImmersiveWindow()
            for (choice in vm.musicChoices) {
                DropdownMenuItem(
                    text = { Text(if (choice == checked) "✓  ${musicLabel(choice)}" else musicLabel(choice), fontFamily = font) },
                    onClick = {
                        open = false
                        vm.setMusic(choice)
                    },
                )
            }
        }
    }
}

// ---- About

private const val REPOSITORY = "https://github.com/50hoursaweek/hushhour"

@Composable
private fun About(theme: Theme, font: FontFamily, onCredits: () -> Unit, onWelcome: () -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val icon = remember(theme) {
        runCatching {
            context.assets.open(if (theme == Theme.DARK) "about/Icon-dark.png" else "about/Icon-light.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.asImageBitmap()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.padding(top = 12.dp).size(96.dp).clip(RoundedCornerShape(22.dp)))
        }
        Text("Slowpage", fontFamily = font, fontSize = 22.sp)
        Text("Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", fontFamily = font, fontSize = 14.sp, color = muted)
        Text("© 2026 Grunt", fontFamily = font, fontSize = 14.sp, color = muted)
        Text(
            "github.com/50hoursaweek/hushhour",
            fontFamily = font,
            fontSize = 15.sp,
            color = accent,
            modifier = Modifier.padding(top = 4.dp).clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, REPOSITORY.toUri())) }
                    .onFailure { Log.w("SlowpageAbout", "no browser for the link") }
            },
        )
        Text("Credits and licences", fontFamily = font, fontSize = 15.sp, color = accent, modifier = Modifier.clickable(onClick = onCredits).testTag("credits"))
        Text("Welcome…", fontFamily = font, fontSize = 15.sp, color = accent, modifier = Modifier.clickable(onClick = onWelcome).testTag("aboutWelcome"))
    }
}

/** The licence texts About lists: the iPad's, then the Android build's own. */
val LICENCES = listOf(
    "Slowpage (MIT)" to "licences/LICENSE-Slowpage.txt",
    "hushhour-core (MIT)" to "licences/LICENSE-hushhour-core.txt",
    "IBM Plex Mono (OFL 1.1)" to "fonts/LICENSE-IBM-Plex-Mono.txt",
    "IBM Plex Sans (OFL 1.1)" to "fonts/LICENSE-IBM-Plex-Sans.txt",
    "Literata (OFL 1.1)" to "fonts/LICENSE-Literata.txt",
    "Lato (OFL 1.1)" to "fonts/LICENSE-Lato.md",
    "Typing sounds (CC0)" to "licences/LICENSE-sounds.md",
    "UniFFI and Symphonia (MPL 2.0)" to "licences/LICENSE-MPL-2.0.txt",
    "All Rust crates in the core" to "licences/LICENSES-rust.txt",
    "Android libraries" to "licences/NOTICE-android.txt",
    "Apache License 2.0" to "licences/LICENSE-Apache-2.0.txt",
    "LLVM libc++" to "licences/LICENSE-LLVM.txt",
)

@Composable
private fun Credits(font: FontFamily, open: (String) -> Unit) {
    val paragraphs = listOf(
        "Slowpage is released under the MIT License.",
        "Fonts: IBM Plex Mono and IBM Plex Sans (IBM), Literata (TypeTogether for Google Fonts) and Lato (Łukasz Dziedzic), all under the SIL Open Font License 1.1, used unmodified.",
        "Typing sounds: cut from Keyboard Soundpack #1 by unicaegames (OpenGameArt), CC0 1.0.",
        "Music loops generated with Google Lyria 3 (AI, SynthID watermark). Dedicated to the public domain under CC0, to the extent any rights exist.",
        "Soundscapes are synthesised in code.",
        "The Markdown scanner, statistics, undo history and soundscapes are the shared hushhour-core crate (MIT), bridged with UniFFI and decoding audio with Symphonia (both MPL 2.0). Every other Rust crate linked into the core (MIT, Apache 2.0, BSD, Zlib, Unicode) is listed with its notice under \"All Rust crates in the core\".",
        "On Android, sound plays through Oboe and the core is loaded with JNA; the screens are AndroidX and Jetpack Compose, in Kotlin; all under the Apache License 2.0 (JNA also under the LGPL). The audio code links LLVM libc++ (Apache 2.0 with LLVM Exceptions). They are listed under \"Android libraries\".",
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section(font) {
            for (text in paragraphs) Text(text, fontFamily = font, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        Section(font, header = "Licence texts") {
            LICENCES.forEachIndexed { i, (title, file) ->
                if (i > 0) HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { open(file) }.padding(vertical = 12.dp).testTag("licence-$title"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, fontFamily = font, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A licence text, in a monospaced face, selectable; laid out a paragraph at a time (the Rust list is 170 KB). */
@Composable
private fun LicenceText(file: String) {
    val context = LocalContext.current
    val paragraphs = remember(file) {
        runCatching { context.assets.open(file).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrDefault("The licence file is missing from this build.")
            .split("\n\n")
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("licenceText")) {
            item { Spacer(Modifier.height(20.dp)) }
            items(paragraphs) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp)) }
        }
    }
}

// ---- The background pattern, for the editor and the tour

/**
 * The pattern of [backdrop] in the colours of [p], behind what follows it in the layout. It
 * runs while the activity is in front (resumed), as the iPad's while its scene is active.
 */
@Composable
fun BackdropLayer(backdrop: Backdrop, p: Palette, typedAt: () -> Long, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { arrayOfNulls<BackdropView>(1) }
    AndroidView(
        factory = { context -> BackdropView(context).also { holder[0] = it } },
        modifier = modifier,
        update = { view ->
            view.typedAt = typedAt
            view.setColours(p.background, p.text)
            view.backdrop = backdrop
            view.inFront = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        },
    )
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> holder[0]?.inFront = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** Test tags. */
const val DIALOG_TAG = "dialog"
const val DONE_TAG = "done"
