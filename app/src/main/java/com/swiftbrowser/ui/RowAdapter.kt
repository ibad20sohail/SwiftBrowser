package com.swiftbrowser.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
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

    fun set(iconRes: Int, title: CharSequence, subtitle: CharSequence?) {
        icon.tag = null
        icon.setImageResource(iconRes)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(Palette.color(icon.context, R.color.text_secondary))
        this.title.text = title
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
            }
        }
    }

    fun setAction(iconRes: Int, description: String, onClick: () -> Unit) {
        action.visibility = View.VISIBLE
        action.setImageResource(iconRes)
        action.contentDescription = description
        action.setOnClickListener { onClick() }
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

/** A RecyclerView adapter over a plain list, binding each item to an item_row. */
class RowAdapter<T>(
    private val bind: (RowHolder, T) -> Unit,
    private val onClick: (T) -> Unit,
    private val onLongClick: ((T) -> Unit)? = null,
) : RecyclerView.Adapter<RowHolder>() {
    var items: List<T> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(Palette.paint(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val item = items[position]
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
}
