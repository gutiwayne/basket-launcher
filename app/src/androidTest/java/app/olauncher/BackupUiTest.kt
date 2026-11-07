package app.olauncher

import android.content.Intent
import android.content.pm.LauncherApps
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import app.olauncher.data.AppList
import app.olauncher.data.AppListStore
import app.olauncher.data.Constants
import app.olauncher.data.ListApp
import app.olauncher.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Cesta: Settings → Backup driven through the real UI and the system file picker, as a user would.
 * Screenshots of every step (and of the failure) go to the app's external files dir, ui-test/,
 * which CI pulls and uploads as an artifact.
 */
@RunWith(AndroidJUnit4::class)
class BackupUiTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val pkg get() = context.packageName
    private val shots by lazy { File(context.getExternalFilesDir(null), "ui-test").apply { deleteRecursively(); mkdirs() } }
    private var shotIndex = 0

    private fun shot(name: String) {
        device.waitForIdle()
        val n = "%02d-%s".format(++shotIndex, name)
        device.takeScreenshot(File(shots, "$n.png"))
        device.dumpWindowHierarchy(File(shots, "$n.xml"))
    }

    private fun waitFor(selector: BySelector, what: String, timeout: Long = 10_000): UiObject2 =
        device.wait(Until.findObject(selector), timeout) ?: run {
            shot("missing-${what.replace(Regex("[^A-Za-z0-9]+"), "_")}")
            throw AssertionError("Not on screen: $what")
        }

    private fun scrollToText(text: String): UiObject2 {
        UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(text)
        return waitFor(By.text(text), text)
    }

    private val picker = By.pkg(Pattern.compile(".*documentsui.*"))

    /** Shows Downloads in the picker if the current root cannot hold the file (e.g. Recent). */
    private fun goToDownloads() {
        device.findObject(By.desc(Pattern.compile("(?i)show roots|navigate up|open navigation drawer")))?.click()
            ?: return
        device.wait(Until.findObject(By.text("Downloads")), 5_000)?.click()
        device.waitForIdle()
    }

    @Test
    fun exportThenImportThroughSystemPicker() {
        val user = android.os.Process.myUserHandle()
        val settingsApp = context.getSystemService(LauncherApps::class.java).getActivityList("com.android.settings", user).first()
        val expected = listOf(
            AppList("ui-a", "Tools", listOf(ListApp(settingsApp.label.toString(), "com.android.settings", settingsApp.componentName.className, user.toString()))),
            AppList("ui-b", "Created empty"),
        )
        Prefs(context).apply {
            firstOpen = false
            firstSettingsOpen = false
            hideSetDefaultLauncher = true
            wallpaperMsgShown = true
            firstOpenTime = System.currentTimeMillis()
            launcherRestartTimestamp = System.currentTimeMillis()
            userState = Constants.UserState.SHARE
            homeAppsNum = 0
        }
        val store = AppListStore(context)
        store.update { expected }

        try {
            context.startActivity(
                context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 10_000)
            shot("home")
            val x = device.displayWidth / 2
            val y = device.displayHeight / 2
            device.swipe(x, y, x, y, 300) // long press on the empty home screen opens Settings
            Thread.sleep(1_000)
            shot("after-long-press")

            // --- Export ---
            val export = scrollToText("Export settings")
            shot("export-visible")
            export.click()
            Thread.sleep(1_500)
            shot("after-export-click")
            waitFor(picker, "file picker (export)")
            shot("export-picker")
            var save = waitFor(By.text(Pattern.compile("(?i)save")).clickable(true), "Save button")
            if (!save.isEnabled) {
                goToDownloads()
                save = waitFor(By.text(Pattern.compile("(?i)save")).clickable(true), "Save button in Downloads")
            }
            save.click()
            waitFor(By.text("Export settings"), "Settings after export (the bug: launcher went home)")
            shot("after-export")

            // Start from nothing, so the import must bring the lists back.
            context.getSharedPreferences("app.olauncher", 0).edit().remove("CESTA_LISTS").commit()
            assertEquals(emptyList<AppList>(), store.all())

            // --- Import ---
            scrollToText("Import settings").click()
            waitFor(picker, "file picker (import)")
            shot("import-picker")
            val file = device.wait(Until.findObject(By.textStartsWith("cesta-launcher-")), 5_000)
                ?: run { goToDownloads(); waitFor(By.textStartsWith("cesta-launcher-"), "exported file in picker") }
            file.click()
            waitFor(By.text("Import settings?"), "confirmation dialog")
            shot("import-confirm")
            waitFor(By.text("Import"), "Import button").click()
            assertNotNull("no Importing… dialog", device.wait(Until.findObject(By.text("Importing…")), 5_000))
            shot("importing")

            val deadline = System.currentTimeMillis() + 10_000
            while (store.all() != expected && System.currentTimeMillis() < deadline) Thread.sleep(200)
            shot("after-import")
            assertEquals(expected, store.all())
        } catch (e: Throwable) {
            shot("failure")
            throw e
        }
    }
}
