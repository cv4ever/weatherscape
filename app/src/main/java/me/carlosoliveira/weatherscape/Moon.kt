package me.carlosoliveira.weatherscape

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Low-precision moon position and phase (the SunCalc algorithm, after Meeus), good to about a
 * degree: plenty for placing the moon in a painted sky.
 */
object Moon {
    class State(
        /** Degrees above the horizon, refraction included. */
        val altitude: Double,
        /** Degrees from south, positive towards the west. */
        val azimuth: Double,
        /** Lit fraction of the disc, 0 new to 1 full. */
        val fraction: Double,
        /** Sun-moon elongation in radians, 0 new to PI full. */
        val elongation: Double,
        /** True while waxing, when the lit limb faces west (right, seen from the north). */
        val waxing: Boolean,
    )

    private const val RAD = PI / 180
    private const val OBLIQUITY = RAD * 23.4397

    private fun days(millis: Long) = millis / 86_400_000.0 - .5 + 2440588 - 2451545
    private fun rightAscension(l: Double, b: Double) = atan2(sin(l) * cos(OBLIQUITY) - tan(b) * sin(OBLIQUITY), cos(l))
    private fun declination(l: Double, b: Double) = asin(sin(b) * cos(OBLIQUITY) + cos(b) * sin(OBLIQUITY) * sin(l))

    fun at(millis: Long, latitude: Double, longitude: Double): State {
        val d = days(millis)
        val phi = RAD * latitude
        val lw = RAD * -longitude

        val meanLongitude = RAD * (218.316 + 13.176396 * d)
        val meanAnomaly = RAD * (134.963 + 13.064993 * d)
        val meanDistance = RAD * (93.272 + 13.229350 * d)
        val moonLon = meanLongitude + RAD * 6.289 * sin(meanAnomaly)
        val moonLat = RAD * 5.128 * sin(meanDistance)
        val moonDistance = 385001 - 20905 * cos(meanAnomaly)
        val moonRa = rightAscension(moonLon, moonLat)
        val moonDec = declination(moonLon, moonLat)

        val hourAngle = RAD * (280.16 + 360.9856235 * d) - lw - moonRa
        var altitude = asin(sin(phi) * sin(moonDec) + cos(phi) * cos(moonDec) * cos(hourAngle))
        val azimuth = atan2(sin(hourAngle), cos(hourAngle) * sin(phi) - tan(moonDec) * cos(phi))
        // Atmospheric refraction lifts objects near the horizon.
        val h = maxOf(altitude, 0.0)
        altitude += .0002967 / tan(h + .00312536 / (h + .08901179))

        val sunAnomaly = RAD * (357.5291 + .98560028 * d)
        val centre = RAD * (1.9148 * sin(sunAnomaly) + .02 * sin(2 * sunAnomaly) + .0003 * sin(3 * sunAnomaly))
        val sunLon = sunAnomaly + centre + RAD * 102.9372 + PI
        val sunRa = rightAscension(sunLon, 0.0)
        val sunDec = declination(sunLon, 0.0)
        val sunDistance = 149598000.0
        val separation = acos((sin(sunDec) * sin(moonDec) + cos(sunDec) * cos(moonDec) * cos(sunRa - moonRa)).coerceIn(-1.0, 1.0))
        val incidence = atan2(sunDistance * sin(separation), moonDistance - sunDistance * cos(separation))
        val angle = atan2(cos(sunDec) * sin(sunRa - moonRa), sin(sunDec) * cos(moonDec) - cos(sunDec) * sin(moonDec) * cos(sunRa - moonRa))
        return State(
            altitude = altitude / RAD,
            azimuth = azimuth / RAD,
            fraction = (1 + cos(incidence)) / 2,
            elongation = PI - incidence,
            waxing = angle < 0,
        )
    }
}
