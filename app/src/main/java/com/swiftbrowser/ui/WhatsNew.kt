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
        Item(R.drawable.ic_lite, "Lite mode in 5 levels", "Lite mode now has levels like the ad blocker: Off, Low, Medium, High and Extreme, for all sites and for each site, from Lite in the ≡ menu. The button shows the level. \"Show sites as designed\" is Lite mode Off.", version = 29),
        Item(R.drawable.ic_storage, "A cache that stays small", "Swift's cache stays under 100 MB (it could grow to 200 MB in a few hours). Choose 50, 100 or 200 MB in Settings → Storage & data.", version = 29),
        Item(R.drawable.ic_delete, "Clearing data", "Clearing data from all time no longer closes Swift, and it says how much space was freed.", version = 29),
        Item(R.drawable.ic_vpn, "VPN turns itself off", "With no internet, or when none of your locations answers, the VPN turns itself off and says why, instead of holding pages back.", version = 29),
        Item(R.drawable.ic_shield, "Ad blocker button", "The Ad blocker button in the ≡ menu lights up while ads are blocked on the site.", version = 29),
        Item(R.drawable.ic_speed, "Made for newer phones too", "Swift now comes in 64-bit for newer phones, so Android 14 and later no longer say it isn't compatible. Phones like the Moto E4 keep the 32-bit Swift, unchanged, and Swift's update check gets each phone its own.", version = 28),
        Item(R.drawable.ic_settings, "Between the phone's bars", "On Android 15 and later the address bar no longer sits under the clock and battery, and pages no longer run under the navigation bar.", version = 28),
        Item(R.drawable.ic_image, "Pictures on big sites", "On sharper screens, pictures on big sites like apple.com no longer stay empty: Swift gives pictures room in proportion to the screen.", version = 28),
        Item(R.drawable.ic_swap, "Links to other apps", "Phone numbers, messages, the Play Store and links to apps open their app again on Android 11 and later, instead of \"No app on your phone can open this link\".", version = 28),
        Item(R.drawable.ic_folder, "Downloads in your Download folder", "On Android 11 and later, downloads go to your Download folder (Download/Swift) as on older phones, where your gallery and other apps see them, and documents can be saved to Downloads again. Before, they stayed inside Swift.", version = 28),
        Item(R.drawable.ic_tab, "Quick settings tiles", "The New tab and Swift VPN tiles in your phone's quick settings open Swift again on Android 14 and later.", version = 28),
        Item(R.drawable.ic_download, "Download notifications", "On Android 13 and later Swift asks once, at your first download, to show its notifications: progress and \"Downloaded\". Downloads Android stops after 6 hours in the background go on when you open Swift.", version = 28),
        Item(R.drawable.ic_speed, "A start that says what it's doing", "When Swift has something to load, its start screen stays with a loader and says what's happening: the browser, the ad blocker, the VPN, then your page. No more blurry picture or blank flash.", version = 27),
        Item(R.drawable.ic_news, "News with pictures", "News stories on the home page now come with their pictures, as cards. With Data saver on mobile data they stay text only.", version = 27),
        Item(R.drawable.ic_tune, "Settings in plain words", "Every setting now says what it does for you, and each is where you'd look for it. Videos & music, Downloads and Files have their own topics, and nothing is folded away under Advanced.", version = 27),
        Item(R.drawable.ic_palette, "One calm look", "The same cards, bars, margins, icons and spacing on every screen, clearer cards and softer pictures in the dark look, rounded site icons, and small drawings on empty screens.", version = 27),
        Item(R.drawable.ic_upload, "Upload from Swift's Files", "A website's upload button now opens Swift's own Files. You can go back to your phone's file picker in Settings → Files.", version = 27),
        Item(R.drawable.ic_image, "How clear your home picture is", "Settings → Home & news → Background of the home page has a slider, from soft to the picture exactly as it is. The picture now runs behind the address bar too, and the text and buttons on it pick the colour that reads.", version = 27),
        Item(R.drawable.ic_speed, "Everything moves the same way", "Buttons, dialogs, sheets, lists and screens now respond with the same short, light motion. Dialogs and sheets open twice as fast, and the player, Downloads, Files and the document viewer have been tidied up to match.", version = 27),
        Item(R.drawable.ic_storage, "More room for the web cache", "Swift no longer limits its web cache to 20 MB, so sites you visit often load faster. It's still emptied by itself when your phone is almost full.", version = 27),
        Item(R.drawable.ic_pin, "Pin, mute and list your tabs", "Hold a tab to pin it: pinned tabs come first and stay open when you close all. A tab playing sound has a speaker to mute it, also in the ≡ menu. The button next to search shows your tabs as a compact list.", version = 26),
        Item(R.drawable.ic_tab, "A group's tabs by the address bar", "On a tab in a group, its other tabs sit in a strip by the address bar: tap one to switch, + opens another in the group (Settings → Browsing & tabs).", version = 26),
        Item(R.drawable.ic_movie, "Repeat a part, frame by frame", "In the player's ⋮ menu: repeat a part between two points (A-B), step through a paused video one frame at a time, and Night sound, which makes loud parts quieter and quiet voices louder.", version = 26),
        Item(R.drawable.ic_folder, "Files: sizes, two folders, network", "Folders show how big they are, and sorting by size sorts them too. Turned sideways, Files can show two folders side by side to copy and move between them. Files → Tools → Network folders opens a computer's or a home storage's shared folders over Wi-Fi.", version = 26),
        Item(R.drawable.ic_image, "Your home page, your way", "Settings → Home & news → Background: a soft colour gradient or a picture of your own behind the home page.", version = 26),
        Item(R.drawable.ic_font, "Swift's own font and icons", "Swift's text is in Google Sans Flex, Google's new font, and its icons in one rounded style: outlined, filled when something is on.", version = 26),
        Item(R.drawable.ic_palette, "Colours from your picture", "Look & screen → Colours → From your picture: Swift's colours made from your home page's picture or your phone's wallpaper.", version = 26),
        Item(R.drawable.ic_bar_colour, "Sites colour the address bar", "A site's own colour fills the address bar (you can turn it off in Look & screen).", version = 26),
        Item(R.drawable.ic_settings, "Rounded groups, big titles", "Settings, History, Bookmarks and Downloads show their rows in rounded groups, under a big title that moves up as you scroll.", version = 26),
        Item(R.drawable.ic_storage, "What fills your storage", "Files' storage bar shows how much pictures, videos, music and documents take, and Camera, Download, Pictures, Movies, Music and Documents have folder icons of their own.", version = 26),
        Item(R.drawable.ic_music_note, "Music in its cover's colours", "The music screen takes its colours from the album cover; the seek bar is thicker with a slim handle (wavy while music plays) and play turns into pause.", version = 26),
        Item(R.drawable.ic_tune, "A fresher look", "Bolder headings, buttons that change shape as you press them, menus and panels on springs, a new loading shape, a sepia page in reader view (Aa) and a home-screen widget in your colours.", version = 26),
        Item(R.drawable.ic_play, "Previous and next beside play", "The buttons beside play go to the previous and next video. Sound and subtitles, the ⋮ menu and the playlist open beside the video (under it when the phone is upright), so it stays in view.", version = 25),
        Item(R.drawable.ic_subtitles, "Subtitle and sound timing", "Move the subtitles earlier or later, the video's own ones too, and the sound, in steps of 0.1 s. Each video keeps its timing and the sound and subtitles you chose.", version = 25),
        Item(R.drawable.ic_tune, "More in the player", "An equalizer (⋮ → Equalizer), pinch to fill the screen, double taps that add up (+10, +20, +30 s), an Up next card before the next video, and subtitles you can drag up or down.", version = 25),
        Item(R.drawable.ic_folder, "Continue in Files", "Videos and music from your phone left half-way are in Files' new Continue row; the home page keeps what you watch and read on the web, with the shortcuts first again. Video pictures show how much you watched, a hold and a slide selects many files, and the search box stays at the top as you scroll.", version = 25),
        Item(R.drawable.ic_tab, "Tabs", "Double-tap the tab button to go back to the tab you were on before. Tab pictures stay inside their cards, and a tab without a picture shows its site's icon. Long pages get a handle at the right edge to move through them fast.", version = 25),
        Item(R.drawable.ic_palette, "Look and feel", "Messages come in your colours, switches slide, buttons spring and tick when pressed, holding an icon shows its name, and the phone's bars no longer flash as Settings or Files open in full screen.", version = 25),
        Item(R.drawable.ic_download, "Downloads", "A ring round the menu button shows how far downloads have come, finished videos show a picture, and website videos say how long is left.", version = 25),
        Item(R.drawable.ic_play, "A new player", "The times sit over a bar as wide as the screen, with back and on 10 seconds beside play. A tap in the middle shows or hides the controls at once, a double tap on a side jumps there, and a speed or sleep timer that is on shows under the title (hold it to turn it off). Sound and subtitles share one button.", version = 24),
        Item(R.drawable.ic_tab, "Tabs stay loaded in Settings", "Opening Settings, Files or the player no longer unloads your other tabs: Swift waits until you have left Swift for a minute. Right now (Settings → Speed & memory) says when tabs were last unloaded, and why.", version = 24),
        Item(R.drawable.ic_movie, "More website videos", "Videos sent as DASH, which many video sites use, are found on pages, play in Swift's player and download as one MP4 with sound, in your phone's language when there is a choice. A stream's download no longer jumps to half way once its sound is in.", version = 24),
        Item(R.drawable.ic_swap, "Home page order", "Continue now comes first, under the search box. Settings → Home & news → Order of the home page puts the parts in the order you like; nothing is left out.", version = 24),
        Item(R.drawable.ic_info, "Swift's licence", "Swift's own code is under the GNU GPL, version 3 or later, as About → Open-source licences now says.", version = 24),
        Item(R.drawable.ic_incognito, "Incognito leaves nothing behind", "Site settings changed in an incognito tab (desktop site, text size, ad blocking and more) now last only until you close your incognito tabs. Before, they were saved and went into backups.", version = 23),
        Item(R.drawable.ic_tab, "Keep a tab loaded", "Hold a tab in the tab grid and choose Keep loaded: Swift won't unload it to save memory. A lower number of tabs kept loaded now applies at once.", version = 23),
        Item(R.drawable.ic_shield, "Memory and security at a glance", "Settings → Speed & memory → Right now shows free memory, Swift's own and its loaded tabs. Privacy & security → Security status shows what is on, and what Swift has checked.", version = 23),
        Item(R.drawable.ic_play, "Videos on pages", "Website videos play in Swift's player again, and downloads Swift starts no longer close it. Videos on this page has a play button, a desktop tab's videos are fetched as desktop, and downloading the same address again first shows the one you have.", version = 23),
        Item(R.drawable.ic_subtitles, "Subtitles", "Choose the language subtitles show in first (Settings → Files & media), and the timing you set stays with each video. The player says when a video is too much for your phone's chip.", version = 23),
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
        Item(R.drawable.ic_volume, "Dolby and DTS sound", "Films with Dolby or DTS sound now play with sound in Swift's player. A 10-bit HEVC video, which your phone can't play, says so.", version = 21),
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
        Card(R.drawable.ic_lite, "Smooth on your phone", "If a site feels slow, raise Lite mode in the ≡ menu: Medium or higher scrolls the smoothest."),
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
                // Neutral: up to 55 of them, and the words are what to read.
                addView(Ui.iconBadge(a, item.icon, 40, tint = Ui.text(a), fill = Ui.color(a, R.color.surface_alt)), LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)).apply { marginEnd = Ui.dp(a, 16) })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(a, Ui.Type.SUBTITLE, item.title))
                    addView(Ui.text(a, Ui.Type.BODY_SECONDARY, item.text).apply { setPadding(0, Ui.dp(a, 2), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        dialog = Sheets.sheet(a, col, footer = Sheets.wideButton(a, "Got it") { dialog.dismiss() })
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
        val dotColours = IntArray(WELCOME.size) { Ui.color(a, R.color.outline) }
        repeat(WELCOME.size) { i ->
            dots.addView(View(a).apply { background = Ui.circle(dotColours[i]) }, LinearLayout.LayoutParams(Ui.dp(a, 8), Ui.dp(a, 8)).apply { setMargins(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0) })
        }
        val skip = TextView(a).apply {
            this.text = "Skip"
            Ui.style(this, Ui.Type.LABEL, Ui.textSecondary(a))
            gravity = Gravity.CENTER
            setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), 0)
            background = Ui.ripple(a, null, 24)
        }
        // Wide enough for its longest words, so it doesn't jump when they change.
        val next = MaterialButton(a).apply { minWidth = Ui.dp(a, 140) }
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
                Motion.pageOut(card) {
                    set()
                    Motion.pageIn(card, if (animate > 0) Motion.FORWARD else Motion.BACK)
                }
            }
            for (i in 0 until dots.childCount) {
                val colour = if (i == index) Ui.accent(a) else Ui.color(a, R.color.outline)
                val from = dotColours[i]
                if (from == colour) continue
                dotColours[i] = colour
                val dot = dots.getChildAt(i).background as android.graphics.drawable.GradientDrawable
                if (animate == 0) {
                    dot.setColor(colour)
                } else {
                    android.animation.ValueAnimator.ofArgb(from, colour).apply {
                        duration = Motion.SHORT
                        interpolator = Motion.standard
                        addUpdateListener { dot.setColor(it.animatedValue as Int) }
                        start()
                    }
                }
            }
            val last = index == WELCOME.size - 1
            next.text = if (last) "Start browsing" else "Next"
            when {
                animate == 0 -> skip.visibility = if (last) View.INVISIBLE else View.VISIBLE
                last -> if (skip.visibility == View.VISIBLE) Motion.fadeOut(skip, toDy = 0, gone = false)
                // Also when it was on its way out.
                skip.visibility != View.VISIBLE || skip.alpha < 1f -> Motion.fadeIn(skip, fromDy = 0, duration = Motion.SHORT)
            }
        }

        var back: androidx.activity.OnBackPressedCallback? = null

        fun finish() {
            SwiftApp.prefs.welcomeDone = true
            back?.remove()
            Motion.fadeOut(overlay, toDy = 0, duration = Motion.NORMAL) { root.removeView(overlay) }
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
