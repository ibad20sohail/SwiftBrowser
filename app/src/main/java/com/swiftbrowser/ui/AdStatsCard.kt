package com.swiftbrowser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import com.swiftbrowser.R
import com.swiftbrowser.adblock.AdStats
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → Ad blocking: how much was blocked this week, with a bar for each day (2.3). */
object AdStatsCard {
    fun build(ctx: Context): View {
        val days = AdStats.perDay()
        val total = days.sum()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 16), Ui.dp(ctx, 18), Ui.dp(ctx, 12))
            addView(Ui.text(ctx, Ui.Type.HEADLINE, NumberFormat.getIntegerInstance().format(total)).apply { textSize = 30f })
            addView(Ui.text(ctx, Ui.Type.BODY_SECONDARY,
                if (total == 0) "Nothing blocked yet this week" else "ads and trackers blocked in the last 7 days"))
            addView(Bars(ctx, days), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 92)).apply { topMargin = Ui.dp(ctx, 12) })
            contentDescription = "$total ads and trackers blocked in the last 7 days"
        }
    }

    /** One bar a day, today on the right in the accent colour; they grow in when shown. */
    private class Bars(ctx: Context, private val days: IntArray) : View(ctx) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = Ui.dpf(ctx, 11.5f) * ctx.resources.configuration.fontScale
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private val letters: List<String>
        private var grow = 1f

        init {
            val today = AdStats.today()
            val format = SimpleDateFormat("EEEEE", Locale.getDefault())
            letters = List(AdStats.DAYS) { i ->
                val day = today - (AdStats.DAYS - 1 - i)
                // Noon of that day, so the time zone offset can't move it to the next one.
                format.format(Date(day * 86_400_000L + 43_200_000L - java.util.TimeZone.getDefault().getOffset(day * 86_400_000L)))
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (days.sum() == 0) return
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    grow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            val max = (days.maxOrNull() ?: 0).coerceAtLeast(1)
            val labelSpace = Ui.dpf(context, 20f)
            val chartTop = Ui.dpf(context, 2f)
            val chartBottom = height - labelSpace
            val slot = width / days.size.toFloat()
            val width = minOf(slot * 0.46f, Ui.dpf(context, 26f))
            val minimum = Ui.dpf(context, 4f)
            val accent = Ui.accent(context)
            val soft = Ui.accentContainer(context)
            val empty = Ui.color(context, R.color.divider)
            for (i in days.indices) {
                val cx = slot * (i + 0.5f)
                val h = if (days[i] == 0) minimum else maxOf(minimum, (chartBottom - chartTop) * days[i] / max * grow)
                rect.set(cx - width / 2, chartBottom - h, cx + width / 2, chartBottom)
                bar.color = when {
                    days[i] == 0 -> empty
                    i == days.lastIndex -> accent
                    else -> soft
                }
                c.drawRoundRect(rect, minOf(width / 2, Ui.dpf(context, 8f)), minOf(width / 2, Ui.dpf(context, 8f)), bar)
                label.color = if (i == days.lastIndex) accent else Ui.textSecondary(context)
                label.isFakeBoldText = i == days.lastIndex
                c.drawText(letters[i], cx, height - Ui.dpf(context, 4f), label)
            }
        }
    }
}
