package com.buzzme.app.network

import android.content.Context
import android.util.Log
import com.buzzme.app.data.model.HostBeacon
import com.buzzme.app.data.model.HostRole
import com.buzzme.app.data.repository.DeviceRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class HostBeaconManager(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val networkManager: NetworkManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: DatagramSocket? = null
    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    private var electionJob: Job? = null

    private val activeBeacons = ConcurrentHashMap<String, HostBeacon>()

    private val _currentActiveHost = MutableStateFlow<HostBeacon?>(null)
    val currentActiveHost: StateFlow<HostBeacon?> = _currentActiveHost.asStateFlow()

    private val _isActingHost = MutableStateFlow<Boolean>(deviceRepository.isPrimaryHost)
    val isActingHost: StateFlow<Boolean> = _isActingHost.asStateFlow()

    var onPeerSyncRequested: ((peerIp: String) -> Unit)? = null

    val isRunning: Boolean
        get() = socket?.isClosed == false

    fun start() {
        if (isRunning) return

        try {
            val ds = DatagramSocket(BEACON_PORT).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 2000
            }
            socket = ds
            Log.i(TAG, "HostBeaconManager started on UDP port $BEACON_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind DatagramSocket on port $BEACON_PORT: ${e.message}")
            try {
                // Fallback: unbound socket for sending only
                socket = DatagramSocket().apply { broadcast = true }
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback DatagramSocket creation failed: ${ex.message}")
            }
        }

        // 1. Send loop (every 3s)
        sendJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(BEACON_INTERVAL_MS)
            }
        }

        // 2. Receive loop
        receiveJob = scope.launch {
            val buffer = ByteArray(2048)
            while (isActive) {
                val ds = socket ?: break
                if (ds.isClosed) break
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    ds.receive(packet)
                    val rawStr = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    handleIncomingBeacon(rawStr, packet.address.hostAddress ?: "")
                } catch (e: Exception) {
                    // Socket timeout or closed — safe to loop
                }
            }
        }

        // 3. Election loop (every 2s)
        electionJob = scope.launch {
            while (isActive) {
                evaluateHostElection()
                delay(ELECTION_CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        sendJob?.cancel()
        receiveJob?.cancel()
        electionJob?.cancel()
        sendJob = null
        receiveJob = null
        electionJob = null

        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
        activeBeacons.clear()
        Log.i(TAG, "HostBeaconManager stopped")
    }

    private fun sendHeartbeat() {
        val wifiIp = networkManager.wifiIpAddress.value ?: return
        val myDeviceId = deviceRepository.hostDeviceId.toString()
        val myName = deviceRepository.getDeviceName().ifBlank { "BuzzMe Device" }
        val isPrimary = deviceRepository.isPrimaryHost

        val myRole = if (isPrimary) HostRole.PRIMARY_HOST else HostRole.BACKUP_HOST
        val myPriority = if (isPrimary) PRIMARY_PRIORITY else BACKUP_BASE_PRIORITY

        val activeHostId = _currentActiveHost.value?.deviceId ?: myDeviceId

        val beacon = HostBeacon(
            deviceId = myDeviceId,
            deviceName = myName,
            role = myRole,
            priority = myPriority,
            activeHostId = activeHostId,
            ipAddress = wifiIp,
            timestampMs = System.currentTimeMillis()
        )

        val payload = json.encodeToString(beacon).toByteArray(StandardCharsets.UTF_8)

        try {
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(payload, payload.size, broadcastAddr, BEACON_PORT)
            socket?.send(packet)

            // Also unicast directly to known peers in case router blocks/drops 255.255.255.255 broadcast
            for (p in deviceRepository.peers.value) {
                val pIp = p.hostIp ?: continue
                if (pIp.isNotBlank() && pIp != "127.0.0.1" && pIp != wifiIp) {
                    try {
                        val peerAddr = InetAddress.getByName(pIp)
                        val uPacket = DatagramPacket(payload, payload.size, peerAddr, BEACON_PORT)
                        socket?.send(uPacket)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            // Multicast/broadcast fallback if restricted
        }
    }

    private fun handleIncomingBeacon(rawJson: String, senderIp: String) {
        val beacon = runCatching { json.decodeFromString<HostBeacon>(rawJson) }.getOrNull() ?: return
        val myDeviceId = deviceRepository.hostDeviceId.toString()

        // Ignore self-beacons
        if (beacon.deviceId == myDeviceId) return

        // Save or update beacon record with actual resolved sender IP if needed
        val resolvedBeacon = if (beacon.ipAddress.isBlank() || beacon.ipAddress == "127.0.0.1") {
            beacon.copy(ipAddress = senderIp, timestampMs = System.currentTimeMillis())
        } else {
            beacon.copy(timestampMs = System.currentTimeMillis())
        }

        val previousPrimaryOnline = activeBeacons.values.any {
            it.role == HostRole.PRIMARY_HOST && (System.currentTimeMillis() - it.timestampMs) < FAILOVER_TIMEOUT_MS
        }

        activeBeacons[beacon.deviceId] = resolvedBeacon

        // If Primary Host just reappeared while we were acting host, trigger sync request
        if (!previousPrimaryOnline && beacon.role == HostRole.PRIMARY_HOST && _isActingHost.value && !deviceRepository.isPrimaryHost) {
            Log.i(TAG, "Primary Host '${beacon.deviceName}' returned! Yielding host role & requesting sync at ${resolvedBeacon.ipAddress}")
            onPeerSyncRequested?.invoke(resolvedBeacon.ipAddress)
        }
    }

    fun evaluateHostElection() {
        val now = System.currentTimeMillis()
        val myDeviceId = deviceRepository.hostDeviceId.toString()
        val myName = deviceRepository.getDeviceName().ifBlank { "BuzzMe Device" }
        val isPrimary = deviceRepository.isPrimaryHost
        val myWifiIp = networkManager.wifiIpAddress.value ?: "127.0.0.1"

        // Remove stale beacons older than FAILOVER_TIMEOUT_MS
        val validBeacons = activeBeacons.values.filter { (now - it.timestampMs) <= FAILOVER_TIMEOUT_MS }

        // Find any external primary host
        val externalPrimaryHost = validBeacons.firstOrNull { it.role == HostRole.PRIMARY_HOST && it.deviceId != myDeviceId }

        if (externalPrimaryHost != null && isPrimary) {
            // Another established host exists on the network! Yield immediately to prevent multiple hosts.
            Log.w(TAG, "Conflict detected: External device '${externalPrimaryHost.deviceName}' (${externalPrimaryHost.ipAddress}) is already Primary Host. Yielding host role.")
            deviceRepository.isPrimaryHost = false
        }

        val updatedIsPrimary = deviceRepository.isPrimaryHost

        val electedHost = if (externalPrimaryHost != null) {
            externalPrimaryHost
        } else if (updatedIsPrimary) {
            HostBeacon(
                deviceId = myDeviceId,
                deviceName = myName,
                role = HostRole.PRIMARY_HOST,
                priority = PRIMARY_PRIORITY,
                activeHostId = myDeviceId,
                ipAddress = myWifiIp,
                timestampMs = now
            )
        } else {
            // No primary host online; local device is NOT designated as host
            null
        }

        _currentActiveHost.value = electedHost
        val iAmHost = (electedHost != null && electedHost.deviceId == myDeviceId && updatedIsPrimary)
        _isActingHost.value = iAmHost

        Log.d(TAG, "Host election evaluated. Elected Host: ${electedHost?.deviceName ?: "None"}, iAmHost=$iAmHost")
    }

    companion object {
        private const val TAG = "HostBeaconManager"
        const val BEACON_PORT = 8898
        const val BEACON_INTERVAL_MS = 3000L
        const val ELECTION_CHECK_INTERVAL_MS = 2000L
        const val FAILOVER_TIMEOUT_MS = 10_000L

        const val PRIMARY_PRIORITY = 100
        const val BACKUP_BASE_PRIORITY = 50
    }
}
