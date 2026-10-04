package com.tommasoberlose.anotherwidget.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.*
import androidx.core.content.ContextCompat
import com.chibatching.kotpref.Kotpref
import com.tommasoberlose.anotherwidget.R
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.DebugLogger
import com.tommasoberlose.anotherwidget.helpers.LocationHelper
import com.tommasoberlose.anotherwidget.network.WeatherNetworkApi
import com.tommasoberlose.anotherwidget.ui.activities.MainActivity
import com.tommasoberlose.anotherwidget.ui.fragments.MainFragment
import com.tommasoberlose.anotherwidget.utils.checkGrantedPermission
import com.tommasoberlose.anotherwidget.utils.ignoreExceptions
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import kotlin.coroutines.resume

class LocationService : Service() {

    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(LOCATION_ACCESS_NOTIFICATION_ID, getLocationAccessNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(LOCATION_ACCESS_NOTIFICATION_ID, getLocationAccessNotification())
        job?.cancel()
        job = GlobalScope.launch(Dispatchers.IO) {
            updateWeather()
            withContext(Dispatchers.Main) {
                stopSelf()
            }
            EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
        }
        return START_STICKY
    }

    /**
     * Cache first: a recent coordinate is reused as is, so the location providers stay off for most
     * refreshes. Only a stale or missing coordinate triggers a fix, and a failed fix still leaves the
     * weather refresh running on the previously stored coordinate.
     */
    private suspend fun updateWeather() {
        Kotpref.init(this)

        DebugLogger.log("LOCATION", "flow_start",
            "trigger" to "weather_refresh",
            "manual" to (Preferences.customLocationAdd != ""),
            "cachedAgeMs" to LocationHelper.ageMs())

        if (!LocationHelper.isCachedLocationFresh()) {
            acquireLocation()
        }

        if (LocationHelper.hasCachedLocation()) {
            WeatherNetworkApi(this@LocationService).updateWeather()
        } else {
            Preferences.weatherProviderLocationError = getString(R.string.weather_provider_error_missing_location)
        }
    }

