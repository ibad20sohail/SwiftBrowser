package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import kotlin.math.abs

/**
 * What Swift says when it starts (2.0): on the very first start a few welcome cards, and after an
 * update, once, what is new in this version. Nothing otherwise.
 */
object WhatsNew {
    private class Item(val icon: Int, val title: String, val text: String)

    private val NEW = listOf(
        Item(R.drawable.ic_palette, "A fresh new look", "Every screen redone, with your choice of colour and a theme that turns dark at night."),
        Item(R.drawable.ic_news, "News on the home page", "Headlines from your country. Pick topics or add your own news sites."),
        Item(R.drawable.ic_more, "A tidier menu", "Choose the buttons you use most; the others are under More."),
        Item(R.drawable.ic_delete, "Undo instead of \"Are you sure?\"", "Swipe away history, bookmarks and downloads, and undo if you change your mind."),
        Item(R.drawable.ic_globe, "A smarter address bar", "It shows the site's name, warns about sites that aren't secure, and finds your open tabs."),
        Item(R.drawable.ic_headphones, "Better for music and videos", "Lock screen controls, headset buttons, a pause when headphones come out, and a sleep timer that fades out."),
        Item(R.drawable.ic_download, "Downloads that don't give up", "They go on after a restart, and when the connection comes back."),
        Item(R.drawable.ic_reader, "Read aloud, improved", "Change the speed, skip paragraphs, and it keeps reading with the screen off."),
    )

    private class Card(val icon: Int, val title: String, val text: String)

    private val WELCOME = listOf(
        Card(R.drawable.ic_shield, "Ads blocked on every site", "Swift's ad blocker stops ads, pop-ups and trackers. Choose how strict it is from ⋮ → Ad blocker."),
        Card(R.drawable.ic_headphones, "Music and videos keep playing", "They go on when you switch tabs, open another app or turn the screen off. Set a sleep timer from the notification."),
        Card(R.drawable.ic_lite, "Smooth on your phone", "If a site feels slow, turn on Lite mode in the ⋮ menu for the smoothest scrolling."),
    )

    /** At start: the welcome on a new install, what's new after an update. */
    fun showOnStart(a: MainActivity) {
        val prefs = SwiftApp.prefs
        val seen = prefs.seenVersion
        prefs.seenVersion = BuildConfig.VERSION_CODE
        val info = try {
            a.packageManager.getPackageInfo(a.packageName, 0)
        } catch (_: Exception) {
            null
        }
        val newInstall = seen == 0 && info != null && info.firstInstallTime == info.lastUpdateTime
        when {
            newInstall && !prefs.welcomeDone -> welcome(a)
            // Never recorded (an update from 1.8 or older), or an older version than this one.
            !newInstall && seen < BuildConfig.VERSION_CODE -> whatsNew(a)
        }
    }

