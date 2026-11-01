package com.swiftbrowser.torrent

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import com.swiftbrowser.core.L
import com.swiftbrowser.download.DownloadService
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.DataSaver
import com.swiftbrowser.engine.Power
import com.swiftbrowser.vpn.Vpn
import org.json.JSONObject
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.info_hash_t
import org.libtorrent4j.swig.libtorrent
import java.io.File
import java.net.URLEncoder

/**
 * Torrents (2.9.2), with libtorrent. They are downloads like the others, rows of [Downloads] (kind
 * [Downloads.KIND_TORRENT]) with the same statuses; this object runs the ones that may run.
 *
 * - Each torrent's metadata (<hash>.torrent) and libtorrent's resume data (<hash>.resume) are kept
 *   in Swift's own torrents folder, so torrents go on after Swift or the phone restarts. The files
 *   themselves go to the download folder's Torrents folder.
 * - libtorrent runs only while a torrent does, and only the torrents that may run are in it: a
 *   paused or waiting one is taken out once its resume data is saved.
 * - They never run while Swift's VPN is on or connecting (Proton's free servers refuse torrents),
 *   wait for Wi-Fi if chosen, pause below 15% battery unless charging and when storage runs out,
 *   and [TorrentSettings.atOnce] run at a time.
 * - Sharing stops the moment the chosen files are complete.
 *
 * Everything here runs on one thread of its own ([worker]); libtorrent's alerts come on another and
 * are handed over to it.
 */
object Torrents {
    const val PAUSED_VPN = "Paused while Swift's VPN is on"
    const val WAITING_WIFI = "Waiting for Wi-Fi"
    const val PAUSED_BATTERY = "Paused: battery low"
    const val WAITING_CHARGER = "Waiting for the charger"
    const val WAITING_NIGHT = "Waiting for the night (1 am to 7 am)"
    const val PAUSED_STORAGE = "Paused: storage almost full"
    const val WAITING_TURN = "Waiting for another torrent to finish"
    const val GETTING_FILES = "Getting the file list…"
    const val CHECKING = "Checking the files…"

    private const val MIN_FREE = 500L * 1024 * 1024
    private const val SPARE = 100L * 1024 * 1024
    private const val BATTERY_LOW = 15
    private const val BATTERY_OK = 20
    private const val POLL_MS = 1000L
    private const val SAVE_EVERY_MS = 60_000L
    private const val STORAGE_EVERY_MS = 15_000L
    private const val IDLE_STOP_MS = 30_000L
    private const val FETCH_SECONDS = 60
    private const val RECHECK_MS = 60_000L

    private lateinit var app: Context
    private val worker by lazy { Handler(HandlerThread("torrents").apply { start() }.looper) }
    private val main = Handler(Looper.getMainLooper())

    private var session: SessionManager? = null

    /** The torrents in libtorrent, by info hash: their row, and their handle once added. */
    private val active = HashMap<String, Long>()
    private val handles = HashMap<String, TorrentHandle>()

    /** In libtorrent but leaving: what happens once their resume data is saved. */
    private val leaving = HashMap<String, Leave>()
    private enum class Leave { STOP, FINISHED }

