package com.swiftbrowser.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.R
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.torrent.TorrentFiles
import com.swiftbrowser.torrent.Torrents
import org.libtorrent4j.TorrentInfo
import java.io.File

/**
 * 2.9.2: the panel that adds a torrent (a magnet link tapped on a page or copied, a .torrent file
 * downloaded or opened from another app), and the one that changes a torrent's files later. It
 * lists the files with tick boxes (junk unticked), warns about Windows programs and 10-bit videos,
 * and checks there is room for what is ticked.
 */
object TorrentSheet {
    /** Most rows a panel lists: in a huge pack the files after these keep their choice. */
    private const val MAX_ROWS = 400

    /** From this many rows, "All files" sets the tick boxes at once instead of animating every one. */
    private const val MANY_ROWS = 12

    /**
     * A magnet link: its file list is asked for first (not while Swift's VPN is on). 3.0: only after
     * a tap on "Look up the files" (asking announces the phone's address to the people sharing it,
     * and a page could open this panel without a tap). [incognito]: from an incognito tab, which the
     * panel warns about: torrents are kept like other downloads.
     */
    fun magnet(a: Activity, link: String, incognito: Boolean = false, added: ((Downloads.Item) -> Unit)? = null) {
        val hash = TorrentFiles.magnetHash(link)
        if (hash == null) {
            Messages.show(a, "This magnet link isn't valid")
            return
        }
        Downloads.findTorrent(hash)?.let {
            already(a)
            return
        }
        val col = Sheets.column(a)
        val head = Head(a, TorrentFiles.magnetName(link) ?: "Torrent", "Torrent from a magnet link")
        col.addView(head.view)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        val dialog = Sheets.sheet(a, col)
        if (incognito) body.addView(incognitoWarning(a))
        if (Torrents.blockReason() == Torrents.PAUSED_VPN) {
            body.addView(Sheets.note(a, "Swift's VPN is on, and torrents only download with it off. Add this one now and it starts when you turn the VPN off. Its files are chosen for you then: all but the junk."))
            body.addView(button(a, "Add to downloads") {
                dialog.dismiss()
                done(a, Torrents.add(link, null, null), added)
            })
            dialog.showImmersive()
            return
        }
        var open = true
        dialog.setOnDismissListener { open = false }
        body.addView(Sheets.note(a, "Without the VPN, others sharing this torrent can see your internet address."))
        body.addView(button(a, "Look up the files") {
            body.removeAllViews()
            if (incognito) body.addView(incognitoWarning(a))
            lookUp(a, link, dialog, head, body, { open }, incognito, added)
            fadeInBody(body)
        })
        dialog.showImmersive()
    }

    /** The file list of [link], asked of the people sharing it (a few seconds, up to a minute). */
    private fun lookUp(
        a: Activity, link: String, dialog: BottomSheetDialog, head: Head, body: LinearLayout, open: () -> Boolean,
        incognito: Boolean, added: ((Downloads.Item) -> Unit)?,
    ) {
        body.addView(progressRow(a, "Getting the file list from the people sharing it…"))
        Torrents.fetchFiles(link) { bytes ->
            if (!open() || a.isFinishing) return@fetchFiles
            body.removeAllViews()
            if (incognito) body.addView(incognitoWarning(a))
            showFetched(a, link, bytes, dialog, head, body, added)
            fadeInBody(body)
        }
    }

    /**
     * What is in [body] now comes in with a fade once it is laid out: only then is its height known,
     * and a list taller than the screen must not go through a layer (the GPU can't hold one that big).
     */
    private fun fadeInBody(body: View) {
        body.alpha = 0f
        body.doOnNextLayout { Motion.fadeIn(it, fromDy = 8) }
    }

    private fun showFetched(
        a: Activity, link: String, bytes: ByteArray?, dialog: BottomSheetDialog, head: Head, body: LinearLayout,
        added: ((Downloads.Item) -> Unit)?,
    ) {
        val ti = bytes?.let { runCatching { TorrentInfo.bdecode(it) }.getOrNull() }
        if (ti == null) {
            body.addView(Sheets.note(a, "No one sharing this torrent answered within a minute. You can add it anyway: it starts once someone shares it."))
            body.addView(button(a, "Add anyway") {
                dialog.dismiss()
                done(a, Torrents.add(link, null, null), added)
            })
            return
        }
        files(a, dialog, head, body, ti, null) { wanted ->
            done(a, Torrents.add(link, bytes, wanted), added)
        }
    }

