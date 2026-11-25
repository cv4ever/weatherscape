package me.carlosoliveira.weatherscape

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one [scenery]: a full-screen pass for the landscape and sky, rain and moments as overlays,
 * then the glass. Everything runs on the GL thread; [pan] and [dim] are written from the main thread.
 */
class SceneRenderer(private val context: Context, val director: SceneDirector, private val scenery: Scenery) : GLSurfaceView.Renderer {
    @Volatile var pan = .5f
    @Volatile var dim = 0f
    /** Sun altitude of the last drawn frame, for the system's wallpaper colour hints. */
    @Volatile var lastSunAltitude = 0.0
        private set
    /**
     * Whether the last frame had fast motion: rain, snow, strong wind, lightning, a moment, birds
     * leaving the trees, or water or mist changing on the glass. Otherwise the valley only drifts.
     */
    @Volatile var busy = true
        private set
    /** Rock Jetty's sea and rocks; null for sceneries drawn from a still plate. */
    val video = if (scenery == Scenery.ROCK_JETTY) VideoLoop(context, "video/rock_jetty.mp4") else null

    private val frame = Frame()
    private val painter = OverlayPainter(ASPECT)
    private val quad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val overlay = floatBuffer(FloatArray(painter.effects.data.size))
    private val lens = LensDrops()
    private val lensBuffer = floatBuffer(lens.vertices)
    private var lensTime = 0.0
    /** Until these times the glass gets a preview shower or preview condensation, whatever the weather. */
    private var lensShowerUntil = -1.0
    private var lensMistUntil = -1.0
    /** Condensation on the glass, 0..1, easing towards the weather's target. */
    private var mistLevel = 0f
    private var mistTarget = 0f

    /** Called on the GL thread when the settings screen asks to try the glass effects. */
    fun previewLens(kind: LensPreview, time: Double) {
        when (kind) {
            LensPreview.SHOWER -> lensShowerUntil = time + LENS_SHOWER_SECONDS
            LensPreview.MIST -> lensMistUntil = time + LENS_MIST_SECONDS
        }
    }

