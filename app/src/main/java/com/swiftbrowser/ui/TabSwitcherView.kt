package com.swiftbrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabGroup
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.tabs.TabRules
import com.swiftbrowser.tabs.Thumbnails
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Full-screen grid of tabs, like Chrome's tab switcher, with a separate page for incognito tabs.
 *  - Hold a tab and drag it to move it; hold it over another tab for a moment and let go to group them.
 *  - A group is one card with pictures of its tabs; tapping it shows the group's tabs.
 *  - Tabs not opened for a while are gathered under "Inactive tabs".
 *  - The search button finds a tab by its title or address.
 *  - 3.0.4: pinned tabs come first; a tab playing sound has a speaker to mute it; the tabs can be
 *    a compact list instead of picture cards (the button by the search, remembered).
 * Nothing here runs while a page is shown, so it costs browsing no speed.
 */
class TabSwitcherView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onTabChosen(tab: Tab)
        fun onNewTab(incognito: Boolean)
        fun onNewTabInGroup(group: TabGroup)
        fun onSwitcherClosed()
    }

    var listener: Listener? = null

    /** Which page is shown: normal tabs or incognito tabs. */
    var showingIncognito = false
        private set

    val isOpen: Boolean get() = visibility == View.VISIBLE && !closing

    private enum class Mode { GRID, GROUP, INACTIVE, SEARCH }

    private var mode = Mode.GRID
    private var openGroup: TabGroup? = null
    private var query = ""

    private var closing = false
    private val grid: RecyclerView
    private val empty: View
    private val header: View
    private val subheader: View
    private val subBack: ImageButton
    private val subDot: View
    private val subTitle: TextView
    private val subSearch: EditText
    private val subAdd: ImageButton
    private val subMore: ImageButton
    private val subAction: TextView
    private val newTab: TextView
    private val searchButton: ImageButton

    /** 3.0.4: picture cards or a compact list ([asList], as last chosen). */
    private val layoutButton: ImageButton
    private var asList = SwiftApp.prefs.tabsAsList
    private val toggle: View
    private val toggleNormal: View
    private val toggleIncognito: View
    private val normalCount: TextView
    private val incognitoIcon: ImageView
    private val adapter = GridAdapter()

    /** How cards come and go; off for the one refresh a page move replaces it ([skipListMotion]). */
    private val listMotion = Motion.listAnimator()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // Dragging a card.
    private var dragging = false
    private var moved = false
    private var maxDrag = 0f
    private var refreshPending = false
    private var mergeCandidate: RecyclerView.ViewHolder? = null
    private var mergeHolder: RecyclerView.ViewHolder? = null
    private var draggedHolder: RecyclerView.ViewHolder? = null
    private val mergeCheck = Runnable { mergeCandidate?.let { if (dragging) setMergeTarget(it) } }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_tab_switcher, this, true)
        isClickable = true
        isFocusable = true
        grid = findViewById(R.id.switcher_grid)
        empty = findViewById(R.id.switcher_empty)
        header = findViewById(R.id.switcher_header)
        subheader = findViewById(R.id.switcher_subheader)
        subBack = findViewById(R.id.sub_back)
        subDot = findViewById(R.id.sub_dot)
        subTitle = findViewById(R.id.sub_title)
        subSearch = findViewById(R.id.sub_search)
        subAdd = findViewById(R.id.sub_add)
        subMore = findViewById(R.id.sub_more)
        subAction = findViewById(R.id.sub_action)
        newTab = findViewById(R.id.switcher_new)
        // 3.0.3: a press springs, as the toolbar's buttons do.
        Ui.pressable(newTab, Motion.PRESS_BUTTON)
        searchButton = findViewById(R.id.switcher_search)
        layoutButton = findViewById(R.id.switcher_layout)
        toggle = findViewById(R.id.switcher_toggle)
        toggleNormal = findViewById(R.id.toggle_normal)
        toggleIncognito = findViewById(R.id.toggle_incognito)
        normalCount = findViewById(R.id.toggle_normal_count)
        incognitoIcon = findViewById(R.id.toggle_incognito_icon)

        val layout = GridLayoutManager(context, columns())
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.items.getOrNull(position) is Item.Banner) layout.spanCount else 1
        }
        grid.layoutManager = layout
        grid.adapter = adapter
        // 3.0: a card that changed is redrawn in place, without the cross-fade of a new copy over it.
        grid.itemAnimator = listMotion
        ItemTouchHelper(DragAndSwipe()).attachToRecyclerView(grid)
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && mode == Mode.SEARCH) hideKeyboard()
            }
        })

        newTab.setOnClickListener { listener?.onNewTab(showingIncognito) }
        searchButton.setOnClickListener { setMode(Mode.SEARCH) }
        layoutButton.setOnClickListener { switchLayout() }
        toggleNormal.setOnClickListener { setPage(false) }
        toggleIncognito.setOnClickListener { setPage(true) }
        // 3.0.3: they spring when pressed, and holding the icon-only ones shows their names.
        for (v in listOf(toggleNormal, toggleIncognito, searchButton, layoutButton)) {
            Ui.pressable(v, Motion.PRESS_ICON)
            v.contentDescription?.let { androidx.appcompat.widget.TooltipCompat.setTooltipText(v, it) }
        }
        showLayoutButton()
        findViewById<View>(R.id.switcher_empty_new).setOnClickListener {
            if (mode == Mode.SEARCH) back() else listener?.onNewTab(showingIncognito)
        }
        for (v in listOf(subBack, subAdd, subMore, subAction)) Ui.pressable(v, Motion.PRESS_ICON, morph = false)
        subBack.setOnClickListener { back() }
        subTitle.setOnClickListener { openGroup?.let { renameGroup(it) } }
        subAdd.setOnClickListener { openGroup?.let { listener?.onNewTabInGroup(it) } }
        subMore.setOnClickListener { openGroup?.let { groupOptions(it) } }
        subAction.setOnClickListener { if (mode == Mode.INACTIVE) closeInactive() }
        subSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (mode != Mode.SEARCH) return
                query = s?.toString().orEmpty()
                refresh()
            }
        })
        subSearch.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
    }

    /**
     * Opens the grid. [page] is where the open tab's page is on the screen (in this view's
     * coordinates): its picture shrinks from there into its card.
     */
    fun open(incognito: Boolean, page: Rect? = null) {
        showingIncognito = incognito
        closing = false
        mode = Mode.GRID
        openGroup = null
        query = ""
        applyPage()
        // The screen may have turned while the grid was hidden.
        (grid.layoutManager as GridLayoutManager).let { if (it.spanCount != columns()) it.spanCount = columns() }
        refresh(scrollToCurrent = true)
        animate().cancel()
        stopZoom()
        for (i in 0 until childCount) getChildAt(i).apply {
            animate().cancel()
            alpha = 1f
        }
        visibility = View.VISIBLE
        alpha = 1f
        // The grid fades in around the open tab's picture, which stays solid as it shrinks.
        fadeBackground(0, 255)
        for (i in 0 until childCount) getChildAt(i).let { v ->
            if (v === zoomView) return@let
            v.alpha = 0f
            Motion.anim(v, ZOOM_MS).alpha(1f).withLayer().start()
        }
        val tab = TabManager.current
        if (page != null && tab != null) post { zoom(tab, page, intoCard = true) }
    }

    /** Closes the grid; with [page], the chosen tab's picture grows from its card to there. */
    fun close(page: Rect? = null) {
        if (visibility != View.VISIBLE || closing) return
        closing = true
        hideKeyboard()
        animate().cancel()
        val tab = TabManager.current
        val zoomed = page != null && tab != null && zoom(tab, page, intoCard = false)
        // With the picture growing to the page, the grid fades behind it; otherwise all of it fades.
        val fading = if (zoomed) (0 until childCount).map { getChildAt(it) }.filter { it !== zoomView } else listOf(this)
        fading.forEach { it.animate().cancel() }
        if (zoomed) fadeBackground(255, 0)
        fading.forEachIndexed { i, v ->
            Motion.anim(v, if (zoomed) ZOOM_MS else Motion.SHORT).alpha(0f).withLayer().withEndAction(if (i > 0) null else Runnable {
                visibility = View.GONE
                fading.forEach { it.alpha = 1f }
                alpha = 1f
                background?.alpha = 255
                closing = false
                stopZoom()
            }).start()
        }
        listener?.onSwitcherClosed()
    }

    // ------------------------------------------------------------------ the open tab's picture moving between page and card

    private var zoomView: TopCropImageView? = null
    private var zoomSpring: androidx.dynamicanimation.animation.SpringAnimation? = null
    private var zoomHidden: View? = null

    /** The moving picture's corners now (px): square on the page, its place's own in the grid. */
    private var zoomCorners = 0f
    private val zoomOutline = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, zoomCorners)
        }
    }

    /** Moves [tab]'s picture between [page] and its card; false when its card isn't on screen. */
    private fun zoom(tab: Tab, page: Rect, intoCard: Boolean): Boolean {
        val idx = adapter.items.indexOfFirst {
            (it is Item.TabCard && it.tab === tab) || (it is Item.GroupCard && it.group.id == tab.groupId)
        }
        val holder = if (idx >= 0) grid.findViewHolderForAdapterPosition(idx) else null
        val box = when (holder) {
            is CardHolder -> holder.thumbBox
            is RowHolder -> holder.thumbBox
            is GroupHolder -> holder.mosaic
            else -> null
        } ?: return false
        val boxCorners = Ui.dpf(context, when (holder) {
            is RowHolder -> Corners.nested(ROW_RADIUS, ROW_GAP)
            is GroupHolder -> Corners.nested(CARD_RADIUS, MOSAIC_MARGIN)
            else -> Corners.nested(CARD_RADIUS, CARD_GAP)
        }.toFloat())
        val card = Rect()
        box.getDrawingRect(card)
        offsetDescendantRectToMyCoords(box, card)
        if (card.isEmpty || !Rect(0, 0, width, height).intersect(card)) return false

        // 3.0.4: still on its way (the grid closed as it opened): on from where the picture is now.
        val moving = zoomView?.takeIf { zoomSpring != null && it.visibility == View.VISIBLE }?.let { Rect(it.left, it.top, it.right, it.bottom) }
        stopZoom()
        val v = zoomView ?: TopCropImageView(context).also {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            it.outlineProvider = zoomOutline
            it.clipToOutline = true
            addView(it, LayoutParams(0, 0))
            zoomView = it
        }
        v.setBackgroundColor(color(if (tab.incognito) R.color.incognito_bg else if (tab.isHome) R.color.home_bg else R.color.page_bg))
        v.setImageDrawable(null)
        if (!tab.isHome) Thumbnails.load(tab) { bmp -> if (zoomView === v && bmp != null) v.setImageBitmap(bmp) }
        v.visibility = View.VISIBLE
        v.bringToFront()
        // The picture's place stays empty while it is on the way in or out; the card's title and ring stay.
        box.alpha = 0f
        zoomHidden = box
        val from = moving ?: if (intoCard) Rect(page) else card
        val to = if (intoCard) card else Rect(page)
        val cornersFrom = if (moving != null) zoomCorners else if (intoCard) 0f else boxCorners
        val cornersTo = if (intoCard) boxCorners else 0f
        zoomCorners = cornersFrom
        v.layout(from.left, from.top, from.right, from.bottom)
        v.invalidateOutline()
        // 3.0.4 (spring motion): on a spring, quick and settling without a bounce.
        if (!Motion.enabled(context)) {
            zoomCorners = cornersTo
            v.layout(to.left, to.top, to.right, to.bottom)
            v.invalidateOutline()
            if (intoCard) stopZoom()
            return true
        }
        val spring = androidx.dynamicanimation.animation.SpringAnimation(androidx.dynamicanimation.animation.FloatValueHolder(0f)).apply {
            spring = androidx.dynamicanimation.animation.SpringForce(1f).setStiffness(ZOOM_STIFFNESS).setDampingRatio(1f)
            minimumVisibleChange = androidx.dynamicanimation.animation.DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
            addUpdateListener { _, f, _ ->
                zoomCorners = cornersFrom + (cornersTo - cornersFrom) * f
                v.layout(
                    lerp(from.left, to.left, f), lerp(from.top, to.top, f),
                    lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f),
                )
                v.invalidateOutline()
            }
        }
        spring.addEndListener { a, canceled, _, _ ->
            if (zoomSpring !== a || canceled) return@addEndListener
            zoomSpring = null
            if (intoCard) stopZoom()
        }
        zoomSpring = spring
        spring.start()
        return true
    }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    private var backgroundAnim: android.animation.ValueAnimator? = null

    private fun fadeBackground(from: Int, to: Int) {
        backgroundAnim?.cancel()
        val bg = background ?: return
        bg.alpha = from
        backgroundAnim = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = ZOOM_MS
            interpolator = Motion.standard
            addUpdateListener { bg.alpha = it.animatedValue as Int }
            start()
        }
    }

    /** The page's colour going from [from] to [to], over the same time as the page's own move. */
    private fun fadePageColour(from: Int, to: Int) {
        backgroundAnim?.cancel()
        backgroundAnim = android.animation.ValueAnimator.ofArgb(from, to).apply {
            duration = Motion.PAGE_MS
            interpolator = Motion.page
            addUpdateListener { setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun stopZoom() {
        zoomSpring?.let {
            zoomSpring = null
            it.cancel()
        }
        zoomHidden?.alpha = 1f
        zoomHidden = null
        zoomView?.let {
            it.visibility = View.GONE
            it.setImageDrawable(null)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // The moving picture is placed by its animation, not by the layout.
        val v = zoomView
        val keep = if (v != null && zoomSpring != null) Rect(v.left, v.top, v.right, v.bottom) else null
        super.onLayout(changed, left, top, right, bottom)
        if (keep != null) v?.layout(keep.left, keep.top, keep.right, keep.bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The screen turned: three tabs a row when it is wide, and every card takes the new size.
        if (w != oldw && oldw > 0) post {
            (grid.layoutManager as GridLayoutManager).spanCount = columns()
            adapter.notifyDataSetChanged()
        }
    }

    private fun landscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** Cards two a row (three when wide); 3.0.4: the list one a row (two when wide). */
    private fun columns() = if (asList) (if (landscape()) 2 else 1) else if (landscape()) 3 else 2

    /** 3.0.4: picture cards or the compact list; the open tab stays in sight. */
    private fun switchLayout() {
        asList = !asList
        SwiftApp.prefs.tabsAsList = asList
        showLayoutButton()
        (grid.layoutManager as GridLayoutManager).spanCount = columns()
        adapter.notifyDataSetChanged()
        val idx = adapter.items.indexOfFirst { (it is Item.TabCard && it.current) || (it is Item.GroupCard && it.current) }
        if (idx >= 0) grid.scrollToPosition(idx)
        if (grid.visibility == View.VISIBLE) Motion.fadeIn(grid, fromDy = 0, duration = Motion.SHORT)
    }

    /** The button shows what it switches to. */
    private fun showLayoutButton() {
        Motion.swapIcon(layoutButton, if (asList) R.drawable.ic_grid_view else R.drawable.ic_view_list)
        layoutButton.contentDescription = if (asList) "Grid of tabs" else "List of tabs"
        androidx.appcompat.widget.TooltipCompat.setTooltipText(layoutButton, layoutButton.contentDescription)
    }

    /** Back from a group, the inactive tabs or a search; false when the grid itself is shown. */
    fun back(): Boolean {
        if (mode == Mode.GRID) return false
        setMode(Mode.GRID)
        return true
    }

    private fun setPage(incognito: Boolean) {
        if (showingIncognito == incognito) return
        showingIncognito = incognito
        mode = Mode.GRID
        openGroup = null
        val colourBefore = (background as? ColorDrawable)?.color
        applyPage()
        refresh(scrollToCurrent = true)
        Motion.pageIn(grid, if (incognito) Motion.FORWARD else Motion.BACK)
        val colourNow = (background as? ColorDrawable)?.color
        if (colourBefore != null && colourNow != null) fadePageColour(colourBefore, colourNow)
    }

    private fun setMode(m: Mode, group: TabGroup? = null) {
        if (mode == Mode.SEARCH && m != Mode.SEARCH) hideKeyboard()
        val leftGroup = openGroup
        mode = m
        openGroup = group
        if (m == Mode.SEARCH) {
            query = ""
            subSearch.setText("")
        }
        applyMode()
        skipListMotion()
        refresh(scrollToCurrent = m == Mode.GRID && leftGroup == null)
        if (m != Mode.GRID) {
            grid.scrollToPosition(0)
        } else if (leftGroup != null) {
            val idx = adapter.items.indexOfFirst { it is Item.GroupCard && it.group === leftGroup }
            if (idx >= 0) grid.scrollToPosition(idx)
        }
        Motion.pageIn(grid, if (m == Mode.GRID) Motion.BACK else Motion.FORWARD)
        Motion.fadeIn(if (mode == Mode.GRID) header else subheader, fromDy = 0, duration = Motion.SHORT)
        if (m == Mode.SEARCH) {
            subSearch.requestFocus()
            subSearch.post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(subSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** The next refresh has the whole list move as a page: the cards' own motion is off until it is laid out. */
    private fun skipListMotion() {
        if (grid.itemAnimator == null) return
        grid.itemAnimator = null
        grid.doOnNextLayout { grid.itemAnimator = listMotion }
    }

    /** Updates the grid after tabs were opened, closed, moved or changed. */
    fun refresh(scrollToCurrent: Boolean = false) {
        if (dragging) {
            refreshPending = true
            return
        }
        val current = TabManager.current
        val listed = if (showingIncognito) TabManager.incognitoTabs() else TabManager.profileTabs()
        val now = System.currentTimeMillis()
        val inactive = if (showingIncognito) emptyList() else listed.filter { TabManager.isInactive(it, now) }
        val group = openGroup
        if (mode == Mode.GROUP && (group == null || group !in TabManager.groups)) {
            mode = Mode.GRID
            openGroup = null
        }
        if (mode == Mode.INACTIVE && inactive.isEmpty()) mode = Mode.GRID
        applyMode()

        val items = ArrayList<Item>()
        when (mode) {
            Mode.GRID -> {
                if (inactive.isNotEmpty()) items += Item.Banner(BANNER_INACTIVE, inactive.size)
                val hidden = inactive.toHashSet()
                val shownGroups = HashSet<Long>()
                for (t in listed) {
                    if (t in hidden) continue
                    val g = TabManager.groupOf(t)
                    if (g == null) {
                        items += tabCard(t, current)
                    } else if (shownGroups.add(g.id)) {
                        val members = TabManager.tabsIn(g)
                        items += Item.GroupCard(
                            g, TabManager.groupTitle(g), g.color, members.size, members.take(4).map { it.id },
                            current != null && current.groupId == g.id,
                        )
                    }
                }
            }
            Mode.GROUP -> TabManager.tabsIn(openGroup!!).forEach { items += tabCard(it, current) }
            Mode.INACTIVE -> {
                items += Item.Banner(NOTE_INACTIVE, inactive.size)
                inactive.forEach { items += tabCard(it, current) }
            }
            Mode.SEARCH -> {
                val q = query.trim().lowercase()
                for (t in listed) {
                    if (q.isEmpty() || t.displayTitle.lowercase().contains(q) || t.url.lowercase().contains(q)) items += tabCard(t, current)
                }
            }
        }
        adapter.submit(items)
        if (scrollToCurrent) {
            val idx = items.indexOfFirst { (it is Item.TabCard && it.current) || (it is Item.GroupCard && it.current) }
            if (idx >= 0) grid.scrollToPosition(idx)
        }
        val n = TabManager.profileTabs().size
        Motion.swapText(normalCount, if (n > 99) ":D" else n.toString())
        if (mode == Mode.GROUP) openGroup?.let { subTitle.text = TabManager.groupTitle(it) }
        showEmpty(items.none { it !is Item.Banner })
    }

    private fun tabCard(t: Tab, current: Tab?) = Item.TabCard(t, t.displayTitle, t.url, t === current, t.pinned, TabRules.sound(t.playing, t.muted), if (mode == Mode.SEARCH) query.trim() else "")

    /** 3.0.4: [tab]'s sound started or stopped, or it was muted or unmuted: only its speaker is drawn again. */
    fun refreshSound(tab: Tab) {
        if (dragging) return
        val idx = adapter.items.indexOfFirst { it is Item.TabCard && it.tab === tab }
        if (idx < 0) return
        val item = adapter.items[idx] as Item.TabCard
        val sound = TabRules.sound(tab.playing, tab.muted)
        if (item.sound == sound) return
        adapter.items[idx] = item.copy(sound = sound)
        adapter.notifyItemChanged(idx, CHANGED_SOUND)
    }

    private fun showEmpty(isEmpty: Boolean) {
        // With no cards at all the list stays under it, so its last card's fade plays out (it has
        // nothing to draw after that); a banner with no cards to go with it is hidden, as ever.
        grid.visibility = if (isEmpty && mode != Mode.SEARCH && adapter.items.isNotEmpty()) View.GONE else View.VISIBLE
        if (!isEmpty) {
            empty.visibility = View.GONE
            return
        }
        val incognito = showingIncognito
        val searching = mode == Mode.SEARCH
        val soft = color(if (incognito) R.color.incognito_url else Looks.accent.container)
        val ink = color(if (incognito) R.color.incognito_text else Looks.accent.onContainer)
        findViewById<ImageView>(R.id.switcher_empty_icon).apply {
            // 3.0.5: no tabs at all: a small drawing, in the incognito page's own colours there; a search
            // that found nothing keeps its icon in a circle. Coloured here, not by a tint on the view,
            // which would wash out the drawing's two tones.
            if (searching) {
                setImageDrawable(Ui.icon(context, R.drawable.ic_search, ink))
                background = Ui.circle(soft)
                val p = dp(16)
                setPadding(p, p, p, p)
            } else {
                setImageDrawable(EmptyArt.TABS.drawable(context, soft, ink))
                background = null
                setPadding(0, 0, 0, 0)
            }
            val size = dp(if (searching) 64 else EmptyArt.sizeDp(context))
            if (layoutParams.width != size) {
                layoutParams.width = size
                layoutParams.height = size
                requestLayout()
            }
        }
        findViewById<TextView>(R.id.switcher_empty_title).apply {
            text = when {
                searching -> "No tabs found"
                incognito -> "No incognito tabs"
                else -> "No open tabs"
            }
            setTextColor(color(if (incognito) R.color.incognito_text else R.color.text_primary))
        }
        findViewById<TextView>(R.id.switcher_empty_text).apply {
            text = when {
                searching -> if (query.isBlank()) "" else "No tab has \"${query.trim()}\" in its title or address."
                incognito -> "Incognito tabs keep no history. Their cookies and site data are deleted when the last one closes."
                else -> ""
            }
            setTextColor(color(if (incognito) R.color.incognito_hint else R.color.text_secondary))
        }
        findViewById<TextView>(R.id.switcher_empty_new).apply {
            text = if (searching) "Show all tabs" else if (incognito) "New incognito tab" else "New tab"
            visibility = if (searching && query.isBlank()) View.GONE else View.VISIBLE
        }
        if (empty.visibility != View.VISIBLE) Motion.fadeIn(empty, fromDy = 12)
    }

    /**
     * A tab's picture or title changed. 3.0: only a new title, address or picture redraws its card,
     * and only that part of it: this comes with every step of the open tab's loading and ad count (up
     * to three times a second), and each time the whole card was made again and cross-faded.
     */
    fun refreshTab(tab: Tab) {
        if (dragging) return
        val idx = adapter.items.indexOfFirst {
            (it is Item.TabCard && it.tab === tab) || (it is Item.GroupCard && tab.id in it.tabIds)
        }
        if (idx < 0) return
        val picture = Thumbnails.cached(tab)
        val newPicture = picture != null && shownPictures[tab.id]?.get() !== picture
        // Told once: a card out of sight takes it when it comes back.
        if (newPicture) pictureShown(tab, picture!!)
        when (val item = adapter.items[idx]) {
            is Item.TabCard -> {
                val fresh = item.copy(title = tab.displayTitle, url = tab.url)
                when {
                    // Another page: its icon and placeholder may change too, so the whole card.
                    fresh.url != item.url -> {
                        adapter.items[idx] = fresh
                        adapter.notifyItemChanged(idx)
                    }
                    fresh.title != item.title -> {
                        adapter.items[idx] = fresh
                        adapter.notifyItemChanged(idx, if (newPicture) CHANGED_TITLE or CHANGED_PICTURE else CHANGED_TITLE)
                    }
                    newPicture -> adapter.notifyItemChanged(idx, CHANGED_PICTURE)
                }
            }
            // A group card shows the pictures of its first four tabs (those are its tabIds).
            is Item.GroupCard -> if (newPicture) adapter.notifyItemChanged(idx, CHANGED_PICTURE)
            is Item.Banner -> Unit
        }
    }

    /** [tab]'s card, drawn again (3.0.1: its "Keep loaded" mark changed). */
    private fun redrawCard(tab: Tab) {
        val idx = adapter.items.indexOfFirst { it is Item.TabCard && it.tab === tab }
        if (idx >= 0) adapter.notifyItemChanged(idx)
    }

    /** The picture each tab's card shows (3.0): a card is redrawn only for a new one. */
    private val shownPictures = HashMap<Long, java.lang.ref.WeakReference<android.graphics.Bitmap>>()

    private fun pictureShown(tab: Tab, bmp: android.graphics.Bitmap) {
        shownPictures[tab.id] = java.lang.ref.WeakReference(bmp)
    }

    private fun applyPage() {
        val dark = showingIncognito
        backgroundAnim?.cancel()
        setBackgroundColor(color(if (dark) R.color.incognito_bg else R.color.switcher_bg))
        val text = color(if (dark) R.color.incognito_text else R.color.text_primary)
        // "New tab" is a tonal pill button.
        val pillText = color(if (dark) R.color.incognito_text else Looks.accent.onContainer)
        newTab.setTextColor(pillText)
        newTab.background = Ui.ripple(
            context,
            GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(color(if (dark) R.color.incognito_url else Looks.accent.container))
            },
            20,
            if (dark) Ui.RIPPLE_ON_DARK else null,
        )
        val plus = ContextCompat.getDrawable(context, R.drawable.ic_add)?.mutate()?.also {
            it.setTint(pillText)
            it.setBounds(0, 0, dp(20), dp(20))
        }
        newTab.setCompoundDrawablesRelative(plus, null, null, null)
        toggle.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color(if (dark) R.color.incognito_url else R.color.surface_alt))
        }
        val selectedColor = if (dark) color(R.color.incognito_hint) else color(Looks.accent.container)
        toggleNormal.background = toggleBackground(selectedColor)
        toggleIncognito.background = toggleBackground(selectedColor)
        toggleNormal.isSelected = !dark
        toggleIncognito.isSelected = dark
        normalCount.setTextColor(text)
        normalCount.background = MainActivity.tabCountBackground(context, text)
        // On incognito's own page it sits on the chosen pill: the page's dark colour reads there.
        incognitoIcon.imageTintList = ColorStateList.valueOf(if (dark) color(R.color.incognito_bg) else text)
        val icons = ColorStateList.valueOf(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
        searchButton.imageTintList = icons
        layoutButton.imageTintList = icons
        val subText = ColorStateList.valueOf(text)
        subBack.imageTintList = subText
        subAdd.imageTintList = subText
        subMore.imageTintList = subText
        subTitle.setTextColor(text)
        // 3.0.5: the one search box of every screen, in the colour of the cards around it.
        Ui.searchBox(subSearch, fill = color(if (dark) R.color.incognito_url else R.color.tab_card_bg), ink = text, soft = color(if (dark) R.color.incognito_hint else R.color.text_secondary))
        subAction.setTextColor(color(if (dark) R.color.incognito_text else Looks.accent.primary))
        subAction.background = Ui.ripple(context, null, Ui.R_SMALL, if (dark) Ui.RIPPLE_ON_DARK else null)
        adapter.notifyDataSetChanged()
    }

    private fun applyMode() {
        val grid = mode == Mode.GRID
        header.visibility = if (grid) View.VISIBLE else View.GONE
        subheader.visibility = if (grid) View.GONE else View.VISIBLE
        if (grid) return
        val group = openGroup
        subDot.visibility = if (mode == Mode.GROUP) View.VISIBLE else View.GONE
        if (group != null) subDot.backgroundTintList = ColorStateList.valueOf(color(TabGroup.colorRes(group.color)))
        subTitle.visibility = if (mode == Mode.SEARCH) View.GONE else View.VISIBLE
        subTitle.isClickable = mode == Mode.GROUP
        subTitle.text = when (mode) {
            Mode.GROUP -> group?.let { TabManager.groupTitle(it) }.orEmpty()
            Mode.INACTIVE -> "Inactive tabs"
            else -> ""
        }
        subTitle.contentDescription = if (mode == Mode.GROUP) "${subTitle.text}. Tap to rename" else null
        subSearch.visibility = if (mode == Mode.SEARCH) View.VISIBLE else View.GONE
        subAdd.visibility = if (mode == Mode.GROUP) View.VISIBLE else View.GONE
        subMore.visibility = if (mode == Mode.GROUP) View.VISIBLE else View.GONE
        subAction.visibility = if (mode == Mode.INACTIVE) View.VISIBLE else View.GONE
        subAction.text = "Close all"
    }

    /** Pill behind the chosen page of the toggle. */
    private fun toggleBackground(selected: Int) = StateListDrawable().apply {
        // 3.0.3: Normal and Incognito cross-fade as you switch, instead of jumping.
        setEnterFadeDuration(Motion.SHORT.toInt())
        setExitFadeDuration(Motion.SHORT.toInt())
        addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(selected)
        })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = dp(17).toFloat()
            setColor(Color.TRANSPARENT)
        })
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
        subSearch.clearFocus()
    }

    private fun color(res: Int) = Palette.color(context, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 3.0.5: tabs' pictures and icons a little dimmer in the dark look; incognito's dark cards count as dark. */
    private fun softPictures() = showingIncognito || Palette.isNight(context)

    // ------------------------------------------------------------------ groups and inactive tabs

    private fun renameGroup(group: TabGroup) {
        val activity = context as? MainActivity ?: return
        Sheets.renameGroup(activity, group)
    }

    private fun groupOptions(group: TabGroup) {
        val activity = context as? MainActivity ?: return
        Sheets.groupOptions(activity, group) { listener?.onNewTabInGroup(group) }
    }

    private fun closeInactive() {
        val now = System.currentTimeMillis()
        val inactive = TabManager.profileTabs().filter { TabManager.isInactive(it, now) }
        TabManager.closeTabs(inactive, undoable = true)
    }

    /**
     * A card held in a search or among the inactive tabs (3.0): its menu at once. Cards can't be
     * dragged there, and elsewhere the menu comes from a drag let go without moving, so holding one
     * there did nothing.
     */
    private fun heldInList(h: RecyclerView.ViewHolder): Boolean {
        if (mode != Mode.SEARCH && mode != Mode.INACTIVE) return false
        val item = adapter.items.getOrNull(h.bindingAdapterPosition) ?: return false
        Haptics.hold(h.itemView)
        if (mode == Mode.SEARCH) hideKeyboard()
        cardMenu(h.itemView, item)
        return true
    }

    /** Menu for a card that was held and let go without moving it (a dialog: it keeps full screen). */
    private fun cardMenu(@Suppress("UNUSED_PARAMETER") anchor: View, item: Item) {
        val activity = context as? MainActivity ?: return
        when (item) {
            // 3.0.4: the same menu as holding a tab in the group strip (with pinning and muting).
            is Item.TabCard -> TabMenu.show(activity, item.tab, item.title, inGrid = true) { redrawCard(item.tab) }
            is Item.GroupCard -> {
                val group = item.group
                val actions = listOf(
                    Sheets.Action(R.drawable.ic_edit, "Rename group") { Sheets.renameGroup(activity, group) },
                    Sheets.Action(R.drawable.ic_layers, "Ungroup (the tabs stay open)") { TabManager.ungroup(group) },
                    Sheets.Action(R.drawable.ic_close, "Close group", danger = true) { TabManager.closeGroup(group) },
                )
                Sheets.actions(activity, item.title, null, actions, iconRes = R.drawable.ic_layers)
            }
            is Item.Banner -> return
        }
    }

    // ------------------------------------------------------------------ dragging

    /** Saves the order the cards were dragged into. */
    private fun commitOrder() {
        val listed = if (showingIncognito) TabManager.incognitoTabs() else TabManager.profileTabs()
        val order = ArrayList<Tab>(listed.size)
        when (mode) {
            Mode.GRID -> {
                for (item in adapter.items) {
                    when (item) {
                        is Item.TabCard -> order += item.tab
                        is Item.GroupCard -> order += TabManager.tabsIn(item.group)
                        is Item.Banner -> Unit
                    }
                }
                // Inactive tabs (not shown in the grid) keep their order, after the others.
                val placed = order.toHashSet()
                listed.filterTo(order) { it !in placed }
            }
            Mode.GROUP -> {
                val groupId = openGroup?.id ?: return
                val inGroup = adapter.items.filterIsInstance<Item.TabCard>().map { it.tab }
                var i = 0
                for (t in listed) order += if (t.groupId == groupId && i < inGroup.size) inGroup[i++] else t
            }
            else -> return
        }
        TabManager.reorder(order)
    }

    /** While dragging a tab over another card, finds the card under its middle. */
    private fun trackMerge(rv: RecyclerView, dragged: RecyclerView.ViewHolder, dX: Float, dY: Float) {
        val draggedItem = adapter.items.getOrNull(dragged.bindingAdapterPosition)
        // 3.0.4: a pinned tab is in no group: it joins none, and none joins it.
        if (mode != Mode.GRID || draggedItem !is Item.TabCard || draggedItem.tab.pinned) return
        val v = dragged.itemView
        val cx = v.left + dX + v.width / 2f
        val cy = v.top + dY + v.height / 2f
        var hit: RecyclerView.ViewHolder? = null
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            if (child === v) continue
            val h = rv.getChildViewHolder(child)
            val item = adapter.items.getOrNull(h.bindingAdapterPosition)
            if ((item !is Item.TabCard || item.tab.pinned) && item !is Item.GroupCard) continue
            val insetX = child.width * 0.25f
            val insetY = child.height * 0.25f
            if (cx > child.left + insetX && cx < child.right - insetX && cy > child.top + insetY && cy < child.bottom - insetY) {
                hit = h
                break
            }
        }
        if (hit === mergeCandidate) return
        mergeCandidate = hit
        removeCallbacks(mergeCheck)
        setMergeTarget(null)
        if (hit != null) postDelayed(mergeCheck, MERGE_HOLD_MS)
    }

    /** Shows which card the dragged tab will join when let go. */
    private fun setMergeTarget(holder: RecyclerView.ViewHolder?) {
        if (holder === mergeHolder) return
        mergeHolder?.itemView?.let { v ->
            Motion.anim(v, Motion.SHORT).scaleX(1f).scaleY(1f).start()
            v.findViewById<View>(R.id.card_selected)?.visibility = View.GONE
            v.findViewById<View>(R.id.group_selected)?.visibility = View.GONE
            v.findViewById<View>(R.id.row_selected)?.visibility = View.GONE
        }
        mergeHolder = holder
        draggedHolder?.itemView?.let { d ->
            val scale = if (holder != null) 0.9f else 1.05f
            Motion.anim(d, Motion.SHORT).alpha(if (holder != null) 0.55f else 1f).scaleX(scale).scaleY(scale).start()
        }
        holder?.itemView?.let { v ->
            Motion.anim(v, Motion.SHORT).scaleX(0.92f).scaleY(0.92f).start()
            v.findViewById<View>(R.id.card_selected)?.visibility = View.VISIBLE
            v.findViewById<View>(R.id.group_selected)?.visibility = View.VISIBLE
            v.findViewById<View>(R.id.row_selected)?.visibility = View.VISIBLE
            Haptics.tick(v)
        }
    }

    private inner class DragAndSwipe : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            val item = adapter.items.getOrNull(vh.bindingAdapterPosition) ?: return 0
            if (item is Item.Banner) return 0
            val drag = if (mode == Mode.GRID || mode == Mode.GROUP) {
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            } else {
                0
            }
            // 3.0.4: a pinned tab can't be swiped away (its menu still closes it).
            val swipe = if (item is Item.TabCard && item.tab.pinned) 0 else ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            return makeMovementFlags(drag, swipe)
        }

        // Held cards in a search or the inactive tabs open their menu instead of being dragged (they
        // have no drag there: their own long click does it, 3.0, heldInList).
        override fun isLongPressDragEnabled() = true

        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val to = adapter.items.getOrNull(target.bindingAdapterPosition)
            if (to !is Item.TabCard && to !is Item.GroupCard) return false
            // 3.0.4: pinned tabs move only among themselves, and the others only after them.
            return pinned(adapter.items.getOrNull(current.bindingAdapterPosition)) == pinned(to)
        }

        private fun pinned(item: Item?) = item is Item.TabCard && item.tab.pinned

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            // Over the middle of a card the tab is about to join it rather than swap places.
            if (mergeHolder != null || mergeCandidate === target) return false
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0 || adapter.items.getOrNull(to) is Item.Banner) return false
            adapter.move(from, to)
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            when (val item = adapter.items.getOrNull(vh.bindingAdapterPosition)) {
                is Item.TabCard -> TabManager.close(item.tab, undoable = true)
                is Item.GroupCard -> TabManager.closeGroup(item.group)
                else -> refresh()
            }
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                draggedHolder = vh
                dragging = true
                moved = false
                maxDrag = 0f
                mergeCandidate = null
                Haptics.hold(vh.itemView)
                // A press and this lift would both move its scale: it has no press while it is held.
                vh.itemView.stateListAnimator = null
                // Over the other cards, for as long as it is held.
                Motion.anim(vh.itemView, Motion.SHORT).scaleX(1.05f).scaleY(1.05f).translationZ(Ui.dpf(context, DRAG_RAISE_DP)).start()
            } else if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && vh != null) {
                // Its fade (onChildDraw) from one layer, not a copy of everything in it on every frame.
                vh.itemView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, active: Boolean,
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                vh.itemView.alpha = 1f - (abs(dX) / vh.itemView.width.coerceAtLeast(1)).coerceIn(0f, 0.8f)
            } else if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && active) {
                maxDrag = maxOf(maxDrag, hypot(dX, dY))
                trackMerge(rv, vh, dX, dY)
            }
            super.onChildDraw(c, rv, vh, dX, dY, actionState, active)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            val v = vh.itemView
            v.setLayerType(View.LAYER_TYPE_NONE, null)
            // A fade still running on it (over another card) would otherwise end after this.
            v.animate().cancel()
            v.alpha = 1f
            // Its press comes back once it has settled: given back now, it would fight this.
            Motion.anim(v, Motion.SHORT).scaleX(1f).scaleY(1f).translationZ(0f).withEndAction { givePressBack(vh) }.start()
            if (!dragging) return
            dragging = false
            draggedHolder = null
            removeCallbacks(mergeCheck)
            val target = mergeHolder?.let { adapter.items.getOrNull(it.bindingAdapterPosition) }
            setMergeTarget(null)
            mergeCandidate = null
            val item = adapter.items.getOrNull(vh.bindingAdapterPosition)
            when {
                target != null && item is Item.TabCard -> when (target) {
                    is Item.TabCard -> TabManager.groupWith(item.tab, target.tab)
                    is Item.GroupCard -> TabManager.addToGroup(item.tab, target.group)
                    else -> Unit
                }
                moved -> commitOrder()
                maxDrag < slop && item != null -> post { cardMenu(vh.itemView, item) }
            }
            if (refreshPending || target != null || moved) {
                refreshPending = false
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ cards

    private sealed class Item {
        /** 3.0.4: with whether it is pinned and what its speaker shows. */
        data class TabCard(
            val tab: Tab, val title: String, val url: String, val current: Boolean,
            val pinned: Boolean = false, val sound: TabRules.Sound = TabRules.Sound.NONE,
            /** The search word marked in its title (a search only). */
            val mark: String = "",
        ) : Item()

        data class GroupCard(
            val group: TabGroup, val title: String, val color: Int, val count: Int, val tabIds: List<Long>, val current: Boolean,
        ) : Item()

        data class Banner(val kind: Int, val count: Int) : Item()
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.card_icon)
        val title: TextView = view.findViewById(R.id.card_title)
        val close: ImageButton = view.findViewById(R.id.card_close)
        val thumbBox: View = view.findViewById(R.id.card_thumb_box)
        val thumb: ImageView = view.findViewById(R.id.card_thumb)
        val placeholder: ImageView = view.findViewById(R.id.card_placeholder)
        val selected: View = view.findViewById(R.id.card_selected)

        /** 3.0.4: the speaker while it plays sound or is muted. */
        val sound: ImageButton = view.findViewById(R.id.card_sound)
        var tab: Tab? = null

        /** 3.0.3: the site's name under its icon while the tab has no picture (or only a black one). */
        val placeholderName: TextView = TextView(view.context).apply {
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }

        init {
            (thumbBox as FrameLayout).addView(placeholderName, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER).apply {
                val side = Ui.dp(view.context, 12)
                setMargins(side, 0, side, 0)
            })
            placeholderName.translationY = Ui.dp(view.context, 38).toFloat()
            // The page's picture has its own rounded corners inside the card.
            thumbBox.clipToOutline = true
            Ui.pressable(close, Motion.PRESS_ICON)
            Ui.pressable(sound, Motion.PRESS_ICON)
            // 3.0: a finger-sized place to close it; the 40 dp button fits its title row.
            Ui.touchArea(close, view as ViewGroup)
        }
    }

    /** 3.0.4: a tab, or a group, as a row of the list (item_tab_row). */
    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbBox: View = view.findViewById(R.id.row_thumb_box)
        val thumb: ImageView = view.findViewById(R.id.row_thumb)
        val placeholder: ImageView = view.findViewById(R.id.row_placeholder)
        val title: TextView = view.findViewById(R.id.row_title)
        val host: TextView = view.findViewById(R.id.row_host)
        val sound: ImageButton = view.findViewById(R.id.row_sound)
        val close: ImageButton = view.findViewById(R.id.row_close)
        val selected: View = view.findViewById(R.id.row_selected)
        var tab: Tab? = null
        var group: TabGroup? = null

        init {
            // The small picture has its own rounded corners.
            thumbBox.clipToOutline = true
            Ui.pressable(close, Motion.PRESS_ICON)
            Ui.pressable(sound, Motion.PRESS_ICON)
        }
    }

    private class GroupHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.group_dot)
        val title: TextView = view.findViewById(R.id.group_title)
        val count: TextView = view.findViewById(R.id.group_count)
        val close: ImageButton = view.findViewById(R.id.group_close)
        val mosaic: View = view.findViewById(R.id.group_mosaic)
        val thumbs: List<ImageView> = listOf(
            view.findViewById(R.id.group_thumb0), view.findViewById(R.id.group_thumb1),
            view.findViewById(R.id.group_thumb2), view.findViewById(R.id.group_thumb3),
        )
        val selected: View = view.findViewById(R.id.group_selected)
        var group: TabGroup? = null

        init {
            Ui.pressable(close, Motion.PRESS_ICON)
            // 3.0: as on a tab's card (the button was 36 dp).
            Ui.touchArea(close, view as ViewGroup)
        }
    }

    private class BannerHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.banner_icon)
        val title: TextView = view.findViewById(R.id.banner_title)
        val text: TextView = view.findViewById(R.id.banner_text)
        val chevron: View = view.findViewById(R.id.banner_chevron)
    }

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: MutableList<Item> = ArrayList()
            private set

        fun submit(newItems: List<Item>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(o: Int, n: Int) = sameItem(old[o], newItems[n])
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]

                // Only the search word changed (typing): just the title is drawn again.
                override fun getChangePayload(o: Int, n: Int): Any? {
                    val a = old[o]
                    val b = newItems[n]
                    return if (a is Item.TabCard && b is Item.TabCard && a.copy(mark = b.mark) == b) CHANGED_TITLE else null
                }
            })
            items = ArrayList(newItems)
            diff.dispatchUpdatesTo(this)
        }

        private fun sameItem(a: Item, b: Item) = when {
            a is Item.TabCard && b is Item.TabCard -> a.tab === b.tab
            a is Item.GroupCard && b is Item.GroupCard -> a.group === b.group
            a is Item.Banner && b is Item.Banner -> a.kind == b.kind
            else -> false
        }

        fun move(from: Int, to: Int) {
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Item.TabCard -> if (asList) TYPE_TAB_ROW else TYPE_TAB
            is Item.GroupCard -> if (asList) TYPE_GROUP_ROW else TYPE_GROUP
            is Item.Banner -> TYPE_BANNER
        }

        /**
         * The picture height for the grid's width now. Worked out on every bind, not once per card:
         * cards made while the screen was turned (a full-screen video) would otherwise come back
         * taller than the others.
         */
        private fun thumbHeight(): Int {
            // The screen's width now: the grid's own may still be the one before the screen turned.
            val width = resources.displayMetrics.widthPixels - grid.paddingLeft - grid.paddingRight
            val wide = landscape()
            // Wide screens: shorter pictures, so a whole row of cards fits on the screen.
            val aspect = if (wide) CARD_ASPECT_WIDE else CARD_ASPECT
            return ((width / (if (wide) 3 else 2) - dp(12)) * aspect).toInt()
        }

        private fun fitHeight(v: View) {
            val h = thumbHeight()
            if (v.layoutParams.height != h) {
                v.layoutParams.height = h
                v.requestLayout()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_TAB -> {
                    val v = Palette.paint(inflater.inflate(R.layout.item_tab_card, parent, false))
                    CardHolder(v).also { h -> v.setOnLongClickListener { heldInList(h) } }
                }
                TYPE_GROUP -> {
                    val v = Palette.paint(inflater.inflate(R.layout.item_group_card, parent, false))
                    GroupHolder(v).also { h -> v.setOnLongClickListener { heldInList(h) } }
                }
                // 3.0.4: the list's rows, for tabs and groups alike.
                TYPE_TAB_ROW, TYPE_GROUP_ROW -> {
                    val v = Palette.paint(inflater.inflate(R.layout.item_tab_row, parent, false))
                    RowHolder(v).also { h -> v.setOnLongClickListener { heldInList(h) } }
                }
                else -> BannerHolder(Palette.paint(inflater.inflate(R.layout.item_tab_banner, parent, false)))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.TabCard -> if (holder is RowHolder) bindTabRow(holder, item) else bindTab(holder as CardHolder, item)
                is Item.GroupCard -> if (holder is RowHolder) bindGroupRow(holder, item) else bindGroup(holder as GroupHolder, item)
                is Item.Banner -> bindBanner(holder as BannerHolder, item)
            }
        }

        /** 3.0: only what changed (refreshTab's CHANGED_ bits); everything when it isn't said. */
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            val changed = payloads.fold(0) { all, p -> all or (p as? Int ?: 0) }
            val item = items[position]
            when {
                changed == 0 -> onBindViewHolder(holder, position)
                item is Item.TabCard && holder is CardHolder && holder.tab === item.tab -> {
                    if (changed and CHANGED_TITLE != 0) bindTitle(holder, item)
                    if (changed and CHANGED_PICTURE != 0) bindPicture(holder, item)
                    if (changed and CHANGED_SOUND != 0) {
                        bindSound(holder.sound, item)
                        holder.itemView.contentDescription = describe(item)
                    }
                }
                item is Item.TabCard && holder is RowHolder && holder.tab === item.tab -> {
                    if (changed and CHANGED_TITLE != 0) bindRowTitle(holder, item)
                    if (changed and CHANGED_PICTURE != 0) bindRowPicture(holder, item)
                    if (changed and CHANGED_SOUND != 0) {
                        bindSound(holder.sound, item)
                        holder.itemView.contentDescription = describe(item)
                    }
                }
                item is Item.GroupCard && holder is GroupHolder && holder.group === item.group -> bindMosaic(holder, item)
                // A group's row shows no pictures.
                item is Item.GroupCard && holder is RowHolder && holder.group === item.group -> Unit
                else -> onBindViewHolder(holder, position)
            }
        }

        /**
         * The open tab's ring (2.6): in the accent chosen, your own included (a ring's colour in a
         * drawable file stayed the preset one), with the card's own corners.
         */
        private fun ring(radius: Int = CARD_RADIUS) = GradientDrawable().apply {
            val width = (2.5f * resources.displayMetrics.density).toInt()
            // 3.0.5: a line is drawn on its shape's edge, half of it outside: its own outer edge follows the card's corner.
            cornerRadius = dp(radius) - width / 2f
            setStroke(width, Ui.accent(context))
        }

        /**
         * 3.0.5: a card is plain filled, as Settings' rows are: no outline or shadow (the grid's page is
         * a step darker than the cards); its background is made once per card and only recoloured.
         */
        private fun setCard(v: View, dark: Boolean, radius: Int = CARD_RADIUS) {
            val fill = color(if (dark) R.color.incognito_url else R.color.tab_card_bg)
            val shape = v.background as? CardShape ?: CardShape().also {
                it.cornerRadius = dp(radius).toFloat()
                v.background = it
            }
            if (shape.filled != fill) {
                shape.filled = fill
                shape.setColor(fill)
            }
            // The touch ripple inside the card's own corners, and light on incognito's dark cards.
            if (shape.rippleDark != dark) {
                shape.rippleDark = dark
                v.foreground = Ui.ripple(context, null, radius, if (dark) Ui.RIPPLE_ON_DARK else null)
                // Its small buttons' ripples stay in their own round place: a free one spread past the card's corner.
                for (id in CARD_BUTTONS) v.findViewById<View>(id)?.background = Ui.ripple(context, null, 20, if (dark) Ui.RIPPLE_ON_DARK else null)
            }
        }

        private fun bindTab(h: CardHolder, card: Item.TabCard) {
            val tab = card.tab
            h.tab = tab
            fitHeight(h.thumbBox)
            val dark = showingIncognito
            // 3.0.5: the page's picture and the site's icon a little dimmer (SoftPictures).
            SoftPictures.apply(h.thumb, softPictures())
            SoftPictures.apply(h.icon, softPictures())
            SoftPictures.apply(h.placeholder, softPictures())
            setCard(h.itemView, dark)
            h.itemView.scaleX = 1f
            h.itemView.scaleY = 1f
            givePressBack(h)
            h.title.setTextColor(color(if (dark) R.color.incognito_text else R.color.text_primary))
            // 3.0.3: a rounded background, not a plain colour: the picture is clipped to it, so its
            // corners stay inside the card's rounded border and the open tab's ring (3.0.5: nesting them).
            h.thumbBox.background = Ui.shape(context, color(if (dark) R.color.incognito_bg else R.color.tab_thumb_bg), Corners.nested(CARD_RADIUS, CARD_GAP))
            h.selected.visibility = if (card.current) View.VISIBLE else View.GONE
            if (card.current) h.selected.background = ring()
            bindTitle(h, card, icon = true)
            bindKept(h.title, tab)
            h.thumb.animate().cancel()
            h.thumb.alpha = 1f
            h.thumb.setImageDrawable(null)
            h.placeholder.visibility = View.VISIBLE
            bindPicture(h, card)
            bindSound(h.sound, card)
            bindClose(h.close, tab)
            h.itemView.setOnClickListener { listener?.onTabChosen(tab) }
        }

        /** The card's title; a document's mark goes with it (its kind comes from its name). */
        private fun bindTitle(h: CardHolder, card: Item.TabCard, icon: Boolean = com.swiftbrowser.docs.Docs.isViewerUrl(card.url)) {
            h.title.text = titleOf(card)
            h.itemView.contentDescription = describe(card)
            if (icon) bindIcon(h, card)
        }

        /** Its title, with the word searched for marked (incognito's cards keep their own colour, so bold only). */
        private fun titleOf(card: Item.TabCard): CharSequence =
            markedText(card.title, card.mark, if (showingIncognito) color(R.color.incognito_text) else Ui.accent(context))

        /** What screen readers hear for a tab: its title, then (3.0.4) pinned, kept loaded and its sound. */
        private fun describe(card: Item.TabCard): String {
            val parts = arrayListOf(card.title)
            if (card.pinned) parts += "pinned"
            if (card.tab.kept) parts += "kept loaded"
            when (card.sound) {
                TabRules.Sound.MUTED -> parts += "muted"
                TabRules.Sound.PLAYING -> parts += "playing sound"
                TabRules.Sound.NONE -> Unit
            }
            return parts.joinToString(", ")
        }

        /** 3.0.1: a tab kept loaded has the memory mark after its title. */
        private fun bindKept(title: TextView, tab: Tab) {
            // 3.0.4: filled, as Swift's icons are for what is on.
            val mark = if (!tab.kept) null else ContextCompat.getDrawable(context, R.drawable.ic_memory_on)?.mutate()?.apply {
                setBounds(0, 0, dp(14), dp(14))
                setTint(color(if (showingIncognito) R.color.incognito_hint else R.color.text_secondary))
            }
            title.setCompoundDrawablesRelative(null, null, mark, null)
            title.compoundDrawablePadding = dp(4)
        }

        /**
         * 3.0.4: the speaker while the tab plays sound, crossed out while it is muted (until unmuted,
         * playing or not); tapping it mutes or unmutes everything the tab plays.
         */
        private fun bindSound(v: ImageButton, card: Item.TabCard) {
            if (card.sound == TabRules.Sound.NONE) {
                v.visibility = View.GONE
                v.setOnClickListener(null)
                return
            }
            val tab = card.tab
            val muted = card.sound == TabRules.Sound.MUTED
            // Before it shows: a speaker just coming into view has nothing to change from.
            Motion.swapIcon(v, if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
            v.visibility = View.VISIBLE
            v.imageTintList = ColorStateList.valueOf(color(if (showingIncognito) R.color.incognito_hint else R.color.text_secondary))
            v.contentDescription = if (muted) "Unmute tab" else "Mute tab"
            androidx.appcompat.widget.TooltipCompat.setTooltipText(v, v.contentDescription)
            v.setOnClickListener {
                Haptics.tick(v)
                Helper.setMuted(tab, !tab.muted)
                refreshSound(tab)
            }
        }

        /**
         * The × that closes the tab. 3.0.4: a pinned tab has a pin mark in its place instead (it isn't
         * closed by a tap there, nor swiped away: its menu closes or unpins it).
         */
        private fun bindClose(v: ImageButton, tab: Tab) {
            if (tab.pinned) {
                // 3.0.4: filled, as Swift's icons are for what is on.
                v.setImageResource(R.drawable.ic_pin_on)
                v.imageTintList = ColorStateList.valueOf(if (showingIncognito) color(R.color.incognito_text) else Ui.accent(context))
                v.contentDescription = "Pinned"
                v.setOnClickListener {
                    (context as? android.app.Activity)?.let { Messages.show(it, "Pinned tabs stay open. Hold the tab to unpin or close it.") }
                }
            } else {
                v.setImageResource(R.drawable.ic_close)
                v.imageTintList = ColorStateList.valueOf(color(if (showingIncognito) R.color.incognito_hint else R.color.text_secondary))
                v.contentDescription = "Close tab"
                v.setOnClickListener { TabManager.close(tab, undoable = true) }
            }
            androidx.appcompat.widget.TooltipCompat.setTooltipText(v, v.contentDescription)
        }

        /** Its site's icon. */
        private fun bindIcon(h: CardHolder, card: Item.TabCard) {
            val tab = card.tab
            // A document open in Swift's viewer shows its kind's mark (2.6), not a globe.
            val viewer = com.swiftbrowser.docs.Docs.isViewerUrl(tab.url)
            val kind = if (viewer) com.swiftbrowser.docs.Docs.kindOf(card.title, null) else null
            when {
                tab.isBlank -> h.icon.setImageDrawable(Ui.logo(context))
                kind != null -> h.icon.setImageDrawable(com.swiftbrowser.docs.DocIcon(kind, dp(18)))
                viewer -> h.icon.setImageResource(R.drawable.ic_documents)
                else -> h.icon.setImageResource(R.drawable.ic_globe)
            }
            h.icon.imageTintList = if (tab.isBlank || kind != null) null else ColorStateList.valueOf(color(R.color.text_secondary))
            if (!tab.isBlank && !viewer) {
                Favicons.load(card.url) { bmp ->
                    if (bmp != null && h.tab === tab) {
                        h.icon.imageTintList = null
                        h.icon.setImageBitmap(bmp)
                    }
                }
            }
        }

        /** The page's picture; its kind's mark (or the logo for a new tab) until there is one. */
        private fun bindPicture(h: CardHolder, card: Item.TabCard) {
            val tab = card.tab
            h.placeholderName.visibility = View.GONE
            if (tab.isHome) {
                // 3.0.1: Swift's mark plain and quiet, like the globe of other cards: a few new tabs no longer
                // fill the grid with coloured logos.
                h.placeholder.setImageResource(if (tab.incognito) R.drawable.ic_incognito else R.drawable.ic_logo_mark)
                h.placeholder.imageTintList = ColorStateList.valueOf(color(if (tab.incognito) R.color.incognito_hint else R.color.text_secondary))
                return
            }
            val viewer = com.swiftbrowser.docs.Docs.isViewerUrl(tab.url)
            h.placeholder.setImageResource(if (viewer) R.drawable.ic_documents else R.drawable.ic_globe)
            h.placeholder.imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            // 3.0.3: no picture yet (or only a black one, as a page playing a video gives): the site's own
            // icon and its name, instead of an empty box.
            val site = if (viewer) null else com.swiftbrowser.core.UrlUtils.host(tab.url)?.removePrefix("www.")?.removePrefix("m.")
            if (site != null) {
                h.placeholderName.text = site
                h.placeholderName.setTextColor(color(if (showingIncognito) R.color.incognito_hint else R.color.text_secondary))
                h.placeholderName.visibility = View.VISIBLE
                Favicons.load(tab.url) { icon ->
                    if (icon != null && h.tab === tab && h.placeholder.visibility == View.VISIBLE) {
                        h.placeholder.imageTintList = null
                        h.placeholder.setImageBitmap(icon)
                    }
                }
            }
            var binding = true
            Thumbnails.load(tab) { bmp ->
                if (bmp != null && h.tab === tab) {
                    val late = !binding && h.thumb.drawable == null
                    h.thumb.setImageBitmap(bmp)
                    h.placeholder.visibility = View.GONE
                    h.placeholderName.visibility = View.GONE
                    pictureShown(tab, bmp)
                    // Its view's alpha: the picture's own stays SoftPictures' dimming.
                    if (late && Motion.enabled(context)) {
                        h.thumb.alpha = 0f
                        Motion.anim(h.thumb, Motion.ICON).alpha(1f).start()
                    }
                }
            }
            binding = false
        }

        // ------------------------------------------------------------------ 3.0.4: the list

        /** A tab's row: its small picture, its title over its site, its speaker, and its × or pin. */
        private fun bindTabRow(h: RowHolder, card: Item.TabCard) {
            val tab = card.tab
            h.tab = tab
            h.group = null
            val dark = showingIncognito
            // 3.0.5: as the cards' (SoftPictures).
            SoftPictures.apply(h.thumb, softPictures())
            SoftPictures.apply(h.placeholder, softPictures())
            setCard(h.itemView, dark, ROW_RADIUS)
            h.itemView.scaleX = 1f
            h.itemView.scaleY = 1f
            h.thumbBox.background = Ui.shape(context, color(if (dark) R.color.incognito_bg else R.color.tab_thumb_bg), Corners.nested(ROW_RADIUS, ROW_GAP))
            h.title.setTextColor(color(if (dark) R.color.incognito_text else R.color.text_primary))
            h.host.setTextColor(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
            // Always the ring, shown for the open tab (and for the row a dragged tab is about to join).
            h.selected.background = ring(ROW_RADIUS)
            h.selected.visibility = if (card.current) View.VISIBLE else View.GONE
            bindRowTitle(h, card)
            bindRowPicture(h, card)
            bindSound(h.sound, card)
            bindClose(h.close, tab)
            h.itemView.setOnClickListener { listener?.onTabChosen(tab) }
        }

        private fun bindRowTitle(h: RowHolder, card: Item.TabCard) {
            h.title.text = titleOf(card)
            bindKept(h.title, card.tab)
            val site = rowSite(card.tab, card.url)
            h.host.text = site
            h.host.visibility = if (site.isEmpty()) View.GONE else View.VISIBLE
            h.itemView.contentDescription = describe(card)
        }

        /** The second line of a tab's row: its site, or what kind of page it shows. */
        private fun rowSite(tab: Tab, url: String): String = when {
            tab.isBlank -> ""
            com.swiftbrowser.docs.Docs.isViewerUrl(url) -> "Document"
            Helper.isReaderUrl(url) -> "Reader view"
            com.swiftbrowser.core.UrlUtils.isHttp(url) -> com.swiftbrowser.core.UrlUtils.host(url)?.removePrefix("www.").orEmpty()
            else -> com.swiftbrowser.core.UrlUtils.display(url)
        }

        /** The page's picture, small; until there is one its site's icon (the logo for a new tab). */
        private fun bindRowPicture(h: RowHolder, card: Item.TabCard) {
            val tab = card.tab
            h.thumb.setImageDrawable(null)
            h.placeholder.visibility = View.VISIBLE
            h.placeholder.imageTintList = ColorStateList.valueOf(color(if (tab.incognito) R.color.incognito_hint else R.color.text_secondary))
            if (tab.isHome) {
                h.placeholder.setImageResource(if (tab.incognito) R.drawable.ic_incognito else R.drawable.ic_logo_mark)
                return
            }
            val viewer = com.swiftbrowser.docs.Docs.isViewerUrl(tab.url)
            h.placeholder.setImageResource(if (viewer) R.drawable.ic_documents else R.drawable.ic_globe)
            if (!viewer) {
                Favicons.load(tab.url) { icon ->
                    if (icon != null && h.tab === tab && h.placeholder.visibility == View.VISIBLE) {
                        h.placeholder.imageTintList = null
                        h.placeholder.setImageBitmap(icon)
                    }
                }
            }
            Thumbnails.load(tab) { bmp ->
                if (bmp != null && h.tab === tab) {
                    h.thumb.setImageBitmap(bmp)
                    h.placeholder.visibility = View.GONE
                    pictureShown(tab, bmp)
                }
            }
        }

        /** A group's row: its colour, its name and how many tabs; it opens the group, as its card does. */
        private fun bindGroupRow(h: RowHolder, card: Item.GroupCard) {
            val group = card.group
            h.group = group
            h.tab = null
            val dark = showingIncognito
            // 3.0.5: the group's mark at full strength, also in a row that showed a tab's picture.
            SoftPictures.apply(h.placeholder, false)
            setCard(h.itemView, dark, ROW_RADIUS)
            h.itemView.scaleX = 1f
            h.itemView.scaleY = 1f
            val groupColor = color(TabGroup.colorRes(card.color))
            val surface = color(if (dark) R.color.incognito_url else R.color.tab_card_bg)
            h.thumbBox.background = Ui.shape(context, Tones.mix(groupColor, surface, 0.82), Corners.nested(ROW_RADIUS, ROW_GAP))
            h.thumb.setImageDrawable(null)
            h.placeholder.visibility = View.VISIBLE
            h.placeholder.setImageResource(R.drawable.ic_layers)
            h.placeholder.imageTintList = ColorStateList.valueOf(groupColor)
            h.title.text = card.title
            h.title.setTextColor(color(if (dark) R.color.incognito_text else R.color.text_primary))
            h.title.setCompoundDrawablesRelative(null, null, null, null)
            val count = if (card.count == 1) "1 tab" else "${card.count} tabs"
            // A group without a name is called by its number of tabs: its sites say more below it.
            h.host.text = if (group.name.isBlank()) {
                TabManager.tabsIn(group).take(3).joinToString(", ") { rowSite(it, it.url).ifEmpty { it.displayTitle } }
            } else {
                count
            }
            h.host.visibility = View.VISIBLE
            h.host.setTextColor(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
            h.sound.visibility = View.GONE
            h.close.setImageResource(R.drawable.ic_close)
            h.close.imageTintList = ColorStateList.valueOf(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
            h.close.contentDescription = "Close group"
            androidx.appcompat.widget.TooltipCompat.setTooltipText(h.close, h.close.contentDescription)
            h.close.setOnClickListener { TabManager.closeGroup(group) }
            h.selected.background = ring(ROW_RADIUS)
            h.selected.visibility = if (card.current) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { setMode(Mode.GROUP, group) }
            h.itemView.contentDescription = "Group ${card.title}, $count"
        }

        private fun bindGroup(h: GroupHolder, card: Item.GroupCard) {
            val group = card.group
            h.group = group
            fitHeight(h.mosaic)
            val dark = showingIncognito
            setCard(h.itemView, dark)
            h.itemView.scaleX = 1f
            h.itemView.scaleY = 1f
            givePressBack(h)
            val groupColor = color(TabGroup.colorRes(card.color))
            h.dot.backgroundTintList = ColorStateList.valueOf(groupColor)
            h.title.text = card.title
            h.title.setTextColor(color(if (dark) R.color.incognito_text else R.color.text_primary))
            h.count.text = card.count.toString()
            h.count.visibility = if (group.name.isBlank()) View.GONE else View.VISIBLE
            h.count.setTextColor(color(if (dark) R.color.incognito_text else R.color.text_secondary))
            h.count.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color(if (dark) R.color.incognito_bg else R.color.surface_alt))
            }
            h.close.imageTintList = ColorStateList.valueOf(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
            // 3.0.3: rounded too, so the group's square fill no longer covers the card's border.
            h.mosaic.background = Ui.shape(context, color(if (dark) R.color.incognito_url else R.color.tab_card_bg), Corners.nested(CARD_RADIUS, MOSAIC_MARGIN))
            h.selected.visibility = if (card.current) View.VISIBLE else View.GONE
            if (card.current) h.selected.background = ring()
            h.thumbs.forEach { it.setImageDrawable(null) }
            bindMosaic(h, card)
            h.itemView.setOnClickListener { setMode(Mode.GROUP, group) }
            h.close.setOnClickListener { TabManager.closeGroup(group) }
            h.itemView.contentDescription = "Group ${card.title}, ${card.count} ${if (card.count == 1) "tab" else "tabs"}"
        }

        /** The pictures of the group's first four tabs. */
        private fun bindMosaic(h: GroupHolder, card: Item.GroupCard) {
            val group = card.group
            val dark = showingIncognito
            val radius = dp(Corners.nested(CARD_RADIUS, MOSAIC_GAP)).toFloat()
            h.thumbs.forEachIndexed { i, view ->
                view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                view.clipToOutline = true
                // 3.0.5: as the cards' (SoftPictures).
                SoftPictures.apply(view, softPictures())
                val id = card.tabIds.getOrNull(i)
                val tab = id?.let { tid -> TabManager.tabs.firstOrNull { it.id == tid } }
                // An empty place has the same fill as a taken one, so the mosaic doesn't look half-built.
                view.background = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(color(if (dark) R.color.incognito_bg else R.color.tab_thumb_bg))
                }
                // A new tab's mark is tinted (3.0.1); a page's picture in this place again must not be.
                view.imageTintList = null
                if (tab == null) {
                    view.setImageDrawable(null)
                } else if (!tab.isHome) {
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    Thumbnails.load(tab) { bmp ->
                        if (bmp != null && h.group === group) {
                            view.setImageBitmap(bmp)
                            pictureShown(tab, bmp)
                        }
                    }
                } else {
                    view.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    // 3.0.1: the same quiet mark as a new tab's card.
                    view.setImageResource(if (tab.incognito) R.drawable.ic_incognito else R.drawable.ic_logo_mark)
                    view.imageTintList = ColorStateList.valueOf(color(if (tab.incognito) R.color.incognito_hint else R.color.text_secondary))
                }
            }
        }

        private fun bindBanner(h: BannerHolder, banner: Item.Banner) {
            val days = SwiftApp.prefs.inactiveDays
            val dark = showingIncognito
            setCard(h.itemView, dark)
            // 3.0: in code, so it follows your own colours (a tint in the layout isn't recoloured).
            h.icon.backgroundTintList = ColorStateList.valueOf(color(if (dark) R.color.incognito_bg else R.color.surface_alt))
            if (banner.kind == BANNER_INACTIVE) {
                h.title.text = if (banner.count == 1) "1 inactive tab" else "${banner.count} inactive tabs"
                h.text.text = "Not opened for $days days"
                h.chevron.visibility = View.VISIBLE
                h.itemView.isClickable = true
                h.itemView.setOnClickListener { setMode(Mode.INACTIVE) }
            } else {
                h.title.text = "Tabs you haven't opened for $days days"
                h.text.text = "They wait here until you open or close them. Change this in Settings → Browsing & tabs."
                h.chevron.visibility = View.GONE
                h.itemView.setOnClickListener(null)
                h.itemView.isClickable = false
            }
        }
    }

    /** 3.0.5: a card's background, made once and only recoloured (setCard). */
    private class CardShape : GradientDrawable() {
        var filled = 0

        /** Which ripple its card has (the page's look it was made for), null before the first. */
        var rippleDark: Boolean? = null
    }

    /**
     * [h]'s press, unless it is a list row (a ripple is enough there) or is being dragged right now.
     * A card gets it when first bound; a drag takes it away (onSelectedChanged) and it comes back as
     * the card settles, or at its next bind if that was cut short.
     */
    private fun givePressBack(h: RecyclerView.ViewHolder) {
        if ((h is CardHolder || h is GroupHolder) && h !== draggedHolder && h.itemView.stateListAnimator == null) {
            Ui.pressable(h.itemView, Motion.PRESS_CARD, morph = false)
        }
    }

    private companion object {
        const val BANNER_INACTIVE = 1
        const val NOTE_INACTIVE = 2

        /** How long the open tab takes to shrink into its card, or to grow back out of it. */
        const val ZOOM_MS = 220L

        /** 3.0.4: the moving picture's spring: done in about the same 220 ms, settling gently. */
        const val ZOOM_STIFFNESS = 600f

        /** A card picture's height for its width (the top of the page is shown). */
        const val CARD_ASPECT = 1.02f
        const val CARD_ASPECT_WIDE = 0.72f

        /** How far a dragged card rises over the others (dp). */
        const val DRAG_RAISE_DP = 6f

        /** The buttons on a card or a row of the list. */
        val CARD_BUTTONS = intArrayOf(R.id.card_close, R.id.card_sound, R.id.row_close, R.id.row_sound, R.id.group_close)

        /** How long a dragged tab rests over another card before letting go groups them. */
        const val MERGE_HOLD_MS = 350L

        /** What changed on a card (refreshTab, 3.0): only that part is drawn again. */
        const val CHANGED_TITLE = 1
        const val CHANGED_PICTURE = 2

        /** 3.0.4: its speaker (refreshSound). */
        const val CHANGED_SOUND = 4

        /** The adapter's kinds of view; 3.0.4: the list's rows. */
        const val TYPE_TAB = 0
        const val TYPE_GROUP = 1
        const val TYPE_BANNER = 2
        const val TYPE_TAB_ROW = 3
        const val TYPE_GROUP_ROW = 4

        /** Corner radii (dp): a card's, and (3.0.4) a row's. */
        const val CARD_RADIUS = Ui.R_MEDIUM
        const val ROW_RADIUS = Ui.R_SMALL

        /**
         * 3.0.5: how far in from the edge what is inside sits (dp), which its corners follow
         * (Corners.nested): a card's picture (item_tab_card), a row's (item_tab_row), and a group's
         * card (item_group_card): its mosaic, and the mosaic's pictures (margin, padding and their own margin).
         */
        const val CARD_GAP = 4
        const val ROW_GAP = 6
        const val MOSAIC_MARGIN = 2
        const val MOSAIC_GAP = 7
    }
}