    /** What's new in this version, as a sheet of short points. */
    fun whatsNew(a: MainActivity) {
        val col = Sheets.column(a)
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 12))
            addView(android.widget.ImageView(a).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)))
            addView(Ui.text(a, Ui.Type.HEADLINE, "What's new in Swift ${BuildConfig.VERSION_NAME}").apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(a, 12), 0, 0)
            })
        })
        for (item in NEW) {
            col.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(Ui.dp(a, 24), Ui.dp(a, 10), Ui.dp(a, 24), Ui.dp(a, 10))
                addView(Ui.iconBadge(a, item.icon, 40), LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)).apply { marginEnd = Ui.dp(a, 16) })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(a, Ui.Type.SUBTITLE, item.title))
                    addView(Ui.text(a, Ui.Type.BODY_SECONDARY, item.text).apply { setPadding(0, Ui.dp(a, 2), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        col.addView(MaterialButton(a).apply {
            text = "Got it"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)).apply {
            setMargins(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8))
        })
        dialog = Sheets.sheet(a, col)
        dialog.showImmersive()
    }

    /** The first start: three short cards over the browser, swiped or stepped through. */
    @SuppressLint("ClickableViewAccessibility")
    fun welcome(a: MainActivity) {
        val root = a.findViewById<FrameLayout>(R.id.root) ?: return
        var index = 0
        val overlay = FrameLayout(a).apply {
            setBackgroundColor(Ui.color(a, R.color.page_bg))
            elevation = Ui.dpf(a, 30f)
            isClickable = true
            isFocusable = true
        }
        val box = MaxWidthLayout(a).apply { maxWidthDp = 440 }
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(a, 32), Ui.dp(a, 24), Ui.dp(a, 32), Ui.dp(a, 32))
        }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val icon = Ui.iconBadge(a, WELCOME[0].icon, 140, tint = Ui.accent(a))
        val title = Ui.text(a, Ui.Type.HEADLINE, "").apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(a, 40), 0, 0)
        }
        val text = Ui.text(a, Ui.Type.BODY, "", Ui.textSecondary(a)).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(a, 12), 0, 0)
        }
        card.addView(icon)
        card.addView(title)
        card.addView(text)
        val dots = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(WELCOME.size) {
            dots.addView(View(a), LinearLayout.LayoutParams(Ui.dp(a, 8), Ui.dp(a, 8)).apply { setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0) })
        }
        val skip = TextView(a).apply {
            this.text = "Skip"
            Ui.style(this, Ui.Type.LABEL, Ui.textSecondary(a))
            gravity = Gravity.CENTER
            setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), 0)
            background = Ui.ripple(a, null, 24)
        }
        val next = MaterialButton(a)
        val buttons = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(skip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 52)))
            addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
            addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 52)))
        }
        column.addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(card)
        column.addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        column.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(a, 24) })
        column.addView(buttons)
        box.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun bind(animate: Int) {
            val c = WELCOME[index]
            val set = {
                icon.setImageDrawable(Ui.icon(a, c.icon, Ui.accent(a)))
                title.text = c.title
                text.text = c.text
                card.contentDescription = "${c.title}. ${c.text}"
            }
            if (animate == 0) {
                set()
            } else {
                card.animate().alpha(0f).translationX(-animate * Ui.dpf(a, 40f)).setDuration(Motion.SHORT).setInterpolator(Motion.exit).withEndAction {
                    set()
                    card.translationX = animate * Ui.dpf(a, 40f)
                    card.animate().alpha(1f).translationX(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.enter).start()
                }.start()
            }
            for (i in 0 until dots.childCount) {
                dots.getChildAt(i).background = Ui.circle(if (i == index) Ui.accent(a) else Ui.color(a, R.color.outline))
            }
            next.text = if (index == WELCOME.size - 1) "Start browsing" else "Next"
            skip.visibility = if (index == WELCOME.size - 1) View.INVISIBLE else View.VISIBLE
        }

        var back: androidx.activity.OnBackPressedCallback? = null

        fun finish() {
            SwiftApp.prefs.welcomeDone = true
            back?.remove()
            overlay.animate().alpha(0f).setDuration(Motion.NORMAL).withEndAction { root.removeView(overlay) }.start()
        }

        fun go(step: Int) {
            val target = index + step
            if (target >= WELCOME.size) {
                finish()
                return
            }
            if (target < 0) return
            index = target
            bind(step)
        }

        // Back steps back through the cards, then closes them (rather than leaving Swift).
        back = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (index > 0) go(-1) else finish()
            }
        }.also { a.onBackPressedDispatcher.addCallback(a, it) }
        next.setOnClickListener { go(1) }
        skip.setOnClickListener { finish() }
        // A sideways swipe moves between the cards too.
        val swipes = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 400) return false
                go(if (vx < 0) 1 else -1)
                return true
            }
        })
        overlay.setOnTouchListener { _, ev -> swipes.onTouchEvent(ev) }
        bind(0)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Motion.fadeIn(card, fromDy = 24, duration = Motion.LONG)
    }
}
