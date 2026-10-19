package com.swiftbrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.ProfileRow
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.download.Hls
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.media.MediaItem
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bottom sheets opened from the toolbar. */
object Sheets {

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun Context.color(res: Int) = ContextCompat.getColor(this, res)

    private fun sheet(ctx: Context, content: View, expand: Boolean = true): BottomSheetDialog {
        val dialog = BottomSheetDialog(ctx)
        val scroll = if (content is RecyclerView || content is ScrollView) content else ScrollView(ctx).apply { addView(content) }
        dialog.setContentView(scroll)
        if (expand) {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        return dialog
    }

    private fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ctx.dp(8), 0, ctx.dp(12))
    }

    private fun header(ctx: Context, text: String, sub: String? = null): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(8))
        addView(TextView(ctx).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ctx.color(R.color.text_primary))
        })
        if (!sub.isNullOrBlank()) {
            addView(TextView(ctx).apply {
                this.text = sub
                textSize = 13f
                setTextColor(ctx.color(R.color.text_secondary))
                setPadding(0, ctx.dp(2), 0, 0)
            })
        }
    }

    private fun label(ctx: Context, text: String, top: Int = 12): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ctx.color(R.color.accent))
        setPadding(ctx.dp(20), ctx.dp(top), ctx.dp(20), ctx.dp(4))
    }

    private fun note(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(ctx.color(R.color.text_secondary))
        setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), ctx.dp(4))
    }

    private fun row(ctx: Context, icon: Int, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(48))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
        textSize = 15f
        setTextColor(ctx.color(R.color.text_primary))
        compoundDrawablePadding = ctx.dp(20)
        val d = ContextCompat.getDrawable(ctx, icon)?.mutate()?.also { it.setTint(ctx.color(R.color.text_secondary)) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
        setBackgroundResource(RowHolder.selectableBackground(ctx))
        setOnClickListener { onClick() }
    }

    private fun switchRow(ctx: Context, icon: Int, text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(0, 0, ctx.dp(16), 0)
            setBackgroundResource(RowHolder.selectableBackground(ctx))
        }
        val sw = SwitchCompat(ctx).apply { isChecked = checked }
        val tv = row(ctx, icon, text) { sw.toggle() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ctx.dp(48), 1f)
            background = null
        }
        box.addView(tv)
        box.addView(sw)
        sw.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        return box
    }

    private fun chips(ctx: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): ChipGroup =
        ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            val bg = ColorStateList(states, intArrayOf(ctx.color(R.color.accent), ctx.color(R.color.surface_alt)))
            val fg = ColorStateList(states, intArrayOf(ctx.color(R.color.on_accent), ctx.color(R.color.text_primary)))
            labels.forEachIndexed { i, text ->
                addView(Chip(ctx).apply {
                    id = View.generateViewId()
                    this.text = text
                    isCheckable = true
                    isCheckedIconVisible = false
                    chipBackgroundColor = bg
                    setTextColor(fg)
                    chipStrokeWidth = 0f
                    isChecked = i == selected
                    setOnClickListener { if (isChecked) onSelect(i) }
                })
            }
        }

    // ------------------------------------------------------------------ menu

    fun menu(a: MainActivity) {
        val tab = TabManager.current ?: return
        val col = column(a)
        lateinit var dialog: BottomSheetDialog

        val icons = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(a.dp(8), 0, a.dp(8), a.dp(4))
        }
        fun iconButton(res: Int, desc: String, enabled: Boolean, tint: Int = R.color.text_secondary, onClick: () -> Unit) {
            icons.addView(ImageButton(a).apply {
                layoutParams = LinearLayout.LayoutParams(0, a.dp(52), 1f)
                setImageResource(res)
                imageTintList = ColorStateList.valueOf(a.color(tint))
                setBackgroundResource(RowHolder.selectableBackground(a))
                contentDescription = desc
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.35f
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            })
        }
        iconButton(R.drawable.ic_back, "Back", tab.canGoBack) { TabManager.goBack(tab) }
        iconButton(R.drawable.ic_forward, "Forward", tab.canGoForward) { TabManager.goForward(tab) }
        if (tab.loading) iconButton(R.drawable.ic_close, "Stop", true) { tab.session?.stop() }
        else iconButton(R.drawable.ic_refresh, "Reload", !tab.isBlank) { TabManager.reload(tab) }
        val pid = tab.profileId
        val url = tab.url
        val bookmarked = UrlUtils.isHttp(url) && SwiftApp.db.isBookmarked(pid, url)
        iconButton(
            if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border,
            if (bookmarked) "Remove bookmark" else "Add bookmark",
            UrlUtils.isHttp(url), if (bookmarked) R.color.accent else R.color.text_secondary,
        ) {
            if (bookmarked) {
                TabManager.io.execute { SwiftApp.db.removeBookmark(pid, url) }
                a.snack("Bookmark removed")
            } else {
                val title = tab.title
                TabManager.io.execute { SwiftApp.db.addBookmark(pid, url, title) }
                a.snack("Bookmarked")
            }
        }
        iconButton(R.drawable.ic_share, "Share", UrlUtils.isHttp(url)) { a.share(url) }
        col.addView(icons)

        col.addView(row(a, R.drawable.ic_add, "New tab") { dialog.dismiss(); TabManager.newTab() })
        col.addView(row(a, R.drawable.ic_bookmarks, "Bookmarks") { dialog.dismiss(); a.openList(BookmarksActivity::class.java) })
        col.addView(row(a, R.drawable.ic_history, "History") { dialog.dismiss(); a.openList(HistoryActivity::class.java) })
        col.addView(row(a, R.drawable.ic_download, "Downloads") { dialog.dismiss(); a.openDownloads() })
        col.addView(row(a, R.drawable.ic_find, "Find in page") { dialog.dismiss(); a.openFind() })
        col.addView(row(a, R.drawable.ic_speed, "Playback speed (${a.formatSpeed(tab.speed)})") {
            dialog.dismiss()
            speed(a, tab)
        })
        col.addView(switchRow(a, R.drawable.ic_desktop, "Desktop site", tab.desktop) { on ->
            dialog.dismiss()
            TabManager.setDesktop(tab, on)
        })
        col.addView(switchRow(a, R.drawable.ic_dark, "Dark mode", SwiftApp.prefs.darkMode) { on ->
            dialog.dismiss()
            setDarkMode(on)
        })
        col.addView(row(a, R.drawable.ic_settings, "Settings") { dialog.dismiss(); a.openList(SettingsActivity::class.java) })
        dialog = sheet(a, col)
        dialog.show()
    }

    fun setDarkMode(on: Boolean) {
        SwiftApp.prefs.darkMode = on
        Engine.setDark(on)
        SwiftApp.applyNightMode(on)
    }

    // ------------------------------------------------------------------ ad blocking

    fun shield(a: MainActivity) {
        val tab = TabManager.current ?: return
        val site = UrlUtils.site(tab.url)
        val col = column(a)
        lateinit var dialog: BottomSheetDialog

        val blocked = tab.badge?.takeIf { it.isNotBlank() && it != "0" }
        val sub = when {
            AdBlock.extension == null || !AdBlock.ready -> "uBlock Origin is getting ready…"
            blocked != null -> "uBlock Origin ${AdBlock.uboVersion} • $blocked blocked on this page"
            else -> "uBlock Origin ${AdBlock.uboVersion}"
        }
        col.addView(header(a, "Ad blocking", sub))

        val levels = AdLevel.entries
        col.addView(label(a, "All sites"))
        val globalDesc = note(a, AdBlock.global.summary)
        col.addView(chips(a, levels.map { it.label }, levels.indexOf(AdBlock.global)) { i ->
            AdBlock.setGlobal(levels[i])
            globalDesc.text = levels[i].summary
            a.updateShield()
        })
        col.addView(globalDesc)

        if (site != null && UrlUtils.isHttp(tab.url)) {
            col.addView(label(a, "This site: $site", top = 16))
            val override = AdBlock.overrideFor(site)
            val options = listOf("Same as all sites") + levels.map { it.label }
            val siteDesc = note(a, override?.summary ?: "Uses the level chosen for all sites.")
            col.addView(chips(a, options, if (override == null) 0 else levels.indexOf(override) + 1) { i ->
                val level = if (i == 0) null else levels[i - 1]
                AdBlock.setOverride(site, level)
                siteDesc.text = level?.summary ?: "Uses the level chosen for all sites."
                a.updateShield()
            })
            col.addView(siteDesc)
            col.addView(note(a, "Changes apply when the page reloads. Filter lists always follow the all-sites level."))
        }

        val buttons = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16), a.dp(8), a.dp(16), 0)
        }
        fun button(text: String, onClick: () -> Unit) {
            buttons.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = text
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            })
        }
        if (!tab.isBlank) button("Reload page") { TabManager.reload(tab) }
        tab.uboAction?.let { action -> button("uBlock Origin panel (element zapper, logger)") { action.click() } }
        AdBlock.extension?.metaData?.optionsPageUrl?.let { url -> button("uBlock Origin settings") { TabManager.newTab(url) } }
        button("Update filter lists now") {
            AdBlock.updateFilterLists()
            a.snack("Updating filter lists…")
        }
        col.addView(buttons)
        dialog = sheet(a, col)
        dialog.show()
    }

    // ------------------------------------------------------------------ tabs

    fun tabs(a: MainActivity) {
        val col = column(a)
        lateinit var dialog: BottomSheetDialog
        val list = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a)
            isNestedScrollingEnabled = false
        }
        val title = TextView(a)
        lateinit var adapter: RowAdapter<Tab>
        fun refresh() {
            val tabs = TabManager.profileTabs()
            adapter.items = tabs
            title.text = "${tabs.size} ${if (tabs.size == 1) "tab" else "tabs"} • ${Profiles.current().name}"
        }
        adapter = RowAdapter(
            bind = { h, t ->
                h.set(R.drawable.ic_tab, t.displayTitle, if (t.isBlank) null else UrlUtils.display(t.url))
                if (t === TabManager.current) h.itemView.setBackgroundResource(R.drawable.bg_current_tab)
                h.setAction(R.drawable.ic_close, "Close tab") {
                    TabManager.close(t)
                    refresh()
                }
            },
            onClick = { t ->
                dialog.dismiss()
                TabManager.select(t)
            },
        )
        list.adapter = adapter

        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(20), a.dp(8), a.dp(8), a.dp(8))
        }
        title.apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(a.color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(title)
        top.addView(MaterialButton(a, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Close all"
            setOnClickListener {
                MaterialAlertDialogBuilder(a)
                    .setMessage("Close all tabs in this profile?")
                    .setPositiveButton("Close all") { _, _ ->
                        dialog.dismiss()
                        TabManager.closeAll(Profiles.currentId)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })
        top.addView(MaterialButton(a).apply {
            text = "New tab"
            setIconResource(R.drawable.ic_add)
            setOnClickListener {
                dialog.dismiss()
                TabManager.newTab()
            }
        })
        col.addView(top)
        col.addView(list)
        refresh()
        dialog = sheet(a, col)
        dialog.show()
        val idx = adapter.items.indexOf(TabManager.current)
        if (idx > 3) list.scrollToPosition(idx)
    }

    // ------------------------------------------------------------------ profiles

    fun profiles(a: MainActivity) {
        val col = column(a)
        lateinit var dialog: BottomSheetDialog
        col.addView(header(a, "Profiles", "Each profile has its own Google login, cookies, history, bookmarks and tabs."))
        val list = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a)
            isNestedScrollingEnabled = false
        }
        val adapter = RowAdapter<ProfileRow>(
            bind = { h, p ->
                val current = p.id == Profiles.currentId
                val count = TabManager.profileTabs(p.id).size
                h.set(R.drawable.ic_person, p.name, (if (current) "Current • " else "") + "$count ${if (count == 1) "tab" else "tabs"}")
                h.icon.imageTintList = ColorStateList.valueOf(p.color)
                if (current) h.itemView.setBackgroundResource(R.drawable.bg_current_tab)
                h.setAction(R.drawable.ic_edit, "Manage") {
                    dialog.dismiss()
                    manageProfile(a, p)
                }
            },
            onClick = { p ->
                dialog.dismiss()
                if (p.id != Profiles.currentId) {
                    TabManager.switchProfile(p.id)
                    a.snack("Switched to ${p.name}")
                }
            },
            onLongClick = { p ->
                dialog.dismiss()
                manageProfile(a, p)
            },
        )
        adapter.items = Profiles.all()
        list.adapter = adapter
        col.addView(list)
        col.addView(row(a, R.drawable.ic_add, "Add profile") {
            dialog.dismiss()
            askText(a, "New profile", "Name (for example your email or \"Movies\")", "") { name ->
                val p = Profiles.add(name)
                TabManager.switchProfile(p.id)
                a.snack("Created ${p.name}. Sign in to Google from the profile menu if you like.")
            }
        })
        col.addView(row(a, R.drawable.ic_person, "Sign in to Google in ${Profiles.current().name}") {
            dialog.dismiss()
            TabManager.newTab("https://accounts.google.com/")
        })
        dialog = sheet(a, col)
        dialog.show()
    }

    private fun manageProfile(a: MainActivity, p: ProfileRow) {
        val options = mutableListOf("Rename", "Clear cookies and site data")
        if (Profiles.all().size > 1) options += "Delete profile"
        MaterialAlertDialogBuilder(a)
            .setTitle(p.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Rename" -> askText(a, "Rename profile", "Name", p.name) { name ->
                        Profiles.rename(p.id, name)
                        a.onTabsChanged()
                    }
                    "Clear cookies and site data" -> MaterialAlertDialogBuilder(a)
                        .setMessage("Sign out of all sites in ${p.name} and delete their stored data?")
                        .setPositiveButton("Clear") { _, _ ->
                            Engine.runtime.storageController.clearDataForSessionContext(Profiles.contextId(p.id))
                            a.snack("Cleared")
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    "Delete profile" -> MaterialAlertDialogBuilder(a)
                        .setTitle("Delete ${p.name}?")
                        .setMessage("Its tabs, history, bookmarks, cookies and logins will be removed.")
                        .setPositiveButton("Delete") { _, _ ->
                            TabManager.deleteProfile(p.id)
                            a.snack("Profile deleted")
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    fun askText(ctx: Context, title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(ctx).apply {
            setText(initial)
            this.hint = hint
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            selectAll()
        }
        val box = LinearLayout(ctx).apply {
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ videos

    fun videos(a: MainActivity, tab: Tab) {
        val col = column(a)
        lateinit var dialog: BottomSheetDialog
        col.addView(header(a, "Videos on this page", "Tap one to download it. Start playing the video first if it isn't listed."))
        val list = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a)
            isNestedScrollingEnabled = false
        }
        val adapter = RowAdapter<MediaItem>(
            bind = { h, m ->
                val name = m.url.substringBefore('?').substringAfterLast('/').ifBlank { UrlUtils.host(m.url) ?: "video" }
                val host = UrlUtils.host(m.frameUrl.ifBlank { m.url }) ?: ""
                val info = if (m.kind == MediaItem.Kind.HLS) {
                    "Stream • " + (streamInfo[m.url]?.summary ?: "checking…")
                } else {
                    "File" + if (m.size > 0) " • ${UrlUtils.formatBytes(m.size)}" else ""
                }
                h.set(R.drawable.ic_movie, if (m.kind == MediaItem.Kind.HLS) "Video stream ($name)" else name, "$info • $host")
                h.setAction(R.drawable.ic_download, "Download") {
                    dialog.dismiss()
                    downloadMedia(a, tab, m)
                }
            },
            onClick = { m ->
                dialog.dismiss()
                downloadMedia(a, tab, m)
            },
            onLongClick = { m -> a.copy(m.url) },
        )
        fun visibleItems(): List<MediaItem> {
            // Hide the quality variants of a stream whose master playlist is listed too.
            val variants = streamInfo.values.flatMap { it.variantUrls }.toSet()
            return tab.media.reversed().filter { it.kind != MediaItem.Kind.HLS || it.url !in variants }
        }
        adapter.items = visibleItems()
        list.adapter = adapter
        val unchecked = tab.media.filter { it.kind == MediaItem.Kind.HLS && !streamInfo.containsKey(it.url) }
        if (unchecked.isNotEmpty()) {
            a.lifecycleScope.launch {
                for (m in unchecked) {
                    streamInfo[m.url] = withContext(Dispatchers.IO) { inspectStream(m) }
                    adapter.items = visibleItems()
                }
            }
        }
        col.addView(list)
        col.addView(row(a, R.drawable.ic_speed, "Playback speed (${a.formatSpeed(tab.speed)})") {
            dialog.dismiss()
            speed(a, tab)
        })
        dialog = sheet(a, col)
        dialog.show()
    }

    /** What a detected HLS playlist contains; cached per URL while the app runs. */
    private class StreamInfo(val summary: String, val variantUrls: List<String>)

    private val streamInfo = HashMap<String, StreamInfo>()

    /** Blocking; reads the playlist to describe it. */
    private fun inspectStream(m: MediaItem): StreamInfo = try {
        val text = Downloads.fetchText(m.url, m.headers)
        when {
            !Hls.isPlaylist(text) -> StreamInfo("unknown format", emptyList())
            Hls.isMaster(text) -> {
                val variants = Hls.parseMaster(text, m.url)
                val best = variants.maxOfOrNull { it.height } ?: 0
                val count = if (variants.size == 1) "1 quality" else "${variants.size} qualities"
                StreamInfo(if (best > 0) "$count, up to ${best}p" else count, variants.map { it.url })
            }
            else -> {
                val media = Hls.parseMedia(text, m.url)
                val summary = when {
                    !media.endList -> "live (can't be saved)"
                    media.unsupportedKey -> "DRM-protected"
                    else -> Hls.formatDuration(media.duration)
                }
                StreamInfo(summary, emptyList())
            }
        }
    } catch (e: Exception) {
        StreamInfo("couldn't read", emptyList())
    }

    private fun downloadMedia(a: MainActivity, tab: Tab, m: MediaItem) {
        val headers = HashMap(m.headers)
        if (!headers.containsKey("referer")) {
            (m.frameUrl.ifBlank { tab.url }).takeIf { UrlUtils.isHttp(it) }?.let { headers["referer"] = it }
        }
        if (m.kind == MediaItem.Kind.HLS) {
            downloadHls(a, tab, m.url, headers)
        } else {
            a.withStorage {
                val base = tab.title.ifBlank { UrlUtils.host(tab.url) ?: "video" }
                val ext = UrlUtils.fileName(m.url, null, m.mime).substringAfterLast('.', "mp4").take(5)
                val name = UrlUtils.sanitizeFileName("$base.$ext")
                Downloads.startUrl(tab.profileId, m.url, name, m.mime.ifBlank { null }, headers, tab.url)
                a.snack("Downloading $name", "View") { a.openDownloads() }
            }
        }
    }

    /** Reads an HLS playlist; if it offers several qualities, lets the user choose one. */
    fun downloadHls(a: MainActivity, tab: Tab, url: String, headers: Map<String, String>) {
        a.snack("Reading video stream…")
        a.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = Downloads.fetchText(url, headers)
                    require(Hls.isPlaylist(text)) { "Not a video playlist" }
                    if (Hls.isMaster(text)) {
                        Hls.parseMaster(text, url) to null
                    } else {
                        emptyList<Hls.Variant>() to Hls.parseMedia(text, url)
                    }
                }
            }
            result.onFailure { a.snack("Couldn't read this stream: ${it.message}") }
            result.onSuccess { (variants, media) ->
                when {
                    media != null && !media.endList -> a.snack("This is a live stream, it can't be downloaded")
                    media != null && media.unsupportedKey -> a.snack("This video is DRM-protected and can't be saved")
                    media != null -> a.startHlsDownload(tab, url, headers, null)
                    variants.isEmpty() -> a.snack("No video found in this stream")
                    variants.size == 1 -> a.startHlsDownload(tab, variants[0].url, headers, shortQuality(variants[0]))
                    else -> MaterialAlertDialogBuilder(a)
                        .setTitle("Choose quality")
                        .setItems(variants.map { it.label }.toTypedArray()) { _, which ->
                            val v = variants[which]
                            a.startHlsDownload(tab, v.url, headers, shortQuality(v))
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun shortQuality(v: Hls.Variant): String? = if (v.height > 0) "${v.height}p" else null

    // ------------------------------------------------------------------ speed

    private val SPEEDS = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)

    fun speed(a: MainActivity, tab: Tab) {
        val labels = SPEEDS.map { a.formatSpeed(it) }.toTypedArray()
        val checked = SPEEDS.indexOfFirst { it == tab.speed }.coerceAtLeast(2)
        MaterialAlertDialogBuilder(a)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, checked) { d, which ->
                Helper.setSpeed(tab, SPEEDS[which])
                d.dismiss()
                if (tab.helperPorts.isEmpty()) a.snack("Speed ${labels[which]} will apply when the video plays")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
