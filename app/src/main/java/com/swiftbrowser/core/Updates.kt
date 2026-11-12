package com.swiftbrowser.core

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.SwiftApp
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.File

/**
 * New versions of Swift are published as GitHub releases with the APK attached; this asks GitHub
 * for the newest one. Installing is up to the user: the APK is downloaded like any other file and
 * Android's installer opens it (an update keeps tabs and settings).
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/ibad20sohail/SwiftBrowser/releases/latest"
    /**
     * 3.0.9: each start of Swift asks GitHub (it was once a day), but not again within this time, as
     * when the screen only restarts.
     */
    private const val AUTO_CHECK_INTERVAL_MS = 10L * 60 * 1000

    /** 3.0.9: the newest release a check found, so the next start can offer it at once. */
    private const val FOUND = "update_found"

    class Release(val version: String, val apkUrl: String, val apkName: String, val size: Long, val notes: String)

    /**
     * 3.0: what a check found: the newest [release] with its app file, the newest [version] GitHub
     * names (also without a file), or the [problem] in words people understand (it always said
     * "Check your internet connection", also when GitHub was busy or the file wasn't up yet).
     */
    class Check(val release: Release?, val version: String?, val problem: String?)

    private val io = Background.single("updates")
    private val main = Handler(Looper.getMainLooper())

    /** A check is running (the one at start isn't asked twice). */
    @Volatile private var checking = false

    /**
     * The id of the update download, so the installer can open when it has finished. 3.0: its file
     * is written down as Swift's own update, so [deleteOldApks] can take it once it is installed,
     * wherever it is and even when it has left the downloads list.
     */
    @Volatile var downloadId: Long = -1
        set(v) {
            field = v
            if (v >= 0) com.swiftbrowser.download.Downloads.get(v)?.filePath?.let { noteFile(it) }
        }

    private fun noteFile(path: String) {
        val prefs = SwiftApp.prefs
        if (path !in prefs.updateFiles) prefs.updateFiles = prefs.updateFiles + path
    }

    /** Calls back on the main thread with what GitHub says about the newest release. */
    fun latest(callback: (Check) -> Unit) {
        io.execute {
            val check = try {
                fetch()
            } catch (e: Exception) {
                L.w("Update check failed", e)
                Check(null, null, OFFLINE)
            }
            main.post { callback(check) }
        }
    }

    /**
     * At each start (3.0.9; once a day before); calls back only when there is a newer version, which
     * is also kept for the next start ([remembered]). 3.0: the wait starts from an answer, so a check
     * that failed (no connection, GitHub busy) is tried again at the next start.
     */
    fun checkNow(prefs: Prefs, force: Boolean, callback: (Release) -> Unit) {
        if (checking) return
        if (!force && System.currentTimeMillis() - prefs.lastUpdateCheck in 0 until AUTO_CHECK_INTERVAL_MS) return
        checking = true
        latest { c ->
            checking = false
            if (c.version != null) prefs.lastUpdateCheck = System.currentTimeMillis()
            val r = c.release
            if (r != null && isNewer(r.version, BuildConfig.VERSION_NAME)) {
                remember(prefs, r)
                callback(r)
            } else if (c.version != null && !isNewer(c.version, BuildConfig.VERSION_NAME)) {
                remember(prefs, null)
            }
        }
    }

    /** 3.0.9: the newer release found by an earlier check, or null (none, or it is installed now). */
    fun remembered(prefs: Prefs): Release? {
        val json = prefs.sp.getString(FOUND, null) ?: return null
        val r = try {
            JSONObject(json).let { Release(it.getString("version"), it.getString("url"), it.getString("name"), it.optLong("size", -1), it.optString("notes")) }
        } catch (_: Exception) {
            null
        }
        if (r == null || !isNewer(r.version, BuildConfig.VERSION_NAME)) {
            remember(prefs, null)
            return null
        }
        return r
    }

    /** 3.0.9: keeps [r] for the next start ([remembered]), or forgets it. */
    fun remember(prefs: Prefs, r: Release?) {
        if (r == null) {
            prefs.sp.edit().remove(FOUND).apply()
            return
        }
        val json = JSONObject().put("version", r.version).put("url", r.apkUrl).put("name", r.apkName).put("size", r.size).put("notes", r.notes)
        prefs.sp.edit().putString(FOUND, json.toString()).apply()
    }

    private const val OFFLINE = "Couldn't check for updates. Check your internet connection."

    /** Through the engine, like pages (2.6): with the VPN on, the check goes through it too. */
    private fun fetch(): Check {
        val request = WebRequest.Builder(LATEST)
            .method("GET")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "Swift/${BuildConfig.VERSION_NAME}")
            .build()
        val response = com.swiftbrowser.engine.Engine.executor.fetch(request, GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS).poll(20_000)
            ?: return Check(null, null, OFFLINE)
        val body = response.body
        val code = response.statusCode
        if (code != 200 || body == null) {
            body?.close()
            fun header(name: String) = response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            val problem = when {
                // GitHub answers 60 checks an hour from one network (on mobile data, many phones share one).
                code == 429 || (code == 403 && header("X-RateLimit-Remaining") == "0") -> {
                    val reset = header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                    val minutes = reset?.let { ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(1) }
                    "GitHub has had too many update checks from your network. Try again " +
                        (if (minutes == null || minutes > 90) "in an hour." else if (minutes == 1L) "in a minute." else "in $minutes minutes.")
                }
                code in 500..599 -> "GitHub isn't answering right now (error $code). Try again later."
                else -> "GitHub didn't give the update (error $code). Try again later."
            }
            L.w("Update check: GitHub answered $code")
            return Check(null, null, problem)
        }
        val bytes = body.use { Storage.readAtMost(it, 4 * 1024 * 1024) } ?: return Check(null, null, "GitHub's answer was too big to read. Try again later.")
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val version = json.optString("tag_name").removePrefix("v").trim()
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            val files = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            // 3.0.6: a release has a 32-bit and a 64-bit app file; this phone's is the one to get.
            val name = PhoneSize.pickApk(files.map { it.optString("name") }, wants64Bit(), can64Bit())
            val a = files.firstOrNull { it.optString("name") == name }
            if (name != null && a != null) {
                return Check(Release(version, a.optString("browser_download_url"), name, a.optLong("size", -1), json.optString("body")), version, null)
            }
        }
        // A release whose file isn't up yet (it is added after the release is made).
        return Check(null, version, if (version.isNotEmpty() && isNewer(version, BuildConfig.VERSION_NAME)) "Swift $version is out, but its app file isn't ready yet. Try again in a while." else null)
    }

    /** 3.0.6: the phone can run 64-bit ARM apps. */
    private fun can64Bit(): Boolean = "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS

    /**
     * 3.0.6: this phone should get the 64-bit Swift: it runs it already, or it can and has more memory
     * than a 2 GB phone (which keeps the 32-bit Swift it was tuned with).
     */
    private fun wants64Bit(): Boolean {
        val runs64 = android.os.Process.is64Bit()
        val memory = SwiftApp.instance?.let { PhoneSize.totalMemory(it) } ?: 0L
        return PhoneSize.wants64Bit(runs64, can64Bit(), memory)
    }

    /**
     * 3.0: blocking. Whether [file] is an update of this very app: Swift's own package, signed with
     * the same key as the Swift installed. Android would refuse anything else anyway, with a puzzling
     * "App not installed"; and a file swapped in for the download never reaches the installer.
     */
    fun isGenuine(ctx: Context, file: File): Boolean {
        val pm = ctx.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return false
                if (archive.packageName != ctx.packageName) return false
                val mine = pm.getPackageInfo(ctx.packageName, flags).signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }
                val theirs = archive.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }
                if (theirs.isNotEmpty()) return sameSigners(mine, theirs)
            }
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return false
            if (archive.packageName != ctx.packageName) return false
            @Suppress("DEPRECATION")
            val mine = pm.getPackageInfo(ctx.packageName, flags).signatures.orEmpty().map { it.toCharsString() }
            @Suppress("DEPRECATION")
            val theirs = archive.signatures.orEmpty().map { it.toCharsString() }
            sameSigners(mine, theirs)
        } catch (e: Exception) {
            L.w("Couldn't check the downloaded update", e)
            false
        }
    }

    /** The same keys signed both (and there are some). */
    fun sameSigners(mine: List<String>, theirs: List<String>): Boolean = mine.isNotEmpty() && mine.toSet() == theirs.toSet()

    /**
     * 3.0: the update [item] finished downloading: [install] runs (on the main thread) only when the
     * file is a genuine Swift update; otherwise it is deleted, and [a] says why.
     */
    fun whenGenuine(a: Activity, item: com.swiftbrowser.download.Downloads.Item, install: () -> Unit) {
        val path = item.filePath
        // Android reads the whole file to check it: a few seconds on a slow phone.
        val checkingFile = com.swiftbrowser.ui.Messages.sticky(a, "Checking the update…")
        io.execute {
            val genuine = isGenuine(a, File(path))
            main.post {
                checkingFile?.dismiss()
                if (genuine) {
                    noteFile(path)
                    install()
                    return@post
                }
                L.w("A downloaded update isn't signed like Swift: deleted")
                com.swiftbrowser.download.Downloads.remove(item.id, deleteFile = true)
                if (!a.isFinishing && !a.isDestroyed) {
                    com.swiftbrowser.ui.Messages.show(a, "That download wasn't a genuine Swift update, so Swift deleted it. Check for updates again in Settings → About Swift.", long = true)
                }
            }
        }
    }

    private const val RELEASE_DOWNLOADS = "https://github.com/ibad20sohail/SwiftBrowser/releases/download/"

    /** "Swift-3.0.apk", or "Swift-3.0 (1).apk" when downloaded twice; 3.0.6: also "Swift-64bit-3.0.6.apk". */
    private val APK_NAME = Regex("^Swift-(?:64bit-)?([0-9.]+)(?: \\(\\d+\\))?\\.apk$", RegexOption.IGNORE_CASE)

    /**
     * Blocking: deletes update APKs of this version or older (they were installed, or are of no use
     * any more), from the downloads list and from Swift's own download folders, where they would
     * otherwise stay (about 150 MB each). 3.0: also those Swift wrote down as its own updates
     * (downloadId) in the download folder (Download/Swift), even after they left the list; and
     * there, a file named like a release that is an older Swift. Returns how many bytes were freed.
     */
    fun deleteOldApks(ctx: Context): Long {
        val current = BuildConfig.VERSION_NAME
        fun outdated(name: String): Boolean {
            val v = APK_NAME.find(name)?.groupValues?.get(1) ?: return false
            return !isNewer(v, current)
        }
        var freed = 0L
        for (d in com.swiftbrowser.download.Downloads.all()) {
            if (d.id == downloadId || d.status != com.swiftbrowser.download.Downloads.DONE) continue
            if (!d.url.startsWith(RELEASE_DOWNLOADS) || !outdated(d.fileName)) continue
            freed += File(d.filePath).length()
            com.swiftbrowser.download.Downloads.remove(d.id, deleteFile = true)
        }
        // Left in Swift's own folders without an entry in the list (removed from the list only).
        val dirs = ctx.getExternalFilesDirs(android.os.Environment.DIRECTORY_DOWNLOADS).filterNotNull() +
            File(ctx.filesDir, "downloads")
        for (dir in dirs) {
            for (f in dir.listFiles().orEmpty()) {
                if (f.isFile && outdated(f.name)) {
                    freed += f.length()
                    f.delete()
                }
            }
        }
        // 3.0: the ones written down, wherever they are; and in the download folder (and its Apps
        // folder), one named like a release that Android reads as an older Swift.
        val prefs = SwiftApp.prefs
        val noted = prefs.updateFiles
        val downloads = listOf(com.swiftbrowser.download.Downloads.directory(), com.swiftbrowser.download.Downloads.defaultDirectory())
            .flatMap { listOf(it, File(it, "Apps")) }.distinct()
        val candidates = noted.map { File(it) } + downloads.flatMap { dir -> dir.listFiles { f -> f.isFile && APK_NAME.matches(f.name) }.orEmpty().toList() }
        val active = downloadId.takeIf { it >= 0 }?.let { com.swiftbrowser.download.Downloads.get(it)?.filePath }
        val keep = HashSet<String>()
        for (f in candidates.distinctBy { it.absolutePath }) {
            if (!f.isFile || f.path == active) {
                if (f.path == active) keep += f.path
                continue
            }
            val archive = try {
                ctx.packageManager.getPackageArchiveInfo(f.path, 0)
            } catch (_: Exception) {
                null
            }
            val swift = archive?.packageName == ctx.packageName
            val version = archive?.versionName
            if (swift && version != null && !isNewer(version, current)) {
                freed += f.length()
                f.delete()
            } else if (f.path in noted) {
                keep += f.path
            }
        }
        if (noted.any { it !in keep }) prefs.updateFiles = noted.filter { it in keep }.toSet()
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
