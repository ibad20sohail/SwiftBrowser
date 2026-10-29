package com.swiftbrowser.docs

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoPreferenceController.SetGeckoPreference
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.zip.ZipInputStream

/**
 * Documents (2.4): PDF, Word, Excel, PowerPoint, e-books and text files open in Swift's own viewer
 * (the helper extension's docs/view.html), from the phone, other apps and downloads. Swift reads the
 * file and hands it to the viewer over its private port: PDFs piece by piece as pdf.js asks, the
 * rest whole. Nothing is uploaded. Each document opened is remembered (the documents table) with
 * where it was left, for the home page's Continue and for reopening after Swift restarts.
 *
 * 2.5: ZIP files list what they hold; documents from the web open without a copy (Save a copy keeps
 * one); Save as PDF, Print, filled-in PDFs saved to Downloads; home screen shortcuts; reading mode.
 */
object Docs {
    enum class Kind(val key: String, val label: String, val color: Int, private val exts: String, private val mimes: String) {
        PDF("pdf", "PDF", 0xFFD93025.toInt(), "pdf", "application/pdf"),
        WORD("word", "Word", 0xFF2B579A.toInt(), "docx docm dotx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        SHEET("sheet", "Excel", 0xFF217346.toInt(), "xlsx xlsm xls xlsb csv tsv ods",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet application/vnd.ms-excel text/csv " +
                "text/comma-separated-values application/vnd.oasis.opendocument.spreadsheet"),
        SLIDES("slides", "PowerPoint", 0xFFC43E1C.toInt(), "pptx ppsx potx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        EPUB("epub", "E-book", 0xFF7B3FA0.toInt(), "epub", "application/epub+zip"),
        OLD_WORD("oldword", "Word", 0xFF2B579A.toInt(), "doc dot", "application/msword"),
        OLD_SLIDES("oldslides", "PowerPoint", 0xFFC43E1C.toInt(), "ppt pps", "application/vnd.ms-powerpoint"),
        ODT("odt", "Text document", 0xFF2B579A.toInt(), "odt", "application/vnd.oasis.opendocument.text"),
        ODP("odp", "Presentation", 0xFFC43E1C.toInt(), "odp", "application/vnd.oasis.opendocument.presentation"),
        RTF("rtf", "Rich text", 0xFF2B579A.toInt(), "rtf", "application/rtf text/rtf"),
        TEXT("text", "Text", 0xFF5F6368.toInt(), "txt log md markdown json xml ini cfg yaml yml srt vtt sub",
            "text/plain text/markdown application/json text/xml"),
        ZIP("zip", "ZIP", 0xFF8D6E00.toInt(), "zip", "application/zip application/x-zip-compressed");

        val extensions: List<String> = exts.split(' ')
        val mimeTypes: List<String> = mimes.split(' ')

        /** The short mark on its icon. */
        val badge: String get() = when (this) {
            PDF -> "PDF"
            WORD, OLD_WORD -> "W"
            SHEET -> "X"
            SLIDES, OLD_SLIDES -> "P"
            EPUB -> "EPUB"
            ODT, ODP -> "ODF"
            RTF -> "RTF"
            TEXT -> "TXT"
            ZIP -> "ZIP"
        }

        companion object {
            fun ofKey(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    /** What Swift can show, by the file's name (its extension) or else its type. */
    fun kindOf(name: String?, mime: String?): Kind? {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (ext.isNotEmpty()) Kind.entries.firstOrNull { ext in it.extensions }?.let { return it }
        val m = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        return Kind.entries.firstOrNull { m in it.mimeTypes }
    }

    /** Every type Swift opens (for Android's file picker and "Open with"). */
    val allMimeTypes: Array<String> get() = Kind.entries.flatMap { it.mimeTypes }.distinct().toTypedArray()

    /** An opened document: [uri] is where it is (a file or another app's content), [web] the link it came from. */
    class Doc(
        val id: String, val uri: String, val name: String, val kind: Kind, val size: Long, val web: String?,
        val place: String?, val progress: Float, val total: Int, val opened: Long, val updated: Long,
        /** Opened in an incognito tab (2.6): kept in memory only, never in the documents table. */
        val incognito: Boolean = false,
    )

    private val io = Executors.newSingleThreadExecutor()

    /** Documents being copied from the web (2.6): their own thread, so open viewers keep reading meanwhile. */
    private val web = com.swiftbrowser.core.Background.single("docs-web")
    private val main = Handler(Looper.getMainLooper())

    /** Documents opened in incognito tabs, gone when the last incognito tab closes. */
    private val incognitoDocs = java.util.concurrent.ConcurrentHashMap<String, Doc>()

    /** The document with [id]: an incognito one in memory, or one in the documents table. */
    fun find(id: String): Doc? = incognitoDocs[id] ?: SwiftApp.db.document(id)

    /** The viewer page for [id] (null until the helper extension is ready). */
    fun viewerUrl(id: String): String? = Helper.extension?.metaData?.baseUrl?.let { base ->
        (if (base.endsWith("/")) base else "$base/") + "docs/view.html#" + id
    }

    fun isViewerUrl(url: String?): Boolean = url != null && url.startsWith("moz-extension://") && url.contains("/docs/view.html")

    /** The document a viewer tab shows. */
    fun docOf(url: String?): Doc? = if (isViewerUrl(url)) find(url!!.substringAfter('#')) else null

    /** Where documents opened without keeping a copy (from the web, out of a ZIP) wait, for a week at most. */
    private fun tempDir(ctx: Context) = File(ctx.cacheDir, "docs")

    private fun isTemp(ctx: Context, uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_FILE && uri.path.orEmpty().startsWith(tempDir(ctx).path + "/")

    /**
     * Opens [uri] in a new tab, in Swift's viewer. [web]: the page link it was downloaded from (for
     * "Exact view (online)"). Says so and returns false when Swift can't show it.
     */
    fun open(
        ctx: Context, uri: Uri, nameHint: String? = null, mimeHint: String? = null, web: String? = null,
        from: Tab? = null, incognito: Boolean = from?.incognito ?: false, backTo: String? = null,
    ): Boolean {
        if (!readable(ctx, uri)) return false
        val (name, size) = describe(ctx, uri, nameHint)
        val mime = mimeHint ?: if (uri.scheme == ContentResolver.SCHEME_CONTENT) ctx.contentResolver.getType(uri) else null
        val kind = kindOf(name, mime) ?: return false
        // A file another app chose to share can be opened again later (Continue, a restored tab).
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        val key = uri.toString()
        val profile = Profiles.currentId
        val id: String
        if (incognito) {
            // Nothing about it is written down: not in Documents, Continue or the app's shortcuts.
            id = idFor("incognito|$key")
            val now = System.currentTimeMillis()
            incognitoDocs[id] = Doc(id, key, name, kind, size, web, null, 0f, 0, now, now, incognito = true)
        } else {
            id = idFor(key, profile)
            io.execute { SwiftApp.db.saveDocument(id, key, name, kind.key, size, web, profile) }
        }
        L.d("Opening document ${kind.key} ($size bytes)")
        reopen(id, from, incognito, backTo)
        return true
    }

    /**
     * A document link on a website (2.5): read into Swift's cache and shown, without a copy in
     * Downloads. The viewer's Save a copy keeps one.
     */
    fun openFromWeb(a: Activity, tab: Tab, response: WebResponse, name: String, mime: String?, done: (Boolean) -> Unit) {
        val body = response.body
        if (body == null) {
            done(false)
            return
        }
        val incognito = tab.incognito
        web.execute {
            val dir = File(tempDir(a), (if (incognito) "private/" else "web/") + idFor(response.uri).take(12))
            val file = File(dir, UrlUtils.sanitizeFileName(name).ifBlank { "document" })
            val ok = try {
                dir.mkdirs()
                body.use { input -> FileOutputStream(file).use { input.copyTo(it, 65536) } }
                true
            } catch (e: Exception) {
                L.w("Couldn't read the document from the web", e)
                file.delete()
                false
            }
            main.post {
                done(ok && !a.isFinishing && open(a, Uri.fromFile(file), name, mime, response.uri.takeIf { UrlUtils.isHttp(it) }, from = tab.takeIf { it in TabManager.tabs }, incognito = incognito))
            }
        }
    }

    private class Waiting(val id: String, val incognito: Boolean, val backTo: String?)

    private val waiting = ArrayList<Waiting>()
    private var trimmed = false

    /** The helper extension is ready: documents asked for meanwhile open now. */
    fun onViewerReady() {
        val list = waiting.toList()
        waiting.clear()
        main.post { list.forEach { reopen(it.id, null, it.incognito, it.backTo) } }
        if (!trimmed) {
            trimmed = true
            val ctx = SwiftApp.instance ?: return
            io.execute { trimTemp(ctx) }
        }
    }

    /**
     * Copies Swift no longer needs: documents from the web or out of ZIPs not opened for a week
     * (opening one marks it as used, see hello). Incognito copies never outlive their tabs.
     */
    private fun trimTemp(ctx: Context) {
        val old = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        File(tempDir(ctx), "private").deleteRecursively()
        tempDir(ctx).walkBottomUp().forEach { f ->
            if (f.isFile && f.lastModified() < old) f.delete()
            else if (f.isDirectory && f != tempDir(ctx) && f.list()?.isEmpty() == true) f.delete()
        }
    }

    /**
     * Opens a document Swift has seen before (Continue, Documents, a shortcut); its tab if it is open
     * already. [from]: the tab it was opened from (Back returns there); [backTo]: the screen Back
     * returns to otherwise (BACK_DOCUMENTS, BACK_DOWNLOADS, BACK_APP).
     */
    fun reopen(id: String, from: Tab? = null, incognito: Boolean = from?.incognito ?: false, backTo: String? = null): Boolean {
        val url = viewerUrl(id)
        if (url == null) {
            // Swift is still starting (opened from another app): the tab opens when the viewer can.
            if (waiting.none { it.id == id }) waiting += Waiting(id, incognito, backTo)
            return true
        }
        val open = TabManager.tabs.firstOrNull { it.url == url && it.incognito == incognito }
        if (open != null) {
            TabManager.select(open)
        } else {
            val tab = TabManager.newTab(url, parent = from?.takeIf { it.incognito == incognito }, incognito = incognito)
            tab.backTo = backTo
        }
        return true
    }

    /**
     * Clearing browsing data (2.6): the copies of documents from the web and ZIPs are deleted (open
     * ones say they're gone) and the shortcuts on Swift's icon follow the list.
     */
    fun clearCache(ctx: Context) {
        web.execute { tempDir(ctx).deleteRecursively() }
        io.execute { updateShortcuts(ctx) }
    }

    /** The last incognito tab closed: its documents are forgotten and their copies deleted. */
    fun onIncognitoEnded() {
        incognitoDocs.clear()
        val ctx = SwiftApp.instance ?: return
        web.execute { File(tempDir(ctx), "private").deleteRecursively() }
    }

    const val BACK_DOCUMENTS = "documents"
    const val BACK_DOWNLOADS = "downloads"
    const val BACK_APP = "app"

    /** Files inside Swift's own folders (its engine profile, cookies) are never shown to another app's request. */
    private fun readable(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = try {
                File(uri.path ?: return false).canonicalPath
            } catch (_: Exception) {
                return false
            }
            val own = File(ctx.applicationInfo.dataDir).canonicalPath
            val downloads = File(ctx.filesDir, "downloads").canonicalPath
            val temp = tempDir(ctx).canonicalPath
            if (path.startsWith(own) && !path.startsWith("$downloads/") && !path.startsWith("$temp/")) return false
            return File(path).canRead()
        }
        return uri.scheme == ContentResolver.SCHEME_CONTENT
    }

    /** The file's name and size, from the file or the app that has it. */
    fun describe(ctx: Context, uri: Uri, nameHint: String?): Pair<String, Long> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path.orEmpty())
            return (nameHint ?: f.name) to f.length()
        }
        var name = nameHint
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (name == null) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document") to size
    }

