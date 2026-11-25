package me.carlosoliveira.weatherscape

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class Mode { LIVE, TIMELAPSE }
enum class WeatherChoice { AUTO, SUNNY, WINDY, RAINY, STORM, FOGGY, SNOWY }
enum class TimeChoice { AUTO, MORNING, AFTERNOON, SUNSET, NIGHT }

/** Minute after 00:00 UTC that this choice shows on [day]; [now] when following the sun. */
fun TimeChoice.minuteOn(day: SolarDay, now: Double): Double = when (this) {
    TimeChoice.AUTO -> now
    TimeChoice.MORNING -> day.sunrise + 75
    TimeChoice.AFTERNOON -> minOf(day.noon + 150, day.sunset - 70)
    TimeChoice.SUNSET -> day.sunset + 2
    TimeChoice.NIGHT -> day.sunset + 150
}

enum class Frequency(val visibleChance: Float, val cooldownSeconds: Int) {
    RARE(.18f, 25 * 60), OCCASIONAL(.38f, 8 * 60), OFTEN(.75f, 90)
}
enum class Quality(val renderScale: Float) { BATTERY(.5f), BALANCED(.7f), SHARP(1f) }
enum class MomentKind(val label: String) {
    BIRD("Passing bird"), FLOCK("Distant flock"), SQUIRREL("Red squirrel"), FIREFLIES("Fireflies"),
    COMET("Shooting star"), SUNBEAM("Sunbeam after rain"), RAINBOW("Rainbow"),
    DEER("Roe deer"), BUZZARD("Buzzard"), GEESE("Geese"), FOG_ROLL("Fog rolling in"), MOONRISE("Moonrise"),
    OWL("Owl"), BATS("Bats"), AURORA("Aurora"), CAR("Distant car"),
}

/** Lens drop tuning; each value multiplies the HTML preview's behaviour. */
enum class DropSize(val scale: Float) { SMALL(.7f), MEDIUM(1f), LARGE(1.4f) }
enum class SlideSpeed(val scale: Float) { SLOW(.55f), NORMAL(1f), FAST(1.7f) }
enum class Drying(val scale: Float) { QUICK(2.2f), NORMAL(1f), LINGER(.35f) }

/** Glass effects the settings screen can try on the home screen. */
enum class LensPreview { SHOWER, MIST }

data class Settings(
    val mode: Mode = Mode.LIVE,
    val useDeviceLocation: Boolean = true,
    val placeName: String = "Lisbon",
    val latitude: Double = 38.7223,
    val longitude: Double = -9.1393,
    /** 0 shows current weather; otherwise the forecast this many hours ahead. */
    val forecastHours: Int = 0,
    val weather: WeatherChoice = WeatherChoice.AUTO,
    val time: TimeChoice = TimeChoice.AUTO,
    val moments: Boolean = true,
    val frequency: Frequency = Frequency.OCCASIONAL,
    val reducedMotion: Boolean = false,
    val frameRate: Int = 30,
    /** Multiplier on how fast fog and clouds drift; 1 matches the preview. */
    val cloudSpeed: Float = 1f,
    val quality: Quality = Quality.BALANCED,
    val dimPercent: Int = 0,
    val pauseOnBatterySaver: Boolean = true,
    val parallax: Boolean = true,
    /** Colder light in winter, fresher greens in spring, warmer late summers. */
    val seasons: Boolean = true,
    /** Mist thins and sometimes a bird leaves a treetop when the phone is unlocked. */
    val unlockReveal: Boolean = true,
    /** Tapping empty home screen flushes birds from the trees. */
    val tapToWake: Boolean = true,
    /** Raindrops gather on the camera glass while it rains, slide down, and dry afterwards. */
    val lensDrops: Boolean = true,
    /** How much rain reaches the glass; 1 matches the preview. */
    val lensAmount: Float = 1f,
    val lensSize: DropSize = DropSize.MEDIUM,
    val lensSpeed: SlideSpeed = SlideSpeed.NORMAL,
    val lensDrying: Drying = Drying.NORMAL,
    /** Small impacts on the glass in heavy rain. */
    val lensSplashes: Boolean = true,
    /** Condensation at the edges of the glass on foggy mornings. */
    val lensMist: Boolean = true,
    /** Draws at 15 fps while nothing moves fast; slow drift looks the same. */
    val calmSaver: Boolean = true,
)

