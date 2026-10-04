package com.tommasoberlose.anotherwidget.network

import android.content.Context
import android.util.Log
import com.chibatching.kotpref.Kotpref
import com.google.gson.internal.LinkedTreeMap
import com.haroldadmin.cnradapter.NetworkResponse
import com.tommasoberlose.anotherwidget.R
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.CachedWeatherForecast
import com.tommasoberlose.anotherwidget.helpers.CachedWeatherHour
import com.tommasoberlose.anotherwidget.helpers.DebugLog
import com.tommasoberlose.anotherwidget.helpers.DebugLogger
import com.tommasoberlose.anotherwidget.helpers.LocationChangePolicy
import com.tommasoberlose.anotherwidget.helpers.WeatherCache
import com.tommasoberlose.anotherwidget.helpers.WeatherHelper
import com.tommasoberlose.anotherwidget.network.repository.QWeatherAuth
import com.tommasoberlose.anotherwidget.network.repository.QWeatherRepository
import com.tommasoberlose.anotherwidget.ui.fragments.MainFragment
import com.tommasoberlose.anotherwidget.ui.widgets.MainWidget
import java.io.IOException
import org.greenrobot.eventbus.EventBus
import java.io.EOFException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of the "test" button in the QWeather settings. */
data class QWeatherCheck(val success: Boolean, val message: String)

class WeatherNetworkApi(val context: Context) {

    // Serialises weather refreshes: a TTL refresh and a location_changed refresh arriving at the
    // same moment must produce one QWeather request, not two. The re-check inside the lock turns the
    // loser of the race into a cache hit.
    private val refreshMutex = Mutex()

    /**
     * Advances the widget by one step.
     *
     * The widget moves on every hour, but QWeather is only contacted when the cached hourly forecast
     * is missing, older than the network TTL, or was fetched for another place. A failed request
     * keeps the previous forecast, so a transient failure never blanks the widget.
     */
    suspend fun updateWeather(flowId: String = DebugLog.newFlowId(), reason: String = "weather_ttl") {
        Kotpref.init(context)
        Preferences.weatherProviderError = "-"
        Preferences.weatherProviderLocationError = ""

        if (!Preferences.showWeather || Preferences.customLocationLat == "" || Preferences.customLocationLon == "") {
            DebugLogger.w("WeatherRepository",
                "weather update skipped reason=${if (!Preferences.showWeather) "weather_disabled" else "missing_coordinates"} flow=$flowId")
            WeatherHelper.removeWeather(context)

            EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
            return
        }

        val latitude = Preferences.customLocationLat.toDoubleOrNull()
        val longitude = Preferences.customLocationLon.toDoubleOrNull()
        if (latitude == null || longitude == null) {
            DebugLogger.w("WeatherRepository",
                "weather update skipped reason=bad_coordinates lat=${Preferences.customLocationLat} lon=${Preferences.customLocationLon} flow=$flowId")
            Preferences.weatherProviderError = context.getString(R.string.weather_provider_error_missing_location)
            EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
            return
        }

        if (!QWeatherAuth.isConfigured()) {
            DebugLogger.w("WeatherRepository", "weather update skipped reason=credentials_missing flow=$flowId")
            Preferences.weatherProviderError = context.getString(R.string.weather_provider_error_missing_key)
            Preferences.weatherProviderLocationError = ""

            WeatherHelper.removeWeather(context)
            EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
            return
        }

        try {
            refreshMutex.withLock {
                var forecast = WeatherCache.load()
                val effectiveReason = LocationChangePolicy.effectiveReason(reason, forecast == null)
                DebugLogger.d("WeatherRepository",
                    "weather update reason=$effectiveReason lat=$latitude lon=$longitude ${describeCache(forecast)} flow=$flowId")

                val refreshedOverNetwork: Boolean
                if (needsRefresh(forecast, latitude, longitude, flowId)) {
                    val refreshed = requestForecast(latitude, longitude, flowId, effectiveReason)

                    if (refreshed != null) {
                        WeatherCache.save(refreshed)
                        forecast = refreshed
                        refreshedOverNetwork = true
                    } else {
                        DebugLogger.w("WeatherCache",
                            "refresh failed, keeping the old forecast kept=${forecast != null} " +
                                "cacheAgeMs=${forecast?.let { WeatherCache.ageOf(it) } ?: 0L} flow=$flowId")
                        refreshedOverNetwork = false
                    }
                } else {
                    refreshedOverNetwork = false
                }

                forecast?.let { cached ->
                    val hour = WeatherCache.selectHour(cached)

                    if (hour == null) {
                        DebugLogger.w("WeatherRepository",
                            "no cached hour close enough to now, leaving the weather as it is " +
                                "cacheAgeMs=${WeatherCache.ageOf(cached)} hours=${cached.hours.size} flow=$flowId")
                    } else {
                        DebugLogger.d("WeatherRepository",
                            "hour selected index=${cached.hours.indexOf(hour)} forecastTime=${DebugLog.timestamp(hour.forecastTime)} " +
                                "code=${hour.code} temperature=${hour.temperatureC} cacheAgeMs=${WeatherCache.ageOf(cached)} flow=$flowId")
                        display(hour, flowId)
                    }
                }

                when {
                    forecast == null ->
                        DebugLogger.w("WeatherRepository", "weather flow failed reason=no_usable_forecast requested=$effectiveReason flow=$flowId")
                    else ->
                        DebugLogger.d("WeatherRepository",
                            "weather flow complete source=${if (refreshedOverNetwork) "network" else "cache"} reason=$effectiveReason flow=$flowId")
                }
            }
        } catch (ex: Exception) {
            // A malformed response or an unreachable API host must never take the app down.
            DebugLogger.e("WeatherRepository", "weather flow failed reason=exception flow=$flowId", ex)
            ex.printStackTrace()
            Preferences.weatherProviderError = context.getString(R.string.weather_provider_error_generic)
            Preferences.weatherProviderLocationError = ""
        } finally {
            EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
        }
    }

