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
import com.swiftbrowser.media.Unfinished
import com.swiftbrowser.tabs.TabManager

/**
 * "Continue" on the home page (2.3): videos and articles left half-way, as a row of cards that
 * scrolls sideways. Hidden while there is nothing to continue.
 */
class ContinueRow(context: Context, private val onOpen: (Unfinished.Item) -> Unit, private val onHold: (Unfinished.Item) -> Unit) :
    LinearLayout(context) {

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
        addView(TextView(context).apply {
            text = "Continue"
            textSize = 15.5f
            typeface = Ui.MEDIUM
            setTextColor(Ui.text(context))
            setPadding(dp(24), 0, 0, dp(10))
        })
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            addView(cards)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Loads the row again (the home page came up, or one was removed). */
    fun refresh() {
        val gen = ++generation
        val profileId = Profiles.currentId
        TabManager.io.execute {
            val items = Unfinished.load(profileId)
            post { if (gen == generation) bind(items) }
        }
    }

    private fun bind(items: List<Unfinished.Item>) {
        val keys = items.map { it.url + it.progress }
        if (keys == shown && cards.childCount == items.size) return
        val wasShown = visibility == VISIBLE
        shown = keys
        cards.removeAllViews()
        items.forEachIndexed { i, item -> cards.addView(card(item), LayoutParams(dp(232), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(10) }) }
        visibility = if (items.isEmpty()) GONE else VISIBLE
        if (!wasShown && items.isNotEmpty()) {
            alpha = 0f
            translationY = dp(8).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    /** Takes [item]'s card away with a short fade, then the row closes up. */
    fun remove(item: Unfinished.Item) {
        Unfinished.remove(item)
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
        background = Ui.shape(context, Ui.color(context, R.color.tile_bg), 18)
        clipToOutline = true
        val site = UrlUtils.host(item.url)?.removePrefix("www.")?.removePrefix("m.").orEmpty()
        addView(FrameLayout(context).apply {
            setBackgroundColor(Ui.color(context, R.color.surface_alt))
            val doc = item.doc
            val picture = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                if (doc != null) {
                    setImageDrawable(com.swiftbrowser.docs.DocIcon(doc.kind, dp(56)))
                } else {
                    setImageDrawable(Ui.icon(context, if (item.video) R.drawable.ic_movie else R.drawable.ic_reader, Ui.textSecondary(context)))
                    alpha = 0.6f
                }
            }
            addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (doc != null) {
                // The document's first page (or its own picture), from the top.
                com.swiftbrowser.docs.DocThumbs.load(context, doc.uri, doc.kind, dp(232)) { bmp ->
                    val top = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, minOf(bmp.height, bmp.width * PICTURE_DP / 232))
                    picture.scaleType = ImageView.ScaleType.CENTER_CROP
                    picture.setImageBitmap(top)
                    picture.alpha = 0f
                    picture.animate().alpha(1f).setDuration(Motion.NORMAL).start()
                }
            }
            item.image?.let { url ->
                // Small and kept in memory a while (PreviewLoader); none on mobile data with Data saver.
                PreviewLoader.load(context as android.app.Activity, url, dp(PICTURE_DP)) { bmp ->
                    picture.scaleType = ImageView.ScaleType.CENTER_CROP
                    picture.setImageBitmap(bmp)
                    picture.alpha = 0f
                    picture.animate().alpha(1f).setDuration(Motion.NORMAL).start()
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
                val icon = ImageView(context).apply {
                    setImageDrawable(Ui.icon(context, if (item.video) R.drawable.ic_movie else R.drawable.ic_reader, Ui.accent(context)))
                }
                addView(icon, LayoutParams(dp(16), dp(16)))
                val doc = item.doc
                if (doc != null) icon.setImageDrawable(com.swiftbrowser.docs.DocIcon(doc.kind, dp(16))) else Favicons.load(item.url) { bmp -> if (bmp != null) icon.setImageBitmap(bmp) }
                addView(Ui.text(context, Ui.Type.CAPTION, doc?.kind?.label ?: site, lines = 1).apply { setPadding(dp(6), 0, 0, 0) },
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(Ui.text(context, Ui.Type.LABEL, item.title.ifBlank { site }, lines = 2).apply {
                setPadding(0, dp(6), 0, 0)
                minLines = 2
            })
            addView(Ui.text(context, Ui.Type.CAPTION, item.caption, lines = 1).apply { setPadding(0, dp(4), 0, 0) })
        })
        contentDescription = "Continue ${item.title.ifBlank { site }}, ${item.caption}"
        setOnClickListener { onOpen(item) }
        setOnLongClickListener {
            onHold(item)
            true
        }
        Ui.pressable(this, 0.96f)
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
    }
}
