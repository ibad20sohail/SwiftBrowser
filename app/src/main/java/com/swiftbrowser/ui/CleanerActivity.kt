package com.swiftbrowser.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.DocIcon
import com.swiftbrowser.docs.DocThumbs
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.files.Cleaner
import com.swiftbrowser.files.FileJobs
import com.swiftbrowser.files.FileKind
import com.swiftbrowser.files.FileOpener
import com.swiftbrowser.files.FileThumbs
import com.swiftbrowser.files.FilesSettings
import com.swiftbrowser.files.Parts
import com.swiftbrowser.files.RecycleBin
import com.swiftbrowser.files.Transfers
import com.swiftbrowser.files.Volumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * "Free up space" (2.9), opened from the Files start page: a walk through the storage (with its
 * progress, and Cancel), then the lists the cleaner found (Cleaner) as cards with what each would
 * free. In a list, things are ticked (app files, empty folders and Swift's leftovers all at first;
 * of twins all but the newest; large files none), and the bar at the bottom says how much the ticked
 * ones take, with Delete (to the recycle bin, Undo at hand) or Delete forever.
 *
 * 3.0: one copy of each set of twins always stays: it can't be ticked, "Tick all" leaves it, and a
 * set down to one copy leaves the list (its last copy was offered for deleting). App files are ticked
 * only when their app is installed. Clearing Swift's leftovers and deleting empty folders ask first.
 */
class CleanerActivity : ListActivity() {
    private class Hint(val text: String)
    private class Card(val kind: Cleaner.Kind, val count: Int, val bytes: Long)
    private class GroupHeader(val group: Any?, val text: String)

    private val access = ChangeAccess(this) { say(it) }
    private val adapter = Adapter()
    private var results: Cleaner.Results? = null
    private var shown: Cleaner.Kind? = null
    private var scanJob: Job? = null
    private var progress = Cleaner.Progress()
    private var working: Messages.Handle? = null
    private var menuShown = ""

    private lateinit var bar: LinearLayout
    private lateinit var bottomBar: BottomBar
    private lateinit var barText: TextView
    private lateinit var foreverButton: MaterialButton
    private lateinit var deleteButton: MaterialButton

