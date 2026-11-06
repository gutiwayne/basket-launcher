package app.olauncher.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderCodecTest {

    private val maps = FolderApp("Maps", "com.google.android.apps.maps", "com.google.MapsActivity", "UserHandle{0}")
    private val bank = FolderApp("Banco", "com.bank", null, "UserHandle{0}")
    private val workMail = FolderApp("Gmail", "com.google.android.gm", "com.google.Gm", "UserHandle{10}")

    @Test
    fun roundTripKeepsEverything() {
        var folders = FolderCodec.create(emptyList(), 3, "  Viajes ", maps, id = "a")
        folders = FolderCodec.addApp(folders, 3, bank)
        folders = FolderCodec.addApp(folders, 3, workMail)
        folders = FolderCodec.create(folders, 5, "Vacía", id = "b")

        val decoded = FolderCodec.decode(FolderCodec.encode(folders))

        assertEquals(folders, decoded)
        assertEquals("Viajes", decoded[0].name)
        assertNull(decoded[0].apps.first { it.appPackage == "com.bank" }.activityClassName)
        assertEquals(listOf("Maps", "Banco", "Gmail"), decoded[0].apps.map { it.label }) // insertion order
    }

    @Test
    fun shortcutsRoundTripAndDifferFromTheirApp() {
        val shortcut = maps.copy(label = "Home", activityClassName = null, shortcutId = "nav-home")
        var folders = FolderCodec.create(emptyList(), 1, "A", maps, id = "a")
        folders = FolderCodec.addApp(folders, 1, shortcut)
        assertEquals(2, folders[0].apps.size)

        val decoded = FolderCodec.decode(FolderCodec.encode(folders))
        assertEquals(folders, decoded)
        assertTrue(decoded[0].apps[1].isShortcut)
        assertNull(decoded[0].apps[0].shortcutId)
    }

    @Test
    fun oldJsonWithoutShortcutFieldStillLoads() {
        val json = "{\"v\":1,\"folders\":[{\"id\":\"a\",\"slot\":2,\"name\":\"X\",\"apps\":" +
            "[{\"label\":\"Maps\",\"package\":\"p\",\"activity\":null,\"user\":\"u\"}]}]}"
        val app = FolderCodec.decode(json)[0].apps[0]
        assertNull(app.shortcutId)
        assertEquals("p", app.appPackage)
    }

    @Test
    fun moveUpAndSort() {
        var folders = FolderCodec.create(emptyList(), 1, "A", maps, id = "a")
        folders = FolderCodec.addApp(folders, 1, bank)
        folders = FolderCodec.addApp(folders, 1, workMail)
        assertEquals(listOf("Maps", "Banco", "Gmail"), folders[0].apps.map { it.label })

        folders = FolderCodec.moveUp(folders, 1, workMail)
        assertEquals(listOf("Maps", "Gmail", "Banco"), folders[0].apps.map { it.label })

        folders = FolderCodec.moveUp(folders, 1, maps) // already first: no change
        assertEquals(listOf("Maps", "Gmail", "Banco"), folders[0].apps.map { it.label })

        folders = FolderCodec.sortByName(folders, 1)
        assertEquals(listOf("Banco", "Gmail", "Maps"), folders[0].apps.map { it.label })
    }

    @Test
    fun emptyOrMissingIsEmptyList() {
        assertTrue(FolderCodec.decode(null).isEmpty())
        assertTrue(FolderCodec.decode("").isEmpty())
        assertTrue(FolderCodec.decode("{\"v\":1}").isEmpty())
    }

    @Test(expected = JSONException::class)
    fun damagedJsonThrowsInsteadOfSilentlyLosingFolders() {
        FolderCodec.decode("{\"v\":1,\"folders\":[{\"id\":")
    }

    @Test
    fun addingSameAppTwiceDoesNotDuplicate() {
        var folders = FolderCodec.create(emptyList(), 1, "A", maps, id = "a")
        folders = FolderCodec.addApp(folders, 1, maps.copy(label = "Otro nombre"))
        assertEquals(1, folders[0].apps.size)
    }

    @Test
    fun sameAppInAnotherProfileIsDifferent() {
        var folders = FolderCodec.create(emptyList(), 1, "A", maps, id = "a")
        folders = FolderCodec.addApp(folders, 1, maps.copy(user = "UserHandle{10}"))
        assertEquals(2, folders[0].apps.size)
    }

    @Test
    fun createOnUsedSlotReplacesOnlyThatSlot() {
        var folders = FolderCodec.create(emptyList(), 1, "A", maps, id = "a")
        folders = FolderCodec.create(folders, 2, "B", bank, id = "b")
        folders = FolderCodec.create(folders, 1, "C", id = "c")
        assertEquals(listOf("B", "C"), folders.map { it.name })
        assertEquals("C", FolderCodec.folderAt(folders, 1)?.name)
    }

    @Test
    fun renameDeleteAndRemove() {
        var folders = FolderCodec.create(emptyList(), 4, "A", maps, id = "a")
        folders = FolderCodec.addApp(folders, 4, bank)
        folders = FolderCodec.rename(folders, 4, " Nuevo ")
        assertEquals("Nuevo", folders[0].name)

        folders = FolderCodec.removeApp(folders, 4, maps)
        assertEquals(listOf(bank), folders[0].apps)

        folders = FolderCodec.removeApps(folders) { it.appPackage == "com.bank" }
        assertTrue(folders[0].apps.isEmpty())

        assertTrue(FolderCodec.delete(folders, 4).isEmpty())
    }

    @Test
    fun operationsOnMissingSlotChangeNothing() {
        val folders = FolderCodec.create(emptyList(), 1, "A", maps, id = "a")
        assertEquals(folders, FolderCodec.addApp(folders, 7, bank))
        assertEquals(folders, FolderCodec.rename(folders, 7, "X"))
        assertEquals(folders, FolderCodec.delete(folders, 7))
        assertNull(FolderCodec.folderAt(folders, 7))
    }
}
