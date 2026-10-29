package com.fiftyhoursaweek.slowpage.editor

import kotlin.math.floor

/**
 * The column geometry, as on Linux and the iPad (E1–E3), in dp for points: the measure is 66
 * characters of the base size (not the zoomed size), so zooming reflows text inside a column
 * whose margins stay put; the column is centred, with at least 1.75 em of padding each side.
 */
object PageMeasure {
    const val MEASURE_CHARACTERS = 66f
    const val SIDE_PADDING_EM = 1.75f
    const val BASE_SIZE = 18f
    /** dp added to the sizes at or above a window width, so text fills a large screen. */
    private val sizeSteps = listOf(900f to 2f, 1400f to 4f)

    fun sizeStep(width: Float): Float = sizeSteps.lastOrNull { width >= it.first }?.second ?: 0f

    /** The size the column width and padding come from: depends on the window only. */
    fun base(width: Float) = BASE_SIZE + sizeStep(width)

    /** Width of the column for a font whose running text averages [charEm] em per character. */
    fun measure(width: Float, charEm: Float) = base(width) * charEm * MEASURE_CHARACTERS

    /** Horizontal inset each side: whatever centres the measure, never less than the padding. */
    fun sideInset(width: Float, charEm: Float) = sideInset(width, width, charEm)

    /** The same for a pane narrower than the window (Split): measure and padding from the window. */
    fun sideInset(paneWidth: Float, windowWidth: Float, charEm: Float): Float {
        val padding = base(windowWidth) * SIDE_PADDING_EM
        return maxOf(padding, floor((paneWidth - measure(windowWidth, charEm)) / 2))
    }

    /** The size text is drawn at: the preferred size plus the window step, within the limit. */
    fun bodySize(preferred: Float, width: Float, maxSize: Float) = minOf(preferred + sizeStep(width), maxSize)

    /** Split sits side by side from this width (dp) and stacks below it (V2). */
    const val SIDE_BY_SIDE_MINIMUM_WIDTH = 1000f
}
