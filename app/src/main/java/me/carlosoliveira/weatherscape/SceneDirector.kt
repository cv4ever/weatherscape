package me.carlosoliveira.weatherscape

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Everything the renderer needs for one frame. Reused between frames to avoid allocation. */
class Frame {
    var phase = 0f
    /** Continuous time in 18-second units for fog and cloud drift; wraps every five hours. */
    var flow = 0f
    var gust = 1f
    var flash = 0f
    var sunbeam = 0f
    var rainbow = 0f
    /** Wind, valley mist, cloud cover, rainfall. */
    val air = FloatArray(4)
    val ground = FloatArray(3)
    val sky = FloatArray(3)
    val horizon = FloatArray(3)
    val mist = FloatArray(3)
    /** Star visibility, golden-hour amount, sun side (-1 morning, 1 afternoon). */
    val light = FloatArray(3)
    /** Sideways drift and gust speed that bend the rain. */
    var rainSway = 0f
    var rainGustSpeed = 0f
    var moment: MomentKind? = null
    var momentProgress = 0f
    var momentFlip = false
    var sunAltitude = 0.0
}

/**
 * Turns settings, weather and the clock into a [Frame]. Runs on the GL thread only;
 * [weather] and [settings] are swapped in from other threads as whole objects.
 */
class SceneDirector {
    @Volatile var settings = Settings()
    @Volatile var weather: WeatherReport? = null

    private val blended = FloatArray(4)
    private var hasBlend = false
    private var lastTime = 0.0
    private var flow = 0.0
    private var targetThunder = false
    private var nextFlashAt = 0.0
    private var flashStartedAt = -100.0

    private var momentKind: MomentKind? = null
    private var momentStart = 0.0
    private var momentFlip = false
    private var momentEnd = -1e9
    private var nextIdleRoll = 0.0

    private var previewKind: MomentKind? = null
    private var previewStart = 0.0

    private var solarKey = ""
    private var solar: SolarDay = SolarDay.of(38.72, -9.14, java.time.LocalDate.now())

    /** Called when the wallpaper becomes visible: the moment people are most likely to notice a surprise. */
    fun onShown(time: Double) {
        if (settings.mode != Mode.LIVE) return
        rollMoment(time, settings.frequency.visibleChance)
        nextIdleRoll = time + 45
    }

    fun preview(kind: MomentKind, time: Double) {
        previewKind = kind
        previewStart = time
        hasBlend = false
    }

    fun update(time: Double, nowMillis: Long, out: Frame) {
        val s = settings
        val dt = if (lastTime == 0.0) 0.0 else (time - lastTime).coerceIn(0.0, 1.0)
        lastTime = time
        val still = s.reducedMotion
        out.phase = if (still) .16f else ((time % 1800) / 18).toFloat()
        // Integrated rather than derived from the clock, so changing the speed never makes the sky jump.
        if (!still) flow = (flow + dt / 18 * s.cloudSpeed) % 1000
        out.flow = if (still) 3f else flow.toFloat()

        val preview = previewKind
        val previewAge = time - previewStart
        when {
            preview != null && previewAge < PREVIEW_LEAD + liveSeconds(preview) + 1 -> {
                staged(STAGE[preview]!!, out)
                val progress = if (still) .53 else (previewAge - PREVIEW_LEAD) / liveSeconds(preview)
                setMoment(out, preview.takeIf { progress in 0.0..1.0 }, progress.toFloat(), false)
                out.flash = 0f
            }
            s.mode == Mode.TIMELAPSE -> {
                if (preview != null) { previewKind = null; hasBlend = false }
                timelapse(time, out, still)
            }
            else -> {
                if (preview != null) { previewKind = null; hasBlend = false }
                live(time, dt, nowMillis, out, still)
            }
        }
        windAndRain(out)
        envelopes(out)
    }

    // ---------------------------------------------------------------- live

