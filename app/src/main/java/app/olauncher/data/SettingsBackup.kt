package app.olauncher.data

import android.content.Context
import android.net.Uri
import android.os.UserHandle
import android.os.UserManager
import app.olauncher.helper.isPackageInstalled
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

// Cesta Launcher: export/import of the launcher configuration as a JSON file, to move it between devices.
// An imported file is untrusted input: only known keys with the expected type and range are applied,
// everything else is ignored. Hidden apps, accessibility/lock state, wallpaper URL and first-run
// bookkeeping are never exported or imported.

class BackupFormatException(message: String) : Exception(message)

data class Backup(
    val settings: Map<String, Any>,
    /** Custom app names, keyed by package name. */
    val renames: Map<String, String>,
    val lists: List<AppList>,
)

/** Pure logic (no Android dependencies, covered by JVM tests). */
object SettingsBackupCodec {
    const val FORMAT = "cesta-launcher-backup"
    const val VERSION = 1
    const val MAX_BYTES = 512 * 1024
    private const val MAX_LISTS = 100
    private const val MAX_APPS_PER_LIST = 500
    private const val MAX_RENAMES = 1000
    private const val MAX_LABEL = 256
    private const val MAX_SHORTCUT_ID = 1024

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val CLASS_NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
    private val USER = Regex("^UserHandle\\{\\d{1,6}\\}$")
    private val CONTROL = Regex("[\\p{Cntrl}\\u2028\\u2029]")

    // Gravity.START / CENTER / END and AppCompatDelegate.MODE_NIGHT_* values, inlined to stay JVM-testable.
    private val ALIGNMENTS = setOf(8388611, 17, 8388613)
    private val THEMES = setOf(-1, 1, 2)

    private sealed class Rule {
        object Bool : Rule()
        class IntIn(val allowed: (Int) -> Boolean) : Rule()
        class FloatIn(val range: ClosedFloatingPointRange<Float>) : Rule()
        class Str(val valid: (String) -> Boolean) : Rule()
    }

    private val label = Rule.Str { it.length <= MAX_LABEL && !CONTROL.containsMatchIn(it) }
    private val pkg = Rule.Str { it.isEmpty() || (it.length <= 255 && PACKAGE.matches(it)) }
    private val cls = Rule.Str { it.isEmpty() || (it.length <= 512 && CLASS_NAME.matches(it)) }
    private val user = Rule.Str { it.isEmpty() || USER.matches(it) }
    private val shortcut = Rule.Str { it.length <= MAX_SHORTCUT_ID && !CONTROL.containsMatchIn(it) }

    /** Keys pointing at an app, applied all together or not at all (see [keepInstalled]). */
    private class AppSlot(val packageKey: String, val userKey: String, val keys: List<String>)

    private val slots: List<AppSlot> = (1..8).map { n ->
        AppSlot(
            "APP_PACKAGE_$n", "APP_USER_$n",
            listOf("APP_NAME_$n", "APP_PACKAGE_$n", "APP_ACTIVITY_CLASS_NAME_$n", "APP_USER_$n", "IS_SHORTCUT_$n", "SHORTCUT_ID_$n"),
        )
    } + listOf("CLOCK", "CALENDAR", "SCREEN_TIME").map { p ->
        AppSlot("${p}_APP_PACKAGE", "${p}_APP_USER", listOf("${p}_APP_PACKAGE", "${p}_APP_USER", "${p}_APP_CLASS_NAME"))
    }

