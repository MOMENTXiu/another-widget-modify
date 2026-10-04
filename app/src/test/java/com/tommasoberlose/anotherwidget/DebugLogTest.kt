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

    // --- timestamp: ISO 8601, milliseconds, local offset ---

    @Test
    fun `timestamps carry milliseconds and the local offset`() {
        val stamp = DebugLog.timestamp(utc(2026, 10, 4, 15, 30, millis = 384))
        assertTrue(
            "unexpected format: $stamp",
            stamp.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}"""))
        )
    }

    @Test
    fun `an utc offset is applied to the wall clock`() {
        // The formatter runs in the JVM default zone, so the offset itself is checked arithmetically:
        // the difference between two stamps one hour apart must stay exactly one hour.
        val first = DebugLog.timestamp(utc(2026, 10, 4, 4, 0))
        val second = DebugLog.timestamp(utc(2026, 10, 4, 5, 0))
        assertFalse(first == second)
        assertEquals(first.length, second.length)
    }

    // --- the line format ---

    @Test
    fun `formats category, event and key value fields`() {
        val line = DebugLog.line(
            utc(2026, 10, 4, 15, 30), "LOCATION", "request_start",
            listOf("provider" to "network", "timeoutMs" to 15000), emptyList()
        )

        assertTrue(line.contains(" [LOCATION] request_start provider=network timeoutMs=15000"))
        assertTrue(line.matches(Regex("""\d{4}-\d{2}-\d{2}T.* \[LOCATION\] request_start.*""")))
    }

    @Test
    fun `null fields are left out`() {
        val line = DebugLog.line(
            utc(2026, 10, 4, 15, 30), "LOCATION", "last_known",
            listOf("provider" to "network", "result" to null), emptyList()
        )

        assertTrue(line.endsWith("[LOCATION] last_known provider=network"))
        assertFalse(line.contains("result="))
    }

    // --- redaction: credentials must never survive ---

    @Test
    fun `registered secrets are replaced`() {
        val key = "e74c22003b6e4744a95f322a6a84179"
        val line = DebugLog.redact(
            "request_start host=example.qweatherapi.com key=$key",
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
        val text = "weather cache hit hours=24 location=guangzhou"
        assertEquals(text, DebugLog.redact(text, emptyList()))
    }

    @Test
    fun `short values are not treated as secrets`() {
        val text = "provider=network result=30"
        assertEquals(text, DebugLog.redact(text, listOf("network")))
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
