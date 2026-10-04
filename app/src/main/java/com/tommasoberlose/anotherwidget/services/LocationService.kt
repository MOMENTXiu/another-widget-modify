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
import androidx.core.app.*
import androidx.core.content.ContextCompat
import com.chibatching.kotpref.Kotpref
import com.tommasoberlose.anotherwidget.R
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.DebugLog
import com.tommasoberlose.anotherwidget.helpers.IntentHelper
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
        DebugLogger.d("LocationService", "onCreate")
        startForeground(LOCATION_ACCESS_NOTIFICATION_ID, getLocationAccessNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val flowId = intent?.getStringExtra(IntentHelper.FLOW_ID_EXTRA) ?: DebugLog.newFlowId()
        DebugLogger.d("LocationService", "onStartCommand flow=$flowId startId=$startId")
        startForeground(LOCATION_ACCESS_NOTIFICATION_ID, getLocationAccessNotification())
        job?.cancel()
        job = GlobalScope.launch(Dispatchers.IO) {
            updateWeather(flowId)
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
    private suspend fun updateWeather(flowId: String) {
        Kotpref.init(this)

        DebugLogger.d("LocationService",
            "location flow start flow=$flowId manual=${Preferences.customLocationAdd != ""} cachedAgeMs=${LocationHelper.ageMs()}")

        if (!LocationHelper.isCachedLocationFresh()) {
            acquireLocation(flowId)
        } else {
            DebugLogger.d("LocationCache",
                "hit ageMs=${LocationHelper.ageMs()} ttlMs=${Constants.LOCATION_CACHE_TTL} (raced, providers stay off) flow=$flowId")
        }

        if (LocationHelper.hasCachedLocation()) {
            WeatherNetworkApi(this@LocationService).updateWeather(flowId)
        } else {
            DebugLogger.w("LocationService", "location flow failed reason=no_location flow=$flowId")
            Preferences.weatherProviderLocationError = getString(R.string.weather_provider_error_missing_location)
        }
    }

    private suspend fun acquireLocation(flowId: String) {
        val fine = checkGrantedPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = checkGrantedPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = checkGrantedPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        DebugLogger.d("LocationService",
            "checkSelfPermission ACCESS_FINE_LOCATION=${granted(fine)} ACCESS_COARSE_LOCATION=${granted(coarse)} " +
                "ACCESS_BACKGROUND_LOCATION=${granted(background)} flow=$flowId")

        if (!fine) {
            DebugLogger.w("LocationService", "location request failed reason=no_permission flow=$flowId")
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            DebugLogger.w("LocationService", "location request failed reason=no_location_manager flow=$flowId")
            return
        }

        // Providers are not all available on every device, so they are checked at runtime.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { isProviderEnabled(locationManager, it, flowId) }

        // Purely diagnostic: what the system offers on this device, including the providers this
        // app deliberately does not use.
        DebugLogger.d("LocationService",
            "getProviders(all)=${locationManager.allProviders.joinToString(prefix = "[", postfix = "]")} flow=$flowId")

        if (providers.isEmpty()) {
            DebugLogger.w("LocationService", "location request failed reason=provider_disabled flow=$flowId")
            return
        }

        logLastKnownCandidates(locationManager, flowId)

        DebugLogger.d("LocationService",
            "requestLocationUpdates providers=${providers.joinToString(prefix = "[", postfix = "]")} " +
                "timeoutMs=${Constants.LOCATION_ACQUISITION_TIMEOUT} flow=$flowId")

        val startedAt = System.currentTimeMillis()
        val fix = withTimeoutOrNull(Constants.LOCATION_ACQUISITION_TIMEOUT) { awaitSingleUpdate(locationManager, providers, flowId) }

        if (fix != null) {
            DebugLogger.d("LocationService",
                "location selected source=fresh_fix provider=${fix.provider} lat=${fix.latitude} lon=${fix.longitude} " +
                    "accuracy=${fix.accuracy} ageMs=${System.currentTimeMillis() - fix.time} " +
                    "durationMs=${System.currentTimeMillis() - startedAt} flow=$flowId")

            LocationHelper.saveLocation(fix.latitude, fix.longitude, fix.time, source = fix.provider, flowId = flowId)
            return
        }

        DebugLogger.w("LocationService",
            "location request timeout durationMs=${System.currentTimeMillis() - startedAt} flow=$flowId")

        // Timed out: reuse the best last known position, but only if it is newer than the cache.
        val lastKnown = providers
            .mapNotNull { lastKnownLocation(locationManager, it) }
            .maxByOrNull { it.time }

        if (lastKnown != null && lastKnown.time > Preferences.lastLocationTimestamp) {
            DebugLogger.d("LocationService",
                "location fallback from=${providers.joinToString("+")} to=${lastKnown.provider} reason=timeout flow=$flowId")
            LocationHelper.saveLocation(lastKnown.latitude, lastKnown.longitude, lastKnown.time,
                source = "last_known:${lastKnown.provider}", flowId = flowId)
            DebugLogger.d("LocationService",
                "location selected source=last_known provider=${lastKnown.provider} accuracy=${lastKnown.accuracy} " +
                    "ageMs=${System.currentTimeMillis() - lastKnown.time} flow=$flowId")
        } else {
            DebugLogger.d("LocationService",
                "location selected source=stale_cache cachedAgeMs=${LocationHelper.ageMs()} flow=$flowId")
        }
    }

    private fun granted(granted: Boolean): String = if (granted) "GRANTED" else "DENIED"

    /** Diagnostics only: what the system remembers for each provider, used or not. */
    private fun logLastKnownCandidates(locationManager: LocationManager, flowId: String) {
        listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
            "fused", LocationManager.PASSIVE_PROVIDER
        ).forEach { provider ->
            val fix = lastKnownLocation(locationManager, provider)
            if (fix == null) {
                DebugLogger.d("LocationService", "getLastKnownLocation provider=$provider result=null flow=$flowId")
            } else {
                DebugLogger.d("LocationService",
                    "getLastKnownLocation provider=$provider lat=${fix.latitude} lon=${fix.longitude} " +
                        "accuracy=${fix.accuracy} ageMs=${System.currentTimeMillis() - fix.time} flow=$flowId")
            }
        }
    }

    private suspend fun awaitSingleUpdate(
        locationManager: LocationManager,
        providers: List<String>,
        flowId: String
    ): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) {
                        DebugLogger.d("LocationService",
                            "onLocationChanged provider=${location.provider} lat=${location.latitude} lon=${location.longitude} " +
                                "accuracy=${location.accuracy} flow=$flowId")
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
                DebugLogger.d("LocationService", "location request cancelled flow=$flowId")
                ignoreExceptions { locationManager.removeUpdates(listener) }
            }

            providers.forEach { provider ->
                ignoreExceptions {
                    DebugLogger.d("LocationService", "requestLocationUpdates provider=$provider flow=$flowId")
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
        }

    private fun isProviderEnabled(locationManager: LocationManager, provider: String, flowId: String = ""): Boolean = try {
        val enabled = locationManager.isProviderEnabled(provider)
        if (flowId.isEmpty()) enabled else {
            DebugLogger.d("LocationService", "isProviderEnabled provider=$provider enabled=$enabled flow=$flowId")
            enabled
        }
    } catch (ex: Exception) {
        DebugLogger.w("LocationService", "isProviderEnabled provider=$provider failed", ex)
        false
    }

    private fun lastKnownLocation(locationManager: LocationManager, provider: String): Location? = try {
        locationManager.getLastKnownLocation(provider)
    } catch (ex: Exception) {
        DebugLogger.w("LocationService", "getLastKnownLocation provider=$provider failed", ex)
        null
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.d("LocationService", "onDestroy")
        job?.cancel()
        job = null
    }

    companion object {
        const val LOCATION_ACCESS_NOTIFICATION_ID = 28465

        @JvmStatic
        fun requestNewLocation(context: Context, flowId: String = DebugLog.newFlowId()) {
            DebugLogger.d("LocationService", "start foreground service flow=$flowId")
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java).putExtra(IntentHelper.FLOW_ID_EXTRA, flowId)
            )
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
