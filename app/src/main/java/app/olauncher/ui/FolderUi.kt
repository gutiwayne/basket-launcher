package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import app.olauncher.R
import app.olauncher.data.Folder
import app.olauncher.data.FolderApp
import app.olauncher.data.FolderCodec
import app.olauncher.data.FolderStore
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isAppCheckReliable
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast

/**
 * Cesta Launcher: folders in the home screen slots.
 * - Long press a slot: choose app or create folder; on a folder: add, remove, rename or delete.
 * - Tap a folder: list of its apps, using the same text style as the home screen.
 */
class FolderUi(
    private val fragment: Fragment,
    private val prefs: Prefs,
    private val launchApp: (FolderApp) -> Unit,
    private val chooseApp: (slot: Int) -> Unit,
    private val addToFolder: (slot: Int) -> Unit,
    private val refresh: () -> Unit,
) {
    private val context: Context get() = fragment.requireContext()
    private val store: FolderStore get() = FolderStore(context)

    /** Draws each folder name in its slot. Call after the home apps are drawn. */
    fun applyToHome(slotView: (Int) -> TextView, homeAppsNum: Int) {
        store.all().filter { it.slot in 1..homeAppsNum }.forEach { folder ->
            slotView(folder.slot).apply {
                text = folder.name + context.getString(R.string.cesta_folder_suffix)
                isVisible = true
            }
        }
    }

    /** Opens the folder if the slot holds one and returns true. */
    fun openIfFolder(slot: Int): Boolean {
        val folder = store.folderAt(slot) ?: return false
        val apps = visibleApps(folder)
        if (apps.isEmpty()) context.showToast(R.string.cesta_folder_empty)
        else showAppsDialog(folder.name, apps) { launchApp(it) }
        return true
    }

    /** Centered dialog with the slot actions (a popup anchored to the slot got cut off at the screen edge). */
    fun showSlotMenu(slot: Int) {
        val folder = store.folderAt(slot)
        val options = mutableListOf<Pair<Int, () -> Unit>>()
        if (folder == null) {
            options += R.string.cesta_choose_app to { chooseApp(slot) }
            options += R.string.cesta_create_folder to {
                askName(context.getString(R.string.cesta_folder_default_name)) { name ->
                    store.update { FolderCodec.create(it, slot, name, appInSlot(slot)) }
                    refresh()
                    addToFolder(slot)
                }
            }
        } else {
            options += R.string.cesta_add_app to { addToFolder(slot) }
            if (folder.apps.size > 1) {
                options += R.string.cesta_reorder_apps to { showReorderDialog(slot) }
                options += R.string.cesta_sort_apps to {
                    store.update { list -> FolderCodec.sortByName(list, slot) }
                }
            }
            if (folder.apps.isNotEmpty()) options += R.string.cesta_remove_app to {
                showAppsDialog(context.getString(R.string.cesta_remove_app), folder.apps) { app ->
                    store.update { list -> FolderCodec.removeApp(list, slot, app) }
                    refresh()
                }
            }
            options += R.string.cesta_rename_folder to {
                askName(folder.name) { name ->
                    store.update { list -> FolderCodec.rename(list, slot, name) }
                    refresh()
                }
            }
            options += R.string.cesta_delete_folder to {
                context.createDialog(
                    title = R.string.cesta_delete_folder,
                    action = R.string.cesta_delete,
                    message = R.string.cesta_delete_folder_message,
                    onAction = {
                        store.update { FolderCodec.delete(it, slot) }
                        refresh()
                    },
                ).showRespectingStatusBar()
            }
        }
        val title = folder?.name ?: prefs.getAppName(slot).ifEmpty { context.getString(R.string.cesta_add) }
        showListDialog(title, options.map { context.getString(it.first) }) { options[it].second() }
    }

    /** Each tap moves the app one position up and reopens the list, so several moves are quick. */
    private fun showReorderDialog(slot: Int) {
        val folder = store.folderAt(slot) ?: return
        showAppsDialog(context.getString(R.string.cesta_reorder_title), folder.apps) { app ->
            store.update { list -> FolderCodec.moveUp(list, slot, app) }
            showReorderDialog(slot)
        }
    }

    /** The app already in the slot becomes the first app of the new folder. */
    private fun appInSlot(slot: Int): FolderApp? {
        val name = prefs.getAppName(slot)
        val pkg = prefs.getAppPackage(slot)
        if (name.isEmpty() || pkg.isEmpty()) return null
        if (prefs.getIsShortcut(slot)) {
            val shortcutId = prefs.getShortcutId(slot).ifEmpty { return null }
            return FolderApp(name, pkg, null, prefs.getAppUser(slot), shortcutId)
        }
        return FolderApp(name, pkg, prefs.getAppActivityClassName(slot).ifEmpty { null }, prefs.getAppUser(slot))
    }

    /** Installed apps. Missing ones are only removed when Android confirms it (see isAppCheckReliable). */
    private fun visibleApps(folder: Folder): List<FolderApp> {
        val (installed, missing) = folder.apps.partition {
            if (it.isShortcut) isShortcutPinned(it) else isPackageInstalled(context, it.appPackage, it.user)
        }
        val gone = missing.filter { isAppCheckReliable(context, it.user) }
        if (gone.isNotEmpty()) {
            store.update { list -> FolderCodec.removeApps(list) { app -> gone.any { it.sameAppAs(app) } } }
        }
        return installed
    }

    private fun isShortcutPinned(app: FolderApp): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        return try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(app.appPackage)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }
            launcherApps.getShortcuts(query, getUserHandleFromString(context, app.user))?.any { it.id == app.shortcutId } == true
        } catch (e: Exception) {
            false
        }
    }

    private fun primaryColor(): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(R.attr.primaryColor, value, true)
        return value.data
    }

    private fun askName(initial: String, onName: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(initial)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(primaryColor())
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 16.dpToPx(); marginEnd = 8.dpToPx() }
        }
        context.createDialog(
            title = R.string.cesta_folder_name,
            action = R.string.cesta_save,
            onAction = {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            },
            content = { input },
        ).showRespectingStatusBar()
    }

    private fun showAppsDialog(title: String, apps: List<FolderApp>, onPick: (FolderApp) -> Unit) =
        showListDialog(title, apps.map { it.label }) { onPick(apps[it]) }

    private fun showListDialog(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        val dialog = OlDialog(context)
        val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        binding.tvTitle.text = title
        binding.tvAction.isVisible = false
        binding.ivClose.setOnClickListener { dialog.dismiss() }

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 12.dpToPx() }
        }
        val selectable = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        labels.forEachIndexed { index, label ->
            list.addView(TextView(context).apply {
                text = label
                setTextAppearance(R.style.TextLarge)
                setTextColor(primaryColor())
                gravity = prefs.homeAlignment
                setPadding(0, 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
                isFocusable = true
                setBackgroundResource(selectable)
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        binding.contentContainer.addView(list)
        binding.contentContainer.isVisible = true
        dialog.setView(binding.root)
        dialog.showRespectingStatusBar()
    }

}
