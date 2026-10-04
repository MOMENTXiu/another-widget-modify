package com.tommasoberlose.anotherwidget

import com.tommasoberlose.anotherwidget.helpers.CachedWeatherForecast
import com.tommasoberlose.anotherwidget.helpers.CachedWeatherHour
import com.tommasoberlose.anotherwidget.helpers.WeatherCache
import com.google.gson.JsonSyntaxException
import com.tommasoberlose.anotherwidget.helpers.WeatherHelper
import com.tommasoberlose.anotherwidget.network.WeatherNetworkApi
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Covers the parts of the QWeather hourly cache that decide *what* the widget shows: which forecast
 * hour is selected as time passes, and which icon that hour maps to.
 */
class WeatherLogicTest {

    /** An independent UTC instant, used as the expected value for the parser under test. */
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    private fun forecastOf(vararg hours: Pair<Long, String>) = CachedWeatherForecast(
        fetchedAt = 0L,
        latitude = 39.92,
        longitude = 116.41,
        hours = hours.map { (time, code) -> CachedWeatherHour(time, code, 30.0) }
    )

    // --- forecastTime parsing: the API returns an ISO offset such as +08:00 ---

    @Test
    fun `parses a positive offset as UTC`() {
        // 12:00 at +08:00 is 04:00 UTC.
        assertEquals(utc(2026, 10, 4, 4, 0), WeatherCache.parseForecastTime("2026-10-04T12:00+08:00"))
    }

    @Test
    fun `parses a negative offset as UTC`() {
        // 12:00 at -05:30 is 17:30 UTC.
        assertEquals(utc(2026, 10, 4, 17, 30), WeatherCache.parseForecastTime("2026-10-04T12:00-05:30"))
    }

    @Test
    fun `parses a compact offset`() {
        assertEquals(utc(2026, 10, 4, 4, 0), WeatherCache.parseForecastTime("2026-10-04T12:00+0800"))
    }

    @Test
    fun `parses a literal Z as UTC`() {
        assertEquals(utc(2026, 10, 4, 4, 0), WeatherCache.parseForecastTime("2026-10-04T04:00Z"))
    }

    @Test
    fun `treats a missing offset as UTC`() {
        assertEquals(utc(2026, 10, 4, 4, 0), WeatherCache.parseForecastTime("2026-10-04T04:00"))
    }

    @Test
    fun `parses seconds when the API sends them`() {
        assertEquals(utc(2026, 10, 4, 4, 0, 30), WeatherCache.parseForecastTime("2026-10-04T12:00:30+08:00"))
    }

    @Test
    fun `rejects unusable timestamps instead of guessing`() {
        assertNull(WeatherCache.parseForecastTime(null))
        assertNull(WeatherCache.parseForecastTime(""))
        assertNull(WeatherCache.parseForecastTime("not a time"))
    }

    // --- hour selection: the widget must follow the forecast as time passes ---

