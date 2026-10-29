package com.fiftyhoursaweek.slowpage.ui

import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import uniffi.hushhour.FontChoice
import uniffi.hushhour.fontFamily

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

/** The face file name for bold and italic. */
fun faceName(bold: Boolean, italic: Boolean) = when {
    bold && italic -> "BoldItalic"
    bold -> "Bold"
    italic -> "Italic"
    else -> "Regular"
}

/**
 * Typefaces for the editor and the preview, by the family names the core uses (`font_family`,
 * `mono_family`), loaded once per face. A missing file falls back to the system face (logged).
 */
class Faces(private val assets: AssetManager) {
    private val cache = HashMap<String, Typeface>()
    private val stems = FontChoice.entries.associateBy({ fontFamily(it) }, ::fileStem)

    fun of(choice: FontChoice, bold: Boolean = false, italic: Boolean = false) =
        get(fileStem(choice), bold, italic)

    fun of(family: String, bold: Boolean = false, italic: Boolean = false): Typeface {
        val stem = stems[family] ?: return system(bold, italic).also { Log.w(TAG, "no bundled family $family") }
        return get(stem, bold, italic)
    }

    @Synchronized
    private fun get(stem: String, bold: Boolean, italic: Boolean): Typeface {
        val file = "fonts/$stem-${faceName(bold, italic)}.ttf"
        return cache.getOrPut(file) {
            runCatching { Typeface.createFromAsset(assets, file) }
                .onFailure { Log.w(TAG, "font $file not found, using the system face") }
                .getOrElse { system(bold, italic) }
        }
    }

    private fun system(bold: Boolean, italic: Boolean): Typeface = Typeface.defaultFromStyle(
        when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        },
    )

    private companion object {
        const val TAG = "SlowpageFonts"
    }
}
