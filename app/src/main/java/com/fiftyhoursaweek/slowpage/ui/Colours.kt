package com.fiftyhoursaweek.slowpage.ui

import uniffi.hushhour.Palette
import uniffi.hushhour.Rgb
import uniffi.hushhour.backdropTiming
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colour rules ported from the iPad (`Colours.swift`), with the same test values (E7). */
object Colours {
    /** Muted UI text on the plain page (status line): the text faded 30% towards the page, as on Linux. */
    const val MUTED_FADE = 0.30f
    /** How far the accent darkens where white text sits on it (the Edit capsule, View: Preview). */
    const val ACCENT_FILL_DARKENING = 0.25f

    fun muted(p: Palette) = mix(p.text, p.background, MUTED_FADE)

    /** Secondary text over the page (quotes, list markers, rules, image text in the preview). */
    fun secondary(p: Palette) = mix(p.text, p.background, backdropTiming().maxSecondaryFade)

    /** The fill behind white text: the accent, darkened. */
    fun accentFill(p: Palette) = mix(p.primary, Rgb(0f, 0f, 0f), ACCENT_FILL_DARKENING)

    /** The caret has the text colour, as on Linux and the iPad (E6). */
    fun caret(p: Palette): Rgb = p.text

    /** The selection: a grey highlight, the text colour at a quarter over the page (E6). */
    fun selection(p: Palette) = mix(p.background, p.text, 0.25f)

    /** The accent darkened towards black in 5% steps until it reaches 4.5:1 on the page. */
    fun link(p: Palette): Rgb {
        var step = 0f
        var colour = p.primary
        while (contrast(colour, p.background) < 4.5 && step < 1f) {
            step += 0.05f
            colour = mix(p.primary, Rgb(0f, 0f, 0f), step)
        }
        return colour
    }

    /** WCAG contrast ratio of two sRGB colours. */
    fun contrast(a: Rgb, b: Rgb): Double {
        fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        fun luminance(c: Rgb) = 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
        val (x, y) = luminance(a) to luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    fun mix(a: Rgb, b: Rgb, t: Float) = Rgb(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t)

    /** As an Android colour int, opaque. */
    fun argb(c: Rgb): Int {
        fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (channel(c.r) shl 16) or (channel(c.g) shl 8) or channel(c.b)
    }
}
