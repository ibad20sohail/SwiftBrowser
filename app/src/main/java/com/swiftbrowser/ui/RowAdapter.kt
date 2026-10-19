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

    fun set(iconRes: Int, title: CharSequence, subtitle: CharSequence?) {
        icon.setImageResource(iconRes)
        this.title.text = title
        this.subtitle.text = subtitle ?: ""
        this.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        action.visibility = View.GONE
        action.setOnClickListener(null)
        itemView.background = null
        itemView.setBackgroundResource(selectableBackground(itemView.context))
    }

    fun setAction(iconRes: Int, description: String, onClick: () -> Unit) {
        action.visibility = View.VISIBLE
        action.setImageResource(iconRes)
        action.contentDescription = description
        action.setOnClickListener { onClick() }
    }

    companion object {
        fun selectableBackground(ctx: Context): Int {
            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            val res = a.getResourceId(0, 0)
            a.recycle()
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
        RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

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
