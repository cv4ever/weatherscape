package me.carlosoliveira.weatherscape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Interleaved triangle vertices: x, y (plate units), r, g, b, a, u, v, shape.
 * Shape 0 is a flat fill, 1 a soft glow over u,v in -1..1, 2 an atlas sprite at u,v.
 */
class OverlayBatch(maxQuads: Int) {
    val data = FloatArray(maxQuads * 6 * STRIDE)
    var vertices = 0
        private set

    fun clear() { vertices = 0 }

    private fun vertex(x: Float, y: Float, r: Float, g: Float, b: Float, a: Float, u: Float, v: Float, shape: Float) {
        if ((vertices + 1) * STRIDE > data.size) return
        var i = vertices * STRIDE
        data[i++] = x; data[i++] = y; data[i++] = r; data[i++] = g; data[i++] = b; data[i++] = a
        data[i++] = u; data[i++] = v; data[i] = shape
        vertices++
    }

    /** A straight stroke whose color fades from [a0] at the start to [a1] at the end. */
    fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, rgb: Rgb, a0: Float, a1: Float, rgb1: Rgb = rgb) {
        val length = hypot(x1 - x0, y1 - y0)
        if (length < 1e-6f || (a0 <= 0f && a1 <= 0f)) return
        val nx = -(y1 - y0) / length * width / 2
        val ny = (x1 - x0) / length * width / 2
        quad(
            x0 + nx, y0 + ny, x0 - nx, y0 - ny, x1 - nx, y1 - ny, x1 + nx, y1 + ny,
            rgb, a0, rgb1, a1,
        )
    }

    private fun quad(
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float,
        start: Rgb, a0: Float, end: Rgb, a1: Float,
    ) {
        vertex(ax, ay, start.r, start.g, start.b, a0, 0f, 0f, 0f)
        vertex(bx, by, start.r, start.g, start.b, a0, 0f, 0f, 0f)
        vertex(cx, cy, end.r, end.g, end.b, a1, 0f, 0f, 0f)
        vertex(ax, ay, start.r, start.g, start.b, a0, 0f, 0f, 0f)
        vertex(cx, cy, end.r, end.g, end.b, a1, 0f, 0f, 0f)
        vertex(dx, dy, end.r, end.g, end.b, a1, 0f, 0f, 0f)
    }

    fun glow(x: Float, y: Float, radius: Float, rgb: Rgb, alpha: Float) {
        if (alpha <= .001f) return
        val l = x - radius; val r = x + radius; val t = y - radius; val b = y + radius
        vertex(l, t, rgb.r, rgb.g, rgb.b, alpha, -1f, -1f, 1f)
        vertex(r, t, rgb.r, rgb.g, rgb.b, alpha, 1f, -1f, 1f)
        vertex(r, b, rgb.r, rgb.g, rgb.b, alpha, 1f, 1f, 1f)
        vertex(l, t, rgb.r, rgb.g, rgb.b, alpha, -1f, -1f, 1f)
        vertex(r, b, rgb.r, rgb.g, rgb.b, alpha, 1f, 1f, 1f)
        vertex(l, b, rgb.r, rgb.g, rgb.b, alpha, -1f, 1f, 1f)
    }

    /**
     * Draws atlas cell [frame] (3 columns, 2 rows) anchored at x, y: the anchor sits at the
     * cell's horizontal centre and [foot] of its height. Then mirrors by [dir], rotates and clips.
     * [brightness] and [saturation] let distant and night animals take the scene's light.
     */
    fun sprite(
        frame: Int, x: Float, y: Float, width: Float, foot: Float, angle: Float, dir: Float, alpha: Float,
        clipLeft: Float = -10f, brightness: Float = 1f, saturation: Float = 1f,
    ) {
        if (alpha <= .002f) return
        val height = width
        var left = -width * .5f
        val right = width * .5f
        val top = -height * foot
        val bottom = height * (1 - foot)
        val u0 = (frame % 3) / 3f
        val u1 = u0 + 1 / 3f
        val v0 = (frame / 3) / 2f
        val v1 = v0 + .5f
        var uLeft = if (dir > 0) u0 else u1
        val uRight = if (dir > 0) u1 else u0
        // Clipping happens before rotation; the squirrel's tilt is a few degrees, so the error is invisible.
        val visibleLeft = clipLeft - x
        if (visibleLeft > left) {
            if (visibleLeft >= right) return
            val t = (visibleLeft - left) / (right - left)
            uLeft = mix(uLeft, uRight, t)
            left = visibleLeft
        }
        val c = cos(angle)
        val s = sin(angle)
        val ltx = x + left * c - top * s; val lty = y + left * s + top * c
        val rtx = x + right * c - top * s; val rty = y + right * s + top * c
        val rbx = x + right * c - bottom * s; val rby = y + right * s + bottom * c
        val lbx = x + left * c - bottom * s; val lby = y + left * s + bottom * c
        val b = brightness
        val shape = 2f + saturation.coerceIn(0f, 1f)
        vertex(ltx, lty, b, b, b, alpha, uLeft, v0, shape)
        vertex(rtx, rty, b, b, b, alpha, uRight, v0, shape)
        vertex(rbx, rby, b, b, b, alpha, uRight, v1, shape)
        vertex(ltx, lty, b, b, b, alpha, uLeft, v0, shape)
        vertex(rbx, rby, b, b, b, alpha, uRight, v1, shape)
        vertex(lbx, lby, b, b, b, alpha, uLeft, v1, shape)
    }

    /** Cross-fades two neighbouring frames so six poses read as continuous motion. */
    fun blended(
        frame: Float, x: Float, y: Float, width: Float, foot: Float, angle: Float, dir: Float, alpha: Float,
        brightness: Float = 1f, saturation: Float = 1f,
    ) {
        val a = floor(frame).toInt().mod(6)
        val f = frame - floor(frame)
        sprite(a, x, y, width, foot, angle, dir, alpha * (1 - f * .55f), brightness = brightness, saturation = saturation)
        sprite((a + 1) % 6, x, y, width, foot, angle, dir, alpha * f, brightness = brightness, saturation = saturation)
    }

    companion object {
        const val STRIDE = 9
    }
}

