package com.tommasoberlose.anotherwidget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.DebugLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the debug logger on a real device: the gating, the logcat-shaped record it produces, the
 * export header, the redaction and its own failure isolation. The user's debugMode value is put
 * back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DebugLoggerInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalDebugMode = Preferences.debugMode

    @Before
    fun startClean() {
        originalDebugMode = Preferences.debugMode
        DebugLogger.clear(context)
    }

    @After
    fun putEverythingBack() {
        Preferences.debugMode = originalDebugMode
        DebugLogger.clear(context)
    }

    private fun currentLog(): String? =
        File(DebugLogger.directory(context), "debug.log").takeIf { it.exists() }?.readText()

    // --- 1 & 2: off by default, and silent while off ---

    @Test
    fun disabledByDefault() {
        Preferences.debugMode = 0
        assertFalse(DebugLogger.isEnabled())
    }

    @Test
    fun writesNothingWhileDisabled() {
        Preferences.debugMode = 0

        DebugLogger.d("LocationService", "requestLocationUpdates provider=network")
        DebugLogger.flush()

        assertNull("no file should exist while Debug Mode is off", currentLog())
    }

    // --- 3 to 6: a realistic runtime chain is recorded ---

    @Test
    fun recordsTheRuntimeChainWhileEnabled() {
        Preferences.debugMode = 1
        val flow = "abc123"

        DebugLogger.d("MainActivity", "onResume")
        DebugLogger.d("Settings", "refresh widget clicked")
        DebugLogger.d("WidgetProvider", "update requested reason=manual_refresh widgets=1 flow=$flow")
        DebugLogger.d("WeatherReceiver", "onReceive action=com.tommasoberlose.anotherwidget.action.ACTION_WEATHER_UPDATE")
        DebugLogger.d("WeatherHelper", "updateWeather trigger=scheduled_refresh flow=$flow cachedAgeMs=60000")
        DebugLogger.d("LocationService", "checkSelfPermission ACCESS_FINE_LOCATION=GRANTED flow=$flow")
        DebugLogger.d("LocationService", "isProviderEnabled provider=network enabled=true flow=$flow")
        DebugLogger.d("LocationService", "requestLocationUpdates providers=[network] timeoutMs=15000 flow=$flow")
        DebugLogger.d("LocationService", "onLocationChanged provider=network accuracy=30.0 flow=$flow")
        DebugLogger.d("LocationCache", "write lat=23.13 lon=113.26 source=network flow=$flow")
        DebugLogger.d("WeatherCache", "miss currentLat=23.13 currentLon=113.26 flow=$flow")
        DebugLogger.d("QWeatherApi", "request endpoint=hourly host=example.re.qweatherapi.com lat=23.13 lon=113.26 hours=24 flow=$flow")
        DebugLogger.d("QWeatherApi", "response status=200 durationMs=373 flow=$flow")
        DebugLogger.d("QWeatherApi", "parse success entries=24 flow=$flow")
        DebugLogger.d("WeatherCache", "write entries=24 lat=23.13 lon=113.26")
        DebugLogger.d("WeatherRepository", "hour selected index=12 forecastTime=2026-10-05T01:00:00.000+08:00 flow=$flow")
        DebugLogger.d("WeatherRepository", "weather flow complete source=network flow=$flow")
        DebugLogger.d("WidgetProvider", "onReceive action=android.appwidget.action.APPWIDGET_UPDATE appWidgetIds=[412]")
        DebugLogger.d("WidgetUpdater", "update start appWidgetId=412 flow=$flow")
        DebugLogger.d("WidgetUpdater", "apply RemoteViews appWidgetId=412")
        DebugLogger.d("WidgetUpdater", "update complete appWidgetId=412 durationMs=18")

        DebugLogger.flush()

        val text = currentLog()
        assertNotNull("no log file was written while Debug Mode was on", text)

        listOf(
            "D/MainActivity(", "D/Settings(", "D/WidgetProvider(", "D/WeatherReceiver(",
            "D/WeatherHelper(", "D/LocationService(", "D/LocationCache(", "D/WeatherCache(",
            "D/QWeatherApi(", "D/WeatherRepository(", "D/WidgetUpdater(",
            "refresh widget clicked", "onLocationChanged provider=network", "parse success entries=24",
            "weather flow complete source=network", "update complete appWidgetId=412"
        ).forEach { assertTrue("missing $it", text!!.contains(it)) }
    }

    // --- 7 & 10: export, and no credentials inside it ---

    @Test
    fun exportCarriesAHeaderAndTheLinesButNoSecret() {
        Preferences.debugMode = 1
        val key = "e74c22003b6e4744a95f322a6a84179"
        DebugLogger.rememberSecret(key)

        DebugLogger.d("QWeatherApi", "request endpoint=hourly host=example.re.qweatherapi.com key=$key")
        DebugLogger.e("QWeatherApi", "request failed type=SocketTimeoutException", java.net.SocketTimeoutException("connect timed out"))
        DebugLogger.flush()

        val exported = DebugLogger.exportText(context)
        assertNotNull(exported)

        assertTrue(exported!!.startsWith("Another Widget Debug Log"))
        assertTrue(exported.contains("App Version: "))
        assertTrue(exported.contains("Android Version: "))
        assertTrue(exported.contains("Device: "))
        assertTrue(exported.contains("Debug Mode: 1"))
        assertTrue(exported.contains("Exported At: "))
        assertTrue(exported.contains("D/QWeatherApi("))

        assertFalse(exported.contains(key))
        assertFalse(exported.contains("X-QW-Api-Key: e74c"))
    }

    @Test
    fun exceptionsCarryARedactedStackTrace() {
        Preferences.debugMode = 1
        val key = "e74c22003b6e4744a95f322a6a84179"
        DebugLogger.rememberSecret(key)

        val error = IllegalStateException("Authorization: Bearer eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9 failed at $key")
        DebugLogger.e("WeatherRepository", "weather flow failed reason=exception", error)
        DebugLogger.flush()

        val text = currentLog()
        assertNotNull(text)
        assertTrue(text!!.contains("E/WeatherRepository("))
        assertTrue(text.contains("IllegalStateException"))
        assertFalse("the jwt must not survive", text.contains("eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse("the registered key must not survive", text.contains(key))
        assertFalse(text.contains("e74c22003b6e4744a95f322a6a84179"))
    }

    // --- 8: clearing ---

    @Test
    fun clearRemovesEveryLogFile() {
        Preferences.debugMode = 1
        DebugLogger.d("WidgetUpdater", "update complete appWidgetId=1 durationMs=3")
        DebugLogger.flush()

        assertNotNull(currentLog())

        val removed = DebugLogger.clear(context)
        assertTrue(removed > 0)
        assertNull(currentLog())
        assertNull(DebugLogger.exportText(context))
    }

    // --- 11 & 12: failures stay contained ---

    @Test
    fun aBrokenLogDestinationDoesNotTakeTheAppDown() {
        Preferences.debugMode = 1

        // A directory where the log file should be makes every append fail.
        File(DebugLogger.directory(context), "debug.log").apply {
            delete()
            mkdirs()
        }

        DebugLogger.d("WidgetProvider", "update requested reason=manual_refresh widgets=1")
        DebugLogger.d("LocationService", "onCreate")
        DebugLogger.flush()

        // Reaching this line is the assertion: the logger swallowed its own failure.
        DebugLogger.clear(context)
    }

    @Test
    fun fileNamesFollowTheDocumentedPattern() {
        val name = DebugLogger.suggestedFileName(1759556400000L)
        val stamp = name.removePrefix("another-widget-debug-").removeSuffix(".log")

        assertTrue(name.startsWith("another-widget-debug-"))
        assertTrue(name.endsWith(".log"))
        assertEquals(17, stamp.length) // yyyy-MM-dd-HHmmss
        assertTrue(stamp.all { it.isDigit() || it == '-' })
    }
}
