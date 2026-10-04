package com.tommasoberlose.anotherwidget.helpers

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Pure helpers behind [DebugLogger]: the logcat-like line format, secret redaction and log
 * rotation. None of this touches Android, so the behaviour can be verified without a device.
 */
object DebugLog {

    /** Short correlation id tying the widget trigger, location, weather and render logs of one
     * refresh together. Best effort only: an independent flow id is better than none. */
    fun newFlowId(): String = UUID.randomUUID().toString().replace("-", "").take(6)

    // Header names and query parameters that must never reach a log line with their value.
    // The optional "bearer" prefix is swallowed too, so `Authorization: Bearer <jwt>` loses the jwt.
    private val SENSITIVE = Regex(
        "(?i)((x-qw-api-key|authorization|api[_-]?key|api[_-]?secret|token|cookie|password|jwt)\\s*[:=]\\s*)(bearer\\s+)?[^\\s&]+"
    )

    /**
     * Local wall clock as `yyyy-MM-dd HH:mm:ss.SSS±hh:mm`, e.g. `2026-10-05 00:30:39.742+08:00`.
     * Built by hand because `SimpleDateFormat` only understands the `X` offset pattern from API 24
     * and this app supports API 23.
     */
    fun stamp(now: Long): String {
        val wallClock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
        return wallClock + offset(now)
    }

    /** Forecast times keep the strict ISO spelling, e.g. `2026-10-04T12:00:00.000+08:00`. */
    fun timestamp(now: Long): String {
        val wallClock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(now))
        return wallClock + offset(now)
    }

    private fun offset(now: Long): String {
        val offset = TimeZone.getDefault().getOffset(now)
        val sign = if (offset < 0) "-" else "+"
        val minutes = Math.abs(offset) / 60000

        return sign + String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
    }

    /**
     * One logcat-shaped record:
     * `2026-10-05 00:30:39.742+08:00 D/LocationService(12345, main): checkSelfPermission ACCESS_FINE_LOCATION=GRANTED`
     */
    fun line(
        now: Long,
        level: Char,
        tag: String,
        message: String,
        pid: Int,
        thread: String,
        secrets: Collection<String>
    ): String = redact("${stamp(now)} $level/$tag($pid, $thread): $message", secrets)

    /**
     * Scrubs registered secrets and any credential-looking header or query assignment. Values shorter
     * than 8 characters are left alone so ordinary words are not mangled.
     */
    fun redact(text: String, secrets: Collection<String>): String {
        var result = text

        secrets.forEach { secret ->
            if (secret.length >= 8) result = result.replace(secret, "***")
        }

        return SENSITIVE.replace(result) { match -> match.groupValues[1] + "***" }
    }

    /**
     * Rotates `debug.log` into `debug.1.log`, `debug.2.log`, …, dropping the oldest one, once the
     * current file has reached [maxBytes]. At most [maxFiles] files are kept.
     */
    fun rotate(current: File, maxBytes: Long, maxFiles: Int) {
        if (!current.exists() || current.length() < maxBytes) return

        for (index in (maxFiles - 2) downTo 1) {
            val older = numbered(current, index)
            if (older.exists()) older.renameTo(numbered(current, index + 1))
        }

        current.renameTo(numbered(current, 1))
    }

    fun numbered(current: File, index: Int): File =
        File(current.parentFile, current.name.removeSuffix(".log") + ".$index.log")

    /** The log files that exist, newest first. */
    fun files(directory: File, maxFiles: Int): List<File> {
        val current = File(directory, "debug.log")
        val rotated = (1 until maxFiles).map { numbered(current, it) }.filter { it.exists() }

        return listOf(current).filter { it.exists() } + rotated
    }
}
