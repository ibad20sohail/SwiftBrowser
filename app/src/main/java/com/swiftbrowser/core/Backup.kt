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
 * to what is there (nothing is deleted), the backup's settings replace these, and then Swift restarts.
 * Passwords and cookies aren't in it: sign in again after restoring on a new phone.
 *
 * 3.0: only the user's own settings go in (BackupSettings), never secrets or what only the phone
 * knows. A backup is read as something that may be broken or made by someone else: every item is
 * checked, the data goes in in one go (all of it or none), and the settings come last.
 */
object Backup {
    private const val HISTORY_LIMIT = 5000

    /** A backup is never bigger than this (5,000 pages of history make about 1 MB). */
    private const val MAX_BYTES = 16 * 1024 * 1024

    private const val AUTO_PREFIX = "Swift backup (automatic)"
    private const val WEEK = 7L * 24 * 3600 * 1000

    class Summary(val profiles: Int, val bookmarks: Int, val tabs: Int, val history: Int, val created: String)

    /**
     * The backup file's format (3.0.1). Backups made before have none and count as 1; a higher one
     * comes from a newer Swift, and is refused rather than half understood.
     */
    const val FORMAT = 1

    /** Whether a backup of [format] can be restored by this Swift. */
    fun readable(format: Int): Boolean = format in 1..FORMAT

    /** Whether [root] was made by a newer Swift (a higher [FORMAT]). */
    fun isNewer(root: JSONObject): Boolean = root.optInt("format", 1) > FORMAT

    /**
     * What a restore added (3.0.1: the message says exactly this; before, a failure with the
     * settings said nothing was changed although the data was in). [settings]: how many settings were
     * taken from the backup, -1 when they couldn't be put in (they stay as they were).
     */
    class Restored(val profiles: Int, val bookmarks: Int, val history: Int, val tabs: Int, val siteLevels: Int, val settings: Int)

    /** Blocking: writes the backup and returns its file. */
    fun write(ctx: Context): File = write(ctx, automatic = false)

