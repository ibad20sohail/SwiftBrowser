package com.swiftbrowser.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R

/** Views of one res/layout/item_row.xml row. */
class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
    val icon: ImageView = view.findViewById(R.id.row_icon)
    val title: TextView = view.findViewById(R.id.row_title)
    val subtitle: TextView = view.findViewById(R.id.row_subtitle)
    val action: ImageButton = view.findViewById(R.id.row_action)

    /** The ripple this row was given, so it isn't made again on every bind (2.6). */
    private var ripple: android.graphics.drawable.Drawable? = null

    /** The icon's soft badge and padding while a picture fills its place (3.0, see setPicture). */
    private var badge: android.graphics.drawable.Drawable? = null
    private var badgePadding = 0
    private var pictured = false

    /** The subtitle shows two lines (3.0, see setSubtitleLines); binds are counted so a late fit is dropped. */
    private var twoLines = false
    private var binds = 0

    init {
        Ui.pressable(action, Motion.PRESS_ICON, morph = false)
    }

    fun set(iconRes: Int, title: CharSequence, subtitle: CharSequence?) {
        binds++
        icon.tag = null
        // A recycled row that showed a picture gets its badge back.
        if (pictured) {
            icon.background = badge
            icon.setPadding(badgePadding, badgePadding, badgePadding, badgePadding)
            pictured = false
        }
        icon.setImageResource(iconRes)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(Palette.color(icon.context, R.color.text_secondary))
        // 3.0.5: a plain icon at full strength, also in a recycled row that showed a site's icon.
        SoftPictures.apply(icon, false)
        this.title.text = title
        if (twoLines) {
            this.subtitle.setSingleLine(true)
            this.subtitle.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            twoLines = false
        }
        this.subtitle.text = subtitle ?: ""
        this.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        action.visibility = View.GONE
        action.setOnClickListener(null)
        // Some lists mark a row with another background (the current tab): back to the ripple then.
        if (ripple == null || itemView.background !== ripple) {
            itemView.setBackgroundResource(selectableBackground(itemView.context))
            ripple = itemView.background
        }
    }

    /** The site's own icon in place of the row's icon, when Swift has it (it may come a moment later). */
    fun setFavicon(url: String) {
        icon.tag = url
        com.swiftbrowser.core.Favicons.load(url) { bmp ->
            if (bmp != null && icon.tag == url) {
                icon.imageTintList = null
                icon.setImageBitmap(bmp)
                // 3.0.5: a little dimmer in the dark look.
                SoftPictures.apply(icon)
            }
        }
    }

    /** A picture filling the icon's place without the soft badge behind it (3.0: a Google account's photo). */
    fun setPicture(picture: android.graphics.drawable.Drawable) {
        if (!pictured) {
            badge = icon.background
            badgePadding = icon.paddingLeft
            pictured = true
        }
        icon.tag = null
        icon.background = null
        icon.setPadding(0, 0, 0, 0)
        icon.imageTintList = null
        icon.setImageDrawable(picture)
    }

    /**
     * Two lines under the title (3.0, profiles: the Google account, then the tabs). The first is
     * shortened in the middle to fit its line, so the second always shows.
     */
    fun setSubtitleLines(first: CharSequence, second: CharSequence) {
        if (!twoLines) {
            subtitle.setSingleLine(false)
            subtitle.maxLines = 2
            subtitle.ellipsize = android.text.TextUtils.TruncateAt.END
            twoLines = true
        }
        subtitle.visibility = View.VISIBLE
        val bind = binds
        val fit = {
            val width = subtitle.width - subtitle.totalPaddingLeft - subtitle.totalPaddingRight
            val line = if (width > 0) android.text.TextUtils.ellipsize(first, subtitle.paint, width.toFloat(), android.text.TextUtils.TruncateAt.MIDDLE) else first
            subtitle.text = android.text.TextUtils.concat(line, "\n", second)
        }
        fit()
        // A new row has no width yet: fitted again once it's laid out, unless it was bound again meanwhile.
        if (subtitle.width == 0) subtitle.post { if (binds == bind && twoLines) fit() }
    }

    fun setAction(iconRes: Int, description: String, onClick: () -> Unit) {
        action.visibility = View.VISIBLE
        action.setImageResource(iconRes)
        action.contentDescription = description
        action.setOnClickListener { onClick() }
    }

    /**
     * 3.0.4: the row's background as it is now (a grouped list's rounded piece, GroupedRows) stays
     * through [set], as the ripple does for other lists' rows.
     */
    fun keepBackground() {
        ripple = itemView.background
    }

    companion object {
        private var selectable = 0

        /** The theme's ripple for rows (the same resource in every screen of Swift, looked up once). */
        fun selectableBackground(ctx: Context): Int {
            if (selectable != 0) return selectable
            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            val res = a.getResourceId(0, 0)
            a.recycle()
            selectable = res
            return res
        }
    }
}