data class Rgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        fun hex(v: Int) = Rgb(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f)
    }
}

/** One texture per animal; each gets its own batch so a frame needs one draw call per atlas. */
enum class Atlas(val path: String) {
    BIRD("textures/bird_atlas.webp"), SQUIRREL("textures/squirrel_atlas.webp"), DEER("textures/deer_atlas.webp"),
    BUZZARD("textures/buzzard_atlas.webp"), GOOSE("textures/goose_atlas.webp"), OWL("textures/owl_atlas.webp"),
    BAT("textures/bat_atlas.webp");

    companion object {
        /** Array form so per-frame loops don't allocate iterators. */
        val ALL: Array<Atlas> = entries.toTypedArray()
    }
}

/**
 * Builds rain, snow and moment geometry for one frame, ported from the HTML preview. Units: the plate is
 * 1 wide and [aspect] tall, y pointing down, so the preview's `w` is 1 and `h` is [aspect].
 */
class OverlayPainter(private val aspect: Float) {
    val effects = OverlayBatch(1600)
    val sprites = java.util.EnumMap<Atlas, OverlayBatch>(Atlas::class.java).apply {
        for (atlas in Atlas.entries) put(atlas, OverlayBatch(24))
    }
    private val birds get() = sprites.getValue(Atlas.BIRD)
    private val squirrels get() = sprites.getValue(Atlas.SQUIRREL)

    private class Drop(val x: Float, val y: Float, val depth: Float, val glint: Float, val chance: Float)