    /**
     * Blocking: the weekly backup, if it is due (Settings → Privacy → Back up every week). Only the
     * two newest automatic backups are kept. Returns the file written, or null.
     */
    fun autoBackupIfDue(ctx: Context): File? {
        val prefs = SwiftApp.prefs
        if (!prefs.autoBackup || System.currentTimeMillis() - prefs.lastAutoBackup < WEEK) return null
        // 3.0: the tabs without their back history (only the addresses are needed).
        if (SwiftApp.db.tabsWithoutState().none { !UrlUtils.isBlank(it.url) }) return null
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
            .put("format", FORMAT)
            .put("version", BuildConfig.VERSION_NAME)
            .put("created", System.currentTimeMillis())

        // 3.0: the user's choices only, each of the kind it should be (BackupSettings).
        val settings = JSONObject()
        for ((key, value) in SwiftApp.prefs.sp.all) {
            val (kind, v) = BackupSettings.forBackup(key, value) ?: continue
            settings.put(key, JSONObject().put("t", kind).put("v", if (v is List<*>) JSONArray(v) else v))
        }
        root.put("settings", settings)

        val profiles = JSONArray()
        val allTabs = db.tabsWithoutState()
        for (p in Profiles.all()) {
            val bookmarks = JSONArray()
            // 3.0: with their folder (2.6 made folders; backups left them out).
            for (b in db.bookmarks(p.id)) {
                bookmarks.put(JSONObject().put("url", b.url).put("title", b.title).put("created", b.created).put("folder", b.folder))
            }
            // The automatic backup (a file in the Download folder, made by itself) leaves history out
            // (2.6): only a backup made on purpose carries it.
            val history = JSONArray()
            if (!automatic) {
                for (h in db.history(p.id, null, HISTORY_LIMIT)) {
                    history.put(JSONObject().put("url", h.url).put("title", h.title).put("last", h.lastVisit).put("visits", h.visits))
                }
            }
            val groups = JSONArray()
            for (g in db.tabGroups().filter { it.profileId == p.id }) groups.put(JSONObject().put("id", g.id).put("name", g.name).put("color", g.color))
            val tabs = JSONArray()
            for (t in allTabs.filter { it.profileId == p.id && !UrlUtils.isBlank(it.url) }) {
                tabs.put(JSONObject().put("url", t.url).put("title", t.title).put("group", t.groupId ?: -1).put("used", t.lastUsed))
            }
            profiles.put(
                JSONObject().put("id", p.id).put("name", p.name).put("color", p.color)
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

    /**
     * Blocking: reads a backup, or null if [uri] isn't one. 3.0: never more than [MAX_BYTES] (a big
     * file picked by mistake isn't read whole), and only what has the shape of a Swift backup.
     */
    fun read(ctx: Context, uri: Uri): JSONObject? = try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { Storage.readAtMost(it, MAX_BYTES) }
        bytes?.let { JSONObject(String(it, Charsets.UTF_8)) }
            ?.takeIf { it.optString("app") == "Swift" && (it.opt("profiles") == null || it.opt("profiles") is JSONArray) }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    fun summary(root: JSONObject): Summary {
        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        var count = 0
        var bookmarks = 0
        var tabs = 0
        var history = 0
        for (i in 0 until profiles.length()) {
            // 3.0: an entry that isn't a profile is passed by (it made the summary crash).
            val p = profiles.optJSONObject(i) ?: continue
            count++
            bookmarks += p.optJSONArray("bookmarks")?.length() ?: 0
            tabs += p.optJSONArray("tabs")?.length() ?: 0
            history += p.optJSONArray("history")?.length() ?: 0
        }
        val created = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(root.optLong("created")))
        return Summary(count, bookmarks, tabs, history, created)
    }

    /** A text of [o], at most [max] long; "" when it isn't one. */
    private fun JSONObject.text(name: String, max: Int = 2048): String = (opt(name) as? String)?.take(max).orEmpty()

    /** A web address of [o] Swift keeps (pages only: never javascript: or Swift's own files). */
    private fun JSONObject.webUrl(name: String): String? = text(name, 8192).takeIf { it.isNotBlank() && UrlUtils.isHttp(it) }

    /**
     * Blocking (run on TabManager.io, so it comes after any pending saves): adds the backup's
     * contents. Swift must restart afterwards to load the tabs and settings. 3.0: the data goes in
     * as one transaction (a failure leaves the phone as it was, and 5,000 pages of history take one
     * write instead of 5,000), then the settings. Null when the data couldn't go in (nothing was
     * changed); otherwise what was added (3.0.1).
     */
    fun restore(ctx: Context, root: JSONObject): Restored? {
        // 3.0.1: a backup from a newer Swift is not half understood.
        if (!readable(root.optInt("format", 1))) return null
        val db = SwiftApp.db
        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        // The backup's profile ids (backups since 2.6) and the ones they get here.
        val ids = HashMap<Long, Long>()
        var newProfiles = 0
        var bookmarks = 0
        var history = 0
        var tabs = 0
        var siteLevels = 0
        try {
            db.transaction {
                val open = db.tabsWithoutState().map { it.profileId to it.url }.toHashSet()
                for (i in 0 until profiles.length()) {
                    val p = profiles.optJSONObject(i) ?: continue
                    val name = p.text("name", 60).trim().ifBlank { "Restored" }
                    val pid = Profiles.all().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                        ?: Profiles.add(name).id.also { newProfiles++ }
                    if (p.has("id")) ids[p.optLong("id")] = pid
                    p.optJSONArray("bookmarks")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            val b = arr.optJSONObject(j) ?: continue
                            val url = b.webUrl("url") ?: continue
                            if (db.importBookmark(pid, url, b.text("title"), b.optLong("created", System.currentTimeMillis()), b.text("folder", 60).trim())) bookmarks++
                        }
                    }
                    p.optJSONArray("history")?.let { arr ->
                        for (j in 0 until minOf(arr.length(), HISTORY_LIMIT)) {
                            val h = arr.optJSONObject(j) ?: continue
                            val url = h.webUrl("url") ?: continue
                            if (db.importHistory(pid, url, h.text("title"), h.optLong("last"), h.optInt("visits", 1).coerceAtLeast(1))) history++
                        }
                    }
                    val groups = HashMap<Long, JSONObject>()
                    p.optJSONArray("groups")?.let { arr ->
                        for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { groups[it.optLong("id")] = it }
                    }
                    // A group is made only for tabs actually added, joining one of the same name if there is one.
                    val groupIds = HashMap<Long, Long>()
                    fun groupFor(id: Long): Long? {
                        val g = groups[id] ?: return null
                        val name = g.text("name", 60)
                        return groupIds.getOrPut(id) {
                            db.tabGroups().firstOrNull { it.profileId == pid && it.name == name }?.id
                                ?: db.insertTabGroup(pid, name, g.optInt("color"))
                        }
                    }
                    p.optJSONArray("tabs")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            val t = arr.optJSONObject(j) ?: continue
                            // Only web pages: another phone's files and Swift's own pages don't exist here.
                            val url = t.webUrl("url") ?: continue
                            if ((pid to url) in open) continue
                            open += pid to url
                            if (db.importTab(pid, url, t.text("title"), groupFor(t.optLong("group", -1)), t.optLong("used", System.currentTimeMillis()))) tabs++
                        }
                    }
                }
                root.optJSONObject("siteLevels")?.let { levels ->
                    for (site in levels.keys()) {
                        if (site.isBlank() || site.length > 253) continue
                        db.setSiteLevel(site, AdLevel.fromKey(levels.optString(site)).key)
                        siteLevels++
                    }
                }
            }
        } catch (e: Exception) {
            L.w("Restoring a backup failed; nothing was changed", e)
            Profiles.refresh()
            return null
        }
        // The settings go in as one edit (all of them or none), after the data. 3.0.1: if they fail,
        // the data stays in and the message says the settings didn't change.
        val settings = try {
            restoreSettings(ctx, root, ids)
        } catch (e: Exception) {
            L.w("Restoring a backup's settings failed; they stay as they were", e)
            -1
        }
        L.d("Backup restored: ${profiles.length()} profiles, $bookmarks bookmarks, $history history, $tabs tabs, $settings settings")
        return Restored(newProfiles, bookmarks, history, tabs, siteLevels, settings)
    }

    /** The message after a restore (3.0.1): exactly what went in. */
    fun restoredText(r: Restored): String {
        fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
        val added = listOfNotNull(
            n(r.profiles, "profile", "profiles").takeIf { r.profiles > 0 },
            n(r.bookmarks, "bookmark", "bookmarks").takeIf { r.bookmarks > 0 },
            n(r.history, "history entry", "history entries").takeIf { r.history > 0 },
            n(r.tabs, "tab", "tabs").takeIf { r.tabs > 0 },
            n(r.siteLevels, "site's ad blocking level", "sites' ad blocking levels").takeIf { r.siteLevels > 0 },
        )
        val data = when (added.size) {
            0 -> "Nothing in it was new for your phone."
            1 -> "Added ${added[0]}."
            else -> "Added ${added.dropLast(1).joinToString(", ")} and ${added.last()}."
        }
        val settings = when {
            r.settings < 0 -> "Its settings couldn't be put in, so yours stay as they were."
            r.settings == 0 -> "It had no settings to restore."
            else -> "${n(r.settings, "setting", "settings")} restored."
        }
        return "$data $settings"
    }

    /**
     * The backup's settings (3.0: last, once the data is in): only those Swift restores, of the kind
     * each one holds. A list setting replaces the phone's (merged, an old backup could add
     * "Saved passwords" to what Quit clears).
     */
    private fun restoreSettings(ctx: Context, root: JSONObject, ids: Map<Long, Long>): Int {
        val settings = root.optJSONObject("settings") ?: return 0
        val edit = SwiftApp.prefs.sp.edit()
        var count = 0
        for (backupKey in settings.keys()) {
            val o = settings.optJSONObject(backupKey) ?: continue
            val raw = when (val v = o.opt("v")) {
                is JSONArray -> (0 until v.length()).map { v.opt(it) }
                JSONObject.NULL -> null
                else -> v
            }
            var value = BackupSettings.fromBackup(backupKey, o.opt("t") as? String, raw) ?: continue
            // A setting of one profile goes to that profile's id here, or is left out if unknown.
            val prefix = BackupSettings.perProfilePrefix(backupKey)
            val key = if (prefix != null) {
                val here = BackupSettings.profileOf(backupKey, prefix)?.let { ids[it] } ?: continue
                "$prefix$here"
            } else {
                backupKey
            }
            when (key) {
                // Another phone's download folder may not exist here; and never Swift's own data folder.
                Prefs.DOWNLOAD_FOLDER -> {
                    val path = value as String
                    val dir = File(path)
                    if (path.isNotBlank() && (!(dir.isDirectory && dir.canWrite()) || AppFiles.isPrivate(ctx, dir))) continue
                }
                // What Quit clears: only what this version knows ("TABS" is ClearData's open tabs).
                QUIT_CLEAR -> {
                    val known = Clearing.What.entries.map { it.name }.toSet() + "TABS"
                    @Suppress("UNCHECKED_CAST")
                    value = (value as Set<String>).filter { it in known }.toSet()
                }
            }
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            count++
        }
        edit.commit()
        return count
    }

    private const val QUIT_CLEAR = "quit_clear"

    /**
     * Runs [restore] after the app's pending database writes; [done] on the main thread with what
     * was added, or null when nothing was changed.
     */
    fun restoreThen(ctx: Context, root: JSONObject, done: (Restored?) -> Unit) {
        val app = ctx.applicationContext
        TabManager.flushSaves()
        TabManager.io.execute {
            val restored = try {
                restore(app, root)
            } catch (e: Exception) {
                L.w("Restoring a backup failed", e)
                null
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(restored) }
        }
    }
}
