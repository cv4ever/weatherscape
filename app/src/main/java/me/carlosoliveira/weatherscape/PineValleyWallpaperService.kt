package me.carlosoliveira.weatherscape

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import kotlin.math.roundToInt

class PineValleyWallpaperService : WallpaperService() {
    private val engines = mutableSetOf<SceneEngine>()

    override fun onCreateEngine(): Engine = SceneEngine().also { engines += it }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding or unfolding changes the display size; the fixed render size must follow.
        engines.forEach { it.applyRenderSize() }
    }

    inner class SceneEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, Choreographer.FrameCallback {
        private val prefs = SettingsStore.prefs(this@PineValleyWallpaperService)
        private val director = SceneDirector()
        private val renderer = SceneRenderer(applicationContext, director)
        private val refresher = WeatherRefresher(applicationContext) { director.weather = it }
        private var view: SceneView? = null
        private var settings = Settings()
        private var visible = false
        private var lastFrameNanos = 0L
        private var handledLensPreviewAt = System.currentTimeMillis()
        private var handledPreviewAt = System.currentTimeMillis()
        private var handledRefreshAt = System.currentTimeMillis()
        private var nightColors: Boolean? = null
        private val power = getSystemService(PowerManager::class.java)
        private val tapPoint = FloatArray(2)

        /** USER_PRESENT arrives once the keyguard is dismissed: the reveal plays as the home screen appears. */
        private val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val now = SystemClock.elapsedRealtimeNanos() / 1e9
                view?.queueEvent { director.onUnlock(now) }
            }
        }

        /** GLSurfaceView drawing into the wallpaper's surface instead of a window's. */
        private inner class SceneView : GLSurfaceView(this@PineValleyWallpaperService) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun destroy() = super.onDetachedFromWindow()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(true)
            applySettings()
            director.weather = refresher.cached()
            view = SceneView().apply {
                setEGLContextClientVersion(2)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
            prefs.registerOnSharedPreferenceChangeListener(this)
            // Preview engines (the picker) have no keyguard to dismiss.
            if (!isPreview) registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            if (!isPreview) unregisterReceiver(unlockReceiver)
            Choreographer.getInstance().removeFrameCallback(this)
            view?.destroy()
            refresher.close()
            engines -= this
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            applyRenderSize()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            val view = view ?: return
            if (visible) {
                applyRenderSize()
                view.onResume()
                val now = SystemClock.elapsedRealtimeNanos() / 1e9
                view.queueEvent { director.onShown(now) }
                checkPreview()
                checkLensPreview()
                refresher.refreshIfStale()
                lastFrameNanos = 0
                view.requestRender()
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                Choreographer.getInstance().removeFrameCallback(this)
                view.onPause()
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            renderer.pan = if (settings.parallax) xOffset else .5f
            if (!visible) return
            view?.requestRender()
        }

        /** The launcher reports taps on empty home-screen space; icons and widgets keep their own taps. */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && visible && settings.tapToWake) {
                val now = SystemClock.elapsedRealtimeNanos() / 1e9
                val surface = surfaceHolder.surfaceFrame
                // The launcher reports display pixels; the surface may render at a lower scale.
                val display = displayContext?.resources?.displayMetrics
                val sx = if (display != null) x * surface.width().toFloat() / display.widthPixels else x.toFloat()
                val sy = if (display != null) y * surface.height().toFloat() / display.heightPixels else y.toFloat()
                view?.queueEvent {
                    renderer.plateAt(sx, sy, tapPoint)
                    director.onTap(now, tapPoint[0], tapPoint[1])
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        /**
         * Paces rendering to the chosen frame rate; static modes redraw only every 30 seconds. When the
         * valley only drifts, slow fog and clouds look the same at [CALM_FPS], so the calm saver uses it.
         */
        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            val still = settings.reducedMotion || (settings.pauseOnBatterySaver && power?.isPowerSaveMode == true)
            val rate = if (settings.calmSaver && !renderer.busy) minOf(settings.frameRate, CALM_FPS) else settings.frameRate
            val interval = if (still) 30_000_000_000L else 1_000_000_000L / rate.coerceIn(10, 60)
            if (frameTimeNanos - lastFrameNanos >= interval - 2_000_000) {
                lastFrameNanos = frameTimeNanos
                view?.requestRender()
                updateColors()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            applySettings()
            when (key) {
                SettingsStore.PREVIEW_REQUESTED_AT -> if (visible) checkPreview()
                SettingsStore.LENS_PREVIEW_AT -> if (visible) checkLensPreview()
                SettingsStore.REFRESH_REQUESTED_AT -> {
                    val at = prefs.getLong(key, 0)
                    if (at > handledRefreshAt) { handledRefreshAt = at; refresher.refresh() }
                }
                "latitude", "longitude", "use_device_location" -> refresher.refreshIfStale()
                SettingsStore.WEATHER_JSON -> director.weather = refresher.cached()
            }
            if (visible) view?.requestRender()
        }

        private fun applySettings() {
            settings = SettingsStore.read(this@PineValleyWallpaperService)
            director.settings = settings
            renderer.dim = settings.dimPercent / 100f
            if (!settings.parallax) renderer.pan = .5f
            applyRenderSize()
        }

        /** Plays a moment the settings screen asked for, if the request is still recent. */
        private fun checkPreview() {
            val at = prefs.getLong(SettingsStore.PREVIEW_REQUESTED_AT, 0)
            if (at <= handledPreviewAt || System.currentTimeMillis() - at > 5 * 60_000) return
            handledPreviewAt = at
            val kind = prefs.getString(SettingsStore.PREVIEW_MOMENT, null)
                ?.let { name -> MomentKind.entries.firstOrNull { it.name == name } } ?: return
            val now = SystemClock.elapsedRealtimeNanos() / 1e9
            view?.queueEvent { director.preview(kind, now) }
        }

        /** Starts a glass effect preview the settings screen asked for, if the request is still recent. */
        private fun checkLensPreview() {
            val at = prefs.getLong(SettingsStore.LENS_PREVIEW_AT, 0)
            if (at <= handledLensPreviewAt || System.currentTimeMillis() - at > 5 * 60_000) return
            handledLensPreviewAt = at
            val kind = prefs.getString(SettingsStore.LENS_PREVIEW_KIND, null)
                ?.let { name -> LensPreview.entries.firstOrNull { it.name == name } } ?: return
            val now = SystemClock.elapsedRealtimeNanos() / 1e9
            view?.queueEvent { renderer.previewLens(kind, now) }
        }

        /** Renders below native resolution and lets the compositor scale up; the plate is softer than the panel anyway. */
        fun applyRenderSize() {
            val holder = surfaceHolder ?: return
            val scale = settings.quality.renderScale
            if (scale >= 1f) { holder.setSizeFromLayout(); return }
            @Suppress("DEPRECATION")
            val display = displayContext?.display ?: return
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val width = (size.x * scale).roundToInt()
            val height = (size.y * scale).roundToInt()
            val frame = holder.surfaceFrame
            if (frame.width() != width || frame.height() != height) holder.setFixedSize(width, height)
        }

        private fun updateColors() {
            val night = renderer.lastSunAltitude < -4
            if (night != nightColors) {
                nightColors = night
                notifyColorsChanged()
            }
        }

        override fun onComputeColors(): WallpaperColors {
            val night = nightColors ?: (renderer.lastSunAltitude < -4)
            val primary = Color.valueOf(if (night) 0xff1c2a44.toInt() else 0xff9fb6c6.toInt())
            val secondary = Color.valueOf(if (night) 0xff13231c.toInt() else 0xff2f4a3c.toInt())
            val tertiary = Color.valueOf(if (night) 0xff3a4a6a.toInt() else 0xff6f8a78.toInt())
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The sky behind the clock is pale by day, so dark text reads there.
                WallpaperColors(primary, secondary, tertiary, if (night) 0 else WallpaperColors.HINT_SUPPORTS_DARK_TEXT)
            } else {
                WallpaperColors(primary, secondary, tertiary)
            }
        }
    }

    companion object {
        private const val CALM_FPS = 15
    }
}