    /** A .torrent file's [data]; 3.0: [incognito] as for [magnet]. */
    fun file(a: Activity, data: ByteArray, incognito: Boolean = false, added: ((Downloads.Item) -> Unit)? = null) {
        val ti = runCatching { TorrentInfo.bdecode(data) }.getOrNull()
        if (ti == null || !ti.isValid) {
            Messages.show(a, "This isn't a torrent file Swift can read")
            return
        }
        if (Downloads.findTorrent(Torrents.hashOf(ti)) != null) {
            already(a)
            return
        }
        val col = Sheets.column(a)
        val head = Head(a, ti.name(), "Torrent")
        col.addView(head.view)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        val dialog = Sheets.sheet(a, col)
        if (incognito) body.addView(incognitoWarning(a))
        files(a, dialog, head, body, ti, null) { wanted -> done(a, Torrents.add(null, data, wanted), added) }
        dialog.showImmersive()
    }

    /**
     * 3.0: torrents take hours and go on after Swift restarts, so one from an incognito tab is kept
     * like any other download: said before it is added, not found out afterwards.
     */
    private fun incognitoWarning(a: Activity): View =
        warning(a, "This tab is incognito, but torrents aren't: this one stays in your Downloads list until you remove it.")

    /** Another choice of files for [item] (Files, in its menu in Downloads). */
    fun chooseFiles(a: Activity, item: Downloads.Item) {
        val ti = Torrents.infoOf(item)
        if (ti == null) {
            Messages.show(a, "Its file list isn't known yet. Try again once it has started.")
            return
        }
        val col = Sheets.column(a)
        val head = Head(a, item.fileName, "Choose the files to download")
        col.addView(head.view)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        val dialog = Sheets.sheet(a, col)
        files(a, dialog, head, body, ti, item) { wanted -> Torrents.setFiles(item, wanted) }
        dialog.showImmersive()
    }

    private fun done(a: Activity, item: Downloads.Item?, added: ((Downloads.Item) -> Unit)?) {
        when {
            item == null -> Messages.show(a, "Couldn't add this torrent")
            added != null -> added(item)
            else -> Messages.show(a, "Added to your downloads")
        }
    }

    private fun already(a: Activity) {
        if (a is DownloadsActivity) {
            Messages.show(a, "It's already in your downloads")
            return
        }
        Messages.show(a, "It's already in your downloads", action = "Show") {
            a.startActivity(Intent(a, DownloadsActivity::class.java))
        }
    }

