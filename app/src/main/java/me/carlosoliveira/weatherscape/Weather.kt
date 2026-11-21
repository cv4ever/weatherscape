package me.carlosoliveira.weatherscape

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One weather observation or forecast hour. Precipitation is in mm per hour. */
data class WeatherSample(
    val epochSeconds: Long,
    val code: Int,
    val temperature: Double,
    val humidity: Double,
    val precipitation: Double,
    val cloudCover: Double,
    val wind: Double,
    val gusts: Double,
    val visibility: Double,
) {
    val thunder get() = code in 95..99
    val snow get() = code in 71..77 || code in 85..86

    fun describe(): String {
        val words = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..65, in 80..82 -> if (precipitation >= 4) "Heavy rain" else "Rain"
            66, 67 -> "Freezing rain"
            in 71..77, 85, 86 -> "Snow"
            in 95..99 -> "Thunderstorm"
            else -> "Weather code $code"
        }
        val windy = if (max(wind, gusts * .6) >= 30) ", windy" else ""
        return "$words$windy · ${temperature.roundToInt()}°"
    }
}

data class Place(val name: String, val detail: String, val latitude: Double, val longitude: Double)

class WeatherReport(val fetchedAt: Long, val current: WeatherSample, private val hourly: List<WeatherSample>) {
    /** The current observation, or the forecast hour closest to [hoursAhead] from now. */
    fun sample(nowSeconds: Long, hoursAhead: Int): WeatherSample {
        if (hoursAhead <= 0 || hourly.isEmpty()) return current
        val target = nowSeconds + hoursAhead * 3600L
        return hourly.minBy { abs(it.epochSeconds - target) }
    }

    /** Seconds since the last measurable rain within the past three hours, or null. */
    fun secondsSinceRain(nowSeconds: Long): Long? {
        if (current.precipitation >= .1) return 0
        // Hourly precipitation is the sum over the hour that ends at its timestamp.
        return hourly.filter { it.epochSeconds <= nowSeconds + 1800 && it.precipitation >= .1 }
            .maxOfOrNull { it.epochSeconds }
            ?.let { (nowSeconds - it).coerceAtLeast(0) }
    }

    companion object {
        fun parse(json: String, fetchedAt: Long): WeatherReport {
            val root = JSONObject(json)
            val c = root.getJSONObject("current")
            // Current precipitation covers the last `interval` seconds; scale it to an hourly rate.
            val perHour = 3600.0 / c.optDouble("interval", 3600.0).coerceAtLeast(60.0)
            val current = WeatherSample(
                epochSeconds = c.getLong("time"),
                code = c.optInt("weather_code"),
                temperature = c.optDouble("temperature_2m", 0.0),
                humidity = c.optDouble("relative_humidity_2m", 70.0),
                precipitation = c.optDouble("precipitation", 0.0) * perHour,
                cloudCover = c.optDouble("cloud_cover", 0.0),
                wind = c.optDouble("wind_speed_10m", 0.0),
                gusts = c.optDouble("wind_gusts_10m", 0.0),
                visibility = c.optDouble("visibility", 20000.0),
            )
            val h = root.getJSONObject("hourly")
            val times = h.getJSONArray("time")
            fun JSONArray.at(i: Int, fallback: Double) = if (isNull(i)) fallback else optDouble(i, fallback)
            val hourly = (0 until times.length()).map { i ->
                WeatherSample(
                    epochSeconds = times.getLong(i),
                    code = h.getJSONArray("weather_code").optInt(i),
                    temperature = h.getJSONArray("temperature_2m").at(i, 0.0),
                    humidity = h.getJSONArray("relative_humidity_2m").at(i, 70.0),
                    precipitation = h.getJSONArray("precipitation").at(i, 0.0),
                    cloudCover = h.getJSONArray("cloud_cover").at(i, 0.0),
                    wind = h.getJSONArray("wind_speed_10m").at(i, 0.0),
                    gusts = h.getJSONArray("wind_gusts_10m").at(i, 0.0),
                    visibility = h.getJSONArray("visibility").at(i, 20000.0),
                )
            }
            return WeatherReport(fetchedAt, current, hourly)
        }
    }
}

