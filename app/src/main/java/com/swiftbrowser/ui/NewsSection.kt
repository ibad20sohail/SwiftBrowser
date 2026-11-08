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
 * The news under the home page's shortcuts (2.0): topic chips, then a card for each story, with
 * its picture across the top, the site and the headline (3.0.5: always; compact rows are gone).
 * Grey shapes stand in while the first stories load; more cards appear while scrolling down, and
 * pictures load only as their cards come near the screen.
 */
@SuppressLint("ViewConstructor")
class NewsSection(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onStoryClicked(story: Story)
        fun onStoryLongClicked(story: Story)
        fun onNewsSettings()
    }

    private companion object {
        /**
         * Rows made when the stories show, then how many more each time the end comes near (3.0: 15
         * and 15; the home page measures everything in it again whenever rows are added).
         */
        const val FIRST = 5
        const val PAGE = 10
        /** A card's picture is decoded to this many dp high, and 16:9 wide (sharp, still light). */
        const val CARD_PICTURE_DP = 180

        /** Sites whose icon was asked for in this run (a site without one isn't asked again). */
        val askedIcons = HashSet<String>()
    }

    /** 3.0: kept in the settings, so it stays when the screen is made again (dark mode, colours). */
    private var topic = com.swiftbrowser.SwiftApp.prefs.newsTopic
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
    private val settingsButton: ImageButton
    private val title: TextView
    private var refreshing = false
    private val cardPictureSize = Ui.dp(context, CARD_PICTURE_DP)
    private val cardPictureWidth = cardPictureSize * 16 / 9

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
            setPadding(0, 0, 0, Ui.dp(context, 4))
        }
        // 3.0.4: as bold as the home page's other headings (the scale's HEADING). 3.0.5: just its word
        // wide, 8 dp in by its own padding as before, so a soft backing can go behind it on the picture.
        title = Ui.text(context, Ui.Type.HEADING, "News").apply { setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0) }
        head.addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(View(context), LayoutParams(0, 0, 1f))
        refreshButton = iconButton(R.drawable.ic_refresh, "Refresh news") { refresh(force = true) }
        head.addView(refreshButton)
        settingsButton = iconButton(R.drawable.ic_tune, "News settings") { listener.onNewsSettings() }
        head.addView(settingsButton)
        addView(head)

        chips.isSingleLine = true
        chips.isSingleSelection = true
        chips.isSelectionRequired = true
        chips.chipSpacingHorizontal = Ui.dp(context, 8)
        // 3.0.5: the row's side padding is the chips' own, so the fade below reaches the row's real end
        // (padding on the row left a sliver of the next chip showing past the fade).
        chips.setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0)
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
            // 3.0.4: topics fade out at the edge where more of them wait (they used to be cut off hard).
            Ui.fadeEdges(this)
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
        // 3.0: the chosen topic filled with the accent and ticked; its soft accent of before was hardly
        // told from the others in the light look. 3.0.1: in the dark look the soft accent again (as the
        // ≡ menu's switches and the Downloads filters), plainly apart from the grey there.
        val night = Palette.isNight(context)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val bg = ColorStateList(states, intArrayOf(if (night) Ui.accentContainer(context) else Ui.accent(context), Ui.color(context, R.color.surface_alt)))
        val fg = ColorStateList(states, intArrayOf(if (night) Ui.onAccentContainer(context) else Ui.onAccent(context), Ui.text(context)))
        for (t in topics) {
            chips.addView(Chip(context).apply {
                id = View.generateViewId()
                text = if (t.key == "nation") News.countryName() else t.label
                isCheckable = true
                isCheckedIconVisible = true
                checkedIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_check)
                checkedIconTint = fg
                chipBackgroundColor = bg
                setTextColor(fg)
                chipStrokeWidth = 0f
                isChecked = t.key == topic
                setOnClickListener {
                    if (!isChecked) isChecked = true
                    if (topic != t.key) {
                        topic = t.key
                        com.swiftbrowser.SwiftApp.prefs.newsTopic = t.key
                        load(switched = true)
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

    /** [switched]: another topic was chosen, so its stories come in with a short fade (2.3). */
    private fun load(switched: Boolean = false) {
        val kept = News.cached(topic)
        if (kept != null) {
            showKept(kept, switched)
            return
        }
        // 3.0: the stories kept on the phone are read on the news thread; grey shapes meanwhile
        // (the stories already shown stay, unless another topic was chosen).
        if (switched || stories.isEmpty()) showSkeleton()
        val asked = topic
        News.loadKept(context, asked) { list ->
            if (asked != topic) return@loadKept
            if (list != null) {
                showKept(list, switched)
            } else {
                showSkeleton()
                refresh(force = false)
            }
        }
    }

    private fun showKept(kept: List<Story>, switched: Boolean) {
        // Opening the home page again doesn't rebuild the same rows.
        if (kept != stories || body.childCount == 0) setStories(kept, animate = switched)
        if (News.stale(topic)) refresh(force = false)
    }

    private fun showSkeleton() {
        stories = emptyList()
        body.removeAllViews()
        lastScan = Int.MIN_VALUE
        message.visibility = View.GONE
        skeleton.visibility = View.VISIBLE
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
        lastScan = Int.MIN_VALUE
        message.removeAllViews()
        message.addView(Ui.emptyState(context, icon, title, hint).apply { setPadding(0, 0, 0, 0) })
        Motion.fadeIn(message, fromDy = 8)
    }

    private fun setStories(list: List<Story>, animate: Boolean) {
        stories = list
        ready.clear()
        readyFrom = -1
        lastScan = Int.MIN_VALUE
        skeleton.visibility = View.GONE
        message.visibility = View.GONE
        body.removeAllViews()
        lastScan = Int.MIN_VALUE
        shown = 0
        showMore()
        if (animate) Motion.stagger(body, step = 30, max = 6)
        post { checkVisible() }
    }

    /** 3.0.5: the home page's picture is behind the news (HomeView.setOnPicture). */
    fun setOnPicture(on: Boolean) {
        // Its heading on a soft pill, its two buttons on soft round backings, as they have one colour.
        title.background = if (on) Ui.shape(context, HomeBackground.backing(context, Ui.text(context)), Ui.R_SMALL) else null
        title.setPadding(Ui.dp(context, 8), if (on) Ui.dp(context, 3) else 0, if (on) Ui.dp(context, 12) else Ui.dp(context, 8), if (on) Ui.dp(context, 3) else 0)
        val fill = if (on) HomeBackground.backing(context, Ui.textSecondary(context), 3.0) else 0
        for (b in listOf(refreshButton, settingsButton)) b.background = if (on) Ui.ripple(context, Ui.circle(fill), 22) else Ui.rippleBorderless(context)
    }

    private fun showMore() {
        val end = (shown + if (shown == 0) FIRST else PAGE).coerceAtMost(stories.size)
        val prebuilt = if (readyFrom == shown) ready.toList() else emptyList()
        ready.clear()
        readyFrom = -1
        // 3.0.5: no lines between the stories: each has room around it.
        for (i in shown until end) body.addView(prebuilt.getOrNull(i - shown) ?: card(stories[i]))
        shown = end
        prepareMore()
    }

    /**
     * The next rows, built ahead one at a time while the phone has nothing else to do (2.6), so
     * reaching the end of the news doesn't stop the scroll to build them all at once.
     */
    private val ready = ArrayList<View>()
    private var readyFrom = -1

    private fun prepareMore() {
        if (shown >= stories.size) return
        readyFrom = shown
        ready.clear()
        val list = stories
        android.os.Looper.myQueue().addIdleHandler {
            val next = readyFrom + ready.size
            if (stories !== list || readyFrom != shown || !isAttachedToWindow || next >= (shown + PAGE).coerceAtMost(stories.size)) {
                return@addIdleHandler false
            }
            ready += card(stories[next])
            true
        }
    }

    /** Where the last look at the rows was made from: small scrolls don't look again (2.6). */
    private var lastScan = Int.MIN_VALUE

    private val visible = android.graphics.Rect()

    /** Loads pictures and more rows for what can be seen now (the home page scrolled or changed). */
    fun checkVisible() {
        if (visibility == View.VISIBLE && getLocalVisibleRect(visible)) onScrolled(visible.bottom, visible.top)
    }

    /** The home page went out of sight: its pictures are let go (recent ones stay in PreviewLoader's small cache). */
    fun releasePictures() {
        for (i in 0 until body.childCount) release(body.getChildAt(i))
        lastScan = Int.MIN_VALUE
    }

    private fun release(row: View) {
        val image = row.getTag(R.id.row_image) as? String ?: return
        if (row.getTag(R.id.row_action) != null) return
        row.findViewById<ImageView>(R.id.row_icon)?.setImageDrawable(null)
        row.setTag(R.id.row_action, image)
    }

    /**
     * The home page scrolled: [visibleBottom] is how far down this section can be seen (px from
     * its top). Pictures load for rows coming into view, and more rows are added near the end.
     */
    fun onScrolled(visibleBottom: Int, visibleTop: Int = 0) {
        if (shown < stories.size && visibleBottom > body.top + body.height - Ui.dp(context, 600)) {
            showMore()
            lastScan = Int.MIN_VALUE
        }
        // Every scroll frame comes here: the rows are looked at again only after 48 dp of scrolling.
        if (lastScan != Int.MIN_VALUE && kotlin.math.abs(visibleBottom - lastScan) < Ui.dp(context, 48)) return
        lastScan = visibleBottom
        val limit = visibleBottom + Ui.dp(context, 300)
        // Pictures far out of sight (2.3) are let go, so a long scroll down the news doesn't keep them all.
        val far = Ui.dp(context, 1600)
        for (i in 0 until body.childCount) {
            val row = body.getChildAt(i)
            val top = body.top + row.top
            if (top + row.height < visibleTop - far || top > visibleBottom + far) {
                release(row)
                continue
            }
            if (top > limit) continue
            val pending = row.getTag(R.id.row_action) as? String ?: continue
            row.setTag(R.id.row_action, null)
            val picture = row.findViewById<ImageView>(R.id.row_icon)
            PreviewLoader.load(context as Activity, pending, cardPictureSize, cardPictureWidth) { bmp ->
                picture.setImageBitmap(bmp)
                picture.alpha = 0f
                picture.animate().alpha(1f).setDuration(Motion.NORMAL).start()
            }
        }
    }

    /** A story as a card (2.2): the picture across the top when it has one, then the site and the headline. */
    private fun card(s: Story): View {
        val ctx = context
        val card = LinearLayout(ctx).apply {
            orientation = VERTICAL
            // 3.0.5: plain filled, no shadow: tile_bg (a step off the home page, as the Continue cards
            // and shortcuts are), so it shows on a white page and over a picture too.
            background = Ui.shape(ctx, Ui.color(ctx, R.color.tile_bg), Ui.R_MEDIUM)
            foreground = Ui.ripple(ctx, null, Ui.R_MEDIUM)
            clipToOutline = true
            tag = s
            contentDescription = "${s.title}. ${s.source}"
            setOnClickListener { listener.onStoryClicked(s) }
            setOnLongClickListener {
                Haptics.hold(this)
                listener.onStoryLongClicked(s)
                true
            }
            // 3.0.5: at the home page's side margin (16 dp), no more in than the cards above and below.
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
            }
        }
        Ui.pressable(card, 0.98f)
        if (s.image != null && !DataSaver.active) {
            val picture = WidePicture(ctx).apply {
                id = R.id.row_icon
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Ui.color(ctx, R.color.skeleton))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // 3.0.5: a little dimmer in the dark look.
                SoftPictures.apply(this)
            }
            card.addView(picture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val kept = PreviewLoader.cached(s.image, cardPictureSize, cardPictureWidth)
            if (kept != null) picture.setImageBitmap(kept) else card.setTag(R.id.row_action, s.image)
            card.setTag(R.id.row_image, s.image)
        }
        val texts = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 14))
        }
        val meta = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(FixedImage(ctx).apply {
            id = R.id.tile_icon
            setImageDrawable(Ui.icon(ctx, R.drawable.ic_globe, Ui.textSecondary(ctx)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // 3.0.5: the site's icon when it comes, a little dimmer in the dark look.
            SoftPictures.apply(this)
        }, LayoutParams(Ui.dp(ctx, 16), Ui.dp(ctx, 16)).apply { marginEnd = Ui.dp(ctx, 8) })
        val ago = if (s.time > 0) " · " + DateUtils.getRelativeTimeSpanString(s.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE) else ""
        meta.addView(Ui.text(ctx, Ui.Type.CAPTION, s.source + ago, lines = 1), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        texts.addView(meta)
        // 3.0.5: calmer headlines, in medium at the scale's heading size (bold at 19 sp over three
        // lines was heavy); the card's picture still leads, and they read beside the 15.5 sp news rows.
        texts.addView(Ui.text(ctx, Ui.Type.HEADING, s.title, lines = 3).apply {
            typeface = Ui.MEDIUM
            setPadding(0, Ui.dp(ctx, 8), 0, 0)
        })
        card.addView(texts)
        bindIcon(card, s)
        return card
    }

    /**
     * A picture or icon whose size never depends on what it shows (3.0): a picture arriving only
     * redraws it. Before, each one (an icon, then a picture, in every row) had the whole home page,
     * hundreds of views with the news, measured again.
     */
    private open class FixedImage(ctx: Context) : ImageView(ctx) {
        private var settingImage = false

        override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
            settingImage = true
            try {
                super.setImageDrawable(drawable)
            } finally {
                settingImage = false
            }
        }

        override fun requestLayout() {
            if (!settingImage) super.requestLayout()
        }
    }

    /** A picture as wide as its card and 16:9 high. */
    private class WidePicture(ctx: Context) : FixedImage(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, w * 9 / 16)
        }
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
