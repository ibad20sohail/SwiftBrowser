package com.swiftbrowser.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.BookmarkRow
import com.swiftbrowser.core.HistoryRow
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared shell for the list screens (toolbar, optional search box, list, empty screen). Rows can be
 * swiped away sideways (2.0); a removal waits until its Undo is gone (or the screen is left) before
 * it really happens.
 */
abstract class ListActivity : AppCompatActivity() {
    protected lateinit var toolbar: MaterialToolbar
    protected lateinit var list: RecyclerView
    protected lateinit var empty: FrameLayout
    protected lateinit var search: EditText

    private val handler = Handler(Looper.getMainLooper())

    /** Removals waiting for their Undo to run out, by key. */
    private val pending = LinkedHashMap<String, Runnable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        Palette.paint(findViewById<View>(android.R.id.content))
        toolbar = findViewById(R.id.list_toolbar)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.list_empty)
        search = findViewById(R.id.list_search)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.navigationContentDescription = "Back"
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setTitleTextAppearance(this, R.style.Swift_ListTitle)
        toolbar.setTitleTextColor(Palette.color(this, R.color.text_primary))
        toolbar.setSubtitleTextAppearance(this, R.style.Swift_ListSubtitle)
        toolbar.setSubtitleTextColor(Palette.color(this, R.color.text_secondary))
        window.statusBarColor = Palette.color(this, R.color.page_bg)
        list.layoutManager = LinearLayoutManager(this)
        // Back clears a search first (2.6), then leaves the screen.
        val clearSearch = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                search.setText("")
            }
        }
        onBackPressedDispatcher.addCallback(this, clearSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearSearch.isEnabled = !s.isNullOrEmpty()
            }
        })
        // The list holds the focus, so the search box doesn't show a cursor until it is tapped.
        list.isFocusableInTouchMode = true
        list.requestFocus()
        SystemBars.keepHidden(this)
        if (grouped) groupedLook()
    }

    // ------------------------------------------------------------------ 3.0.4: grouped rows and a large title

    /**
     * 3.0.4: History, Bookmarks and Downloads: their rows as rounded pieces on the settings' background
     * (GroupedRows), under a large title that moves up under the title bar as the list scrolls while
     * the bar's own title fades in. Files and the other screens built on this keep their look.
     */
    protected open val grouped: Boolean get() = false

    private var largeTitle: TextView? = null
    private var largeSubtitle: TextView? = null
    private var appBar: com.google.android.material.appbar.AppBarLayout? = null
    private var page: androidx.coordinatorlayout.widget.CoordinatorLayout? = null

    /** How far the large title has gone up (0: all on show). */
    private var appBarOffset = 0

    /** The screen's name and the line under it: in the title bar and (3.0.4) in the large title. */
    protected fun setTitles(title: CharSequence, subtitle: CharSequence?) {
        toolbar.title = title
        toolbar.subtitle = subtitle
        largeTitle?.text = title
        largeSubtitle?.let {
            it.text = subtitle
            it.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        followTitle()
    }

    /** 3.0.4: the large title on show again (the list went back to its top: another folder, another filter). */
    protected fun showLargeTitle() {
        if (appBarOffset != 0) appBar?.setExpanded(true, Motion.enabled(this))
    }

    private fun groupedLook() {
        val bg = Palette.color(this, R.color.settings_bg)
        val column = toolbar.parent as LinearLayout
        // The page's colour is the window's own: the rows' pieces are drawn over it, and a slow
        // graphics chip doesn't fill the screen twice under them.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        column.background = null
        toolbar.background = null
        window.statusBarColor = bg
        // The search box in the card colour, like the rows (the softer grey hardly shows on this background).
        search.background = search.background?.mutate()?.also { it.setTint(Palette.color(this, R.color.card_bg)) }
        // The rows' pieces a little in from the sides, like Settings' groups.
        list.setPadding(Ui.dp(this, Ui.M), Ui.dp(this, Ui.XS), Ui.dp(this, Ui.M), list.paddingBottom)

        // The large title (and the line under it) scrolls away; the search box (and what a screen adds
        // under it: Downloads' filters) stays, as before. Done as Files' header is (buildChrome).
        val searchBox = search.parent as View
        val listBox = list.parent as View
        column.removeView(searchBox)
        column.removeView(listBox)
        val title = Ui.text(this, Ui.Type.DISPLAY, "", lines = 2)
        val subtitle = Ui.text(this, Ui.Type.BODY_SECONDARY, "", lines = 1).apply { visibility = View.GONE }
        largeTitle = title
        largeSubtitle = subtitle
        val head = MaxWidthLayout(this).apply {
            addView(LinearLayout(this@ListActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@ListActivity, Ui.XXL), Ui.dp(this@ListActivity, Ui.S), Ui.dp(this@ListActivity, Ui.XXL), Ui.dp(this@ListActivity, Ui.M))
                addView(title)
                addView(subtitle)
            })
        }
        val pinned = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchBox)
        }
        val bar = com.google.android.material.appbar.AppBarLayout(this).apply {
            background = null
            stateListAnimator = null
            elevation = 0f
            addOnOffsetChangedListener { _, offset ->
                appBarOffset = offset
                followTitle()
            }
        }
        // Let go half-way, it goes all the way up or back down (Android's large title bars do the same).
        bar.addView(head, com.google.android.material.appbar.AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            scrollFlags = com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
        })
        // Made in code, AppBarLayout's children scroll by default: the flag has to be cleared to keep it.
        bar.addView(pinned, com.google.android.material.appbar.AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { scrollFlags = 0 })
        // Only a list longer than the room under it moves the title (a short one keeps it where it is).
        val behavior = object : com.google.android.material.appbar.AppBarLayout.Behavior() {
            override fun onStartNestedScroll(
                parent: androidx.coordinatorlayout.widget.CoordinatorLayout, child: com.google.android.material.appbar.AppBarLayout,
                directTargetChild: View, target: View, nestedScrollAxes: Int, type: Int,
            ) = (appBarOffset != 0 || listOverflows()) && super.onStartNestedScroll(parent, child, directTargetChild, target, nestedScrollAxes, type)
        }
        behavior.setDragCallback(object : com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback() {
            override fun canDrag(appBarLayout: com.google.android.material.appbar.AppBarLayout) = appBarOffset != 0 || listOverflows()
        })
        val coordinator = androidx.coordinatorlayout.widget.CoordinatorLayout(this)
        coordinator.addView(bar, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.behavior = behavior
        })
        coordinator.addView(listBox, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            this.behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
        })
        column.addView(coordinator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        appBar = bar
        page = coordinator
        // A list that got short while the title was up (a search, a removal): the title comes back down.
        list.viewTreeObserver.addOnGlobalLayoutListener {
            if (appBarOffset != 0 && !listOverflows()) showLargeTitle()
        }
    }

    /**
     * The large title fades as it goes up under the bar; the bar's own title (and the line under it)
     * fades in over the second half of the way. TalkBack reads the bar's title only while it shows.
     */
    private fun followTitle() {
        val title = largeTitle ?: return
        val range = (title.parent as View).height
        val gone = if (range > 0) (-appBarOffset.toFloat() / range).coerceIn(0f, 1f) else 0f
        title.alpha = 1f - gone
        largeSubtitle?.alpha = 1f - gone
        val a = ((gone - 0.5f) * 2f).coerceIn(0f, 1f)
        for (i in 0 until toolbar.childCount) {
            val c = toolbar.getChildAt(i) as? TextView ?: continue
            if (c.alpha != a) c.alpha = a
            c.importantForAccessibility = if (a > 0f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /** The list goes on past the bottom of the screen (asked as a swipe starts and after a layout, not as it moves). */
    private fun listOverflows(): Boolean {
        val coordinator = page ?: return false
        if (list.canScrollVertically(1) || list.canScrollVertically(-1)) return true
        var bottom = 0
        for (i in 0 until list.childCount) bottom = maxOf(bottom, list.getChildAt(i).bottom)
        if (bottom == 0) return false
        // Where the list's top is under the title bar with the large title all on show (it moves up
        // with it): a list that fits only once the title has gone up still counts as longer, so the
        // title doesn't come back down by itself while it is up.
        var y = 0
        var at: View = list
        while (at !== coordinator) {
            y += at.top
            at = at.parent as? View ?: break
        }
        return y - appBarOffset + bottom > coordinator.height
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    override fun onResume() {
        super.onResume()
        NightLight.attach(this)
    }

    override fun onPause() {
        // Leaving the screen: what was removed is removed now (Undo goes with the screen).
        flushRemovals()
        super.onPause()
    }

    /**
     * Removes something with an Undo: [hide] takes it off the screen now, [commit] really removes
     * it once the Undo is gone, [restore] puts it back when Undo is tapped.
     */
    protected fun removeLater(key: String, message: String, hide: () -> Unit, restore: () -> Unit, commit: () -> Unit) {
        pending.remove(key)?.let {
            handler.removeCallbacks(it)
            it.run()
        }
        hide()
        val r = Runnable {
            pending.remove(key)
            commit()
        }
        pending[key] = r
        handler.postDelayed(r, UNDO_MS)
        Haptics.tick(list)
        Messages.show(this, message, "Undo") {
            handler.removeCallbacks(r)
            if (pending.remove(key) != null) restore()
        }
    }

    private fun flushRemovals() {
        for ((_, r) in pending.toList()) {
            handler.removeCallbacks(r)
            r.run()
        }
        pending.clear()
    }

    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 3.0.4: the band behind a grouped row being swiped, shaped like its piece (made again only when that changes). */
    private val swipePath = android.graphics.Path()
    private val swipeRect = android.graphics.RectF()
    private var swipeRadii: FloatArray? = null

    /** Rows for which [canSwipe] says yes can be swiped away sideways; [onSwiped] then removes them. */
    protected fun enableSwipe(canSwipe: (RecyclerView.ViewHolder) -> Boolean, onSwiped: (RecyclerView.ViewHolder) -> Unit) {
        val icon = Ui.icon(this, R.drawable.ic_delete, Ui.danger(this))
        val iconSize = Ui.dp(this, 24)
        val margin = Ui.dp(this, 24)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (canSwipe(vh)) super.getSwipeDirs(rv, vh) else 0

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = onSwiped(vh)

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    // What is revealed behind the row: a soft red band with a bin on the near side.
                    val v = vh.itemView
                    swipePaint.color = Palette.color(this@ListActivity, R.color.danger_container)
                    val piece = Piece.of(v)
                    if (piece != null) {
                        // 3.0.4: a grouped row's band is its piece's shape where it was; the row moving
                        // over it shows it at its own rounded corners too.
                        val l = v.left.toFloat()
                        val t = v.top.toFloat()
                        val r = v.right.toFloat()
                        val b = v.bottom.toFloat()
                        if (swipeRadii !== piece.radii || swipeRect.left != l || swipeRect.top != t || swipeRect.right != r || swipeRect.bottom != b) {
                            swipeRect.set(l, t, r, b)
                            swipeRadii = piece.radii
                            swipePath.reset()
                            swipePath.addRoundRect(swipeRect, piece.radii, android.graphics.Path.Direction.CW)
                        }
                        c.drawPath(swipePath, swipePaint)
                    } else {
                        val left = if (dX > 0) v.left.toFloat() else v.right + dX
                        val right = if (dX > 0) v.left + dX else v.right.toFloat()
                        c.drawRect(left, v.top.toFloat(), right, v.bottom.toFloat(), swipePaint)
                    }
                    if (icon != null && kotlin.math.abs(dX) > margin) {
                        val top = v.top + (v.height - iconSize) / 2
                        val x = if (dX > 0) v.left + margin else v.right - margin - iconSize
                        icon.setBounds(x, top, x + iconSize, top + iconSize)
                        icon.alpha = (kotlin.math.abs(dX) / (v.width * 0.3f) * 255).toInt().coerceIn(0, 255)
                        icon.draw(c)
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, state, active)
            }
        }).attachToRecyclerView(list)
    }

    /** An empty list: an icon, what is missing, and how to fill it. */
    protected fun showEmpty(isEmpty: Boolean, title: String, hint: String? = null, icon: Int = R.drawable.ic_info) {
        if (!isEmpty) {
            empty.visibility = View.GONE
            return
        }
        empty.removeAllViews()
        empty.addView(Ui.emptyState(this, icon, title, hint), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        if (empty.visibility != View.VISIBLE) Motion.fadeIn(empty, fromDy = 12)
    }

    protected fun openUrl(url: String, newTab: Boolean = false) {
        setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, url).putExtra(MainActivity.EXTRA_NEW_TAB, newTab))
        finish()
    }

    protected fun share(url: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share"))
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** A little longer than the Undo shows (Messages), so it can still be tapped at the end. */
        const val UNDO_MS = 5600L
    }
}

