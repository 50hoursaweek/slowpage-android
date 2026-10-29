package com.fiftyhoursaweek.slowpage.ui

import android.content.res.AssetManager
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import uniffi.hushhour.FontChoice

/**
 * The editor's three bundled OFL families (E4), loaded by file name from `assets/fonts`
 * (scripts/build-rust.sh copies them unmodified): Mono = IBM Plex Mono, Sans = IBM Plex Sans,
 * Serif = Literata, each with regular, italic, bold and bold italic.
 */
fun fileStem(choice: FontChoice): String = when (choice) {
    FontChoice.MONO -> "IBMPlexMono"
    FontChoice.SANS -> "IBMPlexSans"
    FontChoice.SERIF -> "Literata"
}

fun editorFontFamily(choice: FontChoice, assets: AssetManager): FontFamily {
    val stem = fileStem(choice)
    fun face(name: String, weight: FontWeight, style: FontStyle) =
        Font("fonts/$stem-$name.ttf", assets, weight, style)
    return FontFamily(
        face("Regular", FontWeight.Normal, FontStyle.Normal),
        face("Italic", FontWeight.Normal, FontStyle.Italic),
        face("Bold", FontWeight.Bold, FontStyle.Normal),
        face("BoldItalic", FontWeight.Bold, FontStyle.Italic),
    )
}
