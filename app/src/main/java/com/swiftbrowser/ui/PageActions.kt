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
import com.swiftbrowser.core.SiteSettings
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
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
            return
        }
        tab.autoReaderDone.add(tab.url.substringBefore('#'))
        // The second time on a site: offer to always open its articles in reader view (2.3); not
        // from incognito tabs, which leave nothing behind (2.6).
        if (tab.incognito) return
        val site = UrlUtils.site(tab.url) ?: return
        if (SiteSettings.autoReader(site) || site in readerOffered) return
        val times = (readerUses[site] ?: 0) + 1
        readerUses[site] = times
        if (times >= 2) {
            readerOffered += site
            a.snack("Always open articles on $site in reader view?", "Always") {
                SiteSettings.setAutoReader(site, true)
                a.snack("Articles on $site open in reader view. Change it in the site's settings.")
            }
        }
    }

    /** Reader view opened by hand on each site this time, and the sites already offered "Always". */
    private val readerUses = HashMap<String, Int>()
    private val readerOffered = HashSet<String>()

    // ------------------------------------------------------------------ how sites look

    /**
     * Lite mode on all sites (no animations, shadows or blur), or off. Turning it on also turns off
     * "Show sites as designed". Reloads the open page when [a] is given.
     */
    fun setLite(a: MainActivity?, on: Boolean) {
        val prefs = SwiftApp.prefs
        prefs.liteMode = on
        if (on && prefs.sitesAsDesigned) {
            prefs.sitesAsDesigned = false
            Engine.applySitesAsDesigned()
        }
        Helper.sendLook()
        reloadCurrent(a)
    }

    /**
     * Sites exactly as designed (glass blur, animations, no lighter YouTube/TheFlixer), for fast
     * phones. Turning it on turns Lite mode off.
     */
    fun setSitesAsDesigned(a: MainActivity?, on: Boolean) {
        val prefs = SwiftApp.prefs
        prefs.sitesAsDesigned = on
        if (on) prefs.liteMode = false
        Engine.applySitesAsDesigned()
        Helper.sendLook()
        reloadCurrent(a)
    }

    private fun reloadCurrent(a: MainActivity?) {
        val tab = TabManager.current ?: return
        if (a == null || !UrlUtils.isHttp(tab.url)) return
        // The extension registers its styles asynchronously; give it a moment before reloading.
        a.window.decorView.postDelayed({ if (tab.session != null) TabManager.reload(tab) }, 300)
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

    // ------------------------------------------------------------------ QR code (2.6)

    /** The page's address as a QR code: another phone's camera opens it, or it's shared as a picture. */
    fun showQr(a: MainActivity, tab: Tab) {
        val url = tab.url
        if (!UrlUtils.isHttp(url)) {
            a.snack("Open a page to show its QR code")
            return
        }
        val size = Ui.dp(a, 232)
        val code = try {
            com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(
                url, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1, com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
        } catch (e: Exception) {
            a.snack("This address is too long for a QR code")
            return
        }
        val col = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 24), Ui.dp(a, 4))
            // Black on white in dark mode too: cameras read it best that way.
            addView(android.widget.ImageView(a).apply {
                setImageBitmap(code)
                background = Ui.shape(a, android.graphics.Color.WHITE, Ui.R_MEDIUM)
                setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12))
                contentDescription = "QR code of $url"
            })
            addView(Ui.text(a, Ui.Type.SUBTITLE, tab.title.ifBlank { UrlUtils.siteName(url) }, lines = 2).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, Ui.dp(a, 14), 0, 0)
            })
            addView(Ui.text(a, Ui.Type.CAPTION, UrlUtils.display(url), lines = 2).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, Ui.dp(a, 2), 0, 0)
            })
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
            .setView(col)
            .setPositiveButton("Share picture") { _, _ -> shareQr(a, tab, url, size) }
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    private fun shareQr(a: MainActivity, tab: Tab, url: String, size: Int) {
        try {
            val code = com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(
                url, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
            )
            val dir = File(a.cacheDir, "docs/share").apply { mkdirs() }
            val file = File(dir, "QR code.png")
            file.outputStream().use { code.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_SUBJECT, tab.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            a.startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            a.snack("The picture couldn't be shared")
        }
    }

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
