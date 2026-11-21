package me.carlosoliveira.weatherscape

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MoonTest {
    private fun at(iso: String) = Moon.at(Instant.parse(iso).toEpochMilli(), 39.74, -8.81)

    @Test
    fun phaseMatchesPublishedFullAndNewMoons() {
        // timeanddate.com: full moon 15 Dec 2024 09:02 UTC, new moon 30 Dec 2024 22:27 UTC.
        assertTrue(at("2024-12-15T09:02:00Z").fraction > .99)
        assertTrue(at("2024-12-30T22:27:00Z").fraction < .01)
    }

    @Test
    fun waxingBeforeFullWaningAfter() {
        assertTrue(at("2024-12-10T00:00:00Z").waxing)
        assertTrue(!at("2024-12-20T00:00:00Z").waxing)
    }

    @Test
    fun fullMoonIsUpAtMidnightAndDownAtNoon() {
        // Around full moon it rises near sunset and sets near sunrise.
        assertTrue(at("2024-12-15T00:30:00Z").altitude > 30)
        assertTrue(at("2024-12-15T12:30:00Z").altitude < 0)
    }
}
