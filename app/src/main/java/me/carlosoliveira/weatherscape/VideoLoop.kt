package me.carlosoliveira.weatherscape

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * Loops a silent video from assets into a GL external texture.
 * [create], [update] and [release] run on the GL thread, [close] when the GL thread is gone;
 * [playing] may be set from any thread.
 * The player itself only ever runs on the main thread.
 */
class VideoLoop(private val context: Context, private val asset: String) {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var prepared = false
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var fresh = false
    @Volatile private var closed = false

    var texture = 0
        private set
    /** Maps texture coordinates (origin bottom-left) into the decoder's buffer. */
    val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Play while the wallpaper is visible and moving; pause to hold the current frame. */
    @Volatile var playing = false
        set(value) {
            if (field == value) return
            field = value
            main.post { applyPlaying() }
        }

    /** Makes the texture and starts decoding into it. Call again after the GL context is recreated. */
    fun create() {
        release()
        texture = IntArray(1).also { glGenTextures(1, it, 0) }[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val target = SurfaceTexture(texture).apply { setOnFrameAvailableListener { fresh = true } }
        surfaceTexture = target
        val surface = Surface(target)
        main.post {
            if (closed) { surface.release(); return@post }
            player = MediaPlayer().apply {
                context.assets.openFd(asset).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setSurface(surface)
                surface.release()
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { prepared = true; applyPlaying() }
                setOnErrorListener { _, what, extra -> Log.w(TAG, "Video error $what/$extra"); false }
                prepareAsync()
            }
        }
    }

    /** Latches the newest decoded frame, if one arrived since the last call. */
    fun update() {
        val target = surfaceTexture ?: return
        if (!fresh) return
        fresh = false
        target.updateTexImage()
        target.getTransformMatrix(matrix)
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (texture != 0) glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        main.post {
            player?.release()
            player = null
            prepared = false
        }
    }

    /** Stops for good from the main thread, when the GL context goes away with the wallpaper. */
    fun close() {
        closed = true
        playing = false
        surfaceTexture?.release()
        surfaceTexture = null
        player?.release()
        player = null
        prepared = false
    }

    private fun applyPlaying() {
        val p = player ?: return
        if (!prepared) return
        if (playing && !p.isPlaying) p.start() else if (!playing && p.isPlaying) p.pause()
    }

    private companion object {
        const val TAG = "Weatherscape"
    }
}