    private fun live(time: Double, dt: Double, nowMillis: Long, out: Frame, still: Boolean) {
        val s = settings
        val (day, nowMinute) = solarFor(s, Instant.ofEpochMilli(nowMillis))
        val minute = when (s.time) {
            TimeChoice.AUTO -> nowMinute
            TimeChoice.MORNING -> day.sunrise + 75
            TimeChoice.AFTERNOON -> minOf(day.noon + 150, day.sunset - 70)
            TimeChoice.SUNSET -> day.sunset + 2
            TimeChoice.NIGHT -> day.sunset + 150
        }
        ambient(day, minute, out)
        timelapseRainbowGate = false

        val report = weather
        val nowSeconds = nowMillis / 1000
        val sinceRain = report?.secondsSinceRain(nowSeconds)
        val target = when (s.weather) {
            WeatherChoice.SUNNY -> Atmosphere.SUNNY
            WeatherChoice.WINDY -> Atmosphere.WINDY
            WeatherChoice.RAINY -> Atmosphere.RAINY
            WeatherChoice.STORM -> Atmosphere.STORM
            WeatherChoice.FOGGY -> Atmosphere.FOGGY
            WeatherChoice.AUTO -> report?.let { Atmosphere.from(it.sample(nowSeconds, s.forecastHours), sinceRain) }
                ?: Atmosphere(.7f, .3f, .3f, 0f)
        }
        // Valleys hold mist around sunrise.
        val dawn = .45f * (1 - smooth(20f, 110f, abs(circular(minute - day.sunrise)).toFloat()))
        val goal = floatArrayOf(target.wind, target.mist + dawn, target.cover, target.rain)
        // Weather changes arrive as slow blends, never jumps.
        val k = if (hasBlend) (1 - exp(-dt / 40)).toFloat() else 1f
        for (i in 0..3) blended[i] = mix(blended[i], goal[i], k)
        hasBlend = true
        blended.copyInto(out.air)
        targetThunder = target.thunder

        out.flash = if (still || !targetThunder) 0f else lightning(time)
        val m = momentKind
        if (m != null && time - momentStart > liveSeconds(m)) { momentKind = null; momentEnd = time }
        if (!still && s.moments && momentKind == null && time >= nextIdleRoll) {
            rollMoment(time, s.frequency.visibleChance * .3f)
            nextIdleRoll = time + 45
        }
        val active = momentKind?.takeIf { s.moments && !still && time >= momentStart }
        setMoment(out, active, if (active == null) 0f else ((time - momentStart) / liveSeconds(active)).toFloat(), momentFlip)
        lastSinceRain = sinceRain
    }

    private var lastSinceRain: Long? = null

    private fun lightning(time: Double): Float {
        if (nextFlashAt == 0.0 || nextFlashAt < time - 60) nextFlashAt = time + Random.nextDouble(3.0, 10.0)
        if (time >= nextFlashAt) { flashStartedAt = time; nextFlashAt = time + Random.nextDouble(7.0, 25.0) }
        val age = (time - flashStartedAt).toFloat()
        if (age > 1.2f) return 0f
        val strike = (age - .15f) / .07f
        val after = (age - .55f) / .05f
        return .8f * exp(-strike * strike) + .54f * exp(-after * after)
    }

    private fun rollMoment(time: Double, chance: Float) {
        val s = settings
        if (!s.moments || s.reducedMotion || momentKind != null) return
        if (time - momentEnd < s.frequency.cooldownSeconds) return
        if (Random.nextFloat() > chance) return
        val month = Instant.now().atZone(ZoneId.systemDefault()).monthValue
        val alt = lastAltitude
        val (wind, mist, cover, rain) = blended
        val since = lastSinceRain
        val eligible = buildList {
            if (alt > 2 && rain < .02f && wind < 2.4f) add(MomentKind.BIRD to 3)
            if (alt > -4 && rain < .02f && wind < 2.2f) add(MomentKind.FLOCK to 2)
            if (alt > 4 && rain < .02f && wind < 1.6f) add(MomentKind.SQUIRREL to 2)
            if (alt in -14.0..-2.0 && wind < 1.1f && rain < .01f && month in 5..9) add(MomentKind.FIREFLIES to 4)
            if (alt < -14 && cover < .3f && rain < .01f) add(MomentKind.COMET to 3)
            if (alt in 3.0..35.0 && rain < .02f && cover in .3f..0.92f && ((since != null && since < 7200) || mist > .55f)) {
                add(MomentKind.SUNBEAM to 6)
            }
            if (alt in 2.0..40.0 && rain in .04f..0.55f && cover in .15f..0.85f) add(MomentKind.RAINBOW to 8)
        }
        if (eligible.isEmpty()) return
        var pick = Random.nextInt(eligible.sumOf { it.second })
        val kind = eligible.first { (_, weight) -> (pick - weight).also { pick = it } < 0 }.first
        momentKind = kind
        momentStart = time + Random.nextDouble(1.2, 3.5)
        momentFlip = Random.nextBoolean()
    }

