package com.tommasoberlose.anotherwidget.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Looper
import androidx.core.app.*
import androidx.core.content.ContextCompat
import com.chibatching.kotpref.Kotpref
import com.haroldadmin.cnradapter.NetworkResponse
import com.tommasoberlose.anotherwidget.R
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.DebugLog
import com.tommasoberlose.anotherwidget.helpers.DebugLogger
import com.tommasoberlose.anotherwidget.helpers.IntentHelper
import com.tommasoberlose.anotherwidget.helpers.LocationCandidate
import com.tommasoberlose.anotherwidget.helpers.LocationChangePolicy
import com.tommasoberlose.anotherwidget.helpers.LocationCandidates
import com.tommasoberlose.anotherwidget.helpers.LocationHelper
import com.tommasoberlose.anotherwidget.helpers.WeatherCache
import com.tommasoberlose.anotherwidget.helpers.WeatherRegionStore
import com.tommasoberlose.anotherwidget.network.WeatherNetworkApi
import com.tommasoberlose.anotherwidget.network.repository.QWeatherRepository
import com.tommasoberlose.anotherwidget.ui.activities.MainActivity
import com.tommasoberlose.anotherwidget.ui.fragments.MainFragment
import com.tommasoberlose.anotherwidget.utils.checkGrantedPermission
import com.tommasoberlose.anotherwidget.utils.ignoreExceptions
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import kotlin.coroutines.resume

/**
 * The LocalLocationChange executor. Its only question is: has the user moved to another weather
 * region? It has no schedule of its own; LocationChangeReceiver wakes it hourly and the manual
 * refresh routes here as well.
 *
 * Every run first reads the system last-known positions (network, fused, passive, gps) and only
 * asks for a fresh NETWORK fix when none of them is usable - and then never touches GPS.
 */
class LocationService : Service() {

    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        DebugLogger.d("LocationService", "onCreate")
        startForeground(LOCATION_ACCESS_NOTIFICATION_ID, getLocationAccessNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val flowId = intent?.getStringExtra(IntentHelper.FLOW_ID_EXTRA) ?: DebugLog.newFlowId()
        val trigger = intent?.getStringExtra(TRIGGER_EXTRA) ?: TRIGGER_SCHEDULED
        DebugLogger.d("LocationService", "onStartCommand trigger=$trigger flow=$flowId startId=$startId")
        startForeground(LOCATION_ACCESS_NOTIFICATION_ID, getLocationAccessNotification())
        job?.cancel()
        job = GlobalScope.launch(Dispatchers.IO) {
            try {
                executeLocationChange(trigger, flowId)
            } catch (ex: Exception) {
                DebugLogger.e("LocalLocationChange", "flow failed reason=exception flow=$flowId", ex)
            } finally {
                withContext(Dispatchers.Main) { stopSelf() }
                EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
            }
        }
        return START_STICKY
    }

    private suspend fun executeLocationChange(trigger: String, flowId: String) {
        Kotpref.init(this)

        DebugLogger.d("LocalLocationChange",
            "start trigger=$trigger flow=$flowId manual=${Preferences.customLocationAdd != ""} cachedAgeMs=${LocationHelper.ageMs()}")

        // Manual location mode: the user owns the position, the automatic check must not touch it.
        if (Preferences.customLocationAdd != "") {
            DebugLogger.d("LocalLocationChange", "skip reason=manual_location_enabled flow=$flowId")
            if (trigger == TRIGGER_MANUAL_REFRESH) {
                WeatherNetworkApi(this).updateWeather(flowId, reason = "manual_refresh")
            }
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            DebugLogger.w("LocalLocationChange", "failed reason=no_location_manager flow=$flowId")
            return
        }

        logPermissionState(flowId)

        val decision = if (trigger == TRIGGER_MANUAL_REFRESH) {
            // Manual refresh means "re-confirm my position and weather now": straight to a fresh fix.
            LocationChangePolicy.Decision.RequestFresh
        } else {
            val candidates = readLastKnownCandidates(locationManager, flowId)
            LocationChangePolicy.decide(manualLocationEnabled = false, manualRefresh = false, candidates = candidates)
        }

        when (decision) {
            is LocationChangePolicy.Decision.SkipManual -> Unit

            is LocationChangePolicy.Decision.UseLastKnown -> {
                DebugLogger.d("LocalLocationChange",
                    "selected provider=${decision.candidate.provider} source=last_known " +
                        "ageMs=${decision.candidate.ageMs} accuracy=${decision.candidate.accuracy} flow=$flowId")
                processPosition(decision.candidate.latitude, decision.candidate.longitude,
                    "last_known:${decision.candidate.provider}", flowId)
            }

            is LocationChangePolicy.Decision.RequestFresh -> {
                DebugLogger.d("LocalLocationChange",
                    "no usable last-known, requesting current network location flow=$flowId")
                requestFreshAndProcess(locationManager, flowId)
            }
        }
    }