    /** The name, and a line under it that follows the choice. */
    private class Head(a: Activity, title: String, sub: String) {
        val subView: TextView = Ui.text(a, Ui.Type.BODY_SECONDARY, sub).apply { setPadding(0, Ui.dp(a, 4), 0, 0) }
        val view: View = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 24), Ui.dp(a, 10), Ui.dp(a, 24), Ui.dp(a, 8))
            addView(Ui.text(a, Ui.Type.TITLE, title, lines = 3))
            addView(subView)
        }
    }

    /**
     * The files of [ti] with tick boxes: those of [item] as chosen, else all but the junk. [save]
     * gets the ticked files' indexes.
     */
    private fun files(
        a: Activity, dialog: BottomSheetDialog, head: Head, body: LinearLayout, ti: TorrentInfo, item: Downloads.Item?,
        save: (Set<Int>) -> Unit,
    ) {
        val entries = TorrentFiles.list(ti)
        val before = item?.let { Torrents.wantedOf(it, ti) }
        val ticked = HashSet(before ?: entries.filter { !it.junk }.map { it.index })
        val totalSize = entries.sumOf { it.size }
        head.subView.text = "${UrlUtils.formatBytes(totalSize)} · " + if (entries.size == 1) "1 file" else "${entries.size} files"

        // Warnings first, where they are seen.
        val programs = entries.filter { it.program }
        if (programs.isNotEmpty()) {
            val names = programs.take(2).joinToString(", ") { it.name } + if (programs.size > 2) " and ${programs.size - 2} more" else ""
            body.addView(warning(a, "It has a Windows program ($names). Programs can't run on a phone, and in a torrent they are often malware, so they are left unticked."))
        }
        if (!TorrentFiles.phonePlays10Bit && (TorrentFiles.looks10Bit(ti.name()) || entries.any { it.video && TorrentFiles.looks10Bit(it.name) })) {
            body.addView(warning(a, "Its videos look like 10-bit HEVC (the name says so). Your phone's chip can't play that kind of video: look for an x264 or 8-bit version instead."))
        }

        val boxes = ArrayList<Pair<TorrentFiles.Entry, CheckBox>>()
        val space = Ui.steadyFigures(Ui.text(a, Ui.Type.BODY_SECONDARY, "").apply { setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), 0) })
        val go = button(a, "") {}.also { Ui.steadyFigures(it) }
        var allBox: CheckBox? = null
        var quiet = false
        val free = freeSpace()

        fun refresh() {
            val chosen = entries.filter { it.index in ticked }
            val size = chosen.sumOf { it.size }
            val already = before?.let { b -> entries.filter { it.index in b }.sumOf { it.size } } ?: 0L
            val needed = (size - already).coerceAtLeast(0)
            // 3.0: the rule that pauses a torrent for space (100 MB to spare), not "just enough".
            val enough = free < 0 || needed == 0L || Torrents.roomFor(needed, free)
            space.text = when {
                chosen.isEmpty() -> "Tick the files to download"
                !enough -> "Not enough space: it needs ${UrlUtils.formatBytes(needed)} and some to spare, and the phone has ${UrlUtils.formatBytes(free)} free"
                free >= 0 -> "${UrlUtils.formatBytes(size)} of ${UrlUtils.formatBytes(totalSize)} · ${UrlUtils.formatBytes(free)} free on the phone"
                else -> "${UrlUtils.formatBytes(size)} of ${UrlUtils.formatBytes(totalSize)}"
            }
            space.setTextColor(if (!enough) Ui.danger(a) else Ui.textSecondary(a))
            go.isEnabled = chosen.isNotEmpty() && enough && (before == null || ticked != before)
            go.text = when {
                before != null -> "Save"
                chosen.isEmpty() -> "Download"
                else -> "Download · ${UrlUtils.formatBytes(size)}"
            }
            quiet = true
            allBox?.isChecked = ticked.size == entries.size
            quiet = false
        }

        if (entries.size > 1) {
            body.addView(Sheets.label(a, "Files"))
            body.addView(row(a, "All files", null, ticked.size == entries.size) { on ->
                if (quiet) return@row
                if (on) entries.forEach { ticked += it.index } else ticked.clear()
                quiet = true
                boxes.forEach { (e, b) ->
                    b.isChecked = e.index in ticked
                    if (boxes.size >= MANY_ROWS) b.jumpDrawablesToCurrentState()
                }
                quiet = false
                refresh()
            }.also { allBox = it.second }.first)
        }
        val top = ti.name()
        for (e in entries.take(MAX_ROWS)) {
            // Where it is inside the torrent's own folder, when not at its top.
            val folder = e.path.removePrefix("$top/").substringBeforeLast('/', "").replace("/", " › ")
            val caption = UrlUtils.formatBytes(e.size) + if (folder.isNotEmpty()) " · in $folder" else ""
            val (view, box) = row(a, e.name, caption, e.index in ticked) { on ->
                if (quiet) return@row
                if (on) ticked += e.index else ticked -= e.index
                refresh()
            }
            boxes += e to box
            body.addView(view)
        }
        if (entries.size > MAX_ROWS) body.addView(Sheets.note(a, "And ${entries.size - MAX_ROWS} more files, chosen like the ones above."))
        body.addView(space)
        go.setOnClickListener {
            dialog.dismiss()
            save(HashSet(ticked))
        }
        body.addView(go)
        refresh()
    }

    /** Free space where torrents are saved, or -1 if Android doesn't say. */
    private fun freeSpace(): Long {
        var d: File? = Torrents.saveDir()
        while (d != null && !d.exists()) d = d.parentFile
        return try {
            StatFs((d ?: return -1).path).availableBytes
        } catch (_: Exception) {
            -1
        }
    }

    /** A file's row: a tick box, its name and a line under it. */
    private fun row(a: Activity, name: String, caption: String?, on: Boolean, change: (Boolean) -> Unit): Pair<View, CheckBox> {
        val box = CheckBox(a).apply {
            isChecked = on
            buttonTintList = ColorStateList.valueOf(Ui.accent(a))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnCheckedChangeListener { _, c -> change(c) }
        }
        val view = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(a, 52)
            setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 20), 0)
            setBackgroundResource(RowHolder.selectableBackground(a))
            contentDescription = if (caption != null) "$name, $caption" else name
            setOnClickListener { box.isChecked = !box.isChecked }
            // 3.0: TalkBack hears the row as a tick box, ticked or not (the box itself is hidden from it).
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = CheckBox::class.java.name
                    info.isCheckable = true
                    info.isChecked = box.isChecked
                }
            })
            addView(box)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(a, 8), Ui.dp(a, 6), 0, Ui.dp(a, 6))
                addView(Ui.text(a, Ui.Type.BODY, name, lines = 2))
                if (caption != null) addView(Ui.text(a, Ui.Type.CAPTION, caption, lines = 1))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return view to box
    }

    private fun warning(a: Activity, text: String): View = Ui.noteBox(a, R.drawable.ic_error, text, Palette.color(a, R.color.danger_container), Ui.danger(a)).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(a, 24), Ui.dp(a, 6), Ui.dp(a, 24), Ui.dp(a, 6))
        }
    }

    private fun progressRow(a: Activity, text: String): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 16))
        // 3.0.4: Swift's loading indicator, in the accent.
        addView(LoadingIndicator(a), LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)))
        addView(Ui.text(a, Ui.Type.BODY, text).apply { setPadding(Ui.dp(a, 16), 0, 0, 0) })
    }

    /** The wide button at the bottom. */
    private fun button(a: Activity, text: String, onClick: () -> Unit): MaterialButton = Sheets.wideButton(a, text, onClick = onClick)
}
