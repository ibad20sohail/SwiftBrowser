package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.swiftbrowser.R
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.engine.DataSaver
import com.swiftbrowser.news.News
import com.swiftbrowser.news.Story

/**
 * The news under the home page's shortcuts (2.0): topic chips, then compact rows (the site's icon,
 * name and how long ago, the headline, and a small picture on the right when the story has one).
 * Grey shapes stand in while the first stories load; more rows appear while scrolling down, and
 * pictures load only as their rows come near the screen.
 */
@SuppressLint("ViewConstructor")
class NewsSection(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onStoryClicked(story: Story)
        fun onStoryLongClicked(story: Story)
        fun onNewsSettings()
    }

    private companion object {
        const val PAGE = 15
        const val PICTURE_DP = 76

        /** Sites whose icon was asked for in this run (a site without one isn't asked again). */
        val askedIcons = HashSet<String>()
    }

    private var topic = "top"
    private var stories: List<Story> = emptyList()
    private var shown = 0
    private val chips = ChipGroup(context)
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val skeleton = SkeletonView(context, rows = 4, picture = true)
    private val message = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24))
        visibility = View.GONE
    }
    private val refreshButton: ImageButton
    private var refreshing = false
    private val pictureSize = Ui.dp(context, PICTURE_DP)

    private val iconListener: (String) -> Unit = { key ->
        for (i in 0 until body.childCount) {
            val row = body.getChildAt(i)
            val story = row.tag as? Story ?: continue
            if (Favicons.key(story.sourceUrl ?: story.url) == key) bindIcon(row, story)
        }
    }

    init {
        orientation = VERTICAL
        // The heading: News, then refresh and the news settings.
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 8), 0, 0, Ui.dp(context, 4))
        }
        head.addView(Ui.text(context, Ui.Type.SUBTITLE, "News"), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        refreshButton = iconButton(R.drawable.ic_refresh, "Refresh news") { refresh(force = true) }
        head.addView(refreshButton)
        head.addView(iconButton(R.drawable.ic_tune, "News settings") { listener.onNewsSettings() })
        addView(head)

        chips.isSingleLine = true
        chips.isSingleSelection = true
        chips.isSelectionRequired = true
        chips.chipSpacingHorizontal = Ui.dp(context, 8)
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0)
            addView(chips)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(context, 8)
        })
        addView(skeleton)
        addView(message)
        addView(body)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Favicons.addListener(iconListener)
    }

    override fun onDetachedFromWindow() {
        Favicons.removeListener(iconListener)
        super.onDetachedFromWindow()
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageDrawable(Ui.icon(context, icon, Ui.textSecondary(context)))
        background = Ui.rippleBorderless(context)
        contentDescription = label
        layoutParams = LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44))
        setOnClickListener { onClick() }
        Ui.pressable(this, 0.88f)
    }

    private var chipsFor = ""

    private fun buildChips() {
        val topics = News.shownTopics()
        if (topics.none { it.key == topic }) topic = "top"
        val signature = News.country() + topics.joinToString { it.key }
        if (signature == chipsFor) return
        chipsFor = signature
        chips.removeAllViews()
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val bg = ColorStateList(states, intArrayOf(Ui.accentContainer(context), Ui.color(context, R.color.surface_alt)))
        val fg = ColorStateList(states, intArrayOf(Ui.onAccentContainer(context), Ui.text(context)))
        for (t in topics) {
            chips.addView(Chip(context).apply {
                id = View.generateViewId()
                text = if (t.key == "nation") News.countryName() else t.label
                isCheckable = true
                isCheckedIconVisible = false
                chipBackgroundColor = bg
                setTextColor(fg)
                chipStrokeWidth = 0f
                isChecked = t.key == topic
                setOnClickListener {
                    if (!isChecked) isChecked = true
                    if (topic != t.key) {
                        topic = t.key
                        load()
                    }
                }
            })
        }
    }

    /** The home page was opened: the kept stories at once, fresh ones when they are over an hour old. */
    fun show() {
        buildChips()
        load()
    }

    private fun load() {
        val kept = News.cached(context, topic)
        if (kept != null) {
            // Opening the home page again doesn't rebuild the same rows.
            if (kept != stories || body.childCount == 0) setStories(kept, animate = false)
            if (News.stale(topic)) refresh(force = false)
        } else {
            stories = emptyList()
            body.removeAllViews()
            message.visibility = View.GONE
            skeleton.visibility = View.VISIBLE
            refresh(force = false)
        }
    }

    /** Fetches the topic's stories again ([force]: also when they are fresh: pull or the refresh button). */
    fun refresh(force: Boolean, done: (() -> Unit)? = null) {
        if (!force && !News.stale(topic) && stories.isNotEmpty()) {
            done?.invoke()
            return
        }
        if (refreshing) {
            done?.invoke()
            return
        }
        refreshing = true
        refreshButton.animate().rotationBy(360f).setDuration(700).setInterpolator(Motion.standard).start()
        val asked = topic
        News.refresh(context, asked) { list ->
            refreshing = false
            done?.invoke()
            if (asked != topic || !isAttachedToWindow) return@refresh
            when {
                list != null && list.isNotEmpty() -> setStories(list, animate = stories.isEmpty())
                list != null -> showMessage(R.drawable.ic_info, "No stories here yet", "Try another topic, or check the address of your news sites.")
                stories.isEmpty() -> showMessage(R.drawable.ic_offline, "Couldn't load the news", "Check your connection, then pull down or tap refresh.")
                force -> (context as? Activity)?.let { Messages.show(it, "Couldn't load the news. Check your connection.") }
            }
        }
    }

    private fun showMessage(icon: Int, title: String, hint: String) {
        skeleton.visibility = View.GONE
        body.removeAllViews()
        message.removeAllViews()
        message.addView(Ui.emptyState(context, icon, title, hint).apply { setPadding(0, 0, 0, 0) })
        message.visibility = View.VISIBLE
    }

    private fun setStories(list: List<Story>, animate: Boolean) {
        stories = list
        skeleton.visibility = View.GONE
        message.visibility = View.GONE
        body.removeAllViews()
        shown = 0
        showMore()
        if (animate) Motion.stagger(body, step = 30, max = 6)
        post { checkVisible() }
    }

    private fun showMore() {
        val end = (shown + PAGE).coerceAtMost(stories.size)
        for (i in shown until end) {
            if (body.childCount > 0) body.addView(Ui.divider(context, 16))
            body.addView(row(stories[i]))
        }
        shown = end
    }

    private val visible = android.graphics.Rect()

    /** Loads pictures and more rows for what can be seen now (the home page scrolled or changed). */
    fun checkVisible() {
        if (visibility == View.VISIBLE && getLocalVisibleRect(visible)) onScrolled(visible.bottom)
    }

    /**
     * The home page scrolled: [visibleBottom] is how far down this section can be seen (px from
     * its top). Pictures load for rows coming into view, and more rows are added near the end.
     */
    fun onScrolled(visibleBottom: Int) {
        if (shown < stories.size && visibleBottom > body.top + body.height - Ui.dp(context, 600)) showMore()
        val limit = visibleBottom + Ui.dp(context, 300)
        for (i in 0 until body.childCount) {
            val row = body.getChildAt(i)
            if (body.top + row.top > limit) break
            val pending = row.getTag(R.id.row_action) as? String ?: continue
            row.setTag(R.id.row_action, null)
            val picture = row.findViewById<ImageView>(R.id.row_icon)
            PreviewLoader.load(context as Activity, pending, pictureSize) { bmp ->
                picture.setImageBitmap(bmp)
                picture.alpha = 0f
                picture.animate().alpha(1f).setDuration(Motion.NORMAL).start()
            }
        }
    }

    private fun row(s: Story): View {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12))
            background = Ui.ripple(ctx, null, Ui.R_MEDIUM)
            tag = s
            contentDescription = "${s.title}. ${s.source}"
            setOnClickListener { listener.onStoryClicked(s) }
            setOnLongClickListener {
                Haptics.hold(this)
                listener.onStoryLongClicked(s)
                true
            }
        }
        Ui.pressable(row, 0.98f)
        val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
        val meta = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(ImageView(ctx).apply {
            id = R.id.tile_icon
            setImageDrawable(Ui.icon(ctx, R.drawable.ic_globe, Ui.textSecondary(ctx)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(Ui.dp(ctx, 16), Ui.dp(ctx, 16)).apply { marginEnd = Ui.dp(ctx, 8) })
        val ago = if (s.time > 0) " · " + DateUtils.getRelativeTimeSpanString(s.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE) else ""
        meta.addView(Ui.text(ctx, Ui.Type.CAPTION, s.source + ago, lines = 1), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        texts.addView(meta)
        texts.addView(Ui.text(ctx, Ui.Type.SUBTITLE, s.title, lines = 3).apply { setPadding(0, Ui.dp(ctx, 6), 0, 0) })
        row.addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (s.image != null && !DataSaver.active) {
            val picture = ImageView(ctx).apply {
                id = R.id.row_icon
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.shape(ctx, Ui.color(ctx, R.color.skeleton), Ui.R_SMALL)
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(picture, LayoutParams(pictureSize, pictureSize).apply { marginStart = Ui.dp(ctx, 14) })
            val kept = PreviewLoader.cached(s.image, pictureSize)
            if (kept != null) picture.setImageBitmap(kept) else row.setTag(R.id.row_action, s.image)
        }
        bindIcon(row, s)
        return row
    }

    private fun bindIcon(row: View, s: Story) {
        val site = s.sourceUrl ?: s.url
        val icon = row.findViewById<ImageView>(R.id.tile_icon) ?: return
        Favicons.load(site) { bmp ->
            if (bmp != null) {
                if (row.tag === s) {
                    icon.imageTintList = null
                    icon.setImageBitmap(bmp)
                }
            } else if (s.sourceUrl != null && !DataSaver.active && askedIcons.add(Favicons.key(s.sourceUrl) ?: return@load)) {
                // Fetched once and kept (Favicons), like the icons of visited sites.
                Favicons.onPageIcon(s.sourceUrl, null)
            }
        }
    }
}
