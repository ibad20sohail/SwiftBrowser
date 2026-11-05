package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.swiftbrowser.R
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.files.FileKind
import com.swiftbrowser.files.FileThumbs
import com.swiftbrowser.files.FilesSettings
import com.swiftbrowser.media.Unfinished
import com.swiftbrowser.tabs.TabManager
import java.io.File

/**
 * "Continue" on the home page (2.3): videos and articles left half-way, as a row of cards that
 * scrolls sideways. Hidden while there is nothing to continue.
 *
 * 3.0.3: the same row and cards serve Files ([part]): the browser's home page has what was left on
 * the web, Files' start page the videos and songs on the phone. Files reads its cards with the rest
 * of its page and hands them over ([show]), under a heading of its own.
 */
class ContinueRow(
    context: Context,
    private val part: Part,
    private val onOpen: (Unfinished.Item) -> Unit,
    private val onHold: (Unfinished.Item) -> Unit,
) : LinearLayout(context) {

    /** 3.0.3: which Continue the row is: the browser home's (from the web) or Files' (on the phone). */
    enum class Part { BROWSER, FILES }

    // Padded like the page; the row itself reaches the screen's edges (HomeView), so cards scroll out of sight there.
    private val cards = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(16), 0, dp(16), dp(2))
    }
    private var generation = 0
    private var shown: List<String> = emptyList()

    init {
        orientation = VERTICAL
        visibility = GONE
        // 3.0.3: Files' start page names its parts with headings of its own.
        if (part == Part.BROWSER) addView(TextView(context).apply {
            text = "Continue"
            // 3.0.4: as bold as the home page's other headings (the scale's HEADING).
            Ui.size(this, Ui.Type.HEADING)
            setTextColor(Ui.text(context))
            setPadding(dp(24), 0, 0, dp(10))
        })
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            addView(cards)
            // 3.0.4: the cards fade out at the edge where more of them wait.
            Ui.fadeEdges(this)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Loads the row again (the home page came up, or one was removed). */
    fun refresh() {
        val gen = ++generation
        val profileId = Profiles.currentId
        val app = context.applicationContext
        TabManager.io.execute {
            val items = if (part == Part.FILES) Unfinished.forFiles(app) else Unfinished.forBrowser(profileId)
            post { if (gen == generation) show(items) }
        }
    }

    /** Shows [items] as the cards (3.0.3: Files hands over its own); one waiting for its Undo stays out. */
    fun show(items: List<Unfinished.Item>) {
        val cardsNow = items.filter { !Unfinished.isHidden(it) }
        val keys = cardsNow.map { it.url + it.progress }
        if (keys == shown && cards.childCount == cardsNow.size) return
        // A row still closing up after its last card went (remove) stays open for these.
        if (cardsNow.isNotEmpty()) {
            animate().cancel()
            alpha = 1f
        }
        val wasShown = visibility == VISIBLE
        shown = keys
        cards.removeAllViews()
        cardsNow.forEachIndexed { i, item -> cards.addView(card(item), LayoutParams(dp(CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(10) }) }
        visibility = if (cardsNow.isEmpty()) GONE else VISIBLE
        // Files' list brings its parts in itself.
        if (part == Part.BROWSER && !wasShown && cardsNow.isNotEmpty()) {
            alpha = 0f
            translationY = dp(8).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    /**
     * Takes [item]'s card away with a short fade, then the row closes up. [onUndo] (3.0.3): what Undo
     * does besides putting it back (Files shows its start page again); otherwise the row loads again.
     */
    fun remove(item: Unfinished.Item, onUndo: (() -> Unit)? = null) {
        // Removed for good once the Undo is gone (2.6): 3.0, when its message goes, not after a fixed
        // time (holding the message stops its clock, and an Undo then came too late to work).
        Unfinished.hide(item)
        val a = context as? android.app.Activity
        if (a == null) {
            Unfinished.remove(item)
        } else {
            var undone = false
            Messages.show(a, "Removed from Continue", "Undo", onDismiss = { if (!undone) Unfinished.remove(item) }) {
                undone = true
                Unfinished.unhide(item)
                shown = emptyList()
                if (onUndo != null) onUndo() else refresh()
            }
        }
        val card = (0 until cards.childCount).map { cards.getChildAt(it) }.firstOrNull { it.tag === item } ?: return
        card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(180).withEndAction {
            cards.removeView(card)
            if (cards.childCount > 0) {
                val first = cards.getChildAt(0)
                first.layoutParams = (first.layoutParams as LayoutParams).apply { marginStart = 0 }
            }
            shown = emptyList()
            if (cards.childCount == 0) animate().alpha(0f).setDuration(160).withEndAction { visibility = GONE; alpha = 1f }.start()
        }.start()
    }

    /**
     * A card: a 16:9 picture (the video's or the article's, or its kind's icon) with how far you got
     * along its bottom, then the site, the title and what is left.
     */
    private fun card(item: Unfinished.Item): View = LinearLayout(context).apply {
        tag = item
        orientation = VERTICAL
        background = Ui.shape(context, Ui.color(context, R.color.tile_bg), Ui.R_MEDIUM)
        clipToOutline = true
        // 3.0.3: a video or song on the phone (Files' row) has no site: its folder says where it is.
        val local = item.localPath?.let { File(it) }
        val site = if (local != null) "" else UrlUtils.host(item.url)?.removePrefix("www.")?.removePrefix("m.").orEmpty()
        val name = item.title.ifBlank { local?.name ?: site }
        addView(FrameLayout(context).apply {
            val doc = item.doc
            // 3.0.1: until its picture comes (or when it has none), the site's colour softly across the card
            // and a round play (or page) mark, rather than a dim little icon on plain grey. 3.0.3: the
            // accent's for a file on the phone, and a note for a song.
            val tint = if (local != null) Ui.accent(context) else Ui.siteColor(com.swiftbrowser.core.TopSites.key(site))
            if (doc == null) {
                val base = Ui.color(context, R.color.surface_alt)
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(androidx.core.graphics.ColorUtils.blendARGB(base, tint, 0.55f), androidx.core.graphics.ColorUtils.blendARGB(base, tint, 0.18f)),
                )
            } else {
                setBackgroundColor(Ui.color(context, R.color.surface_alt))
            }
            val picture = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                if (doc != null) {
                    setImageDrawable(com.swiftbrowser.docs.DocIcon(doc.kind, dp(56)))
                } else {
                    setImageDrawable(badge(if (item.video) R.drawable.ic_play else if (local != null) R.drawable.ic_music_note else R.drawable.ic_reader, tint))
                }
            }
            addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (doc != null) {
                // The document's whole first page (or its own picture), small on the tinted band (2.6):
                // the top of a white page filled the card with white, glaring in dark mode.
                com.swiftbrowser.docs.DocThumbs.load(context, doc.uri, doc.kind, dp(CARD_DP)) { bmp ->
                    picture.scaleType = ImageView.ScaleType.FIT_CENTER
                    picture.setPadding(0, dp(8), 0, dp(10))
                    picture.setImageBitmap(bmp)
                    picture.alpha = 0f
                    picture.animate().alpha(1f).setDuration(Motion.NORMAL).start()
                }
            }
            item.image?.let { url ->
                // Small and kept in memory a while (PreviewLoader); none on mobile data with Data saver.
                // Just big enough to cover the card's picture (3.0: decoded to its size).
                PreviewLoader.load(context as android.app.Activity, url, dp(PICTURE_DP), dp(CARD_DP)) { bmp ->
                    picture.scaleType = ImageView.ScaleType.CENTER_CROP
                    picture.setImageBitmap(bmp)
                    picture.alpha = 0f
                    picture.animate().alpha(1f).setDuration(Motion.NORMAL).start()
                }
            }
            // 3.0.3: a video on the phone shows a frame of it, made as Files' pictures are (when those are on).
            if (local != null && item.video && FilesSettings.thumbnails) {
                FileThumbs.load(context, local, item.fileTime, FileKind.VIDEO, dp(CARD_DP), wide = true) { bmp ->
                    picture.scaleType = ImageView.ScaleType.CENTER_CROP
                    picture.setImageBitmap(bmp)
                    if (isAttachedToWindow) {
                        picture.alpha = 0f
                        picture.animate().alpha(1f).setDuration(Motion.SHORT).start()
                    }
                }
            }
            addView(Progress(context, item.progress), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PICTURE_DP)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val kind = if (item.video) R.drawable.ic_movie else if (local != null) R.drawable.ic_music_note else R.drawable.ic_reader
                val icon = ImageView(context).apply {
                    setImageDrawable(Ui.icon(context, kind, Ui.accent(context)))
                }
                addView(icon, LayoutParams(dp(16), dp(16)))
                val doc = item.doc
                if (doc != null) icon.setImageDrawable(com.swiftbrowser.docs.DocIcon(doc.kind, dp(16))) else if (local == null) Favicons.load(item.url) { bmp -> if (bmp != null) icon.setImageBitmap(bmp) }
                val where = local?.parentFile?.let { com.swiftbrowser.files.FileIndex.placeOf(context, it) }
                addView(Ui.text(context, Ui.Type.CAPTION, doc?.kind?.label ?: where ?: site, lines = 1).apply { setPadding(dp(6), 0, 0, 0) },
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(Ui.text(context, Ui.Type.LABEL, name, lines = 2).apply {
                setPadding(0, dp(6), 0, 0)
                minLines = 2
            })
            addView(Ui.text(context, Ui.Type.CAPTION, item.caption, lines = 1).apply { setPadding(0, dp(4), 0, 0) })
        })
        contentDescription = "Continue $name, ${item.caption}"
        setOnClickListener { onOpen(item) }
        setOnLongClickListener {
            onHold(item)
            true
        }
        Ui.pressable(this, 0.96f)
    }

    /** 3.0.1: the round mark on a card without its picture: play for a video, a page for an article (3.0.3: [glyph]). */
    private fun badge(glyph: Int, tint: Int): android.graphics.drawable.Drawable {
        val disc = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xEBFFFFFF.toInt())
        }
        val mark = androidx.core.content.ContextCompat.getDrawable(context, glyph)!!.mutate()
        mark.setTint(tint)
        return android.graphics.drawable.LayerDrawable(arrayOf(disc, mark)).apply {
            setLayerSize(0, dp(48), dp(48))
            setLayerSize(1, dp(26), dp(26))
            setLayerGravity(1, Gravity.CENTER)
        }
    }

    /** How far the video was watched or the article read: a bar along the picture's bottom, as on YouTube. */
    private class Progress(ctx: Context, private val share: Float) : View(ctx) {
        private val paint = Paint()

        override fun onDraw(c: Canvas) {
            paint.color = 0x40000000
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = Ui.accent(context)
            c.drawRect(0f, 0f, width * share.coerceIn(0.04f, 1f), height.toFloat(), paint)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** A 16:9 picture on a 232 dp card. */
        const val PICTURE_DP = 130
        const val CARD_DP = 232
    }
}