    /**
     * Checks host/key by making the same request the widget makes. Nothing is stored: the caller
     * passes the values currently typed in the settings screen, which may not be saved yet.
     */
    suspend fun verifyCredentials(host: String, key: String, androidRestriction: Boolean): QWeatherCheck {
        if (key.isBlank()) {
            return QWeatherCheck(false, context.getString(R.string.weather_provider_error_missing_key))
        }

        // Falls back to a reference position so the credentials can be validated before location
        // access is granted; the request only has to prove that the account works.
        val latitude = Preferences.customLocationLat.toDoubleOrNull() ?: REFERENCE_LATITUDE
        val longitude = Preferences.customLocationLon.toDoubleOrNull() ?: REFERENCE_LONGITUDE
        val flowId = DebugLog.newFlowId()

        return try {
            DebugLogger.d("QWeatherApi", "credential check request host=${QWeatherAuth.baseUrl(host)} lat=$latitude lon=$longitude flow=$flowId")
            val repository = QWeatherRepository(context, QWeatherAuth.baseUrl(host), key, androidRestriction)

            when (val response = repository.getWeather(latitude, longitude)) {
                is NetworkResponse.Success -> {
                    val hours = parseHours(response.body)

                    if (hours.isEmpty()) {
                        DebugLogger.w("QWeatherApi", "credential check failed reason=no_usable_hour flow=$flowId")
                        QWeatherCheck(false, context.getString(R.string.weather_provider_error_generic))
                    } else {
                        val celsius = hours.first().temperatureC.toFloat()
                        val temperature = if (Preferences.weatherTempUnit == "F") celsius * 9f / 5f + 32f else celsius
                        val condition = response.body.firstConditionText()

                        DebugLogger.d("QWeatherApi", "credential check succeeded entries=${hours.size} flow=$flowId")
                        QWeatherCheck(
                            true,
                            context.getString(
                                R.string.qweather_check_ok,
                                String.format(Locale.US, "%.1f°%s", temperature, Preferences.weatherTempUnit),
                                condition ?: hours.first().code,
                                hours.size
                            )
                        )
                    }
                }
                is NetworkResponse.ServerError -> {
                    DebugLogger.w("QWeatherApi", "credential check rejected status=${response.code} flow=$flowId")
                    QWeatherCheck(false, qWeatherErrorMessage(response.code, response.body))
                }
                is NetworkResponse.NetworkError -> {
                    DebugLogger.w("QWeatherApi",
                        "credential check failed type=${response.error.javaClass.simpleName} message=${response.error.message} flow=$flowId")
                    QWeatherCheck(false, connectionErrorMessage(response.error))
                }
                else -> {
                    val cause = (response as? NetworkResponse.UnknownError)?.error
                    DebugLogger.w("QWeatherApi",
                        "credential check failed unexpectedly type=${cause?.javaClass?.simpleName} message=${cause?.message} flow=$flowId")
                    QWeatherCheck(false, unexpectedErrorMessage(cause))
                }
            }
        } catch (ex: Exception) {
            DebugLogger.e("QWeatherApi", "credential check failed reason=exception flow=$flowId", ex)
            ex.printStackTrace()
            QWeatherCheck(false, context.getString(R.string.weather_provider_error_generic))
        }
    }

