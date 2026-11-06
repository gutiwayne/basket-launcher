package app.olauncher.ui

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
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
import app.olauncher.helper.isAppCheckReliable
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showPopupMenu
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

    fun showSlotMenu(anchor: View, slot: Int) {
        val folder = store.folderAt(slot)
        anchor.showPopupMenu(configure = { menu ->
            if (folder == null) {
                menu.add(0, MENU_CHOOSE_APP, 0, R.string.cesta_choose_app)
                menu.add(0, MENU_CREATE, 1, R.string.cesta_create_folder)
            } else {
                menu.add(0, MENU_ADD, 0, R.string.cesta_add_app)
                if (folder.apps.isNotEmpty()) menu.add(0, MENU_REMOVE, 1, R.string.cesta_remove_app)
                menu.add(0, MENU_RENAME, 2, R.string.cesta_rename_folder)
                menu.add(0, MENU_DELETE, 3, R.string.cesta_delete_folder)
            }
        }) { item ->
            when (item.itemId) {
                MENU_CHOOSE_APP -> chooseApp(slot)
                MENU_CREATE -> askName(context.getString(R.string.cesta_folder_default_name)) { name ->
                    store.update { FolderCodec.create(it, slot, name, appInSlot(slot)) }
                    refresh()
                    addToFolder(slot)
                }

                MENU_ADD -> addToFolder(slot)
                MENU_REMOVE -> folder?.let {
                    showAppsDialog(context.getString(R.string.cesta_remove_app), it.apps) { app ->
                        store.update { list -> FolderCodec.removeApp(list, slot, app) }
                        refresh()
                    }
                }

                MENU_RENAME -> folder?.let {
                    askName(it.name) { name ->
                        store.update { list -> FolderCodec.rename(list, slot, name) }
                        refresh()
                    }
                }

                MENU_DELETE -> context.createDialog(
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
    }

    /** The app already in the slot becomes the first app of the new folder. */
    private fun appInSlot(slot: Int): FolderApp? {
        val name = prefs.getAppName(slot)
        val pkg = prefs.getAppPackage(slot)
        if (name.isEmpty() || pkg.isEmpty() || prefs.getIsShortcut(slot)) return null
        return FolderApp(name, pkg, prefs.getAppActivityClassName(slot).ifEmpty { null }, prefs.getAppUser(slot))
    }

    /** Installed apps. Missing ones are only removed when Android confirms it (see isAppCheckReliable). */
    private fun visibleApps(folder: Folder): List<FolderApp> {
        val (installed, missing) = folder.apps.partition { isPackageInstalled(context, it.appPackage, it.user) }
        val gone = missing.filter { isAppCheckReliable(context, it.user) }
        if (gone.isNotEmpty()) {
            store.update { list -> FolderCodec.removeApps(list) { app -> gone.any { it.sameAppAs(app) } } }
        }
        return installed
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

    private fun showAppsDialog(title: String, apps: List<FolderApp>, onPick: (FolderApp) -> Unit) {
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
        apps.forEach { app ->
            list.addView(TextView(context).apply {
                text = app.label
                setTextAppearance(R.style.TextLarge)
                setTextColor(primaryColor())
                gravity = prefs.homeAlignment
                setPadding(0, 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
                isFocusable = true
                setBackgroundResource(selectable)
                setOnClickListener {
                    dialog.dismiss()
                    onPick(app)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        binding.contentContainer.addView(list)
        binding.contentContainer.isVisible = true
        dialog.setView(binding.root)
        dialog.showRespectingStatusBar()
    }

    private companion object {
        const val MENU_CHOOSE_APP = 1
        const val MENU_CREATE = 2
        const val MENU_ADD = 3
        const val MENU_REMOVE = 4
        const val MENU_RENAME = 5
        const val MENU_DELETE = 6
    }
}
