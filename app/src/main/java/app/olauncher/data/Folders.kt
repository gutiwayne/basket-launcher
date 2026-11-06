package app.olauncher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

// Cesta Launcher: carpetas en la pantalla de inicio.
// Cada carpeta ocupa uno de los huecos de inicio (1..8) y se guarda como JSON en las mismas
// SharedPreferences del launcher, que viven en el almacenamiento de la app y sobreviven a reinicios.

data class FolderApp(
    val label: String,
    val appPackage: String,
    val activityClassName: String?,
    val user: String,
) {
    fun sameAppAs(other: FolderApp) =
        appPackage == other.appPackage && user == other.user && activityClassName == other.activityClassName
}

data class Folder(
    val id: String,
    val slot: Int,
    val name: String,
    val apps: List<FolderApp> = emptyList(),
)

object FolderFlags {
    /** Flag del cajón de apps para añadir a la carpeta del hueco n: BASE + n (41..48). */
    const val ADD_TO_FOLDER_BASE = 40
    val ADD_TO_FOLDER_RANGE = (ADD_TO_FOLDER_BASE + 1)..(ADD_TO_FOLDER_BASE + 8)
}

/** Conversión a/desde JSON y operaciones puras sobre la lista (sin Android: se prueban en tests JVM). */
object FolderCodec {
    private const val VERSION = 1

    fun encode(folders: List<Folder>): String {
        val array = JSONArray()
        folders.forEach { folder ->
            val apps = JSONArray()
            folder.apps.forEach { app ->
                apps.put(JSONObject().apply {
                    put("label", app.label)
                    put("package", app.appPackage)
                    put("activity", app.activityClassName ?: JSONObject.NULL)
                    put("user", app.user)
                })
            }
            array.put(JSONObject().apply {
                put("id", folder.id)
                put("slot", folder.slot)
                put("name", folder.name)
                put("apps", apps)
            })
        }
        return JSONObject().put("v", VERSION).put("folders", array).toString()
    }

    /** Lanza [JSONException] si el contenido está dañado, para no sobrescribirlo a ciegas. */
    fun decode(json: String?): List<Folder> {
        if (json.isNullOrBlank()) return emptyList()
        val root = JSONObject(json)
        val array = root.optJSONArray("folders") ?: return emptyList()
        val result = mutableListOf<Folder>()
        for (i in 0 until array.length()) {
            val f = array.getJSONObject(i)
            val appsJson = f.optJSONArray("apps") ?: JSONArray()
            val apps = (0 until appsJson.length()).map { j ->
                val a = appsJson.getJSONObject(j)
                FolderApp(
                    label = a.optString("label"),
                    appPackage = a.getString("package"),
                    activityClassName = if (a.isNull("activity")) null else a.optString("activity"),
                    user = a.optString("user"),
                )
            }
            result += Folder(f.getString("id"), f.getInt("slot"), f.optString("name"), apps)
        }
        return result
    }

    fun folderAt(folders: List<Folder>, slot: Int) = folders.firstOrNull { it.slot == slot }

    fun create(folders: List<Folder>, slot: Int, name: String, firstApp: FolderApp? = null, id: String = UUID.randomUUID().toString()): List<Folder> =
        folders.filterNot { it.slot == slot } + Folder(id, slot, name.trim(), listOfNotNull(firstApp))

    fun rename(folders: List<Folder>, slot: Int, name: String) =
        folders.map { if (it.slot == slot) it.copy(name = name.trim()) else it }

    fun delete(folders: List<Folder>, slot: Int) = folders.filterNot { it.slot == slot }

    fun addApp(folders: List<Folder>, slot: Int, app: FolderApp) = folders.map { folder ->
        if (folder.slot != slot || folder.apps.any { it.sameAppAs(app) }) folder
        else folder.copy(apps = (folder.apps + app).sortedBy { it.label.lowercase() })
    }

    fun removeApp(folders: List<Folder>, slot: Int, app: FolderApp) = folders.map { folder ->
        if (folder.slot == slot) folder.copy(apps = folder.apps.filterNot { it.sameAppAs(app) }) else folder
    }

    fun removeApps(folders: List<Folder>, shouldRemove: (FolderApp) -> Boolean) =
        folders.map { folder -> folder.copy(apps = folder.apps.filterNot(shouldRemove)) }
}

/** Guarda las carpetas con commit() (escritura síncrona): ya están en disco antes de apagar el móvil. */
class FolderStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILENAME, 0)

    fun all(): List<Folder> = try {
        FolderCodec.decode(prefs.getString(KEY, null))
    } catch (e: JSONException) {
        // Contenido dañado: se aparta una copia para no perderlo y se empieza de cero.
        prefs.edit().putString(KEY_DAMAGED, prefs.getString(KEY, null)).remove(KEY).commit()
        emptyList()
    }

    fun folderAt(slot: Int) = FolderCodec.folderAt(all(), slot)

    fun update(change: (List<Folder>) -> List<Folder>): Boolean =
        prefs.edit().putString(KEY, FolderCodec.encode(change(all()))).commit()

    companion object {
        private const val PREFS_FILENAME = "app.olauncher"
        private const val KEY = "CESTA_FOLDERS"
        private const val KEY_DAMAGED = "CESTA_FOLDERS_DAMAGED"
    }
}
