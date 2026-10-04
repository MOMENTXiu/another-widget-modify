package com.tommasoberlose.anotherwidget.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tommasoberlose.anotherwidget.global.Actions
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.DebugLogger
import com.tommasoberlose.anotherwidget.helpers.WeatherHelper
import com.tommasoberlose.anotherwidget.services.LocationService
import java.util.*

/**
 * The scheduler of the LocalLocationChange check: roughly once an hour it asks LocationService
 * whether the user has moved to another weather region. It is a plain inexact repeating alarm -
 * weather positioning is not an exact-alarm use case - and it never requests a location itself.
 */
class LocationChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DebugLogger.d("LocationChangeReceiver", "onReceive action=${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                DebugLogger.d("LocationChangeReceiver", "rescheduling after ${intent.action}")
                setUpdates(context)
            }

            Actions.ACTION_LOCATION_CHANGE ->
                WeatherHelper.requestLocationChangeCheck(context, trigger = LocationService.TRIGGER_SCHEDULED)
        }
    }

    companion object {
        fun setUpdates(context: Context) {
            removeUpdates(context)

            if (Preferences.showWeather) {
                val firstRun = System.currentTimeMillis() + Constants.LOCATION_CHECK_INTERVAL_MS
                with(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager) {
                    DebugLogger.d("LocationChangeReceiver",
                        "schedule hourly location change checks intervalMs=${Constants.LOCATION_CHECK_INTERVAL_MS}")
                    setRepeating(
                        AlarmManager.RTC,
                        firstRun,
                        Constants.LOCATION_CHECK_INTERVAL_MS,
                        PendingIntent.getBroadcast(
                            context,
                            0,
                            Intent(context, LocationChangeReceiver::class.java).apply { action = Actions.ACTION_LOCATION_CHANGE },
                            0
                        )
                    )
                }
            } else {
                DebugLogger.d("LocationChangeReceiver", "weather disabled, no location change checks scheduled")
            }
        }

        fun removeUpdates(context: Context) {
            with(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager) {
                DebugLogger.d("LocationChangeReceiver", "cancel location change checks")
                cancel(
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, LocationChangeReceiver::class.java).apply { action = Actions.ACTION_LOCATION_CHANGE },
                        0
                    )
                )
            }
        }
    }
}