    /** The human readable condition of the first returned hour, only used by the credentials check. */
    private fun HashMap<String, Any>.firstConditionText(): String? =
        ((this["hours"] as? List<*>)?.firstOrNull() as? LinkedTreeMap<*, *>)
            ?.let { it["condition"] as? LinkedTreeMap<*, *> }
            ?.get("text") as? String

    private fun needsRefresh(forecast: CachedWeatherForecast?, latitude: Double, longitude: Double, flowId: String): Boolean {
        if (forecast == null) {
            DebugLogger.d("WeatherCache", "miss currentLat=$latitude currentLon=$longitude flow=$flowId")
            return true
        }

        val distance = WeatherCache.distanceTo(forecast, latitude, longitude)
        if (distance > Constants.WEATHER_LOCATION_CHANGE_THRESHOLD) {
            DebugLogger.d("WeatherCache",
                "invalidate reason=location_changed oldLat=${forecast.latitude} oldLon=${forecast.longitude} " +
                    "newLat=$latitude newLon=$longitude distanceMeters=${distance.toInt()} " +
                    "thresholdMeters=${Constants.WEATHER_LOCATION_CHANGE_THRESHOLD.toInt()} flow=$flowId")
            return true
        }

        if (!WeatherCache.isFresh(forecast)) {
            DebugLogger.d("WeatherCache",
                "expired ageMs=${WeatherCache.ageOf(forecast)} ttlMs=${Constants.WEATHER_CACHE_TTL} " +
                    "cachedLat=${forecast.latitude} cachedLon=${forecast.longitude} flow=$flowId")
            return true
        }

        // Fresh, but it may already have run out of hours to display.
        if (WeatherCache.selectHour(forecast) == null) {
            DebugLogger.d("WeatherCache",
                "refresh required reason=no_usable_hour cacheAgeMs=${WeatherCache.ageOf(forecast)} flow=$flowId")
            return true
        }

        DebugLogger.d("WeatherCache",
            "hit ageMs=${WeatherCache.ageOf(forecast)} ttlMs=${Constants.WEATHER_CACHE_TTL} " +
                "cachedLat=${forecast.latitude} cachedLon=${forecast.longitude} " +
                "currentLat=$latitude currentLon=$longitude entries=${forecast.hours.size} flow=$flowId")
        return false
    }

