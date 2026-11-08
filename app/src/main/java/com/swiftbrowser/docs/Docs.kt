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
import com.swiftbrowser.core.AppFiles
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 *
 * 3.0: a viewer page is answered only about its own document (see DocRules); Swift's own private
 * files open only from Swift itself, and are forgotten when Private locks; old Office files are laid
 * out each on a thread of its own; ZIPs are listed from their central directory (ZipIndex); long
 * reads stop when the viewer goes; documents from the web show their progress and can be stopped.
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

    /**
     * 3.0: the long jobs (a whole file, a ZIP's list, a file taken out of a ZIP, a copy to print) off
     * [io], which meanwhile answers hello and the pieces a PDF asks for at once.
     */
    private val bulk = com.swiftbrowser.core.Background.pool("docs-bulk", 2)

    /** 3.0: the pieces of a file the viewer saves, one after another in their order. */
    private val saving = com.swiftbrowser.core.Background.single("docs-save")

    /** Documents being copied from the web (2.6): their own thread, so open viewers keep reading meanwhile. */
    private val web = com.swiftbrowser.core.Background.single("docs-web")
    private val main = Handler(Looper.getMainLooper())

    /** Documents opened in incognito tabs, gone when the last incognito tab closes. */
    private val incognitoDocs = java.util.concurrent.ConcurrentHashMap<String, Doc>()

    /**
     * 3.0: the documents of Files' Private folder open now (incognito ones), and those taken out of
     * its ZIPs: forgotten when Private locks ([forgetPrivate]), their screens kept out of screenshots.
     */
    private val privateIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether document [id] is one of Private's (see [privateIds]). */
    fun isPrivate(id: String?): Boolean = id != null && id in privateIds

    /** A link to a file in Private, as Files hands them to the viewer (content://<package>.files/private/…). */
    private fun isPrivateLink(ctx: Context, uri: Uri): Boolean =
        AppFiles.isOwnProvider(ctx, uri) && uri.pathSegments.firstOrNull() == "private"

    /**
     * 3.0: Private locked (PrivateFolder.lock calls this): its documents are forgotten, so opening one
     * again needs Private open; what was taken out of its ZIPs is deleted, and screens still showing
     * one close.
     */
    fun forgetPrivate() {
        val ids = privateIds.toSet()
        if (ids.isEmpty()) return
        privateIds.removeAll(ids)
        ids.forEach { incognitoDocs.remove(it) }
        SwiftApp.instance?.let { ctx -> web.execute { ids.forEach { File(tempDir(ctx), "private/zip/$it").deleteRecursively() } } }
        main.post { com.swiftbrowser.ui.DocumentActivity.closeShowing(ids) }
        L.d("Private locked: ${ids.size} document(s) of it closed")
    }

    /**
     * The document with [id]: an incognito one in memory, or one in the documents table. 3.0: never a
     * recent one in Private (2.9 kept those opened through another app's "Open with"): Private's
     * files open only from Files, unlocked.
     */
    fun find(id: String): Doc? = incognitoDocs[id] ?: SwiftApp.db.document(id)?.takeUnless { d ->
        SwiftApp.instance?.let { isPrivateLink(it, Uri.parse(d.uri)) } == true
    }

    /** The viewer page for [id] (null until the helper extension is ready). */
    fun viewerUrl(id: String): String? = Helper.extension?.metaData?.baseUrl?.let { base ->
        (if (base.endsWith("/")) base else "$base/") + "docs/view.html#" + id
    }

    /**
     * A document whose file is no longer there (2.9): deleted or moved elsewhere, or its cached copy
     * expired. Recent lists drop it. Another app's document (content://) can't be checked cheaply.
     */
    fun isGone(uri: String): Boolean = Uri.parse(uri).let { it.scheme == ContentResolver.SCHEME_FILE && !File(it.path.orEmpty()).exists() }

    /**
     * 3.0.3: [doc] was read straight from a link without saving it: its copy waits in Swift's cache
     * ([openFromWeb]'s web/ folder). Those stay in the browser's Continue; the rest are Files'.
     */
    fun isWebCopy(doc: Doc): Boolean {
        val ctx = SwiftApp.instance ?: return false
        val u = Uri.parse(doc.uri)
        return u.scheme == ContentResolver.SCHEME_FILE && u.path.orEmpty().startsWith(File(tempDir(ctx), "web").path + "/")
    }

    fun isViewerUrl(url: String?): Boolean = url != null && url.startsWith("moz-extension://") && url.contains("/docs/view.html")

    /** The document a viewer tab shows. */
    fun docOf(url: String?): Doc? = if (isViewerUrl(url)) find(url!!.substringAfter('#')) else null

    /** Where documents opened without keeping a copy (from the web, out of a ZIP) wait, for a week at most. */
    private fun tempDir(ctx: Context) = File(ctx.cacheDir, "docs")

    private fun isTemp(ctx: Context, uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_FILE && uri.path.orEmpty().startsWith(tempDir(ctx).path + "/")

    /**
     * Opens [uri] in Swift's viewer, on its own screen over [ctx] (2.8: not in a tab). [web]: the page
     * link it was downloaded from (for "Exact view (online)"). Returns false when Swift can't show it.
     * [from] and [backTo] are no longer needed: Back returns to the screen it was opened from.
     * [secure] (3.0): the screen stays out of screenshots (a file of Files' Private folder).
     */
    @Suppress("UNUSED_PARAMETER")
    fun open(
        ctx: Context, uri: Uri, nameHint: String? = null, mimeHint: String? = null, web: String? = null,
        from: Tab? = null, incognito: Boolean = from?.incognito ?: false, backTo: String? = null, secure: Boolean = false,
    ): Boolean {
        val id = registerOwn(ctx, uri, nameHint, mimeHint, web, incognito) ?: return false
        com.swiftbrowser.ui.DocumentActivity.open(ctx, id, incognito, secure = secure || isPrivate(id))
        return true
    }

    /**
     * Remembers [uri] as a document (in Documents, unless incognito) and gives its id; null when
     * Swift can't show it (2.8: split from [open], for the viewer screen opened by other apps).
     * 3.0: this is for what other apps send (their "Open with", a link sent to the browser), so never
     * one of Swift's own files: its data folder (cached copies of web documents, incognito ones too)
     * and its own provider's links (Private's files) are refused (AppFiles.refusedFromOutside). Swift's
     * own screens open those through [open].
     */
    fun register(ctx: Context, uri: Uri, nameHint: String? = null, mimeHint: String? = null, web: String? = null, incognito: Boolean = false): String? {
        if (AppFiles.refusedFromOutside(ctx, uri)) {
            L.w("Another app asked to show one of Swift's own files: refused")
            return null
        }
        return registerOwn(ctx, uri, nameHint, mimeHint, web, incognito)
    }

    /** [register] for Swift's own screens, which may open its own files. */
    private fun registerOwn(ctx: Context, uri: Uri, nameHint: String? = null, mimeHint: String? = null, web: String? = null, incognito: Boolean = false): String? {
        // 3.0: Private's files are opened only as incognito documents (never in the recent ones).
        if (!incognito && isPrivateLink(ctx, uri)) return null
        if (!readable(ctx, uri)) return null
        val (name, size) = describe(ctx, uri, nameHint)
        val mime = mimeHint ?: if (uri.scheme == ContentResolver.SCHEME_CONTENT) ctx.contentResolver.getType(uri) else null
        val kind = kindOf(name, mime) ?: return null
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
            if (isPrivateLink(ctx, uri)) privateIds += id
        } else {
            id = idFor(key, profile)
            io.execute { SwiftApp.db.saveDocument(id, key, name, kind.key, size, web, profile) }
        }
        L.d("Opening document ${kind.key} ($size bytes)")
        return id
    }

    /**
     * A document link on a website (2.5): read into Swift's cache and shown, without a copy in
     * Downloads. The viewer's Save a copy keeps one.
     *
     * 3.0: one too big for the viewer (60 MB, a PDF 300 MB) or for the room left on the phone is
     * refused before it is read, or as soon as it gets there; the progress shows with Cancel.
     * [done]: true once it is shown or Swift said why not, false when it couldn't be read; not
     * called when it is cancelled.
     */
    fun openFromWeb(a: Activity, tab: Tab, response: WebResponse, name: String, mime: String?, done: (Boolean) -> Unit) {
        val body = response.body
        if (body == null) {
            done(false)
            return
        }
        val incognito = tab.incognito
        val limit = if (kindOf(name, mime) == Kind.PDF) MAX_ENTRY else MAX_WHOLE
        val length = response.headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }?.value?.trim()?.toLongOrNull() ?: -1L
        val room = a.cacheDir.usableSpace - FREE_MARGIN
        val refusal = when {
            length > limit -> tooBigText(limit)
            length > 0 && length > room -> NO_ROOM
            else -> null
        }
        if (refusal != null) {
            closeQuietly(body)
            com.swiftbrowser.ui.Messages.show(a, refusal)
            done(true)
            return
        }
        val cancelled = AtomicBoolean(false)
        val progress = com.swiftbrowser.ui.Messages.sticky(a, "Opening $name…", "Cancel") {
            cancelled.set(true)
            // A read waiting for the network ends when its stream closes (not on the main thread).
            Thread { closeQuietly(body) }.start()
        }
        web.execute {
            val dir = File(tempDir(a), (if (incognito) "private/" else "web/") + idFor(response.uri).take(12))
            val file = File(dir, UrlUtils.sanitizeFileName(name).ifBlank { "document" })
            var problem: String? = null
            val ok = try {
                dir.mkdirs()
                body.use { input ->
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(65536)
                        var total = 0L
                        var shown = 0L
                        while (!cancelled.get()) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > limit) problem = tooBigText(limit)
                            else if (total > room) problem = NO_ROOM
                            if (problem != null) break
                            out.write(buf, 0, n)
                            if (length > 0 && total - shown >= 262_144) {
                                shown = total
                                val f = total.toFloat() / length
                                main.post {
                                    progress?.setProgress(f)
                                    progress?.setText("Opening $name… ${(f * 100).toInt().coerceIn(0, 99)}%")
                                }
                            }
                        }
                    }
                }
                problem == null && !cancelled.get()
            } catch (e: Exception) {
                if (!cancelled.get()) L.w("Couldn't read the document from the web (${e.javaClass.simpleName})")
                false
            }
            if (!ok) file.delete()
            main.post {
                progress?.dismiss()
                when {
                    cancelled.get() -> {}
                    problem != null -> {
                        if (!a.isFinishing) com.swiftbrowser.ui.Messages.show(a, problem!!)
                        done(true)
                    }
                    else -> done(ok && !a.isFinishing && open(a, Uri.fromFile(file), name, mime, response.uri.takeIf { UrlUtils.isHttp(it) }, from = tab.takeIf { it in TabManager.tabs }, incognito = incognito))
                }
            }
        }
    }

    private fun tooBigText(limit: Long) =
        "This document is too big for Swift's viewer (over ${limit / 1_048_576} MB). Download it instead, and open it with another app."

    private const val NO_ROOM = "There isn't enough free space on the phone to open this document. Free up some space, or download it instead."

    private fun closeQuietly(s: java.io.Closeable) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }

    /** What waits for the viewer page to exist (Swift still starting, opened from another app). */
    private val waiting = ArrayList<() -> Unit>()
    private var trimmed = false

    /** [action] once the viewer page can be loaded: at once if it can already. */
    fun whenViewerReady(action: () -> Unit) {
        if (Helper.extension != null) action() else waiting += action
    }

    /** The helper extension is ready: documents asked for meanwhile open now. */
    fun onViewerReady() {
        main.post {
            val list = waiting.toList()
            waiting.clear()
            list.forEach { it() }
        }
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
        // 3.0: Private's files kept among the recent documents by 2.9 (see find) leave the lists too.
        try {
            SwiftApp.db.recentDocuments(Profiles.currentId, 200).filter { isPrivateLink(ctx, Uri.parse(it.uri)) }
                .forEach { SwiftApp.db.forgetDocumentUri(it.uri) }
        } catch (e: Exception) {
            L.w("Couldn't tidy the recent documents (${e.javaClass.simpleName})")
        }
    }

    /**
     * Opens a document Swift has seen before (Continue, Documents, a shortcut) on the viewer's screen
     * over [ctx] (2.8). [leaveSwift]: Back goes out of Swift (a home screen shortcut).
     */
    fun reopen(ctx: Context, id: String, incognito: Boolean = false, leaveSwift: Boolean = false): Boolean {
        com.swiftbrowser.ui.DocumentActivity.open(ctx, id, incognito, leaveSwift, secure = isPrivate(id))
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
    /** 2.7: opened from the Files screen (FilesActivity), which Back returns to. */
    const val BACK_FILES = "files"
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

    /**
     * From the viewer page in [tab] (see docs/view.js). [senderUrl]: the page's address as the engine
     * reports it (Helper takes these messages only from the helper's viewer page).
     */
    fun onMessage(tab: Tab, msg: JSONObject, port: WebExtension.Port?, senderUrl: String? = null) {
        val id = msg.optString("id")
        // 3.0: a viewer page reads, saves and acts only on the document it was opened with (its id is
        // in its address), never on another one it names. This also keeps ids to their 20 hex digits.
        if (!DocRules.ownDocument(id, senderUrl, com.swiftbrowser.ui.DocumentActivity.docIdOf(tab) ?: DocRules.idInUrl(tab.url))) {
            L.w("The document viewer asked for another document: refused")
            return
        }
        when (msg.optString("type")) {
            "doc-hello" -> if (port != null) io.execute { hello(id, port) }
            "doc-read" -> if (port != null) bulk.execute { sendWhole(id, port) }
            "doc-range" -> if (port != null) {
                val req = msg.optInt("req")
                val begin = msg.optLong("begin")
                val end = msg.optLong("end")
                io.execute { sendRange(id, req, begin, end, port) }
            }
            "doc-place" -> {
                if (tab.incognito) return
                val place = msg.optString("place").take(2000)
                val progress = msg.optDouble("progress", 0.0).toFloat()
                val total = msg.optInt("total", 0)
                io.execute { SwiftApp.db.setDocumentPlace(id, place, progress, total) }
            }
            "doc-reading" -> (TabManager.ui as? MainActivity)?.setDocumentReading(tab, msg.optBoolean("on"), msg.optBoolean("auto"))
            "doc-zip" -> if (port != null) bulk.execute { listZip(id, port) }
            "doc-convert" -> if (port != null) convertOld(id, port)
            "doc-save" -> {
                val part = Saving(id, msg.optString("name"), msg.optInt("seq"), msg.optString("data"), msg.optBoolean("last"))
                saving.execute { savePart(tab, part) }
            }
            "doc-action" -> main.post { act(tab, id, msg.optString("action"), msg) }
            // 3.0: the first page of a PDF as the viewer drew it, for its small picture in Files and Continue.
            "doc-thumb" -> if (!tab.incognito) {
                val data = msg.optString("data")
                bulk.execute {
                    val doc = find(id)?.takeIf { it.kind == Kind.PDF && !it.incognito } ?: return@execute
                    SwiftApp.instance?.let { DocThumbs.fromViewer(it, doc.uri, data) }
                }
            }
        }
    }

    /** 3.0: viewer pages' ports that closed (the page went): what is being sent to them stops. */
    private val closedPorts: MutableSet<WebExtension.Port> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))

    /** A port of the helper extension closed (Helper tells). */
    fun onPortClosed(port: WebExtension.Port) {
        closedPorts += port
    }

    private fun isClosed(port: WebExtension.Port) = port in closedPorts

    /**
     * Old Word and PowerPoint files being laid out (2.8). 3.0: each on a thread of its own. POI 5.0.0
     * can go round in circles or swell on a damaged file; the viewer gives up after 45 seconds and
     * shows the text alone, and the stuck thread is left to end by itself (Java can't stop it
     * safely) without holding up the next old file opened. With a few stuck at once, files get their
     * text alone straight away.
     */
    private val converting = AtomicInteger()

    /**
     * An old Word or PowerPoint file as [OldOffice] lays it out (pages, slides, their pictures), sent
     * to the viewer in pieces (2.8). A password, or a file it can't read, is said instead; the viewer
     * then shows the text alone. 3.0: a file that is really another kind (RTF, a web page, a newer
     * Office file) says which, and the viewer shows it as that.
     */
    private fun convertOld(id: String, port: WebExtension.Port) {
        if (converting.get() >= MAX_CONVERTING) {
            L.w("Old Office files: ${converting.get()} still being laid out, this one gets its text alone")
            post(port, JSONObject().put("type", "doc-html").put("error", "busy"))
            return
        }
        converting.incrementAndGet()
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                layOut(id, port)
            } finally {
                converting.decrementAndGet()
            }
        }, "docs-convert").apply { isDaemon = true }.start()
    }

    private fun layOut(id: String, port: WebExtension.Port) {
        val doc = find(id)
        val ctx = SwiftApp.instance
        if (doc == null || ctx == null) {
            post(port, JSONObject().put("type", "doc-html").put("error", "gone"))
            return
        }
        val uri = Uri.parse(doc.uri)
        var copy: File? = null
        try {
            // The converter reads a file: another app's document is copied into the cache first
            // (3.0: no further than the converter reads, OldOffice.MAX_FILE).
            val file = if (uri.scheme == ContentResolver.SCHEME_FILE) {
                File(uri.path.orEmpty())
            } else {
                File(File(tempDir(ctx), "convert").apply { mkdirs() }, id.take(16)).also { f ->
                    copy = f
                    stream(ctx, uri).use { input -> FileOutputStream(f).use { copyCapped(input, it, OldOffice.MAX_FILE) } }
                }
            }
            val r = try {
                if (doc.kind == Kind.OLD_SLIDES) OldOffice.slides(file) else OldOffice.word(file)
            } catch (e: OldOffice.WrongFormat) {
                // 3.0: a Word file named .ppt (or a PowerPoint file named .doc) is laid out as what it is.
                when {
                    e.looksLike == "doc" && doc.kind == Kind.OLD_SLIDES -> OldOffice.word(file)
                    e.looksLike == "ppt" && doc.kind == Kind.OLD_WORD -> OldOffice.slides(file)
                    else -> throw e
                }
            }
            L.d("Laid out an old ${doc.kind.label} file (${r.pages} pages, ${r.html.length / 1024} KB)")
            val html = r.html
            var from = 0
            do {
                if (isClosed(port)) return
                val to = minOf(html.length, from + CHUNK)
                val msg = JSONObject().put("type", "doc-html").put("data", html.substring(from, to)).put("last", to >= html.length)
                if (to >= html.length && r.note != null) msg.put("note", r.note)
                post(port, msg)
                from = to
            } while (from < html.length)
        } catch (e: OldOffice.PasswordProtected) {
            post(port, JSONObject().put("type", "doc-html").put("error", "password"))
        } catch (e: OldOffice.WrongFormat) {
            L.d("An old Office file is really ${e.looksLike ?: "something else"}")
            post(port, JSONObject().put("type", "doc-html").put("error", "format").put("looksLike", e.looksLike ?: JSONObject.NULL))
        } catch (e: Exception) {
            L.w("Couldn't lay out an old Office file (${e.javaClass.simpleName})")
            post(port, JSONObject().put("type", "doc-html").put("error", "convert"))
        } finally {
            copy?.delete()
        }
    }

    /** Copies at most [max] bytes; one byte more and it throws (a file bigger than what reads it). */
    private fun copyCapped(input: InputStream, out: OutputStream, max: Long) {
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            total += n
            if (total > max) throw IOException("Over ${max / 1_048_576} MB")
            out.write(buf, 0, n)
        }
    }

    private fun post(port: WebExtension.Port, msg: JSONObject) = main.post {
        if (isClosed(port)) return@post
        try {
            port.postMessage(msg)
        } catch (e: Exception) {
            L.w("Document viewer went away (${e.javaClass.simpleName})")
        }
    }

    private fun hello(id: String, port: WebExtension.Port) {
        val doc = find(id)
        if (doc == null) {
            problem(port, "Swift doesn't have this document any more. Open it again from Files.")
            return
        }
        val ctx = SwiftApp.instance ?: return
        val uri = Uri.parse(doc.uri)
        // The file as it is now: another app may have changed it since it was last opened.
        val size = try {
            descriptor(ctx, uri).use { it.statSize }
        } catch (e: Exception) {
            // 3.0: the exception's message names the file: only its kind goes in the log.
            L.w("Can't open the document (${e.javaClass.simpleName})")
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
            .put("colorsLight", viewerColors(ctx, false)).put("colorsDark", viewerColors(ctx, true))
            // 3.0.5: the viewer's bars and messages take Swift's font, or the phone's if it couldn't be read.
            .put("swiftFont", com.swiftbrowser.ui.AppFont.active)
            // 3.0: what the details panel says it is; incognito ones keep nothing (e-book pages);
            // a PDF without its small picture yet gets one drawn by the viewer (DocThumbs).
            .put("label", doc.kind.label).put("incognito", doc.incognito)
            .put("thumb", doc.kind == Kind.PDF && !doc.incognito && !DocThumbs.hasViewerPicture(ctx, doc.uri)))
        if (!doc.incognito) updateShortcuts(ctx)
    }

    /** Swift's page colours, and what only the viewer's own look needs: the page behind documents, raised parts and the accent's soft colours. */
    private fun viewerColors(ctx: Context, night: Boolean): JSONObject {
        fun hex(res: Int) = String.format("#%06x", com.swiftbrowser.ui.Palette.colorFor(ctx, night, res) and 0xFFFFFF)
        val accent = com.swiftbrowser.ui.Looks.accent
        return Helper.pageColors(night)
            .put("backdrop", hex(com.swiftbrowser.R.color.settings_bg))
            .put("surfaceAlt", hex(com.swiftbrowser.R.color.surface_alt))
            .put("accentSoft", hex(accent.container))
            .put("onAccentSoft", hex(accent.onContainer))
    }

    private fun where(ctx: Context, doc: Doc): String {
        val uri = Uri.parse(doc.uri)
        return when {
            // 3.0: Private's documents open as incognito ones, but from Files.
            isPrivate(doc.id) -> "In Files' Private folder (not added to recent documents)"
            doc.incognito -> "Opened in an incognito tab (not kept)"
            isTemp(ctx, uri) && doc.web != null -> "From " + (UrlUtils.host(doc.web) ?: "the web") + " (not kept: Save a copy keeps one)"
            isTemp(ctx, uri) -> "From a ZIP file (not kept: Save a copy keeps one)"
            uri.scheme == ContentResolver.SCHEME_FILE -> File(uri.path.orEmpty()).parent.orEmpty()
            doc.web != null -> "From " + (UrlUtils.host(doc.web) ?: "the web")
            else -> "Shared by " + (uri.authority?.substringBefore(".provider")?.substringAfterLast('.') ?: "another app")
        }
    }

    private fun problem(port: WebExtension.Port, text: String) {
        // 2.9: documents are found in Files (the "documents" action opens it).
        post(port, JSONObject().put("type", "doc-problem").put("text", text)
            .put("actions", JSONArray().put(JSONObject().put("label", "Files").put("action", "documents"))))
    }

    fun descriptor(ctx: Context, uri: Uri) = ctx.contentResolver.openFileDescriptor(uri, "r")
        ?: throw java.io.FileNotFoundException(uri.toString())

    /**
     * The whole file, in pieces; at most a few pieces wait to be sent at a time. 3.0: a file known to
     * be too big is refused before anything is sent, and the reading stops when the viewer goes.
     */
    private fun sendWhole(id: String, port: WebExtension.Port) {
        val ctx = SwiftApp.instance ?: return
        val doc = find(id) ?: return
        val uri = Uri.parse(doc.uri)
        val known = try {
            descriptor(ctx, uri).use { it.statSize }
        } catch (_: Exception) {
            -1L
        }
        if (known > MAX_WHOLE) {
            problem(port, tooBigForViewer())
            return
        }
        val inFlight = Semaphore(3)
        try {
            stream(ctx, uri).use { input ->
                val buf = ByteArray(CHUNK)
                var total = 0L
                while (true) {
                    if (isClosed(port)) return
                    val n = readFully(input, buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_WHOLE) {
                        problem(port, tooBigForViewer())
                        return
                    }
                    inFlight.acquire()
                    val data = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                    main.post {
                        try {
                            if (!isClosed(port)) port.postMessage(JSONObject().put("type", "doc-data").put("data", data))
                        } catch (_: Exception) {
                        }
                        inFlight.release()
                    }
                }
            }
            post(port, JSONObject().put("type", "doc-data").put("last", true))
        } catch (e: Exception) {
            L.w("Couldn't read the document (${e.javaClass.simpleName})")
            problem(port, "Swift couldn't read this file.")
        }
    }

    private fun tooBigForViewer() = "This file is too big for Swift's viewer (over ${MAX_WHOLE / 1_048_576} MB). Open it with another app."

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
        if (isClosed(port) || begin < 0) return
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
            L.w("Couldn't read part of the PDF (${e.javaClass.simpleName})")
            // The page says so instead of waiting for ever.
            post(port, JSONObject().put("type", "doc-range-data").put("req", req).put("error", true))
        }
    }

    // ------------------------------------------------------------------ ZIP files (2.5)

    /** The code page each ZIP's names were read in (see listZip), for taking files out of it. */
    private val zipCharsets = java.util.concurrent.ConcurrentHashMap<String, Charset>()

    /**
     * [read] on [doc]'s ZIP where it is (3.0): its own bytes at any place, which needs a real file
     * (one on the phone, or one another app shares as such). Throws when it can't be read that way.
     */
    private fun <T> withZip(ctx: Context, doc: Doc, read: (ZipIndex.Source) -> T): T =
        descriptor(ctx, Uri.parse(doc.uri)).use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch -> read(ZipIndex.ChannelSource(ch)) }
        }

    /** A ZipInputStream's way of saying an entry is locked with a password. */
    private fun lockedEntry(e: Exception) = e is java.util.zip.ZipException && e.message.orEmpty().contains("encrypted", ignoreCase = true)

    /**
     * What a ZIP file holds (names and sizes), read by Swift: the file itself can be large. 3.0: from
     * its central directory (ZipIndex), which lists a big ZIP at once and knows the files locked with
     * a password. One that can't be read that way (a stream another app sends) is read through, as before.
     */
    private fun listZip(id: String, port: WebExtension.Port) {
        val ctx = SwiftApp.instance ?: return
        val doc = find(id) ?: return
        val reply = JSONObject().put("type", "doc-zip-list")
        val entries = JSONArray()
        val listing = try {
            withZip(ctx, doc) { ZipIndex.list(it, MAX_ZIP_ENTRIES) }
        } catch (e: Exception) {
            L.d("ZIP file read through instead (${e.javaClass.simpleName})")
            null
        }
        if (listing != null) {
            val files = listing.entries.filter { !it.isDirectory }
            if (files.isNotEmpty() && files.all { it.encrypted }) {
                post(port, reply.put("entries", entries).put("locked", true).put("error", LOCKED_ZIP))
                return
            }
            for (e in listing.entries) {
                entries.put(JSONObject().put("name", e.name).put("size", e.size).put("kind", kindOf(e.name, null)?.key ?: JSONObject.NULL).put("locked", e.encrypted))
            }
            if (listing.truncated) reply.put("truncated", true)
            post(port, reply.put("entries", entries))
            return
        }
        // Most ZIPs name their files in UTF-8; older Windows ones in the DOS code page.
        for (charset in listOf(Charsets.UTF_8, Charset.forName("CP437"))) {
            try {
                ZipInputStream(stream(ctx, Uri.parse(doc.uri)), charset).use { zip ->
                    while (true) {
                        if (isClosed(port)) return
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
                if (lockedEntry(e)) {
                    reply.put("locked", true).put("error", LOCKED_ZIP)
                } else {
                    L.w("Couldn't read the ZIP file (${e.javaClass.simpleName})")
                    reply.put("error", "Swift couldn't read this ZIP file. It may be damaged, or not a ZIP file.")
                }
                break
            }
        }
        post(port, reply.put("entries", entries))
    }

    private enum class Taken { OK, TOO_BIG, LOCKED, FAILED }

    /** A document inside a ZIP: taken out into Swift's cache and opened over it (Back returns to the ZIP). */
    private fun openEntry(a: Activity, tab: Tab, doc: Doc, entry: String) {
        bulk.execute {
            val dir = File(tempDir(a), (if (doc.incognito) "private/zip/" else "zip/") + doc.id)
            val name = entry.substringAfterLast('/').ifBlank { "document" }
            val file = File(dir, idFor(entry).take(6) + "-" + UrlUtils.sanitizeFileName(name))
            // A file that would unpack into more than the room left (or 300 MB) is refused.
            val limit = minOf(MAX_ENTRY, a.cacheDir.usableSpace - FREE_MARGIN)
            val taken = try {
                dir.mkdirs()
                takeOut(a, doc, entry, file, limit)
            } catch (e: Exception) {
                L.w("Couldn't take a file out of the ZIP (${e.javaClass.simpleName})")
                if (lockedEntry(e)) Taken.LOCKED else Taken.FAILED
            }
            if (taken != Taken.OK) file.delete()
            main.post {
                if (a.isFinishing) return@post
                val text = when (taken) {
                    Taken.TOO_BIG -> "This file is too big to take out of the ZIP here. Open the ZIP with another app."
                    Taken.LOCKED -> LOCKED_ENTRY
                    Taken.FAILED -> "Swift couldn't take this file out of the ZIP. It may be damaged."
                    Taken.OK -> if (openTaken(a, doc, file, name)) null else "Swift can't show this kind of file."
                }
                if (text != null) com.swiftbrowser.ui.Messages.show(a, text)
            }
        }
    }

    /**
     * [entry] of [doc]'s ZIP into [file], [limit] bytes at most. 3.0: straight from its own bytes, found
     * in the ZIP's list; the whole ZIP is read through only when that can't be done.
     */
    private fun takeOut(ctx: Context, doc: Doc, entry: String, file: File, limit: Long): Taken {
        val direct = try {
            withZip(ctx, doc) { src ->
                val e = ZipIndex.list(src, MAX_ZIP_ENTRIES).entries.firstOrNull { it.name == entry }
                when {
                    e == null -> null
                    e.encrypted -> Taken.LOCKED
                    e.size > limit -> Taken.TOO_BIG
                    else -> ZipIndex.open(src, e).use { input -> FileOutputStream(file).use { out -> if (copyUpTo(input, out, limit)) Taken.OK else Taken.TOO_BIG } }
                }
            }
        } catch (e: ZipIndex.Locked) {
            Taken.LOCKED
        } catch (e: ZipIndex.Unsupported) {
            // Packed in a way only Files unpacks.
            Taken.FAILED
        } catch (e: Exception) {
            null
        }
        if (direct != null) return direct
        var found = false
        ZipInputStream(stream(ctx, Uri.parse(doc.uri)), zipCharsets[doc.id] ?: Charsets.UTF_8).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name != entry) continue
                found = true
                if (!FileOutputStream(file).use { out -> copyUpTo(zip, out, limit) }) return Taken.TOO_BIG
                break
            }
        }
        return if (found) Taken.OK else Taken.FAILED
    }

    /** Copies all of [input], unless it is over [limit] bytes: then false (what was written is left to delete). */
    private fun copyUpTo(input: InputStream, out: OutputStream, limit: Long): Boolean {
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return true
            total += n
            if (total > limit) return false
            out.write(buf, 0, n)
        }
    }

    /** Opens a file taken out of [zip]'s ZIP; one out of Private's is Private's too (3.0). */
    private fun openTaken(a: Activity, zip: Doc, file: File, name: String): Boolean {
        val id = registerOwn(a, Uri.fromFile(file), name, null, incognito = zip.incognito) ?: return false
        if (isPrivate(zip.id)) privateIds += id
        com.swiftbrowser.ui.DocumentActivity.open(a, id, zip.incognito, secure = isPrivate(id))
        return true
    }

    // ------------------------------------------------------------------ saving (2.5)

    private class Saving(val id: String, val name: String, val seq: Int, val data: String, val last: Boolean)

    /** 3.0: the saves under way (with how many bytes each has so far), and those that failed part-way. */
    private val savedBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failedSaves: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * A file the viewer made (a filled-in PDF), arriving in pieces: put together, then saved to Downloads.
     * 3.0: it must be a PDF, and is saved as one whatever name the page gives; once a piece fails, the
     * rest of that save is dropped, so a broken file is never saved.
     */
    private fun savePart(tab: Tab, part: Saving) {
        val ctx = SwiftApp.instance ?: return
        if (part.seq == 0) {
            failedSaves.remove(part.id)
            savedBytes[part.id] = 0L
        } else if (part.id in failedSaves || !savedBytes.containsKey(part.id)) {
            return
        }
        val dir = File(tempDir(ctx), "out").apply { mkdirs() }
        val file = File(dir, part.id + ".part")
        try {
            val bytes = Base64.decode(part.data, Base64.NO_WRAP)
            if (part.seq == 0 && !DocRules.looksLikePdf(bytes)) throw IOException("Not a PDF")
            val total = (savedBytes[part.id] ?: 0L) + bytes.size
            if (total > MAX_ENTRY || bytes.size > dir.usableSpace - FREE_MARGIN) throw IOException("Too big")
            savedBytes[part.id] = total
            FileOutputStream(file, part.seq > 0).use { it.write(bytes) }
        } catch (e: Exception) {
            L.w("Couldn't save the document (${e.javaClass.simpleName})")
            failedSaves += part.id
            savedBytes.remove(part.id)
            file.delete()
            main.post { screenOf(tab)?.let { com.swiftbrowser.ui.Messages.show(it, "Couldn't save the file") } }
            return
        }
        if (!part.last) return
        savedBytes.remove(part.id)
        val name = DocRules.savedPdfName(UrlUtils.sanitizeFileName(part.name))
        main.post { saveToDownloads(tab, name, "application/pdf", null, { out -> file.copyTo(out, true) }) { file.delete() } }
    }

    /** Where [tab]'s viewer is on screen: its own screen (2.8), or the browser for a viewer in a tab. */
    private fun screenOf(tab: Tab): Activity? = com.swiftbrowser.ui.DocumentActivity.hostOf(tab) ?: TabManager.ui as? MainActivity

    /** Puts a file in Downloads (asking for storage access once), then offers to open it. */
    private fun saveToDownloads(tab: Tab, name: String, mime: String, web: String?, write: (File) -> Unit, after: () -> Unit = {}) {
        val a = screenOf(tab) ?: return
        val save = {
            Downloads.saveFile(tab.profileId, name, mime, web, write = write) { item ->
                after()
                if (item == null) {
                    com.swiftbrowser.ui.Messages.show(a, "Couldn't save the file")
                } else {
                    com.swiftbrowser.ui.Messages.show(a, "Saved to Downloads", "Open") {
                        if (!open(a, Uri.fromFile(File(item.filePath)), item.fileName, item.mime)) (TabManager.ui as? MainActivity)?.openFileInTab(item.filePath)
                    }
                }
            }
        }
        when (a) {
            is com.swiftbrowser.ui.DocumentActivity -> a.withStorage(save)
            is MainActivity -> a.withStorage(save)
            else -> save()
        }
    }

    /** The document itself, kept in Downloads (it came from the web, a ZIP or another app). */
    private fun saveCopy(tab: Tab, doc: Doc) {
        val ctx = SwiftApp.instance ?: return
        val uri = Uri.parse(doc.uri)
        val mime = UrlUtils.mimeFromName(doc.name) ?: doc.kind.mimeTypes.first()
        saveToDownloads(tab, doc.name, mime, doc.web, { out ->
            stream(ctx, uri).use { input -> FileOutputStream(out).use { input.copyTo(it, 65536) } }
            // The kept copy is the document from now on (the cached one goes after a week).
            if (isTemp(ctx, uri)) SwiftApp.db.moveDocument(doc.id, Uri.fromFile(out).toString())
        })
    }

    /** Word, Excel, PowerPoint and the rest as a PDF, or printed (the page as the viewer shows it). */
    private fun printOrSave(a: Activity, tab: Tab, doc: Doc, print: Boolean) {
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

    private fun makePdf(a: Activity, tab: Tab, doc: Doc, session: org.mozilla.geckoview.GeckoSession, name: String, print: Boolean, restore: () -> Unit) {
        session.saveAsPdf().accept({ stream ->
            restore()
            if (stream == null) {
                com.swiftbrowser.ui.Messages.show(a, "Couldn't make a PDF of this document")
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
                    main.post { if (ok) printFile(a, name) { FileInputStream(file) } else com.swiftbrowser.ui.Messages.show(a, "Couldn't print this document") }
                }
            } else {
                saveToDownloads(tab, name, "application/pdf", doc.web, { out -> stream.use { input -> out.outputStream().use { input.copyTo(it) } } })
            }
        }, { _ ->
            restore()
            com.swiftbrowser.ui.Messages.show(a, "Couldn't make a PDF of this document")
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
                    // 3.0: copied in the background (a big PDF held up the screen), then said done.
                    val stop = AtomicBoolean(false)
                    cancel?.setOnCancelListener { stop.set(true) }
                    bulk.execute {
                        val failure = try {
                            open().use { input ->
                                FileOutputStream(destination.fileDescriptor).use { out ->
                                    val buf = ByteArray(65536)
                                    while (!stop.get()) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                    }
                                }
                            }
                            null
                        } catch (e: Exception) {
                            L.w("Couldn't hand the PDF to printing (${e.javaClass.simpleName})")
                            "Swift couldn't read this document"
                        }
                        main.post {
                            when {
                                stop.get() -> callback.onWriteCancelled()
                                failure != null -> callback.onWriteFailed(failure)
                                else -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                            }
                        }
                    }
                }
            }, null)
        } catch (e: Exception) {
            L.w("Printing failed", e)
            com.swiftbrowser.ui.Messages.show(a, "Your phone can't print")
        }
    }

    // ------------------------------------------------------------------ home screen shortcuts (2.5)

    const val ACTION_OPEN_DOC = "com.swiftbrowser.action.OPEN_DOC"
    const val EXTRA_DOC_ID = "doc_id"

    private fun shortcutFor(ctx: Context, doc: Doc, shortId: String): ShortcutInfoCompat {
        // 2.8: straight to the viewer's screen (older shortcuts still go through the browser).
        val intent = Intent(ACTION_OPEN_DOC).setClass(ctx, com.swiftbrowser.ui.DocumentActivity::class.java).putExtra(EXTRA_DOC_ID, doc.id)
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
        com.swiftbrowser.ui.Messages.show(a, if (ok) "Added to the home screen" else "Your phone's home screen doesn't accept shortcuts")
    }

    /** The last documents read, among the shortcuts of Swift's icon (hold it on the home screen). */
    private fun updateShortcuts(ctx: Context) {
        try {
            val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx) - STATIC_SHORTCUTS).coerceIn(0, 1)
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
        val screen = com.swiftbrowser.ui.DocumentActivity.hostOf(tab)
        val a: Activity = screen ?: TabManager.ui as? MainActivity ?: return
        when (action) {
            "documents", "find", "close" -> if (screen != null) screen.onPageAction(action) else when (action) {
                "documents" -> com.swiftbrowser.ui.FilesActivity.open(a)
                "find" -> (a as MainActivity).openFind()
            }
            "exact" -> {
                val doc = find(id) ?: return
                val web = doc.web ?: return
                TabManager.newTab(EXACT_VIEW + Uri.encode(web), parent = tab.takeIf { it in TabManager.tabs }, incognito = tab.incognito)
                if (screen != null) screen.startActivity(Intent(screen, MainActivity::class.java))
            }
            // 3.0: a link tapped in an e-book (web pages in a new tab, e-mail and phone links to the phone's apps).
            "link" -> {
                val url = msg.optString("url").take(8000)
                if (screen != null) screen.openLink(url)
                else if (UrlUtils.isHttp(url)) TabManager.newTab(url, parent = tab.takeIf { it in TabManager.tabs }, incognito = tab.incognito)
            }
            // 3.0: a ZIP locked with a password: Files opens it (with its password), at its folder.
            "files" -> {
                val doc = find(id) ?: return
                val uri = Uri.parse(doc.uri)
                val folder = if (uri.scheme == ContentResolver.SCHEME_FILE && !isTemp(a, uri)) File(uri.path.orEmpty()).parentFile else null
                com.swiftbrowser.ui.FilesActivity.open(a, folder)
                screen?.finish()
            }
            "share", "open-with", "save-copy", "print", "save-pdf", "shortcut", "open-entry" -> io.execute {
                val doc = find(id) ?: return@execute
                main.post {
                    when (action) {
                        "share", "open-with" -> handOver(a, doc, share = action == "share")
                        "save-copy" -> saveCopy(tab, doc)
                        "print" -> printOrSave(a, tab, doc, print = true)
                        "save-pdf" -> printOrSave(a, tab, doc, print = false)
                        "shortcut" -> if (doc.incognito) com.swiftbrowser.ui.Messages.show(a, "Incognito documents can't go on the home screen") else pinShortcut(a, doc)
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
            // Not Swift itself (it would just open the file here again). 3.0: nor its document screen's
            // entrance for other apps, which refuses Swift's own links anyway.
            if (!share) {
                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(
                    android.content.ComponentName(a, MainActivity::class.java),
                    android.content.ComponentName(a, com.swiftbrowser.ui.DocumentActivity::class.java),
                ))
            }
            a.startActivity(chooser)
        } catch (e: Exception) {
            com.swiftbrowser.ui.Messages.show(a, "No app on your phone can open this file")
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

    /** Room left free on the phone when Swift copies a document (3.0, as for files out of ZIPs). */
    private const val FREE_MARGIN = 50L * 1_048_576

    /** Old Office files laid out at once (3.0): more means some are stuck, and the next gets its text alone. */
    private const val MAX_CONVERTING = 3

    private const val LOCKED_ZIP = "This ZIP file is locked with a password, so Swift's viewer can't open it. Files can: open it there and type its password."
    private const val LOCKED_ENTRY = "This file in the ZIP is locked with a password. Open the ZIP in Files to take it out with its password."

    /** new_tab, incognito and scan in res/xml/shortcuts.xml. */
    private const val STATIC_SHORTCUTS = 3

    /** Microsoft's free viewer for Office files on the web (the link is sent to Microsoft). */
    const val EXACT_VIEW = "https://view.officeapps.live.com/op/view.aspx?src="
}