    private val drops: Array<Drop> = run {
        var seed = 718327
        fun random(): Float {
            seed = seed * 1664525 + 1013904223
            return (seed.toLong() and 0xffffffffL).toFloat() / 4294967296f
        }
        Array(220) {
            val x = random(); val y = random(); val depth = random().pow(2); val glint = random(); val chance = random()
            Drop(x, y, depth, glint, chance)
        }
    }

    fun paint(f: Frame) {
        effects.clear()
        for (atlas in Atlas.ALL) sprites.getValue(atlas).clear()
        rain(f)
        snow(f)
        val t = f.momentProgress
        val dir = if (f.momentFlip) -1f else 1f
        when (f.moment) {
            MomentKind.BIRD -> bird(t, dir)
            MomentKind.FLOCK -> flock(t, dir)
            MomentKind.SQUIRREL -> squirrel(t, f)
            MomentKind.FIREFLIES -> fireflies(t)
            MomentKind.COMET -> comet(t)
            MomentKind.DEER -> deer(t, f)
            MomentKind.BUZZARD -> buzzard(t, f)
            MomentKind.GEESE -> geese(t, f)
            MomentKind.OWL -> owl(t, dir)
            MomentKind.BATS -> bats(t)
            MomentKind.CAR -> car(t)
            else -> Unit // Sunbeam, rainbow, fog, moonrise and aurora are drawn by the landscape shader.
        }
        if (f.flushProgress >= 0f) flush(f)
    }

    /** 0 at night, 1 in full daylight, from the scene's ground light. */
    private fun daylight(f: Frame) = ((f.ground[1] - .56f) / .57f).coerceIn(0f, 1f)

    private fun snow(f: Frame) {
        val strength = f.snow
        if (strength <= .001f) return
        val h = aspect
        val px = 1f / 380
        for (i in drops.indices) {
            val d = drops[i]
            val z = d.depth
            val density = smooth(d.chance * .8f, d.chance * .8f + .15f, strength)
            if (density <= .001f) continue
            // Flakes fall slowly and swing side to side; nearer ones are larger and faster.
            val fall = 1.6f + floor(z * 2.5f + d.glint * 1.5f)
            val flight = d.y + fall * f.phase * .5f
            val age = wrap(flight)
            val sway = .02f * sin(TAU * (f.phase * (1.3f + d.glint) + d.x * 7)) + f.rainSway * .12f * (.4f + z)
            val x = wrap(d.x + floor(flight) * .618034f + sway) * 1.1f - .05f
            val y = (age * 1.1f - .05f) * h
            val radius = px * (1.1f + 3.4f * z)
            val alpha = (.25f + .55f * z) * density * mix(1f, .75f, f.light[0])
            effects.glow(x, y, radius, SNOW, alpha)
        }
    }

    private fun rain(f: Frame) {
        val w = 1f
        val h = aspect
        val strength = f.air[3]
        if (strength <= .001f) return
        val storm = smooth(.22f, 1f, strength)
        val color = if (f.light[1] > .32f) WARM_RAIN else COOL_RAIN
        val px = w / 380
        for (i in drops.indices) {
            val d = drops[i]
            val z = d.depth
            val fall = 11 + floor(z * 19 + d.glint * 5)
            val slant = mix(.085f + z * .075f, .52f + z * .24f, storm)
            val lateral = mix(.10f, .58f + .42f * z, storm)
            val flight = d.y + fall * f.phase
            val age = wrap(flight)
            val x = (wrap(d.x + floor(flight) * .618034f - slant * age - lateral * f.rainSway) * 1.36f - .18f) * w
            val y = (age * 1.13f - .065f) * h
            val rate = 1.36f * (slant * fall + lateral * f.rainGustSpeed) * w / (1.13f * fall * h)
            val length = px * (3 + z * mix(17f, 24f, storm) + d.glint * 5)
            val density = smooth(d.chance * .76f, d.chance * .76f + .18f, strength)
            val opacity = (.029f + z * .136f + d.glint * .044f) * mix(1f, 1.22f, storm) * mix(1f, .8f, f.light[0]) *
                density * smooth(0f, .32f, strength)
            if (opacity < .001f) continue
            val tip = rate * length
            if (x + tip < -.01f || x > w + .01f || y < -.01f || y - length > h) continue
            val width = px * (.47f + 1.02f * z)
            effects.stroke(x + tip, y - length, x, y, width, color, opacity * .22f, opacity * .44f)
            effects.stroke(x + .43f * tip, y - .43f * length, x, y, width, color, opacity * .4f, opacity * .67f)
        }
    }

