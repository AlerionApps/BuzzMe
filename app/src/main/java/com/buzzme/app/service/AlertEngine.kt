package com.buzzme.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.buzzme.app.R
import com.buzzme.app.ui.MainActivity

object AlertEngine {

    const val CHANNEL_SESSION = "buzzme_session_channel"
    const val CHANNEL_ALERT = "buzzme_alert_channel_v2"
    const val CHANNEL_WARNING = "buzzme_warning_channel"

    const val NOTIFICATION_ID_SESSION = 1001
    const val NOTIFICATION_ID_ALERT = 2001
    const val NOTIFICATION_ID_WARNING = 3001

    // Escalation Vibration Patterns:
    // Level 1: 3 times for 2 seconds (2s on, 0.5s off, 2s on, 0.5s off, 2s on)
    val PATTERN_LEVEL_1 = longArrayOf(0, 2000, 500, 2000, 500, 2000)
    val AMPLITUDES_LEVEL_1 = intArrayOf(0, 255, 0, 255, 0, 255)

    // Level 2: 3 times for 5 seconds (5s on, 0.5s off, 5s on, 0.5s off, 5s on)
    val PATTERN_LEVEL_2 = longArrayOf(0, 5000, 500, 5000, 500, 5000)
    val AMPLITUDES_LEVEL_2 = intArrayOf(0, 255, 0, 255, 0, 255)

    // Level 3: 20 seconds straight
    val PATTERN_LEVEL_3 = longArrayOf(0, 20000)
    val AMPLITUDES_LEVEL_3 = intArrayOf(0, 255)

    // 15-minute window for escalation reset
    const val ESCALATION_RESET_TIMEOUT_MS = 15 * 60 * 1000L

    data class BuzzHistory(
        val count: Int,
        val lastBuzzTimeMs: Long
    )

    private val senderHistory = java.util.concurrent.ConcurrentHashMap<String, BuzzHistory>()

    fun getEscalationLevel(senderKey: String, nowMs: Long = System.currentTimeMillis()): Int {
        val history = senderHistory[senderKey.lowercase().trim()] ?: return 1
        if (nowMs - history.lastBuzzTimeMs > ESCALATION_RESET_TIMEOUT_MS) {
            return 1
        }
        return history.count.coerceIn(1, 3)
    }

    fun recordBuzz(senderKey: String, nowMs: Long = System.currentTimeMillis()): Int {
        val key = senderKey.lowercase().trim()
        val updated = senderHistory.compute(key) { _, current ->
            if (current == null || (nowMs - current.lastBuzzTimeMs) > ESCALATION_RESET_TIMEOUT_MS) {
                BuzzHistory(count = 1, lastBuzzTimeMs = nowMs)
            } else {
                BuzzHistory(count = current.count + 1, lastBuzzTimeMs = nowMs)
            }
        }
        return (updated?.count ?: 1).coerceAtLeast(1)
    }

    fun resetEscalation(senderKey: String? = null) {
        if (senderKey != null) {
            senderHistory.remove(senderKey.lowercase().trim())
        } else {
            senderHistory.clear()
        }
    }