    /** Reads every provider the system offers a last-known position for; gps is read, never started. */
    private fun readLastKnownCandidates(locationManager: LocationManager, flowId: String): List<LocationCandidate> {
        val now = System.currentTimeMillis()

        return listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider ->
                val fix = lastKnownLocation(locationManager, provider)
                if (fix == null) {
                    DebugLogger.d("LocationManager", "lastKnown provider=$provider result=null flow=$flowId")
                    null
                } else {
                    DebugLogger.d("LocationManager",
                        "lastKnown provider=$provider lat=${fix.latitude} lon=${fix.longitude} " +
                            "accuracy=${fix.accuracy} ageMs=${now - fix.time} flow=$flowId")
                    LocationCandidate(provider, fix.latitude, fix.longitude, fix.accuracy, now - fix.time)
                }
            }
            .onEach { candidate ->
                val accepted = LocationCandidates.usable(candidate)
                DebugLogger.d("LocalLocationChange",
                    "candidate provider=${candidate.provider} accepted=$accepted " +
                        "reason=${if (accepted) "fresh_and_accurate" else LocationCandidates.rejectionReason(candidate)} flow=$flowId")
            }
    }

    private fun logPermissionState(flowId: String) {
        val fine = checkGrantedPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = checkGrantedPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        DebugLogger.d("LocationManager",
            "checkSelfPermission ACCESS_FINE_LOCATION=${if (fine) "GRANTED" else "DENIED"} " +
                "ACCESS_COARSE_LOCATION=${if (coarse) "GRANTED" else "DENIED"} flow=$flowId")
    }

    /**
     * The only active request on the default path: one NETWORK fix through getCurrentLocation with a
     * hard 5s timeout. No GPS, no fused race. When it fails, the persisted 6h fallback decides.
     */
    private suspend fun requestFreshAndProcess(locationManager: LocationManager, flowId: String) {
        val fix = withTimeoutOrNull(Constants.LOCATION_FRESH_TIMEOUT_MS) {
            requestCurrentNetworkLocation(locationManager, flowId)
        }

        if (fix == null) {
            DebugLogger.w("LocationManager",
                "currentLocation timeout timeoutMs=${Constants.LOCATION_FRESH_TIMEOUT_MS} flow=$flowId")
        } else if (!LocationCandidates.freshFixUsable(fix.accuracy)) {
            DebugLogger.w("LocationManager",
                "currentLocation rejected accuracy=${fix.accuracy} ceiling=${Constants.LOCATION_FRESH_MAX_ACCURACY_M} flow=$flowId")
        } else {
            DebugLogger.d("LocationManager",
                "currentLocation callback provider=${fix.provider} lat=${fix.latitude} lon=${fix.longitude} " +
                    "accuracy=${fix.accuracy} flow=$flowId")
            DebugLogger.d("LocalLocationChange",
                "selected provider=${fix.provider} source=current_network accuracy=${fix.accuracy} flow=$flowId")
            processPosition(fix.latitude, fix.longitude, "current_network", flowId)
            return
        }

        // Fresh request failed: fall back to the persisted coordinates under the existing 6h policy.
        if (LocationChangePolicy.afterFreshFailure(LocationHelper.hasCachedLocation())) {
            DebugLogger.d("LocalLocationChange",
                "fallback source=stale_cache ageMs=${LocationHelper.ageMs()} flow=$flowId")
            val latitude = Preferences.customLocationLat.toDoubleOrNull()
            val longitude = Preferences.customLocationLon.toDoubleOrNull()
            if (latitude != null && longitude != null) {
                processPosition(latitude, longitude, "stale_cache", flowId)
                return
            }
        }

        DebugLogger.w("LocalLocationChange", "failed reason=location_unavailable flow=$flowId")
        Preferences.weatherProviderLocationError = getString(R.string.weather_provider_error_missing_location)
    }

    /**
     * Region resolve plus the actual decision: same region updates the cache only, a different
     * region refreshes the weather with reason=location_changed.
     */
    private suspend fun processPosition(latitude: Double, longitude: Double, source: String, flowId: String) {
        val stored = WeatherRegionStore.load()

        // The reuse shortcut: close to the last confirmed fix means same region, no lookup.
        if (stored != null && WeatherRegionStore.isWithinReuseRadius(stored, latitude, longitude)) {
            DebugLogger.d("LocalLocationChange",
                "region old=${stored.id} new=${stored.id} changed=false " +
                    "distanceMeters=${WeatherRegionStore.distanceFromAnchor(stored, latitude, longitude).toInt()} resolve=reused flow=$flowId")
            DebugLogger.d("LocalLocationChange", "weather refresh skipped reason=same_region flow=$flowId")
            LocationHelper.saveLocation(latitude, longitude, System.currentTimeMillis(), source = source, flowId = flowId)
            return
        }

        val resolved = resolveRegion(latitude, longitude, flowId)
        val newRegionId = resolved?.first ?: ""
        val newRegionName = resolved?.second ?: ""

        when (LocationChangePolicy.compareRegions(stored?.id ?: "", newRegionId)) {
            LocationChangePolicy.RegionOutcome.Same -> {
                DebugLogger.d("LocalLocationChange",
                    "region old=${stored?.id} new=$newRegionId changed=false resolve=looked_up flow=$flowId")
                DebugLogger.d("LocalLocationChange", "weather refresh skipped reason=same_region flow=$flowId")
                LocationHelper.saveLocation(latitude, longitude, System.currentTimeMillis(), source = source, flowId = flowId)
            }

            LocationChangePolicy.RegionOutcome.Changed -> {
                DebugLogger.d("LocalLocationChange",
                    "region old=${stored?.id} new=$newRegionId changed=true name=$newRegionName flow=$flowId")
                LocationHelper.saveLocation(latitude, longitude, System.currentTimeMillis(), source = source, flowId = flowId)
                WeatherRegionStore.save(newRegionId, newRegionName, latitude, longitude)
                DebugLogger.d("LocalWeather", "refresh requested reason=location_changed flow=$flowId")
                WeatherNetworkApi(this).updateWeather(flowId, reason = "location_changed")
            }

            LocationChangePolicy.RegionOutcome.Initial -> {
                DebugLogger.d("LocalLocationChange",
                    "region adopted new=$newRegionId name=$newRegionName reason=first_run flow=$flowId")
                LocationHelper.saveLocation(latitude, longitude, System.currentTimeMillis(), source = source, flowId = flowId)
                WeatherRegionStore.save(newRegionId, newRegionName, latitude, longitude)
            }

            LocationChangePolicy.RegionOutcome.Unresolved -> {
                DebugLogger.w("LocalLocationChange", "region resolve failed flow=$flowId")
                // Fallback: the movement distance heuristic decides, so a failed lookup can never
                // leave the user with weather for a place they left long ago.
                val forecast = WeatherCache.load()
                val moved = forecast == null ||
                    WeatherCache.distanceTo(forecast, latitude, longitude) > Constants.WEATHER_LOCATION_CHANGE_THRESHOLD

                if (moved) {
                    DebugLogger.d("LocalLocationChange", "region unresolved, distance fallback triggers the refresh flow=$flowId")
                    LocationHelper.saveLocation(latitude, longitude, System.currentTimeMillis(), source = source, flowId = flowId)
                    WeatherNetworkApi(this).updateWeather(flowId, reason = "location_changed")
                } else {
                    DebugLogger.d("LocalLocationChange", "weather refresh skipped reason=same_region_fallback flow=$flowId")
                    LocationHelper.saveLocation(latitude, longitude, System.currentTimeMillis(), source = source, flowId = flowId)
                }
            }
        }
    }

    /** One GeoAPI lookup for coordinates outside the reuse radius; null when it fails. */
    private suspend fun resolveRegion(latitude: Double, longitude: Double, flowId: String): Pair<String, String>? {
        DebugLogger.d("QWeatherApi", "geo lookup request lat=$latitude lon=$longitude flow=$flowId")

        return when (val response = QWeatherRepository(this).geoLookup(latitude, longitude)) {
            is NetworkResponse.Success -> {
                val id = WeatherRegionStore.idFromLookupBody(response.body)
                val name = WeatherRegionStore.nameFromLookupBody(response.body)

                if (id.isNullOrBlank()) {
                    DebugLogger.w("QWeatherApi", "geo lookup response status=${response.code} result=empty flow=$flowId")
                    null
                } else {
                    DebugLogger.d("QWeatherApi", "geo lookup response id=$id name=$name flow=$flowId")
                    id to name
                }
            }
            is NetworkResponse.ServerError -> {
                DebugLogger.w("QWeatherApi", "geo lookup rejected status=${response.code} flow=$flowId")
                null
            }
            is NetworkResponse.NetworkError -> {
                DebugLogger.w("QWeatherApi",
                    "geo lookup failed type=${response.error.javaClass.simpleName} flow=$flowId", response.error)
                null
            }
            else -> {
                DebugLogger.w("QWeatherApi", "geo lookup failed type=unknown flow=$flowId")
                null
            }
        }
    }

    /** The modern one-shot API; the legacy single-update path only exists for API < 30. */
    private suspend fun requestCurrentNetworkLocation(locationManager: LocationManager, flowId: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation {
                    signal.cancel()
                    DebugLogger.d("LocationManager", "currentLocation cancelled flow=$flowId")
                }

                try {
                    DebugLogger.d("LocationManager",
                        "getCurrentLocation provider=network timeoutMs=${Constants.LOCATION_FRESH_TIMEOUT_MS} flow=$flowId")
                    locationManager.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,
                        signal,
                        ContextCompat.getMainExecutor(this)
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (ex: Exception) {
                    DebugLogger.w("LocationManager", "getCurrentLocation failed provider=network", ex)
                    if (continuation.isActive) continuation.resume(null)
                }
            } else {
                try {
                    DebugLogger.d("LocationManager", "requestSingleUpdate provider=network flow=$flowId")
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER,
                        { location -> if (continuation.isActive) continuation.resume(location) },
                        Looper.getMainLooper()
                    )
                } catch (ex: Exception) {
                    DebugLogger.w("LocationManager", "requestSingleUpdate failed provider=network", ex)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private fun lastKnownLocation(locationManager: LocationManager, provider: String): Location? = try {
        locationManager.getLastKnownLocation(provider)
    } catch (ex: Exception) {
        DebugLogger.w("LocationManager", "getLastKnownLocation provider=$provider failed", ex)
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
        const val TRIGGER_EXTRA = "location_trigger"
        const val TRIGGER_SCHEDULED = "scheduled_location_check"
        const val TRIGGER_MANUAL_REFRESH = "manual_refresh"

        @JvmStatic
        fun requestLocationCheck(context: Context, trigger: String, flowId: String) {
            DebugLogger.d("LocationService", "start foreground service trigger=$trigger flow=$flowId")
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java)
                    .putExtra(IntentHelper.FLOW_ID_EXTRA, flowId)
                    .putExtra(TRIGGER_EXTRA, trigger)
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
