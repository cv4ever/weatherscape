package me.carlosoliveira.weatherscape

import kotlin.math.PI
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
     */
    fun sprite(frame: Int, x: Float, y: Float, width: Float, foot: Float, angle: Float, dir: Float, alpha: Float, clipLeft: Float = -10f) {
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
        vertex(ltx, lty, 1f, 1f, 1f, alpha, uLeft, v0, 2f)
        vertex(rtx, rty, 1f, 1f, 1f, alpha, uRight, v0, 2f)
        vertex(rbx, rby, 1f, 1f, 1f, alpha, uRight, v1, 2f)
        vertex(ltx, lty, 1f, 1f, 1f, alpha, uLeft, v0, 2f)
        vertex(rbx, rby, 1f, 1f, 1f, alpha, uRight, v1, 2f)
        vertex(lbx, lby, 1f, 1f, 1f, alpha, uLeft, v1, 2f)
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

/**
 * Builds rain and moment geometry for one frame, ported from the HTML preview. Units: the plate is
 * 1 wide and [aspect] tall, y pointing down, so the preview's `w` is 1 and `h` is [aspect].
 */
class OverlayPainter(private val aspect: Float) {
    val effects = OverlayBatch(1400)
    val birds = OverlayBatch(16)
    val squirrels = OverlayBatch(2)

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
        effects.clear(); birds.clear(); squirrels.clear()
        rain(f)
        val t = f.momentProgress
        val dir = if (f.momentFlip) -1f else 1f
        when (f.moment) {
            MomentKind.BIRD -> bird(t, dir)
            MomentKind.FLOCK -> flock(t, dir)
            MomentKind.SQUIRREL -> squirrel(t, f)
            MomentKind.FIREFLIES -> fireflies(t)
            MomentKind.COMET -> comet(t)
            else -> Unit // Sunbeam and rainbow are drawn by the landscape shader.
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
