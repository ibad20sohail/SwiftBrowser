package com.swiftbrowser.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.swiftbrowser.core.Db
import com.swiftbrowser.core.DownloadRow
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.DownloadRules.Why
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.torrent.Torrents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

/**
 * Download manager. Normal files resume with HTTP range requests; HLS streams (.m3u8) are saved
 * segment by segment (decrypting AES-128 if needed) and then converted to MP4 when possible.
 * All network requests go through Gecko, so they use the same TLS and certificates as pages.
 */
object Downloads {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val FAILED = 4
    const val CONVERTING = 5

    const val KIND_FILE = "file"
    const val KIND_HLS = "hls"
    /** 2.9.2: a torrent, run by Torrents; its path is its file, or its folder when it has several. */
    const val KIND_TORRENT = "torrent"

    private const val MAX_PARALLEL = 2

    /** Shown on a download that waits for Wi-Fi (see Prefs.wifiOnlyDownloads). */
    const val WAITING_FOR_WIFI = "Waiting for Wi-Fi"
    private const val HLS_PARALLEL = 4

    class Item(
        val id: Long,
        val profileId: Long,
        val kind: String,
        val url: String,
        @Volatile var filePath: String,
        @Volatile var fileName: String,
        val mime: String?,
        @Volatile var total: Long,
        @Volatile var done: Long,
        @Volatile var status: Int,
        @Volatile var error: String?,
        /** The page's cookie and sign-in for it; 3.0: let go of once it finished or failed for good. */
        @Volatile var headers: Map<String, String>,
        val pageUrl: String?,
        @Volatile var extra: JSONObject,
        val created: Long,
        /** Started in an incognito tab (2.6): only in memory, gone from the list when incognito ends. */
        val incognito: Boolean = false,
    ) {
        /** A stream's parts saved, of all of them (3.0: its separate sound track's included). */
        @Volatile var segDone: Int = extra.optInt("seg", 0) + extra.optInt("aseg", 0)
        @Volatile var segTotal: Int = extra.optInt("segTotal", 0)

        /** 0..100, or -1 when unknown. */
        val percent: Int
            get() {
                if (status == DONE) return 100
                if (kind == KIND_HLS) return if (segTotal > 0) segDone * 100 / segTotal else -1
                return if (total > 0) (done * 100 / total).toInt() else -1
            }

        @Volatile var bytesPerSecond: Long = 0

        /** A torrent's people sharing it right now (2.9.2). */
        @Volatile var peers: Int = 0
    }

    private lateinit var app: Context
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private val items = LinkedHashMap<Long, Item>()

    /**
     * 3.0: one run of a download's job, and what it has open. A stop (Pause, Cancel, Wi-Fi lost)
     * closes those at once, so a read waiting for the site ends there instead of holding the run up
     * for up to a minute; a new run of the same download waits for the stopped one to be over (both
     * would write the same file), and a run only ever takes itself off the list.
     */
    private class Run {
        lateinit var job: Job
        private val open: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()

        @Volatile var stopped = false
            private set

        fun stop() {
            stopped = true
            job.cancel()
            for (c in open) runCatching { c.close() }
        }

        /** [block] with [c] open: [c] is closed by a stop meanwhile. */
        fun <T> using(c: Closeable, block: () -> T): T {
            open += c
            try {
                if (stopped) runCatching { c.close() }
                return block()
            } finally {
                open -= c
            }
        }
    }

    private val runs = ConcurrentHashMap<Long, Run>()

    /** Stopped runs not over yet, by download: the next run waits for its download's. */
    private val ending = ConcurrentHashMap<Long, Job>()
    private val firstResponses = ConcurrentHashMap<Long, WebResponse>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var notifyPending = false

    fun init(ctx: Context, db: Db, prefs: Prefs) {
        app = ctx.applicationContext
        this.db = db
        this.prefs = prefs
        for (r in db.downloads().reversed()) {
            val item = Item(
                r.id, r.profileId, r.kind, r.url, r.filePath, r.fileName, r.mime, r.total, r.done, r.status, r.error,
                parseHeaders(r.headers), r.pageUrl, r.extra?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject(),
                r.created,
            )
            // Anything interrupted by Swift or the phone closing goes on by itself (a download the
            // user paused stays paused). A file Swift was writing itself (Save offline) can't go on:
            // fetching its address again would save the page's HTML instead (2.6).
            if (item.status == RUNNING || item.status == QUEUED || item.status == CONVERTING) {
                if (item.extra.optBoolean("local")) {
                    item.status = FAILED
                    item.error = "Swift was closed while saving it. Save it again."
                } else {
                    item.status = QUEUED
                }
            }
            // 3.0: an MP4 Swift was making when it was closed is unfinished: it goes (the stream it was
            // made from is still there, and is converted again).
            item.extra.optString("mp4").takeIf { it.isNotEmpty() && it != item.filePath }?.let {
                File(it).delete()
                item.extra.remove("mp4")
            }
            items[item.id] = item
        }
        com.swiftbrowser.engine.DataSaver.networkListeners += { onNetworkChanged() }
        com.swiftbrowser.engine.DataSaver.onlineListeners += { retryFailed() }
        // A moment after starting, so the app comes up first.
        main.postDelayed({ schedule() }, 4000)
        main.postDelayed({ dropMissing() }, 6000)
    }

    /**
     * Finished downloads whose file was deleted (in Files, the gallery, another app) leave the list
     * (2.9): at start and each time the Downloads screen is shown.
     */
    fun dropMissing() {
        scope.launch {
            val done = synchronized(items) { items.values.filter { it.status == DONE } }
            val gone = done.filter { reallyGone(File(it.filePath)) }
            if (gone.isEmpty()) return@launch
            synchronized(items) { gone.forEach { items.remove(it.id) } }
            gone.forEach { if (!it.incognito) db.deleteDownload(it.id) }
            gone.filter { it.kind == KIND_TORRENT }.forEach { Torrents.remove(it, deleteFiles = false) }
            changed(immediate = true)
        }
    }

