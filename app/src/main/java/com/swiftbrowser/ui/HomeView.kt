package com.swiftbrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.SearchEngine
import com.swiftbrowser.core.TopSites
import com.swiftbrowser.tabs.TabManager

/**
 * The home page of new tabs: logo, a search box, shortcuts to your and the most visited sites,
 * and (2.0) the news. Pulling down at the top fetches the news again.
 */
class HomeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onSearchBoxClicked()
        fun onVoiceSearch()
        fun onTileClicked(url: String)
        fun onTileLongClicked(tile: TopSites.Tile, anchor: View)
        fun onAddTile()
        fun onStoryClicked(story: com.swiftbrowser.news.Story)
        fun onStoryLongClicked(story: com.swiftbrowser.news.Story)
        fun onNewsSettings()
        fun onContinueClicked(item: com.swiftbrowser.media.Unfinished.Item)
        fun onContinueLongClicked(item: com.swiftbrowser.media.Unfinished.Item)

        /** The profile pill under the logo (2.7): change profile. */
        fun onProfileClicked()

        /** 3.0.5: what the address bar should show changed: the page's picture, or whether the page is at its top. */
        fun onHomeBarChanged()
    }

    var listener: Listener? = null

    private val topSpace: Space
    private val logo: View

    /** The logo's picture, where the start screen's logo lands (2.7). */
    val logoMark: View
    private val incognitoBox: View
    private val search: LinearLayout

    /** The search box, which slides into the address bar when tapped (see MainActivity). */
    val searchBox: View get() = search

    /** The next shortcuts shown come in with a short fade (after one was added, moved or removed). */
    private var animateTiles = false

    /** The shortcuts shown, in order (without the Add button). */
    private var shownTiles: List<TopSites.Tile> = emptyList()

    /** 3.0.5: the note under full shortcuts, while it's shown. */

    // Dragging a shortcut to another place (2.7: the others make way while it is held, as in the menu).
    private var dragged: View? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragMoved = false

    /** Where the held shortcut was, and the place it would go now (in [tileViews]). */
    private var dragFrom = -1
    private var dragTo = -1

    /** Each shortcut's place in the shortcuts box when the drag began. */
    private var slots: List<android.graphics.Rect> = emptyList()
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val hint: TextView
    private val searchIcon: ImageView
    private val mic: ImageView
    private val tilesBox: LinearLayout
    private val tilesTitle: TextView
    private var incognito = false
    private var generation = 0
    private val tileViews = ArrayList<View>()
    private val scroll: android.widget.ScrollView

    /** Videos and articles left half-way, under the shortcuts (2.3). */
    private val continueRow: ContinueRow

    /** The news under the shortcuts (made when first shown; not in incognito). */
    private var news: NewsSection? = null
    private val newsOn: Boolean get() = !incognito && SwiftApp.prefs.newsEnabled

    // Pulling down at the top refreshes the news.
    private val pullIndicator: ImageView
    private var pullStartX = 0f
    private var pullStartY = 0f
    private var pulling = false
    private var pullDistance = 0f
    private var pullSpin: android.animation.ObjectAnimator? = null

    /** The pull has come far enough that letting go refreshes (one buzz as it gets there, as on a page). */
    private var pullArmed = false

    /** A picture read late fading in over what is behind the page now. */
    private var pictureFade: android.animation.ValueAnimator? = null

    /** 3.0.5: the page's picture; null when it has a colour or a gradient. */
    private var picture: android.graphics.drawable.Drawable? = null

    // The address bar's room, which the picture also fills: above the page, or below it with the bar at the bottom.
    private var barAbove = 0
    private var barBelow = 0

    /** The page is at its top: only then does the address bar let the picture through. */
    var atTop = true
        private set

    /** The address bar stands in this much room above or below the page. */
    fun setBarReach(above: Int, below: Int) {
        barAbove = above
        barBelow = below
        HomeBackground.reach(picture, above, below)
        updateBottomFade()
    }

    /** The part of the picture behind the address bar, drawn as the bar's background; null without a picture. */
    val barPicture: android.graphics.drawable.Drawable? get() = HomeBackground.barPart(picture)

    /** The colour for the address bar's buttons over the picture; null without one. */
    fun barInk(): Int? = HomeBackground.barInk(context, picture)

    /** About the colour along the picture's top edge; null without one. */
    fun pictureTopColour(): Int? = HomeBackground.topColour(context, picture)

    private fun usePicture(behind: android.graphics.drawable.Drawable?) {
        picture = behind?.takeIf { HomeBackground.barPart(it) != null }
        HomeBackground.reach(picture, barAbove, barBelow)
        updateBottomFade()
    }

    /**
     * With the see-through bar at the bottom nothing marks where the page ends, so its last few lines
     * fade out there instead of being cut straight across. Off as soon as the page moves: the bar is
     * solid then, and scrolling pays nothing for this.
     */
    private fun updateBottomFade() {
        val on = picture != null && barBelow > 0 && atTop
        if (scroll.isVerticalFadingEdgeEnabled != on) {
            scroll.isVerticalFadingEdgeEnabled = on
            scroll.invalidate()
        }
    }

    private val iconListener: (String) -> Unit = { key ->
        tileViews.filter { (it.tag as? TopSites.Tile)?.let { t -> com.swiftbrowser.core.Favicons.key(t.url) } == key }.forEach { bindIcon(it) }
    }

    /** Which profile is in use (its colour and name); a tap opens the profiles to change it (2.7). */
    private val profilePill = TextView(context).apply {
        Ui.style(this, Ui.Type.LABEL)
        gravity = android.view.Gravity.CENTER_VERTICAL
        minHeight = dp(40)
        setPadding(dp(14), dp(6), dp(10), dp(6))
        compoundDrawablePadding = dp(8)
        background = Ui.ripple(context, Ui.shape(context, color(R.color.surface_alt), 20), 20)
        setOnClickListener { listener?.onProfileClicked() }
        Ui.pressable(this, Motion.PRESS_BUTTON)
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_home, this, true)
        topSpace = findViewById(R.id.home_top_space)
        logo = findViewById(R.id.home_logo)
        logoMark = findViewById<ImageView>(R.id.home_logo_mark).apply { setImageDrawable(Ui.logo(context)) }
        incognitoBox = findViewById(R.id.home_incognito)
        search = findViewById(R.id.home_search)
        hint = findViewById(R.id.home_search_hint)
        searchIcon = findViewById(R.id.home_search_icon)
        // Swift's own font on every Android (it takes the phone's place by itself only below Android 9).
        AppFont.cut(Ui.Type.SUBTITLE.sp, Ui.Weight.REGULAR)?.let { hint.typeface = it }
        tilesBox = findViewById(R.id.home_tiles)
        tilesTitle = findViewById(R.id.home_tiles_title)
        scroll = findViewById(R.id.home_scroll)
        scroll.isVerticalScrollBarEnabled = false
        search.setOnClickListener { listener?.onSearchBoxClicked() }
        Ui.pressable(search, Motion.PRESS_CARD, morph = false)
        mic = findViewById(R.id.home_mic)
        mic.setOnClickListener { listener?.onVoiceSearch() }
        Ui.pressable(mic, Motion.PRESS_ICON, morph = false)
        scroll.setFadingEdgeLength(dp(BOTTOM_FADE_DP))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            news?.checkVisible()
            val top = y <= dp(AT_TOP_DP)
            if (top != atTop) {
                atTop = top
                updateBottomFade()
                listener?.onHomeBarChanged()
            }
        }
        pullIndicator = ImageView(context).apply {
            setImageDrawable(Ui.icon(context, R.drawable.ic_refresh, Ui.accent(context)))
            // The same circle as a page's pull to refresh.
            setBackgroundResource(R.drawable.bg_ptr)
            Palette.paint(this)
            elevation = dp(4).toFloat()
            scaleType = ImageView.ScaleType.CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(pullIndicator, LayoutParams(dp(40), dp(40), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
        continueRow = ContinueRow(context, ContinueRow.Part.BROWSER, { listener?.onContinueClicked(it) }, { listener?.onContinueLongClicked(it) })
        val column = findViewById<LinearLayout>(R.id.home_column)
        // The row reaches into the column's side padding (it would be cut off there otherwise).
        column.clipToPadding = false
        // From edge to edge (past the column's padding): the cards scroll sideways out of sight.
        column.addView(continueRow, column.indexOfChild(tilesBox) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ui.SECTION_ABOVE)
            marginStart = -dp(16)
            marginEnd = -dp(16)
        })
        // Under the logo: the profile in use, and a tap to change it (2.7).
        (logo.parent as? ViewGroup)?.let { parent ->
            parent.addView(profilePill, parent.indexOfChild(logo) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(14)
            })
        }
        updateProfile()
    }

    /** The profile changed (or was renamed or recoloured). */
    fun updateProfile() {
        val p = com.swiftbrowser.core.Profiles.current()
        profilePill.text = p.name
        // The Google account's photo when the profile is signed in (3.0), otherwise its colour.
        val photo = com.swiftbrowser.core.GoogleAccounts.picture(context, p.id)?.apply { setBounds(0, 0, dp(20), dp(20)) }
        val dot = photo ?: android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(p.color)
            setSize(dp(12), dp(12))
            setBounds(0, 0, dp(12), dp(12))
        }
        val arrow = Ui.icon(context, R.drawable.ic_down, Ui.textSecondary(context))?.apply { setBounds(0, 0, dp(18), dp(18)) }
        profilePill.setCompoundDrawablesRelative(dot, null, arrow, null)
        profilePill.contentDescription = "Profile: ${p.name}. Change profile"
        // 3.0: only when the address bar has no profile button: one way to change profile, not two.
        profilePill.visibility = if (incognito || ToolbarButtons.layout().has("profile")) View.GONE else View.VISIBLE
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Back from Settings, where the address bar's buttons may have changed (3.0).
        if (visibility == View.VISIBLE) updateProfile()
    }

    /** The home page went out of sight: news pictures are let go until it's back (2.3). */
    fun releasePictures() {
        news?.releasePictures()
    }

    /** Takes a card off "Continue". */
    fun removeContinue(item: com.swiftbrowser.media.Unfinished.Item) = continueRow.remove(item)

    /** Shows or hides the news, as set; [refresh] also brings it up to date. */
    private fun updateNews() {
        if (!newsOn) {
            news?.visibility = View.GONE
            return
        }
        val section = news ?: NewsSection(context, object : NewsSection.Listener {
            override fun onStoryClicked(story: com.swiftbrowser.news.Story) { listener?.onStoryClicked(story) }
            override fun onStoryLongClicked(story: com.swiftbrowser.news.Story) { listener?.onStoryLongClicked(story) }
            override fun onNewsSettings() { listener?.onNewsSettings() }
        }).also {
            news = it
            findViewById<LinearLayout>(R.id.home_column).addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Ui.SECTION_ABOVE)
            })
            applyOrder()
            it.setInk(ink)
        }
        section.visibility = View.VISIBLE
        section.show()
        post { section.checkVisible() }
    }

    /** After a site's stories were hidden, or the news settings changed. */
    fun refreshNews() {
        com.swiftbrowser.news.News.clearMemory()
        updateNews()
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!newsOn || news == null) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pullStartX = ev.x
                pullStartY = ev.y
                pulling = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - pullStartY
                if (dragged == null && scroll.scrollY == 0 && dy > touchSlop && dy > Math.abs(ev.x - pullStartX) * 1.5f) {
                    pulling = true
                    pullDistance = 0f
                    pullArmed = false
                    pullSpin?.cancel()
                    pullIndicator.animate().cancel()
                    pullIndicator.visibility = View.VISIBLE
                    pullIndicator.alpha = 0f
                    pullIndicator.scaleX = 0.6f
                    pullIndicator.scaleY = 0.6f
                    pullIndicator.translationY = -dp(40).toFloat()
                    return true
                }
            }
        }
        return false
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(ev)
        val trigger = dp(72).toFloat()
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                pullDistance = (ev.y - pullStartY - touchSlop).coerceAtLeast(0f) * 0.5f
                val shown = pullDistance.coerceAtMost(trigger * 1.3f)
                pullIndicator.translationY = shown - dp(40) + dp(16)
                pullIndicator.alpha = (shown / trigger).coerceIn(0.2f, 1f)
                pullIndicator.rotation = shown * 2.2f
                val scale = 0.6f + 0.4f * (shown / trigger).coerceAtMost(1f)
                pullIndicator.scaleX = scale
                pullIndicator.scaleY = scale
                pullIndicator.imageTintList = ColorStateList.valueOf(if (shown >= trigger) Ui.accent(context) else color(R.color.text_secondary))
                if (shown >= trigger && !pullArmed) Haptics.tick(pullIndicator)
                pullArmed = shown >= trigger
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                pulling = false
                if (pullDistance >= trigger && ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    Motion.anim(pullIndicator, Motion.SHORT).translationY(dp(24).toFloat()).scaleX(1f).scaleY(1f).alpha(1f).start()
                    pullSpin = android.animation.ObjectAnimator.ofFloat(pullIndicator, View.ROTATION, pullIndicator.rotation, pullIndicator.rotation + 360f).apply {
                        duration = 700
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        interpolator = null
                        start()
                    }
                    news?.refresh(force = true) { hidePull() } ?: hidePull()
                } else {
                    hidePull()
                }
            }
        }
        return true
    }

    private fun hidePull() {
        Motion.anim(pullIndicator, Motion.SHORT, Motion.exit).scaleX(0.3f).scaleY(0.3f).alpha(0f).withEndAction {
            pullSpin?.cancel()
            pullSpin = null
            pullIndicator.visibility = View.GONE
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Favicons.addListener(iconListener)
    }

    override fun onDetachedFromWindow() {
        Favicons.removeListener(iconListener)
        pictureFade?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) post { updateTopSpace() }
        // On a turned phone the page keeps a phone's width, centred (shortcuts don't spread apart).
        if (w != oldw) {
            val column = findViewById<LinearLayout>(R.id.home_column)
            val side = maxOf(dp(16), (w - dp(640)) / 2)
            if (column.paddingLeft != side) post { column.setPadding(side, column.paddingTop, side, column.paddingBottom) }
        }
    }

    private fun updateTopSpace() {
        // Logo and search box sit a bit above the middle, as in Chrome; higher with the news below
        // them, and less room when the keyboard is up. 3.0.1: a little higher still, so the page's
        // own things (shortcuts, Continue, news) start sooner.
        val wanted = (height * if (incognito) 0.07f else if (newsOn) 0.035f else 0.12f).toInt()
        val lp = topSpace.layoutParams
        if (lp.height != wanted) {
            lp.height = wanted
            topSpace.layoutParams = lp
        }
    }

    /** Shows the normal or the incognito home page, refreshing the shortcuts. */
    fun show(incognito: Boolean) {
        val changed = this.incognito != incognito
        this.incognito = incognito
        pictureFade?.cancel()
        // 3.0.4: a gradient or your own picture behind it, if chosen (never in incognito).
        val behind = HomeBackground.drawable(context, incognito) { late ->
            if (!this.incognito) {
                usePicture(late)
                fadeInPicture(late)
                applyInk(late)
                listener?.onHomeBarChanged()
            }
        }
        usePicture(behind)
        if (behind != null) background = behind else setBackgroundColor(color(if (incognito) R.color.incognito_bg else R.color.home_bg))
        applyInk(behind)
        logo.visibility = if (incognito) View.GONE else View.VISIBLE
        updateProfile()
        incognitoBox.visibility = if (incognito) View.VISIBLE else View.GONE
        tilesBox.visibility = if (incognito) View.GONE else View.VISIBLE
        if (incognito) tilesTitle.visibility = View.GONE
        if (incognito) continueRow.visibility = View.GONE else continueRow.refresh()
        updateNews()
        applyOrder()
        if (incognito) {
            search.background = GradientDrawable().apply {
                cornerRadius = dp(27).toFloat()
                setColor(color(R.color.incognito_url))
            }
            hint.setTextColor(color(R.color.incognito_hint))
        } else {
            search.setBackgroundResource(R.drawable.bg_home_search)
            Palette.paint(search)
            hint.setTextColor(color(R.color.text_secondary))
            loadTiles()
        }
        // The box's ripple over its shape, which changes above; a light one on incognito's dark box, also in the light look.
        if (changed || search.foreground == null) {
            val ripple = if (incognito) Ui.RIPPLE_ON_DARK else null
            search.foreground = Ui.ripple(context, null, 27, ripple)
            mic.background = Ui.rippleBorderless(context, ripple)
        }
        val engine = SearchEngine.current
        hint.text = engine.hint
        searchIcon.setImageResource(engine.icon)
        searchIcon.imageTintList = if (com.swiftbrowser.core.SearchEngine.isLogo(engine.icon)) null else hint.textColors
        mic.imageTintList = hint.textColors
        post { updateTopSpace() }
        listener?.onHomeBarChanged()
    }

    /**
     * [picture], read after the page was shown, fades in over what is behind the page now, without
     * anything being laid out. Only the background is redrawn while it does.
     */
    private fun fadeInPicture(picture: android.graphics.drawable.Drawable) {
        pictureFade?.cancel()
        val old = background
        if (old == null || !isShown || !Motion.enabled(context)) {
            background = picture
            return
        }
        picture.alpha = 0
        val both = android.graphics.drawable.LayerDrawable(arrayOf(old, picture))
        background = both
        pictureFade = android.animation.ValueAnimator.ofInt(0, 255).apply {
            duration = Motion.PAGE_MS
            interpolator = Motion.standard
            addUpdateListener { picture.alpha = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    picture.alpha = 255
                    if (background === both) background = picture
                }
            })
            start()
        }
    }

    /**
     * 3.0.2: the parts under the search box in the order chosen (HomeOrder: Continue, the shortcuts and
     * the news by default). Nothing is left out, only placed; moved only when the order changed.
     */
    private fun applyOrder() {
        val column = findViewById<LinearLayout>(R.id.home_column)
        val parts = com.swiftbrowser.core.HomeOrder.parse(SwiftApp.prefs.homeOrder).flatMap { part ->
            when (part) {
                com.swiftbrowser.core.HomeOrder.CONTINUE -> listOf<View>(continueRow)
                com.swiftbrowser.core.HomeOrder.SHORTCUTS -> listOf<View>(tilesTitle, tilesBox)
                else -> listOfNotNull<View>(news)
            }
        }
        val after = column.indexOfChild(search) + 1
        if (after <= 0) return
        val shown = (after until column.childCount).map { column.getChildAt(it) }.filter { it in parts }
        if (shown == parts) return
        for (v in parts) column.removeView(v)
        parts.forEachIndexed { i, v -> column.addView(v, after + i) }
    }

    fun scrollToTop() {
        findViewById<View>(R.id.home_scroll).scrollTo(0, 0)
    }

    /** What the shortcuts shown were worked out for, and when (2.6). */
    private var tilesFor = ""
    private var tilesAt = 0L

    private fun loadTiles(force: Boolean = false) {
        val profileId = Profiles.currentId
        val hidden = SwiftApp.prefs.hiddenTiles
        // Shown again within half a minute (back from a page, a new tab): the same shortcuts, without
        // reading the history again (2.6).
        val key = "$profileId|${hidden.sorted()}|${TopSites.historyChanged}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && key == tilesFor && now - tilesAt < 30_000 && tilesBox.childCount > 0) return
        tilesFor = key
        tilesAt = now
        val gen = ++generation
        TabManager.io.execute {
            val tiles = TopSites.load(SwiftApp.db, profileId, hidden)
            post { if (gen == generation) bindTiles(tiles) }
        }
    }

    /** Reloads the shortcuts after one was added, changed or removed. */
    fun refreshTiles() {
        if (incognito) return
        animateTiles = true
        loadTiles(force = true)
    }

    private fun bindTiles(tiles: List<TopSites.Tile>) {
        // The same shortcuts as on screen: nothing to build again (2.6).
        if (tiles == shownTiles && tilesBox.childCount > 0 && !animateTiles) return
        // Where they stand now: after a change only the new one fades in and the others glide over.
        val before = if (animateTiles && isShown) tilePlaces() else null
        if (before != null) fadeAwayRemoved(tiles)
        shownTiles = tiles
        tilesBox.removeAllViews()
        tileViews.clear()
        tilesTitle.visibility = if (incognito) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(context)
        // The last place is an "Add" button while there is room (2.9: no Documents tile any more).
        val cells = ArrayList<Any?>(tiles)
        if (tiles.size < TopSites.MAX) cells += null
        for (rowTiles in cells.chunked(COLUMNS)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (t in rowTiles) {
                if (t == null) {
                    row.addView(addTile(inflater, row))
                    continue
                }
                t as TopSites.Tile
                val v = Palette.paint(inflater.inflate(R.layout.item_tile, row, false))
                v.findViewById<TextView>(R.id.tile_label).text = t.label
                // 3.0.1: until (or unless) the site's icon comes, its letter in a circle of its colour, the
                // icon's size on the same tile: no more tiles of different colours side by side.
                v.findViewById<ImageView>(R.id.tile_icon).apply {
                    setImageDrawable(Ui.monogram(context, t.label, t.key, 26))
                    visibility = View.VISIBLE
                    // 3.0.5: a little dimmer in the dark look (it holds the site's icon when it comes).
                    SoftPictures.apply(this)
                }
                v.findViewById<View>(R.id.tile_letter).visibility = View.GONE
                v.setOnClickListener { listener?.onTileClicked(t.url) }
                // Held: it can be dragged to another place; let go without moving and its menu opens.
                v.setOnLongClickListener {
                    Haptics.hold(v)
                    startDrag(v)
                    true
                }
                v.setOnTouchListener { view, ev -> dragTouch(view, ev) }
                finishCell(v)
                v.tag = t
                tileViews += v
                bindIcon(v)
                row.addView(v)
            }
            repeat(COLUMNS - rowTiles.size) { row.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            tilesBox.addView(row)
        }
        // 3.0: when they are all taken, the Add button makes way for a word on why (it went without one).
        if (tiles.size >= TopSites.MAX) {
            val fullNote = Ui.text(context, Ui.Type.CAPTION, "You can have up to ${TopSites.MAX} shortcuts. Hold one to remove it.").apply {
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), 0)
            }
            tilesBox.addView(fullNote)
        }
        paintTilesInk()
        if (animateTiles) {
            animateTiles = false
            if (before != null) settleTiles(before)
        }
    }

    /** How the text straight on the page's picture looks now; null without a picture (HomeBackground.Ink). */
    private var ink: HomeBackground.Ink? = null

    private fun applyInk(behind: android.graphics.drawable.Drawable?) {
        ink = HomeBackground.inkOf(context, behind)
        HomeBackground.paint(findViewById<TextView>(R.id.home_logo_text), ink, Ui.text(context))
        HomeBackground.paint(tilesTitle, ink, Ui.text(context))
        paintTilesInk()
        continueRow.setInk(ink)
        news?.setInk(ink)
    }

    /** The shortcuts' names, and the note when they are all taken. */
    private fun paintTilesInk() {
        for (r in 0 until tilesBox.childCount) {
            when (val line = tilesBox.getChildAt(r)) {
                is TextView -> HomeBackground.paint(line, ink, Ui.textSecondary(context))
                is ViewGroup -> for (c in 0 until line.childCount) {
                    line.getChildAt(c).findViewById<TextView>(R.id.tile_label)?.let { HomeBackground.paint(it, ink, Ui.text(context)) }
                }
            }
        }
    }

    /** What every cell has: its name in Swift's font, the ripple on its 48 dp box and the press of the whole cell. */
    private fun finishCell(v: View) {
        AppFont.cut(Ui.Type.SMALL.sp, Ui.Weight.REGULAR)?.let { v.findViewById<TextView>(R.id.tile_label).typeface = it }
        v.findViewById<View>(R.id.tile_box).foreground = Ui.ripple(context, null, Ui.R_SMALL)
        Ui.pressable(v, Motion.PRESS_ICON, morph = false)
    }

    /** The cells on the page now: the shortcuts and the Add button. */
    private fun tileCells(): List<View> = (0 until tilesBox.childCount)
        .mapNotNull { tilesBox.getChildAt(it) as? LinearLayout }
        .flatMap { row -> (0 until row.childCount).map { row.getChildAt(it) } }
        .filter { it !is Space }

    private fun cellKey(v: View) = (v.tag as? TopSites.Tile)?.key ?: ADD_CELL

    /** Each cell's place in the shortcuts box, as laid out now. */
    private fun tilePlaces(): Map<String, android.graphics.Point> = tileCells().associate { v ->
        val row = v.parent as View
        cellKey(v) to android.graphics.Point(row.left + v.left, row.top + v.top)
    }

    /** Shortcuts no longer among [kept] fade where they stand, in the box's overlay, while the page is rebuilt under them. */
    private fun fadeAwayRemoved(kept: List<TopSites.Tile>) {
        val keys = kept.mapTo(HashSet()) { it.key }
        for (v in tileViews.filter { cellKey(it) !in keys }) {
            tilesBox.overlay.add(v)
            Motion.anim(v, Motion.PAGE_OUT_MS, Motion.exit).alpha(0f).withEndAction { tilesBox.overlay.remove(v) }.start()
        }
    }

    /**
     * After a shortcut was added, moved or removed: a new cell fades in and the others glide from
     * where they stood ([before]). Laid out once first, so nothing is measured while they move.
     */
    private fun settleTiles(before: Map<String, android.graphics.Point>) {
        tilesBox.clipChildren = false
        for (r in 0 until tilesBox.childCount) (tilesBox.getChildAt(r) as? ViewGroup)?.clipChildren = false
        val observer = tilesBox.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                (if (observer.isAlive) observer else tilesBox.viewTreeObserver).removeOnPreDrawListener(this)
                for (v in tileCells()) {
                    val old = before[cellKey(v)]
                    if (old == null) {
                        v.alpha = 0f
                        Motion.anim(v, Motion.SHORT, Motion.enter).alpha(1f).start()
                        continue
                    }
                    val row = v.parent as View
                    val dx = (old.x - row.left - v.left).toFloat()
                    val dy = (old.y - row.top - v.top).toFloat()
                    if (dx == 0f && dy == 0f) continue
                    v.translationX = dx
                    v.translationY = dy
                    Motion.anim(v, Motion.SHORT).translationX(0f).translationY(0f).start()
                }
                return true
            }
        })
    }

    private fun startDrag(v: View) {
        dragged = v
        dragMoved = false
        dragStartX = lastRawX
        dragStartY = lastRawY
        dragFrom = tileViews.indexOf(v)
        dragTo = dragFrom
        // Every shortcut's place, as laid out now: the others slide between these while it is held.
        slots = tileViews.map { t ->
            val row = t.parent as View
            android.graphics.Rect(row.left + t.left, row.top + t.top, row.left + t.right, row.top + t.bottom)
        }
        // The home page doesn't scroll while a shortcut is dragged.
        v.parent?.requestDisallowInterceptTouchEvent(true)
        // No touch highlight trailing behind it, and the press effect doesn't undo the lift.
        v.stateListAnimator = null
        v.isPressed = false
        v.jumpDrawablesToCurrentState()
        Motion.anim(v, Motion.PRESS_DOWN).scaleX(1.12f).scaleY(1.12f).start()
        v.elevation = dp(6).toFloat()
        // Shortcuts move between rows, and the held one is drawn above the other rows.
        for (r in 0 until tilesBox.childCount) (tilesBox.getChildAt(r) as? ViewGroup)?.clipChildren = false
        (v.parent as? ViewGroup)?.translationZ = dp(6).toFloat()
        tilesBox.clipChildren = false
    }

    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun dragTouch(v: View, ev: android.view.MotionEvent): Boolean {
        lastRawX = ev.rawX
        lastRawY = ev.rawY
        if (dragged !== v) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - dragStartX
                val dy = ev.rawY - dragStartY
                if (!dragMoved && Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) dragMoved = true
                if (dragMoved) {
                    v.translationX = dx
                    v.translationY = dy
                    placeAt(ev.rawX, ev.rawY)
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val moved = dragMoved
                val up = ev.actionMasked == android.view.MotionEvent.ACTION_UP
                val tile = v.tag as? TopSites.Tile
                if (!moved && up && tile != null) {
                    endDrag(v, dragFrom)
                    listener?.onTileLongClicked(tile, v)
                } else {
                    // Cancelled: everything goes back; let go: it settles into the place made for it.
                    endDrag(v, if (up) dragTo else dragFrom)
                }
                return true
            }
        }
        return true
    }

    /** The place under the finger: the shortcuts between there and the held one's place make way. */
    private fun placeAt(rawX: Float, rawY: Float) {
        val at = IntArray(2)
        tilesBox.getLocationOnScreen(at)
        val x = (rawX - at[0]).toInt()
        val y = (rawY - at[1]).toInt()
        val to = slots.indexOfFirst { it.contains(x, y) }
        if (to < 0 || to == dragTo) return
        dragTo = to
        Haptics.tick(tilesBox)
        for ((i, t) in tileViews.withIndex()) {
            if (i == dragFrom) continue
            val place = when {
                dragFrom < to && i in dragFrom + 1..to -> i - 1
                dragFrom > to && i in to until dragFrom -> i + 1
                else -> i
            }
            Motion.anim(t, Motion.SHORT).translationX((slots[place].left - slots[i].left).toFloat())
                .translationY((slots[place].top - slots[i].top).toFloat()).start()
        }
    }

    /** The held shortcut settles into place [to] (its own, when nothing moved), and the order is kept. */
    private fun endDrag(v: View, to: Int) {
        dragged = null
        v.isPressed = false
        val from = dragFrom
        val place = slots.getOrNull(to)
        val own = slots.getOrNull(from)
        val tx = if (place != null && own != null) (place.left - own.left).toFloat() else 0f
        val ty = if (place != null && own != null) (place.top - own.top).toFloat() else 0f
        if (to == from) for ((i, t) in tileViews.withIndex()) if (i != from) Motion.anim(t, Motion.SHORT).translationX(0f).translationY(0f).start()
        Motion.anim(v, Motion.SHORT).translationX(tx).translationY(ty).scaleX(1f).scaleY(1f).withEndAction {
            v.elevation = 0f
            (v.parent as? ViewGroup)?.translationZ = 0f
            Ui.pressable(v, Motion.PRESS_ICON, morph = false)
            if (to != from && from >= 0 && to >= 0 && dragged == null) moveTile(from, to)
        }.start()
    }

    /**
     * The shortcut at [from] goes to [to]. It and the shortcuts before it become your own shortcuts,
     * so they stay where they are (most visited sites after them still change by themselves). The
     * shortcuts already stand there: they are put in their new rows as they are, without a rebuild.
     */
    private fun moveTile(from: Int, to: Int) {
        if (from !in shownTiles.indices || to !in shownTiles.indices) return
        val order = shownTiles.toMutableList()
        val tile = order[from]
        order.add(to, order.removeAt(from))
        val pid = Profiles.currentId
        val keep = TopSites.pinned(pid).map { it.key }.toSet() + tile.key + order.take(to + 1).map { it.key }
        TopSites.setPinned(pid, order.filter { it.key in keep }.map { it.copy(pinned = true) })
        // What the home page loads next time: the same, so it isn't built again.
        shownTiles = order.map { if (it.key in keep) it.copy(pinned = true) else it }
        val views = tileViews.toMutableList()
        views.add(to, views.removeAt(from))
        val rows = (0 until tilesBox.childCount).mapNotNull { tilesBox.getChildAt(it) as? LinearLayout }
        val others = rows.flatMap { row -> (0 until row.childCount).map { row.getChildAt(it) } }
            .filter { it !in tileViews && it !is Space }
        rows.forEach { it.removeAllViews() }
        for ((r, cells) in (views + others).chunked(COLUMNS).withIndex()) {
            val row = rows.getOrNull(r) ?: break
            for (c in cells) {
                c.translationX = 0f
                c.translationY = 0f
                row.addView(c)
            }
            repeat(COLUMNS - cells.size) { row.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f)) }
        }
        tileViews.clear()
        tileViews.addAll(views)
        views.forEachIndexed { i, v -> v.tag = shownTiles[i] }
    }

    private fun addTile(inflater: LayoutInflater, row: LinearLayout): View {
        val v = Palette.paint(inflater.inflate(R.layout.item_tile, row, false))
        v.findViewById<TextView>(R.id.tile_label).text = "Add"
        // 3.0.5: a plain tile like the others (the tile's own shape, no outline around it) with a real
        // add icon in the secondary colour, the size of the other tiles' icons (it was a typed "+").
        v.findViewById<ImageView>(R.id.tile_icon).apply {
            setImageDrawable(Ui.icon(context, R.drawable.ic_add, color(R.color.text_secondary)))
            visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.tile_letter).visibility = View.GONE
        v.contentDescription = "Add a shortcut"
        v.setOnClickListener { listener?.onAddTile() }
        finishCell(v)
        return v
    }

    private fun bindIcon(v: View) {
        val tile = v.tag as? TopSites.Tile ?: return
        Favicons.load(tile.url) { bmp ->
            if (bmp == null || v !in tileViews || v.tag !== tile) return@load
            val icon = v.findViewById<ImageView>(R.id.tile_icon)
            icon.setImageBitmap(bmp)
            icon.visibility = View.VISIBLE
            v.findViewById<View>(R.id.tile_letter).visibility = View.GONE
        }
    }

    private fun color(res: Int) = Palette.color(context, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** How far from the top still counts as the page's top, and how long the fade at the page's end is (dp). */
        const val AT_TOP_DP = 2
        const val BOTTOM_FADE_DP = 40

        /** Shortcuts to a row (2.7: five, smaller). */
        const val COLUMNS = 5

        /** The Add button's place among the shortcuts' keys. */
        const val ADD_CELL = "+"
    }
}
