package com.swiftbrowser.files

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.os.SystemClock
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Wi-Fi transfer (2.9): a small web server on the phone's Wi-Fi address, running only while its
 * screen is open. A computer on the same Wi-Fi opens the address, types the 6-digit code shown on
 * the phone once (it gets a cookie for this session), then sees the phone's storage and SD cards:
 * folders to browse, files to download (pictures and videos also to view, with Range so videos can
 * be skipped through), folders as a ZIP made as it is sent, and files to upload into the folder on
 * show (read from the request as they come, so any size works).
 *
 * Safety: it listens on the Wi-Fi address only; every request needs the session's cookie (or is the
 * code page); the Host header must be the phone's address (a web page elsewhere can't reach it
 * through a name of its own); changes need a header a form on another site can't send; paths are
 * checked to stay inside a storage (no "..", no links out), and hidden folders, the recycle bin and
 * Swift's own folders are never served. Ten wrong codes make a new code. Nothing about files or
 * folders goes into Swift's logs.
 *
 * 3.0: after ten wrong codes in all, no computer gets in (even with the right code) until New code
 * is tapped on the phone, which says why. Swift's own folder is hidden in any capitals (the phone's
 * storage and cards ignore them: "android/DATA/…" got through), but Swift's download folder in it
 * shows as a place of its own. Uploads leave 50 MB free, are noted as unfinished copies (Parts), and
 * fail with a reason in words (the phone full, a FAT32 card and a file over 4 GB). It can go on in
 * the background while files move (WifiTransferService); "connected" ends when the computer has
 * been quiet a while (its page asks every half a minute while open).
 */
class WifiServer(private val ctx: Context, val address: InetAddress, private val page: (String) -> String) {
    /** The code the computer types; a new one on request. */
    @Volatile
    var code: String = newCode()
        private set

    @Volatile
    private var token = newToken()

    @Volatile
    var port = 0
        private set

    @Volatile
    var running = false
        private set

    /** A computer typed the code (this session). */
    @Volatile
    var signedIn = false
        private set

    val sending = AtomicInteger()
    val receiving = AtomicInteger()
    val bytesSent = AtomicLong()
    val bytesReceived = AtomicLong()
    val filesReceived = AtomicInteger()

    /** 3.0: when the computer last asked anything (signed in), on the phone's own clock. */
    @Volatile
    private var lastSeen = 0L

    /** 3.0: when something was last sent or received. */
    @Volatile
    var lastBusyAt = 0L
        private set

    /** 3.0: the file being received now, for the phone's screen and notification (never logged). */
    @Volatile
    var receivingName: String? = null
        private set

    /** 3.0: something is being sent or received now. */
    val busy: Boolean get() = sending.get() > 0 || receiving.get() > 0

    /** 3.0: something was being sent or received within [ms]. */
    fun busyWithin(ms: Long): Boolean = busy || SystemClock.elapsedRealtime() - lastBusyAt < ms

    /** 3.0: a computer is signed in and was heard from lately (its page asks every half a minute while open). */
    val connected: Boolean get() = signedIn && (busy || SystemClock.elapsedRealtime() - lastSeen < QUIET_MS)

    /** 3.0: ten wrong codes were typed: no computer gets in until New code is tapped. */
    val lockedOut: Boolean get() = codeLock.locked

    private var server: ServerSocket? = null
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private val codeLock = CodeLock(MAX_WRONG)
    private val nameLock = Any()

    /** 3.0: where the computer may look: each storage, and Swift's download folder when it is inside Swift's own folder. */
    private val roots: List<Root> = makeRoots()

