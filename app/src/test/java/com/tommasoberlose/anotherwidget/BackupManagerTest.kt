package com.tommasoberlose.anotherwidget

import com.tommasoberlose.anotherwidget.helpers.BackupManager
import com.tommasoberlose.anotherwidget.helpers.BackupSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the backup document itself: what is accepted, what is refused, what is ignored, and — just
 * as important — what never ends up in a backup at all.
 */
class BackupManagerTest {

    private fun ok(json: String): BackupManager.Document {
        val parsed = BackupManager.parse(json)
        assertTrue("expected the backup to parse, got $parsed", parsed is BackupManager.ParseResult.Ok)
        return (parsed as BackupManager.ParseResult.Ok).document
    }

    private fun failure(json: String): BackupManager.Failure {
        val parsed = BackupManager.parse(json)
        assertTrue("expected the backup to be refused, got $parsed", parsed is BackupManager.ParseResult.Error)
        return (parsed as BackupManager.ParseResult.Error).reason
    }

    private fun document(vararg preferences: Pair<String, Any>): BackupManager.Document =
        BackupManager.Document(
            version = 1,
            createdAt = "2026-10-04T13:40:00+08:00",
            appVersion = "2.3.4",
            preferences = mapOf(*preferences)
        )

    // --- envelope ---

    @Test
    fun `round-trips a document it produced itself`() {
        val json = BackupManager.buildDocument(
            preferences = mapOf("showWeather" to true, "textMainSize" to 26.0),
            now = 1759556400000L,
            appVersion = "2.3.4-hubert.1"
        )

        val document = ok(json)
        assertEquals(1, document.version)
        assertEquals("2.3.4-hubert.1", document.appVersion)
        assertEquals(true, document.preferences["showWeather"])
        assertEquals(26.0, document.preferences["textMainSize"])
    }

    @Test
    fun `names the file after the moment of export`() {
        // The timestamp is formatted in local time, so only the shape is asserted here.
        val name = BackupManager.suggestedFileName(1759556400000L)
        val stamp = name.removePrefix("another-widget-backup-").removeSuffix(".json")

        assertTrue(name.startsWith("another-widget-backup-"))
        assertTrue(name.endsWith(".json"))
        assertEquals(15, stamp.length) // yyyy-MM-dd-HHmm
        assertTrue(stamp.all { it.isDigit() || it == '-' })
    }

    @Test
    fun `refuses broken json`() {
        assertEquals(BackupManager.Failure.NOT_JSON, failure("{ this is not json"))
        assertEquals(BackupManager.Failure.NOT_JSON, failure(""))
        assertEquals(BackupManager.Failure.NOT_JSON, failure("[1,2,3]"))
    }

    @Test
    fun `refuses valid json that is not one of our backups`() {
        assertEquals(BackupManager.Failure.NOT_A_BACKUP, failure("""{"hello":"world"}"""))
        assertEquals(
            BackupManager.Failure.NOT_A_BACKUP,
            failure("""{"format":"some-other-app","version":1,"preferences":{"a":1}}""")
        )
    }

    @Test
    fun `requires an explicit version`() {
        assertEquals(
            BackupManager.Failure.NOT_A_BACKUP,
            failure("""{"format":"another-widget-backup","preferences":{"a":1}}""")
        )
    }

    @Test
    fun `refuses a backup from a newer app version`() {
        assertEquals(
            BackupManager.Failure.UNSUPPORTED_VERSION,
            failure("""{"format":"another-widget-backup","version":99,"preferences":{"a":1}}""")
        )
    }

    @Test
    fun `refuses a backup with nothing in it`() {
        assertEquals(
            BackupManager.Failure.EMPTY,
            failure("""{"format":"another-widget-backup","version":1,"preferences":{}}""")
        )
        assertEquals(
            BackupManager.Failure.EMPTY,
            failure("""{"format":"another-widget-backup","version":1}""")
        )
    }

    @Test
    fun `tolerates unknown top level fields`() {
        val document = ok(
            """{"format":"another-widget-backup","version":1,"createdAt":"2026-10-04T13:40:00+08:00",
               "appVersion":"9.9.9","somethingNew":{"nested":true},"preferences":{"showWeather":true}}"""
        )
        assertEquals(true, document.preferences["showWeather"])
    }

    // --- validation: a bad value is skipped, never written ---

