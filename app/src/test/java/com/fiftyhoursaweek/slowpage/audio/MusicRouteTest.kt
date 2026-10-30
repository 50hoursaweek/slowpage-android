package com.fiftyhoursaweek.slowpage.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.hushhour.MusicChoice

/** A6: the iPad's `MusicRoute` tests (OnboardingTests), ported. */
class MusicRouteTest {
    private val someTrack = MusicChoice.Rain
    private val otherTrack = MusicChoice.Night

    @Test fun silenceOverridesAHoldUntilRelease() {
        val route = MusicRoute()
        route.hold(otherTrack)
        route.silence()
        assertEquals(MusicChoice.Off, route.target(someTrack))
        route.sample = otherTrack
        assertEquals("a sample still plays", otherTrack, route.target(someTrack))
        route.sample = null
        route.release()
        assertEquals(someTrack, route.target(someTrack))
    }

    @Test fun theRouteHoldsWhatPlayedAndASampleGoesOverEverything() {
        val route = MusicRoute()
        assertEquals("not held: the checked track", someTrack, route.target(someTrack))
        route.hold(MusicChoice.Off)
        assertEquals("held: what played when the hold began", MusicChoice.Off, route.target(someTrack))
        route.hold(otherTrack)
        assertEquals("a second hold keeps the first", MusicChoice.Off, route.target(someTrack))
        route.sample = otherTrack
        assertEquals("a sample over everything", otherTrack, route.target(someTrack))
        route.sample = null
        route.release()
        assertEquals(someTrack, route.target(someTrack))
    }
}