/** One SharedPreferences file shared by the settings screen and the wallpaper engine. */
object SettingsStore {
    private const val FILE = "weatherscape"
    const val PREVIEW_MOMENT = "preview_moment"
    const val PREVIEW_REQUESTED_AT = "preview_requested_at"
    const val WEATHER_JSON = "weather_json"
    const val WEATHER_FETCHED_AT = "weather_fetched_at"
    const val WEATHER_ERROR = "weather_error"
    const val REFRESH_REQUESTED_AT = "refresh_requested_at"
    const val LENS_PREVIEW_AT = "lens_preview_at"
    const val LENS_PREVIEW_KIND = "lens_preview_kind"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context): Settings {
        val p = prefs(context)
        val d = Settings()
        return Settings(
            mode = p.enum("mode", d.mode),
            useDeviceLocation = p.getBoolean("use_device_location", d.useDeviceLocation),
            placeName = p.getString("place_name", d.placeName) ?: d.placeName,
            latitude = p.getFloat("latitude", d.latitude.toFloat()).toDouble(),
            longitude = p.getFloat("longitude", d.longitude.toFloat()).toDouble(),
            forecastHours = p.getInt("forecast_hours", d.forecastHours),
            weather = p.enum("weather", d.weather),
            time = p.enum("time", d.time),
            moments = p.getBoolean("moments", d.moments),
            frequency = p.enum("frequency", d.frequency),
            reducedMotion = p.getBoolean("reduced_motion", d.reducedMotion),
            frameRate = p.getInt("frame_rate", d.frameRate),
            cloudSpeed = p.getFloat("cloud_speed", d.cloudSpeed),
            quality = p.enum("quality", d.quality),
            dimPercent = p.getInt("dim_percent", d.dimPercent),
            pauseOnBatterySaver = p.getBoolean("pause_on_battery_saver", d.pauseOnBatterySaver),
            parallax = p.getBoolean("parallax", d.parallax),
            seasons = p.getBoolean("seasons", d.seasons),
            unlockReveal = p.getBoolean("unlock_reveal", d.unlockReveal),
            tapToWake = p.getBoolean("tap_to_wake", d.tapToWake),
            lensDrops = p.getBoolean("lens_drops", d.lensDrops),
            lensAmount = p.getFloat("lens_amount", d.lensAmount),
            lensSize = p.enum("lens_size", d.lensSize),
            lensSpeed = p.enum("lens_speed", d.lensSpeed),
            lensDrying = p.enum("lens_drying", d.lensDrying),
            lensSplashes = p.getBoolean("lens_splashes", d.lensSplashes),
            lensMist = p.getBoolean("lens_mist", d.lensMist),
            calmSaver = p.getBoolean("calm_saver", d.calmSaver),
        )
    }

    fun write(context: Context, s: Settings) = prefs(context).edit {
        putString("mode", s.mode.name)
        putBoolean("use_device_location", s.useDeviceLocation)
        putString("place_name", s.placeName)
        putFloat("latitude", s.latitude.toFloat())
        putFloat("longitude", s.longitude.toFloat())
        putInt("forecast_hours", s.forecastHours)
        putString("weather", s.weather.name)
        putString("time", s.time.name)
        putBoolean("moments", s.moments)
        putString("frequency", s.frequency.name)
        putBoolean("reduced_motion", s.reducedMotion)
        putInt("frame_rate", s.frameRate)
        putFloat("cloud_speed", s.cloudSpeed)
        putString("quality", s.quality.name)
        putInt("dim_percent", s.dimPercent)
        putBoolean("pause_on_battery_saver", s.pauseOnBatterySaver)
        putBoolean("parallax", s.parallax)
        putBoolean("seasons", s.seasons)
        putBoolean("unlock_reveal", s.unlockReveal)
        putBoolean("tap_to_wake", s.tapToWake)
        putBoolean("lens_drops", s.lensDrops)
        putFloat("lens_amount", s.lensAmount)
        putString("lens_size", s.lensSize.name)
        putString("lens_speed", s.lensSpeed.name)
        putString("lens_drying", s.lensDrying.name)
        putBoolean("lens_splashes", s.lensSplashes)
        putBoolean("lens_mist", s.lensMist)
        putBoolean("calm_saver", s.calmSaver)
    }

    fun requestPreview(context: Context, kind: MomentKind) = prefs(context).edit {
        putString(PREVIEW_MOMENT, kind.name)
        putLong(PREVIEW_REQUESTED_AT, System.currentTimeMillis())
    }

    /** Asks the wallpaper to show a glass effect for a short while, to try the lens settings. */
    fun requestLensPreview(context: Context, kind: LensPreview) = prefs(context).edit {
        putString(LENS_PREVIEW_KIND, kind.name)
        putLong(LENS_PREVIEW_AT, System.currentTimeMillis())
    }

    /** Only the place keys, so a background location update can't overwrite other settings. */
    fun writePlace(context: Context, name: String, latitude: Double, longitude: Double) = prefs(context).edit {
        putString("place_name", name)
        putFloat("latitude", latitude.toFloat())
        putFloat("longitude", longitude.toFloat())
    }

    fun requestRefresh(context: Context) = prefs(context).edit {
        putLong(REFRESH_REQUESTED_AT, System.currentTimeMillis())
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, fallback: E): E =
        getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: fallback
}
