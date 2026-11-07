package app.olauncher.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.BackupFormatException
import app.olauncher.data.ImportResult
import app.olauncher.data.SettingsBackupStore
import app.olauncher.databinding.CestaSettingsBackupBinding
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cesta Launcher: "Backup" section of the settings screen (export/import through the system file picker).
 * Create it as a property of the fragment, so the result launchers are registered before the fragment starts.
 */
class BackupSection(private val fragment: Fragment) {

    private val exportLauncher = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(::export)
    }
    private val importLauncher = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::confirmImport)
    }

    /** Inserts the section above the links row at the bottom of the settings screen. */
    fun attach(settings: FragmentSettingsBinding) {
        val links = settings.privacy.parent as? ViewGroup ?: return
        val container = links.parent as? ViewGroup ?: return
        val section = CestaSettingsBackupBinding.inflate(LayoutInflater.from(fragment.requireContext()), container, false)
        container.addView(section.root, container.indexOfChild(links))
        section.cestaBackupExport.setOnClickListener {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            exportLauncher.launch("cesta-launcher-$date.json")
        }
        section.cestaBackupImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    private fun export(uri: Uri) {
        val context = fragment.context?.applicationContext ?: return
        fragment.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    SettingsBackupStore(context).exportTo(uri)
                    true
                } catch (e: IOException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            }
            context.showToast(if (ok) R.string.cesta_backup_exported else R.string.cesta_backup_export_failed)
        }
    }

    private fun confirmImport(uri: Uri) {
        val context = fragment.context ?: return
        context.createDialog(
            title = R.string.cesta_backup_import_title,
            action = R.string.cesta_backup_import_action,
            message = R.string.cesta_backup_import_message,
            onAction = { import(uri) },
        ).showRespectingStatusBar()
    }

    private fun import(uri: Uri) {
        val context = fragment.context?.applicationContext ?: return
        fragment.lifecycleScope.launch {
            val result: Result<ImportResult> = withContext(Dispatchers.IO) {
                try {
                    Result.success(SettingsBackupStore(context).importFrom(uri))
                } catch (e: BackupFormatException) {
                    Result.failure(e)
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: SecurityException) {
                    Result.failure(e)
                }
            }
            result.fold(
                onSuccess = { r ->
                    val message = if (r.skippedSlots > 0)
                        context.getString(R.string.cesta_backup_imported_skipped, r.settings, r.lists, r.skippedSlots)
                    else context.getString(R.string.cesta_backup_imported, r.settings, r.lists)
                    context.showToast(message, android.widget.Toast.LENGTH_LONG)
                    // Theme, text size and home apps are read at start-up: restart the launcher UI.
                    fragment.activity?.recreate()
                },
                onFailure = { e ->
                    context.showToast(context.getString(R.string.cesta_backup_import_failed, e.message ?: ""))
                },
            )
        }
    }
}
