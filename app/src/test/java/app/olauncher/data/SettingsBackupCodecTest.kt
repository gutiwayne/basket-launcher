package app.olauncher.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupCodecTest {

    private val maps = ListApp("Maps", "com.google.android.apps.maps", "com.google.MapsActivity", "UserHandle{0}")
    private val termux = ListApp("Termux", "com.termux", null, "UserHandle{0}")
    private val shortcut = ListApp("Home", "com.google.android.apps.maps", null, "UserHandle{0}", "nav-home")

    private val prefs = mapOf(
        "HOME_APPS_NUM" to 3,
        "APP_THEME" to 1,
        "TEXT_SIZE_SCALE" to 1.2f,
        "BOLD_FONT" to true,
        "APP_NAME_1" to "Maps",
        "APP_PACKAGE_1" to "com.google.android.apps.maps",
        "APP_ACTIVITY_CLASS_NAME_1" to "com.google.MapsActivity",
        "APP_USER_1" to "UserHandle{0}",
        "com.termux" to "Shell",
        // Never exported:
        "HIDDEN_APPS" to setOf("com.secret|UserHandle{0}"),
        "LOCK_MODE" to true,
        "DAILY_WALLPAPER_URL" to "https://example.com/w.jpg",
        "FIRST_OPEN" to false,
        "CESTA_LISTS" to "{}",
    )

    private fun backup(settings: String = "{}", extra: String = "") =
        """{"format":"basket-launcher-backup","v":1,"settings":$settings$extra}"""

    @Test
    fun roundTripKeepsPortableSettingsOnly() {
        val lists = listOf(AppList("a", "Server", listOf(termux, shortcut)))
        val parsed = SettingsBackupCodec.parse(SettingsBackupCodec.export(prefs, lists))

        assertEquals(3, parsed.settings["HOME_APPS_NUM"])
        assertEquals(1, parsed.settings["APP_THEME"])
        assertEquals(1.2f, parsed.settings["TEXT_SIZE_SCALE"])
        assertEquals(true, parsed.settings["BOLD_FONT"])
        assertEquals("com.google.android.apps.maps", parsed.settings["APP_PACKAGE_1"])
        assertEquals(mapOf("com.termux" to "Shell"), parsed.renames)
        assertEquals(lists, parsed.lists)
        listOf("HIDDEN_APPS", "LOCK_MODE", "DAILY_WALLPAPER_URL", "FIRST_OPEN", "CESTA_LISTS").forEach {
            assertFalse(it, parsed.settings.containsKey(it))
        }
    }

    @Test
    fun exportedFileDoesNotMentionHiddenApps() {
        val json = SettingsBackupCodec.export(prefs, emptyList())
        assertFalse(json.contains("com.secret"))
        assertFalse(json.contains("example.com"))
    }

    @Test(expected = BackupFormatException::class)
    fun rejectsNonJson() {
        SettingsBackupCodec.parse("not json")
    }

    @Test(expected = BackupFormatException::class)
    fun rejectsOtherFormats() {
        SettingsBackupCodec.parse("""{"format":"something-else","v":1}""")
    }

    @Test
    fun acceptsBackupsFromTheCestaName() {
        SettingsBackupCodec.parse("""{"format":"cesta-launcher-backup","v":1,"settings":{}}""")
    }

    @Test(expected = BackupFormatException::class)
    fun rejectsNewerVersions() {
        SettingsBackupCodec.parse("""{"format":"basket-launcher-backup","v":99}""")
    }

    @Test(expected = BackupFormatException::class)
    fun rejectsHugeFiles() {
        SettingsBackupCodec.parse(" ".repeat(SettingsBackupCodec.MAX_BYTES + 1))
    }

    @Test
    fun ignoresUnknownKeysAndInvalidValues() {
        val parsed = SettingsBackupCodec.parse(
            backup(
                """{
                "HOME_APPS_NUM": 99,
                "APP_THEME": 7,
                "TEXT_SIZE_SCALE": 40,
                "BOLD_FONT": "yes",
                "APP_PACKAGE_1": "../../data",
                "APP_USER_1": "root",
                "APP_ACTIVITY_CLASS_NAME_1": "a b",
                "APP_NAME_2": "line\nbreak",
                "LOCK_MODE": true,
                "HIDDEN_APPS": ["x"],
                "DAILY_WALLPAPER_URL": "https://evil.example",
                "STATUS_BAR": true
                }""",
                ""","renames":{"NOT_A_PACKAGE":"x","com.ok":"Fine","com.bad":"tab\there"}""",
            )
        )
        assertEquals(mapOf<String, Any>("STATUS_BAR" to true), parsed.settings)
        assertEquals(mapOf("com.ok" to "Fine"), parsed.renames)
    }

    @Test
    fun dropsInvalidAppsInLists() {
        val json = backup(
            extra = ""","lists":[
                {"id":"a","name":"Ok","apps":[
                    {"label":"Termux","package":"com.termux","activity":null,"user":"UserHandle{0}","shortcut":null},
                    {"label":"Bad","package":"not a package","activity":null,"user":"UserHandle{0}","shortcut":null},
                    {"label":"Bad user","package":"com.x","activity":null,"user":"me","shortcut":null}
                ]},
                {"id":"a","name":"Duplicate id","apps":[]},
                {"id":"","name":"No id","apps":[]}
            ]""",
        )
        val lists = SettingsBackupCodec.parse(json).lists
        assertEquals(1, lists.size)
        assertEquals(listOf(termux), lists[0].apps)
    }

    @Test(expected = BackupFormatException::class)
    fun damagedListsAreReported() {
        SettingsBackupCodec.parse(backup(extra = ""","lists":[42]"""))
    }

    @Test
    fun keepInstalledDropsWholeSlotsOfMissingApps() {
        val parsed = SettingsBackupCodec.parse(SettingsBackupCodec.export(prefs, emptyList()))
        val (kept, dropped) = SettingsBackupCodec.keepInstalled(parsed) { _, _ -> false }
        assertEquals(1, dropped)
        assertTrue(kept.settings.keys.none { it.endsWith("_1") })
        assertEquals(3, kept.settings["HOME_APPS_NUM"])

        val (all, none) = SettingsBackupCodec.keepInstalled(parsed) { pkg, user ->
            pkg == "com.google.android.apps.maps" && user == "UserHandle{0}"
        }
        assertEquals(0, none)
        assertEquals(parsed, all)
    }

    @Test
    fun mergeAddsAppsAndListsWithoutRemoving() {
        val current = listOf(AppList("a", "Work", listOf(maps)), AppList("b", "Games"))
        val incoming = listOf(
            AppList("a", "Renamed elsewhere", listOf(maps, termux)), // same id
            AppList("x", "games", listOf(shortcut)),                 // same name, other id
            AppList("y", "New", listOf(termux)),
        )
        val merged = SettingsBackupCodec.mergeLists(current, incoming)
        assertEquals(listOf("a", "b", "y"), merged.map { it.id })
        assertEquals("Work", merged[0].name)
        assertEquals(listOf(maps, termux), merged[0].apps)
        assertEquals(listOf(shortcut), merged[1].apps)
        assertEquals(listOf(termux), merged[2].apps)
    }

    @Test
    fun exportIsValidJsonWithHeader() {
        val root = JSONObject(SettingsBackupCodec.export(prefs, emptyList()))
        assertEquals("basket-launcher-backup", root.getString("format"))
        assertEquals(1, root.getInt("v"))
    }
}
