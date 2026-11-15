package com.swiftbrowser.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.swiftbrowser.R
import com.swiftbrowser.media.PlaylistRules
import com.swiftbrowser.media.Playlists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 3.0.11: the user's playlists (Playlists) on screen: Add to playlist (a sheet in Files, the player's
 * own panel on its music screen), a playlist's ⋮, Play, and the playlists' rows at the top of Files'
 * Music category, grouped as the start page's Tools are.
 */
object PlaylistUi {
    /** How a screen says how something went: a message, maybe with a button ([action], [onAction]). */
    fun interface Say {
        fun say(text: String, action: String?, onAction: (() -> Unit)?)
    }

    /** A song Swift's player plays (the only kind a playlist holds). */
    fun isSong(name: String): Boolean = PlayerActivity.kindOf(name) == PlayerActivity.Kind.AUDIO

    /** The playlists in the order they show: by name, as people read them. */
    fun sorted(lists: List<Playlists.Playlist>): List<Playlists.Playlist> = lists.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    // ------------------------------------------------------------------ Add to playlist

    /**
     * The choice of a playlist for [songs]: a row for each (ticked when it has them all already), then
     * New playlist. [ctx] gives the look (a sheet's, or the player's panel's); [close] puts it away as
     * a choice is made; [done] with what to say and the playlist the songs went in.
     */
    fun chooser(a: Activity, ctx: Context, songs: List<File>, lists: List<Playlists.Playlist>, close: () -> Unit, done: (String, Playlists.Playlist) -> Unit): View {
        val paths = songs.map { it.path }
        val col = Sheets.column(ctx)
        col.addView(Sheets.header(ctx, "Add to playlist", if (songs.size == 1) songs[0].nameWithoutExtension else PlaylistRules.songsText(songs.size), lines = 2))
        for (p in sorted(lists)) {
            val has = p.songs.toHashSet().let { set -> paths.all { it in set } }
            col.addView(Sheets.row(ctx, R.drawable.ic_queue_music, p.name) {
                close()
                val added = Playlists.add(a, p.id, paths)
                done(PlaylistRules.addedText(p.name, paths.size, added), p)
            }.apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                // The songs are in it already: a tick at its end, as the player's choices have.
                if (has) setCompoundDrawablesRelativeWithIntrinsicBounds(compoundDrawablesRelative[0], null, Ui.icon(ctx, R.drawable.ic_check, Ui.accent(ctx)), null)
            })
        }
        col.addView(Sheets.row(ctx, R.drawable.ic_add, "New playlist") {
            close()
            Sheets.askText(a, "New playlist", "Name", PlaylistRules.freshName(lists.map { it.name })) { name ->
                val p = Playlists.create(a, name, paths)
                done(PlaylistRules.madeText(p.name, p.songs.size), p)
            }
        })
        return col
    }

    /** Files: [songs] into a playlist chosen in a sheet; [say] tells how it went (with Open), [changed] once they're in. */
    fun addFromFiles(a: AppCompatActivity, songs: List<File>, say: Say, changed: () -> Unit) {
        val playable = songs.filter { isSong(it.name) }
        if (playable.isEmpty()) return
        Playlists.whenLoaded(a) { lists ->
            if (a.isFinishing) return@whenLoaded
            lateinit var dialog: BottomSheetDialog
            val content = chooser(a, a, playable, lists, close = { dialog.dismiss() }) { text, p ->
                changed()
                say.say(text, "Open") { PlaylistActivity.open(a, p.id) }
            }
            dialog = Sheets.sheet(a, content)
            dialog.showImmersive()
        }
    }

    // ------------------------------------------------------------------ a playlist

    /** A playlist's row tapped: its screen; New playlist asks for a name, then shows the new playlist (empty, to fill). */
    fun open(a: AppCompatActivity, row: Row, changed: () -> Unit) {
        when (row) {
            is ListRow -> PlaylistActivity.open(a, row.list.id)
            NewRow -> Playlists.whenLoaded(a) { lists ->
                if (a.isFinishing) return@whenLoaded
                Sheets.askText(a, "New playlist", "Name", PlaylistRules.freshName(lists.map { it.name })) { name ->
                    val p = Playlists.create(a, name, emptyList())
                    changed()
                    PlaylistActivity.open(a, p.id)
                }
            }
        }
    }

    /** A playlist's ⋮ (and hold) in Files: Play, Shuffle, Rename, and Delete with Undo (its songs stay). */
    fun menu(a: AppCompatActivity, p: Playlists.Playlist, say: Say, changed: () -> Unit) {
        val actions = ArrayList<Sheets.Action>()
        if (p.songs.isNotEmpty()) {
            actions += Sheets.Action(R.drawable.ic_play, "Play", primary = true) { play(a, p, shuffle = false, say = say) }
            actions += Sheets.Action(R.drawable.ic_shuffle, "Shuffle", primary = true) { play(a, p, shuffle = true, say = say) }
        }
        actions += Sheets.Action(R.drawable.ic_edit, "Rename", primary = true) { rename(a, p) { changed() } }
        actions += Sheets.Action(R.drawable.ic_delete, "Delete", primary = true, danger = true) {
            Playlists.delete(a, p.id)?.let { (gone, at) ->
                changed()
                say.say("Deleted the playlist “${gone.name}”", "Undo") {
                    Playlists.putBack(a, gone, at)
                    changed()
                }
            }
        }
        Sheets.actions(a, p.name, PlaylistRules.songsText(p.songs.size), actions, iconRes = R.drawable.ic_queue_music)
    }

    fun rename(a: Activity, p: Playlists.Playlist, done: (Playlists.Playlist) -> Unit) {
        Sheets.askText(a, "Rename playlist", "Name", p.name) { name -> Playlists.rename(a, p.id, name)?.let(done) }
    }

    /**
     * Plays [p] on the music screen in its order from the song at [start] ([shuffle]: in a random order,
     * from a random song unless one was chosen). Songs moved or deleted since are left out (looked at
     * off the main thread).
     */
    fun play(a: AppCompatActivity, p: Playlists.Playlist, shuffle: Boolean, start: Int = -1, say: Say) {
        a.lifecycleScope.launch {
            val (songs, at) = withContext(Dispatchers.IO) { PlaylistRules.playable(p.songs, { File(it).isFile }, start.coerceAtLeast(0)) }
            if (songs.isEmpty()) {
                say.say("Its songs were moved or deleted", null, null)
                return@launch
            }
            val first = if (shuffle && start < 0) kotlin.random.Random.nextInt(songs.size) else at
            PlayerActivity.openList(a, p.name, songs, first, shuffle)
        }
    }

    // ------------------------------------------------------------------ Files' Music category

    /** A row of the playlists in Files' Music category: a playlist, or New playlist after them. */
    sealed class Row {
        abstract val key: String
    }

    class ListRow(val list: Playlists.Playlist) : Row() {
        override val key: String get() = "playlist:" + list.id
    }

    object NewRow : Row() {
        override val key: String get() = "playlist:new"
    }

    class Holder internal constructor(view: View, val badge: ImageView, val title: TextView, val caption: TextView, val more: ImageButton) : RecyclerView.ViewHolder(view) {
        var row: Row? = null
        var icon = 0
    }

    /**
     * A row as the start page's Tools are (a grouped piece: a badge, the name, a caption at its end),
     * with ⋮ as Files' rows have: [open] for a tap, [menu] for ⋮ or a hold.
     */
    fun holder(ctx: Context, open: (Row) -> Unit, menu: (Playlists.Playlist) -> Unit): Holder {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.ROW_ONE_LINE)
            setPadding(0, dp(Ui.XS), dp(Ui.S), dp(Ui.XS))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(Ui.MARGIN)
                rightMargin = dp(Ui.MARGIN)
            }
        }
        GroupedRows.prepare(root)
        val badge = Ui.iconBadge(ctx, R.drawable.ic_queue_music)
        root.addView(badge, LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)).apply { marginEnd = dp(14) })
        val title = Ui.text(ctx, Ui.Type.BODY, "", lines = 1)
        root.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val caption = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1)
        root.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(Ui.M) })
        val more = ImageButton(ctx).apply {
            setImageDrawable(Ui.icon(ctx, R.drawable.ic_more, Ui.textSecondary(ctx)))
            contentDescription = "More"
            background = Ui.rippleBorderless(ctx)
            Ui.pressable(this, Motion.PRESS_ICON, morph = false)
        }
        root.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
        val h = Holder(root, badge, title, caption, more)
        root.setOnClickListener { h.row?.let(open) }
        root.setOnLongClickListener { v ->
            val r = h.row as? ListRow ?: return@setOnLongClickListener false
            Haptics.hold(v)
            menu(r.list)
            true
        }
        more.setOnClickListener { (h.row as? ListRow)?.let { menu(it.list) } }
        return h
    }

    /** [h] shows [row], at [position] of [count] rows where [group] says which are the playlists' (their corners). */
    fun bind(h: Holder, row: Row, position: Int, count: Int, group: (Int) -> Any?) {
        h.row = row
        GroupedRows.place(h.itemView, position, count, group)
        val ctx = h.itemView.context
        val icon = if (row is ListRow) R.drawable.ic_queue_music else R.drawable.ic_add
        if (icon != h.icon) {
            h.icon = icon
            h.badge.setImageDrawable(Ui.icon(ctx, icon, Ui.onAccentContainer(ctx)))
        }
        when (row) {
            is ListRow -> {
                val songs = PlaylistRules.songsText(row.list.songs.size)
                h.title.text = row.list.name
                h.caption.text = songs
                h.caption.visibility = View.VISIBLE
                h.more.visibility = View.VISIBLE
                h.itemView.contentDescription = "Playlist ${row.list.name}, $songs"
            }
            NewRow -> {
                h.title.text = "New playlist"
                h.caption.visibility = View.GONE
                h.more.visibility = View.GONE
                h.itemView.contentDescription = "New playlist"
            }
        }
    }
}
