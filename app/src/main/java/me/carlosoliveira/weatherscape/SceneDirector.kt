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
    /** Moon centre x, y in plate UV, radius in plate widths, visibility. */
    val moon = FloatArray(4)
    val moonLight = floatArrayOf(0f, 0f, 1f)
    var moonGlow = 0f
    var aurora = 0f
    /** Fog bank advance 0..1 and strength. */
    val fogRoll = FloatArray(2)
    /** Falling snow and lying snow, 0..1. */
    var snow = 0f
    var snowCover = 0f
    /** Land colour multiplier and saturation for the season. */
    val season = floatArrayOf(1f, 1f, 1f, 1f)
    /** Mist thinning after an unlock, 0..1. */
    var reveal = 0f
    /** Birds flushed from a treetop: origin in plate UV, progress 0..1 (negative when idle), bird count. */
    var flushX = 0f
    var flushY = 0f
    var flushProgress = -1f
    var flushCount = 0
    var flushSeed = 0
}

/**
 * Turns settings, weather and the clock into a [Frame]. Runs on the GL thread only;
 * [weather] and [settings] are swapped in from other threads as whole objects.
 */
class SceneDirector {
    @Volatile var settings = Settings()
    @Volatile var weather: WeatherReport? = null

    /** Wind, mist, cover, rain, falling snow, lying snow, blended towards the weather. */
    private val blended = FloatArray(6)
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

    private var unlockAt = -100.0
    private var flushStart = -100.0
    private var flushX = 0f
    private var flushY = 0f
    private var flushCount = 0
    private var flushSeed = 0

    private var solarKey = ""
    private var solar: SolarDay = SolarDay.of(38.72, -9.14, java.time.LocalDate.now())
    private var moonAt = 0L
    private var moonState: Moon.State? = null
    private var seasonDay = -1L

    /** Called when the wallpaper becomes visible: the moment people are most likely to notice a surprise. */
    fun onShown(time: Double) {
        if (settings.mode != Mode.LIVE) return
        rollMoment(time, settings.frequency.visibleChance)
        nextIdleRoll = time + 45
    }

    /** The phone was just unlocked: the mist thins for a few seconds, and sometimes a bird leaves a treetop. */
    fun onUnlock(time: Double) {
        val s = settings
        if (!s.unlockReveal || s.reducedMotion) return
        unlockAt = time
        val (wind, _, _, rain) = blended
        if (lastAltitude > 2 && rain < .05f && blended[4] < .1f && wind < 2.4f && Random.nextFloat() < .5f) {
            val tip = TREE_TIPS.random()
            startFlush(time, tip.first, tip.second, 1)
        }
    }

    /** A tap on the empty home screen at plate coordinates [x], [y]: birds burst from the nearest treetop. */
    fun onTap(time: Double, x: Float, y: Float) {
        val s = settings
        if (!s.tapToWake || s.reducedMotion || time - flushStart < 4.5) return
        if (lastAltitude < -4 || blended[3] > .3f) return
        val tip = TREE_TIPS.minBy { abs(it.first - x) + .3f * abs(it.second - y) }
        startFlush(time, tip.first, tip.second, Random.nextInt(4, 7))
    }

