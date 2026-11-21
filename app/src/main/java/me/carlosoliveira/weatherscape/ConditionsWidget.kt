package me.carlosoliveira.weatherscape

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.util.Date

/**
 * Home-screen widget: the conditions the wallpaper is showing and the next change in the forecast,
 * so a darkening valley comes with its reason ("Rain from 16:00"). It reads the cached report;
 * the wallpaper and the settings screen fetch new weather and refresh it.
 */
class ConditionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        /** Redraws every placed widget from the cached report. Safe to call from any thread. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ConditionsWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = SettingsStore.prefs(context)
            val settings = SettingsStore.read(context)
            val report = prefs.getString(SettingsStore.WEATHER_JSON, null)?.let { json ->
                runCatching { WeatherReport.parse(json, prefs.getLong(SettingsStore.WEATHER_FETCHED_AT, 0)) }.getOrNull()
            }
            val nowSeconds = System.currentTimeMillis() / 1000
            val views = RemoteViews(context.packageName, R.layout.widget_conditions)
            views.setTextViewText(R.id.widget_place, settings.placeName)
            if (report == null) {
                views.setTextViewText(R.id.widget_now, "Waiting for weather")
                views.setTextViewText(R.id.widget_next, "Open Weatherscape to fetch it")
            } else {
                views.setTextViewText(R.id.widget_now, report.current.describe())
                views.setTextViewText(R.id.widget_next, nextLine(context, report, nowSeconds))
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, SettingsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }

        private fun nextLine(context: Context, report: WeatherReport, nowSeconds: Long): String {
            val change = report.nextChange(nowSeconds) ?: return "No change for ${report.forecastHours(nowSeconds)} hours"
            val (kind, at) = change
            val time = DateFormat.getTimeFormat(context).format(Date(at * 1000))
            return phrase(report.current.kind, kind) + " " + time
        }

        /** Words for a change from [from] to [to], ending where the time follows. */
        private fun phrase(from: Sky, to: Sky): String {
            val wet = from == Sky.RAIN || from == Sky.SNOW || from == Sky.STORM
            return when (to) {
                Sky.RAIN -> if (from == Sky.STORM) "Storm easing to rain at" else "Rain from"
                Sky.SNOW -> "Snow from"
                Sky.STORM -> "Thunderstorms from"
                Sky.FOG -> "Fog from"
                Sky.CLOUDY -> if (wet) "Dry from" else "Clouding over at"
                Sky.CLEAR -> "Clearing from"
            }
        }
    }
}
