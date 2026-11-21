package me.carlosoliveira.weatherscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextChangeTest {
    private val now = 1_800_000_000L

    private fun at(hours: Int, code: Int) = WeatherSample(now + hours * 3600L, code, 15.0, 80.0, 0.0, 50.0, 10.0, 20.0, 20000.0)

    @Test
    fun findsTheFirstLaterHourWithADifferentKindOfWeather() {
        // Past rain and a same-kind hour (partly cloudy after clear) are not changes.
        val report = WeatherReport(now * 1000, at(0, 1), listOf(at(-2, 61), at(1, 2), at(3, 63), at(2, 61), at(5, 0)))
        assertEquals(Sky.RAIN to now + 2 * 3600L, report.nextChange(now))
    }

    @Test
    fun reportsClearingWhenItIsRainingNow() {
        val report = WeatherReport(now * 1000, at(0, 80), listOf(at(1, 81), at(2, 3), at(3, 0)))
        assertEquals(Sky.CLOUDY to now + 2 * 3600L, report.nextChange(now))
    }

    @Test
    fun noChangeWhenTheForecastKeepsTheSameKind() {
        val report = WeatherReport(now * 1000, at(0, 45), listOf(at(1, 48), at(2, 45)))
        assertNull(report.nextChange(now))
    }
}
