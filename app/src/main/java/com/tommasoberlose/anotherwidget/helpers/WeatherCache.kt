package com.tommasoberlose.anotherwidget.helpers

import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/** One hour of the QWeather hourly forecast. Temperature is always kept in Celsius. */
data class CachedWeatherHour(
    val forecastTime: Long,
    val code: String,
    val temperatureC: Double
)

/** The cached hourly sequence plus the position and time it was fetched for. */
data class CachedWeatherForecast(
    val fetchedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val hours: List<CachedWeatherHour>
)

/**
 * Persists the hourly forecast so the widget can advance hour by hour without a network request.
 *
 * The whole sequence is stored as JSON in the app preferences: it is a single atomic write, needs no
 * schema migration, and survives process death and reboots.
 */
object WeatherCache {

    // QWeather returns e.g. "2026-10-04T12:00+08:00" with localTime=true, or "...T04:00Z" in UTC.
    private val TIME_PATTERN = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(Z|[+-]\d{2}:?\d{2})?""")

    private val gson = Gson()

    fun load(): CachedWeatherForecast? {
        val raw = Preferences.weatherForecastCache
        if (raw.isBlank()) {
            DebugLogger.d("WeatherCache", "read empty")
            return null
        }

        return try {
            val parsed = gson.fromJson(raw, CachedWeatherForecast::class.java)
            if (parsed == null || parsed.hours.isEmpty()) {
                DebugLogger.w("WeatherCache", "read empty entries")
                null
            } else {
                DebugLogger.d("WeatherCache", "read entries=${parsed.hours.size} cachedLat=${parsed.latitude} cachedLon=${parsed.longitude}")
                parsed
            }
        } catch (ex: Exception) {
            DebugLogger.w("WeatherCache", "read failed exception=${ex.javaClass.simpleName}", ex)
            null
        }
    }

    /** Only called with a forecast that was validated first, so a working cache is never lost. */
    fun save(forecast: CachedWeatherForecast) {
        DebugLogger.d("WeatherCache",
            "write entries=${forecast.hours.size} lat=${forecast.latitude} lon=${forecast.longitude}")
        Preferences.weatherForecastCache = gson.toJson(forecast)
    }

    fun ageOf(forecast: CachedWeatherForecast): Long = System.currentTimeMillis() - forecast.fetchedAt

    /** Within the network TTL the cached sequence is reused as is. */
    fun isFresh(forecast: CachedWeatherForecast): Boolean = ageOf(forecast) < Constants.WEATHER_CACHE_TTL

    /** metres between where the forecast was fetched and the given position. */
    fun distanceTo(forecast: CachedWeatherForecast, latitude: Double, longitude: Double): Float {
        val distance = FloatArray(1)
        Location.distanceBetween(forecast.latitude, forecast.longitude, latitude, longitude, distance)
        return distance[0]
    }

    /** A forecast fetched somewhere else is useless, even if it is recent. */
    fun isSamePlace(forecast: CachedWeatherForecast, latitude: Double, longitude: Double): Boolean =
        distanceTo(forecast, latitude, longitude) <= Constants.WEATHER_LOCATION_CHANGE_THRESHOLD

    /**
     * The hour to display: the latest entry that has already started, so the widget follows the
     * forecast as time passes instead of showing whichever entry was current when the fetch happened.
     * A cache that has run out of relevant hours returns null rather than an outdated entry.
     */
    fun selectHour(forecast: CachedWeatherForecast, now: Long = System.currentTimeMillis()): CachedWeatherHour? {
        val candidate = forecast.hours
            .filter { it.forecastTime <= now }
            .maxByOrNull { it.forecastTime }
            ?: forecast.hours.minByOrNull { it.forecastTime }

        return candidate?.takeIf { abs(now - it.forecastTime) <= Constants.WEATHER_FORECAST_MAX_DRIFT }
    }

    /**
     * Parses QWeather's forecastTime. Done by hand because SimpleDateFormat does not understand ISO
     * offsets before API 24 and this app supports API 23.
     */
    fun parseForecastTime(raw: String?): Long? {
        val values = TIME_PATTERN.matchEntire(raw?.trim().orEmpty())?.groupValues ?: return null

        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                values[1].toInt(), values[2].toInt() - 1, values[3].toInt(),
                values[4].toInt(), values[5].toInt(), values[6].ifBlank { "0" }.toInt()
            )
        }

        return utc.timeInMillis - offsetMillis(values[7])
    }

    private fun offsetMillis(offset: String): Long {
        if (offset.isEmpty() || offset == "Z") return 0L

        val sign = if (offset.startsWith("-")) -1 else 1
        val digits = offset.drop(1).replace(":", "")
        val hours = digits.take(2).toIntOrNull() ?: 0
        val minutes = digits.drop(2).take(2).toIntOrNull() ?: 0

        return sign * (hours * 60L + minutes) * 60_000L
    }
}
