package com.tommasoberlose.anotherwidget.ui.activities.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.tommasoberlose.anotherwidget.R
import com.tommasoberlose.anotherwidget.databinding.ActivityBackupRestoreBinding
import com.tommasoberlose.anotherwidget.helpers.BackupManager

/**
 * Exports and restores the configuration through Android's own file picker, so the backup can live
 * in Downloads, on a drive, or anywhere else the user chooses, without this app asking for storage
 * permissions or talking to any service.
 */
class BackupRestoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupRestoreBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupRestoreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.actionBack.setOnClickListener { onBackPressed() }
        binding.actionExport.setOnClickListener { exportBackup() }
        binding.actionRestore.setOnClickListener { pickBackup() }
    }

    private fun exportBackup() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = MIME_JSON
                putExtra(Intent.EXTRA_TITLE, BackupManager.suggestedFileName())
            },
            REQUEST_EXPORT
        )
    }

    private fun pickBackup() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = MIME_JSON
                // Some providers hand back a generic type for a .json file, so let those through too.
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME_JSON, "text/plain", "application/octet-stream"))
            },
            REQUEST_RESTORE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> {
                val exported = BackupManager.export(this, uri)
                showStatus(getString(if (exported) R.string.backup_export_success else R.string.backup_export_failed), !exported)
            }
            REQUEST_RESTORE -> confirmRestore(uri)
        }
    }

    private fun confirmRestore(uri: Uri) {
        val json = BackupManager.read(this, uri)
        if (json == null) {
            showStatus(getString(R.string.backup_error_unreadable), true)
            return
        }

        when (val parsed = BackupManager.parse(json)) {
            is BackupManager.ParseResult.Error -> showStatus(messageFor(parsed.reason), true)

            is BackupManager.ParseResult.Ok -> {
                // Validation happens before anything is written, so a bad file cannot half-apply.
                val backup = BackupManager.validate(parsed.document)

                AlertDialog.Builder(this)
                    .setTitle(R.string.backup_restore_confirm_title)
                    .setMessage(
                        getString(
                            R.string.backup_restore_confirm_message,
                            parsed.document.createdAt.ifBlank { getString(R.string.backup_unknown_value) },
                            parsed.document.version,
                            parsed.document.appVersion.ifBlank { getString(R.string.backup_unknown_value) }
                        )
                    )
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_restore) { _, _ ->
                        showStatus(
                            getString(if (BackupManager.restore(this, backup)) R.string.backup_restore_success else R.string.backup_restore_failed),
                            false
                        )
                    }
                    .show()
            }
        }
    }

    private fun messageFor(reason: BackupManager.Failure): String = getString(
        when (reason) {
            BackupManager.Failure.NOT_JSON -> R.string.backup_error_not_json
            BackupManager.Failure.NOT_A_BACKUP -> R.string.backup_error_not_a_backup
            BackupManager.Failure.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported_version
            BackupManager.Failure.EMPTY -> R.string.backup_error_empty
        }
    )

    private fun showStatus(message: String, isError: Boolean) {
        binding.status.text = message
        binding.status.setTextColor(ContextCompat.getColor(this, if (isError) R.color.errorColorText else R.color.colorAccent))
        binding.status.isVisible = true
    }

    companion object {
        private const val REQUEST_EXPORT = 11
        private const val REQUEST_RESTORE = 12
        private const val MIME_JSON = "application/json"
    }
}
