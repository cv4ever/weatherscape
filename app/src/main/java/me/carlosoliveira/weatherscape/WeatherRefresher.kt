package me.carlosoliveira.weatherscape

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.edit
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DeviceLocation {
    fun permitted(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A recent position, asking the phone for a new fix when the last one is over two hours old. */
    fun current(context: Context): Location? {
        if (!permitted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it in manager.allProviders && manager.isProviderEnabled(it) }
        val known = try {
            (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: SecurityException) { null }
        if (known != null && System.currentTimeMillis() - known.time < 2 * 3600_000L) return known
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (provider in providers) {
                var fix: Location? = null
                val latch = CountDownLatch(1)
                try {
                    manager.getCurrentLocation(provider, null, executor) { fix = it; latch.countDown() }
                } catch (_: SecurityException) { return known }
                latch.await(12, TimeUnit.SECONDS)
                fix?.let { return it }
            }
        } finally {
            executor.shutdown()
        }
        return known
    }

    @Suppress("DEPRECATION") // The async variant needs API 33; this runs on a worker thread.
    fun name(context: Context, location: Location): String = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    } catch (_: Exception) { null } ?: "Current location"
}

/**
 * Fetches weather off the main thread and keeps the last report in preferences for cold starts.
 * [onBusy] hears true when a fetch starts and false when it ends, on the worker thread.
 */
class WeatherRefresher(
    private val context: Context,
    private val onBusy: (Boolean) -> Unit = {},
    private val onReport: (WeatherReport) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val prefs = SettingsStore.prefs(context)

    fun cached(): WeatherReport? {
        val json = prefs.getString(SettingsStore.WEATHER_JSON, null) ?: return null
        return try { WeatherReport.parse(json, prefs.getLong(SettingsStore.WEATHER_FETCHED_AT, 0)) } catch (_: Exception) { null }
    }

    fun refreshIfStale() {
        val s = SettingsStore.read(context)
        val age = System.currentTimeMillis() - prefs.getLong(SettingsStore.WEATHER_FETCHED_AT, 0)
        val moved = prefs.getFloat("weather_latitude", 999f) != s.latitude.toFloat() ||
            prefs.getFloat("weather_longitude", 999f) != s.longitude.toFloat()
        // A fetch time in the future means the clock moved back; that report's age is unknown.
        if (age !in 0..STALE_MILLIS || moved) refresh()
    }

    fun refresh() {
        if (!running.compareAndSet(false, true)) return
        onBusy(true)
        worker.execute {
            try {
                var s = SettingsStore.read(context)
                if (s.useDeviceLocation) {
                    DeviceLocation.current(context)?.let { fix ->
                        s = s.copy(latitude = fix.latitude, longitude = fix.longitude, placeName = DeviceLocation.name(context, fix))
                        SettingsStore.writePlace(context, s.placeName, s.latitude, s.longitude)
                    }
                }
                val json = OpenMeteo.forecastJson(s.latitude, s.longitude)
                val now = System.currentTimeMillis()
                val report = WeatherReport.parse(json, now)
                prefs.edit {
                    putString(SettingsStore.WEATHER_JSON, json)
                    putLong(SettingsStore.WEATHER_FETCHED_AT, now)
                    putFloat("weather_latitude", s.latitude.toFloat())
                    putFloat("weather_longitude", s.longitude.toFloat())
                    remove(SettingsStore.WEATHER_ERROR)
                }
                onReport(report)
                ConditionsWidget.updateAll(context)
            } catch (e: Exception) {
                Log.w("Weatherscape", "Weather refresh failed", e)
                prefs.edit { putString(SettingsStore.WEATHER_ERROR, e.message ?: e.javaClass.simpleName) }
            } finally {
                running.set(false)
                onBusy(false)
            }
        }
    }

    fun close() = worker.shutdown()

    companion object {
        const val STALE_MILLIS = 30 * 60_000L
    }
}
