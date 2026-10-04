package com.tommasoberlose.anotherwidget.ui.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import androidx.viewbinding.ViewBinding
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.*
import com.tommasoberlose.anotherwidget.receivers.*
import com.tommasoberlose.anotherwidget.helpers.DebugLog
import com.tommasoberlose.anotherwidget.utils.toPixel
import java.lang.Exception
import kotlin.math.min


class MainWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        CalendarHelper.updateEventList(context)
        WeatherReceiver.setUpdates(context)
        MediaPlayerHelper.updatePlayingMediaInfo(context)

        if (Preferences.showEvents) {
            CalendarHelper.setEventUpdatesAndroidN(context)
        } else {
            CalendarHelper.removeEventUpdatesAndroidN(context)
        }
    }

    override fun onDisabled(context: Context) {
        if (getWidgetCount(context) == 0) {
            UpdatesReceiver.removeUpdates(context)
            WeatherReceiver.removeUpdates(context)
        }
    }

    companion object {

        fun updateWidget(context: Context, reason: String = "unspecified") {
            DebugLogger.log("WIDGET", "update_start", "reason" to reason)
            context.sendBroadcast(IntentHelper.getWidgetUpdateIntent(context))
        }

        fun getWidgetCount(context: Context): Int {
            val widgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, MainWidget::class.java)
            return widgetManager.getAppWidgetIds(widgetComponent).size
        }

        internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager,
                                     appWidgetId: Int) {
            val renderStartedAt = SystemClock.elapsedRealtime()

            DebugLogger.log("WIDGET", "render", "appWidgetId" to appWidgetId,
                "weatherAvailable" to (Preferences.showWeather && Preferences.weatherIcon != ""),
                "condition" to Preferences.weatherIcon,
                "temperature" to Preferences.weatherTemp,
                "unit" to Preferences.weatherRealTempUnit,
                "forecastTime" to (Preferences.weatherForecastTime.takeIf { it > 0 }
                    ?.let { DebugLog.timestamp(it) }))

            val displayMetrics = Resources.getSystem().displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            val dimensions = WidgetHelper.WidgetSizeProvider(context, appWidgetManager).getWidgetsSize(appWidgetId)

            WidgetHelper.runWithCustomTypeface(context) {
                val views = when (Preferences.widgetAlign) {
                    Constants.WidgetAlign.LEFT.rawValue -> AlignedWidget(context).generateWidget(appWidgetId, min(dimensions.first - 8.toPixel(context), min(width, height) - 16.toPixel(context)), it)
                    Constants.WidgetAlign.RIGHT.rawValue -> AlignedWidget(context, rightAligned = true).generateWidget(appWidgetId, min(dimensions.first - 8.toPixel(context), min(width, height) - 16.toPixel(context)), it)
                    else -> StandardWidget(context).generateWidget(appWidgetId, min(dimensions.first - 8.toPixel(context), min(width, height) - 16.toPixel(context)), it)
                }
                try {
                    if (views != null) appWidgetManager.updateAppWidget(appWidgetId, views)
                    DebugLogger.log("WIDGET", "update_complete", "appWidgetId" to appWidgetId,
                        "durationMs" to (SystemClock.elapsedRealtime() - renderStartedAt))
                } catch (ex: Exception) {
                    DebugLogger.log("WIDGET", "update_failed", "appWidgetId" to appWidgetId,
                        "exception" to ex.javaClass.simpleName)
                    ex.printStackTrace()
                }
            }
        }

        fun getWidgetView(context: Context, typeface: Typeface?): ViewBinding? {
            return when (Preferences.widgetAlign) {
                Constants.WidgetAlign.LEFT.rawValue -> AlignedWidget(context).generateWidgetView(typeface)
                Constants.WidgetAlign.RIGHT.rawValue -> AlignedWidget(context, rightAligned = true).generateWidgetView(typeface)
                else -> StandardWidget(context).generateWidgetView(typeface)
            }
        }
    }
}

