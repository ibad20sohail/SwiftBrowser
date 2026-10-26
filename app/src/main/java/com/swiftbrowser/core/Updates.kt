package com.swiftbrowser.core

import android.os.Handler
import android.os.Looper
import com.swiftbrowser.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * New versions of Swift are published as GitHub releases with the APK attached; this asks GitHub
 * for the newest one. Installing is up to the user: the APK is downloaded like any other file and
 * Android's installer opens it (an update keeps tabs and settings).
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/ibad20sohail/SwiftBrowser/releases/latest"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 3600 * 1000

    class Release(val version: String, val apkUrl: String, val apkName: String, val size: Long, val notes: String)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The id of the update download, so the installer can open when it has finished. */
    @Volatile var downloadId: Long = -1

    /** Calls back on the main thread with the newest release, or null if it can't be read. */
    fun latest(callback: (Release?) -> Unit) {
        io.execute {
            val release = try {
                fetch()
            } catch (e: Exception) {
                L.w("Update check failed", e)
                null
            }
            main.post { callback(release) }
        }
    }

    /** Once a day at most; calls back only when there is a newer version. */
    fun checkNow(prefs: Prefs, force: Boolean, callback: (Release) -> Unit) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastUpdateCheck < AUTO_CHECK_INTERVAL_MS) return
        prefs.lastUpdateCheck = now
        latest { r -> if (r != null && isNewer(r.version, BuildConfig.VERSION_NAME)) callback(r) }
    }

    private fun fetch(): Release? {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Swift/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("tag_name").removePrefix("v").trim()
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return Release(version, a.optString("browser_download_url"), name, a.optLong("size", -1), json.optString("body"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private const val RELEASE_DOWNLOADS = "https://github.com/ibad20sohail/SwiftBrowser/releases/download/"
    private val APK_NAME = Regex("^Swift-([0-9.]+)\\.apk$", RegexOption.IGNORE_CASE)

    /**
     * Blocking: deletes update APKs of this version or older (they were installed, or are of no use
     * any more), from the downloads list and from Swift's own download folders, where they would
     * otherwise stay (about 150 MB each). Returns how many bytes were freed.
     */
    fun deleteOldApks(ctx: android.content.Context): Long {
        val current = BuildConfig.VERSION_NAME
        fun outdated(name: String): Boolean {
            val v = APK_NAME.find(name)?.groupValues?.get(1) ?: return false
            return !isNewer(v, current)
        }
        var freed = 0L
        for (d in com.swiftbrowser.download.Downloads.all()) {
            if (d.id == downloadId || d.status != com.swiftbrowser.download.Downloads.DONE) continue
            if (!d.url.startsWith(RELEASE_DOWNLOADS) || !outdated(d.fileName)) continue
            freed += java.io.File(d.filePath).length()
            com.swiftbrowser.download.Downloads.remove(d.id, deleteFile = true)
        }
        // Left in Swift's own folders without an entry in the list (removed from the list only).
        val dirs = ctx.getExternalFilesDirs(android.os.Environment.DIRECTORY_DOWNLOADS).filterNotNull() +
            java.io.File(ctx.filesDir, "downloads")
        for (dir in dirs) {
            for (f in dir.listFiles().orEmpty()) {
                if (f.isFile && outdated(f.name)) {
                    freed += f.length()
                    f.delete()
                }
            }
        }
        if (freed > 0) L.d("Deleted old update files: ${freed / (1024 * 1024)} MB")
        return freed
    }

    /** "1.10" is newer than "1.9". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
