package com.buzzme.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.buzzme.app.R

object BuzzWidget {

    fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("buzz_widget_prefs", Context.MODE_PRIVATE)
        val targetLabel = prefs.getString("widget_${appWidgetId}_label", "Buzz Target") ?: "Buzz Target"
        val targetId = prefs.getString("widget_${appWidgetId}_target_id", "") ?: ""

        val views = RemoteViews(context.packageName, R.layout.widget_buzz_layout)
        views.setTextViewText(R.id.widget_target_name, targetLabel)
        views.setTextViewText(R.id.widget_status, "⚡ BUZZ")

        val intent = Intent(context, BuzzWidgetReceiver::class.java).apply {
            action = BuzzWidgetReceiver.ACTION_WIDGET_BUZZ
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(BuzzWidgetReceiver.EXTRA_TARGET_ID, targetId)
            putExtra(BuzzWidgetReceiver.EXTRA_TARGET_LABEL, targetLabel)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
