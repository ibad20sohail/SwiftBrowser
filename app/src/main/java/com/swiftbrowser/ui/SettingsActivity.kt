package com.swiftbrowser.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.Updates
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.engine.GpuInfo
import com.swiftbrowser.media.Helper
import com.swiftbrowser.media.PlaybackService
import com.swiftbrowser.tabs.TabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.StorageController
import java.io.File
import kotlin.math.roundToInt

/** Settings, grouped in cards. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var column: LinearLayout
    private var siteLevelsSummary: TextView? = null
    private var liteSummary: TextView? = null
    private var storageSummary: TextView? = null
    private var cacheBytes = 0L
    private val levelRows = ArrayList<Pair<AdLevel, View>>()

    private val blue by lazy { 0xFF1A73E8.toInt() }
    private val green by lazy { 0xFF188038.toInt() }
    private val red by lazy { 0xFFD93025.toInt() }
    private val amber by lazy { 0xFFE37400.toInt() }
    private val purple by lazy { 0xFF9334E6.toInt() }
    private val teal by lazy { 0xFF00897B.toInt() }
    private val grey by lazy { 0xFF5F6368.toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SwiftApp.prefs
        val bg = color(R.color.settings_bg)
        window.statusBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Settings"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
            setBackgroundColor(bg)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        build()
    }

    override fun onResume() {
        super.onResume()
        updateSiteLevelsSummary()
        updateLiteSummary()
        computeStorage()
    }

    fun openInBrowser(url: String) {
        setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, url).putExtra(MainActivity.EXTRA_NEW_TAB, true))
        finish()
    }

    private fun build() {
        column.addView(headerCard())

        section("Appearance") {
            switch(R.drawable.ic_dark, blue, "Dark mode", "Websites that support dark mode turn dark too", prefs.darkMode) {
                Sheets.setDarkMode(it)
            }
            lateinit var textSummary: TextView
            textSummary = action(R.drawable.ic_text_size, blue, "Text size", textSizeText(prefs.textScale)) {
                chooseTextSize { textSummary.text = textSizeText(it) }
            }
            switch(R.drawable.ic_zoom, blue, "Zoom on all sites", "Pinch to zoom even where a site turns it off", prefs.forceZoom) {
                prefs.forceZoom = it
                Engine.setForceZoom(it)
            }
        }

        section("Search") {
            info(R.drawable.ic_search, green, "Search engine", "Google")
            switch(R.drawable.ic_lightbulb, amber, "Search suggestions", "Google suggests searches while you type", prefs.searchSuggestions) {
                prefs.searchSuggestions = it
            }
        }

        section("Ad blocking") {
            add(levelChooser())
            siteLevelsSummary = action(R.drawable.ic_shield, red, "Sites with their own level", "") { showSiteLevels() }
            action(R.drawable.ic_ublock, red, "uBlock Origin dashboard", "Filter lists, your own filters and rules") {
                AdBlock.extension?.metaData?.optionsPageUrl?.let { openInBrowser(it) }
            }
            action(R.drawable.ic_refresh, red, "Update filter lists now", "Lists also update by themselves every few days") {
                AdBlock.updateFilterLists()
                toast("Updating filter lists in the background")
            }
        }

        section("Browsing") {
            switch(R.drawable.ic_toolbar, blue, "Hide toolbar while scrolling", "Scroll up a little to bring it back", prefs.hideToolbarOnScroll) {
                prefs.hideToolbarOnScroll = it
            }
            switch(R.drawable.ic_refresh, blue, "Pull down to refresh", "At the top of a page, drag down to reload it", prefs.pullToRefresh) {
                prefs.pullToRefresh = it
            }
            switch(R.drawable.ic_gesture, blue, "Swipe from the edges", "From the left edge of a page to go back, from the right to go forward", prefs.edgeSwipe) {
                prefs.edgeSwipe = it
            }
        }

        section("Video") {
            switch(R.drawable.ic_headphones, purple, "Background play", "Videos and music keep playing when you leave Swift or turn the screen off, with play/pause in the notifications", prefs.backgroundPlay) {
                prefs.backgroundPlay = it
                TabManager.applyBackgroundPlay(it)
                PlaybackService.sync(this@SettingsActivity)
            }
            switch(R.drawable.ic_rotate, purple, "Rotate full-screen videos", "Full-screen videos turn to landscape. Off: the phone's auto-rotate decides.", prefs.autoLandscape) {
                prefs.autoLandscape = it
            }
            switch(R.drawable.ic_gesture, purple, "Swipe gestures", "In full screen, swipe on the left for brightness, on the right for volume", prefs.videoGestures) {
                prefs.videoGestures = it
            }
            switch(R.drawable.ic_movie, purple, "Save streams as MP4", "Convert downloaded video streams to MP4 (otherwise .ts)", prefs.hlsToMp4) {
                prefs.hlsToMp4 = it
            }
            switch(
                R.drawable.ic_play, purple, "Play silent videos by themselves",
                "Like the previews in YouTube's feed. Off makes scrolling smoother on this phone.",
                prefs.autoplayMuted,
            ) { prefs.autoplayMuted = it }
        }

        section("Downloads") {
            switch(R.drawable.ic_download, teal, "Ask before downloading", "Stops sites from starting downloads by themselves", prefs.confirmDownloads) {
                prefs.confirmDownloads = it
            }
            info(R.drawable.ic_folder, teal, "Download folder", Downloads.directory().path.replace("/storage/emulated/0", "Phone storage"))
        }

        section("Performance") {
            lateinit var tabsSummary: TextView
            tabsSummary = action(R.drawable.ic_memory, amber, "Tabs kept in memory", liveTabsText(prefs.maxLiveTabs)) {
                chooseLiveTabs { tabsSummary.text = liveTabsText(it) }
            }
            val gpu = GpuInfo.renderer(this@SettingsActivity).ifBlank { "unknown" }
            switch(
                R.drawable.ic_layers, amber, "Draw pages with the CPU",
                if (prefs.autoSoftwareRendering) {
                    "On by default for this phone's graphics chip ($gpu), which is slow at drawing pages with a lot of text. Turn off to compare. Restarts Swift."
                } else {
                    "Try this if scrolling is choppy. Graphics chip: $gpu. Restarts Swift."
                },
                prefs.softwareRendering,
            ) { on -> confirmRenderingChange(on) }
            liteSummary = action(R.drawable.ic_lite, amber, "Lite mode sites", "") { showLiteSites() }
            switch(
                R.drawable.ic_speed, amber, "Show drawing speed",
                "Shows frames per second and how long each frame takes, on top of pages. For comparing the two drawing methods.",
                prefs.showDrawSpeed,
            ) { on ->
                prefs.showDrawSpeed = on
                Engine.setDrawSpeedMeter(on)
            }
        }

        section("Privacy & storage") {
            action(R.drawable.ic_delete, grey, "Clear browsing data", "History, cookies and cached files of ${Profiles.current().name}") { clearData() }
            storageSummary = action(R.drawable.ic_storage, grey, "Storage used", "Calculating…") { clearCache() }
        }

        section("About") {
            info(
                R.drawable.ic_info, grey, "Swift ${BuildConfig.VERSION_NAME}",
                "Firefox engine (GeckoView 143) • uBlock Origin ${AdBlock.uboVersion}",
            )
            action(R.drawable.ic_update, grey, "Check for updates", "New versions come from GitHub; installing keeps your tabs and settings") {
                checkForUpdates()
            }
            action(R.drawable.ic_report, grey, "Send a problem report", "What Swift noted about crashes and memory, to send by WhatsApp or e-mail") {
                Diagnostics.shareReport(this@SettingsActivity)
            }
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun headerCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_settings_card)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
        addView(ImageView(this@SettingsActivity).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(TextView(this@SettingsActivity).apply {
                text = "Swift"
                textSize = 22f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(color(R.color.text_primary))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "Profile: ${Profiles.current().name} • Ad blocking: ${AdBlock.global.label}"
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
            })
        })
    }

    private inner class Section(val card: LinearLayout) {
        fun add(view: View) {
            if (card.childCount > 0) {
                card.addView(View(this@SettingsActivity).apply { setBackgroundColor(color(R.color.divider)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(70) })
            }
            card.addView(view)
        }

        /** A row that opens something; returns its summary so it can be updated. */
        fun action(icon: Int, tint: Int, title: String, summary: String, onClick: () -> Unit): TextView {
            val (row, sub) = row(icon, tint, title, summary)
            row.isClickable = true
            row.setBackgroundResource(RowHolder.selectableBackground(this@SettingsActivity))
            row.setOnClickListener { onClick() }
            row.addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            add(row)
            return sub
        }

        fun info(icon: Int, tint: Int, title: String, summary: String) {
            add(row(icon, tint, title, summary).first)
        }

        fun switch(icon: Int, tint: Int, title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            val (row, _) = row(icon, tint, title, summary)
            val sw = MaterialSwitch(this@SettingsActivity).apply { isChecked = checked }
            row.addView(sw)
            row.isClickable = true
            row.setBackgroundResource(RowHolder.selectableBackground(this@SettingsActivity))
            row.setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { _, on -> onChange(on) }
            add(row)
        }
    }

    private fun section(title: String, build: Section.() -> Unit) {
        column.addView(TextView(this).apply {
            text = title
            textSize = 13.5f
            letterSpacing = 0.02f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(color(R.color.accent))
            setPadding(dp(18), dp(24), dp(16), dp(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_settings_card)
            clipToOutline = true
        }
        Section(card).build()
        column.addView(card)
    }

    /** Icon in a tinted circle, title and summary. Returns the row and its summary view. */
    private fun row(icon: Int, tint: Int, title: String, summary: String?): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(10), dp(14), dp(10))
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(tint, 0x2A))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(color(R.color.text_primary))
        })
        val sub = TextView(this).apply {
            text = summary
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
            visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row to sub
    }

    /** The four blocking levels with their descriptions; the chosen one is highlighted. */
    private fun levelChooser(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        box.addView(TextView(this).apply {
            text = "Blocking level for all sites"
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(4), 0, 0, dp(8))
        })
        levelRows.clear()
        for (level in AdLevel.entries) {
            val option = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_level_option)
                setPadding(dp(6), dp(10), dp(14), dp(10))
                isClickable = true
                setOnClickListener {
                    AdBlock.setGlobal(level)
                    updateLevelRows()
                }
            }
            option.addView(MaterialRadioButton(this).apply {
                isClickable = false
                isFocusable = false
                tag = "radio"
            })
            option.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = level.label
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.text_primary))
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = level.summary
                    textSize = 13f
                    setTextColor(color(R.color.text_secondary))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(option, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
            levelRows += level to option
        }
        updateLevelRows()
        return box
    }

    private fun updateLevelRows() {
        val current = AdBlock.global
        for ((level, view) in levelRows) {
            view.isSelected = level == current
            (view.findViewWithTag<View>("radio") as? MaterialRadioButton)?.isChecked = level == current
        }
    }

    // ------------------------------------------------------------------ actions

    private fun textSizeText(scale: Float) = "${(scale * 100).roundToInt()}%" + if (scale == 1f) " (as sites designed it)" else ""

    private fun chooseTextSize(onChosen: (Float) -> Unit) {
        val options = floatArrayOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f)
        val labels = options.map { textSizeText(it) }.toTypedArray()
        val checked = options.indexOfFirst { kotlin.math.abs(it - prefs.textScale) < 0.01f }
        MaterialAlertDialogBuilder(this)
            .setTitle("Text size")
            .setSingleChoiceItems(labels, checked) { d, which ->
                prefs.textScale = options[which]
                Engine.setTextScale(options[which])
                onChosen(options[which])
                d.dismiss()
                toast("Pages use the new size when they reload")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateLiteSummary() {
        val n = prefs.liteSites.size
        liteSummary?.text = if (n == 0) {
            "None yet. Turn it on for a slow site from the menu (⋮ → Lite mode): no animations, shadows or blur."
        } else {
            "$n ${if (n == 1) "site" else "sites"} without animations, shadows or blur"
        }
        liteSummary?.visibility = View.VISIBLE
    }

    private fun showLiteSites() {
        val sites = prefs.liteSites.sorted()
        if (sites.isEmpty()) {
            MaterialAlertDialogBuilder(this).setMessage("No sites use Lite mode yet. Turn it on for a slow site from the menu (⋮ → Lite mode).")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Lite mode sites")
            .setItems(sites.toTypedArray()) { _, which ->
                val site = sites[which]
                MaterialAlertDialogBuilder(this)
                    .setMessage("Show $site normally again?")
                    .setPositiveButton("Turn off Lite mode") { _, _ ->
                        prefs.liteSites = prefs.liteSites - site
                        Helper.sendLiteSites()
                        updateLiteSummary()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkForUpdates() {
        toast("Checking for updates…")
        Updates.latest { r ->
            when {
                r == null -> toast("Couldn't reach GitHub. Check the internet connection.")
                !Updates.isNewer(r.version, BuildConfig.VERSION_NAME) -> toast("Swift ${BuildConfig.VERSION_NAME} is the newest version")
                else -> {
                    val size = if (r.size > 0) " (${UrlUtils.formatBytes(r.size)})" else ""
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Update to Swift ${r.version}?")
                        .setMessage(r.notes.ifBlank { "A new version of Swift is available." }.take(1500) +
                            "\n\nThe update$size downloads now; then Android asks to install it. Tabs and settings are kept.")
                        .setPositiveButton("Download") { _, _ ->
                            val item = Downloads.startUrl(Profiles.currentId, r.apkUrl, r.apkName, "application/vnd.android.package-archive", emptyMap(), null)
                            Updates.downloadId = item.id
                            toast("Downloading Swift ${r.version}… Android asks to install it when it's done.")
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
    }

    private fun liveTabsText(n: Int) = when (n) {
        1 -> "1 tab — least memory; other tabs reload when opened"
        2 -> "2 tabs (recommended for this phone)"
        in 3..4 -> "$n tabs — switching is faster, uses more memory"
        else -> "$n tabs — a lot of memory; Android may close Swift's pages or other apps"
    }

    private fun chooseLiveTabs(onChosen: (Int) -> Unit) {
        val options = (1..Prefs.MAX_LIVE_TABS_LIMIT).toList()
        val labels = options.map { liveTabsText(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Tabs kept in memory")
            .setSingleChoiceItems(labels, options.indexOf(prefs.maxLiveTabs)) { d, which ->
                prefs.maxLiveTabs = options[which]
                onChosen(options[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRenderingChange(on: Boolean) {
        prefs.softwareRendering = on
        Engine.setSoftwareRendering(on)
        MaterialAlertDialogBuilder(this)
            .setTitle("Restart Swift?")
            .setMessage("The new drawing method is used after Swift restarts. Your tabs stay open.")
            .setPositiveButton("Restart now") { _, _ -> SwiftApp.restart(this) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun updateSiteLevelsSummary() {
        val n = AdBlock.overrides().size
        siteLevelsSummary?.text =
            if (n == 0) "None yet. Set one from the menu (⋮ → Ad blocker) while on a site." else "$n ${if (n == 1) "site" else "sites"}"
        siteLevelsSummary?.visibility = View.VISIBLE
    }

    private fun showSiteLevels() {
        val entries = AdBlock.overrides().entries.toList()
        if (entries.isEmpty()) {
            MaterialAlertDialogBuilder(this).setMessage("No sites have their own level yet.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tap a site to reset it")
            .setItems(entries.map { "${it.key} — ${it.value.label}" }.toTypedArray()) { _, which ->
                AdBlock.setOverride(entries[which].key, null)
                updateSiteLevelsSummary()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun clearData() {
        val profile = Profiles.current()
        val labels = arrayOf("History", "Cookies and site data (signs you out)", "Cached images and files")
        val checked = booleanArrayOf(true, false, true)
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear data • ${profile.name}")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Clear") { _, _ ->
                if (checked[1]) Engine.runtime.storageController.clearDataForSessionContext(Profiles.contextId(profile.id))
                if (checked[2]) Engine.runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
                if (checked[0]) lifecycleScope.launch { withContext(Dispatchers.IO) { SwiftApp.db.clearHistory(profile.id) } }
                toast("Cleared")
                lifecycleScope.launch {
                    delay(1500)
                    computeStorage()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Free up space?")
            .setMessage(
                "Cached images and files (${UrlUtils.formatBytes(cacheBytes)}) are deleted. " +
                    "Pages load from the network again; logins and settings stay.",
            )
            .setPositiveButton("Clear cache") { _, _ ->
                Engine.runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
                lifecycleScope.launch {
                    delay(1500)
                    computeStorage()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun computeStorage() {
        val summary = storageSummary ?: return
        lifecycleScope.launch {
            val (cache, data) = withContext(Dispatchers.IO) {
                sizeOf(cacheDir) to sizeOf(File(filesDir, "mozilla"))
            }
            cacheBytes = cache
            summary.text = "Cache ${UrlUtils.formatBytes(cache)} • Sites and ad-block lists ${UrlUtils.formatBytes(data)}. Tap to clear the cache."
        }
    }

    private fun sizeOf(f: File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { sizeOf(it) } ?: 0
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
