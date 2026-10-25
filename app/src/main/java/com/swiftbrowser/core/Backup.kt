package com.swiftbrowser.core

import android.content.Context
import android.net.Uri
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.tabs.TabManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore (Settings → Privacy & storage): one JSON file in the download folder with the
 * settings, profiles, bookmarks, history, open tabs and groups, and per-site settings. Restoring adds
 * to what is there (nothing is deleted) and then restarts Swift. Passwords and cookies aren't in it:
 * sign in again after restoring on a new phone.
 */
object Backup {
    private const val HISTORY_LIMIT = 5000

    /** Settings that describe this phone, not the user's choices. */
    private val SKIP_SETTINGS = setOf(
        "prefs_version", Prefs.LAST_UPDATE_CHECK, "ubo_last_update_check", Prefs.CURRENT_PROFILE,
        Prefs.LAST_AUTO_BACKUP, Prefs.LAST_LOW_SPACE_CLEANUP, Prefs.SEEN_VERSION, Prefs.WELCOME_DONE,
    )

    private const val AUTO_PREFIX = "Swift backup (automatic)"
    private const val WEEK = 7L * 24 * 3600 * 1000

    class Summary(val profiles: Int, val bookmarks: Int, val tabs: Int, val history: Int, val created: String)

    /** Blocking: writes the backup and returns its file. */
    fun write(ctx: Context): File = write(ctx, automatic = false)

    /**
     * Blocking: the weekly backup, if it is due (Settings → Privacy → Back up every week). Only the
     * two newest automatic backups are kept. Returns the file written, or null.
     */
    fun autoBackupIfDue(ctx: Context): File? {
        val prefs = SwiftApp.prefs
        if (!prefs.autoBackup || System.currentTimeMillis() - prefs.lastAutoBackup < WEEK) return null
        if (TabManager.tabs.none { !it.incognito && !it.isBlank }) return null
        val file = try {
            write(ctx, automatic = true)
        } catch (e: Exception) {
            L.w("Automatic backup failed", e)
            return null
        }
        prefs.lastAutoBackup = System.currentTimeMillis()
        file.parentFile?.listFiles { f -> f.name.startsWith(AUTO_PREFIX) && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        L.d("Automatic backup written: ${file.name}")
        return file
    }

    private fun write(ctx: Context, automatic: Boolean): File {
        val db = SwiftApp.db
        val root = JSONObject()
            .put("app", "Swift")
            .put("version", BuildConfig.VERSION_NAME)
            .put("created", System.currentTimeMillis())

        val settings = JSONObject()
        for ((key, value) in SwiftApp.prefs.sp.all) {
            if (key in SKIP_SETTINGS || value == null) continue
            val type = when (value) {
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is Set<*> -> "set"
                else -> "s"
            }
            val v: Any = if (value is Set<*>) JSONArray(value.map { it.toString() }) else value
            settings.put(key, JSONObject().put("t", type).put("v", v))
        }
        root.put("settings", settings)

        val profiles = JSONArray()
        for (p in Profiles.all()) {
            val bookmarks = JSONArray()
            for (b in db.bookmarks(p.id)) bookmarks.put(JSONObject().put("url", b.url).put("title", b.title).put("created", b.created))
            val history = JSONArray()
            for (h in db.history(p.id, null, HISTORY_LIMIT)) {
                history.put(JSONObject().put("url", h.url).put("title", h.title).put("last", h.lastVisit).put("visits", h.visits))
            }
            val groups = JSONArray()
            for (g in db.tabGroups().filter { it.profileId == p.id }) groups.put(JSONObject().put("id", g.id).put("name", g.name).put("color", g.color))
            val tabs = JSONArray()
            for (t in db.tabs().filter { it.profileId == p.id && !UrlUtils.isBlank(it.url) }) {
                tabs.put(JSONObject().put("url", t.url).put("title", t.title).put("group", t.groupId ?: -1).put("used", t.lastUsed))
            }
            profiles.put(
                JSONObject().put("name", p.name).put("color", p.color)
                    .put("bookmarks", bookmarks).put("history", history).put("groups", groups).put("tabs", tabs),
            )
        }
        root.put("profiles", profiles)

        val levels = JSONObject()
        for ((site, level) in AdBlock.overrides()) levels.put(site, level.key)
        root.put("siteLevels", levels)

        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val dir = Downloads.directory()
        val base = if (automatic) "$AUTO_PREFIX $day" else "Swift backup $day"
        var file = File(dir, "$base.json")
        var n = 2
        while (file.exists()) file = File(dir, "$base ($n).json").also { n++ }
        file.writeText(root.toString())
        // So file managers and other apps see it straight away.
        android.media.MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(file.path), null, null)
        return file
    }

