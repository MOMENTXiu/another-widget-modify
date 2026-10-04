package com.tommasoberlose.anotherwidget.helpers

import android.content.Context
import android.util.Log
import com.tommasoberlose.anotherwidget.global.Preferences
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The opt-in diagnostic logger, shaped like logcat instead of a business summary.
 *
 * Off by default and a no-op while off. When enabled, every record goes to logcat with the *real*
 * class tag (`WeatherReceiver`, `LocationService`, …) and to the same record in a small set of
 * rotated files, so `adb logcat -v threadtime` and the exported file can be aligned by timestamp:
 *
 *     2026-10-05 00:30:39.742+08:00 D/LocationService(12345, main): requestLocationUpdates provider=network
 *
 * Logging is fire and forget: the file write happens on a single background thread, so a slow disk
 * can never stall a widget update, and every failure is swallowed. Nothing here may change how the
 * app behaves.
 *
 * Callers must not pass credentials; registered secrets are scrubbed from every line as a safety net.
 */
object DebugLogger {

    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val MAX_FILES = 3

    private val secrets = CopyOnWriteArraySet<String>()
    private var appContext: Context? = null
    private val pid: Int by lazy { android.os.Process.myPid() }

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

    fun d(tag: String, message: String) = dispatch('D', tag, message, null)

    fun i(tag: String, message: String) = dispatch('I', tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = dispatch('W', tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = dispatch('E', tag, message, error)

    private fun dispatch(level: Char, tag: String, message: String, error: Throwable?) {
        if (!isEnabled()) return

        val safeMessage = DebugLog.redact(message, secrets)

        try {
            when (level) {
                'I' -> Log.i(tag, safeMessage)
                'W' -> Log.w(tag, safeMessage, error)
                'E' -> Log.e(tag, safeMessage, error)
                else -> Log.d(tag, safeMessage)
            }
        } catch (ignored: Exception) {
            // Logcat failing must not stop the file, and neither may stop the app.
        }

        try {
            writer.execute { write(appContext ?: return@execute, level, tag, safeMessage, error) }
        } catch (ignored: Exception) {
            // The queue is bounded and single threaded; a rejection is simply a lost debug line.
        }
    }

    /** The thread name must be captured on the calling thread, so the record is built here. */
    private fun write(context: Context, level: Char, tag: String, message: String, error: Throwable?) {
        try {
            val line = DebugLog.line(
                System.currentTimeMillis(), level, tag, message, pid, Thread.currentThread().name, secrets
            )

            val trace = error?.let { DebugLog.redact(Log.getStackTraceString(it), secrets) }.orEmpty()

            val directory = File(context.filesDir, "debug-logs").apply { mkdirs() }
            val current = File(directory, "debug.log")

            DebugLog.rotate(current, MAX_FILE_BYTES, MAX_FILES)
            if (trace.isEmpty()) {
                current.appendText(line + "\n")
            } else {
                current.appendText("$line\n$trace\n")
            }
        } catch (ignored: Exception) {
            // Disk full, permissions, whatever: a debug line is never worth an exception.
        }
    }

    /** Waits for the queued lines to reach disk, so an export sees everything logged so far. */
    fun flush(timeoutMs: Long = 2000L) {
        try {
            writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (ignored: Exception) {
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
        builder.append("Exported At: ").append(DebugLog.stamp(System.currentTimeMillis())).append('\n')
        builder.append('\n')

        files.forEach { file ->
            builder.append("---- ").append(file.name).append('\n')
            builder.append(DebugLog.redact(file.readText(), secrets))
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
}
