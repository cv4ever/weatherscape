package me.carlosoliveira.weatherscape

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

const val TAU = (PI * 2).toFloat()

fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

fun smooth(lo: Float, hi: Float, x: Float): Float {
    val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

fun wrap(x: Float) = x - floor(x)

/**
 * Sun position for one UTC date at one place, from NOAA's fractional-year approximation.
 * Times are minutes after 00:00 UTC on that date.
 */
class SolarDay(
    val noon: Double,
    val sunrise: Double,
    val sunset: Double,
    private val sinBase: Double,
    private val cosSpan: Double,
) {
    /** Sun altitude in degrees at [minute] after 00:00 UTC. */
    fun altitude(minute: Double): Double {
        val angle = (minute - noon) * PI / 720
        return Math.toDegrees(asin((sinBase + cosSpan * cos(angle)).coerceIn(-1.0, 1.0)))
    }

    companion object {
        fun of(latitude: Double, longitude: Double, date: LocalDate): SolarDay {
            val yearDays = if (date.isLeapYear) 366.0 else 365.0
            val gamma = 2 * PI * (date.dayOfYear - 1) / yearDays
            val equationOfTime = 229.18 * (.000075 + .001868 * cos(gamma) - .032077 * sin(gamma) -
                .014615 * cos(2 * gamma) - .040849 * sin(2 * gamma))
            val declination = .006918 - .399912 * cos(gamma) + .070257 * sin(gamma) -
                .006758 * cos(2 * gamma) + .000907 * sin(2 * gamma) -
                .002697 * cos(3 * gamma) + .00148 * sin(3 * gamma)
            val lat = Math.toRadians(latitude)
            val noon = 720 - 4 * longitude - equationOfTime
            val sinBase = sin(lat) * sin(declination)
            val cosSpan = cos(lat) * cos(declination)
            // Refraction and the sun's disc put sunrise at -0.833 degrees. Polar day and night clamp.
            val ratio = ((sin(Math.toRadians(-.833)) - sinBase) / cosSpan).coerceIn(-1.0, 1.0)
            val half = 720 / PI * acos(ratio)
            return SolarDay(noon, noon - half, noon + half, sinBase, cosSpan)
        }

        fun at(latitude: Double, longitude: Double, instant: Instant): Pair<SolarDay, Double> {
            val utc = instant.atOffset(ZoneOffset.UTC)
            val minute = utc.hour * 60.0 + utc.minute + utc.second / 60.0
            return of(latitude, longitude, utc.toLocalDate()) to minute
        }
    }
}
