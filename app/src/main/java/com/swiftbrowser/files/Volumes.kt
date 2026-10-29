package com.swiftbrowser.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.swiftbrowser.core.L
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * The phone's storage and its memory cards (2.7). Android 7 lets apps read a removable SD card but
 * not change it with plain file access, so Swift asks once for the card through Android's document
 * access (kept from then on) and makes its changes there through it.
 */
object Volumes {
    /** A storage: the phone's own, or a memory card at [root] (/storage/1A2B-3C4D). */
    class Volume(val name: String, val root: File, val removable: Boolean) {
        /** A card's id ("1A2B-3C4D"): its folder's name under /storage. */
        val uuid: String get() = root.name

        fun contains(f: File): Boolean = f.path == root.path || f.path.startsWith(root.path + "/")

        /** Free and total space, in bytes. */
        fun space(): Pair<Long, Long> = try {
            val s = StatFs(root.path)
            s.availableBytes to s.totalBytes
        } catch (_: Exception) {
            -1L to -1L
        }
    }

    @Volatile
    private var cached: List<Volume>? = null

    fun internalRoot(): File = Environment.getExternalStorageDirectory()

    /** The phone's storage first, then each memory card that is in. */
    fun all(ctx: Context): List<Volume> = cached ?: refresh(ctx)

    /** Looks for cards again (one may have been put in or taken out since). */
    fun refresh(ctx: Context): List<Volume> {
        val out = ArrayList<Volume>()
        out += Volume("Phone storage", internalRoot(), false)
        val cards = try {
            ctx.getExternalFilesDirs(null).drop(1).filterNotNull()
                .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                .map { File(it.path.substringBefore("/Android/data")) }
                .distinctBy { it.path }
        } catch (e: Exception) {
            L.w("Couldn't look for memory cards", e)
            emptyList()
        }
        cards.forEachIndexed { i, root -> out += Volume(if (cards.size > 1) "SD card ${i + 1}" else "SD card", root, true) }
        cached = out
        return out
    }

    /** The storage [f] is on (null for Swift's private folders and anything else). */
    fun of(ctx: Context, f: File): Volume? = all(ctx).filter { it.contains(f) }.maxByOrNull { it.root.path.length }

    fun isCard(ctx: Context, f: File): Boolean = of(ctx, f)?.removable == true

    /** Swift's own folder on a card, which plain file access may change. */
    private fun ownFolder(ctx: Context, v: Volume) = File(v.root, "Android/data/" + ctx.packageName)

    /** Whether changing [f] needs the card's document access (on a memory card, outside Swift's own folder). */
    fun needsAccess(ctx: Context, f: File): Boolean {
        val v = of(ctx, f) ?: return false
        if (!v.removable) return false
        if (ownFolder(ctx, v).let { f.path == it.path || f.path.startsWith(it.path + "/") }) return false
        // Some phones let apps write to the card directly: nothing to ask then.
        return !v.root.canWrite()
    }

    // ------------------------------------------------------------------ document access to a card

    private const val AUTHORITY = "com.android.externalstorage.documents"

    /** The card's top folder Swift may change, if it was allowed (and still is). */
    fun treeFor(ctx: Context, v: Volume): Uri? = try {
        ctx.contentResolver.persistedUriPermissions.firstOrNull { p ->
            p.isWritePermission && p.uri.authority == AUTHORITY &&
                runCatching { DocumentsContract.getTreeDocumentId(p.uri) }.getOrNull() == v.uuid + ":"
        }?.uri
    } catch (_: Exception) {
        null
    }

    /**
     * Android's question "Allow Swift to access the SD card?". On Android 7 to 9 a short question for
     * the whole card; otherwise Android's folder picker, opened at the card.
     */
    fun accessIntent(ctx: Context, v: Volume): Intent {
        if (Build.VERSION.SDK_INT in 24..28) {
            try {
                val sm = ctx.getSystemService(StorageManager::class.java)
                @Suppress("DEPRECATION")
                sm?.getStorageVolume(v.root)?.createAccessIntent(null)?.let { return it }
            } catch (e: Exception) {
                L.w("No simple access question for the card", e)
            }
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildRootUri(AUTHORITY, v.uuid))
            // Asks the picker to list the phone's storages (understood by most phones).
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
    }

    /** The picker for when the short question isn't available or was refused. */
    fun pickerIntent(v: Volume): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        if (Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildRootUri(AUTHORITY, v.uuid))
        putExtra("android.content.extra.SHOW_ADVANCED", true)
    }

    /** The answer to [accessIntent]: true when it is the card's top folder, now kept for good. */
    fun onAccessResult(ctx: Context, v: Volume, data: Intent?): Boolean {
        val uri = data?.data ?: return false
        if (uri.authority != AUTHORITY) return false
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return false
        if (id != v.uuid + ":") {
            L.w("Card access: a folder was picked instead of the card itself")
            return false
        }
        return try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            L.d("Card access allowed")
            true
        } catch (e: Exception) {
            L.w("Couldn't keep the card access", e)
            false
        }
    }

    /** How [f] is changed: plain file access, the card's document access, or null when the card's access is needed first. */
    fun accessFor(ctx: Context, f: File): FileAccess? {
        if (!needsAccess(ctx, f)) return PlainAccess
        val v = of(ctx, f) ?: return PlainAccess
        val tree = treeFor(ctx, v) ?: return null
        return CardAccess(ctx.applicationContext, v, tree)
    }

    /** The card that [files] need access to (the first one without it), if any. */
    fun cardWithoutAccess(ctx: Context, files: Collection<File>): Volume? =
        files.firstOrNull { needsAccess(ctx, it) && treeFor(ctx, of(ctx, it)!!) == null }?.let { of(ctx, it) }
}

