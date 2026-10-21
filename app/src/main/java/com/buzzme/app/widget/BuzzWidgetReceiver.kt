package com.buzzme.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.buzzme.app.BuzzMeApp
import com.buzzme.app.data.model.SessionState
import com.buzzme.app.service.AlertEngine
import com.buzzme.app.service.BuzzForegroundService

class BuzzWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            BuzzWidget.updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_STOP_VIBRATION) {
            AlertEngine.stopVibration(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notificationManager?.cancel(AlertEngine.NOTIFICATION_ID_ALERT)
            return
        }

        if (intent.action == ACTION_WIDGET_PINNED) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val targetId = intent.getStringExtra(EXTRA_TARGET_ID) ?: ""
            val targetLabel = intent.getStringExtra(EXTRA_TARGET_LABEL) ?: "Target"
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val prefs = context.getSharedPreferences("buzz_widget_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("widget_${appWidgetId}_target_id", targetId)
                    .putString("widget_${appWidgetId}_label", targetLabel)
                    .apply()
                val appWidgetManager = AppWidgetManager.getInstance(context)
                BuzzWidget.updateWidget(context, appWidgetManager, appWidgetId)
            }
            return
        }

        if (intent.action == ACTION_WIDGET_BUZZ) {
            val targetId = intent.getStringExtra(EXTRA_TARGET_ID)
            val targetLabel = intent.getStringExtra(EXTRA_TARGET_LABEL) ?: "Target"
            val message = intent.getStringExtra(EXTRA_MESSAGE)

            val app = context.applicationContext as? BuzzMeApp
            val sessionState = app?.sessionController?.sessionState?.value

            if (sessionState !is SessionState.Active) {
                Toast.makeText(
                    context,
                    "BuzzMe session is inactive. Open app to start session.",
                    Toast.LENGTH_LONG
                ).show()
                AlertEngine.showInactiveSessionWarning(
                    context,
                    "Cannot buzz $targetLabel: Active paging session is stopped. Open BuzzMe to start paging session."
                )
                return
            }

            // Session is active: dispatch buzz through service
            val serviceIntent = Intent(context, BuzzForegroundService::class.java).apply {
                action = BuzzForegroundService.ACTION_DISPATCH_BUZZ
                putExtra(BuzzForegroundService.EXTRA_TARGET_ID, targetId)
                if (message != null) {
                    putExtra(BuzzForegroundService.EXTRA_MESSAGE, message)
                }
            }
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            Toast.makeText(context, "Buzzing $targetLabel...", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_STOP_VIBRATION = "com.buzzme.app.widget.ACTION_STOP_VIBRATION"
        const val ACTION_WIDGET_BUZZ = "com.buzzme.app.widget.ACTION_WIDGET_BUZZ"
        const val ACTION_WIDGET_PINNED = "com.buzzme.app.widget.ACTION_WIDGET_PINNED"
        const val EXTRA_TARGET_ID = "com.buzzme.app.widget.EXTRA_TARGET_ID"
        const val EXTRA_TARGET_LABEL = "com.buzzme.app.widget.EXTRA_TARGET_LABEL"
        const val EXTRA_MESSAGE = "com.buzzme.app.widget.EXTRA_MESSAGE"

        fun requestPinWidget(context: Context, targetId: String, targetLabel: String) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                val provider = ComponentName(context, BuzzWidgetReceiver::class.java)
                val callbackIntent = Intent(context, BuzzWidgetReceiver::class.java).apply {
                    action = ACTION_WIDGET_PINNED
                    putExtra(EXTRA_TARGET_ID, targetId)
                    putExtra(EXTRA_TARGET_LABEL, targetLabel)
                }
                val successCallback = PendingIntent.getBroadcast(
                    context,
                    targetId.hashCode(),
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                appWidgetManager.requestPinAppWidget(provider, null, successCallback)
            } else {
                Toast.makeText(context, "Adding widgets directly is not supported by your launcher.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
