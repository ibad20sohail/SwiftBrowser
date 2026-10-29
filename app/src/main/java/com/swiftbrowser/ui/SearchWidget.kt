package com.swiftbrowser.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.swiftbrowser.R

/** The home screen widget (2.6): the bar searches, then Documents and Files (2.7), a new incognito tab and the QR scanner. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_search).apply {
            setOnClickPendingIntent(R.id.widget_bar, open(ctx, MainActivity.ACTION_SEARCH, 1))
            setOnClickPendingIntent(R.id.widget_text, open(ctx, MainActivity.ACTION_SEARCH, 1))
            setOnClickPendingIntent(R.id.widget_documents, open(ctx, MainActivity.ACTION_DOCUMENTS, 4))
            setOnClickPendingIntent(R.id.widget_files, open(ctx, MainActivity.ACTION_FILES, 5))
            setOnClickPendingIntent(R.id.widget_incognito, open(ctx, MainActivity.ACTION_INCOGNITO, 2))
            setOnClickPendingIntent(R.id.widget_scan, open(ctx, MainActivity.ACTION_SCAN, 3))
        }
        manager.updateAppWidget(ids, views)
    }

    private fun open(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, 4300 + code,
            Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** The "New tab" quick settings tile (2.6): opens Swift on a new tab, ready to type. */
class NewTabTileService : TileService() {
    override fun onClick() {
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