    private val rules: Map<String, Rule> = buildMap {
        listOf(
            "AUTO_SHOW_KEYBOARD", "DAILY_WALLPAPER", "HOME_BOTTOM_ALIGNMENT", "STATUS_BAR",
            "SWIPE_LEFT_ENABLED", "SWIPE_RIGHT_ENABLED", "BOLD_FONT",
        ).forEach { put(it, Rule.Bool) }
        put("HOME_APPS_NUM", Rule.IntIn { it in 0..8 })
        put("HOME_ALIGNMENT", Rule.IntIn { it in ALIGNMENTS })
        put("APP_LABEL_ALIGNMENT", Rule.IntIn { it in ALIGNMENTS })
        put("DATE_TIME_VISIBILITY", Rule.IntIn { it in 0..2 })
        put("APP_THEME", Rule.IntIn { it in THEMES })
        put("TEXT_SIZE_SCALE", Rule.FloatIn(0.5f..2.0f))
        for (n in 1..8) {
            put("APP_NAME_$n", label)
            put("APP_PACKAGE_$n", pkg)
            put("APP_ACTIVITY_CLASS_NAME_$n", cls)
            put("APP_USER_$n", user)
            put("IS_SHORTCUT_$n", Rule.Bool)
            put("SHORTCUT_ID_$n", shortcut)
        }
        for (p in listOf("CLOCK", "CALENDAR", "SCREEN_TIME")) {
            put("${p}_APP_PACKAGE", pkg)
            put("${p}_APP_USER", user)
            put("${p}_APP_CLASS_NAME", cls)
        }
    }

    /** [prefs] is SharedPreferences.getAll(); custom app names are the entries keyed by a package name. */
    fun export(prefs: Map<String, *>, lists: List<AppList>): String {
        val settings = JSONObject()
        prefs.forEach { (key, value) ->
            val v = valid(key, value) ?: return@forEach
            settings.put(key, if (v is Float) v.toDouble() else v)
        }
        val renames = JSONObject()
        prefs.forEach { (key, value) ->
            if (value is String && value.isNotEmpty() && isPackage(key) && label.valid(value)) renames.put(key, value)
        }
        val listsJson = JSONObject(AppListCodec.encode(sanitize(lists))).getJSONArray("lists")
        return JSONObject()
            .put("format", FORMAT)
            .put("v", VERSION)
            .put("settings", settings)
            .put("renames", renames)
            .put("lists", listsJson)
            .toString(2)
    }

    /** Throws [BackupFormatException] if [json] is not a Cesta backup this version can read. */
    fun parse(json: String): Backup {
        if (json.length > MAX_BYTES) throw BackupFormatException("File too large")
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupFormatException("Not a JSON file")
        }
        if (root.optString("format") != FORMAT) throw BackupFormatException("Not a Cesta Launcher backup")
        val version = root.optInt("v", -1)
        if (version < 1 || version > VERSION) throw BackupFormatException("Unsupported backup version $version")

