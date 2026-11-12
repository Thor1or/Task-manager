package com.thor1or.dayloop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.thor1or.dayloop.R
import com.thor1or.dayloop.ui.QuickAddActivity

/** A one-line home-screen widget: tap it and type a task. */
class QuickAddWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_add)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, views)
        }
    }
}
