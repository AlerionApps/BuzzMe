package com.buzzme.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_VIBRATION -> {
                AlertEngine.stopVibration(context)
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(AlertEngine.NOTIFICATION_ID_ALERT)
            }
            ACTION_ACKNOWLEDGE_BUZZ -> {
                AlertEngine.stopVibration(context)
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(AlertEngine.NOTIFICATION_ID_ALERT)
                val senderKey = intent.getStringExtra("sender_key")
                val senderLabel = intent.getStringExtra("sender_label")
                CoroutineScope(Dispatchers.IO).launch {
                    AlertEngine.sendAcknowledgementBuzz(context, senderKey, senderLabel)
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP_VIBRATION = "com.buzzme.app.action.STOP_VIBRATION"
        const val ACTION_ACKNOWLEDGE_BUZZ = "com.buzzme.app.action.ACKNOWLEDGE_BUZZ"
    }
}
