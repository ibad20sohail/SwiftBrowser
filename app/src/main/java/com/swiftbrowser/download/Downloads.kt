package com.swiftbrowser.download

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.swiftbrowser.core.Db
import com.swiftbrowser.core.DownloadRow
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayOutputStream
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

    private const val MAX_PARALLEL = 2

    /** Shown on a download that waits for Wi-Fi (see Prefs.wifiOnlyDownloads). */
    const val WAITING_FOR_WIFI = "Waiting for Wi-Fi"
    private const val BIG = 50L * 1024 * 1024
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
        val headers: Map<String, String>,
        val pageUrl: String?,
        @Volatile var extra: JSONObject,
        val created: Long,
        /** Started in an incognito tab (2.6): only in memory, gone from the list when incognito ends. */
        val incognito: Boolean = false,
    ) {
        @Volatile var segDone: Int = extra.optInt("seg", 0)
        @Volatile var segTotal: Int = extra.optInt("segTotal", 0)

        /** 0..100, or -1 when unknown. */
        val percent: Int
            get() {
                if (status == DONE) return 100
                if (kind == KIND_HLS) return if (segTotal > 0) segDone * 100 / segTotal else -1
                return if (total > 0) (done * 100 / total).toInt() else -1
            }

        @Volatile var bytesPerSecond: Long = 0
    }

    private lateinit var app: Context
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private val items = LinkedHashMap<Long, Item>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val streams = ConcurrentHashMap<Long, InputStream>()
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
            items[item.id] = item
        }
        com.swiftbrowser.engine.DataSaver.networkListeners += { onNetworkChanged() }
        com.swiftbrowser.engine.DataSaver.onlineListeners += { retryFailed() }
        // A moment after starting, so the app comes up first.
        main.postDelayed({ schedule() }, 4000)
    }

    fun all(): List<Item> = synchronized(items) { items.values.reversed() }

    /** Clearing browsing data (2.6): entries started since [since] leave the list; the files stay, running ones go on. */
    fun removeEntriesSince(since: Long) {
        val gone = synchronized(items) { items.values.filter { it.created >= since && it.status != RUNNING && it.status != QUEUED && it.status != CONVERTING } }
        for (item in gone) remove(item.id, deleteFile = false)
    }

    fun get(id: Long): Item? = synchronized(items) { items[id] }

    fun activeCount(): Int = synchronized(items) {
        items.values.count { it.status == RUNNING || it.status == QUEUED || it.status == CONVERTING }
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
        val file = synchronized(items) { uniqueFile(directory(), UrlUtils.sanitizeFileName(fileName)) }
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
     */
    fun saveFile(
        profileId: Long, fileName: String, mime: String, pageUrl: String?, write: (File) -> Unit, done: (Item?) -> Unit,
    ) {
        val item = create(profileId, KIND_FILE, pageUrl ?: "", fileName, mime, -1, emptyMap(), pageUrl, JSONObject().put("local", true))
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

    /** [playlistUrl] must be a media playlist (a chosen quality), not a master playlist. */
    fun startHls(profileId: Long, playlistUrl: String, title: String, headers: Map<String, String>, pageUrl: String?, incognito: Boolean = false): Item {
        val name = UrlUtils.sanitizeFileName(title).removeSuffix(".mp4").removeSuffix(".ts") + ".ts"
        val item = create(profileId, KIND_HLS, playlistUrl, name, "video/mp2t", -1, headers, pageUrl, JSONObject(), incognito)
        schedule()
        return item
    }

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
        persist(item)
        changed(immediate = true)
        schedule()
    }

    /** Removes the entry; also deletes the file unless it finished and [keepFile] is set. */
    fun remove(id: Long, deleteFile: Boolean) {
        val item = get(id) ?: return
        stopJob(id)
        firstResponses.remove(id)?.body?.let { runCatching { it.close() } }
        synchronized(items) { items.remove(id) }
        scope.launch {
            if (!item.incognito) db.deleteDownload(id)
            if (deleteFile || item.status != DONE) File(item.filePath).delete()
        }
        changed(immediate = true)
        schedule()
    }

    private fun stopJob(id: Long) {
        jobs.remove(id)?.cancel()
        streams.remove(id)?.let { runCatching { it.close() } }
    }

    /** On mobile data with "Big downloads wait for Wi-Fi": videos and files over 50 MB wait. */
    private fun mustWait(item: Item): Boolean =
        prefs.wifiOnlyDownloads && com.swiftbrowser.engine.DataSaver.onMobileData &&
            (item.kind == KIND_HLS || item.total > BIG || (item.total <= 0 && item.mime?.startsWith("video/") == true))

    /**
     * The connection is back: downloads that failed for lack of it try again, a few times at most
     * (not those whose link expired or that the server refused).
     */
    private fun retryFailed() {
        var any = false
        synchronized(items) { items.values.toList() }.forEach { item ->
            if (item.status != FAILED || !networkError(item.error)) return@forEach
            val tries = item.extra.optInt("retries", 0)
            if (tries >= 3) return@forEach
            item.extra.put("retries", tries + 1)
            item.status = QUEUED
            item.error = null
            persist(item)
            any = true
        }
        if (any) {
            changed(immediate = true)
            schedule()
        }
    }

    private fun networkError(error: String?): Boolean {
        val e = error?.lowercase() ?: return true
        return !e.contains("expired") && !e.contains("server error") && !e.contains("drm") && !e.contains("not a video")
    }

    /** The phone moved between Wi-Fi and mobile data, or the setting changed. */
    fun onNetworkChanged() {
        synchronized(items) { items.values.toList() }.forEach { item ->
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
            val running = items.values.count { it.status == RUNNING || it.status == CONVERTING }
            items.values.filter { it.status == QUEUED && !jobs.containsKey(it.id) }
                .filter { item ->
                    val wait = mustWait(item)
                    item.error = if (wait) WAITING_FOR_WIFI else item.error?.takeIf { it != WAITING_FOR_WIFI }
                    // Waiting: the page's open answer is let go; the file is asked for again on Wi-Fi.
                    if (wait) firstResponses.remove(item.id)?.body?.let { runCatching { it.close() } }
                    !wait
                }
                .sortedBy { it.created }
                .take((MAX_PARALLEL - running).coerceAtLeast(0))
        }
        for (item in toStart) launch(item)
        if (activeCount() > 0) startService()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            L.w("Could not start download service", e)
        }
    }

    private fun launch(item: Item) {
        item.status = RUNNING
        persist(item)
        changed(immediate = true)
        jobs[item.id] = scope.launch {
            try {
                if (item.kind == KIND_HLS) runHls(item) else runFile(item)
                ensureActive()
                finish(item)
            } catch (e: CancellationException) {
                // paused or removed
            } catch (e: Throwable) {
                if (item.status == RUNNING || item.status == CONVERTING) {
                    L.w("Download ${item.id} failed", e)
                    item.status = FAILED
                    item.error = e.message ?: e.javaClass.simpleName
                    persist(item)
                }
            } finally {
                jobs.remove(item.id)
                streams.remove(item.id)
                item.bytesPerSecond = 0
                changed(immediate = true)
                main.post { schedule() }
            }
        }
    }

    private suspend fun finish(item: Item) {
        if (item.kind == KIND_HLS && !item.extra.optBoolean("fmp4") && prefs.hlsToMp4) {
            item.status = CONVERTING
            persist(item)
            changed(immediate = true)
            val ts = File(item.filePath)
            val mp4 = uniqueFile(ts.parentFile ?: directory(), ts.name.removeSuffix(".ts") + ".mp4")
            val job = jobs[item.id]
            val ok = Remuxer.tsToMp4(ts, mp4) { job?.isActive == false }
            if (ok) {
                ts.delete()
                item.filePath = mp4.path
                item.fileName = mp4.name
            } else {
                mp4.delete()
                L.d("Kept .ts file for download ${item.id}")
            }
        }
        val f = File(item.filePath)
        item.done = f.length()
        item.total = item.done
        item.status = DONE
        persist(item)
        MediaScannerConnection.scanFile(app, arrayOf(item.filePath), null, null)
        main.post { DownloadService.notifyFinished(app, item) }
    }

    /**
     * Finished downloads still in Swift's own folders (from before storage access, or Swift's SD card
     * folder) move to [dir], so they stay if Swift is uninstalled (2.5). Downloads in progress stay.
     * [onDone] hears on the main thread how many moved and how many couldn't.
     */
    fun moveKeptTo(dir: File, onDone: (moved: Int, failed: Int) -> Unit) {
        scope.launch {
            var moved = 0
            var failed = 0
            val busy = synchronized(items) { items.values.filter { it.status != DONE }.map { it.filePath }.toSet() }
            for (f in com.swiftbrowser.core.Storage.keptDownloads(app)) {
                if (f.path in busy || f.path.startsWith(dir.path + "/")) continue
                val to = uniqueFile(dir, f.name)
                val ok = runCatching {
                    dir.mkdirs()
                    if (!f.renameTo(to)) {
                        // Another storage (Swift's data → the phone's shared storage): copied, then removed.
                        f.inputStream().use { i -> to.outputStream().use { o -> i.copyTo(o) } }
                        if (to.length() != f.length()) throw IOException("short copy")
                        f.delete()
                    }
                    true
                }.getOrElse { to.delete(); false }
                if (!ok) {
                    failed++
                    continue
                }
                moved++
                synchronized(items) { items.values.filter { it.filePath == f.path } }.forEach {
                    it.filePath = to.path
                    it.fileName = to.name
                    persist(it)
                }
                MediaScannerConnection.scanFile(app, arrayOf(to.path), null, null)
            }
            changed(immediate = true)
            main.post { onDone(moved, failed) }
        }
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

    fun request(url: String, headers: Map<String, String>, range: String? = null, private: Boolean = false): WebResponse {
        val b = WebRequest.Builder(url).method("GET").cacheMode(WebRequest.CACHE_MODE_NO_STORE)
        headers["referer"]?.let { b.referrer(it) }
        headers["cookie"]?.let { b.addHeader("Cookie", it) }
        headers["origin"]?.let { b.addHeader("Origin", it) }
        headers["authorization"]?.let { b.addHeader("Authorization", it) }
        if (range != null) b.addHeader("Range", range)
        val response = try {
            Engine.executor.fetch(b.build(), if (private) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE).poll(45_000)
        } catch (e: Throwable) {
            throw IOException("Network error: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return response ?: throw IOException("No response")
    }

    private fun readAll(response: WebResponse, limit: Int = 64 shl 20): ByteArray {
        val body = response.body ?: return ByteArray(0)
        body.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IOException("Response too large")
            }
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
        return String(readAll(r, 8 shl 20), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ plain files

    private suspend fun runFile(item: Item) = coroutineScope {
        val file = File(item.filePath)
        file.parentFile?.mkdirs()
        var offset = if (file.exists()) file.length() else 0L
        val first = firstResponses.remove(item.id)
        val response: WebResponse
        if (first != null && offset == 0L) {
            response = first
        } else {
            first?.body?.let { runCatching { it.close() } }
            response = request(item.url, item.headers, if (offset > 0) "bytes=$offset-" else null, item.incognito)
            when {
                response.statusCode == 206 -> Unit
                response.statusCode in 200..299 -> offset = 0 // server ignored the range: start over
                response.statusCode == 416 && item.total > 0 && offset >= item.total -> {
                    runCatching { response.body?.close() }
                    item.done = offset
                    return@coroutineScope
                }
                else -> {
                    runCatching { response.body?.close() }
                    throw IOException(
                        if (response.statusCode == 403 || response.statusCode == 401) {
                            "The link expired (${response.statusCode}). Start the download again from the page."
                        } else {
                            "Server error ${response.statusCode}"
                        },
                    )
                }
            }
        }
        // What is really in the file from here on (2.6): a server that ignored the range starts it
        // over, and after Android closed Swift the saved count may lag behind the file.
        item.done = offset
        val contentRangeTotal = response.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
        val length = response.headers["Content-Length"]?.toLongOrNull()
        item.total = contentRangeTotal ?: length?.let { it + offset } ?: item.total
        val body = response.body ?: throw IOException("Empty response")
        response.setReadTimeoutMillis(60_000)
        streams[item.id] = body
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
                    }
                    changed()
                }
            }
        }
        ensureActive()
        if (item.total > 0 && item.done < item.total) throw IOException("Connection closed early")
    }

    // ------------------------------------------------------------------ HLS streams

    private suspend fun runHls(item: Item) = coroutineScope {
        var text = fetchText(item.url, item.headers, item.incognito)
        var playlistUrl = item.url
        if (!Hls.isPlaylist(text)) throw IOException("Not a video playlist")
        if (Hls.isMaster(text)) {
            val best = Hls.parseMaster(text, item.url).firstOrNull() ?: throw IOException("No streams in playlist")
            playlistUrl = best.url
            text = fetchText(playlistUrl, item.headers, item.incognito)
        }
        val media = Hls.parseMedia(text, playlistUrl)
        if (media.segments.isEmpty()) throw IOException("Empty playlist")
        if (!media.endList) throw IOException("This is a live stream, it can't be downloaded")
        if (media.unsupportedKey) throw IOException("This video is copy-protected and can't be saved")

        val extra = item.extra
        if (media.fragmentedMp4 && !item.fileName.endsWith(".mp4")) {
            // fMP4 segments joined after their init segment already form an MP4 file.
            val f = uniqueFile(File(item.filePath).parentFile ?: directory(), item.fileName.removeSuffix(".ts") + ".mp4")
            if (extra.optInt("seg", 0) == 0) {
                File(item.filePath).delete()
                item.filePath = f.path
                item.fileName = f.name
            }
        }
        extra.put("segTotal", media.segments.size)
        item.segTotal = media.segments.size
        extra.put("fmp4", media.fragmentedMp4)
        extra.put("duration", media.duration)
        val startSeg = extra.optInt("seg", 0).coerceIn(0, media.segments.size)
        val startBytes = extra.optLong("bytes", 0)
        persist(item)

        val keys = ConcurrentHashMap<String, ByteArray>()
        val file = File(item.filePath)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(if (startSeg == 0) 0 else startBytes)
            raf.seek(raf.length())
            if (startSeg == 0 && media.init != null) {
                raf.write(fetchSegment(media.init, item.headers, item.incognito))
            }
            item.done = raf.length()
            val pending = ArrayDeque<Deferred<ByteArray>>()
            var next = startSeg
            fun enqueue() {
                val seg = media.segments[next++]
                pending.addLast(async { fetchSegment(seg, item.headers, item.incognito) })
            }
            while (pending.size < HLS_PARALLEL && next < media.segments.size) enqueue()
            var index = startSeg
            var speedMark = System.currentTimeMillis()
            var speedBytes = 0L
            val continuity = IntArray(8192) { -1 }
            while (pending.isNotEmpty()) {
                val data = pending.removeFirst().await()
                val seg = media.segments[index]
                val plain = if (seg.key != null) decrypt(data, seg, keys, item.headers) else data
                if (!media.fragmentedMp4 && Hls.isTransportStream(plain)) Hls.renumberContinuity(plain, continuity)
                ensureActive()
                raf.write(plain)
                index++
                item.done = raf.filePointer
                speedBytes += plain.size
                item.segDone = index
                extra.put("seg", index)
                extra.put("bytes", raf.filePointer)
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
        persist(item)
    }

    private suspend fun fetchSegment(seg: Hls.Segment, headers: Map<String, String>, private: Boolean = false): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val range = if (seg.rangeLength >= 0) "bytes=${seg.rangeStart}-${seg.rangeStart + seg.rangeLength - 1}" else null
                val r = request(seg.url, headers, range, private)
                if (r.statusCode !in 200..299) {
                    runCatching { r.body?.close() }
                    throw IOException("Segment error ${r.statusCode}")
                }
                r.setReadTimeoutMillis(30_000)
                return readAll(r)
            } catch (e: IOException) {
                attempt++
                if (attempt >= 4) throw e
                delay(1000L * attempt)
            }
        }
    }

    private suspend fun decrypt(
        data: ByteArray, seg: Hls.Segment, keys: ConcurrentHashMap<String, ByteArray>, headers: Map<String, String>,
    ): ByteArray {
        val key = seg.key ?: return data
        val uri = key.uri ?: throw IOException("Missing key")
        val keyBytes = keys[uri] ?: fetchSegment(Hls.Segment(uri, 0.0, null, 0, -1, -1), headers).also {
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