/**
 * A RecyclerView adapter over a plain list, binding each item to an item_row. 3.0.4: with
 * [groupOf], a grouped list: rows of the same group (what it says for them) that are next to each
 * other sit together as rounded pieces (GroupedRows).
 */
class RowAdapter<T>(
    private val bind: (RowHolder, T) -> Unit,
    private val onClick: (T) -> Unit,
    private val onLongClick: ((T) -> Unit)? = null,
    private val groupOf: ((T) -> Any)? = null,
) : RecyclerView.Adapter<RowHolder>() {
    private var rows: List<T> = emptyList()

    var items: List<T>
        get() = rows
        set(value) {
            rows = value
            notifyDataSetChanged()
        }

    /**
     * The list after rows went or came back (a removal, an Undo): only those rows move, and the ones
     * left get their corners again. [same]: whether two items are the same row. A screen asks for
     * this; setting [items] is as it always was.
     */
    fun submit(list: RecyclerView, newItems: List<T>, same: (T, T) -> Boolean) {
        val old = rows
        rows = newItems
        ListChanges.dispatch(list, this, old.size, newItems.size, { o, n -> same(old[o], newItems[n]) })
    }

    /** The group of the item at a position (made once, not on every bind). */
    private val groupAt: (Int) -> Any? = { i -> groupOf?.invoke(items[i]) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(Palette.paint(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))).also {
            if (groupOf != null) {
                GroupedRows.prepare(it.itemView)
                it.keepBackground()
            }
        }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val item = items[position]
        if (groupOf != null) GroupedRows.place(holder.itemView, position, items.size, groupAt)
        bind(holder, item)
        holder.itemView.setOnClickListener { onClick(item) }
        if (onLongClick != null) {
            holder.itemView.setOnLongClickListener {
                onLongClick.invoke(item)
                true
            }
        } else {
            holder.itemView.setOnLongClickListener(null)
        }
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == ListChanges.CORNERS }) {
            if (groupOf != null) GroupedRows.place(holder.itemView, position, rows.size, groupAt)
        } else {
            onBindViewHolder(holder, position)
        }
    }
}

/** A list told exactly which rows went or came back, so that only they animate (Motion.listAnimator). */
object ListChanges {
    /** The payload for rows that stayed: who is first or last may have changed, so their corners and gaps are set again. */
    const val CORNERS = "corners"

    /**
     * Tells [adapter] of [list], whose items have just changed from [oldSize] to [newSize]. [same]
     * and [sameContent] take a position in the old list and one in the new; rows the same in both
     * are only redrawn if [sameContent] says they changed.
     */
    fun dispatch(
        list: RecyclerView, adapter: RecyclerView.Adapter<*>, oldSize: Int, newSize: Int,
        same: (Int, Int) -> Boolean, sameContent: (Int, Int) -> Boolean = { _, _ -> true },
    ) {
        val atTop = !list.canScrollVertically(-1)
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldSize
            override fun getNewListSize() = newSize
            override fun areItemsTheSame(o: Int, n: Int) = same(o, n)
            override fun areContentsTheSame(o: Int, n: Int) = sameContent(o, n)
        }, false).dispatchUpdatesTo(adapter)
        if (newSize > 0) adapter.notifyItemRangeChanged(0, newSize, CORNERS)
        // A row that comes in above the first one would otherwise stay out of sight, over the list's top.
        if (atTop) list.scrollToPosition(0)
    }
}

/** [text] with the search word [query] in bold and [color]: only how it looks (the text itself is unchanged). */
fun markedText(text: String, query: String, color: Int): CharSequence {
    if (query.isEmpty()) return text
    val i = text.indexOf(query, ignoreCase = true)
    if (i < 0) return text
    return android.text.SpannableString(text).apply {
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + query.length, 0)
        setSpan(android.text.style.ForegroundColorSpan(color), i, i + query.length, 0)
    }
}
