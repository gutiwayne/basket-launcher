package app.olauncher.listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import app.olauncher.data.AppListStore

/**
 * Cesta Launcher: an app was uninstalled (not updated) from this user, so it leaves every app list
 * and lists left without apps are deleted. Work profile apps are handled by the LauncherApps callback
 * in MainActivity, since this broadcast only reports the launcher's own user.
 */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val appPackage = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        AppListStore(context).removePackage(appPackage, Process.myUserHandle().toString())
    }
}
