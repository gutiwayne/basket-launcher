package app.olauncher.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.BackupFormatException
import app.olauncher.data.ImportResult
import app.olauncher.data.SettingsBackupStore
import app.olauncher.databinding.BasketDialogProgressBinding
import app.olauncher.databinding.BasketSettingsBackupBinding
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Basket Launcher: "Backup" section of the settings screen (export/import through the system file picker).
 * Create it as a property of the fragment, so the result launchers are registered before the fragment starts.
 */
class BackupSection(private val fragment: Fragment) {

    private val exportLauncher = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        pickerOpen = false
        uri?.let(::export)
    }
    private val importLauncher = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerOpen = false
        uri?.let(::confirmImport)
    }

    /** Inserts the section above the links row at the bottom of the settings screen. */
    fun attach(settings: FragmentSettingsBinding) {
        val links = settings.privacy.parent as? ViewGroup ?: return
        val container = links.parent as? ViewGroup ?: return
        val section = BasketSettingsBackupBinding.inflate(LayoutInflater.from(fragment.requireContext()), container, false)
        container.addView(section.root, container.indexOfChild(links))
        section.basketBackupExport.setOnClickListener {
            // Date and time, so a second export the same day does not become "….json (1)" next to an older file.
            val date = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date())
            openPicker { exportLauncher.launch("basket-launcher-$date.json") }
        }
        section.basketBackupImport.setOnClickListener {
            openPicker { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        }
    }

    private fun openPicker(launch: () -> Unit) {
        pickerOpen = true
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            pickerOpen = false
            fragment.context?.showToast(R.string.basket_backup_no_picker)
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
            context.showToast(if (ok) R.string.basket_backup_exported else R.string.basket_backup_export_failed)
        }
    }

    private fun confirmImport(uri: Uri) {
        val context = fragment.context ?: return
        context.createDialog(
            title = R.string.basket_backup_import_title,
            action = R.string.basket_backup_import_action,
            message = R.string.basket_backup_import_message,
            onAction = { import(uri) },
        ).showRespectingStatusBar()
    }

    private fun import(uri: Uri) {
        val context = fragment.context?.applicationContext ?: return
        val progress = showProgress(R.string.basket_backup_importing)
        fragment.lifecycleScope.launch {
            val started = System.currentTimeMillis()
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
            // Keep the dialog up long enough to be seen, even when the import is instant.
            delay((MIN_PROGRESS_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            val message = result.fold(
                onSuccess = { r ->
                    if (r.skippedSlots > 0)
                        context.getString(R.string.basket_backup_imported_skipped, r.settings, r.lists, r.skippedSlots)
                    else context.getString(R.string.basket_backup_imported, r.settings, r.lists)
                },
                onFailure = { e -> context.getString(R.string.basket_backup_import_failed, e.message ?: "") },
            )
            val activity = fragment.activity
            val done = {
                context.showToast(message, android.widget.Toast.LENGTH_LONG)
                // Theme, text size and home apps are read at start-up: restart the launcher UI.
                if (result.isSuccess) activity?.recreate()
            }
            // Recreate only once the dialog is fully gone (it fades out), so it is not left on a dead window.
            if (progress == null) done() else {
                progress.setOnDismissListener { done() }
                progress.dismiss()
            }
        }
    }

    private fun showProgress(@StringRes text: Int): OlDialog? {
        val context = fragment.context ?: return null
        val dialog = OlDialog(context)
        val binding = BasketDialogProgressBinding.inflate(LayoutInflater.from(dialog.context))
        binding.basketProgressText.setText(text)
        dialog.setView(binding.root)
        dialog.setCancelable(false)
        dialog.showRespectingStatusBar()
        return dialog
    }

    companion object {
        private const val MIN_PROGRESS_MS = 1_200L

        /**
         * True while the system file picker is in front. The launcher normally goes back to the home screen
         * when it stops (MainActivity.backToHomeScreen), which would destroy Settings and drop the picked file.
         */
        @Volatile
        var pickerOpen = false
    }
}