    // ---------------------------------------------------------------- timelapse

    private fun timelapse(time: Double, out: Frame, still: Boolean) {
        val s = settings
        val (day, _) = solarFor(s, Instant.now())
        val loop = floor(time / LOOP_SECONDS).toLong()
        val elapsed = minOf(time % LOOP_SECONDS, TIMELAPSE_SECONDS)
        val start = day.sunset - 260
        val end = day.sunset + 155
        val minute = start + (end - start) * elapsed / TIMELAPSE_SECONDS
        if (!still) out.phase = (elapsed / 18).toFloat()
        ambient(day, minute, out)
        val relative = (minute - day.sunset).toFloat()
        fronts(relative, out.air)
        val strike = (relative + 118) / 3.4f
        val after = (relative + 103) / 2.5f
        out.flash = if (still) 0f else .8f * exp(-strike * strike) + .54f * exp(-after * after)

        val schedule = timelapseSchedule(loop, day)
        var shown: Triple<MomentKind, Float, Boolean>? = null
        if (!still && s.moments) {
            for ((kind, at, flip) in schedule) {
                val age = elapsed - at
                val length = TIMELAPSE_LENGTH.getValue(kind)
                if (age in 0.0..length && fitsTimelapse(kind, out)) { shown = Triple(kind, (age / length).toFloat(), flip); break }
            }
        }
        setMoment(out, shown?.first, shown?.second ?: 0f, shown?.third ?: false)
        timelapseRainbowGate = true
    }

    private var timelapseRainbowGate = false
    private var scheduleLoop = -1L
    private var schedule: List<Triple<MomentKind, Double, Boolean>> = emptyList()

    /** Same recipe as the HTML preview: one daytime visitor, one clearing moment, one night moment. */
    private fun timelapseSchedule(loop: Long, day: SolarDay): List<Triple<MomentKind, Double, Boolean>> {
        if (loop == scheduleLoop) return schedule
        scheduleLoop = loop
        val r = Random(loop * 7919 + 17)
        fun at(offset: Double) = (offset + 260) * TIMELAPSE_SECONDS / 415
        val list = mutableListOf<Triple<MomentKind, Double, Boolean>>()
        val early = listOf(MomentKind.BIRD, MomentKind.FLOCK, MomentKind.SQUIRREL).random(r)
        list += Triple(early, 1 + r.nextDouble(), r.nextBoolean())
        val clearing = r.nextDouble()
        if (clearing < .40) list += Triple(MomentKind.RAINBOW, at(-63.0) + r.nextDouble() * .25, false)
        else if (clearing < .88) {
            val kind = if (early == MomentKind.FLOCK) MomentKind.SUNBEAM else listOf(MomentKind.FLOCK, MomentKind.SUNBEAM).random(r)
            list += Triple(kind, at(-42.0) + r.nextDouble(), r.nextBoolean())
        }
        val late = r.nextDouble()
        if (late < .67) {
            val kind = if (late < .51) MomentKind.FIREFLIES else MomentKind.COMET
            val start = if (kind == MomentKind.FIREFLIES) minOf(TIMELAPSE_SECONDS - 8.4, at(75.0)) else TIMELAPSE_SECONDS - 5.4
            list += Triple(kind, start + r.nextDouble() * .8, false)
        }
        schedule = list
        return list
    }

