package com.swiftbrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.tabs.Thumbnails
import kotlin.math.abs

/** Full-screen grid of tabs, like Chrome's tab switcher, with a separate page for incognito tabs. */
class TabSwitcherView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onTabChosen(tab: Tab)
        fun onNewTab(incognito: Boolean)
        fun onSwitcherClosed()
    }

    var listener: Listener? = null

    /** Which page is shown: normal tabs or incognito tabs. */
    var showingIncognito = false
        private set

    val isOpen: Boolean get() = visibility == View.VISIBLE && !closing

    private var closing = false
    private val grid: RecyclerView
    private val empty: View
    private val header: View
    private val newTab: TextView
    private val toggle: View
    private val toggleNormal: View
    private val toggleIncognito: View
    private val normalCount: TextView
    private val incognitoIcon: ImageView
    private val adapter = CardAdapter()

    init {
        LayoutInflater.from(context).inflate(R.layout.view_tab_switcher, this, true)
        isClickable = true
        isFocusable = true
        grid = findViewById(R.id.switcher_grid)
        empty = findViewById(R.id.switcher_empty)
        header = findViewById(R.id.switcher_header)
        newTab = findViewById(R.id.switcher_new)
        toggle = findViewById(R.id.switcher_toggle)
        toggleNormal = findViewById(R.id.toggle_normal)
        toggleIncognito = findViewById(R.id.toggle_incognito)
        normalCount = findViewById(R.id.toggle_normal_count)
        incognitoIcon = findViewById(R.id.toggle_incognito_icon)

        grid.layoutManager = GridLayoutManager(context, 2)
        grid.adapter = adapter
        ItemTouchHelper(SwipeToClose()).attachToRecyclerView(grid)

        newTab.setOnClickListener { listener?.onNewTab(showingIncognito) }
        toggleNormal.setOnClickListener { setPage(false) }
        toggleIncognito.setOnClickListener { setPage(true) }
        findViewById<View>(R.id.switcher_empty_new).setOnClickListener { listener?.onNewTab(showingIncognito) }
    }

    fun open(incognito: Boolean) {
        showingIncognito = incognito
        closing = false
        applyPage()
        refresh(scrollToCurrent = true)
        animate().cancel()
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 1.04f
        scaleY = 1.04f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).withEndAction(null).start()
    }

    fun close() {
        if (visibility != View.VISIBLE || closing) return
        closing = true
        animate().cancel()
        animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(140).withEndAction {
            visibility = View.GONE
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            closing = false
        }.start()
        listener?.onSwitcherClosed()
    }

    private fun setPage(incognito: Boolean) {
        if (showingIncognito == incognito) return
        showingIncognito = incognito
        applyPage()
        refresh(scrollToCurrent = true)
    }

    /** Updates the grid after tabs were opened, closed or changed. */
    fun refresh(scrollToCurrent: Boolean = false) {
        val tabs = if (showingIncognito) TabManager.incognitoTabs() else TabManager.profileTabs()
        val current = TabManager.current
        adapter.submitList(tabs.map { Card(it, it.displayTitle, it.url, it === current) }) {
            if (scrollToCurrent) {
                val idx = tabs.indexOf(current)
                if (idx >= 0) grid.scrollToPosition(idx)
            }
        }
        val n = TabManager.profileTabs().size
        normalCount.text = if (n > 99) ":D" else n.toString()
        empty.visibility = if (tabs.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (tabs.isEmpty()) View.GONE else View.VISIBLE
        if (tabs.isEmpty()) {
            val incognito = showingIncognito
            findViewById<TextView>(R.id.switcher_empty_title).apply {
                text = if (incognito) "No incognito tabs" else "No open tabs"
                setTextColor(color(if (incognito) R.color.incognito_text else R.color.text_primary))
            }
            findViewById<TextView>(R.id.switcher_empty_text).apply {
                text = if (incognito) "Incognito tabs keep no history, and their cookies are deleted when you close them." else ""
                setTextColor(color(if (incognito) R.color.incognito_hint else R.color.text_secondary))
            }
            findViewById<TextView>(R.id.switcher_empty_new).text = if (incognito) "New incognito tab" else "New tab"
        }
    }

    /** A tab's picture or title changed. */
    fun refreshTab(tab: Tab) {
        val idx = adapter.currentList.indexOfFirst { it.tab === tab }
        if (idx >= 0) adapter.notifyItemChanged(idx)
    }

    private fun applyPage() {
        val dark = showingIncognito
        setBackgroundColor(color(if (dark) R.color.incognito_bg else R.color.switcher_bg))
        val text = color(if (dark) R.color.incognito_text else R.color.text_primary)
        newTab.setTextColor(text)
        val plus = ContextCompat.getDrawable(context, R.drawable.ic_add)?.mutate()?.also { it.setTint(text) }
        newTab.setCompoundDrawablesRelativeWithIntrinsicBounds(plus, null, null, null)
        toggle.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color(if (dark) R.color.incognito_url else R.color.surface_alt))
        }
        val selectedColor = if (dark) color(R.color.incognito_hint) else color(R.color.toggle_selected)
        toggleNormal.background = toggleBackground(selectedColor)
        toggleIncognito.background = toggleBackground(selectedColor)
        toggleNormal.isSelected = !dark
        toggleIncognito.isSelected = dark
        normalCount.setTextColor(text)
        normalCount.background = MainActivity.tabCountBackground(context, text)
        incognitoIcon.imageTintList = ColorStateList.valueOf(text)
        adapter.notifyDataSetChanged()
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

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ cards

    private data class Card(val tab: Tab, val title: String, val url: String, val current: Boolean)

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.card_icon)
        val title: TextView = view.findViewById(R.id.card_title)
        val close: ImageButton = view.findViewById(R.id.card_close)
        val thumbBox: View = view.findViewById(R.id.card_thumb_box)
        val thumb: ImageView = view.findViewById(R.id.card_thumb)
        val placeholder: ImageView = view.findViewById(R.id.card_placeholder)
        val selected: View = view.findViewById(R.id.card_selected)
        var tab: Tab? = null
    }

    private inner class CardAdapter : ListAdapter<Card, CardHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tab_card, parent, false)
            v.clipToOutline = true
            val h = CardHolder(v)
            val width = (parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - parent.paddingLeft - parent.paddingRight
            val cardWidth = width / 2 - dp(12)
            h.thumbBox.layoutParams.height = (cardWidth * Thumbnails.ASPECT).toInt()
            return h
        }

        override fun onBindViewHolder(h: CardHolder, position: Int) {
            val card = getItem(position)
            val tab = card.tab
            h.tab = tab
            val dark = showingIncognito
            h.itemView.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color(if (dark) R.color.incognito_url else R.color.tab_card_bg))
            }
            h.title.text = card.title
            h.title.setTextColor(color(if (dark) R.color.incognito_text else R.color.text_primary))
            h.close.imageTintList = ColorStateList.valueOf(color(if (dark) R.color.incognito_hint else R.color.text_secondary))
            h.thumbBox.setBackgroundColor(color(if (dark) R.color.incognito_bg else R.color.tab_thumb_bg))
            h.selected.visibility = if (card.current) View.VISIBLE else View.GONE

            h.icon.setImageResource(if (tab.isBlank) R.drawable.ic_launcher else R.drawable.ic_globe)
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
                h.placeholder.setImageResource(if (tab.incognito) R.drawable.ic_incognito else R.drawable.ic_launcher)
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
    }

    private inner class SwipeToClose : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val tab = (vh as CardHolder).tab ?: return
            TabManager.close(tab, undoable = true)
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, active: Boolean,
        ) {
            vh.itemView.alpha = 1f - (abs(dX) / vh.itemView.width.coerceAtLeast(1)).coerceIn(0f, 0.8f)
            super.onChildDraw(c, rv, vh, dX, dY, actionState, active)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Card>() {
            override fun areItemsTheSame(a: Card, b: Card) = a.tab === b.tab
            override fun areContentsTheSame(a: Card, b: Card) = a == b
        }
    }
}
