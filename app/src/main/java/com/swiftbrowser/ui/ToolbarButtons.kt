package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Profiles
import kotlin.math.hypot

/**
 * The buttons next to the address (2.2): which ones, and on which side of it (Settings → Look &
 * screen → Address bar buttons). The menu button always stays; the video download button and the
 * VPN mark still come and go by themselves.
 */
object ToolbarButtons {
    class Button(val key: String, val icon: Int, val label: String)

    val all = listOf(
        Button("profile", R.drawable.ic_person, "Profile"),
        Button("home", R.drawable.ic_home, "Home"),
        Button("tabs", R.drawable.ic_tab, "Tabs"),
        Button("menu", R.drawable.ic_menu, "Menu"),
        Button("new_tab", R.drawable.ic_add, "New tab"),
        Button("reload", R.drawable.ic_refresh, "Reload"),
        Button("share", R.drawable.ic_share, "Share"),
        Button("bookmark", R.drawable.ic_star_border, "Bookmark"),
        Button("vpn", R.drawable.ic_vpn, "VPN"),
        Button("scan", R.drawable.ic_qr, "Scan QR"),
    )

    /** More would leave too little room for the address on a small phone. */
    const val MAX = 4

    fun button(key: String) = all.first { it.key == key }

    class Layout(val left: MutableList<String>, val right: MutableList<String>) {
        val size get() = left.size + right.size

        fun has(key: String) = key in left || key in right

        fun remove(key: String) {
            left.remove(key)
            right.remove(key)
        }

        fun encode() = left.joinToString(",") + "|" + right.joinToString(",")
    }

    fun defaultLayout() = Layout(mutableListOf("profile", "home"), mutableListOf("tabs", "menu"))

    /** As arranged; from before 2.2, the profile and (if it was on) the home button, then tabs and menu. */
    fun layout(): Layout {
        val keys = all.map { it.key }
        val saved = SwiftApp.prefs.toolbarLayout
        val l = if (saved != null && '|' in saved) {
            val (left, right) = saved.split('|', limit = 2)
            Layout(
                left.split(',').filter { it in keys }.distinct().toMutableList(),
                right.split(',').filter { it in keys }.distinct().toMutableList(),
            )
        } else {
            Layout(listOfNotNull("profile", "home".takeIf { SwiftApp.prefs.showHomeButton }).toMutableList(), mutableListOf("tabs", "menu"))
        }
        l.right.removeAll { it in l.left }
        if (!l.has("menu")) l.right.add("menu")
        while (l.size > MAX) {
            val side = if (l.left.any { it != "menu" }) l.left else l.right
            side.removeAt(side.indexOfFirst { it != "menu" })
        }
        return l
    }

    fun save(l: Layout) {
        SwiftApp.prefs.toolbarLayout = l.encode()
    }
}

/**
 * Arranging the address bar's buttons: a picture of the bar with its buttons on both sides of the
 * address and, under it, the ones not in it. Drag a button to a side of the address or down to the
 * others; tapping one offers the same moves.
 */
@SuppressLint("ViewConstructor")
class ToolbarEditor(private val a: Activity) : FrameLayout(a) {
    private var layout = ToolbarButtons.layout()
    private val note = Ui.text(a, Ui.Type.BODY_SECONDARY, "")
    private val bar = LinearLayout(a)
    private val address = TextView(a)
    private val trayBox = LinearLayout(a)
    private val ghost = DragGhost(this)
    private val views = LinkedHashMap<String, View>()
    private val slop = ViewConfiguration.get(a).scaledTouchSlop
    private var dragKey: String? = null
    private var pressedKey: String? = null
    private var downX = 0f
    private var downY = 0f