    private fun birdFrame(age: Float, glide: Boolean) = if (glide) 2 else floor(wrap(age) * 6).toInt().coerceIn(0, 5)

    private fun bird(t: Float, dir: Float) {
        val w = 1f
        val h = aspect
        val age = t * 4.6f
        val x = if (dir > 0) -.09f + 1.18f * t else 1.09f - 1.18f * t
        val flap = t < .26f || t > .65f
        val y = .345f - .09f * t - .023f * sin(PI.toFloat() * t) + if (flap) .003f * sin(age * TAU * 4.4f) else 0f
        birds.sprite(
            birdFrame(age * 4.4f, !flap), x * w, y * h, w * (.06f + .026f * sin(PI.toFloat() * t)), .5f,
            dir * (-.07f - .12f * sin(t * TAU * .72f)), dir, .89f,
        )
    }

    private fun flock(t: Float, dir: Float) {
        val w = 1f
        val h = aspect
        val front = -.09f + 1.34f * t + .017f * sin(t * PI.toFloat())
        val y = .451f - .046f * t + .015f * sin(t * PI.toFloat() * 1.11f)
        val age = t * 7.5f
        val loosen = mix(.73f, 1.08f, smooth(.15f, .81f, t))
        for (i in FORMATION.indices.reversed()) {
            val (trail, side) = FORMATION[i]
            val x = front - trail * loosen + sin(t * 9.7f + i * 1.7f) * .0045f
            val altitude = y + side * loosen + sin(t * 5.4f + i * 1.1f) * .0037f
            birds.sprite(
                birdFrame(age * (4.1f + i * .065f) + i * .217f, wrap(age * .35f + i * .18f) > .72f),
                (if (dir > 0) x else 1 - x) * w, altitude * h, w * (.019f + (i % 3) * .002f), .5f, -.07f * dir, dir,
                .70f + (i % 3) * .045f,
            )
        }
    }

    private fun squirrel(t: Float, f: Frame) {
        val w = 1f
        val h = aspect
        val departing = t > .73f
        val move = smooth(.06f, .26f, t) * (1 - smooth(.76f, .97f, t))
        val bounding = (t > .06f && t < .26f) || (t > .76f && t < .97f)
        val stride = (if (departing) t - .76f else t - .06f) * 24
        val frame = if (bounding) floor(wrap(stride) * 3).toInt() else if (t < .44f) 3 else if (t < .60f) 4 else 5
        val paw = .705f
        val rest = (paw - .608f) / (.94f - .608f)
        val bend = (1 - rest * rest * (3 - 2 * rest)).pow(2)
        val phase = f.phase
        val wind = f.air[0] * .013f * bend * f.gust *
            (.52f * sin(TAU * phase * 3) + .23f * sin(TAU * phase * 7) + .25f * sin(TAU * phase - .9f))
        val jump = if (bounding) sin(PI.toFloat() * wrap(stride)).pow(2) * w * .007f else 0f
        val x = (.021f + .083f * move + wind) * w
        val y = (paw + .02f * (1 - move)) * h - jump
        squirrels.sprite(frame, x, y, w * .079f, .875f, -.045f, if (departing) -1f else 1f, .94f, clipLeft = w * (.055f + wind))
    }

