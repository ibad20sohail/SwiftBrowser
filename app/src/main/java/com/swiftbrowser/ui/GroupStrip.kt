package com.swiftbrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.swiftbrowser.R
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabGroup
import com.swiftbrowser.tabs.TabManager

/**
 * 3.0.4: the open tab's group as a slim strip by the address bar, as in Chrome: a chip for each of
 * its tabs (the site's icon and a short title, the open one in the group's colour), scrolling
 * sideways, and + for a new tab in the group. MainActivity puts it on the page's side of the bar
 * and moves it with the bar, and paints it in the bar's colours (a website's too, see [paint]).
 * Plain views, changed only when the group or its tabs change.
 */
class GroupStrip(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onTabChosen(tab: Tab)
        fun onTabHeld(tab: Tab)
        fun onNewTab(group: TabGroup)
    }

    var listener: Listener? = null

    private val scroller = HorizontalScrollView(context)
    private val chips = LinearLayout(context)
    private val add = ImageButton(context)

    /** The chips and +. */
    private val bar = LinearLayout(context)

    /** The hairline between the strip and the page. */
    private val line = View(context)
    private var group: TabGroup? = null

    /** What the strip shows now: a page loading (several updates a second) changes nothing here. */
    private var shown = 0L

    /** 3.0.4: the bar's colours as MainActivity paints them ([paint]): Swift's, incognito's or a website's. */
    private var colours: BarColours? = null

    /** The open tab, and its group's colour (the open chip's outline). */
    private var openTab: Tab? = null
    private var groupColour = 0

    /** The colours of the chips of the tab left and the tab opened, fading. */
    private var openFade: android.animation.ValueAnimator? = null

    /** Whether the ripples are a dark bar's; null until first painted. */
    private var rippleDark: Boolean? = null

    /** A chip's tab, the address its icon is for, and whether that icon is one of Swift's (tinted, not the site's). */
    private class Chip(val tab: Tab, val url: String) {
        var tinted = false
        var open = false
    }

    init {
        // Its empty parts are the strip's: a tap there doesn't reach the page under it.
        isClickable = true
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        scroller.isHorizontalScrollBarEnabled = false
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        chips.setPadding(dp(6), 0, dp(2), 0)
        Ui.fadeEdges(scroller)
        scroller.addView(chips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(scroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        add.setImageResource(R.drawable.ic_add)
        add.scaleType = ImageView.ScaleType.CENTER
        add.background = Ui.rippleBorderless(context)
        add.contentDescription = "New tab in this group"
        TooltipCompat.setTooltipText(add, add.contentDescription)
        Ui.pressable(add, Motion.PRESS_ICON)
        add.setOnClickListener { group?.let { g -> listener?.onNewTab(g) } }
        bar.addView(add, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, dp(1).coerceAtMost(2), Gravity.BOTTOM))
    }

    /** The hairline on the page's side: at the top when the strip is above a bar at the bottom. */
    fun setLineAtTop(top: Boolean) {
        val lp = line.layoutParams as LayoutParams
        val gravity = if (top) Gravity.TOP else Gravity.BOTTOM
        if (lp.gravity != gravity) {
            lp.gravity = gravity
            line.layoutParams = lp
        }
    }

    /** Shows [group]'s [tabs], [current] the open one; nothing is touched when nothing changed. */
    fun show(group: TabGroup, tabs: List<Tab>, current: Tab) {
        val incognito = current.incognito
        var key = group.id * 31 + group.color
        key = key * 31 + current.id
        key = key * 31 + if (incognito) 1 else 0
        for (t in tabs) key = (key * 31 + t.id) * 31 + t.title.hashCode() * 17L + t.url.hashCode()
        key = key * 31 + tabs.size
        if (key == shown && this.group === group) return
        val sameGroup = this.group?.id == group.id
        shown = key
        this.group = group
        while (chips.childCount < tabs.size) {
            chips.addView(chip(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        while (chips.childCount > tabs.size) chips.removeViewAt(chips.childCount - 1)
        groupColour = color(TabGroup.colorRes(group.color))
        openTab = current
        openFade?.cancel()
        val fading = ArrayList<TextView>()
        tabs.forEachIndexed { i, t -> bindChip(chips.getChildAt(i) as TextView, t, t === current, if (sameGroup) fading else null) }
        contentDescription = "Tabs in the group ${TabManager.groupTitle(group)}"
        if (fading.isNotEmpty()) fadeChips(fading)
        reveal(tabs.indexOf(current), smooth = sameGroup)
    }

    /**
     * 3.0.4: the strip in the address bar's colours [c] (also each step while they change): Swift's,
     * incognito's or a website's.
     */
    fun paint(c: BarColours) {
        openFade?.cancel()
        colours = c
        setBackgroundColor(c.bar)
        line.setBackgroundColor(c.line)
        add.imageTintList = ColorStateList.valueOf(c.icon)
        // Light ripples on a dark bar, also in the light look: only when that changes.
        if (c.dark != rippleDark) {
            rippleDark = c.dark
            val tint = if (c.dark) Ui.RIPPLE_ON_DARK else null
            add.background = Ui.rippleBorderless(context, tint)
            for (i in 0 until chips.childCount) chips.getChildAt(i).foreground = Ui.ripple(context, null, 15, tint)
        }
        for (i in 0 until chips.childCount) {
            val v = chips.getChildAt(i) as TextView
            val chip = v.tag as? Chip ?: continue
            colourChip(v, chip.tab === openTab)
            if (chip.tinted) v.compoundDrawablesRelative[0]?.setTint(c.icon)
        }
    }

    private fun chip(): TextView = TextView(context).apply {
        Ui.size(this, Ui.Type.CAPTION, keepFont = true)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(136)
        gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = dp(6)
        setPadding(dp(9), 0, dp(11), 0)
        // Over the chip's own shape, which colourChip changes.
        foreground = Ui.ripple(context, null, 15, if (rippleDark == true) Ui.RIPPLE_ON_DARK else null)
        Ui.pressable(this, Motion.PRESS_BUTTON)
        setOnClickListener { v -> (v.tag as? Chip)?.let { listener?.onTabChosen(it.tab) } }
        setOnLongClickListener { v ->
            val chip = v.tag as? Chip ?: return@setOnLongClickListener false
            Haptics.hold(v)
            listener?.onTabHeld(chip.tab)
            true
        }
    }

    /** [fading]: where a chip goes that has just become the open one, or just stopped being it; null: all at once. */
    private fun bindChip(v: TextView, tab: Tab, open: Boolean, fading: MutableList<TextView>?) {
        val title = tab.displayTitle
        if (v.text.toString() != title) v.text = title
        v.contentDescription = if (open) "$title, the open tab" else title
        val was = v.tag as? Chip
        val turned = was != null && was.tab === tab && was.open != open
        if (was?.tab !== tab || was.url != tab.url) {
            v.tag = Chip(tab, tab.url)
            setIcon(v, tab)
        }
        (v.tag as Chip).open = open
        if (turned && fading != null && isShown && Motion.enabled(context)) fading += v else colourChip(v, open)
    }

    /** The two chips involved when another tab opens take their new colours smoothly; only their shapes are redrawn. */
    private fun fadeChips(list: List<TextView>) {
        val c = colours
        if (c == null) {
            for (v in list) colourChip(v, (v.tag as Chip).open)
            return
        }
        val opening = list.map { (it.tag as Chip).open }
        val ring = if (c.site) BarTones.onBar(groupColour, c.bar, c.text) else groupColour
        val openFill = Tones.mix(ring, c.bar, 0.8)
        val width = Ui.dpf(context, 1.5f).toInt().coerceAtLeast(1)
        openFade = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.SHORT
            interpolator = Motion.standard
            addUpdateListener { anim ->
                val t = anim.animatedFraction.toDouble()
                list.forEachIndexed { i, v ->
                    val shape = v.background as? android.graphics.drawable.GradientDrawable ?: return@forEachIndexed
                    val to = if (opening[i]) t else 1.0 - t
                    shape.setColor(BarTones.blend(c.pill, openFill, to))
                    shape.setStroke(width, BarTones.blend(c.pill, ring, to))
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    list.forEachIndexed { i, v -> colourChip(v, opening[i]) }
                }
            })
            start()
        }
    }

    /**
     * A chip in the bar's colours; the open tab's in the group's colour: a soft fill and an outline,
     * so its title stays readable. 3.0.4: its own shape is changed in place (it's repainted while the
     * bar's colours change); on a website's colour the group's is checked for contrast.
     */
    private fun colourChip(v: TextView, open: Boolean) {
        val c = colours ?: return
        v.setTextColor(c.text)
        val shape = v.background as? android.graphics.drawable.GradientDrawable ?: Ui.shape(context, c.pill, 15).also { v.background = it }
        if (open) {
            val ring = if (c.site) BarTones.onBar(groupColour, c.bar, c.text) else groupColour
            shape.setColor(Tones.mix(ring, c.bar, 0.8))
            shape.setStroke(Ui.dpf(context, 1.5f).toInt().coerceAtLeast(1), ring)
        } else {
            shape.setColor(c.pill)
            shape.setStroke(0, android.graphics.Color.TRANSPARENT)
        }
    }

    /** The site's icon: Swift's logo for a new tab, a document's mark, or a globe until the icon comes. */
    private fun setIcon(v: TextView, tab: Tab) {
        val size = dp(16)
        val viewer = com.swiftbrowser.docs.Docs.isViewerUrl(tab.url)
        val secondary = colours?.icon ?: color(if (tab.incognito) R.color.incognito_hint else R.color.text_secondary)
        val first: Drawable? = when {
            tab.isBlank -> Ui.logo(context)
            viewer -> Ui.icon(context, R.drawable.ic_documents, secondary)
            else -> Ui.icon(context, R.drawable.ic_globe, secondary)
        }
        first?.setBounds(0, 0, size, size)
        v.setCompoundDrawablesRelative(first, null, null, null)
        (v.tag as? Chip)?.tinted = !tab.isBlank
        if (tab.isBlank || viewer) return
        val url = tab.url
        Favicons.load(url) { bmp ->
            val chip = v.tag as? Chip
            if (bmp != null && chip?.tab === tab && chip.url == url) {
                val icon = BitmapDrawable(resources, bmp).apply { setBounds(0, 0, size, size) }
                v.setCompoundDrawablesRelative(icon, null, null, null)
                chip.tinted = false
            }
        }
    }

    /** The open tab's chip comes into sight if it isn't. */
    private fun reveal(index: Int, smooth: Boolean) {
        if (index < 0) return
        scroller.post {
            val c = chips.getChildAt(index) ?: return@post
            val x = scroller.scrollX
            val left = c.left - dp(12)
            val right = c.right + dp(12) - scroller.width
            val target = when {
                left < x -> left
                right > x -> right
                else -> return@post
            }
            if (smooth) scroller.smoothScrollTo(target, 0) else scroller.scrollTo(target, 0)
        }
    }

    private fun color(res: Int) = Palette.color(context, res)

    private fun dp(v: Int) = Ui.dp(context, v)
}
