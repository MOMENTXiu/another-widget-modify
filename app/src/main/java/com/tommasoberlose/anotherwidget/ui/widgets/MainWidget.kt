package com.tommasoberlose.anotherwidget.ui.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

    /** Flow id carried by the update broadcast, read in [onReceive] and consumed in [onUpdate]. */
    private var broadcastFlowId: String? = null

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            broadcastFlowId = intent.getStringExtra(IntentHelper.FLOW_ID_EXTRA)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)?.toList()
            DebugLogger.d("WidgetProvider", "onReceive action=${intent.action} appWidgetIds=$ids")
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val flow = broadcastFlowId ?: DebugLog.newFlowId()
        broadcastFlowId = null
        DebugLogger.d("WidgetProvider", "onUpdate appWidgetIds=${appWidgetIds.toList()} flow=$flow")
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, flow)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId, DebugLog.newFlowId())
    }

    override fun onEnabled(context: Context) {
        CalendarHelper.updateEventList(context)
        WeatherReceiver.setUpdates(context)
        LocationChangeReceiver.setUpdates(context)
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
            LocationChangeReceiver.removeUpdates(context)
        }
    }

    companion object {

        fun updateWidget(context: Context, reason: String = "unspecified", flowId: String = DebugLog.newFlowId()) {
            val widgets = getWidgetCount(context)
            DebugLogger.d("WidgetProvider", "update requested reason=$reason widgets=$widgets flow=$flowId")
            if (widgets == 0) {
                DebugLogger.w("WidgetProvider", "no widget is bound, the update will render nothing reason=$reason")
            }
            context.sendBroadcast(IntentHelper.getWidgetUpdateIntent(context, flowId))
        }

        fun getWidgetCount(context: Context): Int {
            val widgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, MainWidget::class.java)
            return widgetManager.getAppWidgetIds(widgetComponent).size
        }

        internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager,
                                     appWidgetId: Int, flowId: String) {
            val renderStartedAt = SystemClock.elapsedRealtime()

            DebugLogger.d("WidgetUpdater", "update start appWidgetId=$appWidgetId flow=$flowId")
            DebugLogger.d("WidgetUpdater",
                "weather appWidgetId=$appWidgetId available=${Preferences.showWeather && Preferences.weatherIcon != ""} " +
                    "condition=${Preferences.weatherIcon} temperature=${Preferences.weatherTemp} " +
                    "unit=${Preferences.weatherRealTempUnit} " +
                    "forecastTime=${Preferences.weatherForecastTime.takeIf { it > 0 }?.let { DebugLog.timestamp(it) }}")

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
                    if (views != null) {
                        DebugLogger.d("WidgetUpdater", "apply RemoteViews appWidgetId=$appWidgetId")
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                    DebugLogger.d("WidgetUpdater",
                        "update complete appWidgetId=$appWidgetId durationMs=${SystemClock.elapsedRealtime() - renderStartedAt}")
                } catch (ex: Exception) {
                    DebugLogger.e("WidgetUpdater", "update failed appWidgetId=$appWidgetId", ex)
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

