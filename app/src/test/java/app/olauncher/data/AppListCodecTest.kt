package app.olauncher.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListCodecTest {

    private val maps = ListApp("Maps", "com.google.android.apps.maps", "com.google.MapsActivity", "UserHandle{0}")
    private val ssh = ListApp("Termux", "com.termux", null, "UserHandle{0}")
    private val workMail = ListApp("Gmail", "com.google.android.gm", "com.google.Gm", "UserHandle{10}")
    private val shortcut = ListApp("Home", "com.google.android.apps.maps", null, "UserHandle{0}", "nav-home")

    @Test
    fun roundTripKeepsEverything() {
        var lists = AppListCodec.create(emptyList(), " Server ", ssh, id = "a")
        lists = AppListCodec.toggle(lists, "a", workMail)
        lists = AppListCodec.toggle(lists, "a", shortcut)
        lists = AppListCodec.create(lists, "Empty", id = "b")
        assertEquals(lists, AppListCodec.decode(AppListCodec.encode(lists)))
        assertEquals("Server", lists[0].name)
    }

    @Test
    fun toggleAddsThenRemoves() {
        var lists = AppListCodec.create(emptyList(), "A", id = "a")
        lists = AppListCodec.toggle(lists, "a", maps)
        assertTrue(AppListCodec.contains(lists, "a", maps))
        lists = AppListCodec.toggle(lists, "a", maps)
        assertFalse(AppListCodec.contains(lists, "a", maps))
    }

    @Test
    fun shortcutIsNotTheSameAsItsApp() {
        var lists = AppListCodec.create(emptyList(), "A", maps, id = "a")
        lists = AppListCodec.toggle(lists, "a", shortcut)
        assertEquals(2, lists[0].apps.size)
        assertTrue(maps.matches(maps.appPackage, maps.user, null))
        assertFalse(maps.matches(shortcut.appPackage, shortcut.user, shortcut.shortcutId))
    }

    @Test
    fun matchIgnoresActivityButNotProfile() {
        assertTrue(maps.matches("com.google.android.apps.maps", "UserHandle{0}", null))
        assertFalse(maps.matches("com.google.android.apps.maps", "UserHandle{10}", null))
    }

    @Test
    fun moveIsClampedAndRenameDeleteWork() {
        var lists = AppListCodec.create(emptyList(), "A", id = "a")
        lists = AppListCodec.create(lists, "B", id = "b")
        lists = AppListCodec.create(lists, "C", id = "c")
        lists = AppListCodec.move(lists, "c", -1)
        assertEquals(listOf("A", "C", "B"), lists.map { it.name })
        lists = AppListCodec.move(lists, "a", -5)
        assertEquals(listOf("A", "C", "B"), lists.map { it.name })
        lists = AppListCodec.rename(lists, "b", " Bee ")
        lists = AppListCodec.delete(lists, "a")
        assertEquals(listOf("C", "Bee"), lists.map { it.name })
    }

    @Test
    fun legacyHomeFoldersBecomeListsInSlotOrder() {
        val json = "{\"v\":1,\"folders\":[" +
            "{\"id\":\"x\",\"slot\":5,\"name\":\"Later\",\"apps\":[]}," +
            "{\"id\":\"y\",\"slot\":2,\"name\":\"Servidor\",\"apps\":[{\"label\":\"Termux\",\"package\":\"com.termux\",\"activity\":null,\"user\":\"UserHandle{0}\"}]}]}"
        val lists = AppListCodec.fromLegacyFolders(json)
        assertEquals(listOf("Servidor", "Later"), lists.map { it.name })
        assertEquals(listOf(ssh), lists[0].apps)
    }

    @Test
    fun emptyInputs() {
        assertTrue(AppListCodec.decode(null).isEmpty())
        assertTrue(AppListCodec.decode("{\"v\":2}").isEmpty())
        assertTrue(AppListCodec.fromLegacyFolders(null).isEmpty())
    }

    @Test(expected = JSONException::class)
    fun damagedJsonThrowsInsteadOfSilentlyLosingLists() {
        AppListCodec.decode("{\"v\":2,\"lists\":[{\"id\":")
    }

    @Test
    fun removingTheLastAppDeletesTheList() {
        var lists = AppListCodec.create(emptyList(), "A", maps, id = "a")
        lists = AppListCodec.create(lists, "B", ssh, id = "b")
        lists = AppListCodec.toggle(lists, "a", maps)
        assertEquals(listOf("b"), lists.map { it.id })
    }

    @Test
    fun removingAnAppThatIsNotTheLastKeepsTheList() {
        var lists = AppListCodec.create(emptyList(), "A", maps, id = "a")
        lists = AppListCodec.toggle(lists, "a", ssh)
        lists = AppListCodec.toggle(lists, "a", maps)
        assertEquals(listOf(ssh), lists.single().apps)
    }

    @Test
    fun listCreatedEmptyStaysUntilItsFirstApp() {
        var lists = AppListCodec.create(emptyList(), "Empty", id = "e")
        lists = AppListCodec.create(lists, "Other", maps, id = "o")
        lists = AppListCodec.toggle(lists, "o", ssh)
        assertEquals(listOf("e", "o"), lists.map { it.id })
        assertTrue(lists[0].apps.isEmpty())
    }

    @Test
    fun uninstallRemovesTheAppAndItsShortcutsEverywhere() {
        var lists = AppListCodec.create(emptyList(), "Only maps", maps, id = "a")
        lists = AppListCodec.toggle(lists, "a", shortcut)
        lists = AppListCodec.create(lists, "Mixed", maps, id = "b")
        lists = AppListCodec.toggle(lists, "b", ssh)
        lists = AppListCodec.create(lists, "Empty", id = "c")
        lists = AppListCodec.create(lists, "Work", workMail, id = "d")

        lists = AppListCodec.removePackage(lists, "com.google.android.apps.maps", "UserHandle{0}")
        assertEquals(listOf("b", "c", "d"), lists.map { it.id })
        assertEquals(listOf(ssh), lists[0].apps)

        // Same package in another profile is a different app.
        assertEquals(lists, AppListCodec.removePackage(lists, "com.google.android.gm", "UserHandle{0}"))
    }

    @Test
    fun removeUninstalledOnlyTouchesTheGivenUser() {
        var lists = AppListCodec.create(emptyList(), "Gone", maps, id = "a")
        lists = AppListCodec.create(lists, "Mixed", maps, id = "b")
        lists = AppListCodec.toggle(lists, "b", ssh)
        lists = AppListCodec.create(lists, "Work", workMail, id = "c")
        lists = AppListCodec.create(lists, "Empty", id = "d")

        // Nothing is installed: every app of user 0 goes, the work profile list stays.
        val result = AppListCodec.removeUninstalled(lists, "UserHandle{0}") { it == "com.termux" }
        assertEquals(listOf("b", "c", "d"), result.map { it.id })
        assertEquals(listOf(ssh), result[0].apps)
        assertEquals(lists, AppListCodec.removeUninstalled(lists, "UserHandle{0}") { true })
    }
}