    /**
     * 3.0: [f] was deleted for real: not merely out of sight because its SD card is out or Swift's
     * access to the phone's storage was taken away (then nothing there can be seen, and the entries
     * and a torrent's progress must stay for when it is back).
     */
    private fun reallyGone(f: File): Boolean {
        if (f.exists()) return false
        val state = try {
            Environment.getExternalStorageState(f)
        } catch (_: Exception) {
            Environment.MEDIA_UNKNOWN
        }
        // Swift's own folders say "unknown": those are always there.
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY && state != Environment.MEDIA_UNKNOWN) return false
        var d = f.parentFile
        while (d != null && !d.exists()) d = d.parentFile
        return d != null && d.canRead() && d.list() != null
    }

    fun all(): List<Item> = synchronized(items) { items.values.reversed() }

    /** Clearing browsing data (2.6): entries started since [since] leave the list; the files stay, running ones go on. */
    fun removeEntriesSince(since: Long, until: Long = Long.MAX_VALUE) {
        for (item in entriesSince(since, until)) remove(item.id, deleteFile = false)
    }

    /** 2.8: the entries [removeEntriesSince] takes from the list (started from [since] to [until], not running). */
    fun entriesSince(since: Long, until: Long = Long.MAX_VALUE): List<Item> =
        synchronized(items) { items.values.filter { it.created in since..until && it.status != RUNNING && it.status != QUEUED && it.status != CONVERTING } }

    fun get(id: Long): Item? = synchronized(items) { items[id] }

    fun activeCount(): Int = synchronized(items) {
        items.values.count { it.status == RUNNING || it.status == QUEUED || it.status == CONVERTING }
    }

    /** 2.9.2: the row of the torrent with info hash [hash], if it is in the list. */
    fun findTorrent(hash: String): Item? = synchronized(items) {
        items.values.firstOrNull { it.kind == KIND_TORRENT && it.extra.optString("hash").equals(hash, ignoreCase = true) }
    }

    /**
     * 2.9.2: a torrent's row (Torrents.add): [path] is its file, or its folder when it has several;
     * [total] the size of the files chosen (-1 until a magnet link's file list is known).
     */
    fun addTorrent(name: String, url: String, path: String, total: Long, extra: JSONObject): Item {
        val now = System.currentTimeMillis()
        val profileId = prefs.currentProfileId
        val clean = UrlUtils.sanitizeFileName(name)
        val id = db.insertDownload(DownloadRow(0, profileId, KIND_TORRENT, url, path, clean, null, total, 0, QUEUED, null, "{}", null, extra.toString(), now))
        val item = Item(id, profileId, KIND_TORRENT, url, path, clean, null, total, 0, QUEUED, null, emptyMap(), null, extra, now)
        synchronized(items) { items[id] = item }
        changed(immediate = true)
        schedule()
        return item
    }

    /** 2.9.2: Torrents changed [item] (null: only progress): saved with [persist], and shown. */
    fun torrentChanged(item: Item?, persist: Boolean) {
        if (persist && item != null) persist(item)
        changed(immediate = persist)
        if (persist) startService()
    }

    /** Who runs is worked out again (2.9.2: after a torrent's files changed). */
    fun reschedule() = main.post { schedule() }

    /** 3.0: the alarm for downloads waiting for a time (the night, a torrent's next try) went off. */
    fun wake() {
        if (!::app.isInitialized) return
        reschedule()
    }

    /** The notification's Pause all (2.9.2): everything downloading or waiting stops until resumed. */
    fun pauseAll() {
        synchronized(items) { items.values.filter { it.status == RUNNING || it.status == QUEUED }.map { it.id } }.forEach { pause(it) }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Coalesces progress updates to at most a few per second. */
    private fun changed(immediate: Boolean = false) {
        if (immediate) {
            // A copy: a listener may remove itself (a document opened once downloaded, 2.4).
            main.post { listeners.toList().forEach { it() } }
            return
        }
        synchronized(this) {
            if (notifyPending) return
            notifyPending = true
        }
        main.postDelayed({
            synchronized(this) { notifyPending = false }
            listeners.toList().forEach { it() }
        }, 400)
    }

    // ------------------------------------------------------------------ starting downloads

    fun hasStoragePermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Where downloads go unless chosen otherwise: Download/Swift on the phone's storage. */
    fun defaultDirectory(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Swift")

    /**
     * The folder chosen in Settings (Download/Swift unless changed). Without storage permission,
     * or if the folder can't be written (an SD card taken out), the app's own folder.
     */
    fun directory(): File {
        val chosen = prefs.downloadFolder.takeIf { it.isNotBlank() }?.let { File(it) }
        val dir = when {
            // Swift's own folder on an SD card needs no permission.
            chosen != null && chosen.path.contains("/Android/data/") && usable(chosen) -> chosen
            !hasStoragePermission(app) -> app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "downloads")
            chosen != null && usable(chosen) -> chosen
            else -> defaultDirectory()
        }
        dir.mkdirs()
        return dir
    }

    private fun usable(dir: File): Boolean = (dir.isDirectory || dir.mkdirs()) && dir.canWrite()

    /**
     * Where a new download of [name] goes (2.9): with "Sort downloads into folders", a folder for
     * its type inside the download folder; anything else stays in the download folder itself.
     */
    /** 2.9.2: where a torrent whose main file is [name] goes: its kind's folder when sorting is on, else null. */
    fun sortedFolderFor(name: String): File? = directoryFor(name, null).takeIf { prefs.sortDownloads && it != directory() }

    private fun directoryFor(name: String, mime: String?): File {
        val dir = directory()
        if (!prefs.sortDownloads) return dir
        val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val type = mime?.substringBefore('/')?.lowercase(java.util.Locale.ROOT)
        val folder = when {
            ext == "apk" -> "Apps"
            type == "video" || ext in VIDEO_EXTS -> "Videos"
            type == "audio" || ext in AUDIO_EXTS -> "Music"
            type == "image" || ext in IMAGE_EXTS -> "Pictures"
            com.swiftbrowser.docs.Docs.kindOf(name, mime) != null && ext != "zip" -> "Documents"
            else -> return dir
        }
        val sub = File(dir, folder)
        return if (usable(sub)) sub else dir
    }

    private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts", "flv", "wmv", "m3u8")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "wma")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")

    /** Swift's folder on a removable SD card, if the phone has one (deleted with the app). */
    fun sdCardDirectory(): File? =
        app.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS).drop(1).firstOrNull { it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }

    /** A folder path as people know it: "Phone › Download/Swift". */
    fun describe(dir: File): String {
        val phone = Environment.getExternalStorageDirectory().path
        return when {
            dir.path.startsWith(phone) -> "Phone storage › " + dir.path.removePrefix(phone).trimStart('/')
            dir.path.contains("/Android/data/") -> "SD card › Swift's folder"
            else -> dir.path
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists() && !reserved(f.path)) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var n = 1
        while (f.exists() || reserved(f.path)) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    /** A path another entry has, finished or not: deleting that entry's file must never hit a new download (2.6). */
    private fun reserved(path: String) = synchronized(items) { items.values.any { it.filePath == path } }

    private var nextPrivateId = -1L

    private fun create(
        profileId: Long, kind: String, url: String, fileName: String, mime: String?, total: Long,
        headers: Map<String, String>, pageUrl: String?, extra: JSONObject, incognito: Boolean = false,
    ): Item {
        val clean = UrlUtils.sanitizeFileName(fileName)
        val file = synchronized(items) { uniqueFile(directoryFor(clean, mime), clean) }
        val now = System.currentTimeMillis()
        val id = if (incognito) {
            // Incognito downloads aren't written down (the file itself is kept, as in other browsers).
            synchronized(items) { nextPrivateId-- }
        } else {
            val row = DownloadRow(
                0, profileId, kind, url, file.path, file.name, mime, total, 0, QUEUED, null,
                JSONObject(headers as Map<*, *>).toString(), pageUrl, extra.toString(), now,
            )
            db.insertDownload(row)
        }
        val item = Item(id, profileId, kind, url, file.path, file.name, mime, total, 0, QUEUED, null, headers, pageUrl, extra, now, incognito)
        synchronized(items) { items[id] = item }
        return item
    }

    /** A page response the engine couldn't display (an attachment, an APK, a video file...). */
    fun startFromResponse(profileId: Long, response: WebResponse, fileName: String, mime: String?, pageUrl: String?, incognito: Boolean = false): Item {
        val total = response.headers["Content-Length"]?.toLongOrNull() ?: -1
        val item = create(profileId, KIND_FILE, response.uri, fileName, mime, total, emptyMap(), pageUrl, JSONObject(), incognito)
        firstResponses[item.id] = response
        schedule()
        return item
    }

    fun startUrl(
        profileId: Long, url: String, fileName: String, mime: String?, headers: Map<String, String>, pageUrl: String?,
        incognito: Boolean = false,
    ): Item {
        val item = create(profileId, KIND_FILE, url, fileName, mime, -1, headers, pageUrl, JSONObject(), incognito)
        schedule()
        return item
    }

    /**
     * A file Swift wrote itself (a page saved for offline reading): listed with the downloads.
     * [write] fills the file; it runs off the main thread, and [done] gets the item or null.
     * 3.0: from an incognito tab ([incognito]) it is an incognito download, like the others there.
     */
    fun saveFile(
        profileId: Long, fileName: String, mime: String, pageUrl: String?, write: (File) -> Unit, incognito: Boolean = false, done: (Item?) -> Unit,
    ) {
        val item = create(profileId, KIND_FILE, pageUrl ?: "", fileName, mime, -1, emptyMap(), pageUrl, JSONObject().put("local", true), incognito)
        item.status = RUNNING
        changed(immediate = true)
        scope.launch {
            val ok = try {
                write(File(item.filePath))
                true
            } catch (e: Exception) {
                L.w("Saving ${item.fileName} failed", e)
                false
            }
            if (ok) {
                val f = File(item.filePath)
                item.done = f.length()
                item.total = item.done
                item.status = DONE
                MediaScannerConnection.scanFile(app, arrayOf(item.filePath), null, null)
            } else {
                File(item.filePath).delete()
                item.status = FAILED
                item.error = "Couldn't save the page"
            }
            persist(item)
            changed(immediate = true)
            main.post { done(if (ok) item else null) }
        }
    }

    /**
     * [playlistUrl] must be a media playlist (a chosen quality), not a master playlist. 3.0: [audio]
     * is the playlist of its sound when that comes separately (Hls.Variant.audio): it is saved too,
     * and joined with the picture in the MP4.
     */
    fun startHls(
        profileId: Long, playlistUrl: String, title: String, headers: Map<String, String>, pageUrl: String?, incognito: Boolean = false,
        audio: String? = null,
    ): Item {
        val name = UrlUtils.sanitizeFileName(title).removeSuffix(".mp4").removeSuffix(".ts") + ".ts"
        val extra = JSONObject()
        audio?.let { extra.put("audio", it) }
        val item = create(profileId, KIND_HLS, playlistUrl, name, "video/mp2t", -1, headers, pageUrl, extra, incognito)
        schedule()
        return item
    }

    /**
     * 3.0: a stream that stopped part-way (failed, or paused) is kept as it is: what was saved
     * becomes the finished download (an MP4 when it can be), a video that ends early.
     */
    fun keepPart(id: Long) {
        val item = get(id) ?: return
        if (item.kind != KIND_HLS || (item.status != FAILED && item.status != PAUSED) || !hasPart(item)) return
        item.extra.put("keep", true)
        item.extra.remove("retryAt")
        item.status = QUEUED
        item.error = null
        persist(item)
        changed(immediate = true)
        schedule()
    }

    /** 3.0: some of a stream's picture is saved ("Keep what was downloaded"). */
    fun hasPart(item: Item): Boolean = item.kind == KIND_HLS && item.extra.optInt("seg", 0) > 0 && item.extra.optLong("bytes", 0) > 0

    fun pause(id: Long) {
        val item = get(id) ?: return
        if (item.status != RUNNING && item.status != QUEUED) return
        item.status = PAUSED
        stopJob(id)
        persist(item)
        changed(immediate = true)
        schedule()
    }

    fun resume(id: Long) {
        val item = get(id) ?: return
        if (item.status != PAUSED && item.status != FAILED) return
        item.status = QUEUED
        item.error = null
        item.extra.remove("retries")
        item.extra.remove("retryAt")
        // 3.0: a torrent no one shared starts its tries again (Torrents).
        item.extra.remove("idle")
        persist(item)
        changed(immediate = true)
        schedule()
    }

    /**
     * Removes the entry. 3.0: the files are deleted only with [deleteFile] (Cancel, Delete): "Remove
     * from list (keep the files)" keeps them whatever the state, an unfinished file where it is and
     * a paused season pack's finished episodes. Before 3.0 an unfinished download lost its files.
     */
    fun remove(id: Long, deleteFile: Boolean) {
        val item = get(id) ?: return
        stopJob(id)
        firstResponses.remove(id)?.let { GeckoFetch.close(it) }
        synchronized(items) { items.remove(id) }
        // 2.9.2: a torrent's files are its own list (a folder may hold others'), deleted by Torrents.
        if (item.kind == KIND_TORRENT) Torrents.remove(item, deleteFiles = deleteFile)
        val stopping = ending[id]
        scope.launch {
            if (!item.incognito) db.deleteDownload(id)
            if (item.kind == KIND_TORRENT) return@launch
            // 3.0: once the stopped run is over: it may still be writing (a part, the MP4).
            stopping?.join()
            if (deleteFile) {
                File(item.filePath).delete()
                soundFile(item)?.delete()
                item.extra.optString("mp4").takeIf { it.isNotEmpty() && it != item.filePath }?.let { File(it).delete() }
            } else {
                soundFile(item)?.let { revealSound(item, it) }
            }
        }
        changed(immediate = true)
        schedule()
    }

    /** 3.0: stops [id]'s run; the next one waits for it to be over. */
    private fun stopJob(id: Long) {
        val run = runs.remove(id) ?: return
        val job = run.job
        ending[id] = job
        job.invokeOnCompletion {
            ending.remove(id, job)
            main.post { schedule() }
        }
        run.stop()
    }

    /**
     * On mobile data with "Big downloads wait for Wi-Fi": videos and files over 50 MB wait. 3.0: not a
     * stream being kept as it is (nothing more is fetched for it).
     */
    private fun mustWait(item: Item): Boolean = !(item.kind == KIND_HLS && item.extra.optBoolean("keep")) && DownloadRules.mustWait(
        prefs.wifiOnlyDownloads, com.swiftbrowser.engine.DataSaver.onMobileData, item.kind == KIND_HLS, item.total, item.done, item.mime,
    )

    /**
     * The connection is back: downloads that failed for lack of it try again, a few times at most
     * (not those whose link expired or that the server refused).
     */
    private fun retryFailed() {
        var any = false
        synchronized(items) { items.values.toList() }.forEach { item ->
            if (item.status != FAILED || item.kind == KIND_TORRENT || !networkError(item)) return@forEach
            val tries = item.extra.optInt("retries", 0)
            if (tries >= DownloadRules.TRIES) return@forEach
            item.extra.put("retries", tries + 1)
            item.extra.remove("retryAt")
            item.status = QUEUED
            item.error = DownloadRules.retrying(tries + 1)
            persist(item)
            any = true
        }
        if (any) {
            changed(immediate = true)
            schedule()
        }
    }

    /** 3.0: by why it failed (rows from before 3.0 only have the error's words). */
    private fun networkError(item: Item): Boolean {
        val why = item.extra.optString("why")
        return if (why.isNotEmpty()) why == Why.NETWORK.name else DownloadRules.legacyNetwork(item.error)
    }

    /**
     * 3.0: [item]'s run failed with [e]: in plain words on its row. A lost connection is tried again
     * by itself a few times ("Retrying (2 of 3)"), at once when the connection is back; what the site
     * refused (an expired link) or can never be saved (a live stream, copy protection) isn't.
     */
    private fun failed(item: Item, e: Throwable) {
        val p = DownloadRules.explain(e)
        L.w("Download ${item.id} failed (${p.why.name.lowercase()})", e)
        item.status = FAILED
        item.error = p.text
        item.extra.put("why", p.why.name)
        item.bytesPerSecond = 0
        val tries = item.extra.optInt("retries", 0)
        if (p.why == Why.NETWORK && tries < DownloadRules.TRIES && com.swiftbrowser.engine.DataSaver.online) {
            // A few seconds more each time; offline, it waits for the connection instead.
            val wait = 10_000L * (tries + 1)
            item.extra.put("retries", tries + 1)
            item.extra.put("retryAt", System.currentTimeMillis() + wait)
            item.status = QUEUED
            item.error = DownloadRules.retrying(tries + 1)
            main.postDelayed({ schedule() }, wait + 100)
        } else if (p.why != Why.NETWORK) {
            // Failed for good: the site's cookie and sign-in aren't kept for it any more.
            forgetHeadersOf(item)
        }
        persist(item)
    }

    /** 3.0: waiting for a moment to try again ([failed]). */
    private fun retryingLater(item: Item): Boolean = item.extra.optLong("retryAt", 0) > System.currentTimeMillis()

    /** The phone moved between Wi-Fi and mobile data, or the setting changed. */
    fun onNetworkChanged() {
        synchronized(items) { items.values.filter { it.kind != KIND_TORRENT } }.forEach { item ->
            if (item.status == RUNNING && mustWait(item)) {
                stopJob(item.id)
                item.status = QUEUED
                item.error = WAITING_FOR_WIFI
                persist(item)
            } else if (item.status == QUEUED && item.error == WAITING_FOR_WIFI && !mustWait(item)) {
                item.error = null
            }
        }
        changed(immediate = true)
        schedule()
    }

    private fun schedule() {
        val toStart = synchronized(items) {
            // 2.9.2: torrents have their own turns and rules (Torrents).
            val running = items.values.count { (it.status == RUNNING || it.status == CONVERTING) && it.kind != KIND_TORRENT }
            val queued = items.values.filter {
                it.status == QUEUED && it.kind != KIND_TORRENT && !runs.containsKey(it.id) && !ending.containsKey(it.id) && !retryingLater(it)
            }
            val start = queued
                .filter { item ->
                    val wait = mustWait(item)
                    item.error = if (wait) WAITING_FOR_WIFI else item.error?.takeIf { it != WAITING_FOR_WIFI }
                    !wait
                }
                .sortedBy { it.created }
                .take((MAX_PARALLEL - running).coerceAtLeast(0))
            // The page's open answer of one that can't start now is let go (3.0: also when the others
            // take every turn, rather than kept for minutes and failing then): its file is asked for
            // again when it starts.
            for (item in queued) if (item !in start) firstResponses.remove(item.id)?.let { GeckoFetch.close(it) }
            start
        }
        for (item in toStart) launch(item)
        Torrents.reschedule()
        startService()
    }

    /** The notification and the service: shown while something downloads, or (quietly) waits. */
    private fun startService() = DownloadService.sync(app)

    /**
     * 3.0: something downloads, converts or is about to: the service keeps Swift running for it.
     * Downloads that only wait (for Wi-Fi, the night, the charger, the VPN to go off) don't.
     */
    fun needsService(): Boolean = synchronized(items) {
        items.values.any { it.status == RUNNING || it.status == CONVERTING || (it.status == QUEUED && !waiting(it)) }
    }

    /** 3.0: really transferring (or converting, or about to try again): what keeps the phone awake. */
    fun busy(): Boolean = synchronized(items) {
        items.values.any { it.status == RUNNING || it.status == CONVERTING || (it.status == QUEUED && retryingLater(it)) }
    }

    /** 3.0: [item] waits for something outside Swift (Wi-Fi, the night, the charger…), not just its turn. */
    fun waiting(item: Item): Boolean {
        if (item.status != QUEUED) return false
        val why = item.error ?: return false
        return if (item.kind == KIND_TORRENT) Torrents.waitsFor(why) else why == WAITING_FOR_WIFI
    }

    private fun launch(item: Item) {
        item.status = RUNNING
        persist(item)
        changed(immediate = true)
        val run = Run()
        // 3.0: a run stopped a moment ago (Pause, then Resume) is over first: it may still be writing.
        val before = ending[item.id]
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                before?.join()
                if (item.kind == KIND_HLS) runHls(item, run) else runFile(item, run)
                ensureActive()
                finish(item)
            } catch (e: CancellationException) {
                // paused or removed
            } catch (e: WaitForWifi) {
                // 3.0: its size is known now: a big file on mobile data waits for Wi-Fi after all.
                if (runs[item.id] === run) {
                    item.status = QUEUED
                    item.error = WAITING_FOR_WIFI
                    persist(item)
                }
            } catch (e: Throwable) {
                // 3.0: only this run's own failure: a stopped run's read cut short isn't one, nor may
                // it mark the run that took its place as failed.
                if (isActive && runs[item.id] === run && (item.status == RUNNING || item.status == CONVERTING)) failed(item, e)
            } finally {
                runs.remove(item.id, run)
                if (!runs.containsKey(item.id)) item.bytesPerSecond = 0
                changed(immediate = true)
                main.post { schedule() }
            }
        }
        runs[item.id] = run
        run.job.start()
    }

    /** 3.0: a file of unknown size grew past 50 MB on mobile data, or its size became known. */
    private class WaitForWifi : Exception()

    private suspend fun finish(item: Item) {
        if (item.kind == KIND_HLS) finishStream(item)
        // 3.0: cancelled while converting: it isn't done, nor said to be.
        currentCoroutineContext().ensureActive()
        val f = File(item.filePath)
        item.done = f.length()
        item.total = item.done
        item.status = DONE
        item.error = null
        item.extra.remove("retries")
        item.extra.remove("retryAt")
        item.extra.remove("why")
        forgetHeadersOf(item)
        persist(item)
        val scanned = listOfNotNull(item.filePath, item.extra.optString("soundFile").takeIf { it.isNotEmpty() })
        MediaScannerConnection.scanFile(app, scanned.toTypedArray(), null, null)
        main.post { DownloadService.notifyFinished(app, item) }
    }

    /**
     * 3.0: a saved stream becomes an MP4 (with its separate sound track, when it has one) only when
     * that is safe: under 4 GB, with room for the copy, and the MP4 checked to be whole before the
     * stream is deleted. Otherwise the stream stays as it is (a .ts file, which Swift's player, VLC
     * and MX Player play), and the row says why.
     */
    private suspend fun finishStream(item: Item) {
        val job = currentCoroutineContext().job
        val video = File(item.filePath)
        val sound = soundFile(item)?.takeIf { it.isFile && it.length() > 0 }
        val partial = item.extra.optBoolean("keep")
        if (partial) item.extra.put("note", partNote(item))
        // A fragmented MP4 stream is an MP4 already; with its sound apart, the two are joined.
        if (!prefs.hlsToMp4 || (item.extra.optBoolean("fmp4") && sound == null)) {
            sound?.let { keepApart(item, it) }
            return
        }
        val dir = video.parentFile ?: directory()
        val bytes = video.length() + (sound?.length() ?: 0)
        val free = try {
            StatFs(dir.path).availableBytes
        } catch (_: Exception) {
            -1L
        }
        when (DownloadRules.convert(bytes, free)) {
            DownloadRules.Convert.TOO_BIG -> {
                addNote(item, "Saved as .ts: it's too big to become an MP4 on this phone")
                sound?.let { keepApart(item, it) }
                return
            }
            DownloadRules.Convert.NO_ROOM -> {
                addNote(item, "Saved as .ts: there wasn't room to make an MP4 of it")
                sound?.let { keepApart(item, it) }
                return
            }
            DownloadRules.Convert.OK -> Unit
        }
        item.status = CONVERTING
        val base = video.name.substringBeforeLast('.')
        val mp4 = synchronized(items) { uniqueFile(dir, "$base.mp4") }
        // Written down first: a conversion Swift's closing cuts short is deleted at the next start.
        item.extra.put("mp4", mp4.path)
        persist(item)
        changed(immediate = true)
        val result = Remuxer.remux(listOfNotNull(video, sound), mp4) { !job.isActive }
        // What went in, and what the playlist said (only for a whole stream).
        val expected = maxOf(result?.videoUs ?: 0L, if (partial) 0L else (item.extra.optDouble("duration", 0.0) * 1_000_000).toLong())
        val whole = result != null && job.isActive && DownloadRules.lengthOk(expected, Remuxer.lengthUs(mp4))
        item.extra.remove("mp4")
        if (!whole) {
            mp4.delete()
            if (!job.isActive) return
            L.d("Kept the stream of download ${item.id}: the MP4 wasn't whole")
            addNote(item, "Saved as .ts: Swift couldn't make a whole MP4 of it")
            sound?.let { keepApart(item, it) }
            return
        }
        video.delete()
        sound?.delete()
        item.extra.remove("soundFile")
        // A fragmented stream had the MP4's own name: the new one takes it back.
        var out = mp4
        val wanted = File(dir, "$base.mp4")
        if (out != wanted && !wanted.exists() && out.renameTo(wanted)) out = wanted
        item.filePath = out.path
        item.fileName = out.name
    }

    /** "The first 12 of 45 minutes": how much of the stream a kept part has. */
    private fun partNote(item: Item): String {
        val total = item.extra.optDouble("duration", 0.0)
        val saved = item.extra.optDouble("secs", 0.0)
        val m = (saved / 60).toInt().coerceAtLeast(1)
        return if (total > 0 && saved > 0) "Only part of it: the first $m of ${(total / 60).toInt().coerceAtLeast(m)} minutes" else "Only the part that was downloaded"
    }

    // ------------------------------------------------------------------ 3.0: a stream's separate sound track

    /** Where [item]'s separate sound track is saved while it downloads (hidden next to the video). */
    private fun soundFile(item: Item): File? {
        if (item.kind != KIND_HLS) return null
        item.extra.optString("soundFile").takeIf { it.isNotEmpty() }?.let { return File(it) }
        if (!item.extra.has("audio")) return null
        val video = File(item.filePath)
        return File(video.parentFile ?: return null, ".${video.name}.sound")
    }

    /** The sound track couldn't be joined with the picture: it stays next to it, as a file of its own. */
    private fun keepApart(item: Item, sound: File) {
        revealSound(item, sound)
        addNote(item, "Its sound is in a separate file next to it")
    }

    /** What the row of a finished download says about it besides its size and date. */
    private fun addNote(item: Item, text: String) {
        val before = item.extra.optString("note")
        item.extra.put("note", if (before.isEmpty()) text else "$before · ${text.replaceFirstChar { it.lowercase() }}")
    }

    /** A hidden sound track gets a name people can see: "Film (sound).aac". */
    private fun revealSound(item: Item, sound: File) {
        if (!sound.isFile || !sound.name.startsWith(".")) return
        val head = ByteArray(400)
        val n = runCatching { sound.inputStream().use { it.read(head) } }.getOrDefault(0)
        val ext = Hls.soundExtension(head.copyOf(n.coerceAtLeast(0)), item.extra.optBoolean("afmp4"))
        val base = File(item.filePath).name.substringBeforeLast('.')
        val dir = sound.parentFile ?: return
        val shown = synchronized(items) { uniqueFile(dir, "$base (sound).$ext") }
        if (sound.renameTo(shown)) item.extra.put("soundFile", shown.path)
    }

    // ------------------------------------------------------------------ 3.0: cookies and sign-ins

    /** [item]'s cookie and sign-in aren't needed any more (finished, or failed for good). */
    private fun forgetHeadersOf(item: Item) {
        if (item.headers.isEmpty()) return
        item.headers = emptyMap()
        if (!item.incognito) scope.launch { db.clearDownloadHeaders(listOf(item.id)) }
    }

    /**
     * 3.0: the sites' cookies were cleared: no download keeps a copy of them (or of a sign-in) any
     * more. Those in progress keep theirs until they end; every other entry (finished, failed,
     * paused) lets go of them.
     */
    fun forgetHeaders() {
        if (!::db.isInitialized) return
        val idle = synchronized(items) { items.values.filter { it.status != RUNNING && it.status != QUEUED && it.status != CONVERTING } }
        idle.forEach { it.headers = emptyMap() }
        val ids = idle.filter { !it.incognito }.map { it.id }
        scope.launch { db.clearDownloadHeaders(ids) }
    }

    /** The last incognito tab closed (2.6): its finished downloads leave the list (their files stay). */
    fun onIncognitoEnded() {
        val gone = synchronized(items) { items.values.filter { it.incognito && it.status != RUNNING && it.status != QUEUED && it.status != CONVERTING } }
        if (gone.isEmpty()) return
        synchronized(items) { gone.forEach { items.remove(it.id) } }
        changed(immediate = true)
    }

    private fun persist(item: Item) {
        if (item.incognito) return
        val snapshot = arrayOf<Any?>(item.filePath, item.fileName, item.total, item.done, item.status, item.error, item.extra.toString())
        scope.launch {
            db.updateDownload(
                item.id, snapshot[0] as String, snapshot[1] as String, snapshot[2] as Long, snapshot[3] as Long,
                snapshot[4] as Int, snapshot[5] as String?, snapshot[6] as String,
            )
        }
    }

    // ------------------------------------------------------------------ networking

    /** A request with the page's [headers] (Referer, Cookie, Origin, a sign-in), whatever their case. */
    private fun builder(url: String, headers: Map<String, String>, range: String?): WebRequest.Builder {
        val b = WebRequest.Builder(url).method("GET").cacheMode(WebRequest.CACHE_MODE_NO_STORE)
        fun h(name: String) = headers[name] ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        h("referer")?.let { b.referrer(it) }
        h("cookie")?.let { b.addHeader("Cookie", it) }
        h("origin")?.let { b.addHeader("Origin", it) }
        h("authorization")?.let { b.addHeader("Authorization", it) }
        if (range != null) b.addHeader("Range", range)
        return b
    }

    private fun flags(private: Boolean) = if (private) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE

    /** Blocking (the video sheet's look at a stream); downloads' own requests are [fetch]'s. */
    fun request(url: String, headers: Map<String, String>, range: String? = null, private: Boolean = false): WebResponse {
        val response = try {
            Engine.executor.fetch(builder(url, headers, range).build(), flags(private)).poll(45_000)
        } catch (e: Throwable) {
            throw IOException("Network error: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return response ?: throw IOException("No response")
    }

    /** 3.0: a download's request, which Pause and Cancel stop at once (see GeckoFetch). */
    private suspend fun fetch(item: Item, url: String, range: String? = null, ifRange: String? = null): WebResponse {
        val b = builder(url, item.headers, range)
        if (ifRange != null) b.addHeader("If-Range", ifRange)
        return GeckoFetch.fetch(b.build(), flags(item.incognito), 45_000)
    }

    /**
     * All of [input] (3.0: [expected] bytes when the site said how many: a part cut short is fetched
     * again rather than saved short).
     */
    private fun readAll(input: InputStream, limit: Int = 64 shl 20, expected: Long = -1): ByteArray {
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IOException("Response too large")
            }
            if (expected > 0 && out.size() < expected) throw IOException("Connection closed early")
            return out.toByteArray()
        }
    }

    /** Fetches a playlist as text (blocking; call off the main thread). */
    fun fetchText(url: String, headers: Map<String, String>, private: Boolean = false): String {
        val r = request(url, headers, private = private)
        if (r.statusCode !in 200..299) {
            runCatching { r.body?.close() }
            throw IOException("Server error ${r.statusCode}")
        }
        return String(r.body?.let { readAll(it, 8 shl 20) } ?: ByteArray(0), Charsets.UTF_8)
    }

    /** A download's playlist, as text. */
    private suspend fun fetchText(item: Item, run: Run, url: String): String {
        val r = fetch(item, url)
        if (r.statusCode !in 200..299) {
            GeckoFetch.close(r)
            throw DownloadRules.failure(r.statusCode)
        }
        val body = r.body ?: return ""
        return String(run.using(body) { readAll(body, 8 shl 20) }, Charsets.UTF_8)
    }

    /** The Content-Length a body will really have (the engine unpacks compressed answers), or -1. */
    private fun plainLength(r: WebResponse): Long {
        val encoding = r.headers["Content-Encoding"]
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return -1
        return r.headers["Content-Length"]?.toLongOrNull() ?: -1
    }

    // ------------------------------------------------------------------ plain files

    private suspend fun runFile(item: Item, run: Run) = coroutineScope {
        val file = File(item.filePath)
        file.parentFile?.mkdirs()
        var offset = if (file.exists()) file.length() else 0L
        val first = firstResponses.remove(item.id)
        var response: WebResponse
        if (first != null && offset == 0L) {
            response = first
        } else {
            first?.let { GeckoFetch.close(it) }
            // 3.0: with If-Range, a file that changed on the site comes whole, never mixed in.
            val since = if (offset > 0) DownloadRules.ifRange(item.extra.optString("etag"), item.extra.optString("modified")) else null
            response = fetch(item, item.url, if (offset > 0) "bytes=$offset-" else null, since)
            when (DownloadRules.resume(offset, response.statusCode, response.headers["Content-Range"], item.total)) {
                DownloadRules.Resume.APPEND -> Unit
                DownloadRules.Resume.RESTART -> {
                    if (offset > 0) L.d("Download ${item.id}: the site sent the file again from its start")
                    offset = 0
                    // A part that didn't start where it stopped: the whole file instead.
                    if (response.statusCode == 206 || response.statusCode == 416) {
                        GeckoFetch.close(response)
                        response = fetch(item, item.url)
                        if (response.statusCode !in 200..299) {
                            GeckoFetch.close(response)
                            throw DownloadRules.failure(response.statusCode)
                        }
                    }
                }
                DownloadRules.Resume.DONE -> {
                    GeckoFetch.close(response)
                    item.done = offset
                    return@coroutineScope
                }
                DownloadRules.Resume.FAIL -> {
                    GeckoFetch.close(response)
                    throw DownloadRules.failure(response.statusCode)
                }
            }
        }
        if (offset == 0L) {
            // What the next resume asks with: the file as it is now.
            response.headers["ETag"]?.let { item.extra.put("etag", it) } ?: item.extra.remove("etag")
            response.headers["Last-Modified"]?.let { item.extra.put("modified", it) } ?: item.extra.remove("modified")
        }
        // What is really in the file from here on (2.6): a server that ignored the range starts it
        // over, and after Android closed Swift the saved count may lag behind the file.
        item.done = offset
        val contentRangeTotal = DownloadRules.rangeTotal(response.headers["Content-Range"])
        val length = plainLength(response).takeIf { it >= 0 }
        item.total = contentRangeTotal ?: length?.let { it + offset } ?: item.total
        // 3.0: its size is known now, so "Big downloads wait for Wi-Fi" is decided again.
        if (mustWait(item)) {
            GeckoFetch.close(response)
            throw WaitForWifi()
        }
        val body = response.body ?: throw IOException("Empty response")
        response.setReadTimeoutMillis(60_000)
        run.using(body) {
            // 3.0: a stopped run never cuts the file back (a new run may be writing it).
            ensureActive()
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var lastPersist = System.currentTimeMillis()
                var speedMark = lastPersist
                var speedBytes = 0L
                body.use { input ->
                    while (isActive) {
                        val n = try {
                            input.read(buf)
                        } catch (e: IOException) {
                            if (!isActive) break
                            throw e
                        }
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        item.done += n
                        speedBytes += n
                        val now = System.currentTimeMillis()
                        if (now - speedMark >= 1000) {
                            item.bytesPerSecond = speedBytes * 1000 / (now - speedMark)
                            speedMark = now
                            speedBytes = 0
                        }
                        if (now - lastPersist > 3000) {
                            persist(item)
                            lastPersist = now
                            // 3.0: a file of unknown size that is clearly big waits for Wi-Fi too.
                            if (item.total <= 0 && mustWait(item)) throw WaitForWifi()
                        }
                        changed()
                    }
                }
            }
        }
        ensureActive()
        if (item.total > 0 && item.done < item.total) throw IOException("Connection closed early")
    }

    // ------------------------------------------------------------------ HLS streams

    private suspend fun runHls(item: Item, run: Run) = coroutineScope {
        val extra = item.extra
        val file = File(item.filePath)
        // 3.0: "Keep what was downloaded", or everything is saved already (Swift was closed while
        // converting it): on to the end without asking the site again (its link may have expired).
        val saved = DownloadRules.streamSaved(
            extra.optInt("seg", 0), extra.optInt("vsegTotal", extra.optInt("segTotal", 0)),
            extra.optInt("aseg", 0), extra.optInt("asegTotal", 0), file.length(), extra.optLong("bytes", 0),
        )
        if (saved || extra.optBoolean("keep")) {
            // What a part cut short added after the last part counted goes.
            truncate(file, extra.optLong("bytes", 0))
            soundFile(item)?.let { s -> if (extra.has("abytes")) truncate(s, extra.optLong("abytes", 0)) }
            return@coroutineScope
        }
        var text = fetchText(item, run, item.url)
        var playlistUrl = item.url
        if (!Hls.isPlaylist(text)) throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.NOT_A_VIDEO)
        if (Hls.isMaster(text)) {
            val best = Hls.parseMaster(text, item.url).firstOrNull() ?: throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.NOTHING)
            playlistUrl = best.url
            // 3.0: a quality whose sound comes separately: that is saved too.
            if (!extra.has("audio")) best.audio?.let { extra.put("audio", it) }
            text = fetchText(item, run, playlistUrl)
        }
        val media = Hls.parseMedia(text, playlistUrl)
        check(media)
        val audioUrl = extra.optString("audio").takeIf { it.isNotEmpty() }
        val audio = audioUrl?.let { url -> Hls.parseMedia(fetchText(item, run, url), url).also { check(it) } }

        if (media.fragmentedMp4 && !item.fileName.endsWith(".mp4")) {
            // fMP4 segments joined after their init segment already form an MP4 file.
            val f = uniqueFile(File(item.filePath).parentFile ?: directory(), item.fileName.removeSuffix(".ts") + ".mp4")
            if (extra.optInt("seg", 0) == 0) {
                File(item.filePath).delete()
                item.filePath = f.path
                item.fileName = f.name
            }
        }
        val videoParts = media.segments.size
        val soundParts = audio?.segments?.size ?: 0
        extra.put("vsegTotal", videoParts)
        extra.put("asegTotal", soundParts)
        extra.put("segTotal", videoParts + soundParts)
        item.segTotal = videoParts + soundParts
        extra.put("fmp4", media.fragmentedMp4)
        extra.put("duration", media.duration)
        if (audio != null) extra.put("afmp4", audio.fragmentedMp4)
        persist(item)
        // 3.0: the sound first (it is small), so a video stopped part-way still has all its sound.
        val sound = soundFile(item)
        if (audio != null && sound != null) fetchTrack(item, run, audio, sound, "aseg", "abytes", 0, 0L, packed = true)
        fetchTrack(item, run, media, File(item.filePath), "seg", "bytes", soundParts, extra.optLong("abytes", 0), packed = false)
        persist(item)
    }

    /** A playlist that can be saved: not empty, not live, no copy protection. */
    private fun check(media: Hls.Media) {
        if (media.segments.isEmpty()) throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.NOTHING)
        if (!media.endList) throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.LIVE)
        if (media.unsupportedKey) throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.PROTECTED)
    }

    private fun truncate(f: File, bytes: Long) {
        if (bytes > 0 && f.isFile && f.length() > bytes) runCatching { RandomAccessFile(f, "rw").use { it.setLength(bytes) } }
    }

    /**
     * The parts of [media] into [file], four fetched at a time, where it stopped last time ([segKey]
     * and [bytesKey] keep that). [before] parts and [bytesBefore] bytes of the download are another
     * track's (the sound, saved first). [packed]: a sound track's parts may be packed audio, whose
     * tags in between come out ([Hls.stripId3]).
     */
    private suspend fun fetchTrack(
        item: Item, run: Run, media: Hls.Media, file: File, segKey: String, bytesKey: String, before: Int, bytesBefore: Long, packed: Boolean,
    ) = coroutineScope {
        val extra = item.extra
        val startSeg = extra.optInt(segKey, 0).coerceIn(0, media.segments.size)
        val startBytes = extra.optLong(bytesKey, 0)
        val keys = ConcurrentHashMap<String, ByteArray>()
        file.parentFile?.mkdirs()
        ensureActive()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(if (startSeg == 0) 0 else startBytes)
            raf.seek(raf.length())
            if (startSeg == 0 && media.init != null) {
                raf.write(fetchSegment(item, run, media.init))
            }
            item.done = bytesBefore + raf.length()
            val pending = ArrayDeque<Deferred<ByteArray>>()
            var next = startSeg
            fun enqueue() {
                val seg = media.segments[next++]
                pending.addLast(async { fetchSegment(item, run, seg) })
            }
            while (pending.size < HLS_PARALLEL && next < media.segments.size) enqueue()
            var index = startSeg
            var speedMark = System.currentTimeMillis()
            var speedBytes = 0L
            var secs = if (packed) 0.0 else media.segments.take(startSeg).sumOf { it.duration }
            val continuity = IntArray(8192) { -1 }
            while (pending.isNotEmpty()) {
                val data = pending.removeFirst().await()
                val seg = media.segments[index]
                var plain = if (seg.key != null) decrypt(item, run, data, seg, keys) else data
                if (!media.fragmentedMp4 && Hls.isTransportStream(plain)) {
                    Hls.renumberContinuity(plain, continuity)
                } else if (packed && !media.fragmentedMp4) {
                    plain = Hls.stripId3(plain)
                }
                ensureActive()
                raf.write(plain)
                index++
                item.done = bytesBefore + raf.filePointer
                speedBytes += plain.size
                item.segDone = before + index
                extra.put(segKey, index)
                extra.put(bytesKey, raf.filePointer)
                if (!packed) {
                    secs += seg.duration
                    extra.put("secs", secs)
                }
                val now = System.currentTimeMillis()
                if (now - speedMark >= 1000) {
                    item.bytesPerSecond = speedBytes * 1000 / (now - speedMark)
                    speedMark = now
                    speedBytes = 0
                }
                if (index % 10 == 0) persist(item)
                changed()
                if (next < media.segments.size) enqueue()
            }
        }
    }

    private suspend fun fetchSegment(item: Item, run: Run, seg: Hls.Segment): ByteArray {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val range = if (seg.rangeLength >= 0) "bytes=${seg.rangeStart}-${seg.rangeStart + seg.rangeLength - 1}" else null
                val r = fetch(item, seg.url, range)
                if (r.statusCode !in 200..299) {
                    GeckoFetch.close(r)
                    throw DownloadRules.failure(r.statusCode)
                }
                r.setReadTimeoutMillis(30_000)
                val body = r.body ?: return ByteArray(0)
                // 3.0: a stop closes it, so a part being read doesn't hold the run up.
                return run.using(body) { readAll(body, expected = plainLength(r)) }
            } catch (e: IOException) {
                if (e is DownloadRules.Failure && e.why == Why.PERMANENT) throw e
                currentCoroutineContext().ensureActive()
                attempt++
                if (attempt >= 4) throw e
                delay(1000L * attempt)
            }
        }
    }

    private suspend fun decrypt(
        item: Item, run: Run, data: ByteArray, seg: Hls.Segment, keys: ConcurrentHashMap<String, ByteArray>,
    ): ByteArray {
        val key = seg.key ?: return data
        val uri = key.uri ?: throw IOException("Missing key")
        // 3.0: through the incognito session too, like the parts (the key's request was the normal one's).
        val keyBytes = keys[uri] ?: fetchSegment(item, run, Hls.Segment(uri, 0.0, null, 0, -1, -1)).also {
            if (it.size != 16) throw IOException("Invalid key")
            keys[uri] = it
        }
        val iv = key.iv ?: Hls.sequenceIv(seg.sequence)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun parseHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
