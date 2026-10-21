package com.buzzme.app.data.repository

import android.app.Service
import android.content.Context
import android.net.wifi.WifiManager
import com.buzzme.app.data.model.SessionEndReason
import com.buzzme.app.data.model.SessionState
import com.buzzme.app.network.SocketServer
import com.buzzme.app.network.web.HttpGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class SessionController(
    private val context: Context,
    private val socketServer: SocketServer,
    private val httpGateway: HttpGateway,
    private val networkManager: com.buzzme.app.network.NetworkManager? = null,
    private val hostBeaconManager: com.buzzme.app.network.HostBeaconManager? = null,
    private val nsdServiceManager: com.buzzme.app.network.NsdServiceManager? = null,
    private val deviceRepository: DeviceRepository? = null
) {
    private val mutex = Mutex()
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Stopped())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun updateMdnsAdvertising() {
        if (deviceRepository?.isPrimaryHost == true && _sessionState.value is SessionState.Active) {
            nsdServiceManager?.startAdvertising(port = 8900, serviceName = "buzzme")
        } else {
            nsdServiceManager?.stopAdvertising()
        }
    }

    suspend fun start(): Boolean = mutex.withLock {
        if (_sessionState.value is SessionState.Active) return true

        return try {
            acquireLocks()
            val wifiIp = networkManager?.wifiIpAddress?.value
            socketServer.start(port = 8899, bindAddress = null)
            httpGateway.start(port = 8900, host = "0.0.0.0")
            hostBeaconManager?.start()
            if (deviceRepository?.isPrimaryHost == true) {
                nsdServiceManager?.startAdvertising(port = 8900, serviceName = "buzzme")
            } else {
                nsdServiceManager?.stopAdvertising()
            }
            _sessionState.value = SessionState.Active(startTimeMs = System.currentTimeMillis())
            true
        } catch (e: Exception) {
            releaseLocks()
            socketServer.stop()
            httpGateway.stop()
            hostBeaconManager?.stop()
            nsdServiceManager?.stopAdvertising()
            _sessionState.value = SessionState.Stopped(SessionEndReason.NETWORK_BIND_FAILED)
            false
        }
    }

    suspend fun stop(reason: SessionEndReason, startId: Int? = null, service: Service? = null) {
        mutex.withLock {
            if (_sessionState.value is SessionState.Stopped) {
                startId?.let { service?.stopSelf(it) }
                return@withLock
            }

            releaseLocks()
            socketServer.stop()
            httpGateway.stop()
            hostBeaconManager?.stop()
            nsdServiceManager?.stopAdvertising()
            _sessionState.value = SessionState.Stopped(reason)
            service?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            startId?.let { service?.stopSelf(it) } ?: service?.stopSelf()
        }
    }

    private fun acquireLocks() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "BuzzMe:WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = wifiManager.createMulticastLock("BuzzMe:MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // Handled if restricted or already held
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // Ignore
        }
        wifiLock = null
        multicastLock = null
    }
}