    private suspend fun acquireLocation() {
        val fine = checkGrantedPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = checkGrantedPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = checkGrantedPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        if (!fine) {
            Log.d(Constants.LOG_TAG, "location refresh skipped: permission denied")
            DebugLogger.log("LOCATION", "request_failed", "reason" to "permission_denied",
                "fine" to fine, "coarse" to coarse, "background" to background)
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        // Providers are not all available on every device, so they are checked at runtime.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { isProviderEnabled(locationManager, it) }

        // Purely diagnostic: what the system offers on this device, including the providers this
        // app deliberately does not use.
        locationManager.allProviders.forEach { provider ->
            DebugLogger.log("LOCATION", "provider_state",
                "provider" to provider,
                "available" to true,
                "enabled" to isProviderEnabled(locationManager, provider))
        }
        DebugLogger.log("LOCATION", "permission",
            "fine" to fine, "coarse" to coarse, "background" to background)

        if (providers.isEmpty()) {
            Log.d(Constants.LOG_TAG, "location refresh skipped: no provider enabled")
            DebugLogger.log("LOCATION", "request_failed", "reason" to "provider_disabled",
                "providers" to locationManager.allProviders.joinToString())
            return
        }

        logLastKnownCandidates(locationManager)

        Log.d(Constants.LOG_TAG, "location refresh attempted on ${providers.joinToString()}")
        DebugLogger.log("LOCATION", "request_start",
            "provider" to providers.joinToString("+"),
            "timeoutMs" to Constants.LOCATION_ACQUISITION_TIMEOUT)

        val startedAt = System.currentTimeMillis()
        val fix = withTimeoutOrNull(Constants.LOCATION_ACQUISITION_TIMEOUT) { awaitSingleUpdate(locationManager, providers) }

        if (fix != null) {
            Log.d(Constants.LOG_TAG, "location refresh succeeded")
            DebugLogger.log("LOCATION", "result",
                "provider" to fix.provider,
                "lat" to fix.latitude,
                "lon" to fix.longitude,
                "accuracy" to fix.accuracy,
                "locationTime" to fix.time,
                "ageMs" to (System.currentTimeMillis() - fix.time),
                "durationMs" to (System.currentTimeMillis() - startedAt))

            LocationHelper.saveLocation(fix.latitude, fix.longitude, fix.time)
            DebugLogger.log("LOCATION", "selected", "source" to "fresh_fix", "provider" to fix.provider,
                "accuracy" to fix.accuracy)
            return
        }

        Log.d(Constants.LOG_TAG, "location refresh failed, falling back to the stored coordinates")
        DebugLogger.log("LOCATION", "request_failed", "reason" to "timeout",
            "timeoutMs" to Constants.LOCATION_ACQUISITION_TIMEOUT)

        // Timed out: reuse the best last known position, but only if it is newer than the cache.
        val lastKnown = providers
            .mapNotNull { lastKnownLocation(locationManager, it) }
            .maxByOrNull { it.time }

        if (lastKnown != null && lastKnown.time > Preferences.lastLocationTimestamp) {
            DebugLogger.log("LOCATION", "fallback",
                "from" to providers.joinToString("+"),
                "to" to lastKnown.provider,
                "reason" to "timeout")
            LocationHelper.saveLocation(lastKnown.latitude, lastKnown.longitude, lastKnown.time)
            DebugLogger.log("LOCATION", "selected", "source" to "last_known", "provider" to lastKnown.provider,
                "accuracy" to lastKnown.accuracy, "ageMs" to (System.currentTimeMillis() - lastKnown.time))
        } else {
            DebugLogger.log("LOCATION", "selected", "source" to "stale_cache",
                "cachedAgeMs" to LocationHelper.ageMs())
        }
    }

    /** Diagnostics only: what the system remembers for each provider, used or not. */
    private fun logLastKnownCandidates(locationManager: LocationManager) {
        listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
            "fused", LocationManager.PASSIVE_PROVIDER
        ).forEach { provider ->
            val fix = lastKnownLocation(locationManager, provider)
            if (fix == null) {
                DebugLogger.log("LOCATION", "last_known", "provider" to provider, "result" to null)
            } else {
                DebugLogger.log("LOCATION", "last_known",
                    "provider" to provider,
                    "accuracy" to fix.accuracy,
                    "ageMs" to (System.currentTimeMillis() - fix.time),
                    "lat" to fix.latitude,
                    "lon" to fix.longitude)
            }
        }
    }

    private suspend fun awaitSingleUpdate(locationManager: LocationManager, providers: List<String>): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) {
                        ignoreExceptions { locationManager.removeUpdates(this) }
                        continuation.resume(location)
                    }
                }

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {}

                @Suppress("DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            continuation.invokeOnCancellation {
                ignoreExceptions { locationManager.removeUpdates(listener) }
            }

            providers.forEach { provider ->
                ignoreExceptions {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
        }

    private fun isProviderEnabled(locationManager: LocationManager, provider: String): Boolean = try {
        locationManager.isProviderEnabled(provider)
    } catch (ex: Exception) {
        false
    }

    private fun lastKnownLocation(locationManager: LocationManager, provider: String): Location? = try {
        locationManager.getLastKnownLocation(provider)
    } catch (ex: Exception) {
        null
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        job = null
    }

    companion object {
        const val LOCATION_ACCESS_NOTIFICATION_ID = 28465

        @JvmStatic
        fun requestNewLocation(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun getLocationAccessNotification(): Notification {
        with(NotificationManagerCompat.from(this)) {
            // Create channel
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                createNotificationChannel(
                    NotificationChannel(
                        getString(R.string.location_access_notification_channel_id),
                        getString(R.string.location_access_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = getString(R.string.location_access_notification_channel_description)
                    }
                )
            }

            val builder = NotificationCompat.Builder(this@LocationService, getString(R.string.location_access_notification_channel_id))
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(getString(R.string.location_access_notification_title))
                .setOngoing(true)
                .setColor(ContextCompat.getColor(this@LocationService, R.color.colorAccent))

            // Main intent that open the activity
            builder.setContentIntent(PendingIntent.getActivity(this@LocationService, 0, Intent(this@LocationService, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT))

            return builder.build()
        }
    }
}
