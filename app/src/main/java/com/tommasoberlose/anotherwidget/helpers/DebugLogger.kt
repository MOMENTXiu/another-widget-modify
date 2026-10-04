package com.tommasoberlose.anotherwidget.helpers

import android.content.Context
import android.util.Log
import com.tommasoberlose.anotherwidget.global.Preferences
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The opt-in diagnostic logger.
 *
 * Off by default and a no-op while off. When enabled it writes the same line to logcat and to a small
 * set of rotated files inside the app's own storage, so a logcat capture and the exported file can be
 * aligned by timestamp.
 *
 * Logging is fire and forget: the file write happens on a single background thread, so a slow disk can
 * never stall a widget update, and every failure is swallowed. Nothing here may change how the app
 * behaves.
 *
 * Callers must not pass credentials; registered secrets are scrubbed from every line as a safety net.
 */
object DebugLogger {

    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val MAX_FILES = 3

    private val secrets = CopyOnWriteArraySet<String>()
    private var appContext: Context? = null

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "debug-log").apply { isDaemon = true }
    }

    /** Called once from the application, so the call sites never need a context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isEnabled(): Boolean = Preferences.debugMode == 1

    /** Registered values are replaced with `***` in every written line. */
    fun rememberSecret(value: String?) {
        if (!value.isNullOrBlank() && value.length >= 8) secrets.add(value)
    }

    fun log(category: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!isEnabled()) return

        val line = DebugLog.line(System.currentTimeMillis(), category, event, fields.toList(), secrets)

        try {
            Log.d(DebugLog.TAG, line)
        } catch (ex: Exception) {
            // Logcat failing must not stop the file, and neither may stop the app.
        }

        try {
            writer.execute { write(appContext ?: return@execute, line) }
        } catch (ex: Exception) {
            // The queue is bounded and single threaded; a rejection is simply a lost debug line.
        }
    }

    private fun write(context: Context, line: String) = try {
        val directory = File(context.filesDir, "debug-logs").apply { mkdirs() }
        val current = File(directory, "debug.log")

        DebugLog.rotate(current, MAX_FILE_BYTES, MAX_FILES)
        current.appendText(line + "\n")
    } catch (ex: Exception) {
        // Disk full, permissions, whatever: a debug line is never worth an exception.
    }

    /** Waits for the queued lines to reach disk, so an export sees everything logged so far. */
    fun flush(timeoutMs: Long = 2000L) {
        try {
            writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (ex: Exception) {
            // Exporting without the last lines is better than hanging.
        }
    }

    fun directory(context: Context): File = File(context.filesDir, "debug-logs")

    /** `another-widget-debug-YYYY-MM-DD-HHmmss.log`, in local time. */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.US).format(java.util.Date(now))
        return "another-widget-debug-$stamp.log"
    }

    /** Header plus every stored line, ready to be written to the user chosen destination. */
    fun exportText(context: Context): String? {
        flush()

        val files = DebugLog.files(directory(context), MAX_FILES)
        if (files.isEmpty()) return null

        val builder = StringBuilder()
        builder.append("Another Widget Debug Log\n")
        builder.append("App Version: ").append(com.tommasoberlose.anotherwidget.BuildConfig.VERSION_NAME).append('\n')
        builder.append("Android Version: ").append(android.os.Build.VERSION.RELEASE)
            .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        builder.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append('\n')
        builder.append("Debug Mode: ").append(Preferences.debugMode).append('\n')
        builder.append("Exported At: ").append(DebugLog.timestamp(System.currentTimeMillis())).append('\n')
        builder.append('\n')

        files.forEach { file ->
            builder.append("---- ").append(file.name).append('\n')
            builder.append(redact(file.readText()))
            if (!builder.endsWith("\n")) builder.append('\n')
        }

        return builder.toString()
    }

    /** Removes every stored log file. */
    fun clear(context: Context): Int {
        flush()
        var removed = 0

        DebugLog.files(directory(context), MAX_FILES + 1).forEach { file ->
            if (file.delete()) removed++
        }

        return removed
    }

    private fun redact(text: String): String = DebugLog.redact(text, secrets)
}
