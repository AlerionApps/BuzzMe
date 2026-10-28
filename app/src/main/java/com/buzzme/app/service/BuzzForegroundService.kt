package com.buzzme.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.buzzme.app.BuzzMeApp
import com.buzzme.app.data.model.SessionEndReason
import com.buzzme.app.data.model.SessionState
import com.buzzme.app.network.SocketClient
import kotlinx.coroutines.*
import java.util.UUID

class BuzzForegroundService : Service() {

    private val sessionController by lazy { (application as BuzzMeApp).sessionController }
    private val deviceRepository by lazy { (application as BuzzMeApp).deviceRepository }
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // null intent = OS restarted us via START_STICKY after killing the service.
        // Re-promote to foreground and resume the session automatically.
        if (action == null || action == ACTION_START_SESSION) {
            val notification = AlertEngine.createSessionNotification(this)
            try {
                promoteForeground(notification)
                serviceScope.launch {
                    val alreadyActive = sessionController.sessionState.value is SessionState.Active
                    if (!alreadyActive) {
                        val started = sessionController.start()
                        if (!started) {
                            sessionController.stop(
                                reason = SessionEndReason.NETWORK_BIND_FAILED,
                                startId = startId,
                                service = this@BuzzForegroundService
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot start foreground service: ${e.message}")
                serviceScope.launch {
                    sessionController.stop(
                        reason = SessionEndReason.START_NOT_ALLOWED,
                        startId = startId,
                        service = this@BuzzForegroundService
                    )
                }
            }
            // START_STICKY: if the OS kills us, it will auto-restart us with a null intent.
            return START_STICKY
        }

        when (action) {
            ACTION_STOP_SESSION -> {
                serviceScope.launch {
                    sessionController.stop(
                        reason = SessionEndReason.STOPPED_BY_USER,
                        startId = startId,
                        service = this@BuzzForegroundService
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Promotes this service to foreground using specialUse type, which has no 6-hour
     * time limit (unlike dataSync). Falls back to 0 (unspecified) below API 34.
     */
    private fun promoteForeground(notification: android.app.Notification) {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            AlertEngine.NOTIFICATION_ID_SESSION,
            notification,
            fgsType
        )
    }

    /**
     * onTimeout() fires only for dataSync FGS — specialUse has no timeout.
     * Kept as a safety net in case of misconfiguration or future SDK changes.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout called unexpectedly — should not fire for specialUse FGS type")
        // Do NOT stop the session.
    }

    override fun onDestroy() {
        runBlocking {
            withTimeoutOrNull(1_000) {
                sessionController.stop(SessionEndReason.SERVICE_DESTROYED)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BuzzForegroundService"
        const val ACTION_START_SESSION = "com.buzzme.app.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.buzzme.app.action.STOP_SESSION"
    }
}

