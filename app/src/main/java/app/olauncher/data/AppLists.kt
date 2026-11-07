package app.olauncher.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

// Basket Launcher: app lists. The drawer shows "All apps" plus one page per list; swiping left/right
// moves between them. Stored as JSON in the launcher's own SharedPreferences (private app storage,
// survives reboots).

data class ListApp(
    val label: String,
    val appPackage: String,
    val activityClassName: String?,
    val user: String,
    /** Set for pinned shortcuts (API 25+); null for regular apps. */
    val shortcutId: String? = null,
) {
    fun sameAppAs(other: ListApp) =
        appPackage == other.appPackage && user == other.user &&
            activityClassName == other.activityClassName && shortcutId == other.shortcutId

    /** Matches a drawer entry. The activity is ignored for apps so a renamed main activity still matches. */
    fun matches(appPackage: String, user: String, shortcutId: String?) =
        this.appPackage == appPackage && this.user == user && this.shortcutId == shortcutId
}

data class AppList(
    val id: String,
    val name: String,
    val apps: List<ListApp> = emptyList(),
)

/** JSON conversion and pure list operations (no Android dependencies, covered by JVM tests). */
object AppListCodec {
    private const val VERSION = 2

    fun encode(lists: List<AppList>): String {
        val array = JSONArray()
        lists.forEach { list ->
            array.put(JSONObject().apply {
                put("id", list.id)
                put("name", list.name)
                put("apps", encodeApps(list.apps))
            })
        }
        return JSONObject().put("v", VERSION).put("lists", array).toString()
    }

    /** Throws [JSONException] on damaged content so it is never blindly overwritten. */
    fun decode(json: String?): List<AppList> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONObject(json).optJSONArray("lists") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val l = array.getJSONObject(i)
            AppList(l.getString("id"), l.optString("name"), decodeApps(l.optJSONArray("apps")))
        }
    }

    /** Home screen folders from earlier Basket builds ({"v":1,"folders":[…]}) become lists, ordered by slot. */
    fun fromLegacyFolders(json: String?): List<AppList> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONObject(json).optJSONArray("folders") ?: return emptyList()
        return (0 until array.length())
            .map { array.getJSONObject(it) }
            .sortedBy { it.optInt("slot") }
            .map { f -> AppList(f.getString("id"), f.optString("name"), decodeApps(f.optJSONArray("apps"))) }
    }

    private fun encodeApps(apps: List<ListApp>) = JSONArray().apply {
        apps.forEach { app ->
            put(JSONObject().apply {
                put("label", app.label)
                put("package", app.appPackage)
                put("activity", app.activityClassName ?: JSONObject.NULL)
                put("user", app.user)
                put("shortcut", app.shortcutId ?: JSONObject.NULL)
            })
        }
    }

    private fun decodeApps(array: JSONArray?): List<ListApp> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { j ->
            val a = array.getJSONObject(j)
            ListApp(
                label = a.optString("label"),
                appPackage = a.getString("package"),
                activityClassName = if (a.isNull("activity")) null else a.optString("activity"),
                user = a.optString("user"),
                shortcutId = if (a.isNull("shortcut")) null else a.optString("shortcut"),
            )
        }
    }

    fun create(lists: List<AppList>, name: String, firstApp: ListApp? = null, id: String = UUID.randomUUID().toString()) =
        lists + AppList(id, name.trim(), listOfNotNull(firstApp))

    fun rename(lists: List<AppList>, id: String, name: String) =
        lists.map { if (it.id == id) it.copy(name = name.trim()) else it }

    fun delete(lists: List<AppList>, id: String) = lists.filterNot { it.id == id }

    /** Moves the list [delta] positions (negative = left), clamped to the ends. */
    fun move(lists: List<AppList>, id: String, delta: Int): List<AppList> {
        val from = lists.indexOfFirst { it.id == id }
        if (from < 0) return lists
        val to = (from + delta).coerceIn(0, lists.lastIndex)
        return lists.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun contains(lists: List<AppList>, id: String, app: ListApp) =
        lists.firstOrNull { it.id == id }?.apps?.any { it.sameAppAs(app) } == true

    /**
     * Adds [app] to the list, or removes it if it is already there.
     * Removing the last app deletes the list; a list created empty stays until it gets its first app.
     */
    fun toggle(lists: List<AppList>, id: String, app: ListApp) = lists.mapNotNull { list ->
        when {
            list.id != id -> list
            list.apps.any { it.sameAppAs(app) } ->
                list.copy(apps = list.apps.filterNot { it.sameAppAs(app) }).takeIf { it.apps.isNotEmpty() }
            else -> list.copy(apps = list.apps + app)
        }
    }

    /** An uninstalled app (and its shortcuts) leaves every list; lists left without apps are deleted. */
    fun removePackage(lists: List<AppList>, appPackage: String, user: String) = lists.mapNotNull { list ->
        val kept = list.apps.filterNot { it.appPackage == appPackage && it.user == user }
        when {
            kept.size == list.apps.size -> list
            kept.isEmpty() -> null
            else -> list.copy(apps = kept)
        }
    }

    /** Removes every app of [user] whose package [isInstalled] rejects, with the same list rules as [removePackage]. */
    fun removeUninstalled(lists: List<AppList>, user: String, isInstalled: (String) -> Boolean): List<AppList> {
        val gone = lists.flatMap { it.apps }.filter { it.user == user }.map { it.appPackage }.toSet().filterNot(isInstalled)
        return gone.fold(lists) { acc, pkg -> removePackage(acc, pkg, user) }
    }
}

