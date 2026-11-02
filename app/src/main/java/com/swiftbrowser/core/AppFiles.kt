package com.swiftbrowser.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Swift's own private storage (3.0): its data folder holds the Private folder, saved sign-ins and the
 * engine's profile with every site's cookies. Other apps and web pages must never get Swift to show,
 * copy or share anything from there, so the screens other apps can open check links with these.
 */
object AppFiles {
    /**
     * Set on intents that open one of Swift's viewers (pictures, player, documents) from the Private
     * folder: the viewer keeps itself out of screenshots and the recent-apps picture (SecureScreen).
     */
    const val EXTRA_SECURE = "com.swiftbrowser.extra.SECURE"

    /** True for a file in Swift's data folder (/data/data/<package> and its other names). */
    fun isPrivate(ctx: Context, file: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val roots = listOfNotNull(
            ctx.applicationInfo.dataDir,
            runCatching { File(ctx.applicationInfo.dataDir).canonicalPath }.getOrNull(),
            "/data/data/${ctx.packageName}",
            "/data/user/0/${ctx.packageName}",
        )
        return roots.any { path == it || path.startsWith("$it/") }
    }

    /** True for a content:// link to Swift's own file provider (only Swift itself hands those out). */
    fun isOwnProvider(ctx: Context, uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority.equals("${ctx.packageName}.files", ignoreCase = true)

    /**
     * A link another app sent to one of Swift's screens that would reach Swift's own private files:
     * file:// paths in its data folder, and links to its own file provider. Such links are refused.
     */
    fun refusedFromOutside(ctx: Context, uri: Uri): Boolean = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> uri.path?.let { isPrivate(ctx, File(it)) } ?: true
        ContentResolver.SCHEME_CONTENT -> isOwnProvider(ctx, uri)
        else -> false
    }
}