        val settings = buildMap<String, Any> {
            root.optJSONObject("settings")?.let { s ->
                s.keys().forEach { key -> valid(key, s.opt(key))?.let { put(key, it) } }
            }
        }
        val renames = buildMap<String, String> {
            root.optJSONObject("renames")?.let { r ->
                r.keys().asSequence().take(MAX_RENAMES).forEach { key ->
                    val value = r.opt(key)
                    if (isPackage(key) && value is String && value.isNotEmpty() && label.valid(value)) put(key, value)
                }
            }
        }
        val lists = try {
            AppListCodec.decode(JSONObject().put("lists", root.optJSONArray("lists") ?: JSONArray()).toString())
        } catch (e: JSONException) {
            throw BackupFormatException("Damaged app lists")
        }
        return Backup(settings, renames, sanitize(lists))
    }

    /**
     * Drops every app slot whose app is not installed here, so it does not overwrite a working one.
     * Returns the filtered backup and the number of slots dropped.
     */
    fun keepInstalled(backup: Backup, isInstalled: (pkg: String, user: String) -> Boolean): Pair<Backup, Int> {
        val settings = backup.settings.toMutableMap()
        var dropped = 0
        slots.forEach { slot ->
            val p = settings[slot.packageKey] as? String ?: return@forEach
            if (p.isNotEmpty() && !isInstalled(p, settings[slot.userKey] as? String ?: "")) {
                slot.keys.forEach { settings.remove(it) }
                dropped++
            }
        }
        return backup.copy(settings = settings) to dropped
    }

    /** Merges by id, then by name (ignoring case); apps are added, nothing is removed. */
    fun mergeLists(current: List<AppList>, incoming: List<AppList>): List<AppList> {
        val result = current.toMutableList()
        incoming.forEach { list ->
            val i = result.indexOfFirst { it.id == list.id }.takeIf { it >= 0 }
                ?: result.indexOfFirst { it.name.equals(list.name, ignoreCase = true) }.takeIf { it >= 0 }
            if (i == null) {
                if (result.size < MAX_LISTS) result += list
            } else {
                val target = result[i]
                val added = list.apps.filter { app -> target.apps.none { it.sameAppAs(app) } }
                result[i] = target.copy(apps = (target.apps + added).take(MAX_APPS_PER_LIST))
            }
        }
        return result
    }

    private fun isPackage(key: String) = key.length <= 255 && PACKAGE.matches(key) && key.any { it.isLowerCase() }

    private fun valid(key: String, value: Any?): Any? = when (val rule = rules[key]) {
        null -> null
        Rule.Bool -> value as? Boolean
        is Rule.IntIn -> (value as? Int)?.takeIf(rule.allowed)
        is Rule.FloatIn -> (value as? Number)?.toFloat()?.takeIf { it in rule.range }
        is Rule.Str -> (value as? String)?.takeIf(rule.valid)
    }

    private fun sanitize(lists: List<AppList>): List<AppList> = lists
        .filter { it.id.isNotEmpty() && it.id.length <= 64 && !CONTROL.containsMatchIn(it.id) }
        .distinctBy { it.id }
        .take(MAX_LISTS)
        .map { list ->
            list.copy(
                name = list.name.replace(CONTROL, "").take(MAX_LABEL),
                apps = list.apps.filter(::validApp).take(MAX_APPS_PER_LIST),
            )
        }

    private fun validApp(app: ListApp) =
        app.appPackage.isNotEmpty() && pkg.valid(app.appPackage) && cls.valid(app.activityClassName ?: "") &&
            user.valid(app.user) && label.valid(app.label) && (app.shortcutId == null || shortcut.valid(app.shortcutId))
}

data class ImportResult(val settings: Int, val renames: Int, val lists: Int, val skippedSlots: Int)

/** Reads and writes backup files picked by the user through the system file picker (no storage permission). */
class SettingsBackupStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILENAME, 0)

    @Throws(IOException::class)
    fun exportTo(uri: Uri) {
        val json = SettingsBackupCodec.export(prefs.all, AppListStore(context).all())
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open file")
        out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    @Throws(IOException::class, BackupFormatException::class)
    fun importFrom(uri: Uri): ImportResult {
        val (backup, skipped) = SettingsBackupCodec.keepInstalled(SettingsBackupCodec.parse(read(uri))) { pkg, user ->
            context.isPackageInstalled(pkg, userFor(user))
        }
        val editor = prefs.edit()
        backup.settings.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        backup.renames.forEach { (pkg, name) -> editor.putString(pkg, name) }
        if (!editor.commit()) throw IOException("Cannot save settings")
        if (!AppListStore(context).update { SettingsBackupCodec.mergeLists(it, backup.lists) })
            throw IOException("Cannot save app lists")
        return ImportResult(backup.settings.size, backup.renames.size, backup.lists.size, skipped)
    }

    /** Reads at most [SettingsBackupCodec.MAX_BYTES] + 1 bytes, so a huge file cannot exhaust memory. */
    private fun read(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        val buffer = ByteArrayOutputStream()
        input.use {
            val chunk = ByteArray(8192)
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > SettingsBackupCodec.MAX_BYTES) throw BackupFormatException("File too large")
            }
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun userFor(user: String): UserHandle {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return userManager.userProfiles.firstOrNull { it.toString() == user } ?: android.os.Process.myUserHandle()
    }

    companion object {
        private const val PREFS_FILENAME = "app.olauncher"
    }
}