    fun idFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.take(10).joinToString("") { String.format("%02x", it) }
    }

    /**
     * A document's id in [profileId] (2.6): the same file opened in two profiles has two records, each
     * with its own place. The first profile keeps the ids of earlier versions (open tabs, shortcuts).
     */
    fun idFor(key: String, profileId: Long): String = if (profileId <= 1L) idFor(key) else idFor("$profileId|$key")

    /** A record for a document not opened yet (a shortcut, a star), so it can be opened by its id. */
    fun remember(ctx: Context, uri: Uri, name: String, kind: Kind, size: Long): String {
        val profile = Profiles.currentId
        val id = idFor(uri.toString(), profile)
        if (SwiftApp.db.document(id) == null) SwiftApp.db.saveDocument(id, uri.toString(), name, kind.key, size, null, profile)
        return id
    }

    // ------------------------------------------------------------------ the viewer's messages

    /** From the viewer page in [tab] (see docs/view.js). */
    fun onMessage(tab: Tab, msg: JSONObject, port: WebExtension.Port?) {
        val id = msg.optString("id")
        when (msg.optString("type")) {
            "doc-hello" -> if (port != null) io.execute { hello(id, port) }
            "doc-read" -> if (port != null) io.execute { sendWhole(id, port) }
            "doc-range" -> if (port != null) {
                val req = msg.optInt("req")
                val begin = msg.optLong("begin")
                val end = msg.optLong("end")
                io.execute { sendRange(id, req, begin, end, port) }
            }
            "doc-place" -> {
                if (tab.incognito) return
                val place = msg.optString("place")
                val progress = msg.optDouble("progress", 0.0).toFloat()
                val total = msg.optInt("total", 0)
                io.execute { SwiftApp.db.setDocumentPlace(id, place, progress, total) }
            }
            "doc-reading" -> (TabManager.ui as? MainActivity)?.setDocumentReading(tab, msg.optBoolean("on"), msg.optBoolean("auto"))
            "doc-zip" -> if (port != null) io.execute { listZip(id, port) }
            "doc-save" -> {
                val part = Saving(id, msg.optString("name"), msg.optString("mime"), msg.optInt("seq"), msg.optString("data"), msg.optBoolean("last"))
                io.execute { savePart(tab.profileId, part) }
            }
            "doc-action" -> main.post { act(tab, id, msg.optString("action"), msg) }
        }
    }

