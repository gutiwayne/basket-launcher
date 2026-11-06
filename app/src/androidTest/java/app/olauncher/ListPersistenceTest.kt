package app.olauncher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.AppList
import app.olauncher.data.AppListStore
import app.olauncher.data.ListApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cesta: checks that app lists survive a system reboot, including the migration of old home folders.
 * CI runs writeLists, reboots the emulator (adb reboot), then runs verifyListsAfterReboot.
 * See .github/scripts/reboot-test.sh.
 */
@RunWith(AndroidJUnit4::class)
class ListPersistenceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val user = android.os.Process.myUserHandle().toString()

    private val expected = listOf(
        AppList("reboot-test", "Servidor", listOf(ListApp("Settings", "com.android.settings", null, user))),
        AppList("legacy", "Old folder", listOf(ListApp("Contacts", "com.android.contacts", null, user))),
    )

    @Test
    fun writeLists() {
        val prefs = context.getSharedPreferences("app.olauncher", 0)
        // An old home folder written by an earlier Cesta build, to be migrated on first read.
        prefs.edit().remove("CESTA_LISTS").putString(
            "CESTA_FOLDERS",
            "{\"v\":1,\"folders\":[{\"id\":\"legacy\",\"slot\":3,\"name\":\"Old folder\",\"apps\":" +
                "[{\"label\":\"Contacts\",\"package\":\"com.android.contacts\",\"activity\":null,\"user\":\"$user\"}]}]}",
        ).commit()
        val store = AppListStore(context)
        store.update { listOf(expected[0]) + it }
        assertEquals(expected, store.all())
    }

    @Test
    fun verifyListsAfterReboot() {
        assertEquals(expected, AppListStore(context).all())
    }
}