    /** The storages' tops and Swift's download folder, as their real paths, for the checks. */
    private val storageTops: List<String> = Volumes.all(ctx).mapNotNull { canonical(it.root) }
    private val downloadsPath: String? = roots.firstOrNull { it.downloads }?.let { canonical(it.dir) }
    private val pool = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, LinkedBlockingQueue(32)) { r ->
        Thread(r, "wifi-transfer-io").apply { isDaemon = true }
    }

    /** Starts listening at [firstPort] or the next free one; false when none could be had. */
    fun start(firstPort: Int): Boolean {
        val ports = (firstPort..firstPort + 10).toList() + 0
        for (p in ports) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(address, p), 50)
                server = s
                port = s.localPort
                break
            } catch (_: IOException) {
            }
        }
        val s = server ?: return false
        running = true
        Thread({ acceptLoop(s) }, "wifi-transfer").apply { isDaemon = true }.start()
        L.d("Wi-Fi transfer on (port $port)")
        return true
    }

    fun stop() {
        if (!running && server == null) return
        running = false
        runCatching { server?.close() }
        server = null
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        pool.shutdownNow()
        L.d("Wi-Fi transfer off")
    }

    /** A new code; the computer signed in with the old one must type it. 3.0: computers may sign in again. */
    fun renewCode() {
        code = newCode()
        token = newToken()
        signedIn = false
        codeLock.reset()
    }

    val url: String get() = "http://${address.hostAddress}:$port"

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try {
                s.accept()
            } catch (_: IOException) {
                break
            }
            try {
                c.soTimeout = 60_000
                c.tcpNoDelay = true
                clients += c
                pool.execute { serve(c) }
            } catch (_: RejectedExecutionException) {
                clients -= c
                runCatching { c.close() }
            } catch (_: IOException) {
                clients -= c
                runCatching { c.close() }
            }
        }
    }

    // ------------------------------------------------------------------ requests

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, List<String>>,
        val headers: Map<String, String>,
        val body: InputStream,
    ) {
        fun q(name: String): String? = query[name]?.firstOrNull()
    }

    private class Reply(val status: Int, val type: String, val body: ByteArray, val headers: List<Pair<String, String>> = emptyList())

    private fun serve(c: Socket) {
        try {
            val input = BufferedInputStream(c.getInputStream(), 16 * 1024)
            val out = BufferedOutputStream(c.getOutputStream(), FileJobs.BUFFER)
            val req = readRequest(input) ?: return
            try {
                route(req, out)
            } catch (e: Exception) {
                L.w("Wi-Fi transfer: a request failed (${e.javaClass.simpleName})")
                // 3.0: the reason in words when it is a usual one (the page shows the answer's "error").
                runCatching { send(out, Reply(500, JSON, err(FileErrors.text(e) ?: "Something went wrong on the phone."))) }
            }
            out.flush()
            // The answer is whole; what the computer still sends (the last bytes of an upload) is read and
            // dropped before closing, or the closing could cut the answer off before the browser reads it.
            runCatching {
                c.shutdownOutput()
                c.soTimeout = 1500
                val skip = ByteArray(8192)
                var total = 0
                while (total < 1_048_576) {
                    val n = input.read(skip)
                    if (n < 0) break
                    total += n
                }
            }
        } catch (_: Exception) {
            // The computer went away or took too long: nothing to answer.
        } finally {
            clients -= c
            runCatching { c.close() }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.length > 16 * 1024) return null
        }
        val lines = head.split("\r\n")
        val first = lines[0].split(' ')
        if (first.size < 3) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim()
        }
        val target = first[1]
        val path = target.substringBefore('?')
        val query = HashMap<String, MutableList<String>>()
        target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { kv ->
            val k = decode(kv.substringBefore('='))
            val v = decode(kv.substringAfter('=', ""))
            query.getOrPut(k) { ArrayList(1) } += v
        }
        return Request(first[0].uppercase(Locale.ROOT), path, query, headers, input)
    }

    private fun decode(s: String) = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        ""
    }

    private fun route(req: Request, out: OutputStream) {
        // Only through the phone's own address: a web page that named itself after it gets nothing.
        val host = req.headers["host"]
        if (host != "${address.hostAddress}:$port") {
            send(out, Reply(403, TEXT, "Open this through the phone's address.".toByteArray()))
            return
        }
        val authed = cookieOk(req)
        if (authed) lastSeen = SystemClock.elapsedRealtime()
        when {
            req.path == "/favicon.ico" -> send(out, Reply(204, TEXT, ByteArray(0)))
            req.path == "/login" && req.method == "POST" -> login(req, out)
            req.path == "/" && (req.method == "GET" || req.method == "HEAD") ->
                send(out, Reply(200, HTML, page(if (authed) "index" else "login").toByteArray(), PAGE_HEADERS), head = req.method == "HEAD")
            !authed -> send(out, Reply(401, JSON, """{"error":"signin"}""".toByteArray()))
            // 3.0: the page says it is still open (for "connected" on the phone).
            req.path == "/api/ping" -> send(out, Reply(204, TEXT, ByteArray(0)))
            req.path == "/api/roots" -> send(out, Reply(200, JSON, rootsJson().toByteArray()))
            req.path == "/api/list" -> list(req, out)
            req.path == "/dl" && (req.method == "GET" || req.method == "HEAD") -> download(req, out)
            req.path == "/zip" && req.method == "GET" -> zip(req, out)
            req.path == "/upload" && req.method == "POST" -> if (fromPage(req)) upload(req, out) else send(out, Reply(403, JSON, err("Refused")))
            req.path == "/api/mkdir" && req.method == "POST" -> if (fromPage(req)) mkdir(req, out) else send(out, Reply(403, JSON, err("Refused")))
            else -> send(out, Reply(404, JSON, err("Not found")))
        }
    }

    /** Changes come only from the page itself (a form on another site can't add this header). */
    private fun fromPage(req: Request) = req.headers["x-swift"] == "1"

    private fun cookieOk(req: Request): Boolean {
        val cookie = req.headers["cookie"] ?: return false
        val value = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=') ?: return false
        return MessageDigest.isEqual(value.toByteArray(), token.toByteArray())
    }

    private fun login(req: Request, out: OutputStream) {
        val len = req.headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1024) ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = req.body.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        val typed = String(body, 0, read).split('&').firstOrNull { it.startsWith("code=") }?.substringAfter('=')?.let { decode(it) }?.filter { it.isDigit() }.orEmpty()
        val answer = codeLock.attempt(typed, code)
        if (answer == CodeLock.Answer.IN) {
            signedIn = true
            lastSeen = SystemClock.elapsedRealtime()
            L.d("Wi-Fi transfer: a computer signed in")
            send(out, Reply(303, TEXT, ByteArray(0), listOf(
                "Location" to "/",
                "Set-Cookie" to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict",
            )))
            return
        }
        // Slow to guess. 3.0: after ten wrong ones in all, nobody gets in until New code is tapped on the phone
        // (2.9 showed a new code instead, which guessing could go on against for ever).
        if (answer == CodeLock.Answer.LOCKED_NOW) L.w("Wi-Fi transfer: ten wrong codes, no more sign-ins until New code is tapped")
        Thread.sleep(600)
        send(out, Reply(303, TEXT, ByteArray(0), listOf("Location" to if (codeLock.locked) "/?locked=1" else "/?wrong=1")))
    }

    // ------------------------------------------------------------------ what may be seen

    /** A place the computer can open: a storage, or (3.0) Swift's download folder when it is inside Swift's own folder there. */
    private class Root(val name: String, val dir: File, val volume: Volumes.Volume, val downloads: Boolean = false)

    private fun makeRoots(): List<Root> {
        val out = Volumes.all(ctx).map { Root(it.name, it.root, it) }.toMutableList()
        // 3.0: Swift's downloads may be in its own folder on a storage (Android/data/…/files, always so on a
        // card), which is hidden: that folder alone shows, as a place of its own.
        val dl = runCatching { com.swiftbrowser.download.Downloads.directory() }.getOrNull()
        val v = dl?.let { Volumes.of(ctx, it) }
        if (dl != null && v != null) {
            val parts = dl.path.removePrefix(v.root.path).trim('/').split('/')
            if (WifiPaths.isOwnFolder(parts, ctx.packageName) && parts.size > 4 && parts[3].equals("files", ignoreCase = true)) {
                out += Root("Swift's downloads" + if (v.removable) " (${v.name})" else "", dl, v, downloads = true)
            }
        }
        return out
    }

    private fun canonical(f: File): String? = runCatching { f.canonicalPath }.getOrNull()

    /** A folder or file asked for: [v] is the place's number, [p] the path inside it. Null when it isn't to be shown. */
    private fun resolve(v: String?, p: String?): Pair<Root, File>? {
        val root = roots.getOrNull(v?.toIntOrNull() ?: 0) ?: return null
        val rel = p.orEmpty().trim('/')
        val parts = rel.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." || it.startsWith(".") || it.contains('\u0000') || it.contains('\\') }) return null
        val f = if (parts.isEmpty()) root.dir else File(root.dir, parts.joinToString("/"))
        val canon = canonical(f) ?: return null
        val top = canonical(root.dir) ?: return null
        // Through a link out of the place: not shown.
        if (canon != top && !canon.startsWith("$top/")) return null
        if (hidden(canon)) return null
        val own = canonical(File(ctx.applicationInfo.dataDir))
        if (own != null && (canon == own || canon.startsWith("$own/"))) return null
        return root to f
    }

    /**
     * Swift's own folder on a storage (its cache and app data) isn't shown. 3.0: in any capitals (the
     * phone's storage and cards ignore them), except for Swift's download folder in it.
     */
    private fun hidden(canon: String): Boolean {
        for (top in storageTops) {
            if (canon != top && !canon.startsWith("$top/")) continue
            val parts = canon.removePrefix(top).trim('/').split('/')
            if (WifiPaths.isOwnFolder(parts, ctx.packageName)) return downloadsPath == null || !WifiPaths.within(canon, downloadsPath)
        }
        return false
    }

    private fun relOf(root: Root, f: File) = f.path.removePrefix(root.dir.path).trim('/')

    private fun rootsJson(): String {
        val arr = JSONArray()
        roots.forEachIndexed { i, r ->
            val (free, total) = r.volume.space()
            arr.put(JSONObject().put("v", i).put("name", r.name).put("free", free).put("total", total).put("card", r.volume.removable))
        }
        return arr.toString()
    }

    private fun list(req: Request, out: OutputStream) {
        val (root, dir) = resolve(req.q("v"), req.q("p")) ?: return send(out, Reply(404, JSON, err("That folder isn't there.")))
        val files = dir.listFiles() ?: return send(out, Reply(404, JSON, err("That folder can't be read.")))
        val rel = relOf(root, dir)
        val items = FileOps.sorted(
            // 3.0: Swift's own folder isn't listed in Android/data in any capitals.
            files.filter { !it.name.startsWith(".") && !(!root.downloads && rel.equals("Android/data", ignoreCase = true) && it.name.equals(ctx.packageName, ignoreCase = true)) }
                .map { FileItem.of(it) },
            FileOps.SORT_NAME,
        )
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().put("n", it.name).put("d", it.isDir).put("s", it.size).put("t", it.time))
        val o = JSONObject()
            .put("v", roots.indexOf(root))
            .put("root", root.name)
            .put("p", rel)
            .put("canWrite", Volumes.accessFor(ctx, dir) != null)
            .put("free", root.volume.space().first)
            .put("items", arr)
        send(out, Reply(200, JSON, o.toString().toByteArray()))
    }

    private fun mkdir(req: Request, out: OutputStream) {
        val (_, dir) = resolve(req.q("v"), req.q("p"))?.takeIf { it.second.isDirectory } ?: return send(out, Reply(404, JSON, err("That folder isn't there.")))
        val name = FileOps.cleanName(req.q("name").orEmpty())
        if (name.isEmpty() || name.startsWith(".")) return send(out, Reply(400, JSON, err("That name can't be used.")))
        val access = Volumes.accessFor(ctx, dir) ?: return send(out, Reply(403, JSON, err(CARD_TEXT)))
        val f = File(dir, name)
        if (f.exists()) return send(out, Reply(409, JSON, err("There's already something called “$name” here.")))
        if (!access.mkdir(f)) return send(out, Reply(500, JSON, err("The phone couldn't make that folder.")))
        send(out, Reply(200, JSON, """{"ok":true}""".toByteArray()))
    }

    // ------------------------------------------------------------------ to the computer

    private fun download(req: Request, out: OutputStream) {
        val (_, f) = resolve(req.q("v"), req.q("p"))?.takeIf { it.second.isFile } ?: return send(out, Reply(404, TEXT, "That file isn't there.".toByteArray()))
        val len = f.length()
        val mime = FileOps.mimeOf(f.name).takeIf { it != "*/*" } ?: "application/octet-stream"
        // Shown in the browser only for kinds that can't run anything; the rest is saved.
        val inline = req.q("view") == "1" && (mime.startsWith("image/") && mime != "image/svg+xml" || mime.startsWith("video/") || mime.startsWith("audio/") || mime == "text/plain" || mime == "application/pdf")
        val headers = ArrayList<Pair<String, String>>()
        headers += "Accept-Ranges" to "bytes"
        headers += "Content-Disposition" to disposition(if (inline) "inline" else "attachment", f.name)
        // A file can't act as the page (scripts in it don't run with the session's cookie).
        if (mime != "application/pdf") headers += "Content-Security-Policy" to "sandbox; default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'"
        var start = 0L
        var end = len - 1
        var status = 200
        val range = req.headers["range"]
        if (range != null && range.startsWith("bytes=") && !range.contains(',')) {
            val spec = range.removePrefix("bytes=").trim()
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            val ok = try {
                if (a.isEmpty()) {
                    start = (len - b.toLong()).coerceAtLeast(0)
                } else {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1)
                }
                start <= end && start < len
            } catch (_: NumberFormatException) {
                false
            }
            if (!ok) {
                send(out, Reply(416, TEXT, ByteArray(0), listOf("Content-Range" to "bytes */$len")))
                return
            }
            status = 206
            headers += "Content-Range" to "bytes $start-$end/$len"
        }
        val count = if (len == 0L) 0L else end - start + 1
        writeHead(out, status, mime, count, headers)
        if (req.method == "HEAD" || count == 0L) return
        sending.incrementAndGet()
        lastBusyAt = SystemClock.elapsedRealtime()
        try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(FileJobs.BUFFER)
                var left = count
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                    bytesSent.addAndGet(n.toLong())
                }
            }
            out.flush()
        } finally {
            sending.decrementAndGet()
            lastBusyAt = SystemClock.elapsedRealtime()
        }
    }

    /** A folder, or the files picked in it ([n]), as a ZIP made while it is sent. */
    private fun zip(req: Request, out: OutputStream) {
        val (r, dir) = resolve(req.q("v"), req.q("p"))?.takeIf { it.second.isDirectory } ?: return send(out, Reply(404, TEXT, "That folder isn't there.".toByteArray()))
        val picked = req.query["n"].orEmpty().filter { n -> n.isNotEmpty() && !n.contains('/') && !n.startsWith(".") }
        val items = if (picked.isEmpty()) listOf(dir) else picked.map { File(dir, it) }.filter { it.exists() }
        if (items.isEmpty()) return send(out, Reply(404, TEXT, "Nothing to send.".toByteArray()))
        val folderName = if (dir.path == r.dir.path) r.name else dir.name
        val base = when {
            picked.isEmpty() -> folderName
            items.size == 1 -> items[0].name
            else -> "$folderName (${items.size} items)"
        }
        val root = canonical(r.dir) ?: return send(out, Reply(404, TEXT, "Nothing to send.".toByteArray()))
        writeHead(out, 200, "application/zip", -1, listOf("Content-Disposition" to disposition("attachment", "$base.zip")))
        sending.incrementAndGet()
        lastBusyAt = SystemClock.elapsedRealtime()
        try {
            val zip = ZipOutputStream(CountingOutput(out, bytesSent))
            for (f in items) addToZip(zip, f, if (items.size == 1 && f.isDirectory) "" else f.name, root)
            zip.finish()
            out.flush()
        } finally {
            sending.decrementAndGet()
            lastBusyAt = SystemClock.elapsedRealtime()
        }
    }

    private fun addToZip(zip: ZipOutputStream, f: File, name: String, root: String) {
        if (f.name.startsWith(".")) return
        // Nothing reached through a link out of the place, nor Swift's own folder (3.0: in any capitals).
        val canon = runCatching { f.canonicalPath }.getOrNull() ?: return
        if (!canon.startsWith("$root/") && canon != root) return
        if (hidden(canon)) return
        if (f.isDirectory) {
            if (name.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("$name/").apply { time = f.lastModified() })
                zip.closeEntry()
            }
            for (c in f.listFiles().orEmpty().sortedBy { it.name }) addToZip(zip, c, if (name.isEmpty()) c.name else "$name/${c.name}", root)
            return
        }
        // A file that can't be read is left out rather than ending the whole ZIP.
        val input = try {
            f.inputStream()
        } catch (_: IOException) {
            return
        }
        input.use {
            // Photos, videos and archives are packed already: stored as they are, which is much quicker on a phone.
            zip.setLevel(if (FileKind.extension(f.name) in PACKED) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
            val buf = ByteArray(FileJobs.BUFFER)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                zip.write(buf, 0, n)
            }
            zip.closeEntry()
        }
    }

    private class CountingOutput(private val out: OutputStream, private val counter: AtomicLong) : OutputStream() {
        override fun write(b: Int) {
            out.write(b)
            counter.incrementAndGet()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            counter.addAndGet(len.toLong())
        }

        override fun flush() = out.flush()

        // The socket is closed by the server, not by the ZIP.
        override fun close() = out.flush()
    }

    // ------------------------------------------------------------------ from the computer

    private fun upload(req: Request, out: OutputStream) {
        val (root, dir) = resolve(req.q("v"), req.q("p"))?.takeIf { it.second.isDirectory } ?: return send(out, Reply(404, JSON, err("That folder isn't there.")))
        val boundary = req.headers["content-type"]?.let { t ->
            if (!t.startsWith("multipart/form-data", ignoreCase = true)) null
            else Regex("boundary=\"?([^\";]+)\"?").find(t)?.groupValues?.get(1)
        } ?: return send(out, Reply(400, JSON, err("Not an upload.")))
        val access = Volumes.accessFor(ctx, dir) ?: return send(out, Reply(403, JSON, err(CARD_TEXT)))
        val length = req.headers["content-length"]?.toLongOrNull() ?: -1L
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        // 3.0: some room always stays free for the phone itself.
        val room = if (free == Long.MAX_VALUE) free else (free - FileJobs.MARGIN).coerceAtLeast(0)
        if (length > 0 && length > room) {
            return send(out, Reply(507, JSON, err("There isn't enough room on the phone: this needs ${UrlUtils.formatBytes(length)}, and ${UrlUtils.formatBytes(room)} can be used.")))
        }
        // 3.0: a FAT32 card can't take a file over 4 GB: said before it is sent, not after.
        if (length > FileErrors.FAT32_MAX + 65_536 && Volumes.isFat32(root.volume)) {
            return send(out, Reply(413, JSON, err(FileErrors.FAT32)))
        }
        val saved = ArrayList<String>()
        receiving.incrementAndGet()
        lastBusyAt = SystemClock.elapsedRealtime()
        var used = 0L
        try {
            val parts = Multipart(req.body, boundary) { n -> bytesReceived.addAndGet(n.toLong()) }
            var more = parts.begin()
            while (more) {
                val headers = parts.headers()
                val disposition = headers["content-disposition"].orEmpty()
                val name = fileNameOf(disposition)
                if (name == null) {
                    more = parts.data(null)
                    continue
                }
                // Written under a hidden name, then given its own: half an upload never shows as the file.
                // 3.0: noted as an unfinished copy, so one cut off by Swift closing goes the next time Files opens.
                val temp = Parts.newIn(dir, "up-")
                Parts.begin(ctx, temp)
                receivingName = name
                var ok = false
                try {
                    Saving(access.output(temp), room - used, root.volume.removable).use { o ->
                        more = parts.data(o)
                        used += o.count
                    }
                    ok = true
                } catch (e: Saving.Failed) {
                    L.w("Wi-Fi transfer: an upload couldn't be saved (${e.cause?.javaClass?.simpleName ?: "no room"})")
                    return send(out, Reply(if (e.full) 507 else 500, JSON, err(e.text)))
                } finally {
                    if (!ok) runCatching { access.delete(temp) }
                    if (!ok) Parts.end(ctx, temp)
                    receivingName = null
                }
                val target = synchronized(nameLock) {
                    val t = FileOps.freeName(File(dir, name), isDir = false)
                    if (!access.rename(temp, t.name)) {
                        runCatching { access.delete(temp) }
                        null
                    } else {
                        t
                    }
                }
                Parts.end(ctx, temp)
                if (target != null) {
                    saved += target.name
                    filesReceived.incrementAndGet()
                    FileOps.scan(ctx, listOf(target.path))
                }
            }
        } finally {
            receiving.decrementAndGet()
            lastBusyAt = SystemClock.elapsedRealtime()
            // 3.0.4: Files measures the folder again (FolderSizes).
            if (saved.isNotEmpty()) FolderSizes.changed(listOf(dir.path))
        }
        send(out, Reply(200, JSON, JSONObject().put("ok", true).put("saved", JSONArray(saved)).toString().toByteArray()))
    }

    /**
     * 3.0: where an upload is written: no further than [room] (the free space less 50 MB), and a
     * failure to write said in words (FileErrors), apart from the computer going away.
     */
    private class Saving(out: OutputStream, private val room: Long, private val onCard: Boolean) : java.io.FilterOutputStream(out) {
        class Failed(val text: String, val full: Boolean, cause: Throwable?) : IOException(text, cause)

        var count = 0L
            private set

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (count + len > room) throw Failed(if (onCard) FileErrors.CARD_FULL else FileErrors.FULL, true, null)
            try {
                out.write(b, off, len)
            } catch (e: IOException) {
                val text = FileErrors.text(e, count, onCard) ?: "The phone couldn't save this."
                throw Failed(text, text == FileErrors.FULL || text == FileErrors.CARD_FULL, e)
            }
            count += len
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun close() {
            try {
                super.close()
            } catch (e: Failed) {
                throw e
            } catch (e: IOException) {
                val text = FileErrors.text(e, count, onCard) ?: "The phone couldn't save this."
                throw Failed(text, text == FileErrors.FULL || text == FileErrors.CARD_FULL, e)
            }
        }
    }

    /** The name the computer gave the file, as a safe name of one part ("upload" when nothing is left). */
    private fun fileNameOf(disposition: String): String? {
        val star = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)?.let { decode(it) }
        val plain = Regex("filename=\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)?.replace("\\\"", "\"")
            ?: Regex("filename=([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)?.trim()
        val raw = star ?: plain ?: return null
        // Some browsers send the computer's whole path: only its last part.
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        return FileOps.cleanName(last).trimStart('.').ifEmpty { "upload" }
    }

    /**
     * Reads a multipart/form-data body part by part as it arrives: each part's data goes straight
     * on to where it is written, with only a buffer's worth held at a time.
     */
    private class Multipart(private val input: InputStream, boundary: String, private val counted: (Int) -> Unit) {
        private val delim = ("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1)
        private val buf = ByteArray(FileJobs.BUFFER + delim.size)
        private var start = 0
        private var end = 0

        private fun fill(): Boolean {
            if (start > 0) {
                System.arraycopy(buf, start, buf, 0, end - start)
                end -= start
                start = 0
            }
            if (end == buf.size) return true
            val n = input.read(buf, end, buf.size - end)
            if (n < 0) return false
            counted(n)
            end += n
            return true
        }

        private fun need(n: Int) {
            while (end - start < n) if (!fill()) throw IOException("The upload ended early")
        }

        private fun indexOf(pattern: ByteArray, from: Int): Int {
            var i = from
            val last = end - pattern.size
            while (i <= last) {
                if (buf[i] == pattern[0]) {
                    var j = 1
                    while (j < pattern.size && buf[i + j] == pattern[j]) j++
                    if (j == pattern.size) return i
                }
                i++
            }
            return -1
        }

        /** Skips what comes before the first part; true when there is one. */
        fun begin(): Boolean {
            val first = delim.copyOfRange(2, delim.size)
            while (true) {
                val i = indexOf(first, start)
                if (i >= 0) {
                    start = i + first.size
                    return afterBoundary()
                }
                start = maxOf(start, end - first.size + 1)
                if (!fill()) return false
            }
        }

        /** After a boundary: "--" ends the body, otherwise a line break and the next part. */
        private fun afterBoundary(): Boolean {
            need(2)
            if (buf[start] == '-'.code.toByte() && buf[start + 1] == '-'.code.toByte()) return false
            while (true) {
                need(2)
                if (buf[start] == '\r'.code.toByte() && buf[start + 1] == '\n'.code.toByte()) {
                    start += 2
                    return true
                }
                start++
            }
        }

        /** The part's headers, names in lower case. */
        fun headers(): Map<String, String> {
            val crlf2 = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            while (true) {
                val i = indexOf(crlf2, start)
                if (i >= 0) {
                    val text = String(buf, start, i - start, Charsets.UTF_8)
                    start = i + crlf2.size
                    val out = HashMap<String, String>()
                    for (l in text.split("\r\n")) {
                        val c = l.indexOf(':')
                        if (c > 0) out[l.substring(0, c).trim().lowercase(Locale.ROOT)] = l.substring(c + 1).trim()
                    }
                    return out
                }
                if (start == 0 && end == buf.size) throw IOException("Headers too long")
                if (!fill()) throw IOException("The upload ended early")
            }
        }

        /** Copies the part's data to [out] (null: skipped) up to the next boundary; true when another part follows. */
        fun data(out: OutputStream?): Boolean {
            while (true) {
                val i = indexOf(delim, start)
                if (i >= 0) {
                    out?.write(buf, start, i - start)
                    start = i + delim.size
                    return afterBoundary()
                }
                // The end of the buffer may be the start of a boundary: kept for the next round.
                val safe = end - (delim.size - 1)
                if (safe > start) {
                    out?.write(buf, start, safe - start)
                    start = safe
                }
                if (!fill()) throw IOException("The upload ended early")
            }
        }
    }

    // ------------------------------------------------------------------ answers

    private fun err(text: String) = JSONObject().put("error", text).toString().toByteArray()

    private fun disposition(kind: String, name: String): String {
        val ascii = name.map { ch -> if (ch.code in 0x20..0x7E && ch != '"' && ch != '\\' && ch != ';') ch else '_' }.joinToString("")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "$kind; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private fun send(out: OutputStream, r: Reply, head: Boolean = false) {
        writeHead(out, r.status, r.type, r.body.size.toLong(), r.headers)
        if (!head) out.write(r.body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: Int, type: String, length: Long, extra: List<Pair<String, String>>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "OK").append("\r\n")
        sb.append("Content-Type: ").append(type).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val COOKIE = "swift_t"
        private const val MAX_WRONG = 10

        /** 3.0: a computer not heard from this long counts as gone (its page asks every 30 seconds; hidden tabs less often). */
        private const val QUIET_MS = 150_000L
        private const val TEXT = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"
        private const val JSON = "application/json; charset=utf-8"
        const val CARD_TEXT = "Swift may only read the SD card. On the phone, open Files and change something on the card once to allow it."

        private val PAGE_HEADERS = listOf(
            "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self'; frame-ancestors 'none'; form-action 'self'",
            "X-Frame-Options" to "DENY",
        )

        private val PACKED = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi",
            "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "7z", "rar", "gz", "xz", "bz2", "apk", "docx", "xlsx", "pptx", "epub", "pdf",
        )

        private val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 206 to "Partial Content", 303 to "See Other", 400 to "Bad Request",
            401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 409 to "Conflict", 413 to "Payload Too Large",
            416 to "Range Not Satisfiable", 500 to "Internal Server Error", 507 to "Insufficient Storage",
        )

        private val random = SecureRandom()

        private fun newCode(): String = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))

        private fun newToken(): String = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }

        /**
         * The phone's address on a Wi-Fi network (or on its own hotspot, which a computer can join);
         * null when it is on neither (mobile data only).
         */
        fun wifiAddress(ctx: Context): Inet4Address? {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                @Suppress("DEPRECATION")
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val lp = cm.getLinkProperties(n) ?: continue
                    lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let { return it as Inet4Address }
                }
            } catch (e: Exception) {
                L.w("Wi-Fi transfer: couldn't read the networks (${e.javaClass.simpleName})")
            }
            // The phone's own hotspot: its Wi-Fi part has a local address while the phone uses mobile data.
            try {
                for (i in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                    val name = i.name.lowercase(Locale.ROOT)
                    if (!i.isUp || i.isLoopback || !(name.contains("wlan") || name.startsWith("ap") || name.startsWith("softap"))) continue
                    i.inetAddresses.toList().firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.let { return it as Inet4Address }
                }
            } catch (_: Exception) {
            }
            return null
        }
    }
}

