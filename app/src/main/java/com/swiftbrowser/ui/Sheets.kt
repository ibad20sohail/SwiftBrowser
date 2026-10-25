package com.swiftbrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.swiftbrowser.core.ClosedTabRow
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.ProfileRow
import com.swiftbrowser.core.SiteSettings
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.download.Hls
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.media.SleepTimer
import com.swiftbrowser.media.MediaItem
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabGroup
import com.swiftbrowser.tabs.TabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bottom sheets opened from the toolbar and the menu. */
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

    // ------------------------------------------------------------------ action lists

    class Action(val icon: Int, val label: String, val run: () -> Unit)

    /**
     * Things to do with a link, a picture, a shortcut or the tab button, as a sheet that slides up:
     * what it is about at the top (a title and, smaller, its address), then a row per action.
     */
    fun actions(a: MainActivity, title: String?, subtitle: String?, actions: List<Action>, icon: android.graphics.Bitmap? = null) {
        val col = column(a)
        lateinit var dialog: BottomSheetDialog
        if (!title.isNullOrBlank() || !subtitle.isNullOrBlank()) {
            val head = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(10))
            }
            if (icon != null) {
                head.addView(android.widget.ImageView(a).apply { setImageBitmap(icon) }, LinearLayout.LayoutParams(a.dp(28), a.dp(28)).apply {
                    marginEnd = a.dp(14)
                })
            }
            head.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                if (!title.isNullOrBlank()) {
                    addView(TextView(a).apply {
                        text = title
                        textSize = 16f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(a.color(R.color.text_primary))
                    })
                }
                if (!subtitle.isNullOrBlank()) {
                    addView(TextView(a).apply {
                        text = subtitle
                        textSize = 13f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setTextColor(a.color(R.color.text_secondary))
                        setPadding(0, a.dp(2), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(head)
            col.addView(View(a).apply { setBackgroundColor(a.color(R.color.divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = a.dp(4) })
        }
        for (action in actions) {
            col.addView(row(a, action.icon, action.label) {
                dialog.dismiss()
                action.run()
            })
        }
        dialog = sheet(a, col)
        dialog.showImmersive()
    }

    // ------------------------------------------------------------------ dark mode

    fun setDarkMode(on: Boolean) {
        if (SwiftApp.prefs.darkMode == on) return
        SwiftApp.prefs.darkMode = on
        // Pages that support dark mode switch right away; sites that choose their colours when
        // the page loads (Google, YouTube) follow after the reload done when the screen restarts.
        Engine.setDark(on)
        SwiftApp.reloadAfterThemeChange = true
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
        AdBlock.extension?.metaData?.optionsPageUrl?.let { url -> button("uBlock Origin settings") { TabManager.newTab(url, incognito = false) } }
        button("Update filter lists now") {
            AdBlock.updateFilterLists()
            a.snack("Updating filter lists…")
        }
        col.addView(buttons)
        dialog = sheet(a, col)
        dialog.showImmersive()
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
            TabManager.newTab("https://accounts.google.com/", incognito = false)
        })
        dialog = sheet(a, col)
        dialog.showImmersive()
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
                        .showImmersive()
                    "Delete profile" -> MaterialAlertDialogBuilder(a)
                        .setTitle("Delete ${p.name}?")
                        .setMessage("Its tabs, history, bookmarks, cookies and logins will be removed.")
                        .setPositiveButton("Delete") { _, _ ->
                            TabManager.deleteProfile(p.id)
                            a.snack("Profile deleted")
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .showImmersive()
                }
            }
            .showImmersive()
    }

    fun askText(ctx: Context, title: String, hint: String, initial: String, allowEmpty: Boolean = false, onOk: (String) -> Unit) {
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
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty() || allowEmpty) onOk(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            true
        }
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
        dialog.showImmersive()
    }

    /** What a detected HLS playlist contains; cached per URL while the app runs. */
    private class StreamInfo(val summary: String, val variantUrls: List<String>)

    private val streamInfo = object : LinkedHashMap<String, StreamInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamInfo>?) = size > 60
    }

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
                    else -> chooseQuality(a, tab, variants, headers)
                }
            }
        }
    }

    private fun shortQuality(v: Hls.Variant): String? = if (v.height > 0) "${v.height}p" else null

    /**
     * The stream's qualities, each with about how big the file will be: the video's length comes
     * from the smallest quality's playlist (one small request), the size from each one's bit rate.
     */
    private fun chooseQuality(a: MainActivity, tab: Tab, variants: List<Hls.Variant>, headers: Map<String, String>) {
        a.lifecycleScope.launch {
            val seconds = withContext(Dispatchers.IO) {
                runCatching {
                    val smallest = variants.minByOrNull { if (it.bandwidth > 0) it.bandwidth else Long.MAX_VALUE } ?: variants.last()
                    Hls.parseMedia(Downloads.fetchText(smallest.url, headers), smallest.url).duration
                }.getOrDefault(0.0)
            }
            val labels = variants.map { v ->
                val quality = shortQuality(v) ?: v.label
                val size = if (seconds > 0 && v.bandwidth > 0) " · about ${UrlUtils.formatBytes((v.bandwidth * seconds / 8).toLong())}" else ""
                quality + size
            }
            val minutes = if (seconds > 0) " (${(seconds / 60).toInt().coerceAtLeast(1)} min)" else ""
            MaterialAlertDialogBuilder(a)
                .setTitle("Choose quality$minutes")
                .setItems(labels.toTypedArray()) { _, which ->
                    val v = variants[which]
                    a.startHlsDownload(tab, v.url, headers, shortQuality(v))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showImmersive()
        }
    }

    // ------------------------------------------------------------------ sleep timer

    /** Stop playing after some minutes, or at the end of what is playing. */
    fun sleepTimer(ctx: Context, onChanged: (() -> Unit)? = null) {
        val choices = SleepTimer.CHOICES.toList() + SleepTimer.END
        val labels = choices.map { if (it == SleepTimer.END) "At the end of this video or song" else "In $it minutes" }.toMutableList()
        val running = SleepTimer.label()
        if (running != null) labels += "Turn off ($running)"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Sleep timer")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= choices.size) {
                    SleepTimer.cancel(ctx)
                    (ctx as? MainActivity)?.snack("Sleep timer off")
                } else {
                    SleepTimer.set(ctx, choices[which])
                    (ctx as? MainActivity)?.snack(
                        if (choices[which] == SleepTimer.END) "Playback stops at the end of this video" else "Playback stops in ${choices[which]} minutes",
                    )
                }
                onChanged?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    // ------------------------------------------------------------------ reader view

    /** Reader view's font and line spacing; open reader pages follow at once. */
    fun readerStyle(ctx: Context, onChanged: (() -> Unit)? = null) {
        val prefs = SwiftApp.prefs
        val col = column(ctx)
        col.addView(header(ctx, "Reader view text", "How articles look in reader view"))
        col.addView(label(ctx, "Font"))
        col.addView(chips(ctx, listOf("Serif", "Sans-serif"), if (prefs.readerFont == "sans") 1 else 0) { i ->
            prefs.readerFont = if (i == 1) "sans" else "serif"
            Helper.sendReaderStyle()
            onChanged?.invoke()
        })
        col.addView(label(ctx, "Line spacing", top = 16))
        col.addView(chips(ctx, listOf("Tight", "Normal", "Loose"), prefs.readerSpacing.coerceIn(0, 2)) { i ->
            prefs.readerSpacing = i
            Helper.sendReaderStyle()
            onChanged?.invoke()
        })
        col.addView(note(ctx, "Text size is set with the A− and A+ buttons in reader view."))
        sheet(ctx, col).showImmersive()
    }

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
            .showImmersive()
    }

    // ------------------------------------------------------------------ tab groups

    /** "Add to group": one of the groups next to the tab, or a new one. */
    fun chooseGroup(a: MainActivity, tab: Tab) {
        val groups = TabManager.groupsFor(tab.incognito, tab.profileId).filter { it.id != tab.groupId }
        val grouped = tab.groupId != null
        if (groups.isEmpty() && !grouped) {
            newGroup(a, tab)
            return
        }
        val labels = groups.map { g ->
            SpannableString("\u25CF  " + TabManager.groupTitle(g)).apply {
                setSpan(ForegroundColorSpan(a.color(TabGroup.colorRes(g.color))), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } + SpannableString("+  New group") +
            (if (grouped) listOf(SpannableString("\u2212  Remove from group")) else emptyList())
        MaterialAlertDialogBuilder(a)
            .setTitle(if (grouped) "Move to group" else "Add to group")
            .setItems(labels.toTypedArray<CharSequence>()) { _, which ->
                when {
                    which < groups.size -> {
                        TabManager.addToGroup(tab, groups[which])
                        a.snack("Added to ${TabManager.groupTitle(groups[which])}")
                    }
                    which == groups.size -> newGroup(a, tab)
                    else -> TabManager.removeFromGroup(tab)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun newGroup(a: MainActivity, tab: Tab) {
        askText(a, "New group", "Name (you can leave it empty)", "", allowEmpty = true) { name ->
            val g = TabManager.newGroupWith(tab)
            if (name.isNotBlank()) TabManager.renameGroup(g, name)
            a.snack("Tab added to ${TabManager.groupTitle(g)}. Drag other tabs onto it in the tab grid.")
        }
    }

    fun renameGroup(a: MainActivity, group: TabGroup) {
        askText(a, "Group name", "For example \"Movies\"", group.name, allowEmpty = true) { name ->
            TabManager.renameGroup(group, name)
        }
    }

    /** The ⋮ of an open group: its name and colour, a new tab in it, ungroup, close. */
    fun groupOptions(a: MainActivity, group: TabGroup, onNewTab: () -> Unit) {
        val col = column(a)
        lateinit var dialog: BottomSheetDialog
        val n = TabManager.tabsIn(group).size
        col.addView(header(a, group.name.ifBlank { "Tab group" }, if (n == 1) "1 tab" else "$n tabs"))
        col.addView(row(a, R.drawable.ic_edit, "Rename group") {
            dialog.dismiss()
            renameGroup(a, group)
        })
        col.addView(label(a, "Colour", top = 8))
        col.addView(colorPicker(a, group.color) { TabManager.setGroupColor(group, it) })
        col.addView(row(a, R.drawable.ic_add, "New tab in this group") {
            dialog.dismiss()
            onNewTab()
        })
        col.addView(row(a, R.drawable.ic_layers, "Ungroup (the tabs stay open)") {
            dialog.dismiss()
            TabManager.ungroup(group)
        })
        col.addView(row(a, R.drawable.ic_close, "Close group") {
            dialog.dismiss()
            TabManager.closeGroup(group)
        })
        dialog = sheet(a, col)
        dialog.showImmersive()
    }

    private fun colorPicker(a: MainActivity, selected: Int, onPick: (Int) -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(a.dp(18), a.dp(4), a.dp(12), a.dp(10))
        val dots = ArrayList<View>()
        fun dot(index: Int, chosen: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(a.color(TabGroup.colorRes(index)))
            if (chosen) setStroke(a.dp(3), a.color(R.color.text_primary))
        }
        for (i in TabGroup.COLORS.indices) {
            val v = View(a).apply {
                contentDescription = TabGroup.COLOR_NAMES[i]
                background = dot(i, i == selected)
                setOnClickListener {
                    dots.forEachIndexed { j, d -> d.background = dot(j, j == i) }
                    onPick(i)
                }
            }
            dots += v
            addView(v, LinearLayout.LayoutParams(a.dp(30), a.dp(30)).apply { marginEnd = a.dp(8) })
        }
    }

    // ------------------------------------------------------------------ recently closed tabs

    fun recentlyClosed(a: MainActivity) {
        val pid = Profiles.currentId
        a.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { TabManager.recentlyClosed(pid) }
            if (rows.isEmpty()) {
                a.snack("No recently closed tabs yet. Tabs you close wait here, so you can open them again.", long = true)
                return@launch
            }
            val col = column(a)
            lateinit var dialog: BottomSheetDialog
            col.addView(header(a, "Recently closed", "Tap one to open it again, with its back history."))
            val list = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a)
                isNestedScrollingEnabled = false
            }
            val now = System.currentTimeMillis()
            val adapter = RowAdapter<ClosedTabRow>(
                bind = { h, r ->
                    val host = UrlUtils.host(r.url)?.removePrefix("www.").orEmpty()
                    val ago = if (now - r.closedAt < DateUtils.MINUTE_IN_MILLIS) "Just now"
                    else DateUtils.getRelativeTimeSpanString(r.closedAt, now, DateUtils.MINUTE_IN_MILLIS)
                    h.set(R.drawable.ic_globe, r.title.ifBlank { UrlUtils.display(r.url) }, if (host.isEmpty()) ago else "$host · $ago")
                    h.icon.imageTintList = ColorStateList.valueOf(a.color(R.color.text_secondary))
                    Favicons.load(r.url) { bmp ->
                        if (bmp != null && h.bindingAdapterPosition >= 0) {
                            h.icon.imageTintList = null
                            h.icon.setImageBitmap(bmp)
                        }
                    }
                },
                onClick = { r ->
                    dialog.dismiss()
                    TabManager.reopen(r)
                },
            )
            adapter.items = rows
            list.adapter = adapter
            col.addView(list)
            col.addView(row(a, R.drawable.ic_delete, "Clear this list") {
                dialog.dismiss()
                TabManager.clearRecentlyClosed(pid)
                a.snack("Recently closed tabs cleared")
            })
            dialog = sheet(a, col)
            dialog.showImmersive()
        }
    }

    // ------------------------------------------------------------------ site settings

    /** A row with an icon, a title, a line of explanation and a switch; the whole row toggles it. */
    private fun switchRow(ctx: Context, icon: Int, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = SlimSwitch(ctx).apply { setChecked(checked, animate = false) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(60)
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(14), ctx.dp(8))
            setBackgroundResource(RowHolder.selectableBackground(ctx))
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(ctx.color(R.color.text_secondary))
            }, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(20), 0, ctx.dp(8), 0)
                addView(TextView(ctx).apply {
                    text = title
                    textSize = 15f
                    setTextColor(ctx.color(R.color.text_primary))
                })
                addView(TextView(ctx).apply {
                    text = sub
                    textSize = 13f
                    setTextColor(ctx.color(R.color.text_secondary))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(32)))
            accessibilityDelegate = SwitchRowAccessibility(sw)
            contentDescription = title
            setOnClickListener {
                val on = !sw.isChecked
                sw.setChecked(on, animate = true)
                onChange(on)
            }
        }
    }

    /**
     * One site's settings: its desktop version, sound that starts by itself, location, ad blocking,
     * and its cookies and data. Opened from ⋮ (the settings button next to the page's name) and
     * from Settings → Privacy & storage → Site settings.
     */
    fun siteSettings(ctx: Context, site: String, tab: Tab? = null, onChanged: (() -> Unit)? = null) {
        val col = column(ctx)
        lateinit var dialog: BottomSheetDialog
        val onThisSite = tab != null && UrlUtils.site(tab.url) == site
        col.addView(header(ctx, site, "Settings for this site, kept for all its pages and tabs."))
        col.addView(switchRow(ctx, R.drawable.ic_desktop, "Desktop site", "Always open the computer version", SiteSettings.desktop(site)) { on ->
            SiteSettings.setDesktop(site, on)
            if (onThisSite && tab != null) TabManager.setDesktop(tab, on)
            onChanged?.invoke()
        })
        col.addView(switchRow(ctx, R.drawable.ic_volume, "Sound can start by itself", "Videos may play with sound before you tap play", SiteSettings.autoplay(site)) { on ->
            SiteSettings.setAutoplay(site, on)
            onChanged?.invoke()
        })
        col.addView(switchRow(ctx, R.drawable.ic_lite, "Lite mode on this site", "Turn off if the site looks broken with Lite mode", !SiteSettings.noLite(site)) { on ->
            SiteSettings.setNoLite(site, !on)
            if (onThisSite && tab != null && SwiftApp.prefs.liteMode) TabManager.reload(tab)
            onChanged?.invoke()
        })
        col.addView(label(ctx, "Location", top = 12))
        val places = SiteSettings.Location.entries
        col.addView(chips(ctx, places.map { it.label }, places.indexOf(SiteSettings.location(site))) { i ->
            SiteSettings.setLocation(site, places[i])
            onChanged?.invoke()
        })
        col.addView(label(ctx, "Ad blocking", top = 16))
        val levels = AdLevel.entries
        val override = AdBlock.overrideFor(site)
        val levelNote = note(ctx, override?.summary ?: "Uses the level chosen for all sites (${AdBlock.global.label}).")
        col.addView(chips(ctx, listOf("Same as all sites") + levels.map { it.label }, if (override == null) 0 else levels.indexOf(override) + 1) { i ->
            val level = if (i == 0) null else levels[i - 1]
            AdBlock.setOverride(site, level)
            levelNote.text = level?.summary ?: "Uses the level chosen for all sites (${AdBlock.global.label})."
            onChanged?.invoke()
        })
        col.addView(levelNote)
        if (onThisSite) col.addView(note(ctx, "Changes apply when the page reloads."))

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), 0)
        }
        fun button(text: String, onClick: () -> Unit) {
            buttons.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = text
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            })
        }
        button("Clear cookies and data of this site") {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Clear $site's data?")
                .setMessage("Its cookies, saved logins and stored data are deleted, so you're signed out of it.")
                .setPositiveButton("Clear") { _, _ ->
                    Engine.runtime.storageController.clearDataFromBaseDomain(site, org.mozilla.geckoview.StorageController.ClearFlags.ALL)
                    if (onThisSite && tab != null) TabManager.reload(tab)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showImmersive()
        }
        if (SiteSettings.hasSettings(site)) {
            button("Reset this site's settings") {
                val wasDesktop = SiteSettings.desktop(site)
                SiteSettings.reset(site)
                if (onThisSite && tab != null && wasDesktop) TabManager.setDesktop(tab, false)
                onChanged?.invoke()
            }
        }
        col.addView(buttons)
        dialog = sheet(ctx, col)
        dialog.showImmersive()
    }
}