class HistoryActivity : ListActivity() {
    /** A day's heading, before that day's pages. */
    private class Day(val label: String, val from: Long, val to: Long)

    private val adapter = DayAdapter()
    private var searchJob: Job? = null
    private var query = ""
    private var rows: List<HistoryRow> = emptyList()

    /**
     * 3.0: history comes [PAGE] pages at a time, more as the list is scrolled (it showed only the
     * newest 500, with no way to older ones); a search goes through all of it the same way.
     */
    private var hasMore = false
    private var loadingMore = false

    /** Counts loads: the results of one that was overtaken (a new search) are dropped. */
    private var generation = 0

    /** The search the pages shown were loaded for. */
    private var loadedQuery = ""

    // Removed, but still undoable.
    private val hiddenIds = HashSet<Long>()
    private val hiddenDays = ArrayList<Pair<Long, Long>>()

    // 3.0.4: each day's pages a group of rounded pieces, under a large title.
    override val grouped: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles("History", Profiles.current().name)
        // The last hour, day, week or all of it (2.6), and what else to clear with the history.
        toolbar.menu.add(0, 1, 0, "Clear").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            ClearData.show(this, preset = setOf(com.swiftbrowser.core.Clearing.What.HISTORY)) { load() }
            true
        }
        search.visibility = View.VISIBLE
        search.hint = "Search your history"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(200)
                    load()
                }
            }
        })
        list.adapter = adapter
        enableSwipe({ it is RowHolder }) { vh ->
            (adapter.items.getOrNull(vh.bindingAdapterPosition) as? HistoryRow)?.let { remove(it) } ?: adapter.notifyItemChanged(vh.bindingAdapterPosition)
        }
        // 3.0: near the end of what is loaded, the next pages come.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 40) loadMore()
            }
        })
        load()
    }

    private fun load() {
        val q = search.text.toString()
        query = q.trim()
        val gen = ++generation
        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) { SwiftApp.db.history(Profiles.currentId, q, PAGE) }
            if (gen != generation) return@launch
            rows = page
            loadedQuery = q
            hasMore = page.size == PAGE
            loadingMore = false
            render()
        }
    }

    private fun loadMore() {
        val last = rows.lastOrNull() ?: return
        if (!hasMore || loadingMore) return
        loadingMore = true
        val gen = generation
        val q = loadedQuery
        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) { SwiftApp.db.history(Profiles.currentId, q, PAGE, after = last) }
            if (gen != generation) return@launch
            rows = rows + page
            hasMore = page.size == PAGE
            loadingMore = false
            render()
        }
    }

    private fun render() {
        val shown = rows.filter { r -> r.id !in hiddenIds && hiddenDays.none { r.lastVisit >= it.first && r.lastVisit < it.second } }
        adapter.items = byDay(shown)
        // 3.0: too few left to fill the screen (a day deleted, say) while there are more: the next ones.
        if (shown.size < 40 && hasMore) list.post { loadMore() }
        if (query.isBlank()) {
            showEmpty(shown.isEmpty(), "No history yet", "Pages you open show up here, newest first.", R.drawable.ic_history)
        } else {
            showEmpty(shown.isEmpty(), "Nothing found", "No page in your history has \"$query\" in its title or address.", R.drawable.ic_search)
        }
    }

    private fun remove(r: HistoryRow) {
        removeLater(
            "h${r.id}", "Removed from history",
            hide = {
                hiddenIds += r.id
                render()
            },
            restore = {
                hiddenIds -= r.id
                render()
            },
            commit = {
                lifecycleScope.launch(Dispatchers.IO) { SwiftApp.db.deleteHistory(r.id) }
            },
        )
    }

    /** Today, Yesterday, then dates, each followed by its pages, newest first. */
    private fun byDay(rows: List<HistoryRow>): List<Any> {
        val out = ArrayList<Any>()
        var day: Day? = null
        for (r in rows) {
            if (day == null || r.lastVisit < day.from) {
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = r.lastVisit
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val from = cal.timeInMillis
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                day = Day(dayLabel(from), from, cal.timeInMillis)
                out += day
            }
            out += r
        }
        return out
    }

    private fun dayLabel(start: Long): String {
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayMs = 24L * 3600 * 1000
        return when {
            start >= today -> "Today"
            start >= today - dayMs -> "Yesterday"
            start >= today - 6 * dayMs -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date(start))
            else -> java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(start))
        }
    }

    /** The search word in bold and the accent colour. */
    private fun marked(text: String): CharSequence {
        if (query.isEmpty()) return text
        val i = text.indexOf(query, ignoreCase = true)
        if (i < 0) return text
        return android.text.SpannableString(text).apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + query.length, 0)
            setSpan(android.text.style.ForegroundColorSpan(Ui.accent(this@HistoryActivity)), i, i + query.length, 0)
        }
    }

    /** A whole day goes at once, with Undo (no question first). */
    private fun deleteDay(day: Day) {
        val range = day.from to day.to
        val message = if (day.label == "Today" || day.label == "Yesterday") "Deleted ${day.label.lowercase()}'s history" else "Deleted the history of ${day.label}"
        removeLater(
            "d${day.from}", message,
            hide = {
                hiddenDays += range
                render()
            },
            restore = {
                hiddenDays -= range
                render()
            },
            commit = {
                lifecycleScope.launch(Dispatchers.IO) { SwiftApp.db.deleteHistoryBetween(Profiles.currentId, day.from, day.to) }
            },
        )
    }

    private inner class DayAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Any> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        /** 3.0.4: a day's pages go together; its heading ends the group before. */
        private val groupOf: (Int) -> Any? = { i -> if (items[i] is Day) null else 0 }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Day) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                return RowHolder(Palette.paint(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))).also {
                    GroupedRows.prepare(it.itemView)
                    it.keepBackground()
                }
            }
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 3.0.4: the day lines up with what is in the rows under it (they sit a little in from the sides).
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 18), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(Ui.text(ctx, Ui.Type.SECTION, "").apply { id = R.id.row_title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(ctx, Ui.Type.LABEL, "Delete day", Ui.textSecondary(ctx)).apply {
                    id = R.id.row_action
                    setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
                    background = Ui.ripple(ctx, null, 16)
                })
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Day -> {
                    holder.itemView.findViewById<TextView>(R.id.row_title).text = item.label
                    // Searching shows matches from many days: deleting a whole day isn't offered then.
                    holder.itemView.findViewById<View>(R.id.row_action).apply {
                        visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
                        contentDescription = "Delete the history of ${item.label}"
                        setOnClickListener { deleteDay(item) }
                    }
                }
                is HistoryRow -> {
                    val h = holder as RowHolder
                    GroupedRows.place(h.itemView, position, items.size, groupOf)
                    val time = DateUtils.formatDateTime(this@HistoryActivity, item.lastVisit, DateUtils.FORMAT_SHOW_TIME)
                    h.set(R.drawable.ic_globe, marked(UrlUtils.readableTitle(item.title, item.url)), marked("${UrlUtils.siteName(item.url)} · $time"))
                    h.setFavicon(item.url)
                    h.setAction(R.drawable.ic_close, "Remove from history") { remove(item) }
                    h.itemView.setOnClickListener { openUrl(item.url) }
                    h.itemView.setOnLongClickListener {
                        rowMenu(item)
                        true
                    }
                }
            }
        }
    }

    private fun rowMenu(r: HistoryRow) {
        Sheets.actions(
            this, UrlUtils.readableTitle(r.title, r.url), UrlUtils.siteName(r.url),
            listOf(
                Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) { openUrl(r.url, newTab = true) },
                Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(r.url) },
                Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copyText(this, r.url) },
                Sheets.Action(R.drawable.ic_delete, "Remove", primary = true, danger = true) { remove(r) },
            ),
            com.swiftbrowser.core.Favicons.cached(r.url), fullAddress = r.url,
        )
    }

    private companion object {
        const val PAGE = 300
    }
}

