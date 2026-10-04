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
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Outcome of the "test" button in the QWeather settings. */
data class QWeatherCheck(val success: Boolean, val message: String)

class WeatherNetworkApi(val context: Context) {

    /**
     * Advances the widget by one step.
     *
     * The widget moves on every hour, but QWeather is only contacted when the cached hourly forecast
     * is missing, older than the network TTL, or was fetched for another place. A failed request
     * keeps the previous forecast, so a transient failure never blanks the widget.
     */
    suspend fun updateWeather() {
        Kotpref.init(context)
        Preferences.weatherProviderError = "-"
        Preferences.weatherProviderLocationError = ""

        if (!Preferences.showWeather || Preferences.customLocationLat == "" || Preferences.customLocationLon == "") {
            WeatherHelper.removeWeather(
                context
            )

            EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
            return
        }

        val latitude = Preferences.customLocationLat.toDoubleOrNull()
        val longitude = Preferences.customLocationLon.toDoubleOrNull()
        if (latitude == null || longitude == null) {
            Log.d(Constants.LOG_TAG, "no usable coordinates, skipping the weather update")
            Preferences.weatherProviderError = context.getString(R.string.weather_provider_error_missing_location)
            EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
            return
        }

        if (!QWeatherAuth.isConfigured()) {
            Preferences.weatherProviderError = context.getString(R.string.weather_provider_error_missing_key)
            Preferences.weatherProviderLocationError = ""

            WeatherHelper.removeWeather(
                context
            )
            EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())
            return
        }