    private val secondaryTint by lazy { ColorStateList.valueOf(Ui.textSecondary(this)) }
    private val accentTint by lazy { ColorStateList.valueOf(Ui.accent(this)) }
    private val onContainerTint by lazy { ColorStateList.valueOf(Ui.onAccentContainer(this)) }
    private var thumbPx = 0

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbPx = dp(Ui.ICON_BOX)
        toolbar.title = "Free up space"
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setOnMenuItemClickListener {
            onMenu(it.itemId)
            true
        }
        list.adapter = adapter
        list.itemAnimator = Motion.listAnimator()
        buildBar()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (shown != null) showSummary(Motion.BACK) else finish()
            }
        })
        showSpace()
        // Turned sideways: what was found stays (a walk still running starts again).
        val kept = if (savedInstanceState != null) lastResults else null
        if (kept != null) {
            results = kept
            val k = savedInstanceState?.getString(STATE_LIST)?.let { key -> Cleaner.Kind.entries.firstOrNull { it.name == key } }
            if (k != null) showList(k) else showSummary()
        } else {
            scan()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shown?.let { outState.putString(STATE_LIST, it.name) }
    }

    override fun onDestroy() {
        list.removeCallbacks(tick)
        working?.dismiss()
        super.onDestroy()
    }

    private fun say(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        if (isFinishing) return
        bar.post {
            if (isFinishing) return@post
            Messages.show(this, text, action, bottomOffset = if (bar.visibility == View.VISIBLE) bar.height else 0, onAction = onAction)
        }
    }

    /** The storages' free space under the title. */
    private fun showSpace() {
        val app = applicationContext
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                Volumes.refresh(app).joinToString(" · ") { v -> v.space().first.let { free -> if (free >= 0) "${v.name}: ${UrlUtils.formatBytes(free)} free" else v.name } }
            }
            if (shown == null) toolbar.subtitle = text
            spaceText = text
        }
    }

    private var spaceText: String? = null

    // ------------------------------------------------------------------ the walk

    private fun scan() {
        scanJob?.cancel()
        results = null
        shown = null
        progress = Cleaner.Progress()
        val p = progress
        val app = applicationContext
        bottomBar.show(false, Motion.enabled(this))
        adapter.set(emptyList())
        showScanning()
        val started = System.currentTimeMillis()
        scanJob = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    Cleaner.scan(app, p) { !isActive }
                } catch (e: Exception) {
                    L.w("Cleaner: the walk failed (${e.javaClass.simpleName})")
                    null
                }
            }
            if (!isActive) return@launch
            list.removeCallbacks(tick)
            L.d("Cleaner: walk over in ${System.currentTimeMillis() - started} ms")
            if (r == null) {
                empty.removeAllViews()
                empty.addView(Ui.emptyState(this@CleanerActivity, R.drawable.ic_error, "Swift couldn't look through your files", "Try again in a moment."))
                return@launch
            }
            results = r
            lastResults = r
            showSummary()
        }
    }

    private var scanStage: TextView? = null
    private var scanCount: TextView? = null
    private var scanBar: ProgressBar? = null

    private val tick = object : Runnable {
        override fun run() {
            val p = progress
            scanStage?.text = p.stage
            scanCount?.text = if (p.files == 1) "1 file looked at" else String.format(Locale.getDefault(), "%,d files looked at", p.files)
            scanBar?.let { b ->
                val indeterminate = p.fraction < 0
                if (b.isIndeterminate != indeterminate) b.isIndeterminate = indeterminate
                if (!indeterminate) Motion.progress(b, (p.fraction * 1000).toInt())
            }
            list.postDelayed(this, 250)
        }
    }

    private fun showScanning() {
        empty.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        col.addView(Ui.iconBadge(this, R.drawable.ic_files_clean, 88, tint = Ui.accent(this)))
        val stage = Ui.text(this, Ui.Type.TITLE, progress.stage).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        col.addView(stage)
        val b = FileJobDialogs.bar(this).apply { isIndeterminate = true }
        col.addView(b, LinearLayout.LayoutParams(dp(220), dp(6)).apply {
            topMargin = dp(16)
            bottomMargin = dp(10)
        })
        val count = Ui.steadyFigures(Ui.text(this, Ui.Type.BODY_SECONDARY, "").apply { gravity = Gravity.CENTER })
        col.addView(count)
        col.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            setOnClickListener {
                scanJob?.cancel()
                finish()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        Palette.paint(col)
        empty.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        empty.visibility = View.VISIBLE
        scanStage = stage
        scanCount = count
        scanBar = b
        list.removeCallbacks(tick)
        list.post(tick)
        updateMenu()
    }

    // ------------------------------------------------------------------ the lists

    private fun showSummary(slide: Int = 0) {
        val r = results ?: return
        shown = null
        toolbar.title = "Free up space"
        toolbar.subtitle = spaceText
        bottomBar.show(false, Motion.enabled(this))
        empty.visibility = View.GONE
        val items = ArrayList<Any>()
        val cards = Cleaner.Kind.entries.map { k ->
            r.of(k).let { l ->
                if (k == Cleaner.Kind.DUPLICATES) {
                    // 3.0: what can be freed: every copy but one of each set (a set of one isn't twins any more).
                    val twins = l.groupBy { it.group }.values.filter { it.size >= 2 }.flatten()
                    Card(k, twins.size, Cleaner.tickableTwins(l).second)
                } else {
                    Card(k, l.size, l.sumOf { it.size })
                }
            }
        }
        val total = cards.filter { it.kind != Cleaner.Kind.EMPTY }.sumOf { it.bytes }
        items += Hint(
            (if (total > 0) "Up to ${UrlUtils.formatBytes(total)} can be freed. " else "") +
                "Delete moves things to the recycle bin, where they stay ${com.swiftbrowser.files.BinClock.daysText(FilesSettings.binDays)} (the space comes back when they leave it or you empty it). Delete forever frees it now.",
        )
        items.addAll(cards)
        adapter.set(items)
        list.scrollToPosition(0)
        if (slide != 0) Motion.pageIn(list, slide)
        updateMenu()
    }

    private fun showList(kind: Cleaner.Kind, slide: Int = 0, removals: Boolean = false) {
        val r = results ?: return
        // Gone meanwhile (deleted in another app): not shown.
        r.lists[kind].orEmpty().forEach { if (!it.gone && !it.file.exists()) it.gone = true }
        // 3.0: a set of twins down to one copy isn't shown, and nothing of it stays ticked.
        if (kind == Cleaner.Kind.DUPLICATES) Cleaner.tidyTwins(r.of(kind))
        val found = r.of(kind).let { l -> if (kind == Cleaner.Kind.DUPLICATES) l.groupBy { it.group }.values.filter { it.size >= 2 }.flatten() else l }
        if (found.isEmpty()) {
            say(kind.nothing)
            if (shown != null) showSummary()
            return
        }
        shown = kind
        toolbar.title = kind.title
        empty.visibility = View.GONE
        val items = ArrayList<Any>()
        items += Hint(kind.hint)
        if (kind == Cleaner.Kind.DUPLICATES) {
            for ((_, group) in found.groupBy { it.group }) {
                if (group.size < 2) continue
                items += GroupHeader(group[0].group, "${group.size} copies · ${UrlUtils.formatBytes(group[0].size)} each")
                items.addAll(group)
            }
        } else {
            items.addAll(found)
        }
        adapter.set(items, removals)
        list.scrollToPosition(0)
        if (slide != 0) Motion.pageIn(list, slide)
        updateBar()
        updateMenu()
    }

    private fun ticked(): List<Cleaner.Found> = shown?.let { k ->
        results?.of(k)?.let { l ->
            // 3.0: of twins, only sets still of two or more (never the last copy of one).
            if (k == Cleaner.Kind.DUPLICATES) {
                Cleaner.tidyTwins(l)
                l.groupBy { it.group }.values.filter { it.size >= 2 }.flatten().filter { it.ticked }
            } else {
                l.filter { it.ticked }
            }
        }
    }.orEmpty()

    /** How many in the list on show can be ticked (3.0: of twins, all but one copy of each). */
    private fun tickable(k: Cleaner.Kind): Int = results?.of(k)?.let { l -> if (k == Cleaner.Kind.DUPLICATES) Cleaner.tickableTwins(l).first else l.size } ?: 0

    /** "3 ticked · 1.2 GB", and the buttons for this list. */
    private fun updateBar() {
        val k = shown ?: return
        val t = ticked()
        val bytes = t.sumOf { it.size }
        val all = tickable(k)
        toolbar.subtitle = "${t.size} of $all ticked" + if (bytes > 0) " · ${UrlUtils.formatBytes(bytes)}" else ""
        barText.text = when {
            t.isEmpty() -> "Tick what you want to delete"
            k == Cleaner.Kind.EMPTY -> if (t.size == 1) "1 empty folder" else "${t.size} empty folders"
            else -> "${FileJobs.items(t.size)} · ${UrlUtils.formatBytes(bytes)} to free"
        }
        // Empty folders hold nothing, and Swift's leftovers come back by themselves: those go for good, no bin.
        val plain = k == Cleaner.Kind.EMPTY || k == Cleaner.Kind.LEFTOVERS
        foreverButton.visibility = if (plain) View.GONE else View.VISIBLE
        deleteButton.text = if (k == Cleaner.Kind.LEFTOVERS) "Clear" else "Delete"
        deleteButton.isEnabled = t.isNotEmpty()
        foreverButton.isEnabled = t.isNotEmpty()
        bottomBar.show(true, Motion.enabled(this))
        updateMenu()
    }

    private fun buildBar() {
        val box = list.parent as ViewGroup
        val column = box.parent as LinearLayout
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Palette.color(this@CleanerActivity, R.color.sheet_bg))
        }
        bottomBar = BottomBar(bar)
        // 3.0.5: no line above it: its own colour against the page's says where it begins.
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        barText = Ui.text(this, Ui.Type.BODY, "", lines = 2)
        line.addView(barText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foreverButton = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Delete forever"
            setTextColor(Ui.danger(this@CleanerActivity))
            setOnClickListener { delete(forever = true) }
        }
        line.addView(foreverButton)
        deleteButton = MaterialButton(this).apply {
            text = "Delete"
            setOnClickListener { delete(forever = false) }
        }
        line.addView(deleteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        bar.addView(line)
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Palette.paint(bar)
    }

    private fun updateMenu() {
        val k = shown
        val all = k != null && ticked().size.let { n -> n > 0 && n >= tickable(k) }
        val key = if (k == null) "" else "tick:$all"
        if (key == menuShown) return
        menuShown = key
        toolbar.menu.clear()
        if (k == null) return
        TitleBar.addTextAction(toolbar, MENU_TICK, if (all) "Untick all" else "Tick all") { onMenu(MENU_TICK) }
    }

    private fun onMenu(id: Int) {
        if (id != MENU_TICK) return
        val k = shown ?: return
        val l = results?.of(k) ?: return
        val on = ticked().size < tickable(k)
        when {
            // 3.0: all but the copy of each set that stays.
            k == Cleaner.Kind.DUPLICATES && on -> Cleaner.tickAllTwins(l)
            else -> l.forEach { it.ticked = on }
        }
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_TICK)
        updateBar()
    }

    private fun toggle(f: Cleaner.Found, position: Int) {
        // 3.0: the last unticked copy of a set of twins stays.
        if (!f.ticked && !Cleaner.canTick(results?.of(Cleaner.Kind.DUPLICATES).orEmpty(), f)) {
            say("One copy always stays. Untick another copy first to delete this one.")
            return
        }
        f.ticked = !f.ticked
        adapter.notifyItemChanged(position, PAYLOAD_TICK)
        updateBar()
    }

    // ------------------------------------------------------------------ deleting

    private fun delete(forever: Boolean) {
        val k = shown ?: return
        // ticked() never holds every copy of a set of twins (3.0).
        val items = ticked()
        if (items.isEmpty()) return
        val files = items.map { it.file }
        when {
            // 3.0: asked first (it was deleted for good at once).
            k == Cleaner.Kind.LEFTOVERS -> Dialogs.confirm(
                this, "Clear Swift's leftovers?",
                "${FileJobs.items(items.size)} (${UrlUtils.formatBytes(items.sumOf { it.size })}) " + (if (items.size == 1) "is" else "are") +
                    " deleted for good. Swift makes its pictures and copies again when it needs them.",
                "Clear", danger = true,
            ) { access.withChanges(files.filter { Volumes.of(this, it) != null }) { clearLeftovers(items) } }
            // Only folders still without a file below them (something may have been put there since the walk).
            k == Cleaner.Kind.EMPTY -> {
                val still = items.filter { it.file.isDirectory && com.swiftbrowser.files.FileOps.measure(listOf(it.file)).files == 0 }
                if (still.isEmpty()) {
                    markGone(items.map { it.file.path }.toSet())
                    refresh()
                } else {
                    // 3.0: asked first.
                    Dialogs.confirm(
                        this, if (still.size == 1) "Delete 1 empty folder?" else "Delete ${still.size} empty folders?",
                        (if (still.size == 1) "It holds" else "They hold") + " nothing, so nothing is lost. Swift can't bring " + (if (still.size == 1) "it" else "them") + " back.",
                        "Delete", danger = true,
                    ) { access.withChanges(still.map { it.file }) { erase(still) } }
                }
            }
            forever || !FilesSettings.useRecycleBin -> Dialogs.confirm(
                this, if (items.size == 1) "Delete “${files[0].name}” for good?" else "Delete ${items.size} files for good?",
                "${UrlUtils.formatBytes(items.sumOf { it.size })} is freed now. Swift can't bring " + (if (items.size == 1) "it" else "them") + " back.",
                "Delete forever", danger = true,
            ) { access.withChanges(files) { erase(items) } }
            // 3.0: the bin at a card's top may need the card's access even when the files don't (Swift's own folder there).
            else -> access.withChanges(files + RecycleBin.binsFor(this, files)) { toBin(items) }
        }
    }

    private fun toBin(items: List<Cleaner.Found>) {
        showWorking("Moving to the recycle bin…")
        RecycleBin.move(this, items.map { it.file }) { b ->
            if (isDestroyed) return@move
            doneWorking()
            markGone(items.filter { !it.file.exists() }.map { it.file.path }.toSet())
            refresh()
            if (b.items.isNotEmpty()) {
                say("Moved ${RecycleBin.movedText(b.items)} to the recycle bin", "Undo") {
                    RecycleBin.restore(this, b.items) { back, _ ->
                        val paths = back.map { it.path }.toSet()
                        results?.lists?.values?.forEach { l -> l.forEach { if (it.file.path in paths) it.gone = false } }
                        refresh()
                        // 3.0: put back under another name (the old one was taken meanwhile): said.
                        RecycleBin.renamedText(b.items, back)?.let { say(it) }
                    }
                }
            }
            if (b.failed.isNotEmpty()) {
                val failed = items.filter { f -> b.failed.any { it.path == f.file.path } }
                Dialogs.confirm(this, "Delete for good?", RecycleBin.couldntText(b.failed) + " Swift can't bring them back once deleted.", "Delete", danger = true) {
                    erase(failed)
                }
            }
        }
    }

    private fun erase(items: List<Cleaner.Found>) {
        val files = items.map { it.file }
        val what = if (files.size == 1) files[0].name else FileJobs.items(files.size)
        showWorking("Deleting…")
        FileJobs.start(this, FileJobs.Kind.DELETE, "Deleting $what", onDone = done@{ job ->
            if (isDestroyed) return@done
            doneWorking()
            markGone(items.filter { !it.file.exists() }.map { it.file.path }.toSet())
            refresh()
            job.message?.takeIf { !job.cancelled }?.let { say(it) }
        }) { w -> Transfers.delete(w, files) }
    }

    /** Swift's own pictures and copies (its cache), and unfinished copies on the storage. */
    private fun clearLeftovers(items: List<Cleaner.Found>) {
        val app = applicationContext
        showWorking("Clearing…")
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                var freed = 0L
                // 3.0: an unfinished copy a file job started meanwhile is being written: it stays.
                val jobsIdle = FileJobs.active.none { it.kind != FileJobs.Kind.OPEN }
                for (f in items) {
                    val file = f.file
                    if (Parts.isPart(file.name) && (!jobsIdle || Parts.isLive(file))) continue
                    val ok = when {
                        file.path == File(app.cacheDir, "doc_thumbs").path -> {
                            DocThumbs.clear(app)
                            true
                        }
                        Volumes.of(app, file) != null && !file.path.contains("/Android/data/") ->
                            Volumes.accessFor(app, file)?.let { a -> runCatching { a.delete(file) }.getOrDefault(false) } == true
                        else -> file.deleteRecursively()
                    }
                    if (ok) freed += f.size
                }
                freed
            }
            doneWorking()
            items.forEach { it.gone = true }
            refresh()
            say("Cleared ${UrlUtils.formatBytes(bytes)}")
        }
    }

    private fun markGone(paths: Set<String>) {
        results?.lists?.values?.forEach { l -> l.forEach { if (it.file.path in paths) it.gone = true } }
    }

    /** The list on show again after a change (the summary when nothing is left in it). */
    private fun refresh() {
        val k = shown
        if (k == null) showSummary() else if (results?.of(k).isNullOrEmpty()) showSummary() else showList(k, removals = true)
        showSpace()
    }

    private fun showWorking(text: String) {
        working?.dismiss()
        working = Messages.sticky(this, text, bottomOffset = if (bar.visibility == View.VISIBLE) bar.height else 0)
    }

    private fun doneWorking() {
        working?.dismiss()
        working = null
    }

    private fun open(f: Cleaner.Found) {
        if (f.isDir) {
            // 3.0: Files shows nothing of Swift's own data (its leftovers inside itself, such as pictures of documents).
            if (Volumes.of(this, f.file) == null) {
                say("Swift keeps these inside itself, so Files can't show them. Clearing them is safe: Swift makes them again when needed.")
                return
            }
            FilesActivity.open(this, f.file)
            return
        }
        if (FileOpener.open(this, f.file)) finish()
    }

    // ------------------------------------------------------------------ the list's rows

    // 3.0.5: the same short date as Downloads and Files.
    private val dates by lazy { ShortDates(this) }

    private fun dateText(t: Long): String? = dates.text(t)

    private fun subtitleOf(f: Cleaner.Found): String {
        val parts = ArrayList<String>(4)
        if (f.size > 0) parts += UrlUtils.formatBytes(f.size)
        if (!f.note.isNullOrEmpty()) parts += f.note
        if (shown != Cleaner.Kind.LEFTOVERS || !f.isDir) {
            dateText(f.time)?.let { parts += it }
            parts += "in " + com.swiftbrowser.files.FileIndex.placeOf(this, f.file.parentFile)
        }
        return parts.joinToString(" · ")
    }

    private class FoundHolder(root: View, val icon: ImageView, val title: TextView, val subtitle: TextView, val check: ImageView) : RecyclerView.ViewHolder(root) {
        var thumb: FileThumbs.Request? = null

        /** The icon's holder and its ripple, for a file and for a folder (a recycled row keeps them). */
        lateinit var fileHolderBg: android.graphics.drawable.Drawable
        lateinit var folderHolderBg: android.graphics.drawable.Drawable
    }

    private class CardHolder(v: View, val icon: ImageView, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(v)

    private class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Any> = emptyList()
            private set

        /** Shows [l]. With [removals], when it is the list on show less a few things, those close up (as in Files). */
        fun set(l: List<Any>, removals: Boolean = false) {
            val old = items
            items = l
            val gone = old.size - l.size
            if (removals && gone in 1..MAX_ANIMATED && Motion.enabled(this@CleanerActivity)) {
                val removed = ArrayList<Int>(gone)
                var j = 0
                for (i in old.indices) {
                    if (j < l.size && keyOf(old[i]) == keyOf(l[j])) j++ else removed += i
                }
                if (j == l.size && removed.size == gone) {
                    for (p in removed.asReversed()) notifyItemRemoved(p)
                    // A heading says how many copies are left.
                    l.forEachIndexed { i, it -> if (it is GroupHeader) notifyItemChanged(i) }
                    return
                }
            }
            notifyDataSetChanged()
        }

        private fun keyOf(o: Any): Any = when (o) {
            is Cleaner.Found -> o.file.path
            is GroupHeader -> "group:" + o.group
            is Hint -> "hint:" + o.text
            else -> o
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Cleaner.Found -> TYPE_ROW
            is Card -> TYPE_CARD
            is GroupHeader -> TYPE_HEADER
            else -> TYPE_HINT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            TYPE_ROW -> makeRow()
            TYPE_CARD -> makeCard()
            // 3.0.5: the headings line up with the icons of the rows under them, with the one section rhythm.
            TYPE_HEADER -> TextHolder(Ui.text(this@CleanerActivity, Ui.Type.SECTION, "").apply {
                setPadding(dp(Ui.MARGIN), dp(Ui.SECTION_ABOVE), dp(Ui.MARGIN), dp(Ui.SECTION_BELOW))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            else -> TextHolder(Ui.text(this@CleanerActivity, Ui.Type.BODY_SECONDARY, "").apply {
                setPadding(dp(Ui.MARGIN), dp(8), dp(Ui.MARGIN), dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = onBindViewHolder(holder, position, mutableListOf())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            when (val item = items[position]) {
                is Cleaner.Found -> {
                    val h = holder as FoundHolder
                    if (PAYLOAD_TICK in payloads) bindTick(h, item, true) else bindRow(h, item)
                }
                is Card -> bindCard(holder as CardHolder, item)
                is GroupHeader -> (holder as TextHolder).text.text = item.text
                is Hint -> (holder as TextHolder).text.text = item.text
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is FoundHolder) {
                holder.thumb?.cancel()
                holder.thumb = null
                Motion.stopPicture(holder.icon)
            }
        }
    }

    private fun makeRow(): FoundHolder {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ui.MARGIN), dp(8), dp(8), dp(8))
            minimumHeight = dp(Ui.ROW_TWO_LINES)
            setBackgroundResource(RowHolder.selectableBackground(ctx))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val icon = ImageView(ctx).apply {
            clipToOutline = true
            // 3.0.5: the one row icon holder; flat on the page, so in the card colour (the page's a step darker).
            contentDescription = "Open"
        }
        root.addView(icon, LinearLayout.LayoutParams(thumbPx, thumbPx))
        val title = Ui.text(ctx, Ui.Type.BODY, "").apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val subtitle = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 2)
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            addView(title)
            addView(subtitle)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val check = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(check, LinearLayout.LayoutParams(dp(44), dp(44)))
        val h = FoundHolder(root, icon, title, subtitle, check)
        h.fileHolderBg = Ui.ripple(ctx, Ui.shape(ctx, Palette.color(ctx, R.color.card_bg), Ui.R_SMALL), Ui.R_SMALL)
        h.folderHolderBg = Ui.ripple(ctx, Ui.shape(ctx, Ui.accentContainer(ctx), Ui.R_SMALL), Ui.R_SMALL)
        root.setOnClickListener {
            val f = adapter.items.getOrNull(h.bindingAdapterPosition) as? Cleaner.Found ?: return@setOnClickListener
            toggle(f, h.bindingAdapterPosition)
        }
        // The picture opens the file, to see what it is before deleting it.
        icon.setOnClickListener {
            val f = adapter.items.getOrNull(h.bindingAdapterPosition) as? Cleaner.Found ?: return@setOnClickListener
            open(f)
        }
        return h
    }

    private fun bindRow(h: FoundHolder, f: Cleaner.Found) {
        h.title.text = if (shown == Cleaner.Kind.LEFTOVERS && f.isDir) f.note ?: f.file.name else f.file.name
        h.subtitle.text = if (shown == Cleaner.Kind.LEFTOVERS && f.isDir) UrlUtils.formatBytes(f.size) else subtitleOf(f)
        h.thumb?.cancel()
        h.thumb = null
        Motion.stopPicture(h.icon)
        val kind = f.kind
        h.icon.tag = f.file.path
        h.icon.scaleType = ImageView.ScaleType.CENTER
        h.icon.background = if (f.isDir) h.folderHolderBg else h.fileHolderBg
        // The same icons as in Files: a folder on the accent's soft colour, a document with its own mark.
        val doc = if (!f.isDir && kind == FileKind.DOCUMENT) Docs.kindOf(f.file.name, null) else null
        if (doc != null) {
            h.icon.imageTintList = null
            h.icon.setImageDrawable(DocIcon(doc, dp(30)))
        } else {
            h.icon.setImageResource(if (f.isDir) R.drawable.ic_folder else kind.icon)
            h.icon.imageTintList = if (f.isDir) onContainerTint else secondaryTint
        }
        if (!f.isDir && FilesSettings.thumbnails && FileThumbs.has(kind)) {
            val key = f.file.path
            // From memory it comes during this call; one made later comes in softly.
            var binding = true
            h.thumb = FileThumbs.load(this, f.file, f.time, kind, thumbPx) { bmp ->
                if (h.icon.tag != key) return@load
                h.icon.imageTintList = null
                h.icon.scaleType = ImageView.ScaleType.CENTER_CROP
                h.icon.setImageBitmap(bmp)
                if (!binding) Motion.fadePicture(h.icon)
            }
            binding = false
        }
        bindTick(h, f, false)
    }

    private fun bindTick(h: FoundHolder, f: Cleaner.Found, tapped: Boolean) {
        h.check.setImageResource(if (f.ticked) R.drawable.ic_check_box else R.drawable.ic_check_box_blank)
        h.check.imageTintList = if (f.ticked) accentTint else secondaryTint
        if (tapped && f.ticked) Motion.bump(h.check, 1.12f)
        h.itemView.contentDescription = f.file.name + if (f.ticked) ", ticked" else ", not ticked"
    }

    /** A list on the summary: as the Files start page's cards. */
    private fun makeCard(): CardHolder {
        val ctx = this
        val outer = FrameLayout(ctx).apply {
            setPadding(dp(Ui.MARGIN), dp(4), dp(Ui.MARGIN), dp(4))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ui.ROW_INSET), dp(14), dp(12), dp(14))
            // 3.0.5: a plain filled card, no outline (the page behind it is a step darker).
            background = Ui.ripple(ctx, Ui.shape(ctx, Palette.color(ctx, R.color.card_bg), Ui.R_MEDIUM), Ui.R_MEDIUM)
            Ui.pressable(this, Motion.PRESS_CARD)
        }
        val icon = Ui.iconBadge(ctx, R.drawable.ic_folder)
        card.addView(icon, LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)).apply { marginEnd = dp(14) })
        val title = Ui.text(ctx, Ui.Type.SUBTITLE, "", lines = 1)
        val detail = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1)
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(detail)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(ImageView(ctx).apply {
            setImageDrawable(Ui.icon(ctx, R.drawable.ic_chevron_right, Ui.textSecondary(ctx)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        outer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val h = CardHolder(outer, icon, title, detail)
        card.setOnClickListener { (adapter.items.getOrNull(h.bindingAdapterPosition) as? Card)?.let { showList(it.kind, Motion.FORWARD) } }
        return h
    }

    private fun bindCard(h: CardHolder, c: Card) {
        h.icon.setImageDrawable(Ui.icon(this, c.kind.icon, Ui.onAccentContainer(this)))
        h.title.text = c.kind.title
        h.detail.text = when {
            c.count == 0 -> c.kind.nothing
            c.kind == Cleaner.Kind.EMPTY -> if (c.count == 1) "1 folder" else "${c.count} folders"
            c.kind == Cleaner.Kind.DUPLICATES -> "${c.count} files · ${UrlUtils.formatBytes(c.bytes)}"
            else -> "${FileJobs.items(c.count)} · ${UrlUtils.formatBytes(c.bytes)}"
        }
        (h.title.parent.parent as View).contentDescription = "${c.kind.title}, ${h.detail.text}"
    }

    companion object {
        private const val STATE_LIST = "list"
        private const val MENU_TICK = 1
        private const val TYPE_ROW = 0
        private const val TYPE_CARD = 1
        private const val TYPE_HEADER = 2
        private const val TYPE_HINT = 3
        private const val PAYLOAD_TICK = "tick"

        /** More rows than this removed at once show anew instead of closing up one by one. */
        private const val MAX_ANIMATED = 30

        /** What the last walk found, kept while Swift runs (the screen turned sideways keeps it). */
        private var lastResults: Cleaner.Results? = null

        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, CleanerActivity::class.java))
        }
    }
}
