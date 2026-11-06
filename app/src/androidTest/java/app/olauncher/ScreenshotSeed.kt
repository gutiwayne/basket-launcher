package app.olauncher

import android.app.WallpaperManager
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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

    /** Deep blue vertical gradient generated on the device: no third-party image, no personal photo. */
    private fun setGradientWallpaper() {
        val metrics = context.resources.displayMetrics
        val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPaint(Paint().apply {
            shader = LinearGradient(
                0f, 0f, metrics.widthPixels * 0.3f, metrics.heightPixels.toFloat(),
                intArrayOf(0xFF0B2A8C.toInt(), 0xFF06186A.toInt(), 0xFF020A2E.toInt()), null, Shader.TileMode.CLAMP,
            )
        })
        WallpaperManager.getInstance(context).setBitmap(bitmap)
    }

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
        // Minimal home like a typical setup: only clock and date, apps live in the drawer and lists.
        prefs.homeAppsNum = 0
        setGradientWallpaper()

        // Clean first run: no launcher chooser, tips or prompts. The keyboard stays on, as in daily use.
        prefs.firstOpen = false
        prefs.firstSettingsOpen = false
        prefs.hideSetDefaultLauncher = true
        prefs.wallpaperMsgShown = true
        prefs.firstOpenTime = System.currentTimeMillis()
        prefs.userState = Constants.UserState.SHARE

        println("CESTA_SEED daily=${daily.map { it.label }} tools=${tools.map { it.label }}")
    }
}
