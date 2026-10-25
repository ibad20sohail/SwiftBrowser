package com.swiftbrowser.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.R
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.UrlUtils

/**
 * The address bar's suggestions in groups (2.0): what you typed first, then search suggestions,
 * open tabs (to switch to), bookmarks and history, each group under a small heading. Rows show the
 * site's icon and the typed words in bold; the arrow puts a suggestion in the box to keep typing.
 * With the address bar at the bottom the list grows upward, so the best match sits next to the bar.
 */
class SuggestionAdapter(
    private val bottomBar: () -> Boolean,
    private val onPick: (Suggestion) -> Unit,
    private val onFill: (Suggestion) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** A group of suggestions; [title] null for the top one (what was typed). */
    class Group(val title: String?, val items: List<Suggestion>)

    private class Header(val title: String)

    private var rows: List<Any> = emptyList()
    private var typed = ""

    fun show(groups: List<Group>, typedText: String) {
        typed = typedText.trim()
        val out = ArrayList<Any>()
        val bottom = bottomBar()
        for (g in groups) {
            if (g.items.isEmpty()) continue
            if (bottom) {
                // The list is drawn from the bottom up: items first (reversed), then the heading.
                out.addAll(g.items.reversed())
                g.title?.let { out.add(Header(it)) }
            } else {
                g.title?.let { out.add(Header(it)) }
                out.addAll(g.items)
            }
        }
        rows = out
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is Header) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        if (viewType == 1) {
            val tv = Ui.text(ctx, Ui.Type.SECTION, "").apply {
                setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 14), Ui.dp(ctx, 20), Ui.dp(ctx, 4))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isFocusable = false
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        return RowHolder(LayoutInflater.from(ctx).inflate(R.layout.item_row, parent, false)).also {
            // A little lower than list rows: suggestions are mostly one line.
            it.itemView.minimumHeight = Ui.dp(ctx, 56)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = rows[position]
        if (item is Header) {
            (holder.itemView as TextView).text = item.title
            holder.itemView.contentDescription = item.title
            return
        }
        val s = item as Suggestion
        val h = holder as RowHolder
        val ctx = h.itemView.context
        val icon = when (s.kind) {
            Suggestion.Kind.CLIPBOARD -> R.drawable.ic_copy
            Suggestion.Kind.GO -> R.drawable.ic_globe
            Suggestion.Kind.SEARCH -> R.drawable.ic_search
            Suggestion.Kind.BOOKMARK -> R.drawable.ic_star
            Suggestion.Kind.HISTORY -> R.drawable.ic_history
            Suggestion.Kind.TAB -> R.drawable.ic_tab
        }
        val subtitle = when (s.kind) {
            Suggestion.Kind.SEARCH -> null
            Suggestion.Kind.GO -> s.url
            Suggestion.Kind.TAB -> "Switch to this tab · " + UrlUtils.siteName(s.url)
            else -> UrlUtils.siteName(s.url)
        }
        h.set(icon, bold(s.title), subtitle)
        // Sites show their own icon when Swift has it.
        val favicon = if (s.kind == Suggestion.Kind.BOOKMARK || s.kind == Suggestion.Kind.HISTORY || s.kind == Suggestion.Kind.TAB) Favicons.cached(s.url) else null
        if (favicon != null) {
            h.icon.imageTintList = null
            h.icon.setImageBitmap(favicon)
        } else {
            h.icon.imageTintList = ColorStateList.valueOf(Ui.textSecondary(ctx))
        }
        if (s.kind == Suggestion.Kind.TAB) {
            h.setAction(R.drawable.ic_open_new, "Switch to tab") { onPick(s) }
        } else {
            h.setAction(R.drawable.ic_fill, "Put in the address box") { onFill(s) }
        }
        h.itemView.setOnClickListener { onPick(s) }
    }

    /** The words typed, in bold where they appear. */
    private fun bold(text: String): CharSequence {
        if (typed.isEmpty()) return text
        val i = text.indexOf(typed, ignoreCase = true)
        if (i < 0) return text
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), i, i + typed.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