    private fun deer(t: Float, f: Frame) {
        // A roe deer steps out of the trees onto the meadow far below, grazes, looks up, and walks back.
        var x = .488f
        var dir = 1f
        val frame = when {
            t < .32f -> { val k = t / .32f; x = .446f + .042f * k; floor(wrap(k * 5) * 3).toInt() }
            t < .58f -> 3
            t < .70f -> 4
            else -> { val k = (t - .7f) / .3f; x = .488f - .042f * k; dir = -1f; floor(wrap(k * 4) * 3).toInt() }
        }
        val alpha = smooth(0f, .1f, t) * (1 - smooth(.88f, 1f, t)) * .95f
        sprites.getValue(Atlas.DEER).sprite(
            frame, x, .6985f * aspect, .021f, .917f, 0f, dir, alpha,
            brightness = .42f + .36f * daylight(f), saturation = .6f,
        )
    }

    private fun buzzardPath(t: Float, out: FloatArray) {
        // The thermal's centre slides in from off-screen, holds, then drifts out: the bird circles all the way.
        val cx = mix(-.32f, .42f, smooth(0f, .32f, t)) + mix(0f, .92f, smooth(.68f, 1f, t))
        val cy = .27f - .04f * t
        val angle = t * TAU * 1.55f + .4f
        out[0] = cx + .085f * cos(angle)
        out[1] = cy + .024f * sin(angle)
        out[2] = 1 + .12f * sin(angle)
    }

    private val now = FloatArray(3)
    private val ahead = FloatArray(3)
    private val behind = FloatArray(3)

    private fun buzzard(t: Float, f: Frame) {
        buzzardPath(t, now); buzzardPath(minOf(1f, t + .004f), ahead); buzzardPath(maxOf(0f, t - .004f), behind)
        val vx = ahead[0] - behind[0]
        val vy = ahead[1] - behind[1]
        val turn = (vx * (ahead[1] - 2 * now[1] + behind[1]) - vy * (ahead[0] - 2 * now[0] + behind[0])) /
            (vx * vx + vy * vy + 1e-9f).pow(1.5f)
        val bank = (turn * .004f).coerceIn(-1f, 1f)
        val age = t * 22f
        val flapping = t < .1f || t > .9f || (t > .47f && t < .52f)
        val dir = if (vx >= 0) 1f else -1f
        val b = .45f + .45f * daylight(f)
        val batch = sprites.getValue(Atlas.BUZZARD)
        val x = now[0]; val y = now[1] * aspect; val width = .05f * now[2]
        if (flapping) {
            val beat = .5f - .5f * cos(age * TAU * 2.2f)
            batch.sprite(0, x, y, width, .5f, bank * .12f, dir, .88f * (1 - beat * .7f), brightness = b, saturation = .7f)
            batch.sprite(5, x, y, width, .5f, bank * .12f, dir, .88f * beat, brightness = b, saturation = .7f)
        } else {
            val glide = if (abs(bank) < .3f) 0f else if (bank > 0) 1 + minOf(1f, (abs(bank) - .3f) / .5f) else 4f
            batch.blended(glide, x, y, width, .5f, bank * .12f, dir, .88f, brightness = b, saturation = .7f)
        }
    }

    private fun geese(t: Float, f: Frame) {
        // A loose V high up, heading south-west, each bird on its own wingbeat.
        val leadX = 1.12f - 1.36f * t
        val leadY = .17f - .03f * t + .005f * sin(t * 5)
        val b = .42f + .38f * daylight(f)
        val batch = sprites.getValue(Atlas.GOOSE)
        for (i in 0 until 9) {
            val k = (i + 1) / 2
            val side = if (i % 2 == 1) 1f else -1f
            val x = leadX + k * .03f + .003f * sin(t * 7 + i)
            val y = leadY + side * k * .011f + .002f * sin(t * 9 + i * 1.7f)
            batch.blended(wrap(t * 9 * 2.8f + i * .37f) * 6, x, y * aspect, .021f, .5f, .04f, -1f, .82f, brightness = b, saturation = .6f)
        }
    }