    private var landscape = 0
    private var effects = 0
    private var glass = 0
    private var mist = 0
    /** Copy of the frame behind the lens drops, sized to the surface. */
    private var frameCopy = 0
    private var plate = 0
    private var seaMask = 0
    private var stars = 0
    private var moonTexture = 0
    private var fogTexture = 0
    private var auroraTexture = 0
    private val atlases = java.util.EnumMap<Atlas, Int>(Atlas::class.java)
    private var grain = 0
    private val landscapeUniforms = HashMap<String, Int>()
    private val effectUniforms = HashMap<String, Int>()
    private val glassUniforms = HashMap<String, Int>()
    private val mistUniforms = HashMap<String, Int>()
    private var width = 1
    private var height = 1
    private val crop = FloatArray(4)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        landscape = program(
            scenery.shader, vertex = "landscape",
            prelude = if (video != null) listOf("external.glsl", "sky.glsl") else listOf("sky.glsl"),
        )
        effects = program("overlay")
        glass = program("glass")
        for (name in listOf("scene", "texel", "reach", "daylight")) glassUniforms[name] = glGetUniformLocation(glass, name)
        mist = program("mist", vertex = "screen")
        for (name in listOf("scene", "grain", "texel", "ratio", "amount", "flow")) mistUniforms[name] = glGetUniformLocation(mist, name)
        for (name in listOf("cropOffset", "cropScale", "landscape", "stars", "grain", "moonTexture", "fogTexture",
            "auroraTexture", "phase", "flow", "gust", "flash", "sunbeam", "rainbow", "dim", "air", "ground", "sky",
            "horizonColor", "mist", "light", "moon", "moonLight", "moonGlow", "aurora", "snowCover", "reveal", "fogRoll",
            "season", "video", "seaMask", "videoMatrix", "wet")) {
            landscapeUniforms[name] = glGetUniformLocation(landscape, name)
        }
        for (name in listOf("cropOffset", "cropScale", "plateAspect", "atlas", "dim")) {
            effectUniforms[name] = glGetUniformLocation(effects, name)
        }
        stars = texture("textures/milky_way.webp")
        moonTexture = texture("textures/moon.webp")
        auroraTexture = texture("textures/aurora.webp", GL_MIRRORED_REPEAT)
        if (video != null) {
            video.create()
            seaMask = texture("textures/rock_jetty_sea.png")
        } else {
            plate = texture("textures/pine_valley.webp")
            // The fog bank tiles sideways as it drifts.
            fogTexture = texture("textures/fog_bank.webp", GL_MIRRORED_REPEAT)
        }
        if (scenery.wildlife) for (atlas in Atlas.entries) atlases[atlas] = texture(atlas.path)
        grain = grainTexture()
        frameCopy = IntArray(1).also { glGenTextures(1, it, 0) }[0]
        allocateFrameCopy()
        glDisable(GL_DEPTH_TEST)
        glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        glViewport(0, 0, width, height)
        allocateFrameCopy()
    }

    private fun allocateFrameCopy() {
        glBindTexture(GL_TEXTURE_2D, frameCopy)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height, 0, GL_RGB, GL_UNSIGNED_BYTE, null)
    }

    override fun onDrawFrame(gl: GL10?) {
        val time = SystemClock.elapsedRealtimeNanos() / 1e9
        director.update(time, System.currentTimeMillis(), frame)
        video?.update()
        lastSunAltitude = frame.sunAltitude
        computeCrop()
        drawLandscape()
        painter.paint(frame)
        drawOverlays()
        drawLens(time)
        busy = frame.moment != null || frame.flushProgress >= 0f || frame.reveal > .01f || frame.flash > .001f ||
            frame.air[3] > .01f || frame.snow > .01f || frame.air[0] > 1.3f || lens.vertexCount > 0 ||
            abs(mistLevel - mistTarget) > .01f || video?.playing == true
    }

    /** Condensation and raindrops on the camera glass, both refracting a copy of the frame drawn so far. */
    private fun drawLens(time: Double) {
        val s = director.settings
        val dt = time - lensTime
        lensTime = time
        // Rare redraws (reduced motion, Battery Saver) hold a still glass instead of simulating.
        val still = dt > 1.0 || s.reducedMotion
        updateMist(if (still) null else dt.toFloat(), s)
        if (s.lensDrops) {
            val metrics = context.resources.displayMetrics
            val unitsWide = metrics.widthPixels / metrics.density / PREVIEW_WIDTH_DP
            // The preview shower ignores the weather, then lets the glass dry as it would after rain.
            val rain = if (time < lensShowerUntil) max(frame.air[3], .85f) else frame.air[3]
            lens.update(
                if (still) null else dt.toFloat(), rain, frame.air[0], unitsWide, unitsWide * height / width,
                amount = s.lensAmount, size = s.lensSize.scale, speed = s.lensSpeed.scale, drying = s.lensDrying.scale,
                splashes = s.lensSplashes,
            )
        } else {
            lens.clear()
        }
        if (lens.vertexCount == 0 && mistLevel < .004f) return
        glBindTexture(GL_TEXTURE_2D, frameCopy)
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, frameCopy)
        if (mistLevel >= .004f) drawMist()
        if (lens.vertexCount > 0) drawDrops()
    }

    /**
     * Condensation follows dense valley fog, strongest in the cool of the morning or night, and
     * clears slowly as the fog lifts. A null [dt] jumps straight to the target.
     */
    private fun updateMist(dt: Float?, s: Settings) {
        val afternoon = frame.light[2] > 0f && frame.sunAltitude > 10
        var target = if (s.lensMist) smooth(.8f, 1.15f, frame.air[1]) * (if (afternoon) .3f else 1f) else 0f
        // The preview fogs the glass for a while, then lets it clear the way it would as fog lifts.
        if (lensTime < lensMistUntil) target = 1f
        val seconds = if (lensTime < lensMistUntil) MIST_PREVIEW_SECONDS else MIST_SECONDS
        mistTarget = target
        mistLevel = if (dt == null) target else mistLevel + (target - mistLevel) * min(1f, dt / seconds)
    }

    private fun drawMist() {
        glUseProgram(mist)
        val u = mistUniforms
        glUniform1i(u.getValue("scene"), 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, grain); glUniform1i(u.getValue("grain"), 1)
        glActiveTexture(GL_TEXTURE0)
        glUniform2f(u.getValue("texel"), 1f / width, 1f / height)
        glUniform1f(u.getValue("ratio"), height.toFloat() / width)
        glUniform1f(u.getValue("amount"), mistLevel)
        glUniform1f(u.getValue("flow"), frame.flow)
        val corner = glGetAttribLocation(mist, "corner")
        quad.position(0)
        glEnableVertexAttribArray(corner)
        glVertexAttribPointer(corner, 2, GL_FLOAT, false, 0, quad)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(corner)
    }

    private fun drawDrops() {
        glUseProgram(glass)
        val u = glassUniforms
        glUniform1i(u.getValue("scene"), 0)
        glUniform2f(u.getValue("texel"), 1f / width, 1f / height)
        // Taller reach so drops in the sky still catch the dark ridges, upside down.
        glUniform2f(u.getValue("reach"), .3f, .55f * width / height)
        glUniform1f(u.getValue("daylight"), ((frame.sky[0] + frame.sky[1] + frame.sky[2]) / 1.6f).coerceAtMost(1f))
        lensBuffer.clear()
        lensBuffer.put(lens.vertices, 0, lens.vertexCount * LensDrops.STRIDE)
        val stride = LensDrops.STRIDE * 4
        val position = glGetAttribLocation(glass, "position")
        val local = glGetAttribLocation(glass, "local")
        val shape = glGetAttribLocation(glass, "shape")
        lensBuffer.position(0); glVertexAttribPointer(position, 2, GL_FLOAT, false, stride, lensBuffer)
        lensBuffer.position(2); glVertexAttribPointer(local, 2, GL_FLOAT, false, stride, lensBuffer)
        lensBuffer.position(4); glVertexAttribPointer(shape, 3, GL_FLOAT, false, stride, lensBuffer)
        glEnableVertexAttribArray(position); glEnableVertexAttribArray(local); glEnableVertexAttribArray(shape)
        glDrawArrays(GL_TRIANGLES, 0, lens.vertexCount)
        glDisableVertexAttribArray(position); glDisableVertexAttribArray(local); glDisableVertexAttribArray(shape)
    }

    /** Cover-fits the 9:16 plate: crop the sides on tall screens, the sky and foreground on wide ones. */
    private fun computeCrop() {
        val screen = height.toFloat() / width
        if (screen >= ASPECT) {
            val scaleX = ASPECT / screen
            crop[0] = (1 - scaleX) * pan.coerceIn(0f, 1f); crop[1] = 0f; crop[2] = scaleX; crop[3] = 1f
        } else {
            val scaleY = screen / ASPECT
            // Keep the valley and the treetops, which sit low in the plate.
            crop[0] = 0f; crop[1] = (.6f - scaleY / 2).coerceIn(0f, 1 - scaleY); crop[2] = 1f; crop[3] = scaleY
        }
    }

    private fun drawLandscape() {
        glDisable(GL_BLEND)
        glUseProgram(landscape)
        val u = landscapeUniforms
        glUniform2f(u.getValue("cropOffset"), crop[0], crop[1])
        glUniform2f(u.getValue("cropScale"), crop[2], crop[3])
        if (video != null) {
            glActiveTexture(GL_TEXTURE0); glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, video.texture); glUniform1i(u.getValue("video"), 0)
            glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D, seaMask); glUniform1i(u.getValue("seaMask"), 6)
            glUniformMatrix4fv(u.getValue("videoMatrix"), 1, false, video.matrix, 0)
            glUniform1f(u.getValue("wet"), frame.wet)
        } else {
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, plate); glUniform1i(u.getValue("landscape"), 0)
            glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, fogTexture); glUniform1i(u.getValue("fogTexture"), 4)
        }
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, grain); glUniform1i(u.getValue("grain"), 2)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, stars); glUniform1i(u.getValue("stars"), 1)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, moonTexture); glUniform1i(u.getValue("moonTexture"), 3)
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, auroraTexture); glUniform1i(u.getValue("auroraTexture"), 5)
        glUniform1f(u.getValue("phase"), frame.phase)
        glUniform1f(u.getValue("flow"), frame.flow)
        glUniform1f(u.getValue("gust"), frame.gust)
        glUniform1f(u.getValue("flash"), frame.flash)
        glUniform1f(u.getValue("sunbeam"), frame.sunbeam)
        glUniform1f(u.getValue("rainbow"), frame.rainbow)
        glUniform1f(u.getValue("dim"), dim)
        glUniform4fv(u.getValue("air"), 1, frame.air, 0)
        glUniform3fv(u.getValue("ground"), 1, frame.ground, 0)
        glUniform3fv(u.getValue("sky"), 1, frame.sky, 0)
        glUniform3fv(u.getValue("horizonColor"), 1, frame.horizon, 0)
        glUniform3fv(u.getValue("mist"), 1, frame.mist, 0)
        glUniform3fv(u.getValue("light"), 1, frame.light, 0)
        glUniform4fv(u.getValue("moon"), 1, frame.moon, 0)
        glUniform3fv(u.getValue("moonLight"), 1, frame.moonLight, 0)
        glUniform1f(u.getValue("moonGlow"), frame.moonGlow)
        glUniform1f(u.getValue("aurora"), frame.aurora)
        glUniform1f(u.getValue("snowCover"), frame.snowCover)
        glUniform1f(u.getValue("reveal"), frame.reveal)
        glUniform2f(u.getValue("fogRoll"), frame.fogRoll[0], frame.fogRoll[1])
        glUniform4fv(u.getValue("season"), 1, frame.season, 0)
        val corner = glGetAttribLocation(landscape, "corner")
        quad.position(0)
        glEnableVertexAttribArray(corner)
        glVertexAttribPointer(corner, 2, GL_FLOAT, false, 0, quad)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(corner)
    }

    private fun drawOverlays() {
        if (painter.effects.vertices == 0 && Atlas.ALL.all { painter.sprites.getValue(it).vertices == 0 }) return
        glEnable(GL_BLEND)
        // Overlay colours are premultiplied, as are the atlas bitmaps Android decodes.
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glUseProgram(effects)
        val u = effectUniforms
        glUniform2f(u.getValue("cropOffset"), crop[0], crop[1])
        glUniform2f(u.getValue("cropScale"), crop[2], crop[3])
        glUniform1f(u.getValue("plateAspect"), ASPECT)
        glUniform1f(u.getValue("dim"), dim)
        glUniform1i(u.getValue("atlas"), 0)
        glActiveTexture(GL_TEXTURE0)
        draw(painter.effects, grain)
        for (atlas in Atlas.ALL) painter.sprites.getValue(atlas).let { if (it.vertices > 0) draw(it, atlases.getValue(atlas)) }
    }

    /** Maps a touch on the screen to plate coordinates, using the crop of the last frame drawn. */
    fun plateAt(screenX: Float, screenY: Float, out: FloatArray) {
        out[0] = crop[0] + screenX / width * crop[2]
        out[1] = crop[1] + screenY / height * crop[3]
    }

    private fun draw(batch: OverlayBatch, texture: Int) {
        if (batch.vertices == 0) return
        glBindTexture(GL_TEXTURE_2D, texture)
        overlay.clear()
        overlay.put(batch.data, 0, batch.vertices * OverlayBatch.STRIDE)
        val stride = OverlayBatch.STRIDE * 4
        val position = glGetAttribLocation(effects, "position")
        val color = glGetAttribLocation(effects, "color")
        val shape = glGetAttribLocation(effects, "shape")
        overlay.position(0); glVertexAttribPointer(position, 2, GL_FLOAT, false, stride, overlay)
        overlay.position(2); glVertexAttribPointer(color, 4, GL_FLOAT, false, stride, overlay)
        overlay.position(6); glVertexAttribPointer(shape, 3, GL_FLOAT, false, stride, overlay)
        glEnableVertexAttribArray(position); glEnableVertexAttribArray(color); glEnableVertexAttribArray(shape)
        glDrawArrays(GL_TRIANGLES, 0, batch.vertices)
        glDisableVertexAttribArray(position); glDisableVertexAttribArray(color); glDisableVertexAttribArray(shape)
    }

    /** Links [name].frag after the [prelude] files, which hold code that several fragment shaders share. */
    private fun program(name: String, vertex: String = name, prelude: List<String> = emptyList()): Int {
        fun read(file: String) = context.assets.open("shaders/$file").bufferedReader().use { it.readText() }
        fun compile(type: Int, files: List<String>): Int {
            val shader = glCreateShader(type)
            glShaderSource(shader, files.joinToString("\n") { read(it) })
            glCompileShader(shader)
            val ok = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader ${files.last()} failed: ${glGetShaderInfoLog(shader)}" }
            return shader
        }
        val id = glCreateProgram()
        glAttachShader(id, compile(GL_VERTEX_SHADER, listOf("$vertex.vert")))
        glAttachShader(id, compile(GL_FRAGMENT_SHADER, prelude + "$name.frag"))
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program $name failed: ${glGetProgramInfoLog(id)}" }
        return id
    }

    /** Fixed-seed random bytes the shader's value noise reads instead of computing a hash. */
    private fun grainTexture(): Int {
        val random = java.util.Random(0x5eed)
        val bytes = ByteArray(256 * 256).also { random.nextBytes(it) }
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, 256, 256, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, ByteBuffer.wrap(bytes))
        return ids[0]
    }

    private fun texture(path: String, wrap: Int = GL_CLAMP_TO_EDGE): Int {
        val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it) }
            ?: error("Cannot decode $path")
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        Log.d(TAG, "Loaded $path ${bitmap.width}x${bitmap.height}")
        bitmap.recycle()
        return ids[0]
    }

    companion object {
        private const val TAG = "Weatherscape"
        /** Plate height over width (1152 x 2048). */
        const val ASPECT = 2048f / 1152f
        /** Width of the HTML preview's wall, the unit lens drops are sized in. */
        private const val PREVIEW_WIDTH_DP = 380f
        private const val LENS_SHOWER_SECONDS = 25.0
        private const val LENS_MIST_SECONDS = 15.0
        /** Time constant of condensation following the weather: it forms and clears over a minute or so. */
        private const val MIST_SECONDS = 25f
        /** The preview fogs the glass faster so it is ready to watch. */
        private const val MIST_PREVIEW_SECONDS = 3f

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values); position(0)
            }
    }
}
