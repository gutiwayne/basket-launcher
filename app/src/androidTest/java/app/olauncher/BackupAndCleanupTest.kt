package app.olauncher

import android.content.pm.LauncherApps
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.AppList
import app.olauncher.data.AppListStore
import app.olauncher.data.ListApp
import app.olauncher.data.SettingsBackupStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Cesta: on a real Android runtime (its own regex and org.json), with real installed apps.
 * - exportImportKeepsListApps: export → clear lists → import gives the same lists back.
 * - writeListWithTestPackage / verifyUninstalledListDeleted: CI uninstalls this test APK in between
 *   (see .github/scripts/reboot-test.sh); the list holding only that package must be gone.
 */
@RunWith(AndroidJUnit4::class)
class BackupAndCleanupTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val user = android.os.Process.myUserHandle()
    private val prefs get() = context.getSharedPreferences("app.olauncher", 0)

    /** Real drawer entries for an installed app, built the same way the drawer builds them. */
    private fun installedApp(appPackage: String): ListApp {
        val activity = context.getSystemService(LauncherApps::class.java).getActivityList(appPackage, user).first()
        return ListApp(activity.label.toString(), appPackage, activity.componentName.className, user.toString())
    }

    @Test
    fun exportImportKeepsListApps() {
        prefs.edit().remove("CESTA_LISTS").commit()
        val store = AppListStore(context)
        val lists = listOf(
            AppList("backup-a", "Tools", listOf(installedApp("com.android.settings"))),
            AppList("backup-b", "Created empty"),
        )
        store.update { lists }

        val file = File(context.cacheDir, "backup-test.json")
        SettingsBackupStore(context).exportTo(Uri.fromFile(file))
        assertTrue(file.readText().contains("com.android.settings"))

        prefs.edit().remove("CESTA_LISTS").commit()
        assertEquals(emptyList<AppList>(), store.all())

        val result = SettingsBackupStore(context).importFrom(Uri.fromFile(file))
        assertEquals(2, result.lists)
        assertEquals(lists, store.all())
        file.delete()
    }

    @Test
    fun writeListWithTestPackage() {
        prefs.edit().remove("CESTA_LISTS").commit()
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        AppListStore(context).update {
            listOf(
                AppList("uninstall-only", "Only test app", listOf(ListApp("Test", testPackage, null, user.toString()))),
                AppList("uninstall-mixed", "Mixed", listOf(
                    ListApp("Test", testPackage, null, user.toString()),
                    installedApp("com.android.settings"),
                )),
                AppList("uninstall-empty", "Created empty"),
            )
        }
    }

    @Test
    fun verifyUninstalledListDeleted() {
        val store = AppListStore(context)
        val deadline = System.currentTimeMillis() + 10_000
        while (store.all().any { it.id == "uninstall-only" } && System.currentTimeMillis() < deadline) Thread.sleep(250)
        val lists = store.all()
        assertEquals(listOf("uninstall-mixed", "uninstall-empty"), lists.map { it.id })
        assertEquals(listOf("com.android.settings"), lists[0].apps.map { it.appPackage })
    }
}
