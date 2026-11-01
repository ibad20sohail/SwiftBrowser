package com.swiftbrowser.ui

import android.app.Activity
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.core.Clearing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Choosing days to clear (2.8): a month at a time; tap the first day, then the last (or the same
 * one again for just that day). Swift's own small calendar rather than Material's date range
 * picker: that one is a full-screen fragment that shows itself, so it can't be opened the way
 * Swift opens its dialogs over full screen (see Immersive: the status and navigation bars would
 * come back), and it draws its days in the theme's colours, never your own. Days after today
 * can't be chosen.
 */
object ChooseDates {
    private const val CELLS = 42

    /** A label for chosen days: "12 Oct – 20 Oct", "12 Oct", or with years when not this year's. */
    fun label(first: Int, last: Int): String {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val withYear = first / 10000 != year || last / 10000 != year
        val format = SimpleDateFormat(if (withYear) "d MMM yyyy" else "d MMM", Locale.getDefault())
        val a = format.format(Clearing.calendarOf(first).time)
        return if (first == last) a else a + " – " + format.format(Clearing.calendarOf(last).time)
    }

    /**
     * Asks for days, starting from [first] to [last] when chosen before. [onChosen] gets the first
     * and last day (as Clearing.dayOf gives them); [onCancel] runs when the dialog is left without.
     */
    fun show(a: Activity, first: Int?, last: Int?, onChosen: (Int, Int) -> Unit, onCancel: () -> Unit) {
        val today = Clearing.dayOf(System.currentTimeMillis())
        val before = first?.takeIf { it <= today }
        var start: Int? = before
        var end: Int? = if (before != null) last?.takeIf { it in before..today } else null
        // The month on screen, by its first day: the one with the days chosen before, or this one.
        val month = Clearing.calendarOf((end ?: start ?: today) / 100 * 100 + 1)
        val thisMonth = today / 100

        val accent = Ui.accent(a)
        val onAccent = Ui.onAccent(a)
        val band = Ui.accentContainer(a)
        val onBand = Ui.onAccentContainer(a)
        val text = Ui.text(a)
        val secondary = Ui.textSecondary(a)

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), 0)
        }
        val summary = Ui.text(a, Ui.Type.BODY_SECONDARY, "").apply {
            setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 8))
        }
        box.addView(summary)

        // ‹ October 2026 ›
        val title = Ui.text(a, Ui.Type.SUBTITLE, "").apply { gravity = Gravity.CENTER }
        fun arrow(back: Boolean) = ImageView(a).apply {
            setImageDrawable(Ui.icon(a, R.drawable.ic_chevron_right, text))
            if (back) rotation = 180f
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.rippleBorderless(a)
            contentDescription = if (back) "Month before" else "Month after"
        }
        val previous = arrow(true)
        val next = arrow(false)
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previous, LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(next, LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)))
        })

        // The days of the week, in the order the phone's region starts its week.
        val firstWeekday = Calendar.getInstance().firstDayOfWeek
        val initials = SimpleDateFormat("EEEEE", Locale.getDefault())
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4))
            for (c in 0 until 7) {
                val weekday = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (firstWeekday - 1 + c) % 7 + 1) }
                addView(Ui.text(a, Ui.Type.CAPTION, initials.format(weekday.time)).apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        })

        // Six weeks of days, made once and filled in for each month (the dialog keeps its height).
        val cells = ArrayList<FrameLayout>(CELLS)
        val numbers = ArrayList<TextView>(CELLS)
        // A little smaller than a finger's 40dp, so all six weeks fit the dialog on a small phone.
        val circle = Ui.dp(a, 32)
        for (r in 0 until CELLS / 7) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 7) {
                val number = TextView(a).apply {
                    Ui.style(this, Ui.Type.BODY)
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val cell = FrameLayout(a).apply { addView(number, FrameLayout.LayoutParams(circle, circle, Gravity.CENTER)) }
                row.addView(cell, LinearLayout.LayoutParams(0, Ui.dp(a, 36), 1f))
                cells += cell
                numbers += number
            }
            box.addView(row)
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null
        val full = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())
        val heading = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        fun bind() {
            val year = month.get(Calendar.YEAR)
            val m = month.get(Calendar.MONTH) + 1
            title.text = heading.format(month.time)
            val lead = (month.get(Calendar.DAY_OF_WEEK) - firstWeekday + 7) % 7
            val days = month.getActualMaximum(Calendar.DAY_OF_MONTH)
            val s = start
            val e = end
            for (i in 0 until CELLS) {
                val cell = cells[i]
                val number = numbers[i]
                val n = i - lead + 1
                if (n !in 1..days) {
                    number.text = ""
                    number.background = null
                    cell.background = null
                    cell.isClickable = false
                    cell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    continue
                }
                val day = year * 10000 + m * 100 + n
                val future = day > today
                val ends = day == s || day == e
                val between = s != null && e != null && day > s && day < e
                number.text = n.toString()
                number.alpha = if (future) 0.35f else 1f
                number.typeface = if (ends || day == today) Ui.MEDIUM else android.graphics.Typeface.DEFAULT
                number.setTextColor(if (ends) onAccent else if (between) onBand else if (future) secondary else text)
                number.background = when {
                    ends -> Ui.circle(accent)
                    // Today: a ring.
                    day == today -> Ui.circle(0).apply { setStroke(Ui.dpf(a, 1.5f).toInt().coerceAtLeast(1), accent) }
                    else -> null
                }
                // The band joining the first day to the last.
                cell.background = when {
                    between -> Band(band, Band.ALL, circle)
                    s != null && e != null && s != e && day == s -> Band(band, Band.RIGHT, circle)
                    s != null && e != null && s != e && day == e -> Band(band, Band.LEFT, circle)
                    else -> null
                }
                cell.isClickable = !future
                cell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                cell.contentDescription = full.format(Clearing.calendarOf(day).time) + when {
                    future -> ", can't be chosen"
                    ends || between -> ", chosen"
                    else -> ""
                }
                cell.setOnClickListener {
                    val first0 = start
                    when {
                        first0 == null || end != null -> {
                            start = day
                            end = null
                        }
                        day < first0 -> start = day
                        else -> end = day
                    }
                    bind()
                }
            }
            val ahead = year * 100 + m < thisMonth
            next.isEnabled = ahead
            next.alpha = if (ahead) 1f else 0.3f
            summary.text = when {
                s == null -> "Tap the first day, then the last"
                e == null -> "From ${label(s, s)}: tap the last day, or Done for just that day"
                else -> {
                    val count = ((Clearing.startOfDay(Clearing.nextDay(e)) - Clearing.startOfDay(s) + 3_600_000L) / 86_400_000L).toInt()
                    label(s, e) + if (count == 1) " · 1 day" else " · $count days"
                }
            }
            dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = s != null
        }
        previous.setOnClickListener {
            month.add(Calendar.MONTH, -1)
            bind()
        }
        next.setOnClickListener {
            if (month.get(Calendar.YEAR) * 100 + month.get(Calendar.MONTH) + 1 >= thisMonth) return@setOnClickListener
            month.add(Calendar.MONTH, 1)
            bind()
        }
        bind()

        var chosen = false
        val d = MaterialAlertDialogBuilder(a)
            .setTitle("Choose dates")
            .setView(box)
            .setPositiveButton("Done") { _, _ ->
                val s = start ?: return@setPositiveButton
                chosen = true
                onChosen(s, end ?: s)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        dialog = d
        // Cancel, Back or a tap beside it: whatever was chosen before stays.
        d.setOnDismissListener { if (!chosen) onCancel() }
        d.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = start != null
    }

    /** The soft band behind chosen days, as tall as a day's circle: across the day, or its right or left half (the first and last day). */
    private class Band(color: Int, private val part: Int, private val height: Int) : Drawable() {
        private val paint = Paint().apply { this.color = color }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val top = b.exactCenterY() - height / 2f
            val left = if (part == RIGHT) b.exactCenterX() else b.left.toFloat()
            val right = if (part == LEFT) b.exactCenterX() else b.right.toFloat()
            canvas.drawRect(left, top, right, top + height, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        companion object {
            const val ALL = 0
            const val RIGHT = 1
            const val LEFT = 2
        }
    }
}