/** Saves lists with commit() (synchronous write), so they are on disk before the phone shuts down. */
class AppListStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILENAME, 0)

    fun all(): List<AppList> {
        migrateLegacyFolders()
        return try {
            AppListCodec.decode(prefs.getString(KEY, null))
        } catch (e: JSONException) {
            // Damaged content: keep a copy aside instead of losing it, then start fresh.
            prefs.edit().putString(KEY_DAMAGED, prefs.getString(KEY, null)).remove(KEY).commit()
            emptyList()
        }
    }

    fun update(change: (List<AppList>) -> List<AppList>): Boolean =
        prefs.edit().putString(KEY, AppListCodec.encode(change(all()))).commit()

    fun removePackage(appPackage: String, user: String) = save(all()) { AppListCodec.removePackage(it, appPackage, user) }

    /**
     * Safety net for uninstalls the launcher missed (it was force-stopped, so no broadcast arrived).
     * Only the launcher's own user: work profile apps disappear while the profile is paused.
     * Disabled apps still count as installed.
     */
    fun removeUninstalled(context: Context) {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        save(all()) { lists ->
            AppListCodec.removeUninstalled(lists, Process.myUserHandle().toString()) { pkg ->
                try {
                    pm.getApplicationInfo(pkg, flags)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            }
        }
    }

    private fun save(before: List<AppList>, change: (List<AppList>) -> List<AppList>) {
        val after = change(before)
        if (after != before) prefs.edit().putString(KEY, AppListCodec.encode(after)).commit()
    }

    private fun migrateLegacyFolders() {
        val legacy = prefs.getString(KEY_LEGACY_FOLDERS, null) ?: return
        val lists = try {
            AppListCodec.fromLegacyFolders(legacy)
        } catch (e: JSONException) {
            emptyList()
        }
        val current = try {
            AppListCodec.decode(prefs.getString(KEY, null))
        } catch (e: JSONException) {
            emptyList()
        }
        prefs.edit()
            .putString(KEY, AppListCodec.encode(current + lists.filter { l -> current.none { it.id == l.id } }))
            .putString(KEY_LEGACY_BACKUP, legacy)
            .remove(KEY_LEGACY_FOLDERS)
            .commit()
    }

    companion object {
        private const val PREFS_FILENAME = "app.olauncher"
        private const val KEY = "CESTA_LISTS"
        private const val KEY_DAMAGED = "CESTA_LISTS_DAMAGED"
        private const val KEY_LEGACY_FOLDERS = "CESTA_FOLDERS"
        private const val KEY_LEGACY_BACKUP = "CESTA_FOLDERS_MIGRATED"
    }
}
