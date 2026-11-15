package com.swiftbrowser.ui

import android.app.Activity
import android.content.Intent
import com.swiftbrowser.download.Downloads
import java.io.File

/**
 * 3.0.11: a file Swift wrote for the user to take to another browser or app (Export bookmarks,
 * Export passwords): where it went, as a backup's message says it, with Share.
 */
object ExportUi {
    fun saved(a: Activity, file: File, mime: String) {
        if (a.isFinishing) return
        Messages.show(a, "Saved to ${Downloads.describe(file.parentFile ?: file)} › ${file.name}", "Share", long = true) { share(a, file, mime) }
    }

    private fun share(a: Activity, file: File, mime: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            a.startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            com.swiftbrowser.core.L.w("An exported file couldn't be shared (${e.javaClass.simpleName})")
            Messages.show(a, "The file couldn't be shared")
        }
    }
}
