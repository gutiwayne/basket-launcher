package app.olauncher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.Folder
import app.olauncher.data.FolderApp
import app.olauncher.data.FolderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cesta: checks that folders survive a system reboot.
 * CI runs writeFolders, reboots the emulator (adb reboot), then runs verifyFoldersAfterReboot.
 * See .github/scripts/reboot-test.sh.
 */
@RunWith(AndroidJUnit4::class)
class FolderPersistenceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val expected = listOf(
        Folder(
            id = "reboot-test", slot = 2, name = "Viajes",
            apps = listOf(
                FolderApp("Ajustes", "com.android.settings", null, android.os.Process.myUserHandle().toString()),
                FolderApp("Contactos", "com.android.contacts", null, android.os.Process.myUserHandle().toString()),
            ),
        ),
    )

    @Test
    fun writeFolders() {
        assertTrue(FolderStore(context).update { expected })
        assertEquals(expected, FolderStore(context).all())
    }

    @Test
    fun verifyFoldersAfterReboot() {
        assertEquals(expected, FolderStore(context).all())
    }
}