    @Test
    fun `selects the hour that has already started`() {
        val forecast = forecastOf(
            utc(2026, 10, 4, 4, 0) to "101",   // 12:00 local, cloudy
            utc(2026, 10, 4, 5, 0) to "101",   // 13:00 local, cloudy
            utc(2026, 10, 4, 6, 0) to "302"    // 14:00 local, thunderstorm
        )

        assertEquals("101", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 4, 1))?.code)
        assertEquals("101", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 4, 59))?.code)
        assertEquals("101", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 5, 0))?.code)
        assertEquals("101", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 5, 59))?.code)
        // 14:xx shows the thunderstorm hour, with no new network request in between.
        assertEquals("302", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 6, 0))?.code)
        assertEquals("302", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 6, 30))?.code)
    }

    @Test
    fun `ignores the order the hours arrive in`() {
        val forecast = forecastOf(
            utc(2026, 10, 4, 6, 0) to "302",
            utc(2026, 10, 4, 4, 0) to "101",
            utc(2026, 10, 4, 5, 0) to "100"
        )

        assertEquals("101", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 4, 30))?.code)
        assertEquals("100", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 5, 30))?.code)
    }

    @Test
    fun `falls back to the next hour when every entry is still ahead`() {
        val forecast = forecastOf(
            utc(2026, 10, 4, 5, 0) to "101",
            utc(2026, 10, 4, 6, 0) to "302"
        )

        assertEquals("101", WeatherCache.selectHour(forecast, utc(2026, 10, 4, 4, 30))?.code)
    }

    @Test
    fun `refuses to present an outdated entry as the current weather`() {
        val forecast = forecastOf(
            utc(2026, 10, 4, 4, 0) to "101",
            utc(2026, 10, 4, 5, 0) to "302"
        )

        // Close enough after the last entry: still usable.
        assertNotNull(WeatherCache.selectHour(forecast, utc(2026, 10, 4, 7, 0)))
        // Many hours later the cache has run out, so nothing is displayed...
        assertNull(WeatherCache.selectHour(forecast, utc(2026, 10, 4, 10, 0)))
    }

    @Test
    fun `keeps the temperature of the selected hour`() {
        val forecast = CachedWeatherForecast(
            fetchedAt = 0L, latitude = 39.92, longitude = 116.41,
            hours = listOf(
                CachedWeatherHour(utc(2026, 10, 4, 4, 0), "101", 30.0),
                CachedWeatherHour(utc(2026, 10, 4, 5, 0), "302", 29.0)
            )
        )

        assertEquals(30.0, WeatherCache.selectHour(forecast, utc(2026, 10, 4, 4, 30))?.temperatureC)
        assertEquals(29.0, WeatherCache.selectHour(forecast, utc(2026, 10, 4, 5, 30))?.temperatureC)
    }

    // --- network TTL: unchanged location + a fresh cache must not trigger a request ---

    @Test
    fun `a cache younger than the network ttl is fresh`() {
        val now = System.currentTimeMillis()

        assertTrue(WeatherCache.isFresh(CachedWeatherForecast(now, 0.0, 0.0, emptyList())))
        // Three hours old is the boundary: refresh from there on.
        assertFalse(WeatherCache.isFresh(CachedWeatherForecast(now - 3 * 60 * 60 * 1000L, 0.0, 0.0, emptyList())))
        assertFalse(WeatherCache.isFresh(CachedWeatherForecast(now - 4 * 60 * 60 * 1000L, 0.0, 0.0, emptyList())))
    }

    // --- failure classification: an empty rejection is not a malformed response ---

    @Test
    fun `an empty error body is reported as the host being turned away`() {
        // This is what QWeather's edge sends for a host that is not the account's host.
        assertTrue(WeatherNetworkApi.isTurnedAway(EOFException("End of input at line 1 column 1 path $")))
        // A malformed but non-empty body is a different failure and must stay generic.
        assertFalse(WeatherNetworkApi.isTurnedAway(JsonSyntaxException("Expected BEGIN_OBJECT")))
        assertFalse(WeatherNetworkApi.isTurnedAway(IOException("connection reset")))
        assertFalse(WeatherNetworkApi.isTurnedAway(null))
    }

    // --- condition mapping: every category the widget has to support ---

    @Test
    fun `maps the QWeather categories onto the existing icon set`() {
        assertEquals("01d", WeatherHelper.getQWeatherIcon("100", true))   // clear day
        assertEquals("01n", WeatherHelper.getQWeatherIcon("150", false))  // clear night
        assertEquals("02d", WeatherHelper.getQWeatherIcon("102", true))   // partly cloudy
        assertEquals("03d", WeatherHelper.getQWeatherIcon("101", true))   // cloudy
        assertEquals("04d", WeatherHelper.getQWeatherIcon("104", true))   // overcast
        assertEquals("09d", WeatherHelper.getQWeatherIcon("300", true))   // shower
        assertEquals("10d", WeatherHelper.getQWeatherIcon("305", true))   // rain
        assertEquals("09d", WeatherHelper.getQWeatherIcon("307", true))   // heavy rain
        assertEquals("11d", WeatherHelper.getQWeatherIcon("302", true))   // thunderstorm
        assertEquals("13d", WeatherHelper.getQWeatherIcon("400", true))   // snow
        assertEquals("13d", WeatherHelper.getQWeatherIcon("403", true))   // heavy snow
        assertEquals("82d", WeatherHelper.getQWeatherIcon("501", true))   // fog
        assertEquals("50d", WeatherHelper.getQWeatherIcon("502", true))   // haze
        assertEquals("50d", WeatherHelper.getQWeatherIcon("503", true))   // sand
        assertEquals("50d", WeatherHelper.getQWeatherIcon("504", true))   // dust
        assertEquals("80d", WeatherHelper.getQWeatherIcon("200", true))   // wind
    }

    @Test
    fun `a night code stays night whatever the clock says`() {
        assertEquals("01n", WeatherHelper.getQWeatherIcon("150", true))
        assertEquals("10n", WeatherHelper.getQWeatherIcon("305", false))
    }

    @Test
    fun `unknown codes fall back to a generic icon instead of failing`() {
        assertEquals(WeatherHelper.UNKNOWN_ICON, WeatherHelper.getQWeatherIcon("800", true))
        assertEquals(WeatherHelper.UNKNOWN_ICON, WeatherHelper.getQWeatherIcon("999", true))
        assertEquals(WeatherHelper.UNKNOWN_ICON, WeatherHelper.getQWeatherIcon("", true))
        assertEquals(WeatherHelper.UNKNOWN_ICON, WeatherHelper.getQWeatherIcon("nonsense", true))
        // Never blank: a blank id would make the widget drop the weather entirely.
        assertTrue(WeatherHelper.getQWeatherIcon("999", true).isNotEmpty())
    }
}