    private fun startFlush(time: Double, x: Float, y: Float, count: Int) {
        flushStart = time
        flushX = x
        flushY = y
        flushCount = count
        flushSeed = Random.nextInt()
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
        out.moon[3] = 0f
        out.moonGlow = 0f
        out.snow = 0f
        out.snowCover = 0f

        val preview = previewKind
        val previewAge = time - previewStart
        when {
            preview != null && previewAge < PREVIEW_LEAD + liveSeconds(preview) + 1 -> {
                staged(preview, out)
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
        season(nowMillis, out)
        windAndRain(out)
        envelopes(out)
        val unlockAge = (time - unlockAt).toFloat()
        out.reveal = if (unlockAge in 0f..6f) smooth(0f, .8f, unlockAge) * (1 - smooth(2.5f, 6f, unlockAge)) else 0f
        val flushAge = ((time - flushStart) / FLUSH_SECONDS).toFloat()
        out.flushProgress = if (flushAge in 0f..1f) flushAge else -1f
        out.flushX = flushX
        out.flushY = flushY
        out.flushCount = flushCount
        out.flushSeed = flushSeed
    }

    // ---------------------------------------------------------------- live

    private fun live(time: Double, dt: Double, nowMillis: Long, out: Frame, still: Boolean) {
        val s = settings
        val (day, nowMinute) = solarFor(s, Instant.ofEpochMilli(nowMillis))
        val minute = s.time.minuteOn(day, nowMinute)
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
            WeatherChoice.SNOWY -> Atmosphere.SNOWY
            WeatherChoice.AUTO -> report?.let { Atmosphere.from(it.sample(nowSeconds, s.forecastHours), sinceRain) }
                ?: Atmosphere(.7f, .3f, .3f, 0f)
        }
        // Valleys hold mist around sunrise.
        val dawn = .45f * (1 - smooth(20f, 110f, abs(circular(minute - day.sunrise)).toFloat()))
        goal[0] = target.wind; goal[1] = target.mist + dawn; goal[2] = target.cover; goal[3] = target.rain
        goal[4] = target.snow; goal[5] = target.snowCover
        // Weather changes arrive as slow blends, never jumps.
        val k = if (hasBlend) (1 - exp(-dt / 40)).toFloat() else 1f
        for (i in blended.indices) blended[i] = mix(blended[i], goal[i], k)
        hasBlend = true
        blended.copyInto(out.air, 0, 0, 4)
        out.snow = blended[4]
        out.snowCover = blended[5]
        targetThunder = target.thunder

        realMoon(nowMillis + ((minute - nowMinute) * 60_000).toLong(), out)
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

    private val goal = FloatArray(6)
    private var lastSinceRain: Long? = null

    /** Places the real moon: the view faces south, about 64 degrees across and 96 degrees tall. */
    private fun realMoon(millis: Long, out: Frame) {
        val s = settings
        if (moonState == null || abs(millis - moonAt) > 20_000) {
            moonState = Moon.at(millis, s.latitude, s.longitude)
            moonAt = millis
        }
        val m = moonState ?: return
        val x = (.5 + m.azimuth / 64).toFloat()
        val y = (.575 - m.altitude / 96).toFloat()
        val onScreen = smooth(-.08f, -.02f, x) * (1 - smooth(1.02f, 1.08f, x))
        val above = smooth(-2f, 3f, m.altitude.toFloat())
        out.moon[0] = x; out.moon[1] = y; out.moon[2] = .018f
        // By day the moon is pale; it only reads properly once the sky darkens.
        out.moon[3] = onScreen * above * (.3f + .7f * out.light[0])
        setMoonLight(m, out)
        out.moonGlow = (m.fraction * smooth(0f, 10f, m.altitude.toFloat()) * out.light[0] * (1 - .7f * out.air[2])).toFloat()
        lastMoonFraction = m.fraction
    }

    private var lastMoonFraction = .5

    private fun setMoonLight(m: Moon.State, out: Frame) {
        val side = if (m.waxing) 1f else -1f
        out.moonLight[0] = (sin(m.elongation) * side).toFloat()
        out.moonLight[1] = 0f
        out.moonLight[2] = (-cos(m.elongation)).toFloat()
    }

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
        val wind = blended[0]; val mist = blended[1]; val cover = blended[2]; val rain = blended[3]; val snow = blended[4]
        val since = lastSinceRain
        val calm = rain < .02f && snow < .3f
        val morning = lastLightSide < 0
        val eligible = buildList {
            if (alt > 2 && calm && wind < 2.4f) add(MomentKind.BIRD to 3)
            if (alt > -4 && calm && wind < 2.2f) add(MomentKind.FLOCK to 2)
            if (alt > 4 && calm && wind < 1.6f) add(MomentKind.SQUIRREL to 2)
            if (alt in -14.0..-2.0 && wind < 1.1f && rain < .01f && snow < .01f && month in 5..9) add(MomentKind.FIREFLIES to 4)
            if (alt < -14 && cover < .3f && rain < .01f) add(MomentKind.COMET to 3)
            if (alt in 3.0..35.0 && rain < .02f && cover in .3f..0.92f && ((since != null && since < 7200) || mist > .55f)) {
                add(MomentKind.SUNBEAM to 6)
            }
            if (alt in 2.0..40.0 && rain in .04f..0.55f && cover in .15f..0.85f) add(MomentKind.RAINBOW to 8)
            if (alt in -8.0..8.0 && calm && wind < 1.4f) add(MomentKind.DEER to 4)
            if (alt > 15 && rain < .01f && cover < .6f && wind < 2.2f) add(MomentKind.BUZZARD to 3)
            if (alt > -2 && calm && month in listOf(3, 4, 9, 10, 11)) add(MomentKind.GEESE to 3)
            if (alt in -3.0..12.0 && morning && wind < 1.2f && rain < .01f && mist > .4f) add(MomentKind.FOG_ROLL to 5)
            if (alt < -8 && wind < 1.6f && rain < .01f) add(MomentKind.OWL to 3)
            if (alt in -12.0..-1.0 && wind < 1.2f && rain < .01f && snow < .01f && month in 4..10) add(MomentKind.BATS to 3)
            // Auroras never reach Portugal; a rare one is a deliberate liberty.
            if (alt < -14 && cover < .2f && rain < .01f && Random.nextFloat() < .2f) add(MomentKind.AURORA to 2)
            if (alt < -6 && rain < .4f) add(MomentKind.CAR to 3)
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

        val schedule = timelapseSchedule(loop)
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

    /** Same recipe as the HTML preview: a daytime visitor, a clearing moment, a dusk visitor, a night moment. */
    private fun timelapseSchedule(loop: Long): List<Triple<MomentKind, Double, Boolean>> {
        if (loop == scheduleLoop) return schedule
        scheduleLoop = loop
        val r = Random(loop * 7919 + 17)
        fun at(offset: Double) = (offset + 260) * TIMELAPSE_SECONDS / 415
        val list = mutableListOf<Triple<MomentKind, Double, Boolean>>()
        val early = listOf(MomentKind.BIRD, MomentKind.FLOCK, MomentKind.SQUIRREL, MomentKind.BUZZARD, MomentKind.GEESE).random(r)
        list += Triple(early, 1 + r.nextDouble(), r.nextBoolean())
        val clearing = r.nextDouble()
        if (clearing < .40) list += Triple(MomentKind.RAINBOW, at(-63.0) + r.nextDouble() * .25, false)
        else if (clearing < .88) {
            val kind = if (early == MomentKind.FLOCK) MomentKind.SUNBEAM else listOf(MomentKind.FLOCK, MomentKind.SUNBEAM).random(r)
            list += Triple(kind, at(-42.0) + r.nextDouble(), r.nextBoolean())
        }
        if (r.nextDouble() < .55) list += Triple(listOf(MomentKind.DEER, MomentKind.BATS).random(r), at(3.0) + r.nextDouble() * .6, r.nextBoolean())
        val late = r.nextDouble()
        if (late < .8) {
            val kind = when {
                late < .2 -> MomentKind.FIREFLIES
                late < .32 -> MomentKind.COMET
                late < .44 -> MomentKind.OWL
                late < .56 -> MomentKind.CAR
                late < .74 -> MomentKind.MOONRISE
                else -> MomentKind.AURORA
            }
            val length = TIMELAPSE_LENGTH.getValue(kind)
            val start = if (kind == MomentKind.FIREFLIES) minOf(TIMELAPSE_SECONDS - length - 1, at(75.0)) else TIMELAPSE_SECONDS - length - .6
            list += Triple(kind, start + r.nextDouble() * .5, r.nextBoolean())
        }
        schedule = list
        return list
    }

    private fun fitsTimelapse(kind: MomentKind, f: Frame): Boolean {
        val (wind, mist, cover, rain) = f.air
        val alt = f.sunAltitude
        return when (kind) {
            MomentKind.RAINBOW -> alt > 1 && alt < 23 && rain > .014f && rain < .205f && cover > .26f && cover < .625f && mist > .75f
            MomentKind.MOONRISE -> f.light[0] > .5f && cover < .35f && rain < .01f && lastMoonFraction > .05
            MomentKind.AURORA -> f.light[0] > .8f && cover < .2f && rain < .01f
            MomentKind.CAR -> f.light[0] > .6f && rain < .3f
            MomentKind.OWL -> f.light[0] > .5f && wind < 1.6f && rain < .01f
            MomentKind.BATS -> alt > -12 && alt < -1 && wind < 1.2f && rain < .01f
            MomentKind.DEER -> alt > -8 && alt < 8 && wind < 1.3f && rain < .012f
            MomentKind.BUZZARD -> f.light[0] < .1f && alt > 12 && cover < .6f && rain < .01f
            MomentKind.GEESE -> f.light[0] < .3f && wind < 2.4f && rain < .012f
            MomentKind.FOG_ROLL -> alt > -3 && alt < 12 && wind < 1.2f && rain < .01f
            MomentKind.COMET -> rain <= .012f && f.light[0] > .55f && cover < .16f
            MomentKind.FIREFLIES -> rain <= .012f && f.light[0] > .23f && wind < 1.1f
            MomentKind.SUNBEAM -> rain <= .012f && f.light[0] < .10f && mist > .6f && cover > .045f
            else -> rain <= .012f && wind < 2.38f && f.light[0] < .32f
        }
    }

    // ---------------------------------------------------------------- shared

    /** Holds a fixed point of the storm sequence so a preview always has suitable light and weather. */
    private fun staged(kind: MomentKind, out: Frame) {
        val (day, _) = solarFor(settings, Instant.now())
        val minute = if (kind == MomentKind.FOG_ROLL) day.sunrise + 25 else day.sunset + STAGE.getValue(kind)
        ambient(day, minute, out)
        fronts((minute - day.sunset).toFloat(), out.air)
        if (kind == MomentKind.FOG_ROLL) out.air[1] += .75f * (1 - smooth(20f, 110f, abs(minute - day.sunrise).toFloat()))
        timelapseRainbowGate = false
        if (kind == MomentKind.MOONRISE) {
            moonState = Moon.at(System.currentTimeMillis(), settings.latitude, settings.longitude)
            lastMoonFraction = moonState!!.fraction
        }
    }

    private var lastAltitude = 0.0
    private var lastLightSide = 1f

    private fun ambient(day: SolarDay, minute: Double, out: Frame) {
        val altitudeDeg = day.altitude(minute)
        lastAltitude = altitudeDeg
        out.sunAltitude = altitudeDeg
        val altitude = altitudeDeg.toFloat()
        val bright = smooth(-6f, 12f, altitude)
        val twilight = smooth(-18f, -5f, altitude)
        val gold = smooth(-10f, -1.3f, altitude) * (1 - smooth(1f, 13f, altitude))
        // 0 in the blue hour, 1 once the sky is fully dark: near-black sky, the land a faint silhouette.
        val deep = 1 - smooth(-16f, -8f, altitude)
        out.ground[0] = mix(mix(.45f, .10f, deep), 1.15f, bright) + .22f * gold
        out.ground[1] = mix(mix(.56f, .12f, deep), 1.13f, bright) + .07f * gold
        out.ground[2] = mix(mix(.76f, .17f, deep), 1.035f, bright) - .10f * gold
        out.sky[0] = mix(mix(.052f, .004f, deep), .405f, bright) + .153f * gold
        out.sky[1] = mix(mix(.086f, .006f, deep), .648f, bright) + .066f * gold
        out.sky[2] = mix(mix(.17f, .016f, deep), .865f, bright) + .18f * twilight * (1 - bright)
        // A faint glow stays low over the ridges, so the treeline still reads against the sky.
        out.horizon[0] = mix(mix(.21f, .03f, deep), .72f, bright) + .40f * gold
        out.horizon[1] = mix(mix(.31f, .037f, deep), .83f, bright) + .24f * gold
        out.horizon[2] = mix(mix(.48f, .06f, deep), .91f, bright) - .10f * gold
        out.mist[0] = mix(mix(.33f, .065f, deep), .71f, bright) + .32f * gold
        out.mist[1] = mix(mix(.42f, .075f, deep), .82f, bright) + .10f * gold
        out.mist[2] = mix(mix(.58f, .10f, deep), .88f, bright) - .06f * gold
        out.light[0] = 1 - smooth(-17f, -6f, altitude)
        out.light[1] = gold
        out.light[2] = if (circular(minute - day.noon) >= 0) 1f else -1f
        lastLightSide = out.light[2]
    }

    /** Land colour by date: colder and greyer in winter, fresher in spring, warmer in late summer and autumn. */
    private fun season(nowMillis: Long, out: Frame) {
        val s = settings
        if (!s.seasons) { out.season.fill(1f); seasonDay = -1; return }
        val dayNumber = nowMillis / 86_400_000L
        if (dayNumber == seasonDay) return
        seasonDay = dayNumber
        var day = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).dayOfYear.toDouble()
        if (s.latitude < 0) day = (day + 182.5) % 365
        for (i in SEASONS.indices) {
            val a = SEASONS[i]
            val b = SEASONS[(i + 1) % SEASONS.size]
            val span = (b[0] - a[0] + 365) % 365
            val into = (day - a[0] + 365) % 365
            if (into < span) {
                val t = smooth(0f, 1f, (into / span).toFloat())
                for (k in 0..3) out.season[k] = mix(a[k + 1].toFloat(), b[k + 1].toFloat(), t)
                return
            }
        }
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
        val kind = out.moment
        out.sunbeam = if (kind == MomentKind.SUNBEAM) smooth(.08f, .42f, t) * (1 - smooth(.63f, .98f, t)) else 0f
        out.rainbow = if (kind == MomentKind.RAINBOW) {
            val envelope = smooth(.04f, .36f, t) * (1 - smooth(.65f, 1f, t))
            if (timelapseRainbowGate) {
                // In the timelapse the bow fades with the drizzle and the closing cloud gap.
                val rain = out.air[3]
                envelope * smooth(.014f, .041f, rain) * (1 - smooth(.178f, .205f, rain)) * (1 - smooth(.53f, .625f, out.air[2]))
            } else envelope
        } else 0f
        out.aurora = if (kind == MomentKind.AURORA) smooth(.04f, .3f, t) * (1 - smooth(.72f, 1f, t)) else 0f
        if (kind == MomentKind.FOG_ROLL) {
            out.fogRoll[0] = t
            out.fogRoll[1] = smooth(0f, .15f, t) * (1 - smooth(.85f, 1f, t))
        } else { out.fogRoll[0] = 0f; out.fogRoll[1] = 0f }
        if (kind == MomentKind.MOONRISE) {
            // The staged rise: the moon clears the left-hand ridge in today's real phase.
            val rise = smooth(0f, 1f, t)
            out.moon[0] = .205f + .03f * rise
            out.moon[1] = .605f - .135f * rise
            out.moon[2] = .018f
            out.moon[3] = smooth(0f, .05f, t) * (1 - smooth(.92f, 1f, t))
            moonState?.let { setMoonLight(it, out) }
            out.moonGlow = lastMoonFraction.toFloat()
        }
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
        const val FLUSH_SECONDS = 4.0
        private const val LOOP_SECONDS = 52.0
        private const val PREVIEW_LEAD = 1.0

        /** Foreground treetops the birds can leave from, plate UV. */
        private val TREE_TIPS = listOf(.058f to .608f, .19f to .71f, .474f to .71f, .798f to .696f, .927f to .701f)

        /** Day of year, then land colour multiplier and saturation: mid-winter, spring, summer, autumn. */
        private val SEASONS = arrayOf(
            doubleArrayOf(15.0, .95, .98, 1.05, .82),
            doubleArrayOf(110.0, .99, 1.04, .97, 1.08),
            doubleArrayOf(200.0, 1.04, 1.02, .95, 1.03),
            doubleArrayOf(293.0, 1.05, 1.0, .92, .94),
        )

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

        /** Where in the storm sequence each preview is staged, in minutes from sunset (fog rolls in at dawn). */
        private val STAGE = mapOf(
            MomentKind.BIRD to -243.0, MomentKind.FLOCK to -36.0, MomentKind.SQUIRREL to -243.0,
            MomentKind.FIREFLIES to 82.0, MomentKind.COMET to 155.0, MomentKind.SUNBEAM to -34.0,
            MomentKind.RAINBOW to -52.0, MomentKind.DEER to 4.0, MomentKind.BUZZARD to -243.0,
            MomentKind.GEESE to -243.0, MomentKind.FOG_ROLL to 0.0, MomentKind.MOONRISE to 155.0,
            MomentKind.OWL to 155.0, MomentKind.BATS to 40.0, MomentKind.AURORA to 155.0, MomentKind.CAR to 155.0,
        )

        /** Real-time lengths: slow light events linger, animals pass at their own pace. */
        private val LIVE_LENGTH = mapOf(
            MomentKind.BIRD to 4.6, MomentKind.FLOCK to 7.5, MomentKind.SQUIRREL to 5.8,
            MomentKind.FIREFLIES to 12.0, MomentKind.COMET to 4.4, MomentKind.SUNBEAM to 16.0,
            MomentKind.RAINBOW to 20.0, MomentKind.DEER to 16.0, MomentKind.BUZZARD to 22.0,
            MomentKind.GEESE to 14.0, MomentKind.FOG_ROLL to 30.0, MomentKind.MOONRISE to 14.0,
            MomentKind.OWL to 6.5, MomentKind.BATS to 10.0, MomentKind.AURORA to 40.0, MomentKind.CAR to 14.0,
        )

        /** Lengths inside the compressed 48-second timelapse, matching the HTML preview. */
        private val TIMELAPSE_LENGTH = mapOf(
            MomentKind.BIRD to 4.6, MomentKind.FLOCK to 7.5, MomentKind.SQUIRREL to 5.8,
            MomentKind.FIREFLIES to 7.4, MomentKind.COMET to 4.4, MomentKind.SUNBEAM to 6.1,
            MomentKind.RAINBOW to 2.45, MomentKind.DEER to 11.0, MomentKind.BUZZARD to 15.0,
            MomentKind.GEESE to 9.0, MomentKind.FOG_ROLL to 14.0, MomentKind.MOONRISE to 12.0,
            MomentKind.OWL to 5.5, MomentKind.BATS to 7.0, MomentKind.AURORA to 14.0, MomentKind.CAR to 11.0,
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