    private fun post(port: WebExtension.Port, msg: JSONObject) = main.post {
        try {
            port.postMessage(msg)
        } catch (e: Exception) {
            L.w("Document viewer went away", e)
        }
    }

    private fun hello(id: String, port: WebExtension.Port) {
        val doc = find(id)
        if (doc == null) {
            problem(port, "Swift doesn't have this document any more. Open it again from Documents.")
            return
        }
        val ctx = SwiftApp.instance ?: return
        val uri = Uri.parse(doc.uri)
        // The file as it is now: another app may have changed it since it was last opened.
        val size = try {
            descriptor(ctx, uri).use { it.statSize }
        } catch (e: Exception) {
            L.w("Can't open the document", e)
            problem(port, "Swift can't open this file now. It may have been moved or deleted, or the app it came from no longer shares it.")
            return
        }
        // A cached copy (from the web, a ZIP) counts as used now: it is kept a week from today.
        if (isTemp(ctx, uri)) File(uri.path.orEmpty()).setLastModified(System.currentTimeMillis())
        if (!doc.incognito) {
            SwiftApp.db.touchDocument(id)
            if (size > 0 && size != doc.size) SwiftApp.db.setDocumentSize(id, size)
        }
        // Not on the phone as a file of its own (from the web, a ZIP or another app): it can be kept.
        val copy = isTemp(ctx, uri) || uri.scheme == ContentResolver.SCHEME_CONTENT
        post(port, JSONObject().put("type", "doc-info").put("id", id).put("name", doc.name).put("kind", doc.kind.key)
            .put("size", if (size > 0) size else doc.size).put("ranges", size > 0).put("place", doc.place ?: "")
            .put("progress", doc.progress.toDouble()).put("web", doc.web != null).put("where", where(ctx, doc))
            .put("copy", copy).put("accentLight", Helper.accentColor(false)).put("accentDark", Helper.accentColor(true))
            .put("colorsLight", Helper.pageColors(false)).put("colorsDark", Helper.pageColors(true)))
        if (!doc.incognito) updateShortcuts(ctx)
    }

