package me.carlosoliveira.weatherscape

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Raindrops on the camera glass, ported from the HTML preview.
 * One unit is the width of the HTML preview's wall (380 dp), so drops keep their size on the cover and
 * inner displays; the glass spans [w] by [h] units, y pointing down. Beads are small and static; drops grow, then slide with
 * stick-slip, leave bead trails, sweep up beads and merge. Everything dries once the rain stops.
 * Arrays are preallocated, so a frame never allocates.
 */
class LensDrops {
    private val bx = FloatArray(MAX_BEADS)
    private val by = FloatArray(MAX_BEADS)
    private val br = FloatArray(MAX_BEADS)
    private var beads = 0

    private val dx = FloatArray(MAX_DROPS)
    private val dy = FloatArray(MAX_DROPS)
    private val dr = FloatArray(MAX_DROPS)
    private val vy = FloatArray(MAX_DROPS)
    private val stall = FloatArray(MAX_DROPS)
    private val wander = FloatArray(MAX_DROPS)
    private val travelled = FloatArray(MAX_DROPS)
    private var drops = 0

    /** Heavy-rain impacts: a ring of water spreads out and leaves a bead where it hit. */
    private val sx = FloatArray(MAX_SPLASHES)
    private val sy = FloatArray(MAX_SPLASHES)
    private val sr = FloatArray(MAX_SPLASHES)
    private val sAge = FloatArray(MAX_SPLASHES)
    private val sSeed = FloatArray(MAX_SPLASHES)
    private var splashes = 0

    /**
     * Quads for the glass shader: 6 vertices each of screen x, y (0..1, y down), local x, y (-1..1),
     * and a shape: kind (0 drop, 1 splash), splash age 0..1, and a seed for variety.
     */
    val vertices = FloatArray((MAX_BEADS + MAX_DROPS + MAX_SPLASHES) * 6 * STRIDE)
    var vertexCount = 0
        private set

    val isEmpty get() = beads == 0 && drops == 0 && splashes == 0

    fun clear() { beads = 0; drops = 0; splashes = 0; vertexCount = 0 }

    /**
     * Advances by [dt] seconds. A null [dt] means the wallpaper is redrawing rarely (reduced motion or
     * Battery Saver): the glass then holds a still set of drops while it rains and clears when dry.
     * [amount] scales how much rain reaches the glass, [size] the drops, [speed] how fast they slide
     * and [drying] how fast the glass dries; 1 matches the HTML preview.
     */
    fun update(
        dt: Float?, rain: Float, wind: Float, width: Float, height: Float,
        amount: Float = 1f, size: Float = 1f, speed: Float = 1f, drying: Float = 1f, splashes: Boolean = true,
    ) {
        w = width
        h = height
        this.amount = amount
        this.size = size
        this.speed = speed
        this.drying = drying
        splashesOn = splashes
        if (dt == null) {
            if (rain < .02f) clear() else if (isEmpty) seed(rain)
        } else {
            step(dt, rain, wind)
        }
        build()
    }

    private var w = 1f
    private var h = 1f
    private var amount = 1f
    private var size = 1f
    private var speed = 1f
    private var drying = 1f
    private var splashesOn = true

    private fun seed(rain: Float) {
        val area = w * h / 1.78f * amount
        repeat((260 * rain * area).toInt()) { addBead(Random.nextFloat() * w, Random.nextFloat() * h, size * (.003f + .007f * sq(Random.nextFloat()))) }
        repeat(min(MAX_DROPS, (16 * rain * area).toInt())) { addDrop(Random.nextFloat() * h, size * (.011f + .014f * Random.nextFloat())) }
    }

