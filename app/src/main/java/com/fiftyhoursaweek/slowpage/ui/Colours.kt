package com.fiftyhoursaweek.slowpage.ui

import uniffi.hushhour.Palette
import uniffi.hushhour.Rgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colour rules ported from the iPad (`Colours.swift`), with the same test values (E7). */
object Colours {
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
}
