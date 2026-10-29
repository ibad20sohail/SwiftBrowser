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
        fun onDocumentsTile()
    }

    var listener: Listener? = null

    private val topSpace: Space
    private val logo: View
    private val incognitoBox: View
    private val search: LinearLayout

    /** The search box, which slides into the address bar when tapped (see MainActivity). */
    val searchBox: View get() = search

    /** The next shortcuts shown come in with a short fade (after one was added, moved or removed). */
    private var animateTiles = false

    /** The shortcuts shown, in order (without the Add button). */
    private var shownTiles: List<TopSites.Tile> = emptyList()
    private var tilesBuiltWithDocs = false

    // Dragging a shortcut to another place.
    private var dragged: View? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragMoved = false
    private var hovered: View? = null
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val hint: TextView
    private val searchIcon: ImageView
    private val tilesBox: LinearLayout
    private val tilesTitle: View
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

    private val iconListener: (String) -> Unit = { key ->
        tileViews.filter { (it.tag as? TopSites.Tile)?.let { t -> com.swiftbrowser.core.Favicons.key(t.url) } == key }.forEach { bindIcon(it) }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_home, this, true)
        topSpace = findViewById(R.id.home_top_space)
        logo = findViewById(R.id.home_logo)
        findViewById<ImageView>(R.id.home_logo_mark).setImageDrawable(Ui.logo(context))
        incognitoBox = findViewById(R.id.home_incognito)
        search = findViewById(R.id.home_search)
        hint = findViewById(R.id.home_search_hint)
        searchIcon = findViewById(R.id.home_search_icon)
        tilesBox = findViewById(R.id.home_tiles)
        tilesTitle = findViewById(R.id.home_tiles_title)
        scroll = findViewById(R.id.home_scroll)
        scroll.isVerticalScrollBarEnabled = false
        search.setOnClickListener { listener?.onSearchBoxClicked() }
        Ui.pressable(search, 0.98f)
        findViewById<View>(R.id.home_mic).setOnClickListener { listener?.onVoiceSearch() }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> news?.checkVisible() }
        pullIndicator = ImageView(context).apply {
            setImageDrawable(Ui.icon(context, R.drawable.ic_refresh, Ui.accent(context)))
            background = Ui.circle(color(R.color.ptr_bg))
            elevation = dp(4).toFloat()
            scaleType = ImageView.ScaleType.CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(pullIndicator, LayoutParams(dp(40), dp(40), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
        continueRow = ContinueRow(context, { listener?.onContinueClicked(it) }, { listener?.onContinueLongClicked(it) })
        val column = findViewById<LinearLayout>(R.id.home_column)
        // The row reaches into the column's side padding (it would be cut off there otherwise).
        column.clipToPadding = false
        // From edge to edge (past the column's padding): the cards scroll sideways out of sight.
        column.addView(continueRow, column.indexOfChild(tilesBox) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
            marginStart = -dp(16)
            marginEnd = -dp(16)
        })
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
                topMargin = dp(28)
            })
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
                    pullSpin?.cancel()
                    pullIndicator.visibility = View.VISIBLE
                    pullIndicator.alpha = 0f
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
                pullIndicator.alpha = (shown / trigger).coerceIn(0f, 1f)
                pullIndicator.rotation = shown * 2.5f
                pullIndicator.imageTintList = ColorStateList.valueOf(if (shown >= trigger) Ui.accent(context) else color(R.color.text_secondary))
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                pulling = false
                if (pullDistance >= trigger && ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    Haptics.tick(this)
                    pullIndicator.animate().translationY(dp(24).toFloat()).setDuration(Motion.SHORT).start()
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
        pullIndicator.animate().alpha(0f).translationY(-dp(40).toFloat()).setDuration(Motion.NORMAL).withEndAction {
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
        // them, and less room when the keyboard is up.
        val wanted = (height * if (incognito) 0.07f else if (newsOn) 0.06f else 0.15f).toInt()
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
        setBackgroundColor(color(if (incognito) R.color.incognito_bg else R.color.home_bg))
        logo.visibility = if (incognito) View.GONE else View.VISIBLE
        incognitoBox.visibility = if (incognito) View.VISIBLE else View.GONE
        tilesBox.visibility = if (incognito) View.GONE else View.VISIBLE
        if (incognito) tilesTitle.visibility = View.GONE
        if (incognito) continueRow.visibility = View.GONE else continueRow.refresh()
        updateNews()
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
        val engine = SearchEngine.current
        hint.text = engine.hint
        searchIcon.setImageResource(engine.icon)
        searchIcon.imageTintList = if (com.swiftbrowser.core.SearchEngine.isLogo(engine.icon)) null else hint.textColors
        post { updateTopSpace() }
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
        val key = "$profileId|${hidden.sorted()}|${SwiftApp.prefs.homeDocuments}|${TopSites.historyChanged}"
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
        if (tiles == shownTiles && tilesBox.childCount > 0 && !animateTiles && tilesBuiltWithDocs == SwiftApp.prefs.homeDocuments) return
        tilesBuiltWithDocs = SwiftApp.prefs.homeDocuments
        shownTiles = tiles
        tilesBox.removeAllViews()
        tileViews.clear()
        tilesTitle.visibility = if (incognito) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(context)
        // The last place is an "Add" button while there is room; Documents (2.4) just before it.
        val cells = ArrayList<Any?>(tiles)
        if (SwiftApp.prefs.homeDocuments) cells += DOCUMENTS
        if (tiles.size < TopSites.MAX) cells += null
        for (rowTiles in cells.chunked(4)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (t in rowTiles) {
                if (t == null) {
                    row.addView(addTile(inflater, row))
                    continue
                }
                if (t === DOCUMENTS) {
                    row.addView(documentsTile(inflater, row))
                    continue
                }
                t as TopSites.Tile
                val v = Palette.paint(inflater.inflate(R.layout.item_tile, row, false))
                v.findViewById<TextView>(R.id.tile_label).text = t.label
                val letter = v.findViewById<TextView>(R.id.tile_letter)
                letter.text = t.label.take(1).uppercase()
                // Soft, like the other tiles (2.6): a light tint of the colour, the letter in it.
                val strong = letterColor(t.key)
                letter.setTextColor(strong)
                letter.background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(androidx.core.graphics.ColorUtils.blendARGB(color(R.color.card_bg), strong, 0.2f))
                }
                v.setOnClickListener { listener?.onTileClicked(t.url) }
                // Held: it can be dragged to another place; let go without moving and its menu opens.
                v.setOnLongClickListener {
                    Haptics.hold(v)
                    startDrag(v)
                    true
                }
                v.setOnTouchListener { view, ev -> dragTouch(view, ev) }
                Ui.pressable(v, 0.92f)
                v.tag = t
                tileViews += v
                bindIcon(v)
                row.addView(v)
            }
            repeat(4 - rowTiles.size) { row.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            tilesBox.addView(row)
        }
        if (animateTiles) {
            animateTiles = false
            var i = 0
            for (r in 0 until tilesBox.childCount) {
                val row = tilesBox.getChildAt(r) as? LinearLayout ?: continue
                for (c in 0 until row.childCount) {
                    val v = row.getChildAt(c)
                    if (v is Space) continue
                    v.alpha = 0f
                    v.scaleX = 0.9f
                    v.scaleY = 0.9f
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(i++ * 12L).setDuration(170).start()
                }
            }
        }
    }

    private fun startDrag(v: View) {
        dragged = v
        dragMoved = false
        dragStartX = lastRawX
        dragStartY = lastRawY
        // The home page doesn't scroll while a shortcut is dragged.
        v.parent?.requestDisallowInterceptTouchEvent(true)
        // No touch highlight trailing behind it, and the press effect doesn't undo the lift.
        v.stateListAnimator = null
        v.isPressed = false
        v.jumpDrawablesToCurrentState()
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
        v.elevation = dp(6).toFloat()
        // It is drawn above the other rows as it leaves its own.
        (v.parent as? ViewGroup)?.let { row ->
            row.clipChildren = false
            row.translationZ = dp(6).toFloat()
        }
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
                    setHovered(tileAt(ev.rawX, ev.rawY, except = v))
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val target = if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) hovered else null
                val moved = dragMoved
                endDrag(v)
                val tile = v.tag as? TopSites.Tile
                when {
                    tile == null -> Unit
                    !moved && ev.actionMasked == android.view.MotionEvent.ACTION_UP -> listener?.onTileLongClicked(tile, v)
                    target != null -> moveTile(tile, target.tag as? TopSites.Tile)
                }
                return true
            }
        }
        return true
    }

    private fun endDrag(v: View) {
        dragged = null
        v.isPressed = false
        setHovered(null)
        v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(150).withEndAction { Ui.pressable(v, 0.92f) }.start()
        v.elevation = 0f
        (v.parent as? ViewGroup)?.translationZ = 0f
    }

    private fun setHovered(v: View?) {
        if (v === hovered) return
        hovered?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
        hovered = v
        v?.animate()?.scaleX(0.88f)?.scaleY(0.88f)?.setDuration(100)?.start()
        if (v != null) Haptics.tick(v)
    }

    /** The shortcut under the finger (screen coordinates). */
    private fun tileAt(rawX: Float, rawY: Float, except: View): View? {
        val at = IntArray(2)
        for (t in tileViews) {
            if (t === except) continue
            t.getLocationOnScreen(at)
            if (rawX >= at[0] && rawX < at[0] + t.width && rawY >= at[1] && rawY < at[1] + t.height) return t
        }
        return null
    }

    /**
     * [tile] goes where [target] is. It and the shortcuts before it become your own shortcuts, so
     * they stay where they are (most visited sites after them still change by themselves).
     */
    private fun moveTile(tile: TopSites.Tile, target: TopSites.Tile?) {
        if (target == null || target.key == tile.key) return
        val order = shownTiles.toMutableList()
        val from = order.indexOfFirst { it.key == tile.key }
        val to = order.indexOfFirst { it.key == target.key }
        if (from < 0 || to < 0) return
        order.add(to, order.removeAt(from))
        val pid = Profiles.currentId
        val keep = TopSites.pinned(pid).map { it.key }.toSet() + tile.key + order.take(to + 1).map { it.key }
        TopSites.setPinned(pid, order.filter { it.key in keep }.map { it.copy(pinned = true) })
        refreshTiles()
    }

    /** Documents (2.4): the phone's PDFs and Office files. Hold it to take it off the home page. */
    private fun documentsTile(inflater: LayoutInflater, row: LinearLayout): View {
        val v = Palette.paint(inflater.inflate(R.layout.item_tile, row, false))
        v.findViewById<TextView>(R.id.tile_label).text = "Documents"
        v.findViewById<View>(R.id.tile_letter).visibility = View.GONE
        v.findViewById<ImageView>(R.id.tile_icon).apply {
            visibility = View.VISIBLE
            setImageDrawable(Ui.icon(context, R.drawable.ic_documents, Ui.onAccent(context)))
            background = Ui.shape(context, Ui.accent(context), 18)
            scaleType = ImageView.ScaleType.CENTER
        }
        v.contentDescription = "Documents"
        v.setOnClickListener { listener?.onDocumentsTile() }
        v.setOnLongClickListener {
            Haptics.hold(v)
            val a = context as? android.app.Activity ?: return@setOnLongClickListener true
            Sheets.actions(a, "Documents", "Your PDFs, Word, Excel and PowerPoint files", listOf(
                Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { listener?.onDocumentsTile() },
                Sheets.Action(R.drawable.ic_close, "Take off the home page") {
                    SwiftApp.prefs.homeDocuments = false
                    refreshTiles()
                    Messages.show(a, "Documents is still in the ≡ menu", "Undo") {
                        SwiftApp.prefs.homeDocuments = true
                        refreshTiles()
                    }
                },
            ), iconRes = R.drawable.ic_documents)
            true
        }
        Ui.pressable(v, 0.92f)
        return v
    }

    private fun addTile(inflater: LayoutInflater, row: LinearLayout): View {
        val v = Palette.paint(inflater.inflate(R.layout.item_tile, row, false))
        v.findViewById<TextView>(R.id.tile_label).text = "Add"
        v.findViewById<TextView>(R.id.tile_letter).apply {
            text = "+"
            setTextColor(color(R.color.text_secondary))
            textSize = 26f
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), color(R.color.outline))
            }
        }
        v.contentDescription = "Add a shortcut"
        v.setOnClickListener { listener?.onAddTile() }
        Ui.pressable(v, 0.92f)
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

    private fun letterColor(key: String): Int {
        val palette = Profiles.COLORS
        return palette[(key.hashCode() and 0x7fffffff) % palette.size]
    }

    private fun color(res: Int) = Palette.color(context, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** The Documents tile's place among the shortcuts. */
        val DOCUMENTS = Any()
    }
}
