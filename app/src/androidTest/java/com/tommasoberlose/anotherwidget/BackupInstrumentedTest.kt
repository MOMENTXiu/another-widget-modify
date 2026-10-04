package com.tommasoberlose.anotherwidget

import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tommasoberlose.anotherwidget.global.Preferences
import com.tommasoberlose.anotherwidget.helpers.BackupManager
import com.tommasoberlose.anotherwidget.databinding.ActivityBackupRestoreBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Round-trip on a real device: the parts the JVM tests cannot reach, namely the file access through
 * ContentResolver, the preference transaction, and the refresh that follows a restore.
 *
 * The two settings it changes are put back afterwards, so running this does not disturb the app.
 */
@RunWith(AndroidJUnit4::class)
class BackupInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = File(context.cacheDir, "backup-instrumented-test.json")
    private val uri: Uri = Uri.fromFile(file)

    // snapshots of everything this test touches
    private val originalMainSize = Preferences.textMainSize
    private val originalDividers = Preferences.showDividers
    private val originalForecastCache = Preferences.weatherForecastCache
    private val originalLocationTimestamp = Preferences.lastLocationTimestamp

    @After
    fun putEverythingBack() {
        Preferences.textMainSize = originalMainSize
        Preferences.showDividers = originalDividers
        Preferences.weatherForecastCache = originalForecastCache
        Preferences.lastLocationTimestamp = originalLocationTimestamp
        file.delete()
    }

    @Test
    fun exportsRestoresAndKeepsSecretsOut() {
        // A distinctive configuration to export.
        Preferences.textMainSize = 31.5f
        Preferences.showDividers = false
        Preferences.weatherForecastCache = """{"fetchedAt":1,"hours":[]}"""
        Preferences.lastLocationTimestamp = 123456789L

        // 1. export writes a real file
        assertTrue("export failed", BackupManager.export(context, uri))
        assertTrue("no file was written", file.exists() && file.length() > 0)

        val json = file.readText()
        // 2. the exported document carries no secret and no cache
        assertFalse(json.contains(Preferences.weatherProviderApiQWeather.takeIf { it.isNotEmpty() } ?: "@@none@@"))
        assertFalse(json.contains("weatherForecastCache"))
        assertFalse(json.contains("lastLocationTimestamp"))
        assertFalse(json.contains("weatherProviderApiQWeather"))

        // 3. change the configuration, then restore it from the file
        Preferences.textMainSize = 12f
        Preferences.showDividers = true

        val readBack = BackupManager.read(context, uri)
        assertTrue("the file could not be read back", readBack != null)

        val parsed = BackupManager.parse(readBack!!)
        assertTrue("the exported file did not parse: $parsed", parsed is BackupManager.ParseResult.Ok)

        val backup = BackupManager.validate((parsed as BackupManager.ParseResult.Ok).document)
        assertTrue("restore failed", BackupManager.restore(context, backup))

        // 4. the configuration is back, and the weather cache was dropped
        assertEquals(31.5f, Preferences.textMainSize, 0.001f)
        assertEquals(false, Preferences.showDividers)
        assertEquals("", Preferences.weatherForecastCache)
        assertEquals(0L, Preferences.lastLocationTimestamp)
    }

    @Test
    fun refusesAFileThatIsNotABackup() {
        Preferences.textMainSize = 24f
        file.writeText("""{"format":"something-else","version":1,"preferences":{"textMainSize":99}}""")

        val parsed = BackupManager.parse(file.readText())
        assertTrue(parsed is BackupManager.ParseResult.Error)

        // Nothing was applied.
        assertEquals(24f, Preferences.textMainSize, 0.001f)
    }

    @Test
    fun theScreenLayoutInflates() {
        // Inflating the screen's layout is what would catch a broken style, colour or drawable at
        // runtime. Launching the activity itself is not possible while the device blocks background
        // activity starts, so the layout is inflated with the activity's own theme instead.
        val themed = ContextThemeWrapper(context, R.style.AppTheme)
        val binding = ActivityBackupRestoreBinding.inflate(LayoutInflater.from(themed))

        assertTrue(binding.actionExport != null)
        assertTrue(binding.actionRestore != null)
        assertTrue(binding.status != null)
        assertTrue(binding.actionBack != null)
        // The screen starts with no status message shown.
        assertEquals(View.GONE, binding.status.visibility)
    }
}
