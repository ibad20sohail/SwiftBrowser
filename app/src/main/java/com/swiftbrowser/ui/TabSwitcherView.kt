package com.swiftbrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabGroup
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.tabs.Thumbnails
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Full-screen grid of tabs, like Chrome's tab switcher, with a separate page for incognito tabs.
 *  - Hold a tab and drag it to move it; hold it over another tab for a moment and let go to group them.
 *  - A group is one card with pictures of its tabs; tapping it shows the group's tabs.
 *  - Tabs not opened for a while are gathered under "Inactive tabs".
 *  - The search button finds a tab by its title or address.
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
    private val toggle: View
    private val toggleNormal: View
    private val toggleIncognito: View
    private val normalCount: TextView
    private val incognitoIcon: ImageView
    private val adapter = GridAdapter()
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
        searchButton = findViewById(R.id.switcher_search)
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
        ItemTouchHelper(DragAndSwipe()).attachToRecyclerView(grid)
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && mode == Mode.SEARCH) hideKeyboard()
            }
        })

        newTab.setOnClickListener { listener?.onNewTab(showingIncognito) }
        searchButton.setOnClickListener { setMode(Mode.SEARCH) }
        toggleNormal.setOnClickListener { setPage(false) }
        toggleIncognito.setOnClickListener { setPage(true) }
        findViewById<View>(R.id.switcher_empty_new).setOnClickListener {
            if (mode == Mode.SEARCH) back() else listener?.onNewTab(showingIncognito)
        }
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
            v.animate().alpha(1f).setDuration(ZOOM_MS).start()
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
            v.animate().alpha(0f).setDuration(if (zoomed) ZOOM_MS else 140).withEndAction(if (i > 0) null else Runnable {
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
    private var zoomAnim: android.animation.ValueAnimator? = null
    private var zoomHidden: View? = null

    /** Moves [tab]'s picture between [page] and its card; false when its card isn't on screen. */
    private fun zoom(tab: Tab, page: Rect, intoCard: Boolean): Boolean {
        val idx = adapter.items.indexOfFirst {
            (it is Item.TabCard && it.tab === tab) || (it is Item.GroupCard && it.group.id == tab.groupId)
        }
        val holder = if (idx >= 0) grid.findViewHolderForAdapterPosition(idx) else null
        val box = when (holder) {
            is CardHolder -> holder.thumbBox
            is GroupHolder -> holder.mosaic
            else -> null
        } ?: return false
        val card = Rect()
        box.getDrawingRect(card)
        offsetDescendantRectToMyCoords(box, card)
        if (card.isEmpty || !Rect(0, 0, width, height).intersect(card)) return false

        stopZoom()
        val v = zoomView ?: TopCropImageView(context).also {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(it, LayoutParams(0, 0))
            zoomView = it
        }
        v.setBackgroundColor(color(if (tab.incognito) R.color.incognito_bg else if (tab.isHome) R.color.home_bg else R.color.page_bg))
        v.setImageDrawable(null)
        if (!tab.isHome) Thumbnails.load(tab) { bmp -> if (zoomView === v && bmp != null) v.setImageBitmap(bmp) }
        v.visibility = View.VISIBLE
        v.bringToFront()
        // The card stays empty while its picture is on the way in or out.
        val cardView = holder!!.itemView
        cardView.alpha = 0f
        zoomHidden = cardView
        val from = if (intoCard) Rect(page) else card
        val to = if (intoCard) card else Rect(page)
        v.layout(from.left, from.top, from.right, from.bottom)
        zoomAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_MS
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener {
                val f = it.animatedValue as Float
                v.layout(
                    lerp(from.left, to.left, f), lerp(from.top, to.top, f),
                    lerp(from.right, to.right, f), lerp(from.bottom, to.bottom, f),
                )
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (zoomAnim !== animation) return
                    if (intoCard) stopZoom()
                }
            })
            start()
        }
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
            addUpdateListener { bg.alpha = it.animatedValue as Int }
            start()
        }
    }

    private fun stopZoom() {
        zoomAnim?.let {
            zoomAnim = null
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
        val keep = if (v != null && zoomAnim != null) Rect(v.left, v.top, v.right, v.bottom) else null
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

    private fun columns() = if (landscape()) 3 else 2

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
        applyPage()
        refresh(scrollToCurrent = true)
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
        refresh(scrollToCurrent = m == Mode.GRID && leftGroup == null)
        if (m != Mode.GRID) {
            grid.scrollToPosition(0)
        } else if (leftGroup != null) {
            val idx = adapter.items.indexOfFirst { it is Item.GroupCard && it.group === leftGroup }
            if (idx >= 0) grid.scrollToPosition(idx)
        }
        if (m == Mode.SEARCH) {
            subSearch.requestFocus()
            subSearch.post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(subSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }
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
        normalCount.text = if (n > 99) ":D" else n.toString()
        if (mode == Mode.GROUP) openGroup?.let { subTitle.text = TabManager.groupTitle(it) }
        showEmpty(items.none { it !is Item.Banner })
    }

    private fun tabCard(t: Tab, current: Tab?) = Item.TabCard(t, t.displayTitle, t.url, t === current)

    private fun showEmpty(isEmpty: Boolean) {
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        grid.visibility = if (isEmpty && mode != Mode.SEARCH) View.GONE else View.VISIBLE
        if (!isEmpty) return
        val incognito = showingIncognito
        val searching = mode == Mode.SEARCH
        findViewById<ImageView>(R.id.switcher_empty_icon).apply {
            setImageResource(if (searching) R.drawable.ic_search else if (incognito) R.drawable.ic_incognito else R.drawable.ic_layers)
            imageTintList = ColorStateList.valueOf(color(if (incognito) R.color.incognito_text else Looks.accent.onContainer))
            background = Ui.circle(color(if (incognito) R.color.incognito_url else Looks.accent.container))
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
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
                incognito -> "Incognito tabs keep no history, and their cookies are deleted when you close them."
                else -> ""
            }
            setTextColor(color(if (incognito) R.color.incognito_hint else R.color.text_secondary))
        }
        findViewById<TextView>(R.id.switcher_empty_new).apply {
            text = if (searching) "Show all tabs" else if (incognito) "New incognito tab" else "New tab"
            visibility = if (searching && query.isBlank()) View.GONE else View.VISIBLE
        }
    }

    /** A tab's picture or title changed. */
    fun refreshTab(tab: Tab) {
        if (dragging) return
        val idx = adapter.items.indexOfFirst {
            (it is Item.TabCard && it.tab === tab) || (it is Item.GroupCard && tab.id in it.tabIds)
        }
        if (idx >= 0) adapter.notifyItemChanged(idx)
    }

    private fun applyPage() {
        val dark = showingIncognito
        setBackgroundColor(color(if (dark) R.color.incognito_bg else R.color.switcher_bg))
        val text = color(if (dark) R.color.incognito_text else R.color.text_primary)
        // "New tab" is a tonal pill button.
        val pillText = color(if (dark) R.color.incognito_text else Looks.accent.onContainer)
        newTab.setTextColor(pillText)
        newTab.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x22000000),
            GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(color(if (dark) R.color.incognito_url else Looks.accent.container))
            },
            null,
        )
        val plus = ContextCompat.getDrawable(context, R.drawable.ic_add)?.mutate()?.also { it.setTint(pillText) }
        newTab.setCompoundDrawablesRelativeWithIntrinsicBounds(plus, null, null, null)
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
        incognitoIcon.imageTintList = ColorStateList.valueOf(text)
        val icons = ColorStateList.valueOf(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
        searchButton.imageTintList = icons
        val subText = ColorStateList.valueOf(text)
        subBack.imageTintList = subText
        subAdd.imageTintList = subText
        subMore.imageTintList = subText
        subTitle.setTextColor(text)
        subSearch.setTextColor(text)
        subSearch.setHintTextColor(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
        subSearch.background = ContextCompat.getDrawable(context, R.drawable.bg_url)?.mutate()?.also {
            it.setTint(color(if (dark) R.color.incognito_url else R.color.surface_alt))
        }
        subAction.setTextColor(color(if (dark) R.color.incognito_text else Looks.accent.primary))
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

    /** Menu for a card that was held and let go without moving it (a dialog: it keeps full screen). */
    private fun cardMenu(@Suppress("UNUSED_PARAMETER") anchor: View, item: Item) {
        val activity = context as? MainActivity ?: return
        val actions = ArrayList<Sheets.Action>()
        val title: String
        when (item) {
            is Item.TabCard -> {
                val tab = item.tab
                title = item.title
                if (tab.groupId == null) {
                    actions += Sheets.Action(R.drawable.ic_layers, "Add to group") { Sheets.chooseGroup(activity, tab) }
                } else {
                    actions += Sheets.Action(R.drawable.ic_layers, "Move to another group") { Sheets.chooseGroup(activity, tab) }
                    actions += Sheets.Action(R.drawable.ic_close, "Take out of the group") { TabManager.removeFromGroup(tab) }
                }
                actions += Sheets.Action(R.drawable.ic_close, "Close tab", danger = true) { TabManager.close(tab, undoable = true) }
            }
            is Item.GroupCard -> {
                val group = item.group
                title = item.title
                actions += Sheets.Action(R.drawable.ic_edit, "Rename group") { Sheets.renameGroup(activity, group) }
                actions += Sheets.Action(R.drawable.ic_layers, "Ungroup (keep the tabs)") { TabManager.ungroup(group) }
                actions += Sheets.Action(R.drawable.ic_close, "Close group", danger = true) { TabManager.closeGroup(group) }
            }
            is Item.Banner -> return
        }
        Sheets.actions(activity, title, null, actions)
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
        if (mode != Mode.GRID || adapter.items.getOrNull(dragged.bindingAdapterPosition) !is Item.TabCard) return
        val v = dragged.itemView
        val cx = v.left + dX + v.width / 2f
        val cy = v.top + dY + v.height / 2f
        var hit: RecyclerView.ViewHolder? = null
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            if (child === v) continue
            val h = rv.getChildViewHolder(child)
            val item = adapter.items.getOrNull(h.bindingAdapterPosition)
            if (item !is Item.TabCard && item !is Item.GroupCard) continue
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
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            v.findViewById<View>(R.id.card_selected)?.visibility = View.GONE
            v.findViewById<View>(R.id.group_selected)?.visibility = View.GONE
        }
        mergeHolder = holder
        draggedHolder?.itemView?.let { d ->
            d.alpha = if (holder != null) 0.55f else 1f
            val scale = if (holder != null) 0.9f else 1.05f
            d.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        holder?.itemView?.let { v ->
            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
            v.findViewById<View>(R.id.card_selected)?.visibility = View.VISIBLE
            v.findViewById<View>(R.id.group_selected)?.visibility = View.VISIBLE
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
            return makeMovementFlags(drag, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
        }

        // Held cards in a search or the inactive tabs open their menu instead of being dragged.
        override fun isLongPressDragEnabled() = true

        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            adapter.items.getOrNull(target.bindingAdapterPosition).let { it is Item.TabCard || it is Item.GroupCard }

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
                vh.itemView.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
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
            vh.itemView.alpha = 1f
            vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
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
        data class TabCard(val tab: Tab, val title: String, val url: String, val current: Boolean) : Item()

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
        var tab: Tab? = null

        init {
            // The page's picture has its own rounded corners inside the card.
            thumbBox.clipToOutline = true
            // The touch ripple stays inside the card's rounded corners.
            view.clipToOutline = true
            Ui.pressable(close, 0.85f)
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
            view.clipToOutline = true
            Ui.pressable(close, 0.85f)
        }
    }

    private class BannerHolder(view: View) : RecyclerView.ViewHolder(view) {
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
            is Item.TabCard -> 0
            is Item.GroupCard -> 1
            is Item.Banner -> 2
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
                0 -> {
                    val v = Palette.paint(inflater.inflate(R.layout.item_tab_card, parent, false))
                    v.clipToOutline = true
                    CardHolder(v)
                }
                1 -> {
                    val v = Palette.paint(inflater.inflate(R.layout.item_group_card, parent, false))
                    v.clipToOutline = true
                    GroupHolder(v)
                }
                else -> BannerHolder(Palette.paint(inflater.inflate(R.layout.item_tab_banner, parent, false)))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.TabCard -> bindTab(holder as CardHolder, item)
                is Item.GroupCard -> bindGroup(holder as GroupHolder, item)
                is Item.Banner -> bindBanner(holder as BannerHolder, item)
            }
        }

        private fun cardBackground(dark: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(color(if (dark) R.color.incognito_url else R.color.tab_card_bg))
            setStroke(dp(1), color(if (dark) R.color.incognito_url else R.color.outline))
        }

        private fun bindTab(h: CardHolder, card: Item.TabCard) {
            val tab = card.tab
            h.tab = tab
            fitHeight(h.thumbBox)
            val dark = showingIncognito
            // Outlined rather than shadowed: just as clear, and nothing extra to draw.
            h.itemView.background = cardBackground(dark)
            h.itemView.scaleX = 1f
            h.itemView.scaleY = 1f
            h.title.text = card.title
            h.title.setTextColor(color(if (dark) R.color.incognito_text else R.color.text_primary))
            h.close.imageTintList = ColorStateList.valueOf(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
            h.thumbBox.setBackgroundColor(color(if (dark) R.color.incognito_bg else R.color.tab_thumb_bg))
            h.selected.visibility = if (card.current) View.VISIBLE else View.GONE

            if (tab.isBlank) h.icon.setImageDrawable(Ui.logo(context)) else h.icon.setImageResource(R.drawable.ic_globe)
            h.icon.imageTintList = if (tab.isBlank) null else ColorStateList.valueOf(color(R.color.text_secondary))
            if (!tab.isBlank) {
                Favicons.load(card.url) { bmp ->
                    if (bmp != null && h.tab === tab) {
                        h.icon.imageTintList = null
                        h.icon.setImageBitmap(bmp)
                    }
                }
            }

            h.thumb.setImageDrawable(null)
            h.placeholder.visibility = View.VISIBLE
            if (tab.isHome) {
                if (tab.incognito) h.placeholder.setImageResource(R.drawable.ic_incognito) else h.placeholder.setImageDrawable(Ui.logo(context))
                h.placeholder.imageTintList = if (tab.incognito) ColorStateList.valueOf(color(R.color.incognito_hint)) else null
            } else {
                h.placeholder.setImageResource(R.drawable.ic_globe)
                h.placeholder.imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
                Thumbnails.load(tab) { bmp ->
                    if (bmp != null && h.tab === tab) {
                        h.thumb.setImageBitmap(bmp)
                        h.placeholder.visibility = View.GONE
                    }
                }
            }

            h.itemView.setOnClickListener { listener?.onTabChosen(tab) }
            h.close.setOnClickListener { TabManager.close(tab, undoable = true) }
            h.itemView.contentDescription = card.title
        }

        private fun bindGroup(h: GroupHolder, card: Item.GroupCard) {
            val group = card.group
            h.group = group
            fitHeight(h.mosaic)
            val dark = showingIncognito
            h.itemView.background = cardBackground(dark)
            h.itemView.scaleX = 1f
            h.itemView.scaleY = 1f
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
            h.mosaic.setBackgroundColor(color(if (dark) R.color.incognito_url else R.color.tab_card_bg))
            h.selected.visibility = if (card.current) View.VISIBLE else View.GONE
            val radius = dp(10).toFloat()
            h.thumbs.forEachIndexed { i, view ->
                view.setImageDrawable(null)
                view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                view.clipToOutline = true
                val id = card.tabIds.getOrNull(i)
                val tab = id?.let { tid -> TabManager.tabs.firstOrNull { it.id == tid } }
                view.background = if (tab == null) null else GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(color(if (dark) R.color.incognito_bg else R.color.tab_thumb_bg))
                }
                if (tab != null && !tab.isHome) {
                    Thumbnails.load(tab) { bmp -> if (bmp != null && h.group === group) view.setImageBitmap(bmp) }
                } else if (tab != null) {
                    view.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    if (tab.incognito) view.setImageResource(R.drawable.ic_incognito) else view.setImageDrawable(Ui.logo(context))
                }
                if (tab != null && !tab.isHome) view.scaleType = ImageView.ScaleType.CENTER_CROP
            }
            h.itemView.setOnClickListener { setMode(Mode.GROUP, group) }
            h.close.setOnClickListener { TabManager.closeGroup(group) }
            h.itemView.contentDescription = "Group ${card.title}, ${card.count} tabs"
        }

        private fun bindBanner(h: BannerHolder, banner: Item.Banner) {
            val days = SwiftApp.prefs.inactiveDays
            val dark = showingIncognito
            h.itemView.background = cardBackground(dark)
            if (banner.kind == BANNER_INACTIVE) {
                h.title.text = if (banner.count == 1) "1 inactive tab" else "${banner.count} inactive tabs"
                h.text.text = "Not opened for $days days"
                h.chevron.visibility = View.VISIBLE
                h.itemView.isClickable = true
                h.itemView.setOnClickListener { setMode(Mode.INACTIVE) }
            } else {
                h.title.text = "Tabs you haven't opened for $days days"
                h.text.text = "They wait here until you open or close them. Change this in Settings → Browsing."
                h.chevron.visibility = View.GONE
                h.itemView.setOnClickListener(null)
                h.itemView.isClickable = false
            }
        }
    }

    private companion object {
        const val BANNER_INACTIVE = 1
        const val NOTE_INACTIVE = 2

        /** How long the open tab takes to shrink into its card, or to grow back out of it. */
        const val ZOOM_MS = 220L

        /** A card picture's height for its width (the top of the page is shown). */
        const val CARD_ASPECT = 1.02f
        const val CARD_ASPECT_WIDE = 0.72f

        /** How long a dragged tab rests over another card before letting go groups them. */
        const val MERGE_HOLD_MS = 350L
    }
}
