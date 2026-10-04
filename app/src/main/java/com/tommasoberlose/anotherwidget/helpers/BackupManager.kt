package com.tommasoberlose.anotherwidget.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.chibatching.kotpref.bulk
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.tommasoberlose.anotherwidget.BuildConfig
import com.tommasoberlose.anotherwidget.global.Constants
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.receivers.WeatherReceiver
import com.tommasoberlose.anotherwidget.ui.fragments.MainFragment
import com.tommasoberlose.anotherwidget.ui.widgets.MainWidget
import org.greenrobot.eventbus.EventBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A backup that was read and accepted, ready to be applied as one transaction. */
class ValidatedBackup internal constructor(
    val version: Int,
    val createdAt: String,
    val appVersion: String,
    val restoredNames: List<String>,
    val skippedNames: List<String>,
    private val transaction: () -> Unit
) {
    /** Writes everything at once; nothing has been touched before this call. */
    internal fun apply() = transaction()
}

/**
 * Export and restore of the user's configuration as a versioned, human readable JSON document.
 *
 * Nothing here mutates preferences while reading or validating: [validate] only builds the write
 * transaction, and it is executed by [restore] once the caller has confirmed.
 */
object BackupManager {

    const val FORMAT = "another-widget-backup"
    const val VERSION = 1

    private const val MAX_FILE_BYTES = 4 * 1024 * 1024

    private val gson = Gson()

    class Document(
        val version: Int,
        val createdAt: String,
        val appVersion: String,
        val preferences: Map<String, Any>
    )

    enum class Failure { NOT_JSON, NOT_A_BACKUP, UNSUPPORTED_VERSION, EMPTY }

    sealed class ParseResult {
        class Ok(val document: Document) : ParseResult()
        class Error(val reason: Failure) : ParseResult()
    }

    // ---------------------------------------------------------------- export

    fun suggestedFileName(now: Long = System.currentTimeMillis()): String =
        "another-widget-backup-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now))}.json"

    /** The configuration to export: the schema, minus the automatic location cache. */
    fun collectConfiguration(): Map<String, Any> {
        val values = linkedMapOf<String, Any>()

        BackupSchema.entries.forEach { entry ->
            // Without a manual location these fields hold the automatic location cache.
            if (entry.name in BackupSchema.MANUAL_COORDINATES && Preferences.customLocationAdd == "") return@forEach
            values[entry.name] = entry.current()
        }

        return values
    }

    fun buildDocument(
        preferences: Map<String, Any> = collectConfiguration(),
        now: Long = System.currentTimeMillis(),
        appVersion: String = BuildConfig.VERSION_NAME
    ): String {
        val document = linkedMapOf<String, Any>(
            "format" to FORMAT,
            "version" to VERSION,
            "createdAt" to DebugLog.timestamp(now),
            "appVersion" to appVersion,
            "preferences" to preferences
        )
        return gson.toJson(document)
    }

    fun export(context: Context, uri: Uri): Boolean = try {
        // "wt" truncates, so re-exporting over an existing file cannot leave stale bytes behind.
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(buildDocument().toByteArray(Charsets.UTF_8))
            stream.flush()
        } != null
    } catch (ex: Exception) {
        Log.w(Constants.LOG_TAG, "backup export failed: ${ex.javaClass.simpleName}")
        false
    }

    // ---------------------------------------------------------------- import

    fun read(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(MAX_FILE_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            if (total > MAX_FILE_BYTES) null else String(buffer, 0, total, Charsets.UTF_8)
        }
    } catch (ex: Exception) {
        Log.w(Constants.LOG_TAG, "backup could not be read: ${ex.javaClass.simpleName}")
        null
    }

    /**
     * Reads and checks the envelope only. Unknown fields are ignored, and a version newer than this
     * build is refused rather than half understood.
     */
    fun parse(json: String): ParseResult {
        val root = try {
            JsonParser.parseString(json)
        } catch (ex: Exception) {
            return ParseResult.Error(Failure.NOT_JSON)
        }

        if (!root.isJsonObject) return ParseResult.Error(Failure.NOT_JSON)
        val document = root.asJsonObject

        if (document.get("format")?.takeIf { it.isJsonPrimitive }?.asString != FORMAT) {
            return ParseResult.Error(Failure.NOT_A_BACKUP)
        }

        val version = document.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: return ParseResult.Error(Failure.NOT_A_BACKUP)

        // A simple dispatch is enough for now; v1 is the only version that exists.
        if (version > VERSION) return ParseResult.Error(Failure.UNSUPPORTED_VERSION)

        val preferences = document.get("preferences")?.takeIf { it.isJsonObject }
            ?: return ParseResult.Error(Failure.EMPTY)

        val values: Map<String, Any> = try {
            gson.fromJson(preferences, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()
        } catch (ex: Exception) {
            return ParseResult.Error(Failure.NOT_JSON)
        }

        if (values.isEmpty()) return ParseResult.Error(Failure.EMPTY)

        return ParseResult.Ok(
            Document(
                version = version,
                createdAt = document.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                appVersion = document.get("appVersion")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                preferences = values
            )
        )
    }

    /**
     * Turns parsed values into a single write transaction. Values that fail validation are skipped
     * rather than refused, and unknown names are ignored, so a partial or newer backup still restores
     * everything it legitimately can.
     */
    fun validate(document: Document): ValidatedBackup {
        val writes = mutableListOf<() -> Unit>()
        val restored = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        BackupSchema.entries.forEach { entry ->
            val raw = document.preferences[entry.name]
            if (raw == null) {
                skipped.add(entry.name)
                return@forEach
            }

            val accepted = entry.accept(raw)
            if (accepted == null) {
                // Reported by the caller; nothing at all is written for a rejected value.
                skipped.add(entry.name)
            } else {
                writes.add { entry.apply(accepted) }
                restored.add(entry.name)
            }
        }

        val transaction = {
            Preferences.bulk {
                writes.forEach { it.invoke() }

                // Runtime state never travels in a backup, so it must not survive a restore either.
                weatherForecastCache = ""
                lastLocationTimestamp = 0L
                weatherProviderError = ""
                weatherProviderLocationError = ""
                if (customLocationAdd == "") {
                    customLocationLat = ""
                    customLocationLon = ""
                }
            }
            Unit
        }

        return ValidatedBackup(
            version = document.version,
            createdAt = document.createdAt,
            appVersion = document.appVersion,
            restoredNames = restored,
            skippedNames = skipped,
            transaction = transaction
        )
    }

    /** Applies a validated backup and rebuilds everything that depends on the configuration. */
    fun restore(context: Context, backup: ValidatedBackup): Boolean = try {
        backup.apply()

        // The schedule and the widget follow the restored configuration.
        WeatherReceiver.setUpdates(context)
        MainWidget.updateWidget(context, "restore")
        EventBus.getDefault().post(MainFragment.UpdateUiMessageEvent())

        Log.d(
            Constants.LOG_TAG,
            "backup restored: ${backup.restoredNames.size} settings applied, " +
                "${backup.skippedNames.size} skipped (${backup.skippedNames.joinToString()})"
        )
        true
    } catch (ex: Exception) {
        ex.printStackTrace()
        false
    }
}
