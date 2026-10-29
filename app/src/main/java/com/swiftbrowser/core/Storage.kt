package com.swiftbrowser.core

import android.content.Context
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.StorageController
import java.io.File

/**
 * What Swift keeps on the phone (Settings → Storage), and clearing what sites have stored. The
 * engine keeps each site's stored data in its profile under storage/default (and temporary), one
 * folder per origin, such as "https+++m.youtube.com^geckoViewUserContextId=…".
 */
object Storage {
    class Usage(
        /** The app itself: the engine and Swift. */
        val app: Long,
        /** uBlock Origin's filter lists (and Swift's own helper extension). */
        val adBlock: Long,
        /** Data sites stored (logins stay in cookies, which are tiny). */
        val sites: Long,
        /** Recently loaded files sites reuse. */
        val cache: Long,
        /** The engine's own data: security lists, settings, cookies, history of logins. */
        val engine: Long,
        /** Swift's tabs, history, bookmarks, tab pictures and site icons. */
        val swift: Long,
        /**
         * Files downloaded into Swift's own folders (without storage permission, or the SD card
         * folder): they count as Swift's data and go when Swift is uninstalled.
         */
        val kept: Long,
        /** Everything else Swift's folders hold (news, crash notes, the engine's start-up files). */
        val other: Long,
    ) {
        /** The app, its data folder and its own folders on the storage. */
        val total get() = app + adBlock + sites + cache + engine + swift + kept + other

        /** Android 7's App info shows the cache on its own line, outside its Total. */
        val androidTotal get() = total - cache
    }

    class SiteUsage(val site: String, val bytes: Long, val lastVisit: Long)

    /** Blocking: everything measured. */
    fun measure(ctx: Context): Usage {
        val app = appSize(ctx)
        var adBlock = 0L
        var sites = 0L
        var profiles = 0L
        for (p in profileDirs(ctx)) {
            profiles += sizeOf(p)
            for (origin in originDirs(p)) {
                val size = sizeOf(origin)
                if (origin.name.startsWith("moz-extension")) adBlock += size else sites += size
            }
        }
        val swift = sizeOf(File(ctx.filesDir, "thumbs")) + sizeOf(File(ctx.filesDir, "favicons")) +
            sizeOf(File(ctx.applicationInfo.dataDir, "databases")) + sizeOf(File(ctx.applicationInfo.dataDir, "shared_prefs"))
        val cache = sizeOf(ctx.cacheDir)
        val internalDownloads = sizeOf(File(ctx.filesDir, "downloads"))
        // Downloads in Swift's own folders (inside its data and Android/data/<package>); the rest of
        // Android/data (its cache there) counts as other (2.5), so "kept" means downloads only.
        val kept = keptDownloads(ctx).sumOf { it.length() }
        val external = ownExternalDirs(ctx).sumOf { sizeOf(it) }
        // The whole data folder (not "lib", a link to the installed app), less what is counted above.
        val dataTotal = File(ctx.applicationInfo.dataDir).listFiles()?.filter { it.name != "lib" }?.sumOf { sizeOf(it) } ?: 0L
        val other = (dataTotal - profiles - cache - swift - internalDownloads).coerceAtLeast(0) +
            (external - (kept - internalDownloads)).coerceAtLeast(0)
        return Usage(app, adBlock, sites, cache, (profiles - adBlock - sites).coerceAtLeast(0), swift, kept, other)
    }

    /**
     * The installed app as Android counts it: its folder (the APK and any unpacked engine libraries)
     * and the compiled code, whose folder can't be listed but whose file can be read by name.
     */
    private fun appSize(ctx: Context): Long {
        val apk = File(ctx.applicationInfo.sourceDir)
        val folder = apk.parentFile ?: return apk.length()
        val isas = android.os.Build.SUPPORTED_ABIS.mapNotNull {
            when (it) {
                "arm64-v8a" -> "arm64"
                "armeabi-v7a", "armeabi" -> "arm"
                "x86_64" -> "x86_64"
                "x86" -> "x86"
                else -> null
            }
        }.distinct()
        val compiled = isas.sumOf { File(folder, "oat/$it/base.odex").length() }
        return (folder.listFiles()?.filter { it.name != "oat" }?.sumOf { sizeOf(it) } ?: apk.length()) + compiled
    }

