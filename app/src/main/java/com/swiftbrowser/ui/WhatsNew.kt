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
    /** A point of what's new; [version] is the versionCode it came in (2.9.1: an update shows only what is newer than the version before). */
    private class Item(val icon: Int, val title: String, val text: String, val version: Int = 19)

    private val NEW = listOf(
        Item(R.drawable.ic_incognito, "Incognito leaves nothing behind", "Site settings changed in an incognito tab (desktop site, text size, ad blocking and more) now last only until you close your incognito tabs. Before, they were saved and went into backups.", version = 23),
        Item(R.drawable.ic_tab, "Keep a tab loaded", "Hold a tab in the tab grid and choose Keep loaded: Swift won't unload it to save memory. A lower number of tabs kept loaded now applies at once.", version = 23),
        Item(R.drawable.ic_shield, "Memory and security at a glance", "Settings → Speed & memory → Right now shows free memory, Swift's own and its loaded tabs. Privacy & security → Security status shows what is on, and what Swift has checked.", version = 23),
        Item(R.drawable.ic_play, "Videos on pages", "Website videos play in Swift's player again, and downloads Swift starts no longer close it. Videos on this page has a play button, a desktop tab's videos are fetched as desktop, and downloading the same address again first shows the one you have.", version = 23),
        Item(R.drawable.ic_subtitles, "Subtitles", "Choose the language subtitles show in first (Settings → Files & media), and the timing you set stays with each video. The player says when a video is too much for this phone's chip.", version = 23),
        Item(R.drawable.ic_download, "Backups say what they did", "Restoring a backup says exactly what it added. A backup made by a newer Swift is refused instead of half read.", version = 23),
        Item(R.drawable.ic_palette, "A softer look", "A softer accent in the dark look, a lighter home page and new Continue cards.", version = 23),
        Item(R.drawable.ic_tab, "Lighter pop-ups and tabs", "Links that open a new tab load once, not twice: the hidden copy that kept using memory is gone, and sign-in pop-ups work again. A tab you used for a long time stays loaded when you switch away.", version = 22),
        Item(R.drawable.ic_shield, "Ads blocked from the first page", "After a restart, pages wait for the ad blocker (or tap Load now). If a page loaded before it was ready, Swift offers to reload it.", version = 22),
        Item(R.drawable.ic_lock, "Safer browsing", "Pages can't fake Google in the address bar, long addresses show their real ending, QR codes show where they lead first, and a page can't trap you with endless messages. Incognito and saved passwords stay out of screenshots.", version = 22),
        Item(R.drawable.ic_folder, "Your files are safer", "Removing a download from the list keeps its files, Compress can't empty the file it packs, a copy cut short never looks complete, and the Private folder's PIN stays in charge.", version = 22),
        Item(R.drawable.ic_documents, "Documents", "Password-protected PDFs open again, scanned PDFs show their pages, and Word files that are really another format still open.", version = 22),
        Item(R.drawable.ic_download, "Downloads and the player", "Videos whose sound comes separately download with sound, downloads open in Swift's player, torrents stay quieter on Wi-Fi, and Urdu or Arabic subtitles read correctly.", version = 22),
        Item(R.drawable.ic_vpn, "VPN, backups and settings", "If the VPN was on, it comes back by itself after a restart, with pages waiting. Backups leave out the Private folder's secrets, settings search finds what you type, and About lists the open-source parts.", version = 22),
        Item(R.drawable.ic_person, "Your Google account on each profile", "The profiles list, the profile button and the home page show the Google account signed in to each profile, with its name and photo. Swift sees it on Google's own pages, never in incognito.", version = 22),
        Item(R.drawable.ic_torrent, "Torrents", "Tap a magnet link or open a .torrent file, choose its files (junk is left unticked) and it downloads in Downloads. Torrents pause while Swift's VPN is on and stop sharing once they are done.", version = 21),
        Item(R.drawable.ic_volume, "Dolby and DTS sound", "Films with Dolby or DTS sound now play with sound in Swift's player. A 10-bit HEVC video, which this phone can't play, says so.", version = 21),
        Item(R.drawable.ic_play, "Player", "Swipe up past the loudest for a volume boost up to 200%. Subtitles in white or yellow, with or without a box, also from a torrent's Subs folder. Screenshots from the ⋮ menu.", version = 21),
        Item(R.drawable.ic_download, "Downloads", "Filters for videos, torrents and other files, Pause all in the notification, and downloads keep going with the screen off.", version = 21),
        Item(R.drawable.ic_search, "More room in Files", "The search box and the folder path now scroll away with the list. Scroll back to the top to search.", version = 20),
        Item(R.drawable.ic_image, "Cleaner picture viewer", "The buttons under a picture are icons only. Hold one to see its name.", version = 20),
        Item(R.drawable.ic_play, "Media player", "Videos and music from Files play in Swift's own player: swipe for brightness, volume and seeking, speed, subtitles, going on where you stopped, and music in the background."),
        Item(R.drawable.ic_image, "Picture viewer", "Crop and turn pictures, see their details, slideshows, moving GIFs and set as wallpaper. Swift can also open pictures, videos and music from other apps."),
        Item(R.drawable.ic_folder, "Files tools", "A recycle bin (30 days), Free up space, a Private folder behind your screen lock, and Wi-Fi transfer to your computer. Documents are in Files now."),
        Item(R.drawable.ic_tune, "Files settings", "Settings → Files & media, also from Files' ⋮ menu: how Files opens, the recycle bin, Private and the player."),
        Item(R.drawable.ic_check, "Smaller fixes", "Panels scroll back up without closing, deleted files leave the recent lists, and two new choices: Swift player for website videos and sorting downloads into folders."),
    )

    private class Card(val icon: Int, val title: String, val text: String)

    private val WELCOME = listOf(
        Card(R.drawable.ic_shield, "Ads blocked on every site", "Swift's ad blocker stops ads, pop-ups and trackers. Choose how strict it is from ≡ → Ad blocking."),
        Card(R.drawable.ic_headphones, "Music and videos keep playing", "They go on when you switch tabs, open another app or turn the screen off. Set a sleep timer from the notification."),
        Card(R.drawable.ic_lite, "Smooth on your phone", "If a site feels slow, turn on Lite mode in the ≡ menu for the smoothest scrolling."),
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
            !newInstall && seen < BuildConfig.VERSION_CODE -> whatsNew(a, seen)
        }
    }

    /** What's new in this version, as a sheet of short points. */
    fun whatsNew(a: MainActivity, seen: Int = 0) {
        val items = NEW.filter { it.version > seen }.ifEmpty { NEW }
        val col = Sheets.column(a)
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 12))
            addView(android.widget.ImageView(a).apply { setImageDrawable(Ui.logo(a)) }, LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)))
            addView(Ui.text(a, Ui.Type.HEADLINE, "What's new in Swift ${BuildConfig.VERSION_NAME}").apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(a, 12), 0, 0)
            })
        })
        for (item in items) {
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
        // 3.0: it scrolls when taller than the screen (a turned phone cut off Next and Skip); when it
        // fits, it fills the screen as before, the card in the middle.
        val scroller = android.widget.ScrollView(a).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(scroller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        // The scrolling box takes the touches on the card now: it hears the sideways swipes too, and
        // still scrolls.
        scroller.setOnTouchListener { _, ev ->
            swipes.onTouchEvent(ev)
            false
        }
        bind(0)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Motion.fadeIn(card, fromDy = 24, duration = Motion.LONG)
    }
}