/**
 * Wrong codes typed on computers (3.0): after [max] in all (not in a row, and many computers or
 * requests at once count together), no computer gets in, even with the right code, until [reset]
 * (New code on the phone). Guessing can't go on in the background while the phone shows a code.
 */
class CodeLock(private val max: Int) {
    enum class Answer { IN, WRONG, LOCKED_NOW, LOCKED }

    private var wrong = 0

    @Volatile
    var locked = false
        private set

    /** [typed] against the code shown on the phone. */
    @Synchronized
    fun attempt(typed: String, code: String): Answer {
        if (locked) return Answer.LOCKED
        if (typed.isNotEmpty() && MessageDigest.isEqual(typed.toByteArray(), code.toByteArray())) return Answer.IN
        wrong++
        if (wrong >= max) {
            locked = true
            return Answer.LOCKED_NOW
        }
        return Answer.WRONG
    }

    /** New code: computers may try again. */
    @Synchronized
    fun reset() {
        wrong = 0
        locked = false
    }
}

/** What Wi-Fi transfer never shows (3.0), as plain checks on paths. */
object WifiPaths {
    /**
     * Whether [parts] (a path inside a storage, folder by folder) is in Swift's own folder there,
     * Android/data/<package>, in any capitals: the phone's storage and memory cards ignore them.
     */
    fun isOwnFolder(parts: List<String>, pkg: String): Boolean =
        parts.size >= 3 && parts[0].equals("Android", ignoreCase = true) && parts[1].equals("data", ignoreCase = true) && parts[2].equals(pkg, ignoreCase = true)

    /** Whether [path] is [dir] or inside it, capitals aside. */
    fun within(path: String, dir: String): Boolean =
        path.equals(dir, ignoreCase = true) || path.lowercase(Locale.ROOT).startsWith(dir.lowercase(Locale.ROOT) + "/")
}