    /** Android/data/<package> on the phone storage and on an SD card. */
    private fun ownExternalDirs(ctx: Context): List<File> =
        (ctx.getExternalFilesDirs(null).toList() + ctx.externalCacheDirs.toList()).filterNotNull()
            .mapNotNull { it.parentFile }.distinctBy { it.absolutePath }

    /** The files downloaded into Swift's own folders, biggest first. */
    fun keptDownloads(ctx: Context): List<File> {
        val dirs = ctx.getExternalFilesDirs(android.os.Environment.DIRECTORY_DOWNLOADS).filterNotNull() + File(ctx.filesDir, "downloads")
        return dirs.flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }.sortedByDescending { it.length() }
    }

    /**
     * Blocking: when the phone is almost full (under 300 MB free), the cache is emptied, at most once a
     * day. Returns how much it held, or 0 when nothing was done.
     */
    fun freeSpaceIfLow(ctx: Context): Long {
        val prefs = SwiftApp.prefs
        if (System.currentTimeMillis() - prefs.lastLowSpaceCleanup < 24L * 3600 * 1000) return 0
        val free = try {
            android.os.StatFs(ctx.filesDir.path).availableBytes
        } catch (_: Exception) {
            return 0
        }
        if (free >= 300L * 1024 * 1024) return 0
        val cache = sizeOf(ctx.cacheDir)
        if (cache < 2L * 1024 * 1024) return 0
        prefs.lastLowSpaceCleanup = System.currentTimeMillis()
        Engine.runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
        L.d("Phone almost full (${free / (1024 * 1024)} MB free): cache cleared")
        return cache
    }

    /** Blocking: sites that stored something, the biggest first. */
    fun sites(ctx: Context): List<SiteUsage> {
        val bytes = HashMap<String, Long>()
        for (p in profileDirs(ctx)) {
            for (origin in originDirs(p)) {
                val site = siteOf(origin.name) ?: continue
                bytes[site] = (bytes[site] ?: 0L) + sizeOf(origin)
            }
        }
        val visits = lastVisits()
        return bytes.filter { it.value >= 4096 }.map { (site, size) -> SiteUsage(site, size, visits[site] ?: 0L) }
            .sortedByDescending { it.bytes }
    }

    /** A site's stored data and cached files; its cookies stay, so you stay signed in. */
    fun clearSite(site: String) {
        Engine.runtime.storageController.clearDataFromBaseDomain(
            site, StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.ALL_CACHES,
        )
    }

    /** Everything of sites not visited for [days] days (not the [open] ones, in a tab); returns how many. */
    fun clearUnused(ctx: Context, days: Int, open: Set<String>): Int {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        val old = sites(ctx).filter { it.lastVisit < limit && it.site !in open }
        for (s in old) Engine.runtime.storageController.clearDataFromBaseDomain(s.site, StorageController.ClearFlags.ALL)
        return old.size
    }

    private fun lastVisits(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((url, last) in SwiftApp.db.lastVisits()) {
            val site = UrlUtils.site(url) ?: continue
            if (last > (out[site] ?: 0L)) out[site] = last
        }
        return out
    }

    private fun profileDirs(ctx: Context): List<File> =
        File(ctx.filesDir, "mozilla").listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun originDirs(profile: File): List<File> =
        listOf("default", "temporary").flatMap { File(profile, "storage/$it").listFiles()?.toList().orEmpty() }.filter { it.isDirectory }

    /** "https+++m.youtube.com^…" → youtube.com; extensions and files have no site. */
    private fun siteOf(name: String): String? {
        val origin = name.substringBefore('^')
        if (!origin.startsWith("http")) return null
        val host = origin.substringAfter("+++").substringBefore('+')
        return if (host.isBlank()) null else UrlUtils.siteOfHost(host)
    }

    fun sizeOf(f: File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { sizeOf(it) } ?: 0
    }
}