    private fun owl(t: Float, dir: Float) {
        // Tawny owl: a quick downstroke, a slower recovery, the body lifting on each beat, then a long glide.
        val age = t * 6.5f
        val x = if (dir > 0) -.08f + 1.16f * t else 1.08f - 1.16f * t
        val beat = wrap(age / .62f)
        val gliding = wrap(age / 3.1f) > .52f
        val pose = if (beat < .4f) 4 * beat / .4f else 4 + 2 * (beat - .4f) / .6f
        val lift = if (gliding) 0f else .0035f * sin(beat * TAU)
        val y = (.585f - .03f * sin(t * PI.toFloat()) - lift) * aspect
        val batch = sprites.getValue(Atlas.OWL)
        if (gliding) batch.sprite(2, x, y, .06f, .5f, dir * -.03f, dir, .92f, brightness = .2f, saturation = .35f)
        else batch.blended(pose, x, y, .06f, .5f, dir * -.03f, dir, .92f, brightness = .2f, saturation = .35f)
    }

    private fun bats(t: Float) {
        // Erratic hunting loops just above the canopy.
        val fade = smooth(0f, .12f, t) * (1 - smooth(.86f, 1f, t))
        val batch = sprites.getValue(Atlas.BAT)
        for (i in 0 until 3) {
            val s = i * 2.17f + .4f
            val x = .47f + .3f * sin(t * 3.1f + s * 5) + .07f * sin(t * 11.3f + s * 3)
            val y = .645f + .035f * sin(t * 4.3f + s * 2) + .014f * sin(t * 17f + s)
            val dx = .93f * cos(t * 3.1f + s * 5) + .79f * cos(t * 11.3f + s * 3)
            val frame = floor(wrap(t * 10 * 9 + s) * 6).toInt()
            batch.sprite(frame, x, y * aspect, .017f, .5f, 0f, if (dx > 0) 1f else -1f, fade * .9f, brightness = .17f, saturation = .3f)
        }
    }

    /** The valley road: a long straight, a small bend that dips towards the viewer, then another straight. */
    private fun roadY(x: Float): Float {
        val bend = smooth(.42f, .52f, x)
        return .742f - .006f * bend + .0045f * sin(PI.toFloat() * ((x - .42f) / .1f).coerceIn(0f, 1f))
    }

    /** Driving: cruise, brake into the bend, round it slowly, accelerate out. Time is the integral of 1/speed. */
    private val roadX = FloatArray(401)
    private val roadT = FloatArray(401).also { ts ->
        var time = 0f
        for (i in 0..400) {
            val x = .1f + .72f * i / 400
            val speed = mix(1f, .32f, smooth(.3f, .42f, x)) + mix(0f, .68f, smooth(.52f, .66f, x))
            if (i > 0) time += .72f / 400 / speed
            roadX[i] = x
            ts[i] = time
        }
        for (i in ts.indices) ts[i] /= time
    }