/** How files are changed (2.7): plain file access, or a memory card's document access. */
abstract class FileAccess {
    /** Makes one folder ([dir]'s parent is there already). */
    abstract fun mkdir(dir: File): Boolean

    /** Writes [f] (made, or emptied when it is there already). */
    abstract fun output(f: File): OutputStream

    /** A file opened for writing at any place (7z needs to go back to its start); closing the channel closes it. */
    abstract fun channel(f: File): FileChannel

    /** Deletes a file, or a folder with all it holds. */
    abstract fun delete(f: File): Boolean

    abstract fun rename(f: File, name: String): Boolean

    /** Moves [f] into [dir] on the same storage, keeping its name (no copying). */
    abstract fun moveInto(f: File, dir: File): Boolean

    /** Sets a copy's date to the original's (not possible on a card through document access). */
    open fun setTime(f: File, time: Long) {}

    fun mkdirs(dir: File): Boolean {
        if (dir.isDirectory) return true
        val parent = dir.parentFile ?: return false
        return mkdirs(parent) && (dir.isDirectory || mkdir(dir))
    }
}

/** The phone's storage and Swift's own folders: plain file access. */
object PlainAccess : FileAccess() {
    override fun mkdir(dir: File) = dir.mkdir() || dir.isDirectory

    override fun output(f: File): OutputStream = FileOutputStream(f)

    override fun channel(f: File): FileChannel {
        val raf = java.io.RandomAccessFile(f, "rw")
        raf.setLength(0)
        return raf.channel
    }

    override fun delete(f: File): Boolean = if (f.isDirectory) f.deleteRecursively() else f.delete() || !f.exists()

    override fun rename(f: File, name: String) = f.renameTo(File(f.parentFile, name))

    override fun moveInto(f: File, dir: File) = f.renameTo(File(dir, f.name))

    override fun setTime(f: File, time: Long) {
        if (time > 0) f.setLastModified(time)
    }
}

/** A memory card through the document access the user allowed: each file by its document address. */
class CardAccess(private val ctx: Context, private val volume: Volumes.Volume, private val tree: Uri) : FileAccess() {
    private val resolver: ContentResolver get() = ctx.contentResolver

    private fun doc(f: File): Uri {
        val rel = f.path.removePrefix(volume.root.path).trimStart('/')
        return DocumentsContract.buildDocumentUriUsingTree(tree, volume.uuid + ":" + rel)
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // The card's provider keeps the name as given when the type matches its ending.
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun mkdir(dir: File): Boolean {
        if (dir.isDirectory) return true
        val parent = dir.parentFile ?: return false
        return try {
            DocumentsContract.createDocument(resolver, doc(parent), DocumentsContract.Document.MIME_TYPE_DIR, dir.name) != null
        } catch (e: Exception) {
            L.w("Couldn't make a folder on the card", e)
            false
        }
    }

    private fun create(f: File): Uri {
        if (f.exists()) {
            // Emptied in place: a new document would get another name ("name (1)").
            return doc(f)
        }
        val parent = f.parentFile ?: throw IOException("No folder")
        return DocumentsContract.createDocument(resolver, doc(parent), mimeOf(f.name), f.name)
            ?: throw IOException("The card refused a new file")
    }

    override fun output(f: File): OutputStream =
        resolver.openOutputStream(create(f), "wt") ?: throw IOException("The card refused the file")

    override fun channel(f: File): FileChannel {
        val pfd = resolver.openFileDescriptor(create(f), "rwt") ?: throw IOException("The card refused the file")
        // The stream owns the descriptor from here: closing the channel closes both.
        return ParcelChannel.of(pfd)
    }

    override fun delete(f: File): Boolean = try {
        DocumentsContract.deleteDocument(resolver, doc(f)) || !f.exists()
    } catch (e: Exception) {
        L.w("Couldn't delete on the card", e)
        !f.exists()
    }

    override fun rename(f: File, name: String): Boolean = try {
        DocumentsContract.renameDocument(resolver, doc(f), name) != null && File(f.parentFile, name).exists()
    } catch (e: Exception) {
        L.w("Couldn't rename on the card", e)
        false
    }

    override fun moveInto(f: File, dir: File): Boolean {
        val parent = f.parentFile ?: return false
        return try {
            DocumentsContract.moveDocument(resolver, doc(f), doc(parent), doc(dir)) != null
        } catch (e: Exception) {
            // Not every phone's card can move: the job copies, then deletes.
            L.w("The card can't move files", e)
            false
        }
    }
}

/** A channel over a document's descriptor that closes the descriptor with it. */
private object ParcelChannel {
    fun of(pfd: android.os.ParcelFileDescriptor): FileChannel {
        val stream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        return stream.channel
    }
}
