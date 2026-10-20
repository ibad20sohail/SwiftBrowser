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