    private suspend fun requestForecast(latitude: Double, longitude: Double, flowId: String, reason: String): CachedWeatherForecast? {
        val startedAt = System.currentTimeMillis()

        DebugLogger.d("QWeatherApi",
            "request endpoint=hourly host=${QWeatherAuth.baseUrl()} lat=$latitude lon=$longitude hours=24 localTime=true reason=$reason flow=$flowId")

        return try {
            when (val response = QWeatherRepository(context).getWeather(latitude, longitude)) {
                is NetworkResponse.Success -> {
                    DebugLogger.d("QWeatherApi",
                        "response status=${response.code} durationMs=${System.currentTimeMillis() - startedAt} flow=$flowId")
                    DebugLogger.d("QWeatherApi", "parse start flow=$flowId")
                    val hours = parseHours(response.body)

                    if (hours.isEmpty()) {
                        DebugLogger.w("QWeatherApi", "parse failed reason=no_usable_entry flow=$flowId")
                        Preferences.weatherProviderError = context.getString(R.string.weather_provider_error_generic)
                        Preferences.weatherProviderLocationError = ""
                        null
                    } else {
                        DebugLogger.d("QWeatherApi",
                            "parse success entries=${hours.size} firstForecastTime=${DebugLog.timestamp(hours.first().forecastTime)} " +
                                "lastForecastTime=${DebugLog.timestamp(hours.last().forecastTime)} flow=$flowId")
                        Preferences.weatherProviderError = ""
                        Preferences.weatherProviderLocationError = ""
                        CachedWeatherForecast(System.currentTimeMillis(), latitude, longitude, hours)
                    }
                }
                is NetworkResponse.ServerError -> {
                    DebugLogger.w("QWeatherApi",
                        "request rejected status=${response.code} errorType=${errorTypeOf(response.body)} " +
                            "durationMs=${System.currentTimeMillis() - startedAt} flow=$flowId")
                    Preferences.weatherProviderError = qWeatherErrorMessage(response.code, response.body)
                    Preferences.weatherProviderLocationError = ""
                    null
                }
                is NetworkResponse.NetworkError -> {
                    DebugLogger.w("QWeatherApi",
                        "request failed type=${response.error.javaClass.simpleName} message=${response.error.message} " +
                            "durationMs=${System.currentTimeMillis() - startedAt} flow=$flowId",
                        response.error)
                    Preferences.weatherProviderError = connectionErrorMessage(response.error)
                    Preferences.weatherProviderLocationError = ""
                    null
                }
                else -> {
                    val cause = (response as? NetworkResponse.UnknownError)?.error
                    DebugLogger.w("QWeatherApi",
                        "request failed type=${cause?.javaClass?.simpleName} message=${cause?.message} " +
                            "durationMs=${System.currentTimeMillis() - startedAt} flow=$flowId",
                        cause)
                    Preferences.weatherProviderError = unexpectedErrorMessage(cause)
                    Preferences.weatherProviderLocationError = ""
                    null
                }
            }
        } catch (ex: Exception) {
            DebugLogger.e("QWeatherApi", "request failed reason=exception flow=$flowId", ex)
            ex.printStackTrace()
            Preferences.weatherProviderError = context.getString(R.string.weather_provider_error_generic)
            Preferences.weatherProviderLocationError = ""
            null
        }
    }

    /**
     * Reads the hourly payload, shaped like
     * {"hours":[{"forecastTime":"2026-10-04T12:00+08:00",
     *            "condition":{"code":"101","text":"Cloudy"},
     *            "temperature":{"value":29.7,"unit":"°C"}}]}.
     */
    private fun parseHours(body: HashMap<String, Any>): List<CachedWeatherHour> {
        val rawHours = body["hours"] as? List<*> ?: return emptyList()

        return rawHours.mapNotNull { entry ->
            val hour = entry as? LinkedTreeMap<*, *> ?: return@mapNotNull null
            val forecastTime = WeatherCache.parseForecastTime(hour["forecastTime"] as? String) ?: return@mapNotNull null
            val temperature = qWeatherCelsius(hour["temperature"] as? LinkedTreeMap<*, *>) ?: return@mapNotNull null
            val code = ((hour["condition"] as? LinkedTreeMap<*, *>)?.get("code") as? String).orEmpty()

            if (code.isBlank()) return@mapNotNull null

            CachedWeatherHour(forecastTime, code, temperature.toDouble())
        }.sortedBy { it.forecastTime }
    }

    private fun display(hour: CachedWeatherHour, flowId: String) {
        // The cache holds Celsius; the user preference decides what is displayed.
        val celsius = hour.temperatureC.toFloat()

        Preferences.weatherForecastTime = hour.forecastTime
        Preferences.weatherTemp = if (Preferences.weatherTempUnit == "F") celsius * 9f / 5f + 32f else celsius
        Preferences.weatherIcon = WeatherHelper.getQWeatherIcon(hour.code, isDaytimeNow())
        Preferences.weatherRealTempUnit = Preferences.weatherTempUnit
        Preferences.weatherProviderError = ""
        Preferences.weatherProviderLocationError = ""

        DebugLogger.d("WeatherRepository",
            "display forecastTime=${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(hour.forecastTime))} " +
                "code=${hour.code} temp=${hour.temperatureC}C icon=${Preferences.weatherIcon} flow=$flowId")
        MainWidget.updateWidget(context, "weather_refresh", flowId)
    }