class BookmarksActivity : ListActivity() {
    /** A row: a folder, or a bookmark (2.6). */
    private sealed class Item {
        class Folder(val name: String, val count: Int) : Item()
        class Mark(val row: BookmarkRow) : Item()
    }

    private var rows: List<BookmarkRow> = emptyList()
    private val hiddenIds = HashSet<Long>()

    /** The folder open ("" is the top of the list). */
    private var folder = ""
    private var query = ""

    private val adapter = RowAdapter<Item>(
        bind = { h, item ->
            when (item) {
                is Item.Folder -> {
                    h.set(R.drawable.ic_folder, item.name, if (item.count == 1) "1 bookmark" else "${item.count} bookmarks")
                    h.setAction(R.drawable.ic_more, "More") { folderMenu(item.name) }
                }
                is Item.Mark -> {
                    val r = item.row
                    // Found by searching: which folder it's in.
                    val where = if (query.isNotEmpty() && r.folder.isNotEmpty()) "${r.folder} · " else ""
                    h.set(R.drawable.ic_star, UrlUtils.readableTitle(r.title, r.url), where + UrlUtils.siteName(r.url))
                    h.setFavicon(r.url)
                    h.setAction(R.drawable.ic_more, "More") { rowMenu(r) }
                }
            }
        },
        onClick = { item ->
            when (item) {
                is Item.Folder -> openFolder(item.name)
                is Item.Mark -> openUrl(item.row.url)
            }
        },
        onLongClick = { item ->
            when (item) {
                is Item.Folder -> folderMenu(item.name)
                is Item.Mark -> rowMenu(item.row)
            }
        },
        // 3.0.4: the folders one group, the bookmarks another.
        groupOf = { item -> item is Item.Folder },
    )

    override val grouped: Boolean get() = true