/** Open-Meteo needs no API key. */
object OpenMeteo {
    private const val FIELDS =
        "temperature_2m,relative_humidity_2m,precipitation,weather_code,cloud_cover,wind_speed_10m,wind_gusts_10m,visibility"

    fun forecastJson(latitude: Double, longitude: Double): String = get(
        "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=$FIELDS&hourly=$FIELDS&past_hours=3&forecast_hours=8&timeformat=unixtime&timezone=GMT",
    )

    fun search(name: String): List<Place> {
        val query = URLEncoder.encode(name.trim(), "UTF-8")
        val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$query&count=6&format=json"))
            .optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val detail = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            Place(r.getString("name"), detail, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("Open-Meteo answered HTTP $status")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/** The scene's weather inputs: wind, valley mist, cloud cover, rainfall, plus lightning. */
data class Atmosphere(val wind: Float, val mist: Float, val cover: Float, val rain: Float, val thunder: Boolean = false) {
    companion object {
        val SUNNY = Atmosphere(.66f, .11f, .03f, 0f)
        val WINDY = Atmosphere(2.55f, .18f, .27f, 0f)
        val RAINY = Atmosphere(.92f, .67f, .79f, .48f)
        val STORM = Atmosphere(2.85f, .78f, 1f, 1f, thunder = true)
        val FOGGY = Atmosphere(1f, 1.13f, 0f, 0f)

        private fun curve(x: Double, vararg points: Pair<Double, Double>): Float {
            if (x <= points.first().first) return points.first().second.toFloat()
            for (i in 1 until points.size) {
                val (x1, y1) = points[i]
                val (x0, y0) = points[i - 1]
                if (x <= x1) return (y0 + (y1 - y0) * (x - x0) / (x1 - x0)).toFloat()
            }
            return points.last().second.toFloat()
        }

        /** Maps measured weather onto the shader's ranges, tuned against the hand-made presets above. */
        fun from(s: WeatherSample, secondsSinceRain: Long?): Atmosphere {
            val windy = max(s.wind, s.gusts * .6)
            var wind = curve(windy, 0.0 to .45, 10.0 to .7, 20.0 to 1.1, 30.0 to 1.7, 45.0 to 2.5, 60.0 to 2.9)
            var cover = (s.cloudCover / 100).toFloat()
            // Snow has no renderer yet, so it shows as low overcast instead of rain.
            var rain = if (s.snow) 0f else curve(
                s.precipitation, 0.0 to 0.0, .1 to .12, .5 to .3, 1.0 to .45, 2.5 to .65, 5.0 to .85, 8.0 to 1.0,
            )
            when (s.code) {
                3 -> cover = max(cover, .85f)
                in 51..57 -> rain = max(rain, .15f)
                in 61..67, in 80..82 -> { rain = max(rain, .3f); cover = max(cover, .7f) }
                in 71..77, 85, 86 -> cover = max(cover, .9f)
            }
            if (s.thunder) { rain = max(rain, .8f); cover = 1f; wind = max(wind, 2.6f) }
            var mist = .12f + smooth(70f, 100f, s.humidity.toFloat()) * .7f +
                (1 - smooth(1000f, 6000f, s.visibility.toFloat())) * .4f
            if (s.code == 45 || s.code == 48) mist = max(mist, 1.15f)
            // Rain leaves mist in the valley for a while after it stops.
            if (secondsSinceRain != null) mist += .6f * (1 - smooth(0f, 5400f, secondsSinceRain.toFloat()))
            return Atmosphere(wind, mist.coerceAtMost(1.25f), cover.coerceIn(0f, 1f), rain, s.thunder)
        }
    }
}
