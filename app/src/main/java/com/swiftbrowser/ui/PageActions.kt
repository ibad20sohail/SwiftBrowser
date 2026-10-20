package com.swiftbrowser.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import java.io.File

/** Things the menu does with the current page. */
object PageActions {

    // ------------------------------------------------------------------ reader view

    /** Shows the page as a clean article, or goes back to the page from reader view. */
    fun reader(a: MainActivity, tab: Tab) {
        if (Helper.isReaderUrl(tab.url)) {
            if (tab.canGoBack) TabManager.goBack(tab)
            return
        }
        if (!UrlUtils.isHttp(tab.url) || tab.session == null || !Helper.startReader(tab)) {
            a.snack("Reader view isn't available on this page")
        }
    }

    // ------------------------------------------------------------------ Lite mode

    /** Lite mode on all sites (no animations, shadows or blur), or off. Reloads the open page. */
    fun setLite(a: MainActivity, on: Boolean) {
        SwiftApp.prefs.liteMode = on
        Helper.sendLiteMode()
        val tab = TabManager.current
        // The extension registers the style asynchronously; give it a moment before reloading.
        if (tab != null && UrlUtils.isHttp(tab.url)) {
            a.window.decorView.postDelayed({ if (tab.session != null) TabManager.reload(tab) }, 300)
        }
    }

    // ------------------------------------------------------------------ save for offline

    /** Saves the page as a PDF in Downloads, to read without internet. */
    fun savePage(a: MainActivity, tab: Tab) {
        val session = tab.session
        if (session == null || !UrlUtils.isHttp(tab.url)) {
            a.snack("This page can't be saved")
            return
        }
        a.withStorage {
            a.snack("Saving page…")
            val name = UrlUtils.sanitizeFileName(tab.title.ifBlank { UrlUtils.host(tab.url) ?: "page" }).take(80) + ".pdf"
            session.saveAsPdf().accept({ stream ->
                if (stream == null) {
                    a.snack("Couldn't save this page")
                    return@accept
                }
                Downloads.saveFile(tab.profileId, name, "application/pdf", tab.url, write = { file ->
                    stream.use { input -> file.outputStream().use { input.copyTo(it) } }
                }) { item ->
                    if (item == null) a.snack("Couldn't save this page")
                    else a.snack("Saved for offline reading", "Open") { a.openFileInTab(item.filePath) }
                }
            }, { _ -> a.snack("Couldn't save this page") })
        }
    }

    /** Pages saved as PDF open in Swift itself (the engine has a PDF viewer). */
    fun isSavedPage(d: Downloads.Item): Boolean =
        d.fileName.endsWith(".pdf", ignoreCase = true) && File(d.filePath).exists()

    // ------------------------------------------------------------------ home screen shortcut

    fun addToHomeScreen(a: MainActivity, tab: Tab) {
        val url = tab.url
        if (!UrlUtils.isHttp(url)) {
            a.snack("This page can't be added to the home screen")
            return
        }
        val label = tab.title.ifBlank { UrlUtils.host(url)?.removePrefix("www.") ?: "Site" }.take(24)
        Favicons.load(url) { icon ->
            val info = ShortcutInfoCompat.Builder(a, "site-" + url.hashCode())
                .setShortLabel(label)
                .setIcon(IconCompat.createWithBitmap(shortcutIcon(a, icon, label)))
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url), a, MainActivity::class.java))
                .build()
            val ok = ShortcutManagerCompat.isRequestPinShortcutSupported(a) &&
                ShortcutManagerCompat.requestPinShortcut(a, info, null)
            a.snack(if (ok) "Added to the home screen" else "This phone's home screen doesn't accept shortcuts")
        }
    }

    /** A round icon: the site's own icon on white, or its first letter on a colour. */
    private fun shortcutIcon(a: MainActivity, favicon: Bitmap?, label: String): Bitmap {
        val size = (48 * a.resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(96)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val r = size / 2f
        if (favicon != null) {
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(r, r, r, paint)
            val inner = size * 0.58f
            val left = (size - inner) / 2f
            c.drawBitmap(favicon, Rect(0, 0, favicon.width, favicon.height), RectF(left, left, left + inner, left + inner), paint)
        } else {
            val colors = intArrayOf(0xFF1B66E4.toInt(), 0xFF0F9D58.toInt(), 0xFFE8710A.toInt(), 0xFF9334E6.toInt(), 0xFFD93025.toInt())
            paint.color = colors[(label.hashCode() and 0x7fffffff) % colors.size]
            c.drawCircle(r, r, r, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = size * 0.46f
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textAlign = Paint.Align.CENTER
            val letter = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "S"
            c.drawText(letter, r, r - (paint.descent() + paint.ascent()) / 2f, paint)
        }
        return bmp
    }
}