    private fun step(dt: Float, rain: Float, wind: Float) {
        // Spawn rates are tuned for the preview's 9:16 wall; larger glass catches more rain.
        val area = w * h / 1.78f * amount
        if (rain > .01f) {
            var n = 50 * rain * area * dt + Random.nextFloat()
            while (n >= 1) { addBead(Random.nextFloat() * w, Random.nextFloat() * h, size * (.0028f + .0075f * sq(Random.nextFloat()))); n-- }
            n = (2.2f * rain + 5 * rain * rain) * area * dt + Random.nextFloat()
            while (n >= 1 && drops < MAX_DROPS) {
                val d = addDrop(Random.nextFloat() * h, size * (.011f + .022f * sq(Random.nextFloat())))
                // A new drop swallows the beads it lands on.
                var i = beads - 1
                while (i >= 0) {
                    if (hypot(bx[i] - dx[d], by[i] - dy[d]) < dr[d]) { absorb(d, br[i], .5f); removeBead(i) }
                    i--
                }
                n--
            }
            // Heavy rain hits hard enough to splash before it settles.
            if (splashesOn && rain > .45f) {
                n = 14 * (rain - .45f) * area * dt + Random.nextFloat()
                while (n >= 1 && splashes < MAX_SPLASHES) {
                    val s = splashes++
                    sx[s] = Random.nextFloat() * w; sy[s] = Random.nextFloat() * h
                    sr[s] = size * (.014f + .014f * Random.nextFloat()); sAge[s] = 0f; sSeed[s] = Random.nextFloat()
                    addBead(sx[s], sy[s], size * (.003f + .003f * Random.nextFloat()))
                    n--
                }
            }
        } else {
            // After the shower the glass dries: beads first, then the drops.
            for (i in 0 until beads) br[i] -= .001f * size * drying * dt
            for (i in 0 until drops) dr[i] -= DRY_RATE * size * drying * dt
        }
        var i = beads - 1
        var k = splashes - 1
        while (k >= 0) {
            sAge[k] += dt / SPLASH_SECONDS
            if (sAge[k] >= 1f) {
                splashes--
                sx[k] = sx[splashes]; sy[k] = sy[splashes]; sr[k] = sr[splashes]; sAge[k] = sAge[splashes]; sSeed[k] = sSeed[splashes]
            }
            k--
        }
        while (i >= 0) { if (br[i] < .0018f * size) removeBead(i); i-- }

        var d = drops - 1
        while (d >= 0) {
            slide(d, dt, rain, wind)
            d--
        }
        merge()
    }

    private fun slide(d: Int, dt: Float, rain: Float, wind: Float) {
        if (dr[d] < GONE * size || dy[d] - dr[d] > h) { removeDrop(d); return }
        if (rain > .01f && vy[d] == 0f) dr[d] += .0024f * size * rain * dt * Random.nextFloat()
        val slide = SLIDE * size
        if (dr[d] < slide) { vy[d] = 0f; return }
        if (stall[d] > 0) { stall[d] -= dt; vy[d] *= .8f; return }
        // Stick and slip: water catches on the glass, then lets go.
        val target = (.05f + .38f * min(2f, (dr[d] - slide) / slide)) * speed
        vy[d] += (target - vy[d]) * min(1f, dt * 6)
        if (Random.nextFloat() < dt * 1.4f) { stall[d] = .05f + .35f * Random.nextFloat(); wander[d] = (Random.nextFloat() - .5f) * .3f }
        val step = vy[d] * dt
        dy[d] += step
        dx[d] += step * (wander[d] + .05f * wind)
        travelled[d] += step
        while (travelled[d] > TRAIL * size) {
            travelled[d] -= TRAIL * size
            if (Random.nextFloat() < .75f && beads < MAX_BEADS) {
                val r = dr[d] * (.18f + .2f * Random.nextFloat())
                addBead(dx[d] + (Random.nextFloat() - .5f) * dr[d] * .4f, dy[d] - dr[d] * 1.25f, r)
                dr[d] = sqrt(max(0f, dr[d] * dr[d] - r * r * .7f))
            }
        }
        // Beads ahead of a sliding drop are swept into it.
        var j = beads - 1
        while (j >= 0) {
            val ax = bx[j] - dx[d]
            val ay = by[j] - dy[d]
            if (ay > -dr[d] * .3f && hypot(ax, ay) < dr[d] * .95f) { absorb(d, br[j], .8f); removeBead(j) }
            j--
        }
    }

