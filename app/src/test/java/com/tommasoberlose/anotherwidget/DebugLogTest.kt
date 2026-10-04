package com.tommasoberlose.anotherwidget

import com.tommasoberlose.anotherwidget.helpers.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Calendar
import java.util.TimeZone

/** The line format, the secret scrubbing and the rotation, none of which need a device. */
class DebugLogTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, millis: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis

    // --- stamp: logcat-shaped wall clock with the local offset ---

    @Test
    fun `stamps carry milliseconds and the local offset`() {
        val stamp = DebugLog.stamp(utc(2026, 10, 5, 0, 30, millis = 742))
        assertTrue(
            "unexpected stamp: $stamp",
            stamp.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}"""))
        )
    }

    // --- the logcat-like record format ---

    @Test
    fun `records look like logcat with tag, pid and thread`() {
        val line = DebugLog.line(
            utc(2026, 10, 5, 0, 30, millis = 742), 'D', "LocationService",
            "checkSelfPermission ACCESS_FINE_LOCATION=GRANTED", 12345, "main", emptyList()
        )

        assertTrue(
            line.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2} D/LocationService\(12345, main\): checkSelfPermission ACCESS_FINE_LOCATION=GRANTED"""))
        )
    }

    @Test
    fun `warn and error levels are preserved`() {
        val line = DebugLog.line(
            utc(2026, 10, 5, 0, 30), 'W', "QWeatherApi", "request failed type=SocketTimeoutException", 1, "DefaultDispatcher", emptyList()
        )

        assertTrue(line.contains(" W/QWeatherApi(1, DefaultDispatcher): request failed"))
    }

    // --- redaction: credentials must never survive ---

    @Test
    fun `registered secrets are replaced`() {
        val key = "e74c22003b6e4744a95f322a6a84179"
        val line = DebugLog.redact(
            "request host=example.qweatherapi.com key=$key",
            listOf(key)
        )

        assertFalse(line.contains(key))
        assertTrue(line.contains("key=***"))
    }

    @Test
    fun `credential headers and query values are scrubbed even when unregistered`() {
        val scrubbed = DebugLog.redact(
            "GET /weather/v1/hourly/23.13/113.26 X-QW-Api-Key: abc1234567 " +
                "Authorization: Bearer eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9 api_key=deadbeefcafe",
            emptyList()
        )

        assertFalse(scrubbed.contains("abc1234567"))
        assertFalse(scrubbed.contains("eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse(scrubbed.contains("deadbeefcafe"))
        assertTrue(scrubbed.contains("X-QW-Api-Key: ***"))
        assertTrue(scrubbed.contains("Authorization: ***"))
        assertTrue(scrubbed.contains("api_key=***"))
    }

    @Test
    fun `ordinary text is not mangled`() {
        val text = "weather cache hit entries=24 location=guangzhou"
        assertEquals(text, DebugLog.redact(text, emptyList()))
    }

    @Test
    fun `short values are not treated as secrets`() {
        val text = "provider=network result=30"
        assertEquals(text, DebugLog.redact(text, listOf("network")))
    }

    // --- flow ids ---

    @Test
    fun `flow ids are six lowercase hex characters and pairwise distinct`() {
        val ids = (1..50).map { DebugLog.newFlowId() }

        ids.forEach { id ->
            assertTrue("unexpected flow id: $id", id.matches(Regex("""[0-9a-f]{6}""")))
        }
        assertEquals("flow ids must not repeat", ids.size, ids.toSet().size)
    }

    // --- rotation ---

    @Test
    fun `rotates and keeps at most the configured number of files`() {
        val dir = temporaryFolder.newFolder("logs")
        val current = java.io.File(dir, "debug.log")

        current.writeText("x".repeat(60))
        DebugLog.rotate(current, maxBytes = 50, maxFiles = 3)
        assertEquals("x".repeat(60), java.io.File(dir, "debug.1.log").readText())
        assertNull(DebugLog.files(dir, 3).firstOrNull { it.name == "debug.2.log" })

        current.writeText("y".repeat(60))
        DebugLog.rotate(current, maxBytes = 50, maxFiles = 3)
        assertEquals("x".repeat(60), java.io.File(dir, "debug.2.log").readText())

        current.writeText("z".repeat(60))
        DebugLog.rotate(current, maxBytes = 50, maxFiles = 3)
        // The oldest content (x) was dropped as the cap; the append that follows every rotation
        // recreates debug.log, so a steady state holds exactly three files.
        current.writeText("append")
        assertEquals(3, DebugLog.files(dir, 3).size)
        assertEquals("y".repeat(60), java.io.File(dir, "debug.2.log").readText())
        assertEquals("z".repeat(60), java.io.File(dir, "debug.1.log").readText())
        assertFalse(java.io.File(dir, "debug.3.log").exists())
    }

    @Test
    fun `a small log is not rotated`() {
        val dir = temporaryFolder.newFolder("logs2")
        val current = java.io.File(dir, "debug.log")
        current.writeText("tiny")

        DebugLog.rotate(current, maxBytes = 50, maxFiles = 3)

        assertTrue(current.exists())
        assertFalse(java.io.File(dir, "debug.1.log").exists())
    }

    @Test
    fun `lists the current file first`() {
        val dir = temporaryFolder.newFolder("logs3")
        val current = java.io.File(dir, "debug.log")
        current.writeText("current")

        DebugLog.rotate(current, maxBytes = 5, maxFiles = 3)
        java.io.File(dir, "debug.log").writeText("newest")

        val files = DebugLog.files(dir, 3)
        assertEquals("debug.log", files.first().name)
        assertEquals("debug.1.log", files[1].name)
        assertEquals("newest", files.first().readText())
    }
}