    private fun fitsTimelapse(kind: MomentKind, f: Frame): Boolean {
        val (wind, mist, cover, rain) = f.air
        if (kind == MomentKind.RAINBOW) {
            return f.sunAltitude > 1 && f.sunAltitude < 23 && rain > .014f && rain < .205f && cover > .26f && cover < .625f && mist > .75f
        }
        if (rain > .012f) return false
        return when (kind) {
            MomentKind.COMET -> f.light[0] > .55f && cover < .16f
            MomentKind.FIREFLIES -> f.light[0] > .23f && wind < 1.1f
            MomentKind.SUNBEAM -> f.light[0] < .10f && mist > .6f && cover > .045f
            else -> wind < 2.38f && f.light[0] < .32f
        }
    }

    // ---------------------------------------------------------------- shared

    /** Holds a fixed point of the timelapse so a preview always has suitable light and weather. */
    private fun staged(offsetFromSunset: Double, out: Frame) {
        val (day, _) = solarFor(settings, Instant.now())
        val minute = day.sunset + offsetFromSunset
        ambient(day, minute, out)
        fronts(offsetFromSunset.toFloat(), out.air)
        timelapseRainbowGate = false
    }

    private var lastAltitude = 0.0

    private fun ambient(day: SolarDay, minute: Double, out: Frame) {
        val altitudeDeg = day.altitude(minute)
        lastAltitude = altitudeDeg
        out.sunAltitude = altitudeDeg
        val altitude = altitudeDeg.toFloat()
        val bright = smooth(-6f, 12f, altitude)
        val twilight = smooth(-18f, -5f, altitude)
        val gold = smooth(-10f, -1.3f, altitude) * (1 - smooth(1f, 13f, altitude))
        out.ground[0] = mix(.45f, 1.15f, bright) + .22f * gold
        out.ground[1] = mix(.56f, 1.13f, bright) + .07f * gold
        out.ground[2] = mix(.76f, 1.035f, bright) - .10f * gold
        out.sky[0] = mix(.052f, .405f, bright) + .153f * gold
        out.sky[1] = mix(.086f, .648f, bright) + .066f * gold
        out.sky[2] = mix(.17f, .865f, bright) + .18f * twilight * (1 - bright)
        out.horizon[0] = mix(.21f, .72f, bright) + .40f * gold
        out.horizon[1] = mix(.31f, .83f, bright) + .24f * gold
        out.horizon[2] = mix(.48f, .91f, bright) - .10f * gold
        out.mist[0] = mix(.33f, .71f, bright) + .32f * gold
        out.mist[1] = mix(.42f, .82f, bright) + .10f * gold
        out.mist[2] = mix(.58f, .88f, bright) - .06f * gold
        out.light[0] = 1 - smooth(-17f, -6f, altitude)
        out.light[1] = gold
        out.light[2] = if (circular(minute - day.noon) >= 0) 1f else -1f
    }

    private fun windAndRain(out: Frame) {
        val slow = TAU * (2 * out.phase + .11f)
        val fast = TAU * (5 * out.phase - .19f)
        val c1 = cos(slow)
        val c2 = cos(fast)
        out.gust = .88f + .24f * c1 + .12f * c2
        out.rainSway = .23f * sin(slow) + .055f * sin(fast)
        out.rainGustSpeed = TAU * (.46f * c1 + .275f * c2)
    }

    private fun envelopes(out: Frame) {
        val t = out.momentProgress
        out.sunbeam = if (out.moment == MomentKind.SUNBEAM) smooth(.08f, .42f, t) * (1 - smooth(.63f, .98f, t)) else 0f
        out.rainbow = if (out.moment == MomentKind.RAINBOW) {
            val envelope = smooth(.04f, .36f, t) * (1 - smooth(.65f, 1f, t))
            if (timelapseRainbowGate) {
                // In the timelapse the bow fades with the drizzle and the closing cloud gap.
                val rain = out.air[3]
                envelope * smooth(.014f, .041f, rain) * (1 - smooth(.178f, .205f, rain)) * (1 - smooth(.53f, .625f, out.air[2]))
            } else envelope
        } else 0f
    }

