package com.swiftbrowser.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.swiftbrowser.R
import com.swiftbrowser.core.L

/**
 * The home screen widget (2.6): the bar searches, then Documents and Files (2.7), a new incognito tab
 * and the QR scanner. 3.0.4: in Swift's look, light or dark as Swift is now: its surface colour
 * (your background), the icons in the accent and the words in the matching grey.
 */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(ctx))
        drawn = look()
    }

    companion object {
        /** What the widgets were last drawn in (light or dark, and the colours chosen), so they're redrawn only when that changed. */
        @Volatile private var drawn: String? = null

        private fun look() = "${Looks.wantsDark()} ${Palette.signature}"

        /**
         * 3.0.4: draws every Swift widget on the home screen in Swift's look now: when the look, the
         * accent or the background changed, and when Swift starts or comes back ("Dark at night" may
         * have turned while it was away). Nothing is done when they already look so.
         */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext ?: ctx
            val look = look()
            if (look == drawn) return
            try {
                val manager = AppWidgetManager.getInstance(app) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(app, SearchWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(app))
                drawn = look
            } catch (e: Exception) {
                L.w("Couldn't redraw the widgets", e)
            }
        }

        private fun views(ctx: Context): RemoteViews {
            val night = Looks.wantsDark()
            fun c(res: Int) = Palette.colorFor(ctx, night, res)
            val accent = c(R.color.accent)
            return RemoteViews(ctx.packageName, R.layout.widget_search).apply {
                // A widget can't make a shape of its own colour: its rounded picture is tinted instead.
                setInt(R.id.widget_bg, "setColorFilter", c(R.color.sheet_bg))
                setTextColor(R.id.widget_text, c(R.color.text_secondary))
                for (id in intArrayOf(R.id.widget_search_icon, R.id.widget_files, R.id.widget_incognito, R.id.widget_scan)) {
                    setInt(id, "setColorFilter", accent)
                }
                setOnClickPendingIntent(R.id.widget_bar, open(ctx, MainActivity.ACTION_SEARCH, 1))
                setOnClickPendingIntent(R.id.widget_text, open(ctx, MainActivity.ACTION_SEARCH, 1))
                setOnClickPendingIntent(R.id.widget_files, open(ctx, MainActivity.ACTION_FILES, 5))
                setOnClickPendingIntent(R.id.widget_incognito, open(ctx, MainActivity.ACTION_INCOGNITO, 2))
                setOnClickPendingIntent(R.id.widget_scan, open(ctx, MainActivity.ACTION_SCAN, 3))
            }
        }

        private fun open(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx, 4300 + code,
                Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

/** The "New tab" quick settings tile (2.6): opens Swift on a new tab, ready to type. */
class NewTabTileService : TileService() {
    override fun onClick() {
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