    /** Back leaves a search, then a folder, then the screen. */
    private val back = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (query.isNotEmpty()) search.setText("") else openFolder("")
        }
    }

    private val importPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles("Bookmarks", Profiles.current().name)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.menu.add(0, 1, 0, "Import").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            Dialogs.confirm(
                this, "Import bookmarks",
                "From a bookmarks file another browser saved: in Chrome on a computer, Bookmarks → Bookmark manager → ⋮ → Export bookmarks; " +
                    "in Firefox, Bookmarks → Manage bookmarks → Import and Backup → Export. Copy the file to this phone, then choose it.",
                "Choose file",
            ) {
                try {
                    importPicker.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain", "application/octet-stream"))
                } catch (e: Exception) {
                    Messages.show(this, "No app to choose a file with")
                }
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, back)
        search.visibility = View.VISIBLE
        search.hint = "Search your bookmarks"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                render()
            }
        })
        list.adapter = adapter
        enableSwipe({ vh -> adapter.items.getOrNull(vh.bindingAdapterPosition) is Item.Mark }) { vh ->
            (adapter.items.getOrNull(vh.bindingAdapterPosition) as? Item.Mark)?.let { delete(it.row) }
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            rows = withContext(Dispatchers.IO) { SwiftApp.db.bookmarks(Profiles.currentId) }
            render()
        }
    }

    private fun folders(): List<String> = rows.map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }

    private fun openFolder(name: String) {
        folder = name
        setTitles(name.ifEmpty { "Bookmarks" }, toolbar.subtitle)
        list.scrollToPosition(0)
        showLargeTitle()
        render()
    }

    private fun render() {
        val visible = rows.filter { it.id !in hiddenIds }
        val items: List<Item> = if (query.isNotEmpty()) {
            visible.filter { it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) }.map { Item.Mark(it) }
        } else {
            val inFolder = visible.filter { it.folder == folder }.map { Item.Mark(it) }
            if (folder.isEmpty()) {
                folders().map { name -> Item.Folder(name, visible.count { it.folder == name }) } + inFolder
            } else {
                inFolder
            }
        }
        adapter.items = items
        back.isEnabled = query.isNotEmpty() || folder.isNotEmpty()
        when {
            query.isNotEmpty() -> showEmpty(items.isEmpty(), "Nothing found", "No bookmark has \"$query\" in its name or address.", R.drawable.ic_search)
            folder.isNotEmpty() -> showEmpty(items.isEmpty(), "This folder is empty", "Move bookmarks here with ⋮ → Move to folder.", R.drawable.ic_folder)
            else -> showEmpty(items.isEmpty(), "No bookmarks yet", "Tap the star in the ≡ menu to keep a page here.", R.drawable.ic_star_border)
        }
    }

    private fun delete(r: BookmarkRow) {
        removeLater(
            "b${r.id}", "Bookmark removed",
            hide = {
                hiddenIds += r.id
                render()
            },
            restore = {
                hiddenIds -= r.id
                render()
            },
            commit = {
                lifecycleScope.launch(Dispatchers.IO) { SwiftApp.db.deleteBookmark(r.id) }
            },
        )
    }

    /** The bookmark's name and its address. */
    private fun editBookmark(r: BookmarkRow) {
        val pad = Ui.dp(this, 24)
        val name = EditText(this).apply {
            hint = "Name"
            setSingleLine()
            setText(r.title)
        }
        val address = EditText(this).apply {
            hint = "Address"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(r.url)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 3, pad, 0)
            addView(name)
            addView(address)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Edit bookmark")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        // A wrong address keeps the dialog open with what was typed (2.6).
        val save = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        save?.setOnClickListener {
            val url = UrlUtils.toUrlOrSearch(address.text.toString())
            if (!UrlUtils.isHttp(url) || UrlUtils.searchQuery(url) != null && !address.text.contains('.')) {
                address.error = "That isn't a web address"
                address.requestFocus()
                return@setOnClickListener
            }
            save.isEnabled = false
            val title = name.text.toString().trim()
            lifecycleScope.launch {
                // 3.0: an address another bookmark has can't be saved twice (it crashed Swift): the
                // dialog stays open and says so.
                val taken = withContext(Dispatchers.IO) {
                    val db = SwiftApp.db
                    !db.updateBookmark(r.id, title, url) && db.bookmark(Profiles.currentId, url)?.let { it.id != r.id } == true
                }
                if (taken) {
                    save.isEnabled = true
                    address.error = "That address is already bookmarked"
                    address.requestFocus()
                    return@launch
                }
                dialog.dismiss()
                load()
            }
        }
    }

    /** Into a folder, a new one, or back to the top of the list. */
    private fun moveToFolder(r: BookmarkRow) {
        val names = folders().filter { it != r.folder }
        val choices = (if (r.folder.isNotEmpty()) listOf("Top of the list") else emptyList()) + names + "New folder…"
        MaterialAlertDialogBuilder(this)
            .setTitle("Move to folder")
            .setItems(choices.toTypedArray()) { _, which ->
                val pick = choices[which]
                when {
                    which == choices.lastIndex -> Sheets.askText(this, "New folder", "Folder name", "") { name ->
                        val n = name.trim().take(60)
                        if (n.isNotEmpty()) setFolder(r, n)
                    }
                    pick == "Top of the list" && r.folder.isNotEmpty() && which == 0 -> setFolder(r, "")
                    else -> setFolder(r, pick)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun setFolder(r: BookmarkRow, name: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SwiftApp.db.setBookmarkFolder(r.id, name) }
            load()
            Messages.show(this@BookmarksActivity, if (name.isEmpty()) "Moved to the top of the list" else "Moved to $name")
        }
    }

    private fun folderMenu(name: String) {
        Sheets.actions(
            this, name, null,
            listOf(
                Sheets.Action(R.drawable.ic_edit, "Rename", primary = true) {
                    Sheets.askText(this, "Rename folder", "Folder name", name) { text ->
                        val n = text.trim().take(60)
                        if (n.isEmpty() || n == name) return@askText
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { SwiftApp.db.renameBookmarkFolder(Profiles.currentId, name, n) }
                            load()
                        }
                    }
                },
                // The bookmarks stay, at the top of the list.
                Sheets.Action(R.drawable.ic_close, "Remove folder (keeps its bookmarks)") {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { SwiftApp.db.renameBookmarkFolder(Profiles.currentId, name, "") }
                        load()
                        Messages.show(this@BookmarksActivity, "Its bookmarks are at the top of the list")
                    }
                },
            ),
        )
    }

    private fun rowMenu(r: BookmarkRow) {
        Sheets.actions(
            this, UrlUtils.readableTitle(r.title, r.url), UrlUtils.siteName(r.url),
            listOf(
                Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) { openUrl(r.url, newTab = true) },
                Sheets.Action(R.drawable.ic_edit, "Edit", primary = true) { editBookmark(r) },
                Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(r.url) },
                Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copyText(this, r.url) },
                Sheets.Action(R.drawable.ic_folder, "Move to folder") { moveToFolder(r) },
                Sheets.Action(R.drawable.ic_delete, "Remove bookmark", danger = true) { delete(r) },
            ),
            com.swiftbrowser.core.Favicons.cached(r.url), fullAddress = r.url,
        )
    }

    /** A bookmarks file saved by Chrome, Firefox or another browser (the "Netscape" format they share). */
    private fun importFrom(uri: android.net.Uri) {
        val pid = Profiles.currentId
        // 3.0: a big file says how far it got.
        var progress: Messages.Handle? = null
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    // 3.0: read only up to its limit (a big file picked by mistake isn't read whole).
                    val bytes = contentResolver.openInputStream(uri)?.use { com.swiftbrowser.core.Storage.readAtMost(it, 30_000_000) }
                        ?: return@withContext -1
                    if (bytes.size > 500_000) withContext(Dispatchers.Main) { progress = Messages.sticky(this@BookmarksActivity, "Importing bookmarks…") }
                    val marks = BookmarkFile.parse(String(bytes, Charsets.UTF_8))
                    val db = SwiftApp.db
                    val had = db.bookmarks(pid).map { it.url }.toHashSet()
                    val fresh = marks.filter { had.add(it.url) }
                    // 3.0: one transaction (it wrote to the phone's storage once per bookmark).
                    db.transaction {
                        fresh.forEachIndexed { i, m ->
                            db.importBookmark(pid, m.url, m.title, m.added, m.folder)
                            if (i % 250 == 0 && progress != null) {
                                val text = "Importing bookmarks… ${i * 100 / fresh.size}%"
                                runOnUiThread { progress?.setText(text) }
                            }
                        }
                    }
                    fresh.size
                } catch (e: Exception) {
                    com.swiftbrowser.core.L.w("Couldn't import bookmarks", e)
                    -1
                } catch (e: OutOfMemoryError) {
                    com.swiftbrowser.core.L.w("Couldn't import bookmarks: the file is too big")
                    -1
                }
            }
            progress?.dismiss()
            load()
            Messages.show(
                this@BookmarksActivity,
                when (count) {
                    -1 -> "That file couldn't be read"
                    0 -> "No new bookmarks in that file"
                    1 -> "1 bookmark imported"
                    else -> "$count bookmarks imported"
                },
            )
        }
    }
}

/** Reads a browser's bookmarks file: links in folders (<H3>), as Chrome and Firefox export them (2.6). */
object BookmarkFile {
    class Mark(val url: String, val title: String, val folder: String, val added: Long)

