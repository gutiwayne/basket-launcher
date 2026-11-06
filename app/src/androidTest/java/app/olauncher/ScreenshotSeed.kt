package app.olauncher

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.AppList
import app.olauncher.data.AppListStore
import app.olauncher.data.Constants
import app.olauncher.data.ListApp
import app.olauncher.data.Prefs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cesta: prepares the launcher for README screenshots (run by .github/workflows/screenshots.yml, not by CI).
 * Picks apps that exist on the emulator image, so the result does not depend on a specific system image.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotSeed {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seed() {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val user = Process.myUserHandle()
        val installed = launcherApps.getActivityList(null, user).filter { it.applicationInfo.packageName != context.packageName }

        // First installed match for each wish, by package name fragment.
        fun pick(vararg fragments: String): LauncherActivityInfo? =
            fragments.firstNotNullOfOrNull { f -> installed.firstOrNull { it.applicationInfo.packageName.contains(f) } }

        val used = mutableSetOf<String>()
        fun take(vararg fragments: String) = pick(*fragments)?.takeIf { used.add(it.componentName.flattenToString()) }

        val daily = listOfNotNull(take("dialer"), take("messaging", "mms"), take("camera"), take("deskclock", "clock"))
        val tools = listOfNotNull(take("android.settings"), take("calculator"), take("documentsui", "files"), take("calendar"), take("chrome"))
        assertTrue("emulator has too few apps: ${installed.map { it.applicationInfo.packageName }}", daily.size >= 2 && tools.size >= 2)

        fun toListApp(info: LauncherActivityInfo) =
            ListApp(info.label.toString(), info.applicationInfo.packageName, info.componentName.className, user.toString())

        AppListStore(context).update {
            listOf(
                AppList("screenshot-daily", "Daily", daily.map(::toListApp)),
                AppList("screenshot-tools", "Tools", tools.map(::toListApp)),
            )
        }

        val prefs = Prefs(context)
        val home = (daily + tools).take(4)
        prefs.homeAppsNum = home.size
        home.forEachIndexed { i, info ->
            val slot = i + 1
            val name = info.label.toString()
            val pkg = info.applicationInfo.packageName
            val activity = info.componentName.className
            when (slot) {
                1 -> { prefs.appName1 = name; prefs.appPackage1 = pkg; prefs.appActivityClassName1 = activity; prefs.appUser1 = user.toString() }
                2 -> { prefs.appName2 = name; prefs.appPackage2 = pkg; prefs.appActivityClassName2 = activity; prefs.appUser2 = user.toString() }
                3 -> { prefs.appName3 = name; prefs.appPackage3 = pkg; prefs.appActivityClassName3 = activity; prefs.appUser3 = user.toString() }
                4 -> { prefs.appName4 = name; prefs.appPackage4 = pkg; prefs.appActivityClassName4 = activity; prefs.appUser4 = user.toString() }
            }
        }
        // Clean first run: no launcher chooser, tips, prompts or keyboard covering the drawer.
        prefs.firstOpen = false
        prefs.firstSettingsOpen = false
        prefs.hideSetDefaultLauncher = true
        prefs.autoShowKeyboard = false
        prefs.wallpaperMsgShown = true
        prefs.firstOpenTime = System.currentTimeMillis()
        prefs.userState = Constants.UserState.SHARE

        println("CESTA_SEED daily=${daily.map { it.label }} tools=${tools.map { it.label }} home=${home.map { it.label }}")
    }
}