    /** Touching drops merge into the larger one. */
    private fun merge() {
        var i = 0
        while (i < drops) {
            var j = i + 1
            while (j < drops) {
                if (hypot(dx[i] - dx[j], dy[i] - dy[j]) <= (dr[i] + dr[j]) * .8f) {
                    val big = if (dr[i] >= dr[j]) i else j
                    val small = if (big == i) j else i
                    absorb(big, dr[small], 1f)
                    dy[big] += dr[small] * .3f
                    removeDrop(small)
                    if (small == i) { j = drops; i-- } else j = i + 1
                } else j++
            }
            i++
        }
    }

    private fun build() {
        var o = 0
        for (i in 0 until beads) o = quad(o, bx[i], by[i], br[i], br[i])
        for (d in 0 until drops) {
            // Sliding drops stretch along their path, with the water gathered low.
            val stretch = 1 + min(.55f, vy[d] * 1.6f)
            val rx = dr[d] * (1 - .08f * (stretch - 1))
            val ry = dr[d] * stretch
            o = quad(o, dx[d], dy[d] - .15f * ry + .15f * dr[d], rx, ry)
        }
        for (k in 0 until splashes) o = quad(o, sx[k], sy[k], sr[k], sr[k], 1f, sAge[k], sSeed[k])
        vertexCount = o / STRIDE
    }

    private fun quad(start: Int, x: Float, y: Float, rx: Float, ry: Float, kind: Float = 0f, age: Float = 0f, seed: Float = 0f): Int {
        val l = (x - rx) / w
        val r = (x + rx) / w
        val t = (y - ry) / h
        val b = (y + ry) / h
        var o = vertex(start, l, t, -1f, -1f, kind, age, seed)
        o = vertex(o, r, t, 1f, -1f, kind, age, seed)
        o = vertex(o, r, b, 1f, 1f, kind, age, seed)
        o = vertex(o, l, t, -1f, -1f, kind, age, seed)
        o = vertex(o, r, b, 1f, 1f, kind, age, seed)
        return vertex(o, l, b, -1f, 1f, kind, age, seed)
    }

    private fun vertex(o: Int, px: Float, py: Float, lx: Float, ly: Float, kind: Float, age: Float, seed: Float): Int {
        vertices[o] = px; vertices[o + 1] = py; vertices[o + 2] = lx; vertices[o + 3] = ly
        vertices[o + 4] = kind; vertices[o + 5] = age; vertices[o + 6] = seed
        return o + STRIDE
    }

    private fun addBead(x: Float, y: Float, r: Float) {
        if (beads >= MAX_BEADS) return
        bx[beads] = x; by[beads] = y; br[beads] = r; beads++
    }

    private fun removeBead(i: Int) {
        beads--
        bx[i] = bx[beads]; by[i] = by[beads]; br[i] = br[beads]
    }

    private fun addDrop(y: Float, r: Float): Int {
        val d = drops++
        dx[d] = Random.nextFloat() * w; dy[d] = y; dr[d] = r
        vy[d] = 0f; stall[d] = 0f; wander[d] = (Random.nextFloat() - .5f) * .3f; travelled[d] = 0f
        return d
    }

    private fun removeDrop(d: Int) {
        drops--
        dx[d] = dx[drops]; dy[d] = dy[drops]; dr[d] = dr[drops]; vy[d] = vy[drops]
        stall[d] = stall[drops]; wander[d] = wander[drops]; travelled[d] = travelled[drops]
    }

    private fun absorb(d: Int, r: Float, share: Float) { dr[d] = sqrt(dr[d] * dr[d] + r * r * share) }

    private fun sq(v: Float) = v * v

    companion object {
        const val STRIDE = 7

        /** Seconds the largest falling drop takes to dry once the rain stops, at [drying] speed. */
        fun secondsToDry(drying: Float): Int = ((.033f - GONE) / DRY_RATE / drying).roundToInt()

        private const val DRY_RATE = .0022f
        private const val GONE = .0035f
        // Room for the heaviest amount on the unfolded inner display, about twice the cover screen's glass.
        private const val MAX_BEADS = 3000
        private const val MAX_DROPS = 160
        private const val MAX_SPLASHES = 60
        private const val SPLASH_SECONDS = .35f
        private const val SLIDE = .028f
        private const val TRAIL = .026f
    }
}