    private val TOKEN = Regex("<(/?)(dl|h3|a)\\b([^>]*)>(?:([^<]*)</(?:h3|a)>)?", RegexOption.IGNORE_CASE)
    private val HREF = Regex("href\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val ADDED = Regex("add_date\\s*=\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<Mark> {
        val out = ArrayList<Mark>()
        val stack = ArrayList<String>()
        var pending: String? = null
        val now = System.currentTimeMillis()
        for (m in TOKEN.findAll(html)) {
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            when {
                tag == "h3" && !closing -> pending = decode(m.groupValues[4]).trim()
                tag == "dl" && !closing -> {
                    stack += pending ?: (stack.lastOrNull() ?: "")
                    pending = null
                }
                tag == "dl" && closing -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                tag == "a" && !closing -> {
                    val url = HREF.find(m.groupValues[3])?.groupValues?.get(1)?.let { decode(it) } ?: continue
                    if (!UrlUtils.isHttp(url)) continue
                    val added = ADDED.find(m.groupValues[3])?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000 } ?: now
                    out += Mark(url, decode(m.groupValues[4]).trim(), stack.lastOrNull()?.take(60) ?: "", added.coerceAtMost(now))
                }
            }
        }
        return out
    }

    private fun decode(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

class DownloadsActivity : ListActivity() {
    private val adapter = DownloadAdapter()
    private val listener: () -> Unit = { refresh() }
    private val hiddenIds = HashSet<Long>()

    // 3.0.4: the downloads one group of rounded pieces, newest first as before (sections by state would
    // make a row jump to another place as its download finishes), under a large title.
    override val grouped: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles("Downloads", Downloads.describe(Downloads.directory()))
        // Search by name (2.6), like History and Bookmarks.
        search.visibility = View.VISIBLE
        search.hint = "Search your downloads"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                refresh()
            }
        })
        list.adapter = adapter
        list.itemAnimator = null
        buildTop()
        // 2.9.2: a magnet link or .torrent file opened with Swift from another app.
        if (savedInstanceState == null) openTorrent(intent)
        // Finished, failed and paused downloads can be swiped off the list (the file stays).
        enableSwipe({ vh ->
            val d = adapter.items.getOrNull(vh.bindingAdapterPosition)
            d != null && d.status != Downloads.RUNNING && d.status != Downloads.QUEUED && d.status != Downloads.CONVERTING
        }) { vh -> adapter.items.getOrNull(vh.bindingAdapterPosition)?.let { removeFromList(it, deleteFile = false) } }
        refresh()
        // 2.9: files deleted elsewhere leave the list.
        Downloads.dropMissing()
    }

    override fun onStart() {
        super.onStart()
        Downloads.addListener(listener)
        refresh()
        // 2.9.2: torrents waiting for space or the night are looked at again; a copied magnet link is offered.
        com.swiftbrowser.torrent.Torrents.reschedule()
        checkClipboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTorrent(intent)
    }

    // ------------------------------------------------------------------ 2.9.2: torrents

    /** All, Videos, Torrents or Files: which downloads the list shows. */
    private var filter = 0
    private lateinit var magnetCard: LinearLayout
    private lateinit var magnetText: TextView
    private var copiedMagnet: String? = null

    /** Under the search box: the copied magnet link's card, and the filter chips. */
    private fun buildTop() {
        val box = search.parent as View
        val column = box.parent as LinearLayout
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        magnetText = Ui.text(this, Ui.Type.BODY_SECONDARY, "", Ui.onAccentContainer(this), lines = 2)
        magnetCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = Ui.shape(this@DownloadsActivity, Ui.accentContainer(this@DownloadsActivity), 16)
            setPadding(Ui.dp(this@DownloadsActivity, 16), Ui.dp(this@DownloadsActivity, 10), Ui.dp(this@DownloadsActivity, 4), Ui.dp(this@DownloadsActivity, 10))
            isClickable = true
            setOnClickListener { copiedMagnet?.let { link -> TorrentSheet.magnet(this@DownloadsActivity, link) { added() } } }
            addView(ImageView(this@DownloadsActivity).apply {
                setImageDrawable(Ui.icon(this@DownloadsActivity, R.drawable.ic_torrent, Ui.onAccentContainer(this@DownloadsActivity)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(Ui.dp(this@DownloadsActivity, 24), Ui.dp(this@DownloadsActivity, 24)))
            addView(LinearLayout(this@DownloadsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@DownloadsActivity, 14), 0, Ui.dp(this@DownloadsActivity, 8), 0)
                addView(Ui.text(this@DownloadsActivity, Ui.Type.SUBTITLE, "Add the copied magnet link", Ui.onAccentContainer(this@DownloadsActivity)))
                addView(magnetText)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(this@DownloadsActivity).apply {
                setImageDrawable(Ui.icon(this@DownloadsActivity, R.drawable.ic_close, Ui.onAccentContainer(this@DownloadsActivity)))
                background = Ui.ripple(this@DownloadsActivity, null, 20)
                contentDescription = "Not now"
                setOnClickListener {
                    dismissedMagnet = copiedMagnet?.let { com.swiftbrowser.torrent.TorrentFiles.magnetHash(it) }
                    magnetCard.visibility = View.GONE
                }
            }, LinearLayout.LayoutParams(Ui.dp(this@DownloadsActivity, 44), Ui.dp(this@DownloadsActivity, 44)))
        }
        top.addView(magnetCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(this@DownloadsActivity, 16), Ui.dp(this@DownloadsActivity, 2), Ui.dp(this@DownloadsActivity, 16), Ui.dp(this@DownloadsActivity, 8))
        })
        val chips = Sheets.chipRow(this, FILTERS, filter) { i ->
            filter = i
            refresh()
            list.scrollToPosition(0)
            showLargeTitle()
        }.apply { setPadding(Ui.dp(this@DownloadsActivity, 12), 0, Ui.dp(this@DownloadsActivity, 12), Ui.dp(this@DownloadsActivity, 4)) }
        top.addView(Palette.paint(chips), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(top, column.indexOfChild(box) + 1)
    }

    /** A magnet link on the clipboard that isn't in the list yet: offered at the top. */
    private fun checkClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } catch (_: Exception) {
            null
        }
        val hash = text?.takeIf { it.startsWith("magnet:?", ignoreCase = true) }?.let { com.swiftbrowser.torrent.TorrentFiles.magnetHash(it) }
        if (hash == null || hash == dismissedMagnet || Downloads.findTorrent(hash) != null) {
            copiedMagnet = null
            magnetCard.visibility = View.GONE
            return
        }
        copiedMagnet = text
        magnetText.text = com.swiftbrowser.torrent.TorrentFiles.magnetName(text) ?: "A torrent"
        magnetCard.visibility = View.VISIBLE
    }

    private fun added() {
        magnetCard.visibility = View.GONE
        if (filter != 0 && filter != FILTER_TORRENTS) {
            filter = 0
            refresh()
        }
        Messages.show(this, "Added to your downloads")
    }

    /** A magnet link or a .torrent file sent to Swift by another app ("Open with"). */
    private fun openTorrent(i: Intent?) {
        val data = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        if (data.scheme.equals("magnet", ignoreCase = true)) {
            TorrentSheet.magnet(this, data.toString()) { added() }
            return
        }
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(data)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            // No torrent file is this big: something else.
                            if (out.size() > MAX_TORRENT_FILE) return@use null
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            if (bytes == null) Messages.show(this@DownloadsActivity, "Couldn't read this torrent file") else TorrentSheet.file(this@DownloadsActivity, bytes) { added() }
        }
    }

    private fun isVideo(d: Downloads.Item): Boolean {
        if (d.kind == Downloads.KIND_TORRENT) {
            return com.swiftbrowser.torrent.Torrents.mainFile(d)?.let { PlayerActivity.kindOf(it.name) == PlayerActivity.Kind.VIDEO } == true
        }
        val mime = d.mime ?: UrlUtils.mimeFromName(d.fileName) ?: ""
        return d.kind == Downloads.KIND_HLS || mime.startsWith("video")
    }

    private fun shown(d: Downloads.Item): Boolean = when (filter) {
        FILTER_VIDEOS -> isVideo(d)
        FILTER_TORRENTS -> d.kind == Downloads.KIND_TORRENT
        FILTER_FILES -> d.kind != Downloads.KIND_TORRENT && !isVideo(d)
        else -> true
    }

    /** "6 min", "1 h 20 min": how long [seconds] is. */
    private fun duration(seconds: Long): String = when {
        seconds < 60 -> "<1 min"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }

    private fun torrentStatus(d: Downloads.Item): String = when (d.status) {
        Downloads.DONE -> listOf("Torrent", UrlUtils.formatBytes(d.done), dateText(d.created)).joinToString(" · ")
        Downloads.QUEUED -> d.error ?: "Waiting to start…"
        Downloads.PAUSED -> "Paused · ${UrlUtils.formatBytes(d.done)}${if (d.percent >= 0) " (${d.percent}%)" else ""}"
        Downloads.FAILED -> "Didn't finish: ${(d.error ?: "something went wrong").trimEnd('.')}. Tap ↻ to try again."
        else -> d.error ?: buildString {
            if (d.percent >= 0) append("${d.percent}% · ")
            append(UrlUtils.formatBytes(d.done))
            if (d.total > 0) append(" of ").append(UrlUtils.formatBytes(d.total))
            val speed = d.bytesPerSecond
            if (speed > 0) append(" · ").append(UrlUtils.formatBytes(speed)).append("/s")
            append(" · ").append(
                when (d.peers) {
                    0 -> "looking for people sharing it"
                    1 -> "1 peer"
                    else -> "${d.peers} peers"
                },
            )
            if (speed > 0 && d.total > d.done) append(" · ").append(duration((d.total - d.done) / speed)).append(" left")
        }
    }

    private fun openTorrentItem(d: Downloads.Item) {
        val main = com.swiftbrowser.torrent.Torrents.mainFile(d)
        val folder = File(d.filePath)
        when {
            main != null && main.exists() && PlayerActivity.kindOf(main.name) != null -> PlayerActivity.open(this, main)
            folder.isDirectory -> FilesActivity.open(this, folder)
            main != null && main.exists() -> FilesActivity.open(this, main.parentFile ?: return)
            else -> Messages.show(this, "The files aren't there any more")
        }
    }

    private fun torrentMenu(d: Downloads.Item) {
        val done = d.status == Downloads.DONE
        val running = d.status == Downloads.RUNNING || d.status == Downloads.QUEUED
        val actions = ArrayList<Sheets.Action>()
        if (done) {
            val video = isVideo(d)
            actions += Sheets.Action(if (video) R.drawable.ic_play else R.drawable.ic_open_new, if (video) "Play" else "Open", primary = true) { openTorrentItem(d) }
            actions += Sheets.Action(R.drawable.ic_folder, "Where", primary = true) { showInFolder(d) }
        }
        actions += Sheets.Action(R.drawable.ic_link, "Copy link", primary = true) { copyText(this, d.url) }
        if (d.extra.optInt("files") > 1) actions += Sheets.Action(R.drawable.ic_check, "Choose the files") { TorrentSheet.chooseFiles(this, d) }
        if (!running) actions += Sheets.Action(R.drawable.ic_close, "Remove from list (keep the files)") { removeFromList(d, deleteFile = false) }
        actions += if (running || d.status == Downloads.PAUSED) {
            Sheets.Action(R.drawable.ic_close, "Cancel download", danger = true) { removeFromList(d, deleteFile = true) }
        } else {
            Sheets.Action(R.drawable.ic_delete, "Delete the files", danger = true) { removeFromList(d, deleteFile = true) }
        }
        Sheets.actions(this, d.fileName, statusText(d), actions, iconRes = R.drawable.ic_torrent)
    }

    override fun onStop() {
        Downloads.removeListener(listener)
        super.onStop()
    }

    private var query = ""

    /** What each row showed, so a progress tick redraws only the rows that changed (2.6). */
    private var drawn: List<String> = emptyList()

    private fun refresh() {
        val items = Downloads.all().filter { it.id !in hiddenIds && shown(it) && (query.isEmpty() || it.fileName.contains(query, ignoreCase = true)) }
        val now = items.map { "${it.status}|${it.done}|${it.total}|${it.fileName}|${it.error}|${it.bytesPerSecond}|${it.peers}" }
        if (items.size == adapter.items.size && items.indices.all { items[it] === adapter.items[it] }) {
            for (i in now.indices) if (now[i] != drawn.getOrNull(i)) adapter.notifyItemChanged(i)
        } else {
            adapter.items = items
        }
        drawn = now
        if (query.isNotEmpty()) {
            showEmpty(items.isEmpty(), "Nothing found", "No download has \"$query\" in its name.", R.drawable.ic_search)
        } else if (filter == FILTER_TORRENTS) {
            showEmpty(items.isEmpty(), "No torrents yet", "Tap a magnet link on a site, or open a .torrent file, and it downloads here.", R.drawable.ic_torrent)
        } else if (filter != 0) {
            showEmpty(items.isEmpty(), "Nothing here", "No download of this kind yet.", R.drawable.ic_download)
        } else {
            showEmpty(
                items.isEmpty(), "No downloads yet",
                "Hold a link, picture or video and choose Download. On pages with videos, the download button appears next to the address.",
                R.drawable.ic_download,
            )
        }
    }

    /** Off the list (and with [deleteFile], the file too) once the Undo is gone. */
    private fun removeFromList(d: Downloads.Item, deleteFile: Boolean) {
        val running = d.status == Downloads.RUNNING || d.status == Downloads.QUEUED || d.status == Downloads.CONVERTING
        // 3.0: an unfinished download's files stay too (before 3.0 they went): where they are is said.
        val where = (if (d.kind == Downloads.KIND_TORRENT) d.extra.optString("dir").takeIf { it.isNotEmpty() }?.let { File(it) } else null)
            ?: File(d.filePath).parentFile
        removeLater(
            "dl${d.id}",
            when {
                running -> "Download cancelled"
                deleteFile -> "Deleted ${d.fileName}"
                d.status == Downloads.DONE -> if (d.kind == Downloads.KIND_TORRENT) "Removed from the list (the files stay)" else "Removed from the list (the file stays)"
                where != null -> "Removed from the list. What was downloaded stays in ${Downloads.describe(where)}"
                else -> "Removed from the list (what was downloaded stays)"
            },
            hide = {
                hiddenIds += d.id
                refresh()
            },
            restore = {
                hiddenIds -= d.id
                refresh()
            },
            commit = {
                Downloads.remove(d.id, deleteFile = deleteFile || running)
                hiddenIds -= d.id
            },
        )
    }

    private inner class DownloadHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.dl_icon)
        val name: TextView = v.findViewById(R.id.dl_name)
        val progress: ProgressBar = v.findViewById(R.id.dl_progress)
        val status: TextView = v.findViewById(R.id.dl_status)
        val action: ImageButton = v.findViewById(R.id.dl_action)
        val more: ImageButton = v.findViewById(R.id.dl_more)

        // 3.0.3: a finished video's frame in place of its icon (see showFrame), and the icon as it was.
        val iconTint = icon.imageTintList
        val iconBadge: android.graphics.drawable.Drawable? = icon.background
        val iconPad = icon.paddingLeft
        val iconScale: ImageView.ScaleType = icon.scaleType
        val frameFill = Ui.shape(v.context, Palette.color(v.context, R.color.surface_alt), Ui.R_SMALL)
        var frameRequest: com.swiftbrowser.files.FileThumbs.Request? = null
        var frameFor: String? = null
        var frame: android.graphics.Bitmap? = null
    }

    private inner class DownloadAdapter : RecyclerView.Adapter<DownloadHolder>() {
        var items: List<Downloads.Item> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = items.size

        /** 3.0.4: every download is in the one group. */
        private val groupOf: (Int) -> Any? = { 0 }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            DownloadHolder(Palette.paint(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))).also {
                // The bar in the accent chosen (2.6), your own included.
                it.progress.progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent(parent.context))
                GroupedRows.prepare(it.itemView)
            }

        override fun onBindViewHolder(h: DownloadHolder, position: Int) {
            val d = items[position]
            GroupedRows.place(h.itemView, position, items.size, groupOf)
            val mime = d.mime ?: UrlUtils.mimeFromName(d.fileName) ?: ""
            val video = d.kind == Downloads.KIND_HLS || mime.startsWith("video")
            h.icon.setImageResource(
                when {
                    d.kind == Downloads.KIND_TORRENT -> R.drawable.ic_torrent
                    video -> R.drawable.ic_movie
                    mime.startsWith("image") -> R.drawable.ic_image
                    mime.startsWith("audio") -> R.drawable.ic_headphones
                    PageActions.isSavedPage(d) -> R.drawable.ic_offline
                    // What kind of file it is (2.6), not just "a download".
                    com.swiftbrowser.docs.Docs.kindOf(d.fileName, d.mime) != null -> R.drawable.ic_documents
                    d.fileName.endsWith(".zip", true) || d.fileName.endsWith(".rar", true) || d.fileName.endsWith(".7z", true) -> R.drawable.ic_folder
                    d.fileName.endsWith(".apk", true) -> R.drawable.ic_update
                    else -> R.drawable.ic_download
                },
            )
            showFrame(h, d, video)
            h.name.text = d.fileName
            val pct = d.percent
            h.progress.visibility = if (d.status == Downloads.DONE) View.GONE else View.VISIBLE
            h.progress.isIndeterminate = (d.status == Downloads.RUNNING && pct < 0) || d.status == Downloads.CONVERTING
            // Moves smoothly to the new value instead of jumping.
            if (pct >= 0) h.progress.setProgress(pct, h.progress.progress < pct)
            h.status.text = statusText(d)
            h.status.setTextColor(if (d.status == Downloads.FAILED) Ui.danger(this@DownloadsActivity) else Ui.textSecondary(this@DownloadsActivity))
            when (d.status) {
                Downloads.RUNNING, Downloads.QUEUED -> {
                    h.action.visibility = View.VISIBLE
                    h.action.setImageResource(R.drawable.ic_pause)
                    h.action.contentDescription = "Pause"
                    h.action.setOnClickListener { Downloads.pause(d.id) }
                }
                Downloads.PAUSED, Downloads.FAILED -> {
                    // 3.0: no ↻ for what can never be saved (a live stream, copy protection).
                    val never = d.status == Downloads.FAILED && d.kind != Downloads.KIND_TORRENT &&
                        (d.extra.optBoolean("local") || problemOf(d).why == com.swiftbrowser.download.DownloadRules.Why.PERMANENT)
                    h.action.visibility = if (never) View.GONE else View.VISIBLE
                    h.action.setImageResource(if (d.status == Downloads.FAILED) R.drawable.ic_refresh else R.drawable.ic_play)
                    h.action.contentDescription = if (d.status == Downloads.FAILED) "Try again" else "Resume"
                    h.action.setOnClickListener { Downloads.resume(d.id) }
                }
                else -> h.action.visibility = View.GONE
            }
            h.itemView.setOnClickListener { if (d.status == Downloads.DONE) open(d) }
            h.itemView.setOnLongClickListener {
                menu(d, h.frame)
                true
            }
            h.more.setOnClickListener { menu(d, h.frame) }
        }

        override fun onViewRecycled(holder: DownloadHolder) {
            holder.frameRequest?.cancel()
            holder.frameRequest = null
        }
    }

    /**
     * 3.0.3: a finished video download whose file is still there shows a frame of it in a rounded
     * square, instead of the film icon. Made off the main thread by Files' picture maker and kept in its
     * memory (a missing file makes none: the icon stays); downloads running or of other kinds keep their icons.
     */
    private fun showFrame(h: DownloadHolder, d: Downloads.Item, video: Boolean) {
        h.frameRequest?.cancel()
        h.frameRequest = null
        h.frame = null
        h.icon.imageTintList = h.iconTint
        h.icon.background = h.iconBadge
        h.icon.clipToOutline = false
        h.icon.setPadding(h.iconPad, h.iconPad, h.iconPad, h.iconPad)
        h.icon.scaleType = h.iconScale
        val wanted = video && d.status == Downloads.DONE && d.kind != Downloads.KIND_TORRENT
        h.frameFor = if (wanted) d.filePath else null
        if (!wanted) return
        val key = d.filePath
        h.frameRequest = com.swiftbrowser.files.FileThumbs.load(this, File(d.filePath), d.created, com.swiftbrowser.files.FileKind.VIDEO, Ui.dp(this, 40)) { bmp ->
            if (h.frameFor != key) return@load
            h.frame = bmp
            h.icon.imageTintList = null
            h.icon.background = h.frameFill
            h.icon.clipToOutline = true
            h.icon.setPadding(0, 0, 0, 0)
            h.icon.scaleType = ImageView.ScaleType.CENTER_CROP
            h.icon.setImageBitmap(bmp)
        }
    }

    /** When it was downloaded: the time today, else the day (2.6). */
    private fun dateText(time: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = time }
        return when {
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) ->
                android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(time))
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) ->
                java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(time))
            else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(time))
        }
    }

    private fun statusText(d: Downloads.Item): String = if (d.kind == Downloads.KIND_TORRENT) torrentStatus(d) else when (d.status) {
        // 3.0: with what to know about it (kept as .ts, only part of it, its sound apart).
        Downloads.DONE -> listOfNotNull(
            com.swiftbrowser.docs.Docs.kindOf(d.fileName, d.mime)?.label, UrlUtils.formatBytes(d.done), dateText(d.created),
            d.extra.optString("note").takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        Downloads.QUEUED -> when (d.error) {
            Downloads.WAITING_FOR_WIFI -> "Waiting for Wi-Fi (it's a big file)"
            null -> "Waiting to start…"
            else -> d.error ?: ""
        }
        Downloads.PAUSED -> "Paused · ${UrlUtils.formatBytes(d.done)}${if (d.percent >= 0) " (${d.percent}%)" else ""}"
        Downloads.FAILED -> failedText(d)
        Downloads.CONVERTING -> "Saving as MP4…"
        else -> buildString {
            // 3.0: a try after the connection was lost says so.
            d.extra.optInt("retries", 0).takeIf { it > 0 }?.let { append(com.swiftbrowser.download.DownloadRules.retrying(it)).append(" · ") }
            if (d.percent >= 0) append("${d.percent}% · ")
            append(UrlUtils.formatBytes(d.done))
            if (d.total > 0 && d.kind != Downloads.KIND_HLS) append(" of ").append(UrlUtils.formatBytes(d.total))
            if (d.bytesPerSecond > 0) append(" · ").append(UrlUtils.formatBytes(d.bytesPerSecond)).append("/s")
            secondsLeft(d)?.let { append(" · ").append(duration(it)).append(" left") }
        }
    }

    /**
     * 3.0.3: about how long a running download has left, as for torrents: a file's from its size, a
     * stream's (HLS, DASH: no size until the end) from how much of its parts are in. Null until known.
     */
    private fun secondsLeft(d: Downloads.Item): Long? {
        val fraction = when {
            d.kind == Downloads.KIND_HLS -> com.swiftbrowser.download.DownloadRules.streamFraction(d.segDone, d.segTotal, d.soundParts, d.soundShare)
            d.total > 0 -> d.done.toDouble() / d.total
            else -> return null
        }
        return com.swiftbrowser.download.DownloadRules.secondsLeft(d.done, fraction, d.bytesPerSecond)
    }

    /**
     * 3.0: what went wrong in plain words ("The phone is full", "The site refused — the link may
     * have expired"; rows from before 3.0 kept raw errors, put in words here), and ↻ only when trying
     * again can help (not for a live stream or copy protection).
     */
    private fun failedText(d: Downloads.Item): String {
        val p = problemOf(d)
        val text = if (p.text.startsWith("Swift")) p.text else p.text.replaceFirstChar { it.lowercase() }
        // A page Swift saved itself can't be fetched again: it is saved again from the page.
        val again = p.retryable && !d.extra.optBoolean("local")
        return "Didn't finish: " + text.trimEnd('.') + "." + if (again) " Tap ↻ to try again." else ""
    }

    private fun problemOf(d: Downloads.Item): com.swiftbrowser.download.DownloadRules.Problem {
        val why = d.extra.optString("why")
        if (why.isEmpty()) return com.swiftbrowser.download.DownloadRules.reword(d.error)
        return com.swiftbrowser.download.DownloadRules.Problem(
            runCatching { com.swiftbrowser.download.DownloadRules.Why.valueOf(why) }.getOrDefault(com.swiftbrowser.download.DownloadRules.Why.OTHER),
            d.error ?: "Something went wrong.",
        )
    }

    private fun open(d: Downloads.Item) {
        if (d.kind == Downloads.KIND_TORRENT) {
            openTorrentItem(d)
            return
        }
        if (VpnUi.isSetupFile(d)) {
            VpnUi.addDownloaded(this, d)
            return
        }
        // 3.0: videos and songs in Swift's player (going on where they were left, with the subtitles
        // next to them and the folder as the playlist), pictures in its picture viewer.
        val file = File(d.filePath)
        if (file.isFile && (PlayerActivity.kindOf(d.fileName) != null || MediaViewerActivity.kindOf(d.fileName) == MediaViewerActivity.Kind.IMAGE)) {
            if (PlayerActivity.kindOf(d.fileName) != null) PlayerActivity.open(this, file, incognito = d.incognito) else MediaViewerActivity.open(this, file)
            return
        }
        if (PageActions.isSavedPage(d) || com.swiftbrowser.docs.Docs.kindOf(d.fileName, d.mime) != null && File(d.filePath).exists()) {
            // Documents (pages saved as PDF among them) open in Swift's own viewer (2.4).
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_FILE, d.filePath)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
            return
        }
        val intent = openFileIntent(this, d)
        if (intent == null) {
            Messages.show(this, "The file isn't there any more")
            return
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Messages.show(this, "No app on this phone can open this file")
        }
    }

    /** 3.0: Android's "Open with" list, without Swift's own player and viewers (the file opens in those by a tap). */
    private fun openWithAnother(d: Downloads.Item) {
        val view = openFileIntent(this, d)
        if (view == null) {
            Messages.show(this, "The file isn't there any more")
            return
        }
        try {
            startActivity(
                Intent.createChooser(view, "Open with").putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(android.content.ComponentName(this, PlayerActivity::class.java), android.content.ComponentName(this, MediaViewerActivity::class.java)),
                ),
            )
        } catch (_: Exception) {
            Messages.show(this, "No app on this phone can open this file")
        }
    }

    private fun shareFile(d: Downloads.Item) {
        openFileIntent(this, d)?.let { view ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = view.type
                putExtra(Intent.EXTRA_STREAM, view.data)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, "Share"))
            } catch (_: Exception) {
            }
        }
    }

    /** [frame] (3.0.3): the video's frame its row shows, for the sheet's picture too. */
    private fun menu(d: Downloads.Item, frame: android.graphics.Bitmap? = null) {
        if (d.kind == Downloads.KIND_TORRENT) {
            torrentMenu(d)
            return
        }
        val done = d.status == Downloads.DONE
        val running = d.status == Downloads.RUNNING || d.status == Downloads.QUEUED || d.status == Downloads.CONVERTING
        val actions = ArrayList<Sheets.Action>()
        val media = PlayerActivity.kindOf(d.fileName)
        if (done) {
            actions += Sheets.Action(if (media != null) R.drawable.ic_play else R.drawable.ic_open_new, if (media != null) "Play" else "Open", primary = true) { open(d) }
            actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { shareFile(d) }
            actions += Sheets.Action(R.drawable.ic_folder, "Where", primary = true) { showInFolder(d) }
            // 3.0: Swift's own player or viewer opens them; another app is still a choice.
            if (media != null || MediaViewerActivity.kindOf(d.fileName) == MediaViewerActivity.Kind.IMAGE) {
                actions += Sheets.Action(R.drawable.ic_reopen, "Open with another app") { openWithAnother(d) }
            }
        }
        actions += Sheets.Action(R.drawable.ic_link, "Copy", primary = true) { copyText(this, d.url) }
        // 3.0: a stream that stopped part-way can be kept as it is: a video that ends early.
        if ((d.status == Downloads.FAILED || d.status == Downloads.PAUSED) && Downloads.hasPart(d)) {
            actions += Sheets.Action(R.drawable.ic_check, "Keep what was downloaded") {
                Downloads.keepPart(d.id)
                Messages.show(this, "Saving what was downloaded…")
            }
        }
        if (!running) actions += Sheets.Action(R.drawable.ic_close, if (done) "Remove from list (keep the file)" else "Remove from list (keep what was downloaded)") { removeFromList(d, deleteFile = false) }
        actions += if (running) {
            Sheets.Action(R.drawable.ic_close, "Cancel download", danger = true) { removeFromList(d, deleteFile = true) }
        } else {
            Sheets.Action(R.drawable.ic_delete, "Delete the file", danger = true) { removeFromList(d, deleteFile = true) }
        }
        val mime = d.mime ?: UrlUtils.mimeFromName(d.fileName) ?: ""
        val kind = when {
            d.kind == Downloads.KIND_HLS || mime.startsWith("video") -> R.drawable.ic_movie
            mime.startsWith("image") -> R.drawable.ic_image
            mime.startsWith("audio") -> R.drawable.ic_headphones
            PageActions.isSavedPage(d) -> R.drawable.ic_offline
            else -> R.drawable.ic_download
        }
        Sheets.actions(this, d.fileName, statusText(d), actions, icon = frame, iconRes = kind)
    }

    /** Opens the phone's file manager at the download's folder, or says where it is. */
    private fun showInFolder(d: Downloads.Item) {
        // A torrent of several files is a folder of its own: there.
        val dir = File(d.filePath).takeIf { d.kind == Downloads.KIND_TORRENT && it.isDirectory } ?: File(d.filePath).parentFile ?: return
        // 2.7: in Swift's own Files screen.
        if (dir.isDirectory) {
            FilesActivity.open(this, dir)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(d.fileName)
            .setMessage("Saved in ${Downloads.describe(dir)}\n\n${dir.path}")
            .setPositiveButton(android.R.string.ok, null)
            .showImmersive()
    }

    companion object {
        private val FILTERS = listOf("All", "Videos", "Torrents", "Files")
        private const val FILTER_VIDEOS = 1
        private const val FILTER_TORRENTS = 2
        private const val FILTER_FILES = 3
        private const val MAX_TORRENT_FILE = 20 * 1024 * 1024

        /** The copied magnet link the user said "not now" to (until another is copied). */
        private var dismissedMagnet: String? = null

        fun openFileIntent(ctx: Context, d: Downloads.Item): Intent? {
            val file = File(d.filePath)
            if (!file.exists()) return null
            // Never a file:// link (Android refuses them): Swift's shared folders, or a copy (2.6).
            val uri = com.swiftbrowser.docs.Docs.shareUri(ctx, file) ?: return null
            val mime = UrlUtils.mimeFromName(d.fileName) ?: d.mime ?: "*/*"
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        /**
         * 3.0: what a finished download's notification opens: a video or song in Swift's player, a
         * picture in its viewer (by the file, not through another app's link), anything else as before.
         */
        fun openIntent(ctx: Context, d: Downloads.Item): Intent? {
            val file = File(d.filePath)
            if (!file.isFile) return null
            return when {
                PlayerActivity.kindOf(d.fileName) != null -> PlayerActivity.intentFor(ctx, file, d.incognito)
                MediaViewerActivity.kindOf(d.fileName) == MediaViewerActivity.Kind.IMAGE -> MediaViewerActivity.intentFor(ctx, file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                else -> openFileIntent(ctx, d)
            }
        }
    }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
    val activity = ctx as? android.app.Activity
    if (activity is MainActivity) activity.snack("Copied")
    else if (activity != null) Messages.show(activity, "Copied")
    else android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}
