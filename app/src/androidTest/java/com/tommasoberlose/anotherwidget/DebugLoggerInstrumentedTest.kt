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
 * Runs the debug logger on a real device: the gating, the file it produces, the export header, the
 * redaction and its own failure isolation. The user's debugMode value is put back afterwards.
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

    private fun logDirectory(): File = DebugLogger.directory(context)

    private fun logText(): String? =
        DebugLogFiles.read(logDirectory())

    private object DebugLogFiles {
        fun read(dir: File): String? =
            java.io.File(dir, "debug.log").takeIf { it.exists() }?.readText()
    }

    // --- 1 & 2: off by default, and silent while off ---

    @Test
    fun disabledByDefault() {
        Preferences.debugMode = 0
        assertFalse(DebugLogger.isEnabled())
    }

    @Test
    fun writesNothingWhileDisabled() {
        Preferences.debugMode = 0

        DebugLogger.log("LOCATION", "flow_start", "trigger" to "test")
        DebugLogger.flush()

        assertNull("no file should exist while Debug Mode is off", logText())
    }

    // --- 3 to 6: the decision chain is recorded ---

    @Test
    fun recordsTheWholeChainWhileEnabled() {
        Preferences.debugMode = 1

        // location
        DebugLogger.log("LOCATION", "flow_start", "trigger" to "weather_refresh", "manual" to true)
        DebugLogger.log("LOCATION", "provider_state", "provider" to "gps", "available" to true, "enabled" to true)
        DebugLogger.log("LOCATION", "permission", "fine" to true, "coarse" to true, "background" to false)
        DebugLogger.log("LOCATION", "last_known", "provider" to "network", "result" to null)
        DebugLogger.log("LOCATION", "request_start", "provider" to "network", "timeoutMs" to 15000)
        DebugLogger.log("LOCATION", "result", "provider" to "network", "lat" to 23.13, "lon" to 113.26,
            "accuracy" to 30, "durationMs" to 373)
        DebugLogger.log("LOCATION", "selected", "source" to "fresh_fix", "provider" to "network")
        DebugLogger.log("LOCATION", "movement", "distanceMeters" to 12453,
            "thresholdMeters" to 10000, "weatherCacheInvalidated" to true)

        // weather api + cache + hourly selection
        DebugLogger.log("WEATHER_API", "request_start", "endpoint" to "hourly",
            "host" to "example.re.qweatherapi.com", "hours" to 24, "localTime" to true)
        DebugLogger.log("WEATHER_API", "response", "status" to 200, "durationMs" to 373,
            "success" to true, "hourCount" to 24)
        DebugLogger.log("WEATHER_CACHE", "miss")
        DebugLogger.log("WEATHER_CACHE", "refresh_failed_keep_old", "kept" to true)
        DebugLogger.log("WEATHER", "hourly_select", "selectedForecastTime" to "2026-10-04T12:00:00.000+08:00",
            "index" to 0, "conditionCode" to "101", "temperature" to 30.0)

        // widget
        DebugLogger.log("WIDGET", "update_start", "reason" to "weather_refresh")
        DebugLogger.log("WIDGET", "update_complete", "durationMs" to 12)

        DebugLogger.flush()

        val text = logText()
        assertNotNull("no log file was written while Debug Mode was on", text)

        listOf(
            "[LOCATION] flow_start", "[LOCATION] provider_state", "[LOCATION] permission",
            "[LOCATION] last_known", "[LOCATION] request_start", "[LOCATION] result",
            "[LOCATION] selected", "[LOCATION] movement",
            "[WEATHER_API] request_start", "[WEATHER_API] response",
            "[WEATHER_CACHE] miss", "[WEATHER_CACHE] refresh_failed_keep_old",
            "[WEATHER] hourly_select",
            "[WIDGET] update_start", "[WIDGET] update_complete"
        ).forEach { assertTrue("missing $it", text!!.contains(it)) }
    }

    // --- 7 & 10: export, and no credentials inside it ---

    @Test
    fun exportCarriesAHeaderAndTheLinesButNoSecret() {
        Preferences.debugMode = 1
        val key = "e74c22003b6e4744a95f322a6a84179"
        DebugLogger.rememberSecret(key)

        DebugLogger.log("WEATHER_API", "request_start", "endpoint" to "hourly",
            "host" to "example.re.qweatherapi.com", "key" to key)
        DebugLogger.log("WIDGET", "update_complete", "durationMs" to 5)
        DebugLogger.flush()

        val exported = DebugLogger.exportText(context)
        assertNotNull(exported)

        assertTrue(exported!!.startsWith("Another Widget Debug Log"))
        assertTrue(exported.contains("App Version: "))
        assertTrue(exported.contains("Android Version: "))
        assertTrue(exported.contains("Device: "))
        assertTrue(exported.contains("Debug Mode: 1"))
        assertTrue(exported.contains("Exported At: "))
        assertTrue(exported.contains("[WEATHER_API] request_start"))

        assertFalse(exported.contains(key))
        assertFalse(exported.contains("X-QW-Api-Key: e74c"))
    }

    // --- 8: clearing ---

    @Test
    fun clearRemovesEveryLogFile() {
        Preferences.debugMode = 1
        DebugLogger.log("WIDGET", "update_start", "reason" to "test")
        DebugLogger.flush()

        assertNotNull(logText())

        val removed = DebugLogger.clear(context)
        assertTrue(removed > 0)
        assertNull(logText())
        assertNull(DebugLogger.exportText(context))
    }

    // --- 11 & 12: failures stay contained ---

    @Test
    fun aBrokenLogDestinationDoesNotTakeTheAppDown() {
        Preferences.debugMode = 1

        // A directory where the log file should be makes every append fail.
        java.io.File(logDirectory(), "debug.log").apply {
            delete()
            mkdirs()
        }

        DebugLogger.log("WIDGET", "update_start", "reason" to "test")
        DebugLogger.log("LOCATION", "flow_start", "trigger" to "test")
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
