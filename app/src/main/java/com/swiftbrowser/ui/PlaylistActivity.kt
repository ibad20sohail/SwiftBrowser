package com.swiftbrowser.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.R
import com.swiftbrowser.files.FileIndex
import com.swiftbrowser.media.PlaylistRules
import com.swiftbrowser.media.Playlists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 3.0.11: one of the user's playlists (Playlists): its songs in their order, under its name, with Play
 * and Shuffle at the top; a tap plays from that song. Hold a song to move it (the others make way, as
 * tabs do in the tab list), swipe it away or ⋮ → Remove to take it out, with Undo. Songs moved or
 * deleted since show greyed as missing and are left out when it plays. Add songs picks them in Files
 * (its picker, at Music); ⋮ at the top renames or deletes the playlist. The songs themselves are never
 * touched.
 */
class PlaylistActivity : ListActivity() {
    /** A song as its row shows it: [place] is where it is ("Music › Album"), [missing] that it's gone. */
    private class Song(val path: String, val title: String, val place: String, val missing: Boolean)

    private var id = ""
    private var name = ""
    private var paths: List<String> = emptyList()
    private val adapter = SongAdapter()
    private lateinit var buttons: View

    /** Each removal gets its own Undo. */
    private var removals = 0

    /** The playlists' version shown last (-1: nothing yet), and whether the screen was in sight before. */
    private var shownVersion = -1
    private var seen = false

    override val grouped: Boolean get() = true

