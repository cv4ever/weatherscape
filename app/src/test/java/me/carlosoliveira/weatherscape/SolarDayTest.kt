package me.carlosoliveira.weatherscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SolarDayTest {
    private fun minutes(hour: Int, minute: Int) = hour * 60.0 + minute

    @Test
    fun lisbonSolsticeMatchesPublishedTimes() {
        // timeanddate.com, Lisbon, 21 June 2026: sunrise 06:12, sunset 21:05 local (UTC+1).
        val day = SolarDay.of(38.7223, -9.1393, LocalDate.of(2026, 6, 21))
        assertEquals(minutes(5, 12), day.sunrise, 3.0)
        assertEquals(minutes(20, 5), day.sunset, 3.0)
    }

    @Test
    fun altitudeIsZeroAtSunsetAndNegativeAtNight() {
        val day = SolarDay.of(51.5072, -.1276, LocalDate.of(2026, 12, 21))
        assertEquals(-.833, day.altitude(day.sunset), .3)
        assertTrue(day.altitude(day.sunset + 120) < -12)
    }
}
