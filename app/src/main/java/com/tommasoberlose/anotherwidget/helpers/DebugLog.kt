package com.tommasoberlose.anotherwidget.helpers

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure helpers behind [DebugLogger]: the line format, secret redaction and log rotation. None of
 * this touches Android, so the behaviour can be verified without a device.
 */
object DebugLog {

    const val TAG = "AnotherWidgetDebug"

    // Header names and query parameters that must never reach a log line with their value.
    // The optional "bearer" prefix is swallowed too, so `Authorization: Bearer <jwt>` loses the jwt.
    private val SENSITIVE = Regex(
        "(?i)((x-qw-api-key|authorization|api[_-]?key|api[_-]?secret|token|cookie|password|jwt)\\s*[:=]\\s*)(bearer\\s+)?[^\\s&]+"
    )

    /**
     * Local time as ISO 8601 with milliseconds and the UTC offset, e.g.
     * `2026-10-04T23:30:12.384+08:00`. Built by hand because `SimpleDateFormat` only understands the
     * `X` offset pattern from API 24 and this app supports API 23.
     */
    fun timestamp(now: Long): String {
        val wallClock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(now))
        val offset = TimeZone.getDefault().getOffset(now)
        val sign = if (offset < 0) "-" else "+"
        val minutes = Math.abs(offset) / 60000

        return wallClock + sign + String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
    }

    /** `2026-10-04T23:30:12.384+08:00 [LOCATION] request_start provider=network timeoutMs=15000` */
    fun line(
        now: Long,
        category: String,
        event: String,
        fields: List<Pair<String, Any?>>,
        secrets: Collection<String>
    ): String {
        val builder = StringBuilder(timestamp(now))
            .append(" [").append(category).append("] ").append(event)

        fields.forEach { (name, value) ->
            if (value != null) builder.append(' ').append(name).append('=').append(value)
        }

        return redact(builder.toString(), secrets)
    }

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