    /** File lists being fetched for the add panel (libtorrent stays up meanwhile). */
    private var fetching = 0
    private var batteryBlocked = false
    private var lastSave = 0L
    private var lastStorage = 0L
    private var polling = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Vpn.addListener { reschedule() }
        Power.batteryListeners += { reschedule() }
    }

    private fun dir(): File = File(app.filesDir, "torrents").apply { mkdirs() }
    private fun torrentFile(hash: String) = File(dir(), "$hash.torrent")
    private fun resumeFile(hash: String) = File(dir(), "$hash.resume")

    /** Where torrents save their files: the Torrents folder of the download folder. */
    fun saveDir(): File = File(Downloads.directory(), "Torrents")

    fun hashOf(item: Downloads.Item): String? = item.extra.optString("hash").takeIf { it.isNotEmpty() }

    private fun keyOf(h: info_hash_t): String = if (h.has_v1()) h.getV1().to_hex() else h.get_best().to_hex()

    /** The key of a torrent's metadata, as rows keep it. */
    fun hashOf(ti: TorrentInfo): String = keyOf(ti.swig().info_hashes())

    /** Torrents may not run now: why (shown on their rows), or null. */
    fun blockReason(): String? {
        if (Vpn.wanted || Vpn.state != Vpn.State.OFF) return PAUSED_VPN
        if (TorrentSettings.wifiOnly && DataSaver.onMobileData) return WAITING_WIFI
        if (TorrentSettings.chargingOnly && !Power.charging) return WAITING_CHARGER
        if (TorrentSettings.nightOnly && !TorrentSettings.isNight()) return WAITING_NIGHT
        batteryBlocked = when {
            !TorrentSettings.batteryPause || Power.charging || Power.percent < 0 -> false
            batteryBlocked -> Power.percent < BATTERY_OK
            else -> Power.percent < BATTERY_LOW
        }
        return if (batteryBlocked) PAUSED_BATTERY else null
    }

    // ------------------------------------------------------------------ adding

    /**
     * Adds a torrent to Downloads: from its metadata [data] (a .torrent file, or one fetched for a
     * magnet link) with the files chosen in [wanted] (by index; null: all but the junk), or from a
     * [magnet] link alone (its files are chosen once known: all but the junk). Returns the row, or
     * the one already there for the same torrent.
     */
    fun add(magnet: String?, data: ByteArray?, wanted: Set<Int>?): Downloads.Item? {
        val folder = saveDir()
        if (data != null) {
            val ti = try {
                TorrentInfo.bdecode(data)
            } catch (e: Throwable) {
                L.w("Not a torrent file", e)
                return null
            }
            val hash = hashOf(ti)
            Downloads.findTorrent(hash)?.let { return it }
            val entries = TorrentFiles.list(ti)
            val prio = priorities(ti, wanted ?: entries.filter { !it.junk }.map { it.index }.toSet())
            val total = entries.filter { prio[it.index] != Priority.IGNORE }.sumOf { it.size }
            val main = TorrentFiles.main(entries) { prio[it.index] != Priority.IGNORE }
            // A film's torrent goes with the other videos when "Sort downloads into folders" is on.
            val folder = main?.takeIf { it.video }?.let { Downloads.sortedFolderFor(it.name) } ?: folder
            val extra = JSONObject().put("hash", hash).put("dir", folder.path).put("prio", prioText(prio)).put("files", entries.size)
            main?.let { extra.put("main", it.path) }
            val link = magnet ?: ("magnet:?xt=urn:btih:$hash&dn=" + URLEncoder.encode(ti.name(), "UTF-8"))
            try {
                torrentFile(hash).writeBytes(data)
            } catch (e: Exception) {
                L.w("Couldn't keep the torrent file", e)
                return null
            }
            return Downloads.addTorrent(ti.name(), link, File(folder, ti.name()).path, total, extra)
        }
        magnet ?: return null
        val hash = TorrentFiles.magnetHash(magnet) ?: return null
        Downloads.findTorrent(hash)?.let { return it }
        val name = TorrentFiles.magnetName(magnet) ?: hash
        val extra = JSONObject().put("hash", hash).put("dir", folder.path)
        return Downloads.addTorrent(name, magnet, File(folder, name).path, -1, extra)
    }

    private fun priorities(ti: TorrentInfo, wanted: Set<Int>): Array<Priority> =
        Array(ti.numFiles()) { if (it in wanted) Priority.DEFAULT else Priority.IGNORE }

    private fun prioText(p: Array<Priority>) = p.joinToString("") { if (it == Priority.IGNORE) "0" else "1" }

    private fun prioFrom(text: String, count: Int): Array<Priority>? =
        if (text.length != count) null else Array(count) { if (text[it] == '0') Priority.IGNORE else Priority.DEFAULT }

    /** The metadata of [item]'s torrent, once known (blocking: a file read). */
    fun infoOf(item: Downloads.Item): TorrentInfo? {
        val hash = hashOf(item) ?: return null
        val f = torrentFile(hash)
        return if (f.exists()) runCatching { TorrentInfo(f) }.getOrNull() else null
    }

    /** The files chosen for [item] (by index), once its metadata is known. */
    fun wantedOf(item: Downloads.Item, ti: TorrentInfo): Set<Int> {
        val p = prioFrom(item.extra.optString("prio"), ti.numFiles()) ?: TorrentFiles.defaultPriorities(ti)
        return p.indices.filter { p[it] != Priority.IGNORE }.toSet()
    }

    /** Another choice of files for [item] (the Files item of its menu). A finished torrent gets the new ones. */
    fun setFiles(item: Downloads.Item, wanted: Set<Int>) = worker.post {
        val ti = infoOf(item) ?: return@post
        val hash = hashOf(item) ?: return@post
        val prio = priorities(ti, wanted)
        val entries = TorrentFiles.list(ti)
        item.extra.put("prio", prioText(prio))
        TorrentFiles.main(entries) { prio[it.index] != Priority.IGNORE }?.let { item.extra.put("main", it.path) }
        item.total = entries.filter { prio[it.index] != Priority.IGNORE }.sumOf { it.size }
        handles[hash]?.prioritizeFiles(prio)
        // The resume data still has the old choice: it would win at the next start.
        if (hash !in active) resumeFile(hash).delete()
        if (item.status == Downloads.DONE) {
            item.status = Downloads.QUEUED
            item.error = null
        }
        Downloads.torrentChanged(item, persist = true)
        Downloads.reschedule()
    }

    /**
     * The file list of [magnet] for the add panel: libtorrent asks the people sharing it (a few
     * seconds, up to a minute). [done] gets the .torrent's bytes on the main thread, or null.
     */
    fun fetchFiles(magnet: String, done: (ByteArray?) -> Unit) = worker.post {
        val s = ensureSession()
        if (s == null) {
            main.post { done(null) }
            return@post
        }
        fetching++
        Thread({
            val bytes = try {
                s.fetchMagnet(withTrackers(magnet), FETCH_SECONDS, File(app.cacheDir, "torrent-fetch").apply { mkdirs() })
            } catch (e: Throwable) {
                L.w("Couldn't fetch a torrent's files", e)
                null
            }
            worker.post {
                fetching--
                idleCheck()
            }
            main.post { done(bytes) }
        }, "torrent-fetch").start()
    }

    private fun withTrackers(magnet: String): String {
        if (!TorrentSettings.extraTrackers) return magnet
        return magnet + TorrentFiles.TRACKERS.filter { !magnet.contains(URLEncoder.encode(it, "UTF-8")) && !magnet.contains(it) }
            .joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
    }

    // ------------------------------------------------------------------ running

    /** The rows changed (added, paused, resumed) or the rules did: who runs is worked out again. */
    fun reschedule() {
        if (!::app.isInitialized) return
        worker.post { applyRules() }
    }

    /** New speed limits apply to what runs, a new number at once to who runs. */
    fun applySettings() {
        if (!::app.isInitialized) return
        worker.post {
            session?.applySettings(limits(SettingsPack()))
            applyRules()
        }
    }

    private fun applyRules() {
        val torrents = Downloads.all().filter { it.kind == Downloads.KIND_TORRENT }
        val block = blockReason()
        // Those running keep their place; then oldest first.
        val waiting = torrents.filter { it.status == Downloads.RUNNING || it.status == Downloads.QUEUED }
            .sortedWith(compareBy({ hashOf(it) !in active }, { it.created }))
        var slots = TorrentSettings.atOnce
        for (item in waiting) {
            val hash = hashOf(item) ?: continue
            val reason = block ?: storageBlock(item) ?: if (slots > 0) null else WAITING_TURN
            if (reason == null) {
                slots--
                if (hash !in active) start(item, hash)
            } else {
                if (hash in active) stop(hash, Leave.STOP)
                if (item.status != Downloads.QUEUED || item.error != reason) {
                    item.status = Downloads.QUEUED
                    item.error = reason
                    item.bytesPerSecond = 0
                    Downloads.torrentChanged(item, persist = true)
                }
            }
        }
        worker.removeCallbacks(recheck)
        if (waiting.any { it.status == Downloads.QUEUED && (it.error == WAITING_NIGHT || it.error == PAUSED_STORAGE) }) {
            worker.postDelayed(recheck, RECHECK_MS)
        }
        // Paused, failed or removed ones leave libtorrent.
        for ((hash, id) in active.toList()) {
            val item = Downloads.get(id)
            if (item == null || item.status == Downloads.PAUSED || item.status == Downloads.FAILED) {
                if (hash !in leaving) stop(hash, Leave.STOP)
            }
        }
        idleCheck()
    }

    private val recheck = Runnable { applyRules() }

    /** The file Play opens: a torrent's film (or its biggest file), once its file list is known. */
    fun mainFile(item: Downloads.Item): File? {
        val main = item.extra.optString("main").takeIf { it.isNotEmpty() } ?: return null
        return File(item.extra.optString("dir").ifEmpty { return null }, main)
    }

    /** Free space for what is left of [item], with some to spare; else why it waits. */
    private fun storageBlock(item: Downloads.Item): String? {
        val folder = File(item.extra.optString("dir").ifEmpty { saveDir().path })
        val free = try {
            folder.mkdirs()
            StatFs((if (folder.exists()) folder else folder.parentFile ?: return null).path).availableBytes
        } catch (_: Exception) {
            return null
        }
        if (free >= MIN_FREE) return null
        val left = if (item.total > 0) (item.total - item.done).coerceAtLeast(0) else Long.MAX_VALUE
        return if (left > 0 && left > free - SPARE) PAUSED_STORAGE else null
    }

    private fun start(item: Downloads.Item, hash: String) {
        val s = ensureSession()
        if (s == null) {
            fail(item, "Torrents can't run on this phone")
            return
        }
        val folder = File(item.extra.optString("dir").ifEmpty { saveDir().path }).apply { mkdirs() }
        var p: add_torrent_params? = null
        val resume = resumeFile(hash)
        if (resume.exists()) {
            val ec = error_code()
            p = try {
                libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(resume.readBytes()), ec).takeIf { ec.value() == 0 }
            } catch (e: Throwable) {
                L.w("Couldn't read a torrent's resume data", e)
                null
            }
        }
        if (p == null) {
            val tf = torrentFile(hash)
            if (tf.exists()) {
                val ti = try {
                    TorrentInfo(tf)
                } catch (e: Throwable) {
                    fail(item, "Its torrent file is damaged")
                    return
                }
                p = add_torrent_params()
                p.set_ti(ti.swig())
                val prio = prioFrom(item.extra.optString("prio"), ti.numFiles()) ?: TorrentFiles.defaultPriorities(ti)
                p.set_file_priorities(Vectors.bytes2byte_vector(ByteArray(prio.size) { prio[it].swig().toByte() }))
            } else {
                val ec = error_code()
                p = libtorrent.parse_magnet_uri(item.url, ec)
                if (ec.value() != 0) {
                    fail(item, "This magnet link isn't valid")
                    return
                }
            }
        }
        p.setSave_path(folder.absolutePath)
        // Swift decides what runs and when, so libtorrent's own queue is off; never "seed only".
        p.setFlags(
            p.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv())
                .and_(TorrentFlags.UPLOAD_MODE.inv()).and_(TorrentFlags.SEED_MODE.inv()),
        )
        if (TorrentSettings.extraTrackers) {
            val trackers = p.getTrackers()
            for (t in TorrentFiles.TRACKERS) if (t !in trackers) trackers.add(t)
            p.setTrackers(trackers)
        }
        active[hash] = item.id
        s.swig().async_add_torrent(p)
        item.status = Downloads.RUNNING
        item.error = if (torrentFile(hash).exists() || p.ti_ptr() != null) null else GETTING_FILES
        Downloads.torrentChanged(item, persist = true)
        startPolling()
    }

    /** Takes [hash] out of libtorrent once its resume data is saved; [leave] says what then. */
    private fun stop(hash: String, leave: Leave) {
        leaving[hash] = leave
        val th = handles[hash] ?: return // not added yet: done when it is
        try {
            th.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
        } catch (e: Throwable) {
            left(hash)
        }
    }

    /** [hash] is out of libtorrent. */
    private fun left(hash: String) {
        val leave = leaving.remove(hash)
        val th = handles.remove(hash)
        val id = active.remove(hash)
        if (th != null) runCatching { session?.remove(th) }
        val item = id?.let { Downloads.get(it) }
        if (leave == Leave.FINISHED && item != null) finished(item)
        applyRules()
    }

    /** All the chosen files are complete: sharing stops here, and the row says it's done. */
    private fun finished(item: Downloads.Item) {
        item.status = Downloads.DONE
        item.error = null
        item.bytesPerSecond = 0
        item.peers = 0
        if (item.total > 0) item.done = item.total
        Downloads.torrentChanged(item, persist = true)
        // Galleries and music apps see the new files.
        val ti = infoOf(item)
        if (ti != null) {
            val folder = item.extra.optString("dir")
            val wanted = wantedOf(item, ti)
            val paths = TorrentFiles.list(ti).filter { it.index in wanted }.map { File(folder, it.path).path }
            if (paths.isNotEmpty()) MediaScannerConnection.scanFile(app, paths.toTypedArray(), null, null)
        }
        main.post { DownloadService.notifyFinished(app, item) }
    }

    private fun fail(item: Downloads.Item, why: String) {
        item.status = Downloads.FAILED
        item.error = why
        item.bytesPerSecond = 0
        Downloads.torrentChanged(item, persist = true)
    }

    /**
     * [item] left the list ([deleteFiles]: with its files). Its metadata and resume data go too.
     * Called after the row is gone.
     */
    fun remove(item: Downloads.Item, deleteFiles: Boolean) {
        if (!::app.isInitialized) return
        worker.post {
            val hash = hashOf(item) ?: return@post
            val th = handles.remove(hash)
            active.remove(hash)
            leaving.remove(hash)
            if (th != null) {
                runCatching { if (deleteFiles) session?.remove(th, SessionHandle.DELETE_FILES) else session?.remove(th) }
            } else if (deleteFiles) {
                deleteFilesOf(item, hash)
            }
            // libtorrent deletes in the background: the metadata it may still need goes a moment later.
            worker.postDelayed({
                if (deleteFiles && th != null) deleteFilesOf(item, hash)
                torrentFile(hash).delete()
                resumeFile(hash).delete()
            }, 3000)
            applyRules()
        }
    }

    /** The files [item] downloaded (never anything else in its folder), and the folders left empty. */
    private fun deleteFilesOf(item: Downloads.Item, hash: String) {
        val folder = File(item.extra.optString("dir").ifEmpty { return })
        val ti = infoOf(item)
        if (ti == null) {
            // Never got its file list: at most a single file of its name.
            File(item.filePath).takeIf { it.isFile }?.delete()
            return
        }
        val dirs = HashSet<File>()
        for (e in TorrentFiles.list(ti)) {
            val f = File(folder, e.path)
            f.delete()
            var d = f.parentFile
            while (d != null && d != folder && d.path.startsWith(folder.path)) {
                dirs += d
                d = d.parentFile
            }
        }
        dirs.sortedByDescending { it.path.length }.forEach { d -> if (d.list()?.isEmpty() == true) d.delete() }
        folder.listFiles()?.filter { it.name.startsWith(".") && it.name.endsWith(".parts") && it.name.contains(hash, ignoreCase = true) }?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ libtorrent

    private fun limits(sp: SettingsPack): SettingsPack {
        sp.downloadRateLimit(TorrentSettings.downLimitKb * 1024)
        sp.uploadRateLimit(TorrentSettings.upLimitKb * 1024)
        return sp
    }

    private fun ensureSession(): SessionManager? {
        session?.let { return it }
        return try {
            val s = SessionManager(false)
            s.addListener(listener)
            val sp = limits(SettingsPack())
            // A 2 GB phone: fewer connections than a computer's client.
            sp.connectionsLimit(80)
            sp.setEnableDht(true)
            sp.setEnableLsd(true)
            sp.alertQueueSize(2000)
            val params = SessionParams(sp)
            // Plain file reads and writes: libtorrent's default maps whole files into memory, which a
            // 32-bit phone can't do for big films.
            params.setPosixDiskIO()
            s.start(params)
            session = s
            L.d("Torrents started")
            s
        } catch (e: Throwable) {
            L.w("Couldn't start torrents", e)
            null
        }
    }

    /** With nothing to do for a while, libtorrent stops (and its memory is given back). */
    private fun idleCheck() {
        worker.removeCallbacks(stopSession)
        if (session != null && active.isEmpty() && fetching == 0) worker.postDelayed(stopSession, IDLE_STOP_MS)
    }

    private val stopSession = Runnable {
        if (active.isNotEmpty() || fetching > 0) return@Runnable
        val s = session ?: return@Runnable
        session = null
        try {
            s.removeListener(listener)
            s.stop()
        } catch (e: Throwable) {
            L.w("Stopping torrents", e)
        }
        L.d("Torrents stopped")
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        worker.postDelayed(poll, POLL_MS)
    }

    /** Once a second, while torrents run: their progress on the rows and in the notification. */
    private val poll = object : Runnable {
        override fun run() {
            if (active.isEmpty()) {
                polling = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val save = now - lastSave > SAVE_EVERY_MS
            if (save) lastSave = now
            for ((hash, th) in handles.toList()) {
                if (hash in leaving) continue
                val item = active[hash]?.let { Downloads.get(it) } ?: continue
                val st = try {
                    th.status()
                } catch (e: Throwable) {
                    continue
                }
                if (st.errorCode().isError) {
                    fail(item, st.errorCode().message ?: "Something went wrong")
                    stop(hash, Leave.STOP)
                    continue
                }
                if (st.hasMetadata()) {
                    // libtorrent counts whole pieces; the row shows the chosen files' size, in proportion.
                    val wanted = st.totalWanted()
                    if (item.total <= 0) item.total = wanted
                    item.done = if (wanted > 0) (item.total.toDouble() * st.totalWantedDone() / wanted).toLong().coerceIn(0, item.total) else 0
                }
                item.bytesPerSecond = st.downloadPayloadRate().toLong()
                item.peers = st.numPeers()
                item.error = when (st.state()) {
                    TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> CHECKING
                    TorrentStatus.State.DOWNLOADING_METADATA -> GETTING_FILES
                    else -> null
                }
                if (save) {
                    Downloads.torrentChanged(item, persist = true)
                    if (th.needSaveResumeData()) runCatching { th.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
                }
            }
            Downloads.torrentChanged(null, persist = false)
            if (now - lastStorage > STORAGE_EVERY_MS) {
                lastStorage = now
                if (active.values.any { id -> Downloads.get(id)?.let { storageBlock(it) } != null }) applyRules()
            }
            worker.postDelayed(this, POLL_MS)
        }
    }

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.ADD_TORRENT.swig(), AlertType.METADATA_RECEIVED.swig(), AlertType.TORRENT_FINISHED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(), AlertType.SAVE_RESUME_DATA_FAILED.swig(),
            AlertType.TORRENT_ERROR.swig(), AlertType.FILE_ERROR.swig(),
        )

        // On libtorrent's thread: what is needed is taken from the alert here. The alert, and the handle
        // in it (a pointer into the alert, not a copy), are gone once this returns: handles to keep come
        // from the session ([onAdded]).
        override fun alert(alert: Alert<*>) {
            val ta = alert as? TorrentAlert<*> ?: return
            val hash = try {
                keyOf(ta.handle().swig().info_hashes())
            } catch (e: Throwable) {
                return
            }
            when (alert.type()) {
                AlertType.ADD_TORRENT -> {
                    val error = (alert as AddTorrentAlert).error().takeIf { it.isError }?.message
                    worker.post { onAdded(hash, error) }
                }
                AlertType.METADATA_RECEIVED -> worker.post { onMetadata(hash) }
                AlertType.TORRENT_FINISHED -> worker.post {
                    if (hash in active && leaving[hash] != Leave.FINISHED) stop(hash, Leave.FINISHED)
                }
                AlertType.SAVE_RESUME_DATA -> {
                    val params = (alert as SaveResumeDataAlert).params().swig()
                    val resume = runCatching { Vectors.byte_vector2bytes(libtorrent.write_resume_data_buf_ex(params)) }.getOrNull()
                    val torrent = if (params.ti_ptr() != null) runCatching { Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params)) }.getOrNull() else null
                    worker.post { onSaved(hash, resume, torrent) }
                }
                AlertType.SAVE_RESUME_DATA_FAILED -> worker.post { if (hash in leaving) left(hash) }
                AlertType.TORRENT_ERROR -> {
                    val why = (alert as TorrentErrorAlert).error().message
                    worker.post { onError(hash, why) }
                }
                AlertType.FILE_ERROR -> {
                    val why = (alert as FileErrorAlert).error().message
                    worker.post { onError(hash, why) }
                }
                else -> {}
            }
        }
    }

    private fun onAdded(hash: String, error: String?) {
        val item = active[hash]?.let { Downloads.get(it) } ?: return
        val th = if (error == null) runCatching { session?.find(Sha1Hash.parseHex(hash)) }.getOrNull() else null
        if (th == null) {
            active.remove(hash)
            leaving.remove(hash)
            fail(item, error ?: "Couldn't start it")
            return
        }
        handles[hash] = th
        // Asked to leave before libtorrent had it.
        leaving[hash]?.let {
            stop(hash, it)
            return
        }
        th.unsetFlags(TorrentFlags.AUTO_MANAGED)
        th.resume()
    }

    /** A magnet link's file list arrived: all but the junk are wanted, and the row gets its real name. */
    private fun onMetadata(hash: String) {
        val item = active[hash]?.let { Downloads.get(it) } ?: return
        val th = handles[hash] ?: return
        val ti = th.torrentFile() ?: return
        val entries = TorrentFiles.list(ti)
        val prio = prioFrom(item.extra.optString("prio"), ti.numFiles()) ?: TorrentFiles.defaultPriorities(ti)
        th.prioritizeFiles(prio)
        item.extra.put("prio", prioText(prio)).put("files", entries.size)
        TorrentFiles.main(entries) { prio[it.index] != Priority.IGNORE }?.let { item.extra.put("main", it.path) }
        item.fileName = ti.name()
        item.filePath = File(item.extra.optString("dir"), ti.name()).path
        item.total = entries.filter { prio[it.index] != Priority.IGNORE }.sumOf { it.size }
        item.error = null
        Downloads.torrentChanged(item, persist = true)
        // Its metadata is kept now, so it never has to be fetched again.
        runCatching { th.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
    }

    private fun onSaved(hash: String, resume: ByteArray?, torrent: ByteArray?) {
        // Removed meanwhile: nothing is written back.
        if (hash !in active) return
        try {
            if (resume != null) {
                val tmp = File(dir(), "$hash.resume.tmp")
                tmp.writeBytes(resume)
                tmp.renameTo(resumeFile(hash))
            }
            if (torrent != null && !torrentFile(hash).exists()) torrentFile(hash).writeBytes(torrent)
        } catch (e: Exception) {
            L.w("Couldn't save a torrent's progress", e)
        }
        if (hash in leaving) left(hash)
    }

    private fun onError(hash: String, why: String?) {
        val item = active[hash]?.let { Downloads.get(it) } ?: return
        fail(item, why?.takeIf { it.isNotBlank() } ?: "Something went wrong")
        if (hash !in leaving) stop(hash, Leave.STOP)
    }
}