    private fun describeCache(forecast: CachedWeatherForecast?): String {
        if (forecast == null) return "no cached forecast"

        val age = TimeUnit.MINUTES.convert(WeatherCache.ageOf(forecast), TimeUnit.MILLISECONDS)
        return "cached forecast age=${age}m entries=${forecast.hours.size}"
    }

    /** The problem+json error type of a rejected response, safe for the log. */
    private fun errorTypeOf(body: HashMap<String, Any>?): String =
        (body?.get("error") as? LinkedTreeMap<*, *>)?.get("type") as? String ?: "unknown"

    /**
     * A host name that does not resolve used to be reported as a plain connection error, which sent
     * people looking in the wrong place: it is nearly always a typo in the configured API host.
     */
    private fun connectionErrorMessage(error: IOException): String = when (error) {
        is UnknownHostException -> context.getString(R.string.weather_provider_error_host_not_found)
        is SocketTimeoutException -> context.getString(R.string.weather_provider_error_timeout)
        else -> context.getString(R.string.weather_provider_error_connection)
    }

    private fun unexpectedErrorMessage(cause: Throwable?): String =
        if (isTurnedAway(cause)) {
            context.getString(R.string.weather_provider_error_empty_response)
        } else {
            context.getString(R.string.weather_provider_error_generic)
        }

    private fun isDaytimeNow(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 6..18

    private fun qWeatherTemperature(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    }

    /** Reads the {"value": 31.71, "unit": "°C"} shape and normalizes it to Celsius. */
    private fun qWeatherCelsius(temperature: LinkedTreeMap<*, *>?): Float? {
        val value = qWeatherTemperature(temperature?.get("value")) ?: return null
        val unit = (temperature?.get("unit") as? String).orEmpty()

        return if (unit.contains("F", ignoreCase = true)) (value - 32f) * 5f / 9f else value
    }

    /**
     * Rejected requests come back as an HTTP error status with a problem+json body, for example
     * {"error":{"status":403,"type":"...#invalid-host","title":"Invalid Host"}}. The type is used to
     * tell a wrong API host from a wrong key, because both are easy to get wrong while configuring.
     */
    private fun qWeatherErrorMessage(httpCode: Int, body: HashMap<String, Any>?): String {
        val errorType = errorTypeOf(body)

        return when {
            errorType.contains("invalid-host") -> context.getString(R.string.weather_provider_error_invalid_host)
            errorType.contains("security-restriction") -> context.getString(R.string.weather_provider_error_security_restriction)
            errorType.contains("unauthorized") || httpCode == 401 -> context.getString(R.string.weather_provider_error_invalid_key)
            errorType.contains("too-many-requests") || errorType.contains("over-monthly-limit") || httpCode == 429 -> context.getString(R.string.weather_provider_error_rate_limit)
            errorType.contains("no-credit") || errorType.contains("overdue") -> context.getString(R.string.weather_provider_error_quota_exceeded)
            errorType.contains("no-such-location") || errorType.contains("data-not-available") -> context.getString(R.string.weather_provider_error_wrong_location)
            httpCode == 403 -> context.getString(R.string.weather_provider_error_forbidden)
            else -> context.getString(R.string.weather_provider_error_generic)
        }
    }

    companion object {
        // Only used by the credentials check when no position is known yet.
        private const val REFERENCE_LATITUDE = 39.92
        private const val REFERENCE_LONGITUDE = 116.41

        /**
         * An empty error body means the request was turned away before QWeather's JSON layer
         * answered, which in practice means the configured API host is not the one assigned to the
         * account. A malformed but non-empty body is a different problem and stays generic.
         */
        internal fun isTurnedAway(cause: Throwable?): Boolean = cause is EOFException
    }
}
