package app.olauncher.helper

import android.content.Context
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.FolderApp
import app.olauncher.data.FolderCodec
import app.olauncher.data.FolderStore

/** Cesta: add the app picked in the drawer to the folder in [slot]. Kept out of MainViewModel to ease upstream merges. */
fun addAppToFolder(context: Context, appModel: AppModel, slot: Int) {
    when (appModel) {
        is AppModel.App -> {
            val store = FolderStore(context)
            val app = FolderApp(appModel.appLabel, appModel.appPackage, appModel.activityClassName, appModel.user.toString())
            store.update { FolderCodec.addApp(it, slot, app) }
            store.folderAt(slot)?.let { context.showToast(context.getString(R.string.cesta_added_to_folder, it.name)) }
        }

        is AppModel.PinnedShortcut -> context.showToast(context.getString(R.string.cesta_shortcuts_not_supported))
        else -> {}
    }
}