        try {
            var forecast = WeatherCache.load()
            Log.d(Constants.LOG_TAG, "weather update triggered: ${describeCache(forecast)}, coordinates $latitude,$longitude")

            if (needsRefresh(forecast, latitude, longitude)) {
                Log.d(Constants.LOG_TAG, "weather cache miss, requesting the QWeather hourly forecast")
                val refreshed = requestForecast(latitude, longitude)

                if (refreshed != null) {
                    WeatherCache.save(refreshed)
                    forecast = refreshed
                } else {
                    Log.d(Constants.LOG_TAG, "QWeather refresh failed, keeping the cached forecast")
                }
            } else {
                Log.d(Constants.LOG_TAG, "weather cache hit, no network request")
            }

            val hour = forecast?.let { WeatherCache.selectHour(it) }
            if (hour == null) {
                Log.d(Constants.LOG_TAG, "no cached hour close enough to now, leaving the weather as it is")
            } else {
                display(hour)
            }
        } catch (ex: Exception) {
            // A malformed response or an unreachable API host must never take the app down.
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

        return try {
            val repository = QWeatherRepository(context, QWeatherAuth.baseUrl(host), key, androidRestriction)

            when (val response = repository.getWeather(latitude, longitude)) {
                is NetworkResponse.Success -> {
                    val hours = parseHours(response.body)

                    if (hours.isEmpty()) {
                        QWeatherCheck(false, context.getString(R.string.weather_provider_error_generic))
                    } else {
                        val celsius = hours.first().temperatureC.toFloat()
                        val temperature = if (Preferences.weatherTempUnit == "F") celsius * 9f / 5f + 32f else celsius
                        val condition = response.body.firstConditionText()

                        Log.d(Constants.LOG_TAG, "credentials check succeeded, ${hours.size} hours returned")
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
                    Log.d(Constants.LOG_TAG, "credentials check rejected with HTTP ${response.code}")
                    QWeatherCheck(false, qWeatherErrorMessage(response.code, response.body))
                }
                is NetworkResponse.NetworkError -> {
                    Log.d(Constants.LOG_TAG, "credentials check failed: ${response.error.javaClass.simpleName}: ${response.error.message}")
                    QWeatherCheck(false, connectionErrorMessage(response.error))
                }
                else -> {
                    val cause = (response as? NetworkResponse.UnknownError)?.error
                    Log.w(Constants.LOG_TAG, "credentials check failed unexpectedly: ${cause?.javaClass?.simpleName}: ${cause?.message}")
                    QWeatherCheck(false, unexpectedErrorMessage(cause))
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            QWeatherCheck(false, context.getString(R.string.weather_provider_error_generic))
        }
    }

    /** The human readable condition of the first returned hour, only used by the credentials check. */
    private fun HashMap<String, Any>.firstConditionText(): String? =
        ((this["hours"] as? List<*>)?.firstOrNull() as? LinkedTreeMap<*, *>)
            ?.let { it["condition"] as? LinkedTreeMap<*, *> }
            ?.get("text") as? String

    private fun needsRefresh(forecast: CachedWeatherForecast?, latitude: Double, longitude: Double): Boolean {
        if (forecast == null) return true

        if (!WeatherCache.isSamePlace(forecast, latitude, longitude)) {
            Log.d(Constants.LOG_TAG, "weather cache invalidated: the location changed")
            return true
        }

        if (!WeatherCache.isFresh(forecast)) {
            Log.d(Constants.LOG_TAG, "weather cache expired (network TTL)")
            return true
        }

        // Fresh, but it may already have run out of hours to display.
        return WeatherCache.selectHour(forecast) == null
    }

    private suspend fun requestForecast(latitude: Double, longitude: Double): CachedWeatherForecast? {
        return try {
            when (val response = QWeatherRepository(context).getWeather(latitude, longitude)) {
                is NetworkResponse.Success -> {
                    val hours = parseHours(response.body)

                    if (hours.isEmpty()) {
                        Log.w(Constants.LOG_TAG, "QWeather response held no usable hourly entry")
                        Preferences.weatherProviderError = context.getString(R.string.weather_provider_error_generic)
                        Preferences.weatherProviderLocationError = ""
                        null
                    } else {
                        Log.d(Constants.LOG_TAG, "QWeather refresh succeeded, ${hours.size} hours cached")
                        Preferences.weatherProviderError = ""
                        Preferences.weatherProviderLocationError = ""
                        CachedWeatherForecast(System.currentTimeMillis(), latitude, longitude, hours)
                    }
                }
                is NetworkResponse.ServerError -> {
                    Log.d(Constants.LOG_TAG, "QWeather refresh rejected with HTTP ${response.code}")
                    Preferences.weatherProviderError = qWeatherErrorMessage(response.code, response.body)
                    Preferences.weatherProviderLocationError = ""
                    null
                }
                is NetworkResponse.NetworkError -> {
                    Log.d(Constants.LOG_TAG, "QWeather refresh failed: ${response.error.javaClass.simpleName}: ${response.error.message}")
                    Preferences.weatherProviderError = connectionErrorMessage(response.error)
                    Preferences.weatherProviderLocationError = ""
                    null
                }
                else -> {
                    val cause = (response as? NetworkResponse.UnknownError)?.error
                    Log.w(Constants.LOG_TAG, "QWeather refresh failed unexpectedly: ${cause?.javaClass?.simpleName}: ${cause?.message}")
                    Preferences.weatherProviderError = unexpectedErrorMessage(cause)
                    Preferences.weatherProviderLocationError = ""
                    null
                }
            }
        } catch (ex: Exception) {
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

    private fun display(hour: CachedWeatherHour) {
        // The cache holds Celsius; the user preference decides what is displayed.
        val celsius = hour.temperatureC.toFloat()

        Preferences.weatherTemp = if (Preferences.weatherTempUnit == "F") celsius * 9f / 5f + 32f else celsius
        Preferences.weatherIcon = WeatherHelper.getQWeatherIcon(hour.code, isDaytimeNow())
        Preferences.weatherRealTempUnit = Preferences.weatherTempUnit
        Preferences.weatherProviderError = ""
        Preferences.weatherProviderLocationError = ""

        Log.d(Constants.LOG_TAG, "displaying ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(hour.forecastTime))} code=${hour.code} temp=${hour.temperatureC}C")
        MainWidget.updateWidget(context)
    }

    private fun describeCache(forecast: CachedWeatherForecast?): String {
        if (forecast == null) return "no cached forecast"

        val age = TimeUnit.MINUTES.convert(WeatherCache.ageOf(forecast), TimeUnit.MILLISECONDS)
        return "cached forecast age=${age}m hours=${forecast.hours.size}"
    }

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
        val errorType = ((body?.get("error") as? LinkedTreeMap<*, *>)?.get("type") as? String).orEmpty()

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
