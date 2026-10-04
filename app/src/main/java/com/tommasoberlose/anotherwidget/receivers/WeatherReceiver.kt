package com.tommasoberlose.anotherwidget.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tommasoberlose.anotherwidget.global.Actions
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.DebugLog
import com.tommasoberlose.anotherwidget.helpers.DebugLogger
import com.tommasoberlose.anotherwidget.helpers.WeatherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*


class WeatherReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DebugLogger.d("WeatherReceiver", "onReceive action=${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                DebugLogger.d("WeatherReceiver", "rescheduling weather updates after ${intent.action}")
                setUpdates(context)
            }

            Actions.ACTION_WEATHER_UPDATE -> {
                val flowId = DebugLog.newFlowId()
                DebugLogger.d("WeatherReceiver", "scheduled weather refresh starting flow=$flowId")
                GlobalScope.launch(Dispatchers.IO) {
                    WeatherHelper.refreshWeatherData(context, flowId, trigger = "weather_ttl")
                }
            }
        }
    }

    companion object {
        private const val MINUTE = 60 * 1000L
        fun setUpdates(context: Context) {
            removeUpdates(context)

            if (Preferences.showWeather) {
                val interval = MINUTE * when (Preferences.weatherRefreshPeriod) {
                    0 -> 30
                    1 -> 60
                    2 -> 60L * 3
                    3 -> 60L * 6
                    4 -> 60L * 12
                    5 -> 60L * 24
                    else -> 60
                }
                with(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager) {
                    DebugLogger.d("WeatherReceiver", "schedule repeating weather updates intervalMs=$interval")
                    setRepeating(
                        AlarmManager.RTC,
                        Calendar.getInstance().timeInMillis,
                        interval,
                        PendingIntent.getBroadcast(context, 0, Intent(context, WeatherReceiver::class.java).apply { action = Actions.ACTION_WEATHER_UPDATE }, 0)
                    )
                }
            } else {
                DebugLogger.d("WeatherReceiver", "weather disabled, no weather updates scheduled")
            }
        }

        fun setOneTimeUpdate(context: Context) {
            if (Preferences.showWeather) {
                DebugLogger.d("WeatherReceiver", "schedule one time weather updates in 10/20/30 minutes")
                listOf(10, 20, 30).forEach {
                    with(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager) {
                        setExactAndAllowWhileIdle(
                            AlarmManager.RTC,
                            it * MINUTE,
                            PendingIntent.getBroadcast(context, it, Intent(context, WeatherReceiver::class.java).apply { action = Actions.ACTION_WEATHER_UPDATE }, 0)
                        )
                    }
                }
            }
        }

        fun removeUpdates(context: Context) {
            with(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager) {
                DebugLogger.d("WeatherReceiver", "cancel weather updates")
                cancel(PendingIntent.getBroadcast(context, 0, Intent(context, WeatherReceiver::class.java).apply { action = Actions.ACTION_WEATHER_UPDATE }, 0))
                listOf(10, 20, 30).forEach {
                    cancel(PendingIntent.getBroadcast(context, it, Intent(context, WeatherReceiver::class.java).apply { action = Actions.ACTION_WEATHER_UPDATE }, 0))
                }
            }
        }
    }
}