    @Test
    fun `keeps valid values and skips values that fail validation`() {
        val backup = BackupManager.validate(
            document(
                "showWeather" to true,                       // fine
                "weatherTempUnit" to "X",                    // not a unit
                "customLocationLat" to "999.0",              // outside [-90, 90]
                "customLocationLon" to "113.26",             // fine
                "textGlobalColor" to "not-a-color",          // not a colour
                "clockTextSize" to 90.0,                     // fine
                "weatherIconPack" to 42                      // no such pack
            )
        )

        assertTrue("showWeather" in backup.restoredNames)
        assertTrue("customLocationLon" in backup.restoredNames)
        assertTrue("clockTextSize" in backup.restoredNames)
        assertTrue("weatherTempUnit" in backup.skippedNames)
        assertTrue("customLocationLat" in backup.skippedNames)
        assertTrue("textGlobalColor" in backup.skippedNames)
        assertTrue("weatherIconPack" in backup.skippedNames)
    }

    @Test
    fun `missing settings are left alone instead of being reset`() {
        val backup = BackupManager.validate(document("showWeather" to true))

        assertTrue("showWeather" in backup.restoredNames)
        // Everything else is skipped, which leaves the current value (or the default) in place.
        assertFalse("weatherTempUnit" in backup.restoredNames)
        assertTrue("weatherTempUnit" in backup.skippedNames)
    }

    @Test
    fun `ignores settings it does not know`() {
        val backup = BackupManager.validate(
            document("showWeather" to true, "somethingFromTheFuture" to 7, "anotherUnknown" to "x")
        )

        assertTrue("showWeather" in backup.restoredNames)
        assertFalse("somethingFromTheFuture" in backup.restoredNames)
        assertFalse("somethingFromTheFuture" in backup.skippedNames)
    }

    @Test
    fun `refuses a wrong type instead of guessing`() {
        val backup = BackupManager.validate(
            document(
                "showWeather" to "yes",        // a string where a boolean belongs
                "textMainSize" to true,        // a boolean where a number belongs
                "customNotes" to 12            // a number where text belongs
            )
        )

        assertTrue(backup.restoredNames.isEmpty())
        // Rejected values and absent ones both end up skipped; neither is written.
        assertTrue("showWeather" in backup.skippedNames)
        assertTrue("textMainSize" in backup.skippedNames)
        assertTrue("customNotes" in backup.skippedNames)
    }

    // --- the audit itself: what must never be in a backup ---

    @Test
    fun `never carries credentials`() {
        val names = BackupSchema.entries.map { it.name }

        assertFalse("weatherProviderApiQWeather" in names)
        assertTrue("the API host is not a secret and should still travel",
            "weatherProviderQWeatherHost" in names)
    }

    @Test
    fun `never carries runtime state`() {
        val names = BackupSchema.entries.map { it.name }

        listOf(
            "weatherForecastCache", "weatherIcon", "weatherTemp", "weatherRealTempUnit",
            "lastLocationTimestamp", "weatherProviderError", "weatherProviderLocationError",
            "nextEventId", "nextEventName", "lastNotificationId", "mediaPlayerTitle",
            "isBatteryLevelLow", "isCharging", "googleFitSteps", "customFontFile",
            "installedIntegrations"
        ).forEach { assertFalse("$it must not be backed up", it in names) }
    }

    @Test
    fun `the schema has no duplicate entries`() {
        val names = BackupSchema.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `manual location travels but the automatic location cache does not`() {
        val names = BackupSchema.entries.map { it.name }

        assertTrue("customLocationAdd" in names)
        assertTrue("customLocationLat" in names)
        assertTrue("customLocationLon" in names)
        // The coordinates are only exported for a manual location; see collectConfiguration().
        assertEquals(setOf("customLocationLat", "customLocationLon"), BackupSchema.MANUAL_COORDINATES)
        assertFalse("lastLocationTimestamp" in names)
    }

    @Test
    fun `a document with every known setting validates cleanly`() {
        // Ask each entry which of these it accepts, so the test does not need to know the schema
        // types and never has to touch the stored preferences.
        val candidates = listOf<Any>("#FFFFFF", "FF", "C", "23.13", 1, 0, 20.0, true, "", "x")
        val values = BackupSchema.entries.associate { entry ->
            val accepted = candidates.firstOrNull { entry.accept(it) != null }
            assertNotNull("no candidate value was accepted for ${entry.name}", accepted)
            entry.name to accepted!!
        }

        val backup = BackupManager.validate(
            BackupManager.Document(1, "2026-10-04T13:40:00+08:00", "test", values)
        )

        assertEquals(BackupSchema.entries.size, backup.restoredNames.size)
        assertTrue("unexpected skips: ${backup.skippedNames}", backup.skippedNames.isEmpty())
    }

    @Test
    fun `an exported document contains no secret material`() {
        val json = BackupManager.buildDocument(
            preferences = mapOf("showWeather" to true, "weatherProviderQWeatherHost" to "example.qweatherapi.com"),
            appVersion = "test"
        )

        assertNotNull(json)
        assertFalse(json.contains("X-QW-Api-Key"))
        assertFalse(json.contains("Authorization"))
        assertFalse(json.contains("PREF_WEATHER_PROVIDER_API_QWEATHER"))
    }
}