    private fun where(ctx: Context, doc: Doc): String {
        val uri = Uri.parse(doc.uri)
        return when {
            doc.incognito -> "Opened in an incognito tab (not kept)"
            isTemp(ctx, uri) && doc.web != null -> "From " + (UrlUtils.host(doc.web) ?: "the web") + " (not kept: Save a copy keeps one)"
            isTemp(ctx, uri) -> "From a ZIP file (not kept: Save a copy keeps one)"
            uri.scheme == ContentResolver.SCHEME_FILE -> File(uri.path.orEmpty()).parent.orEmpty()
            doc.web != null -> "From " + (UrlUtils.host(doc.web) ?: "the web")
            else -> "Shared by " + (uri.authority?.substringBefore(".provider")?.substringAfterLast('.') ?: "another app")
        }
    }

    private fun problem(port: WebExtension.Port, text: String) {
        post(port, JSONObject().put("type", "doc-problem").put("text", text)
            .put("actions", JSONArray().put(JSONObject().put("label", "Documents").put("action", "documents"))))
    }

    fun descriptor(ctx: Context, uri: Uri) = ctx.contentResolver.openFileDescriptor(uri, "r")
        ?: throw java.io.FileNotFoundException(uri.toString())

    /** The whole file, in pieces; at most a few pieces wait to be sent at a time. */
    private fun sendWhole(id: String, port: WebExtension.Port) {
        val ctx = SwiftApp.instance ?: return
        val doc = find(id) ?: return
        val inFlight = Semaphore(3)
        try {
            stream(ctx, Uri.parse(doc.uri)).use { input ->
                val buf = ByteArray(CHUNK)
                var total = 0L
                while (true) {
                    val n = readFully(input, buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_WHOLE) {
                        problem(port, "This file is too big for Swift's viewer (over ${MAX_WHOLE / 1_048_576} MB). Open it with another app.")
                        return
                    }
                    inFlight.acquire()
                    val data = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                    main.post {
                        try {
                            port.postMessage(JSONObject().put("type", "doc-data").put("data", data))
                        } catch (_: Exception) {
                        }
                        inFlight.release()
                    }
                }
            }
            post(port, JSONObject().put("type", "doc-data").put("last", true))
        } catch (e: Exception) {
            L.w("Couldn't read the document", e)
            problem(port, "Swift couldn't read this file.")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private fun stream(ctx: Context, uri: Uri): InputStream =
        if (uri.scheme == ContentResolver.SCHEME_FILE) FileInputStream(uri.path) else ctx.contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException(uri.toString())

    /** PDFs: the bytes [begin] to [end] pdf.js asked for (the viewer asks for 4 MB at most at a time). */
    private fun sendRange(id: String, req: Int, begin: Long, end: Long, port: WebExtension.Port) {
        val ctx = SwiftApp.instance ?: return
        val doc = find(id) ?: return
        val length = (end - begin).coerceIn(0, MAX_RANGE.toLong()).toInt()
        try {
            descriptor(ctx, Uri.parse(doc.uri)).use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val buf = ByteBuffer.allocate(length)
                    var pos = begin
                    while (buf.hasRemaining()) {
                        val n = ch.read(buf, pos)
                        if (n <= 0) break
                        pos += n
                    }
                    val data = Base64.encodeToString(buf.array(), 0, buf.position(), Base64.NO_WRAP)
                    post(port, JSONObject().put("type", "doc-range-data").put("req", req).put("data", data))
                }
            }
        } catch (e: Exception) {
            L.w("Couldn't read part of the PDF", e)
            // The page says so instead of waiting for ever.
            post(port, JSONObject().put("type", "doc-range-data").put("req", req).put("error", true))
        }
    }

