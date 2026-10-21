package com.buzzme.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receives BOOT_COMPLETED (and equivalent) broadcasts and restarts the
 * BuzzForegroundService automatically on device startup or package update.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val bootAction = intent.action ?: return
        if (bootAction != Intent.ACTION_BOOT_COMPLETED &&
            bootAction != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            bootAction != Intent.ACTION_MY_PACKAGE_REPLACED &&
            bootAction != "android.intent.action.QUICKBOOT_POWERON" &&
            bootAction != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.i(TAG, "Boot/Startup event received ($bootAction) — starting BuzzMe paging session")
        startSessionService(context)
    }

    companion object {
        private const val TAG = "BuzzBootReceiver"

        fun startSessionService(context: Context) {
            val serviceIntent = Intent(context, BuzzForegroundService::class.java).apply {
                action = BuzzForegroundService.ACTION_START_SESSION
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct startForegroundService failed (${e.message}). Triggering AlarmManager foreground service launch fallback...")
                try {
                    val pendingIntent = PendingIntent.getForegroundService(
                        context,
                        1001,
                        serviceIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    val triggerTime = System.currentTimeMillis() + 100
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    } else {
                        alarmManager?.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "AlarmManager fallback start failed", ex)
                }
            }
        }
    }
}