    /** Files as the picker (its upload one), at Music: the songs chosen come back here. */
    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val picked = r.data?.takeIf { r.resultCode == RESULT_OK }?.let { FilePick.picked(it) } ?: return@registerForActivityResult
        addPicked(picked.mapNotNull { it.path })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra(EXTRA_ID).orEmpty()
        list.adapter = adapter
        buildButtons()
        buildMenu()
        ItemTouchHelper(Moves()).attachToRecyclerView(list)
        setTitles(Playlists.get(id)?.name.orEmpty(), null)
        Playlists.whenLoaded(this) { show() }
    }

    override fun onStart() {
        super.onStart()
        // Back from elsewhere (Files, the player): songs may have been moved or deleted meanwhile.
        if (seen && shownVersion >= 0) show(force = true)
        seen = true
    }

    // ------------------------------------------------------------------ the screen

    /** Play and Shuffle, under the large title; they stay at the top as the songs scroll. */
    private fun buildButtons() {
        val box = search.parent as View
        val column = box.parent as LinearLayout
        val playButton = Sheets.wideButton(this, "Play") { play(shuffle = false) }.apply {
            setIconResource(R.drawable.ic_play)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        val shuffleButton = Sheets.wideButton(this, "Shuffle", outlined = true) { play(shuffle = true) }.apply {
            setIconResource(R.drawable.ic_shuffle)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Ui.MARGIN), dp(Ui.XS), dp(Ui.MARGIN), dp(Ui.S))
            addView(playButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(Ui.S) })
            addView(shuffleButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        // As wide as the list under it on a turned phone.
        buttons = MaxWidthLayout(this).apply {
            visibility = View.GONE
            addView(row)
        }
        column.addView(Palette.paint(buttons), column.indexOfChild(box) + 1)
    }

    private fun buildMenu() {
        addMenu(MENU_ADD, "Add songs", R.drawable.ic_playlist_add)
        addMenu(MENU_MORE, "More", R.drawable.ic_more)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_ADD -> addSongs()
                MENU_MORE -> moreSheet()
            }
            true
        }
    }

    private fun addMenu(id: Int, title: String, icon: Int) {
        toolbar.menu.add(0, id, id, title).apply {
            setIcon(Ui.icon(this@PlaylistActivity, icon, Ui.text(this@PlaylistActivity)))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            androidx.core.view.MenuItemCompat.setTooltipText(this, title)
        }
    }

    /**
     * The playlist as it is now: its name and songs, which of them are gone (looked at off the main
     * thread) and where each one is. Gone itself (deleted elsewhere): the screen closes.
     */
    private fun show(force: Boolean = false) {
        val p = Playlists.get(id)
        if (p == null) {
            finish()
            return
        }
        if (!force && Playlists.version == shownVersion) return
        shownVersion = Playlists.version
        name = p.name
        paths = p.songs
        if (adapter.items.isEmpty()) setTitles(p.name, PlaylistRules.songsText(p.songs.size))
        val wanted = p.songs
        val app = applicationContext
        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                wanted.map { path ->
                    val f = File(path)
                    Song(path, f.nameWithoutExtension, FileIndex.placeOf(app, f.parentFile), !f.isFile)
                }
            }
            // Changed again meanwhile: that look shows it.
            if (wanted !== paths || isFinishing) return@launch
            render(songs)
        }
    }

    /** [songs] on screen (rows that went or came back move, the rest stay), with the title's count and the top's buttons. */
    private fun render(songs: List<Song>, animated: Boolean = false) {
        if (animated) adapter.submit(songs) else adapter.set(songs)
        val missing = songs.count { it.missing }
        setTitles(name, PlaylistRules.songsText(songs.size, missing))
        buttons.visibility = if (songs.any { !it.missing }) View.VISIBLE else View.GONE
        if (songs.isEmpty()) showNoSongs() else empty.visibility = View.GONE
        // Once: how its songs move and go.
        if (songs.size >= 2 && !Playlists.hintShown) {
            Playlists.hintShown = true
            Messages.show(this, "Hold a song to move it, or swipe it away to take it out", long = true)
        }
    }

    /** No songs (a new playlist): what to do, and the button that does it. */
    private fun showNoSongs() {
        empty.removeAllViews()
        val state = Ui.emptyState(this, R.drawable.ic_queue_music, "No songs yet", "Choose songs from your music to play them in the order you like.")
        state.addView(Palette.paint(MaterialButton(this).apply {
            text = "Add songs"
            setOnClickListener { addSongs() }
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        empty.addView(state, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (empty.visibility != View.VISIBLE) Motion.fadeIn(empty, fromDy = 12)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun say(text: String, action: String? = null, onAction: (() -> Unit)? = null) = Messages.show(this, text, action, onAction = onAction)

    // ------------------------------------------------------------------ playing

    private fun playlist(): Playlists.Playlist? = Playlists.get(id)

    private fun play(shuffle: Boolean, from: Int = -1) {
        val p = playlist() ?: return
        PlaylistUi.play(this, p, shuffle, from) { text, action, onAction -> say(text, action, onAction) }
    }

    private fun tapped(s: Song) {
        if (s.missing) {
            say("This song was moved or deleted", "Remove") { remove(s) }
            return
        }
        play(shuffle = false, from = paths.indexOf(s.path))
    }

    // ------------------------------------------------------------------ changing it

    /** Files' picker, at Music: hold songs there to choose several. */
    private fun addSongs() {
        try {
            pick.launch(FilePick.intent(this, arrayOf("audio/*"), multiple = true).putExtra(FilesActivity.EXTRA_FOLDER, FilesActivity.categoryPlace(FileIndex.Category.MUSIC)))
        } catch (e: Exception) {
            say("Files couldn't be opened")
        }
    }

    private fun addPicked(chosen: List<String>) {
        val songs = chosen.filter { PlaylistUi.isSong(File(it).name) }
        if (songs.isEmpty()) {
            if (chosen.isNotEmpty()) say("Only songs go in a playlist")
            return
        }
        val added = Playlists.add(this, id, songs)
        if (added < 0) return
        say(PlaylistRules.addedText(name, songs.size, added))
        show()
    }

    /** Takes [s] out (the file stays), with Undo; it goes at once, so nothing waits to be saved. */
    private fun remove(s: Song) {
        val at = paths.indexOf(s.path)
        if (at < 0) return
        removeLater(
            "song${removals++}", "Removed from the playlist",
            hide = { change(paths - s.path) },
            restore = { change(PlaylistRules.putBack(paths, s.path, at), back = listOf(s)) },
            commit = {},
        )
    }

    /** The songs gone since they were added, out together (with Undo). */
    private fun removeMissing() {
        val gone = adapter.items.filter { it.missing }
        if (gone.isEmpty()) return
        val out = gone.map { it.path }.toHashSet()
        val before = paths
        removeLater(
            "song${removals++}", if (gone.size == 1) "Took out 1 missing song" else "Took out ${gone.size} missing songs",
            hide = { change(paths.filter { it !in out }) },
            restore = {
                // Where they were; songs added meanwhile stay, at the end.
                val now = paths.toHashSet()
                val had = before.toHashSet()
                change(before.filter { it in out || it in now } + paths.filter { it !in had }, back = gone)
            },
            commit = {},
        )
    }

    /** The playlist's songs are [songs] now: kept, and the rows that went or came back ([back]) move. */
    private fun change(songs: List<String>, back: List<Song> = emptyList()) {
        Playlists.setSongs(this, id, songs)
        paths = songs
        shownVersion = Playlists.version
        val known = (adapter.items + back).associateBy { it.path }
        val rows = songs.mapNotNull { known[it] }
        render(rows, animated = true)
        // Songs not on screen yet (still being looked at): looked at again with the rest.
        if (rows.size < songs.size) show(force = true)
    }

    private fun moreSheet() {
        val p = playlist() ?: return
        val missing = adapter.items.count { it.missing }
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_playlist_add, "Add songs", primary = true) { addSongs() }
        actions += Sheets.Action(R.drawable.ic_edit, "Rename", primary = true) {
            PlaylistUi.rename(this, p) { renamed ->
                name = renamed.name
                setTitles(renamed.name, toolbar.subtitle)
            }
        }
        if (missing > 0) actions += Sheets.Action(R.drawable.ic_close, if (missing == 1) "Take out the missing song" else "Take out the $missing missing songs") { removeMissing() }
        actions += Sheets.Action(R.drawable.ic_delete, "Delete playlist", danger = true) {
            // 3.0.12: no question first: deleted at once, and the screen this goes back to offers Undo.
            Playlists.delete(this, p.id)?.let { (gone, at) ->
                Messages.later("Deleted the playlist “${gone.name}”", "Undo") { screen ->
                    Playlists.putBack(screen, gone, at)
                    (screen as? FilesActivity)?.playlistsChanged()
                }
            }
            finish()
        }
        Sheets.actions(this, p.name, PlaylistRules.songsText(p.songs.size, missing), actions, iconRes = R.drawable.ic_queue_music)
    }

    private fun songMenu(s: Song) {
        val at = paths.indexOf(s.path)
        val actions = ArrayList<Sheets.Action>()
        if (!s.missing) actions += Sheets.Action(R.drawable.ic_play, "Play", primary = true) { tapped(s) }
        actions += Sheets.Action(R.drawable.ic_close, "Remove from the playlist", primary = true) { remove(s) }
        // Also without dragging (TalkBack, or a long list): a place up or down.
        if (at > 0) actions += Sheets.Action(R.drawable.ic_up, "Move up") { change(PlaylistRules.move(paths, at, at - 1)) }
        if (at in 0 until paths.size - 1) actions += Sheets.Action(R.drawable.ic_down, "Move down") { change(PlaylistRules.move(paths, at, at + 1)) }
        Sheets.actions(this, s.title, if (s.missing) "Moved or deleted" else s.place, actions, iconRes = R.drawable.ic_music_note)
    }

    // ------------------------------------------------------------------ the rows

    /** The songs' rows: grouped pieces (as Bookmarks'), moved by a hold and a drag. */
    private inner class SongAdapter : RecyclerView.Adapter<RowHolder>() {
        var items: List<Song> = emptyList()
            private set

        private val groupAt: (Int) -> Any? = { 0 }

        fun set(songs: List<Song>) {
            items = songs
            notifyDataSetChanged()
        }

        /** Only the rows that went or came back move (a removal or its Undo). */
        fun submit(songs: List<Song>) {
            val old = items
            items = songs
            ListChanges.dispatch(list, this, old.size, songs.size, { o, n -> old[o].path == songs[n].path }, { o, n -> old[o].missing == songs[n].missing && old[o].place == songs[n].place })
        }

        /** A drag moved the row at [from] to [to]: the rows between make way, and their corners follow. */
        fun move(from: Int, to: Int) {
            val next = items.toMutableList()
            next.add(to, next.removeAt(from))
            items = next
            notifyItemMoved(from, to)
            notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1, ListChanges.CORNERS)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(Palette.paint(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))).also { h ->
                GroupedRows.prepare(h.itemView)
                h.keepBackground()
                h.itemView.setOnClickListener { items.getOrNull(h.bindingAdapterPosition)?.let { tapped(it) } }
            }

        override fun onBindViewHolder(h: RowHolder, position: Int) {
            val s = items[position]
            GroupedRows.place(h.itemView, position, items.size, groupAt)
            h.set(R.drawable.ic_music_note, s.title, if (s.missing) "Moved or deleted" else s.place)
            h.setAction(R.drawable.ic_more, "More") { songMenu(s) }
            // Gone: greyed, as something unavailable is.
            val a = if (s.missing) Ui.DISABLED_ALPHA else 1f
            h.icon.alpha = a
            h.title.alpha = a
            h.subtitle.alpha = a
            h.itemView.contentDescription = s.title + if (s.missing) ", moved or deleted" else ""
        }

        override fun onBindViewHolder(h: RowHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == ListChanges.CORNERS }) {
                GroupedRows.place(h.itemView, position, items.size, groupAt)
            } else {
                onBindViewHolder(h, position)
            }
        }
    }

    /**
     * Holding a song lifts it (as a tab held in the tab list) and it follows the finger, the others
     * making way; let go, the order is kept. A swipe sideways takes it out, as rows go elsewhere.
     */
    private inner class Moves : ItemTouchHelper.Callback() {
        private var moved = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (vh.bindingAdapterPosition in adapter.items.indices) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END) else 0

        override fun isLongPressDragEnabled() = true

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0 || from == to) return false
            adapter.move(from, to)
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            adapter.items.getOrNull(vh.bindingAdapterPosition)?.let { remove(it) } ?: adapter.notifyDataSetChanged()
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                moved = false
                Haptics.hold(vh.itemView)
                Motion.anim(vh.itemView, Motion.SHORT).scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).translationZ(Ui.dpf(this@PlaylistActivity, LIFT_DP)).start()
            }
        }

        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, active: Boolean) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) drawSwipe(c, vh, dX)
            super.onChildDraw(c, rv, vh, dX, dY, actionState, active)
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            Motion.anim(vh.itemView, Motion.SHORT).scaleX(1f).scaleY(1f).translationZ(0f).start()
            if (!moved) return
            moved = false
            val order = adapter.items.map { it.path }
            if (order == paths) return
            Playlists.setSongs(this@PlaylistActivity, id, order)
            paths = order
            shownVersion = Playlists.version
        }
    }

    companion object {
        private const val EXTRA_ID = "playlist"
        private const val MENU_ADD = 1
        private const val MENU_MORE = 2

        /** How a held song lifts: as a tab held in the tab list (TabSwitcherView). */
        private const val LIFT_SCALE = 1.05f
        private const val LIFT_DP = 6f

        fun open(from: Context, id: String) {
            val i = Intent(from, PlaylistActivity::class.java).putExtra(EXTRA_ID, id)
            if (from !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(i)
        }
    }
}