    init {
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        column.addView(note)
        bar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color(R.color.toolbar_bg))
                setStroke(dp(1), color(R.color.divider))
            }
        }
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(14) })
        address.apply {
            text = "Address"
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            setTextColor(color(R.color.text_secondary))
            background = Ui.shape(a, color(R.color.surface_alt), 21)
        }
        column.addView(Ui.text(a, Ui.Type.SECTION, "Not in the address bar").apply { setPadding(0, dp(20), 0, dp(6)) })
        trayBox.orientation = LinearLayout.VERTICAL
        trayBox.minimumHeight = dp(88)
        trayBox.background = Ui.shape(a, color(R.color.surface_alt), 16)
        column.addView(trayBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        say(null)
        render()
    }

    fun reset() {
        layout = ToolbarButtons.defaultLayout()
        ToolbarButtons.save(layout)
        say(null)
        render(animate = true)
    }

    /** The usual hint, or a short warning in its place. */
    private fun say(warning: String?) {
        note.text = warning ?: "Hold a button and drag it to either side of the address, or down to remove it. Up to ${ToolbarButtons.MAX} fit; the menu button always stays."
        note.setTextColor(if (warning != null) Ui.danger(a) else color(R.color.text_secondary))
    }

    /** [animate]: the buttons glide from where they were to their new places (2.3). */
    private fun render(animate: Boolean = false) {
        val before = if (animate && isAttachedToWindow) {
            views.mapValues { (_, v) -> IntArray(2).also { v.getLocationOnScreen(it) } }
        } else {
            emptyMap()
        }
        views.clear()
        bar.removeAllViews()
        for (key in layout.left) bar.addView(barButton(key), LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(address, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        for (key in layout.right) bar.addView(barButton(key), LinearLayout.LayoutParams(dp(44), dp(44)))
        trayBox.removeAllViews()
        val rest = ToolbarButtons.all.filter { !layout.has(it.key) }
        if (rest.isEmpty()) {
            trayBox.addView(Ui.text(a, Ui.Type.BODY_SECONDARY, "All of them are in the bar").apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(30), dp(12), dp(30))
            })
        }
        for (row in rest.chunked(4)) {
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (b in row) line.addView(chip(b.key), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            repeat(4 - row.size) { line.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
            trayBox.addView(line)
        }
        if (before.isNotEmpty()) glide(before)
    }

    /** Just before the next frame: each button starts at its old place and glides to the new one. */
    private fun glide(before: Map<String, IntArray>) {
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                val now = IntArray(2)
                for ((key, v) in views) {
                    if (key == dragKey) continue
                    val old = before[key]
                    if (old == null) {
                        Motion.fadeIn(v, fromDy = 0, duration = Motion.SHORT)
                        continue
                    }
                    v.getLocationOnScreen(now)
                    val dx = (old[0] - now[0]).toFloat()
                    val dy = (old[1] - now[1]).toFloat()
                    if (dx == 0f && dy == 0f) continue
                    v.translationX = dx
                    v.translationY = dy
                    v.animate().translationX(0f).translationY(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.standard).start()
                }
                return true
            }
        })
    }

    private fun barButton(key: String): View {
        val b = ToolbarButtons.button(key)
        val v: View = if (key == "profile") {
            // The profile's coloured letter, as in the bar.
            FrameLayout(a).apply {
                val p = Profiles.current()
                addView(TextView(a).apply {
                    text = Profiles.initial(p)
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                    background = ContextCompat.getDrawable(a, R.drawable.bg_circle)
                    backgroundTintList = ColorStateList.valueOf(p.color)
                }, LayoutParams(dp(30), dp(30), Gravity.CENTER))
            }
        } else {
            ImageView(a).apply {
                setImageResource(b.icon)
                imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
                scaleType = ImageView.ScaleType.CENTER
            }
        }
        v.contentDescription = b.label
        v.isClickable = true
        v.background = Ui.ripple(a, null, 22)
        v.visibility = if (key == dragKey) View.INVISIBLE else View.VISIBLE
        v.setOnClickListener { barSheet(key) }
        views[key] = v
        return v
    }

    private fun chip(key: String): View {
        val b = ToolbarButtons.button(key)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.color.toolbar_bg))
        }
        val glyph = ContextCompat.getDrawable(a, b.icon)!!.mutate().apply { setTint(color(R.color.text_primary)) }
        val icon = LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerSize(0, dp(44), dp(44))
            setLayerSize(1, dp(22), dp(22))
            setLayerGravity(1, Gravity.CENTER)
        }
        val v = TextView(a).apply {
            text = b.label
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(color(R.color.text_primary))
            setPadding(dp(2), dp(10), dp(2), dp(8))
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, icon, null, null)
            background = Ui.ripple(a, null, 12)
            contentDescription = "Add ${b.label}"
            visibility = if (key == dragKey) View.INVISIBLE else View.VISIBLE
            setOnClickListener { add(key) }
        }
        views[key] = v
        return v
    }

    /** A tap on one of the others: into the bar, at the right side's end (before the menu button). */
    private fun add(key: String) {
        if (layout.size >= ToolbarButtons.MAX) {
            say("Up to ${ToolbarButtons.MAX} buttons fit. Drag one down here first.")
            return
        }
        val r = layout.right
        r.add(if (r.lastOrNull() == "menu") r.size - 1 else r.size, key)
        changed()
    }

    private fun barSheet(key: String) {
        val b = ToolbarButtons.button(key)
        val side = if (key in layout.left) layout.left else layout.right
        val other = if (side === layout.left) layout.right else layout.left
        val at = side.indexOf(key)
        val actions = ArrayList<Sheets.Action>()
        if (at > 0) actions += Sheets.Action(R.drawable.ic_back, "Move left", primary = true) {
            side.removeAt(at)
            side.add(at - 1, key)
            changed()
        }
        if (at < side.size - 1) actions += Sheets.Action(R.drawable.ic_forward, "Move right", primary = true) {
            side.removeAt(at)
            side.add(at + 1, key)
            changed()
        }
        val toLeft = side === layout.right
        actions += Sheets.Action(if (toLeft) R.drawable.ic_back else R.drawable.ic_forward, if (toLeft) "To the left of the address" else "To the right of the address") {
            side.remove(key)
            if (toLeft) other.add(key) else other.add(0, key)
            changed()
        }
        if (key != "menu") actions += Sheets.Action(R.drawable.ic_close, "Remove from the bar") {
            layout.remove(key)
            changed()
        }
        Sheets.actions(a, b.label, null, actions, iconRes = b.icon)
    }

    private fun changed() {
        ToolbarButtons.save(layout)
        say(null)
        render(animate = true)
    }

    // ------------------------------------------------------------------ dragging

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dragKey != null) {
            dragTouch(ev)
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                pressedKey = views.entries.firstOrNull { within(it.value, ev.rawX, ev.rawY) }?.key
                // A press on a button may become a drag: the sheet around doesn't scroll or close meanwhile.
                if (pressedKey != null) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val key = pressedKey
                if (key != null && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                    startDrag(key)
                    dragTouch(ev)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dragKey == null) return super.onTouchEvent(ev)
        dragTouch(ev)
        return true
    }

    private fun startDrag(key: String) {
        val v = views[key] ?: return
        dragKey = key
        v.isPressed = false
        ghost.start(v, downX, downY)
        v.visibility = View.INVISIBLE
        // The dialog keeps its place while a button is dragged.
        parent?.requestDisallowInterceptTouchEvent(true)
        Haptics.hold(v)
    }

    private fun dragTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                ghost.move(ev.rawX, ev.rawY)
                retarget(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop()
        }
    }

    private fun retarget(x: Float, y: Float) {
        val key = dragKey ?: return
        if (within(bar, x, y)) {
            if (!layout.has(key) && layout.size >= ToolbarButtons.MAX) {
                say("Up to ${ToolbarButtons.MAX} buttons fit. Drag one down here first.")
                return
            }
            val at = IntArray(2)
            address.getLocationOnScreen(at)
            val toLeft = x < at[0] + address.width / 2f
            val side = if (toLeft) layout.left else layout.right
            // The place among the side's other buttons, by their middles.
            val index = side.filter { it != key }.count { k ->
                val v = views[k] ?: return@count false
                val p = IntArray(2)
                v.getLocationOnScreen(p)
                p[0] + v.width / 2f < x
            }
            val others = side.filter { it != key }
            if (side.indexOf(key) == index && side.size == others.size + 1) return
            layout.remove(key)
            side.add(index.coerceAtMost(side.size), key)
            say(null)
            render(animate = true)
        } else if (within(trayBox, x, y)) {
            if (key == "menu") {
                say("The menu button always stays in the bar.")
                return
            }
            if (layout.has(key)) {
                layout.remove(key)
                say(null)
                render(animate = true)
            }
        }
    }

    private fun drop() {
        val key = dragKey ?: return
        dragKey = null
        ToolbarButtons.save(layout)
        render()
        val target = views[key]
        target?.visibility = View.INVISIBLE
        ghost.drop(target) { target?.visibility = View.VISIBLE }
        Haptics.tick(this)
    }

    private fun within(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return x >= at[0] && x < at[0] + v.width && y >= at[1] && y < at[1] + v.height
    }

    private fun color(res: Int) = Palette.color(a, res)

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