    private fun car(t: Float) {
        var lo = 0
        var hi = roadT.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (roadT[mid] < t) lo = mid else hi = mid }
        val x = mix(roadX[lo], roadX[hi], ((t - roadT[lo]) / maxOf(1e-6f, roadT[hi] - roadT[lo])).coerceIn(0f, 1f))
        val y = roadY(x)
        val tx = .002f
        val ty = (roadY(x + .002f) - roadY(x - .002f)) * .5f
        val len = hypot(tx, ty)
        // Headlights brighten as the bend turns the car towards the viewer; tail lights show as it turns away.
        val toward = ty / len * 3
        val side = abs(tx / len)
        val gaps = .5f + .5f * sin(x * 57) * sin(x * 23 + 1.3f)
        val seen = (.25f + .75f * smooth(.3f, .62f, gaps)) * smooth(0f, .06f, t) * (1 - smooth(.94f, 1f, t))
        val front = smooth(-.2f, .6f, toward)
        val spread = .0012f + .0026f * (1 - side * .8f)
        val py = y * aspect
        effects.glow(x + .016f * tx / len, py + .016f * ty / len * aspect, .03f, HEADLIGHT_SPILL, .08f * seen * (.3f + front))
        for (o in intArrayOf(-1, 1)) effects.glow(x + o * spread, py, .0055f, HEADLIGHT, seen * (.35f + .65f * front))
        effects.glow(x, py, .013f, HEADLIGHT_HALO, .2f * seen * (.4f + .6f * front))
        val tail = smooth(.05f, -.6f, toward) * seen
        for (o in intArrayOf(-1, 1)) effects.glow(x + o * spread, py, .0042f, TAIL_LIGHT, .7f * tail)
    }

    /** Birds burst from a treetop and scatter up and away, after an unlock or a tap. */
    private fun flush(f: Frame) {
        val t = f.flushProgress
        // A stateless hash of the flush seed, so every frame of one flush draws the same birds.
        var state = f.flushSeed
        fun next(): Float {
            state = state * 1664525 + 1013904223
            return (state ushr 8) / 16777216f
        }
        val b = .38f + .5f * daylight(f)
        val fade = 1 - smooth(.8f, 1f, t)
        for (i in 0 until f.flushCount) {
            val side = if (next() < .5f) 1f else -1f
            val spread = .08f + .25f * next()
            val climb = .12f + .18f * next()
            val delay = .08f * next()
            val rate = 9f + 3 * next()
            val offset = next()
            val k = ((t - delay) / (1 - delay)).coerceIn(0f, 1f)
            if (k <= 0f) continue
            // Accelerating away: slow lift-off from the crown, then a fast climb.
            val ease = k * k * (1.6f - .6f * k)
            val x = f.flushX + side * spread * ease
            val y = (f.flushY - .01f - climb * ease + .03f * ease * ease) * aspect
            val beat = wrap(k * rate + offset) * 6
            birds.blended(beat, x, y, .022f + .012f * k, .5f, side * -.15f, side, .9f * fade * smooth(0f, .05f, k), brightness = b)
        }
    }

    private fun fireflies(t: Float) {
        val w = 1f
        val h = aspect
        val envelope = smooth(.03f, .16f, t) * (1 - smooth(.84f, 1f, t))
        for ((x, y, offset) in FIREFLY_PATHS) {
            val px = w * (x + .017f * sin(t * 8 + offset) + .008f * sin(t * 17 + offset * .67f))
            val py = h * (y + .008f * cos(t * 8 + offset) + .004f * t)
            val signal = .5f + .5f * sin(t * TAU * (1.15f + offset * .18f) + offset * 2)
            val glowing = envelope * (.055f + .76f * smooth(.54f, .96f, signal))
            effects.glow(px, py, w * .008f, FIREFLY_HALO, glowing * .72f)
            effects.glow(px, py, w * .0026f, FIREFLY_CORE, glowing)
        }
    }

    private fun comet(t: Float) {
        val w = 1f
        val h = aspect
        val run = ((t - .12f) / .62f).coerceIn(0f, 1f)
        val visible = smooth(.12f, .22f, t) * (1 - smooth(.60f, .75f, t))
        val remnant = smooth(.20f, .35f, t) * (1 - smooth(.69f, 1f, t))
        if (visible + remnant < .002f) return
        val x = w * (.955f - .89f * run)
        val y = h * (.108f + .203f * run)
        val slope = .203f * h / (.89f * w)
        val tail = w * (.13f + .35f * smooth(0f, .63f, run))
        val tx = x + tail
        val ty = y - tail * slope
        // Dust tail: a tapered ribbon, widest just behind the head.
        val dustAlpha = .57f * remnant + .40f * visible
        val steps = 18
        for (i in 0 until steps) {
            val s0 = i / steps.toFloat()
            val s1 = (i + 1) / steps.toFloat()
            val w0 = .011f * w * sin(PI.toFloat() * s0).pow(.8f) * (.35f + .65f * s0)
            val w1 = .011f * w * sin(PI.toFloat() * s1).pow(.8f) * (.35f + .65f * s1)
            val (c0, a0) = DUST.at(s0)
            val (c1, a1) = DUST.at(s1)
            effects.stroke(
                mix(tx, x, s0), mix(ty, y, s0), mix(tx, x, s1), mix(ty, y, s1), (w0 + w1),
                c0, a0 * dustAlpha * .55f, a1 * dustAlpha * .55f, c1,
            )
            effects.stroke(
                mix(tx, x, s0), mix(ty, y, s0), mix(tx, x, s1), mix(ty, y, s1), w * .0033f,
                c0, a0 * (.68f * remnant + .32f * visible), a1 * (.68f * remnant + .32f * visible), c1,
            )
        }
        if (visible < .002f) return
        val cx = x + tail * .59f
        val cy = y - tail * .59f * slope
        for (i in 0 until 8) {
            val s0 = i / 8f
            val s1 = (i + 1) / 8f
            val (c0, a0) = CORE.at(s0)
            val (c1, a1) = CORE.at(s1)
            effects.stroke(mix(cx, x, s0), mix(cy, y, s0), mix(cx, x, s1), mix(cy, y, s1), w * .0044f, c0, a0 * visible, a1 * visible, c1)
        }
        effects.glow(x, y, w * .028f, Rgb.hex(0x9ed0ff), visible * .55f)
        effects.glow(x, y, w * .009f, Rgb.hex(0xfffdf0), visible)
    }

    /** Piecewise-linear color stops, like a CSS gradient. */
    private class Stops(vararg val stops: Pair<Float, Pair<Rgb, Float>>) {
        fun at(s: Float): Pair<Rgb, Float> {
            for (i in 1 until stops.size) {
                val (p1, v1) = stops[i]
                if (s <= p1) {
                    val (p0, v0) = stops[i - 1]
                    val k = ((s - p0) / (p1 - p0)).coerceIn(0f, 1f)
                    val (c0, a0) = v0
                    val (c1, a1) = v1
                    return Rgb(mix(c0.r, c1.r, k), mix(c0.g, c1.g, k), mix(c0.b, c1.b, k)) to mix(a0, a1, k)
                }
            }
            return stops.last().second
        }
    }

    companion object {
        private val COOL_RAIN = Rgb.hex(0xcdddeb)
        private val WARM_RAIN = Rgb.hex(0xdecfbb)
        private val FIREFLY_HALO = Rgb.hex(0xe6e89c)
        private val FIREFLY_CORE = Rgb.hex(0xfff6bf)
        private val SNOW = Rgb.hex(0xf4f7fb)
        private val HEADLIGHT = Rgb.hex(0xfff4d6)
        private val HEADLIGHT_HALO = Rgb.hex(0xffe2a8)
        private val HEADLIGHT_SPILL = Rgb.hex(0xfff1c4)
        private val TAIL_LIGHT = Rgb.hex(0xff3b2a)
        private val FORMATION = arrayOf(
            0f to 0f, .036f to -.014f, .049f to .011f, .081f to -.026f, .102f to .028f,
            .124f to -.035f, .153f to .039f, .171f to -.039f,
        )
        private val FIREFLY_PATHS = arrayOf(
            Triple(.16f, .79f, .7f), Triple(.31f, .84f, 2.1f), Triple(.42f, .765f, 3.4f),
            Triple(.56f, .91f, 4.9f), Triple(.77f, .83f, 1.3f), Triple(.83f, .91f, 3.1f),
        )
        private val DUST = Stops(
            0f to (Rgb.hex(0x969dd4) to 0f), .35f to (Rgb.hex(0x829fed) to .19f),
            .76f to (Rgb.hex(0xabc6fb) to .68f), 1f to (Rgb.hex(0xdbedfc) to .94f),
        )
        private val CORE = Stops(
            0f to (Rgb.hex(0x9dbdfa) to 0f), .6f to (Rgb.hex(0xbfdcff) to .5f), 1f to (Rgb.hex(0xfff8df) to 1f),
        )
    }
}
