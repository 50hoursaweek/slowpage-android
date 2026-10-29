package com.fiftyhoursaweek.slowpage.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.hushhour.FontChoice
import uniffi.hushhour.fontCharEm
import uniffi.hushhour.limits

/** E1–E3, with the iPad's values (points there, dp here). */
class PageMeasureTest {
    @Test fun theMeasureIs66CharactersOfTheBaseSizeCentred() {
        // 1180 dp wide → base 20, Mono 0.6 em → a 792 dp column, centred.
        assertEquals(20f, PageMeasure.base(1180f))
        assertEquals(792f, PageMeasure.measure(1180f, 0.6f), 0.001f)
        assertEquals(194f, PageMeasure.sideInset(1180f, 0.6f))
        // 834 dp → base 18; Serif 0.5 em → 594 dp column, inset 120.
        assertEquals(18f, PageMeasure.base(834f))
        assertEquals(120f, PageMeasure.sideInset(834f, 0.5f))
    }

    @Test fun narrowWindowsKeepThePaddingAndLoseTheMeasure() {
        // 500 dp (a Huawei split screen): the Mono measure does not fit; 1.75 em of padding stays.
        assertEquals(18f * 1.75f, PageMeasure.sideInset(500f, 0.6f))
    }

    @Test fun inSplitTheMeasureComesFromTheWindowAndTheCentringFromThePane() {
        // 1280 dp window, side by side: 640 dp panes are narrower than the window's measure.
        val charEm = fontCharEm(FontChoice.SERIF)
        assertEquals(PageMeasure.base(1280f) * 1.75f, PageMeasure.sideInset(640f, 1280f, charEm))
    }

    @Test fun theStepsFollowTheWindowWidth() {
        assertEquals(0f, PageMeasure.sizeStep(899f))
        assertEquals(2f, PageMeasure.sizeStep(900f))
        assertEquals(4f, PageMeasure.sizeStep(1400f))
        // The drawn size is the preferred size plus the step, never past the limit.
        val max = limits().maxSize
        assertEquals(20f, PageMeasure.bodySize(18f, 1000f, max))
        assertEquals(max, PageMeasure.bodySize(max, 1400f, max))
    }
}
