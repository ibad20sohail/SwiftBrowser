package com.swiftbrowser.docs

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Documents (2.4): PDF, Word, Excel, PowerPoint, e-books and text files open in Swift's own viewer
 * (the helper extension's docs/view.html), from the phone, other apps and downloads. Swift reads the
 * file and hands it to the viewer over its private port: PDFs piece by piece as pdf.js asks, the
 * rest whole. Nothing is uploaded. Each document opened is remembered (the documents table) with
 * where it was left, for the home page's Continue and for reopening after Swift restarts.
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
        TEXT("text", "Text", 0xFF5F6368.toInt(), "txt log md markdown json xml ini cfg conf yaml yml srt vtt sub",
            "text/plain text/markdown application/json text/xml");

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
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The viewer page for [id] (null until the helper extension is ready). */
    fun viewerUrl(id: String): String? = Helper.extension?.metaData?.baseUrl?.let { base ->
        (if (base.endsWith("/")) base else "$base/") + "docs/view.html#" + id
    }

    fun isViewerUrl(url: String?): Boolean = url != null && url.startsWith("moz-extension://") && url.contains("/docs/view.html")

    /** The document a viewer tab shows. */
    fun docOf(url: String?): Doc? = if (isViewerUrl(url)) SwiftApp.db.document(url!!.substringAfter('#')) else null

    /**
     * Opens [uri] in a new tab, in Swift's viewer. [web]: the page link it was downloaded from (for
     * "Exact view (online)"). Says so and returns false when Swift can't show it.
     */
    fun open(ctx: Context, uri: Uri, nameHint: String? = null, mimeHint: String? = null, web: String? = null): Boolean {
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
        val id = idFor(key)
        val profile = Profiles.currentId
        io.execute { SwiftApp.db.saveDocument(id, key, name, kind.key, size, web, profile) }
        L.d("Opening document ${kind.key} ($size bytes)")
        val url = viewerUrl(id)
        if (url == null) {
            // Swift is still starting (opened from another app): the tab opens when the viewer can.
            waiting += id
            return true
        }
        TabManager.newTab(url, incognito = false)
        return true
    }

    private val waiting = ArrayList<String>()

    /** The helper extension is ready: documents asked for meanwhile open now. */
    fun onViewerReady() {
        val ids = waiting.toList()
        waiting.clear()
        main.post { ids.forEach { reopen(it) } }
    }

    /** Opens a document Swift has seen before (Continue, Documents' recent list). */
    fun reopen(id: String): Boolean {
        val url = viewerUrl(id) ?: return false
        TabManager.newTab(url, incognito = false)
        return true
    }

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
            if (path.startsWith(own) && !path.startsWith(downloads)) return false
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

    private fun idFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.take(10).joinToString("") { String.format("%02x", it) }
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
            "doc-action" -> main.post { act(id, msg.optString("action")) }
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
        val doc = SwiftApp.db.document(id)
        if (doc == null) {
            problem(port, "Swift doesn't have this document any more. Open it again from Documents.")
            return
        }
        val ctx = SwiftApp.instance ?: return
        val uri = Uri.parse(doc.uri)
        val seekable = try {
            descriptor(ctx, uri).use { it.statSize > 0 }
        } catch (e: Exception) {
            L.w("Can't open the document", e)
            problem(port, "Swift can't open this file now. It may have been moved or deleted, or the app it came from no longer shares it.")
            return
        }
        SwiftApp.db.touchDocument(id)
        post(port, JSONObject().put("type", "doc-info").put("id", id).put("name", doc.name).put("kind", doc.kind.key)
            .put("size", doc.size).put("ranges", seekable && doc.size > 0).put("place", doc.place ?: "")
            .put("progress", doc.progress.toDouble()).put("web", doc.web != null).put("where", where(doc))
            .put("accentLight", Helper.accentColor(false)).put("accentDark", Helper.accentColor(true)))
    }

    private fun where(doc: Doc): String {
        val uri = Uri.parse(doc.uri)
        return when {
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
        val doc = SwiftApp.db.document(id) ?: return
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

    /** PDFs: the bytes [begin] to [end] pdf.js asked for. */
    private fun sendRange(id: String, req: Int, begin: Long, end: Long, port: WebExtension.Port) {
        val ctx = SwiftApp.instance ?: return
        val doc = SwiftApp.db.document(id) ?: return
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
        }
    }

    // ------------------------------------------------------------------ the viewer's buttons

    private fun act(id: String, action: String) {
        val a = TabManager.ui as? com.swiftbrowser.ui.MainActivity ?: return
        when (action) {
            "documents" -> a.openDocuments()
            "find" -> a.openFind()
            "exact" -> {
                val doc = SwiftApp.db.document(id) ?: return
                val web = doc.web ?: return
                TabManager.newTab(EXACT_VIEW + Uri.encode(web), incognito = false)
            }
            "share", "open-with" -> io.execute {
                val doc = SwiftApp.db.document(id) ?: return@execute
                main.post { handOver(a, doc, share = action == "share") }
            }
        }
    }

    /** Shares the file, or opens it in another app (Android's chooser). */
    fun handOver(a: android.app.Activity, doc: Doc, share: Boolean) {
        val uri = Uri.parse(doc.uri).let { u ->
            if (u.scheme == ContentResolver.SCHEME_FILE) {
                try {
                    FileProvider.getUriForFile(a, a.packageName + ".files", File(u.path.orEmpty()))
                } catch (_: Exception) {
                    u
                }
            } else {
                u
            }
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
            if (!share) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(a, com.swiftbrowser.ui.MainActivity::class.java)))
            a.startActivity(chooser)
        } catch (e: Exception) {
            com.swiftbrowser.ui.Messages.show(a, "No app on the phone can open this file")
        }
    }

    private const val CHUNK = 262_144
    private const val MAX_RANGE = 4 * 1_048_576
    private const val MAX_WHOLE = 60L * 1_048_576

    /** Microsoft's free viewer for Office files on the web (the link is sent to Microsoft). */
    const val EXACT_VIEW = "https://view.officeapps.live.com/op/view.aspx?src="
}
