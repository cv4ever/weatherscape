package me.carlosoliveira.weatherscape

import android.content.Context
import android.graphics.BitmapFactory
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

/**
 * Draws Pine Valley: one full-screen pass for the landscape, then rain and moments as overlays.
 * Everything runs on the GL thread; [pan] and [dim] are written from the main thread.
 */
class SceneRenderer(private val context: Context, val director: SceneDirector) : GLSurfaceView.Renderer {
    @Volatile var pan = .5f
    @Volatile var dim = 0f
    /** Sun altitude of the last drawn frame, for the system's wallpaper colour hints. */
    @Volatile var lastSunAltitude = 0.0
        private set

    private val frame = Frame()
    private val painter = OverlayPainter(ASPECT)
    private val quad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val overlay = floatBuffer(FloatArray(painter.effects.data.size))

    private var landscape = 0
    private var effects = 0
    private var plate = 0
    private var stars = 0
    private var birdAtlas = 0
    private var squirrelAtlas = 0
    private var grain = 0
    private val landscapeUniforms = HashMap<String, Int>()
    private val effectUniforms = HashMap<String, Int>()
    private var width = 1
    private var height = 1
    private val crop = FloatArray(4)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        landscape = program("landscape")
        effects = program("overlay")
        for (name in listOf("cropOffset", "cropScale", "landscape", "stars", "grain", "phase", "flow", "gust", "flash", "sunbeam",
            "rainbow", "dim", "air", "ground", "sky", "horizonColor", "mist", "light")) {
            landscapeUniforms[name] = glGetUniformLocation(landscape, name)
        }
        for (name in listOf("cropOffset", "cropScale", "plateAspect", "atlas", "dim")) {
            effectUniforms[name] = glGetUniformLocation(effects, name)
        }
        plate = texture("textures/pine_valley.webp")
        stars = texture("textures/milky_way.webp")
        birdAtlas = texture("textures/bird_atlas.webp")
        squirrelAtlas = texture("textures/squirrel_atlas.webp")
        grain = grainTexture()
        glDisable(GL_DEPTH_TEST)
        glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        director.update(SystemClock.elapsedRealtimeNanos() / 1e9, System.currentTimeMillis(), frame)
        lastSunAltitude = frame.sunAltitude
        computeCrop()
        drawLandscape()
        painter.paint(frame)
        drawOverlays()
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
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, plate); glUniform1i(u.getValue("landscape"), 0)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, grain); glUniform1i(u.getValue("grain"), 2)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, stars); glUniform1i(u.getValue("stars"), 1)
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
        val corner = glGetAttribLocation(landscape, "corner")
        quad.position(0)
        glEnableVertexAttribArray(corner)
        glVertexAttribPointer(corner, 2, GL_FLOAT, false, 0, quad)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(corner)
    }

    private fun drawOverlays() {
        if (painter.effects.vertices + painter.birds.vertices + painter.squirrels.vertices == 0) return
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
        draw(painter.effects, birdAtlas)
        draw(painter.birds, birdAtlas)
        draw(painter.squirrels, squirrelAtlas)
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

    private fun program(name: String): Int {
        fun compile(type: Int, file: String): Int {
            val source = context.assets.open("shaders/$file").bufferedReader().use { it.readText() }
            val shader = glCreateShader(type)
            glShaderSource(shader, source)
            glCompileShader(shader)
            val ok = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader $file failed: ${glGetShaderInfoLog(shader)}" }
            return shader
        }
        val id = glCreateProgram()
        glAttachShader(id, compile(GL_VERTEX_SHADER, "$name.vert"))
        glAttachShader(id, compile(GL_FRAGMENT_SHADER, "$name.frag"))
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

    private fun texture(path: String): Int {
        val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it) }
            ?: error("Cannot decode $path")
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
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

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values); position(0)
            }
    }
}