    /** Blocking: backups in the download folders, newest first. */
    fun found(): List<File> {
        val dirs = listOf(
            Downloads.directory(),
            Downloads.defaultDirectory(),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        ).distinct()
        return dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.startsWith("Swift backup") && f.name.endsWith(".json") }?.toList().orEmpty()
        }.distinctBy { it.canonicalPath }.sortedByDescending { it.lastModified() }
    }

    /** Blocking: reads a backup, or null if [uri] isn't one. */
    fun read(ctx: Context, uri: Uri): JSONObject? = try {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        text?.let { JSONObject(it) }?.takeIf { it.optString("app") == "Swift" }
    } catch (_: Exception) {
        null
    }

    fun summary(root: JSONObject): Summary {
        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        var bookmarks = 0
        var tabs = 0
        var history = 0
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            bookmarks += p.optJSONArray("bookmarks")?.length() ?: 0
            tabs += p.optJSONArray("tabs")?.length() ?: 0
            history += p.optJSONArray("history")?.length() ?: 0
        }
        val created = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(root.optLong("created")))
        return Summary(profiles.length(), bookmarks, tabs, history, created)
    }

    /**
     * Blocking (run on TabManager.io, so it comes after any pending saves): adds the backup's
     * contents. Swift must restart afterwards to load the tabs and settings.
     */
    fun restore(root: JSONObject) {
        val db = SwiftApp.db
        val sp = SwiftApp.prefs.sp
        root.optJSONObject("settings")?.let { settings ->
            val edit = sp.edit()
            for (key in settings.keys()) {
                if (key in SKIP_SETTINGS) continue
                val o = settings.optJSONObject(key) ?: continue
                when (o.optString("t")) {
                    "b" -> edit.putBoolean(key, o.optBoolean("v"))
                    "i" -> edit.putInt(key, o.optInt("v"))
                    "l" -> edit.putLong(key, o.optLong("v"))
                    "f" -> edit.putFloat(key, o.optDouble("v").toFloat())
                    "set" -> {
                        val arr = o.optJSONArray("v") ?: JSONArray()
                        // Lists like per-site settings are merged with what this phone has.
                        val mine = sp.getStringSet(key, emptySet()) ?: emptySet()
                        edit.putStringSet(key, mine + (0 until arr.length()).map { arr.getString(it) })
                    }
                    else -> edit.putString(key, o.optString("v"))
                }
            }
            edit.commit()
        }

        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        val open = db.tabs().map { it.profileId to it.url }.toSet()
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            val name = p.optString("name").ifBlank { "Restored" }
            val pid = Profiles.all().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: Profiles.add(name).id
            p.optJSONArray("bookmarks")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val b = arr.getJSONObject(j)
                    db.importBookmark(pid, b.optString("url"), b.optString("title"), b.optLong("created", System.currentTimeMillis()))
                }
            }
            p.optJSONArray("history")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val h = arr.getJSONObject(j)
                    db.importHistory(pid, h.optString("url"), h.optString("title"), h.optLong("last"), h.optInt("visits", 1))
                }
            }
            val groups = HashMap<Long, JSONObject>()
            p.optJSONArray("groups")?.let { arr ->
                for (j in 0 until arr.length()) arr.getJSONObject(j).let { groups[it.optLong("id")] = it }
            }
            // A group is made only for tabs actually added, joining one of the same name if there is one.
            val groupIds = HashMap<Long, Long>()
            fun groupFor(id: Long): Long? {
                val g = groups[id] ?: return null
                return groupIds.getOrPut(id) {
                    db.tabGroups().firstOrNull { it.profileId == pid && it.name == g.optString("name") }?.id
                        ?: db.insertTabGroup(pid, g.optString("name"), g.optInt("color"))
                }
            }
            p.optJSONArray("tabs")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val t = arr.getJSONObject(j)
                    val url = t.optString("url")
                    if (url.isBlank() || (pid to url) in open) continue
                    db.importTab(pid, url, t.optString("title"), groupFor(t.optLong("group", -1)), t.optLong("used", System.currentTimeMillis()))
                }
            }
        }
        root.optJSONObject("siteLevels")?.let { levels ->
            for (site in levels.keys()) db.setSiteLevel(site, AdLevel.fromKey(levels.optString(site)).key)
        }
    }

    /** Runs [restore] after the app's pending database writes. */
    fun restoreThen(root: JSONObject, done: () -> Unit) {
        TabManager.flushSaves()
        TabManager.io.execute {
            restore(root)
            android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }
    }
}