    fun initChannels(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Clean up legacy alert channel if it exists
        try {
            notificationManager.deleteNotificationChannel("buzzme_alert_channel")
        } catch (e: Exception) {
            // Ignore
        }

        // 1. Ongoing Session Channel
        val sessionChannel = NotificationChannel(
            CHANNEL_SESSION,
            "BuzzMe Active Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains the foreground paging session"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(sessionChannel)

        // 2. High-Priority Alert Channel (Completely Silent - 100% Tactile & Visual for Deaf / Hard of Hearing)
        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "Buzz Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Household pager alerts and buzzes"
            setSound(null, null)
            enableLights(true)
            enableVibration(false)
            try {
                setBypassDnd(true)
            } catch (e: SecurityException) {
                // If permission ACCESS_NOTIFICATION_POLICY is not granted
            }
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(alertChannel)

        // 3. Status/Warning Channel
        val warningChannel = NotificationChannel(
            CHANNEL_WARNING,
            "BuzzMe Status & Warnings",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Session status and widget warnings"
        }
        notificationManager.createNotificationChannel(warningChannel)
    }

    fun createSessionNotification(context: Context): Notification {
        initChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, BuzzForegroundService::class.java).apply {
            action = BuzzForegroundService.ACTION_STOP_SESSION
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setContentTitle("BuzzMe")
            .setContentText("Ready for family buzzes")
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Session", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun dispatchBuzzAlert(
        context: Context,
        senderLabel: String,
        messageText: String,
        senderId: String? = null
    ) {
        initChannels(context)

        // Track escalating buzz count
        val key = senderId?.ifBlank { null } ?: senderLabel
        val count = recordBuzz(key)
        val level = count.coerceIn(1, 3)

        android.util.Log.i("BuzzMe", "dispatchBuzzAlert: sender='$senderLabel', key='$key', count=$count, level=$level")

        // Trigger vibration according to level (1, 2, or 3)
        triggerVibration(context, level)

        val urgentIntent = Intent(context, com.buzzme.app.ui.UrgentAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("sender_key", key)
            putExtra("sender_label", senderLabel)
            putExtra("buzz_message", messageText)
            putExtra("urgency_level", level)
        }
        val urgentPendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() % 10000).toInt(),
            urgentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val silenceIntent = Intent(context, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_STOP_VIBRATION
            putExtra("sender_key", key)
        }
        val silencePendingIntent = PendingIntent.getBroadcast(
            context,
            999,
            silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ackIntent = Intent(context, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_ACKNOWLEDGE_BUZZ
            putExtra("sender_key", key)
            putExtra("sender_label", senderLabel)
        }
        val ackPendingIntent = PendingIntent.getBroadcast(
            context,
            998,
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (level) {
            1 -> "Nudge: $senderLabel"
            2 -> "Alert: $senderLabel"
            else -> "🚨 Emergency: $senderLabel"
        }

        val hasCustomNote = messageText.isNotBlank() && messageText != "Buzz!" && !messageText.startsWith("Buzz [")
        val patternDescription = when (level) {
            1 -> "Gentle buzz (Nudge: checking in)"
            2 -> "Firm pulsing buzz (Alert: needs attention)"
            else -> "Persistent urgent buzz (Emergency / Unignorable)"
        }

        val body = if (hasCustomNote) messageText else patternDescription
        val bigTextContent = if (hasCustomNote) {
            "$messageText\n• $patternDescription"
        } else {
            patternDescription
        }

        // Directly launch full-screen popup activity over lock screen / foreground
        try {
            context.startActivity(urgentIntent)
        } catch (e: Exception) {
            android.util.Log.e("BuzzMe", "Failed to directly launch UrgentAlertActivity", e)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigTextContent))
            .setSmallIcon(R.drawable.ic_notification_small)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(urgentPendingIntent)
            .setDeleteIntent(silencePendingIntent)
            .setFullScreenIntent(urgentPendingIntent, true)
            .addAction(android.R.drawable.ic_menu_send, "I'm on my way!", ackPendingIntent)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Silence", silencePendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    fun triggerVibration(context: Context, level: Int = 1) {
        try {
            val vibrator = getVibrator(context) ?: return

            val (pattern, amplitudes) = when (level) {
                1 -> Pair(PATTERN_LEVEL_1, AMPLITUDES_LEVEL_1)
                2 -> Pair(PATTERN_LEVEL_2, AMPLITUDES_LEVEL_2)
                else -> Pair(PATTERN_LEVEL_3, AMPLITUDES_LEVEL_3)
            }

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(pattern, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(pattern, -1)
            }

            vibrator.vibrate(effect, audioAttributes)
            android.util.Log.i("BuzzMe", "Vibration triggered for level $level (${pattern.joinToString()})")
        } catch (e: Exception) {
            android.util.Log.e("BuzzMe", "Error triggering vibration for level $level", e)
        }
    }

    fun stopVibration(context: Context) {
        try {
            val vibrator = getVibrator(context)
            vibrator?.cancel()
            android.util.Log.i("BuzzMe", "Vibration stopped")
        } catch (e: Exception) {
            android.util.Log.e("BuzzMe", "Error stopping vibration", e)
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun showInactiveSessionWarning(context: Context, detail: String) {
        initChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_WARNING)
            .setContentTitle("BuzzMe Status")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_WARNING, notification)
    }

    suspend fun sendAcknowledgementBuzz(context: Context, senderKey: String?, senderLabel: String?): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val app = context.applicationContext as? com.buzzme.app.BuzzMeApp ?: return@withContext false
                val repo = app.deviceRepository
                val peer = repo.peers.value.firstOrNull {
                    (!senderKey.isNullOrBlank() && it.keyId == senderKey) ||
                    (!senderLabel.isNullOrBlank() && it.label.equals(senderLabel, ignoreCase = true))
                } ?: return@withContext false

                val hostIp = peer.hostIp
                if (hostIp.isNullOrBlank()) {
                    android.util.Log.e("BuzzMe", "sendAcknowledgementBuzz: Host IP is blank for ${peer.label}")
                    return@withContext false
                }

                val secret = repo.decryptSecret(peer.wrappedSecret, peer.iv)
                val newSeq = peer.highestSeq + 1
                val result = com.buzzme.app.network.SocketClient.sendBuzz(
                    host = hostIp,
                    port = peer.port,
                    senderKeyId = java.util.UUID.fromString(peer.keyId),
                    targetDeviceId = java.util.UUID.fromString(peer.peerDeviceId),
                    sessionEpoch = peer.sessionEpoch,
                    sequence = newSeq,
                    message = "I'm on my way!",
                    pairwiseSecret = secret
                )
                if (result is com.buzzme.app.network.SocketClient.SendResult.Success) {
                    repo.updatePeerSequence(peer.keyId, newSeq)
                    android.util.Log.i("BuzzMe", "sendAcknowledgementBuzz: delivered to ${peer.label}")
                    true
                } else {
                    android.util.Log.e("BuzzMe", "sendAcknowledgementBuzz failed: $result")
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("BuzzMe", "Exception in sendAcknowledgementBuzz", e)
                false
            }
        }
    }
}