    // ------------------------------------------------------------------ ZIP files (2.5)

    /** The code page each ZIP's names were read in (see listZip), for taking files out of it. */
    private val zipCharsets = java.util.concurrent.ConcurrentHashMap<String, Charset>()

    /** What a ZIP file holds (names and sizes), read by Swift: the file itself can be large. */
    private fun listZip(id: String, port: WebExtension.Port) {
        val ctx = SwiftApp.instance ?: return
        val doc = find(id) ?: return
        val reply = JSONObject().put("type", "doc-zip-list")
        val entries = JSONArray()
        // Most ZIPs name their files in UTF-8; older Windows ones in the DOS code page.
        for (charset in listOf(Charsets.UTF_8, Charset.forName("CP437"))) {
            try {
                ZipInputStream(stream(ctx, Uri.parse(doc.uri)), charset).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (entries.length() >= MAX_ZIP_ENTRIES) {
                            reply.put("truncated", true)
                            break
                        }
                        entries.put(JSONObject().put("name", e.name).put("size", e.size).put("kind", kindOf(e.name, null)?.key ?: JSONObject.NULL))
                    }
                }
                zipCharsets[id] = charset
                break
            } catch (e: IllegalArgumentException) {
                // A name that isn't UTF-8: read the list again in the other code page.
                while (entries.length() > 0) entries.remove(entries.length() - 1)
            } catch (e: Exception) {
                L.w("Couldn't read the ZIP file", e)
                reply.put("error", "Swift couldn't read this ZIP file. It may be damaged, or not a ZIP file.")
                break
            }
        }
        post(port, reply.put("entries", entries))
    }

    /** A document inside a ZIP: taken out into Swift's cache and opened in its own tab (Back returns to the ZIP). */
    private fun openEntry(a: Activity, tab: Tab, doc: Doc, entry: String) {
        io.execute {
            val dir = File(tempDir(a), (if (doc.incognito) "private/zip/" else "zip/") + doc.id)
            val name = entry.substringAfterLast('/').ifBlank { "document" }
            val file = File(dir, idFor(entry).take(6) + "-" + UrlUtils.sanitizeFileName(name))
            var found = false
            var tooBig = false
            val ok = try {
                dir.mkdirs()
                ZipInputStream(stream(a, Uri.parse(doc.uri)), zipCharsets[doc.id] ?: Charsets.UTF_8).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name != entry) continue
                        found = true
                        // A file that would unpack into more than the room left (or 300 MB) is refused.
                        val limit = minOf(MAX_ENTRY, a.cacheDir.usableSpace - 50L * 1_048_576)
                        FileOutputStream(file).use { out ->
                            val buf = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > limit) {
                                    tooBig = true
                                    break
                                }
                                out.write(buf, 0, n)
                            }
                        }
                        break
                    }
                }
                found && !tooBig
            } catch (e: Exception) {
                L.w("Couldn't take a file out of the ZIP", e)
                false
            }
            if (!ok) file.delete()
            main.post {
                if (a.isFinishing) return@post
                when {
                    tooBig -> com.swiftbrowser.ui.Messages.show(a, "This file is too big to take out of the ZIP here. Open the ZIP with another app.")
                    !ok || !open(a, Uri.fromFile(file), name, null, from = tab.takeIf { it in TabManager.tabs }, incognito = doc.incognito) ->
                        com.swiftbrowser.ui.Messages.show(a, "Swift couldn't open this file from the ZIP. It may be protected by a password.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ saving (2.5)

    private class Saving(val id: String, val name: String, val mime: String, val seq: Int, val data: String, val last: Boolean)

    /** A file the viewer made (a filled-in PDF), arriving in pieces: put together, then saved to Downloads. */
    private fun savePart(profileId: Long, part: Saving) {
        val ctx = SwiftApp.instance ?: return
        val dir = File(tempDir(ctx), "out").apply { mkdirs() }
        val file = File(dir, part.id + ".part")
        try {
            FileOutputStream(file, part.seq > 0).use { it.write(Base64.decode(part.data, Base64.NO_WRAP)) }
        } catch (e: Exception) {
            L.w("Couldn't save the document", e)
            file.delete()
            main.post { (TabManager.ui as? MainActivity)?.snack("Couldn't save the file") }
            return
        }
        if (!part.last) return
        val name = UrlUtils.sanitizeFileName(part.name).ifBlank { "document.pdf" }
        main.post { saveToDownloads(profileId, name, part.mime.ifBlank { "application/pdf" }, null, { out -> file.copyTo(out, true) }) { file.delete() } }
    }

    /** Puts a file in Downloads (asking for storage access once), then offers to open it. */
    private fun saveToDownloads(profileId: Long, name: String, mime: String, web: String?, write: (File) -> Unit, after: () -> Unit = {}) {
        val a = TabManager.ui as? MainActivity ?: return
        a.withStorage {
            Downloads.saveFile(profileId, name, mime, web, write = write) { item ->
                after()
                if (item == null) {
                    a.snack("Couldn't save the file")
                } else {
                    a.snack("Saved to Downloads", "Open") {
                        if (!open(a, Uri.fromFile(File(item.filePath)), item.fileName, item.mime)) a.openFileInTab(item.filePath)
                    }
                }
            }
        }
    }

    /** The document itself, kept in Downloads (it came from the web, a ZIP or another app). */
    private fun saveCopy(tab: Tab, doc: Doc) {
        val ctx = SwiftApp.instance ?: return
        val uri = Uri.parse(doc.uri)
        val mime = UrlUtils.mimeFromName(doc.name) ?: doc.kind.mimeTypes.first()
        saveToDownloads(tab.profileId, doc.name, mime, doc.web, { out ->
            stream(ctx, uri).use { input -> FileOutputStream(out).use { input.copyTo(it, 65536) } }
            // The kept copy is the document from now on (the cached one goes after a week).
            if (isTemp(ctx, uri)) SwiftApp.db.moveDocument(doc.id, Uri.fromFile(out).toString())
        })
    }

    /** Word, Excel, PowerPoint and the rest as a PDF, or printed (the page as the viewer shows it). */
    private fun printOrSave(a: MainActivity, tab: Tab, doc: Doc, print: Boolean) {
        if (print && doc.kind == Kind.PDF) {
            printFile(a, doc.name) { stream(a, Uri.parse(doc.uri)) }
            return
        }
        val session = tab.session ?: return
        val name = doc.name.substringBeforeLast('.', doc.name) + ".pdf"
        // The page's corners would show the viewer's own address and the time: left empty for documents.
        val blank = listOf("print.print_headerright", "print.print_footerright")
        val restore = { blank.forEach { GeckoPreferenceController.clearGeckoUserPref(it) } }
        GeckoPreferenceController.setGeckoPrefs(blank.map { SetGeckoPreference.setStringPref(it, "", GeckoPreferenceController.PREF_BRANCH_USER) })
            .accept({ _ -> makePdf(a, tab, doc, session, name, print, restore) }, { _ -> makePdf(a, tab, doc, session, name, print, restore) })
    }

    private fun makePdf(a: MainActivity, tab: Tab, doc: Doc, session: org.mozilla.geckoview.GeckoSession, name: String, print: Boolean, restore: () -> Unit) {
        session.saveAsPdf().accept({ stream ->
            restore()
            if (stream == null) {
                a.snack("Couldn't make a PDF of this document")
                return@accept
            }
            if (print) {
                io.execute {
                    val dir = File(tempDir(a), "print").apply { mkdirs() }
                    val file = File(dir, "print.pdf")
                    val ok = try {
                        stream.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
                        true
                    } catch (e: Exception) {
                        false
                    }
                    main.post { if (ok) printFile(a, name) { FileInputStream(file) } else a.snack("Couldn't print this document") }
                }
            } else {
                saveToDownloads(tab.profileId, name, "application/pdf", doc.web, { out -> stream.use { input -> out.outputStream().use { input.copyTo(it) } } })
            }
        }, { _ ->
            restore()
            a.snack("Couldn't make a PDF of this document")
        })
    }

    /** Android's print screen for a PDF (a printer, or Save as PDF). */
    private fun printFile(a: Activity, name: String, open: () -> InputStream) {
        val pm = a.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        try {
            pm.print(name, object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancel: CancellationSignal?,
                    callback: LayoutResultCallback, extras: Bundle?,
                ) {
                    if (cancel?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    callback.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
                }

                override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
                    try {
                        open().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }, null)
        } catch (e: Exception) {
            L.w("Printing failed", e)
            com.swiftbrowser.ui.Messages.show(a, "This phone can't print")
        }
    }

    // ------------------------------------------------------------------ home screen shortcuts (2.5)

    const val ACTION_OPEN_DOC = "com.swiftbrowser.action.OPEN_DOC"
    const val EXTRA_DOC_ID = "doc_id"

    private fun shortcutFor(ctx: Context, doc: Doc, shortId: String): ShortcutInfoCompat {
        val intent = Intent(ACTION_OPEN_DOC).setClass(ctx, MainActivity::class.java).putExtra(EXTRA_DOC_ID, doc.id)
        return ShortcutInfoCompat.Builder(ctx, shortId)
            .setShortLabel(doc.name.take(24).ifBlank { doc.kind.label })
            .setLongLabel(doc.name.take(60))
            .setIcon(IconCompat.createWithBitmap(shortcutIcon(ctx, doc.kind)))
            .setIntent(intent)
            .build()
    }

    /** The document's mark (its kind's colour and badge) on a white circle. */
    private fun shortcutIcon(ctx: Context, kind: Kind): Bitmap {
        val size = (48 * ctx.resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(96)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val inner = (size * 0.6f).toInt()
        val icon = DocIcon(kind, inner)
        val left = (size - inner) / 2
        icon.setBounds(left, left, left + inner, left + inner)
        icon.draw(c)
        return bmp
    }

    /** "Add to home screen" for a document: it opens straight in Swift's viewer. */
    fun pinShortcut(a: Activity, doc: Doc) {
        val ok = ShortcutManagerCompat.isRequestPinShortcutSupported(a) &&
            ShortcutManagerCompat.requestPinShortcut(a, shortcutFor(a, doc, "doc-" + doc.id), null)
        com.swiftbrowser.ui.Messages.show(a, if (ok) "Added to the home screen" else "This phone's home screen doesn't accept shortcuts")
    }

    /** The last documents read, among the shortcuts of Swift's icon (hold it on the home screen). */
    private fun updateShortcuts(ctx: Context) {
        try {
            val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx) - STATIC_SHORTCUTS).coerceIn(0, 2)
            val docs = SwiftApp.db.recentDocuments(Profiles.currentId, room)
            // Each document has its own id: one dragged to the home screen keeps opening that document.
            val list = docs.map { d -> shortcutFor(ctx, d, "recent-doc-" + d.id) }
            ShortcutManagerCompat.setDynamicShortcuts(ctx, list)
        } catch (e: Exception) {
            L.w("Couldn't update the shortcuts", e)
        }
    }

    // ------------------------------------------------------------------ the viewer's buttons

    private fun act(tab: Tab, id: String, action: String, msg: JSONObject) {
        val a = TabManager.ui as? MainActivity ?: return
        when (action) {
            "documents" -> a.openDocuments()
            "find" -> a.openFind()
            "exact" -> {
                val doc = find(id) ?: return
                val web = doc.web ?: return
                TabManager.newTab(EXACT_VIEW + Uri.encode(web), parent = tab, incognito = tab.incognito)
            }
            "share", "open-with", "save-copy", "print", "save-pdf", "shortcut", "open-entry" -> io.execute {
                val doc = find(id) ?: return@execute
                main.post {
                    when (action) {
                        "share", "open-with" -> handOver(a, doc, share = action == "share")
                        "save-copy" -> saveCopy(tab, doc)
                        "print" -> printOrSave(a, tab, doc, print = true)
                        "save-pdf" -> printOrSave(a, tab, doc, print = false)
                        "shortcut" -> if (doc.incognito) a.snack("Incognito documents can't go on the home screen") else pinShortcut(a, doc)
                        "open-entry" -> openEntry(a, tab, doc, msg.optString("entry"))
                    }
                }
            }
        }
    }

    /** Shares the file, or opens it in another app (Android's chooser). */
    fun handOver(a: Activity, doc: Doc, share: Boolean) {
        val uri = Uri.parse(doc.uri).let { u ->
            if (u.scheme == ContentResolver.SCHEME_FILE) shareUri(a, File(u.path.orEmpty())) else u
        }
        if (uri == null) {
            com.swiftbrowser.ui.Messages.show(a, "Swift couldn't hand this file to another app")
            return
        }
        val mime = UrlUtils.mimeFromName(doc.name) ?: doc.kind.mimeTypes.first()
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val chooser = Intent.createChooser(intent, if (share) "Share ${doc.name}" else "Open with")
            // Not Swift itself (it would just open the file here again).
            if (!share) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(a, MainActivity::class.java)))
            a.startActivity(chooser)
        } catch (e: Exception) {
            com.swiftbrowser.ui.Messages.show(a, "No app on this phone can open this file")
        }
    }

    /**
     * A content:// link another app may read, for one of Swift's files (2.6). Android refuses
     * file:// links; a file in no folder Swift shares from (an SD card's other folders) is copied
     * into the cache first. Null when even that fails.
     */
    fun shareUri(ctx: Context, file: File): Uri? {
        val authority = ctx.packageName + ".files"
        try {
            return FileProvider.getUriForFile(ctx, authority, file)
        } catch (_: Exception) {
        }
        // A copy only for files of a sensible size (a film on a memory card isn't copied).
        if (file.length() > 100L * 1_048_576) return null
        return try {
            val dir = File(tempDir(ctx), "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val copy = File(dir, file.name)
            file.copyTo(copy, overwrite = true)
            FileProvider.getUriForFile(ctx, authority, copy)
        } catch (e: Exception) {
            L.w("Couldn't share the file", e)
            null
        }
    }

    private const val CHUNK = 262_144
    private const val MAX_RANGE = 4 * 1_048_576
    private const val MAX_WHOLE = 60L * 1_048_576
    private const val MAX_ZIP_ENTRIES = 2000
    private const val MAX_ENTRY = 300L * 1_048_576

    /** new_tab, incognito and scan in res/xml/shortcuts.xml. */
    private const val STATIC_SHORTCUTS = 3

    /** Microsoft's free viewer for Office files on the web (the link is sent to Microsoft). */
    const val EXACT_VIEW = "https://view.officeapps.live.com/op/view.aspx?src="
}