    private fun setMoment(out: Frame, kind: MomentKind?, progress: Float, flip: Boolean) {
        out.moment = kind
        out.momentProgress = progress.coerceIn(0f, 1f)
        out.momentFlip = flip
    }

    private fun solarFor(s: Settings, instant: Instant): Pair<SolarDay, Double> {
        val (fresh, minute) = SolarDay.at(s.latitude, s.longitude, instant)
        val key = "${s.latitude},${s.longitude},${instant.epochSecond / 86400}"
        if (key != solarKey) { solarKey = key; solar = fresh }
        return solar to minute
    }

    private fun liveSeconds(kind: MomentKind) = LIVE_LENGTH.getValue(kind)

    companion object {
        const val TIMELAPSE_SECONDS = 48.0
        private const val LOOP_SECONDS = 52.0
        private const val PREVIEW_LEAD = 1.0

        /** Minutes relative to sunset: wind, valley mist, cloud cover, rainfall. */
        private val FRONTS = arrayOf(
            floatArrayOf(-260f, .65f, .15f, .03f, 0f), floatArrayOf(-234f, .65f, .13f, .03f, 0f),
            floatArrayOf(-209f, 1.8f, .17f, .12f, 0f), floatArrayOf(-181f, 2.32f, .25f, .58f, 0f),
            floatArrayOf(-159f, 2.50f, .42f, .79f, .40f), floatArrayOf(-113f, 2.85f, .70f, 1f, 1f),
            floatArrayOf(-94f, 2.24f, .80f, .91f, .64f), floatArrayOf(-64f, 1.02f, 1.22f, .61f, .19f),
            floatArrayOf(-42f, .77f, 1.23f, .31f, 0f), floatArrayOf(-12f, .63f, .97f, .10f, 0f),
            floatArrayOf(31f, .60f, .55f, .045f, 0f), floatArrayOf(86f, .56f, .30f, .02f, 0f),
            floatArrayOf(155f, .51f, .19f, .015f, 0f),
        )

        fun fronts(relative: Float, air: FloatArray) {
            var a = FRONTS[0]
            var b = a
            for (i in 1 until FRONTS.size) {
                b = FRONTS[i]
                if (relative <= b[0]) break
                a = b
            }
            val t = if (a === b) 0f else smooth(a[0], b[0], relative)
            for (i in 0..3) air[i] = mix(a[i + 1], b[i + 1], t)
        }

        /** Where in the storm sequence each preview is staged, in minutes from sunset. */
        private val STAGE = mapOf(
            MomentKind.BIRD to -243.0, MomentKind.FLOCK to -36.0, MomentKind.SQUIRREL to -243.0,
            MomentKind.FIREFLIES to 82.0, MomentKind.COMET to 155.0, MomentKind.SUNBEAM to -34.0,
            MomentKind.RAINBOW to -52.0,
        )

        /** Real-time lengths: slow light events linger, animals pass at their own pace. */
        private val LIVE_LENGTH = mapOf(
            MomentKind.BIRD to 4.6, MomentKind.FLOCK to 7.5, MomentKind.SQUIRREL to 5.8,
            MomentKind.FIREFLIES to 12.0, MomentKind.COMET to 4.4, MomentKind.SUNBEAM to 16.0,
            MomentKind.RAINBOW to 20.0,
        )

        /** Lengths inside the compressed 48-second timelapse, matching the HTML preview. */
        private val TIMELAPSE_LENGTH = mapOf(
            MomentKind.BIRD to 4.6, MomentKind.FLOCK to 7.5, MomentKind.SQUIRREL to 5.8,
            MomentKind.FIREFLIES to 7.4, MomentKind.COMET to 4.4, MomentKind.SUNBEAM to 6.1,
            MomentKind.RAINBOW to 2.45,
        )

        /** Wraps a minute difference into -720..720 so day boundaries don't flip the sun's side. */
        fun circular(minutes: Double): Double {
            var m = minutes % 1440
            if (m > 720) m -= 1440
            if (m < -720) m += 1440
            return m
        }
    }
}
