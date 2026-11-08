package com.swiftbrowser.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Swift's own Files as the file picker for a website's upload button (Settings → Files). Files is
 * started for a result by the browser screen and answers with the chosen files' paths: as plain
 * text, because Android 7 and later refuse a file:// address in a result.
 */
object FilePick {
    private const val EXTRA_MIMES = "pick_mimes"
    private const val EXTRA_MULTIPLE = "pick_multiple"
    private const val EXTRA_PICKED = "picked_paths"

    /** What the page accepts ([mimes], empty for anything), and whether several files may be chosen. */
    class Request(val mimes: Array<String>, val multiple: Boolean)

    fun intent(ctx: Context, mimes: Array<String>, multiple: Boolean): Intent =
        Intent(ctx, FilesActivity::class.java).putExtra(EXTRA_MIMES, mimes).putExtra(EXTRA_MULTIPLE, multiple)

    /** The request [launch] carries, or null when Files wasn't opened to pick. */
    fun requestIn(launch: Intent): Request? {
        val mimes = launch.getStringArrayExtra(EXTRA_MIMES) ?: return null
        return Request(mimes, launch.getBooleanExtra(EXTRA_MULTIPLE, false))
    }

    fun result(files: List<File>): Intent = Intent().putStringArrayListExtra(EXTRA_PICKED, ArrayList(files.map { it.path }))

    /** The files [result] carries, or null when the answer came from Android's own picker. */
    fun picked(result: Intent): List<Uri>? = result.getStringArrayListExtra(EXTRA_PICKED)?.map { Uri.fromFile(File(it)) }

    fun accepts(request: Request, name: String): Boolean =
        PickRules.accepts(request.mimes, MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: kindOf(name))

    /** Android 7 doesn't know every type: a picture, video or song Swift itself recognises still counts as one. */
    private fun kindOf(name: String): String? = when (com.swiftbrowser.files.FileKind.of(name, false)) {
        com.swiftbrowser.files.FileKind.IMAGE -> "image/other"
        com.swiftbrowser.files.FileKind.VIDEO -> "video/other"
        com.swiftbrowser.files.FileKind.AUDIO -> "audio/other"
        else -> null
    }
}

/** Which files a page's upload field takes. Plain rules, tested on the PC (PickRulesTest). */
internal object PickRules {
    /** Whether a file of type [mime] is one of [wanted] (exact types, or a whole kind such as all images); a type not known only when the page takes anything. */
    fun accepts(wanted: Array<String>, mime: String?): Boolean {
        val asked = wanted.map { it.trim().lowercase() }
        if (asked.isEmpty() || "*/*" in asked) return true
        val type = mime?.lowercase() ?: return false
        return asked.any { want -> want == type || (want.endsWith("/*") && type.startsWith(want.dropLast(1))) }
    }
}
