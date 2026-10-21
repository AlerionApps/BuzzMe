package com.buzzme.app.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buzzme.app.BuzzMeApp
import com.buzzme.app.crypto.CryptoUtils
import com.buzzme.app.data.model.*
import com.buzzme.app.network.SocketClient
import com.buzzme.app.network.web.WebPushManager
import com.buzzme.app.service.AlertEngine
import com.buzzme.app.service.BuzzForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

data class ReceivedBuzz(
    val senderLabel: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BuzzMeApp
    val sessionController = app.sessionController
    val deviceRepository = app.deviceRepository
    val networkManager = app.networkManager
    val webPushManager = app.webPushManager
    val hostBeaconManager = app.hostBeaconManager

    val sessionState = sessionController.sessionState
    val peers = deviceRepository.peers
    val subscriptions = deviceRepository.subscriptions
    val capabilityTokens = deviceRepository.capabilityTokens
    val wifiIpAddress = networkManager.wifiIpAddress
    val currentActiveHost = hostBeaconManager.currentActiveHost
    val isActingHost = hostBeaconManager.isActingHost
    val isPrimaryHost = deviceRepository.isPrimaryHostFlow

    val activePairing: StateFlow<PairingSession?> = deviceRepository.currentPairingSession
    val deviceName: StateFlow<String?> = deviceRepository.deviceName

    val networkDiscoveryManager = app.networkDiscoveryManager
    val nsdServiceManager = app.nsdServiceManager

    val discoveredHost = networkDiscoveryManager.discoveredHost
    val isScanning = networkDiscoveryManager.isScanning
    val pendingJoinRequests = deviceRepository.pendingJoinRequests

    var autoApproveOnWifi: Boolean
        get() = deviceRepository.autoApproveOnWifi
        set(value) { deviceRepository.autoApproveOnWifi = value }

    fun startDiscovery() {
        networkDiscoveryManager.startDiscovery()
    }

    fun stopDiscovery() {
        networkDiscoveryManager.stopDiscovery()
    }

    fun approveJoinRequest(requestId: String) {
        val result = deviceRepository.approveJoinRequest(requestId)
        if (result != null) {
            _statusMessage.value = "Approved join request from ${result.deviceName}"
            pushPeerDirectoryToAllPeers()
        }
    }

    fun declineJoinRequest(requestId: String) {
        deviceRepository.declineJoinRequest(requestId)
        _statusMessage.value = "Declined join request"
    }

    fun sendJoinRequest(
        targetHost: com.buzzme.app.network.DiscoveredHost,
        myDeviceName: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ephemeralKeyPair = CryptoUtils.generateEcKeyPair()
                val peerPubKeyBytes = CryptoUtils.encodeUncompressedP256(ephemeralKeyPair.public as ECPublicKey)
                val peerPubKeyBase64 = CryptoUtils.base64UrlEncode(peerPubKeyBytes)
                val peerUuid = deviceRepository.hostDeviceId.toString()

                val requestPayload = json.encodeToString(
                    ZeroScanJoinPayload(
                        deviceName = myDeviceName,
                        deviceType = "android",
                        peerId = peerUuid,
                        peerPubKey = peerPubKeyBase64
                    )
                )

                val joinUrl = "http://${targetHost.ipAddress}:${targetHost.httpPort}/api/v1/join-request"
                android.util.Log.i("BuzzMe", "Sending zero-scan join request to $joinUrl")

                val request = Request.Builder()
                    .url(joinUrl)
                    .post(requestPayload.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val msg = "Join request failed (${response.code})"
                    _statusMessage.value = msg
                    onResult?.invoke(false, msg)
                    return@launch
                }

                val body = response.body?.string() ?: ""
                val parsedResp = runCatching { json.decodeFromString<ZeroScanJoinResponse>(body) }.getOrNull()
                val initialResp = parsedResp ?: run {
                    _statusMessage.value = "Invalid server response"
                    onResult?.invoke(false, "Invalid server response")
                    return@launch
                }

                var currentResp = initialResp

                // If status is pending, poll status endpoint until approved or declined
                if (currentResp.status == "pending") {
                    _statusMessage.value = "Waiting for host approval..."
                    val requestId = currentResp.requestId
                    val pollStart = System.currentTimeMillis()
                    while (isActive && System.currentTimeMillis() - pollStart < 120_000L) {
                        delay(1000L)
                        val pollReq = Request.Builder()
                            .url("http://${targetHost.ipAddress}:${targetHost.httpPort}/api/v1/join-request/status?requestId=$requestId")
                            .get()
                            .build()
                        val pollResp = httpClient.newCall(pollReq).execute()
                        if (pollResp.isSuccessful) {
                            val pollBody = pollResp.body?.string() ?: ""
                            val updated = runCatching { json.decodeFromString<ZeroScanJoinResponse>(pollBody) }.getOrNull()
                            if (updated != null && updated.status != "pending") {
                                currentResp = updated
                                break
                            }
                        }
                    }
                }

                val finalResp = currentResp
                if (finalResp.status == "approved" && !finalResp.hostPublicKey.isNullOrBlank() && !finalResp.assignedKeyId.isNullOrBlank() && finalResp.sessionEpoch != null) {
                    val hostPubKeyBytes = CryptoUtils.base64UrlDecode(finalResp.hostPublicKey)
                    val hostPublicKey = CryptoUtils.decodeUncompressedP256(hostPubKeyBytes)

                    val sharedSecret = CryptoUtils.computeEcdhSharedSecret(ephemeralKeyPair.private, hostPublicKey)
                    val pairwiseSecret = CryptoUtils.derivePairwiseSecret(sharedSecret, ByteArray(32))
                    val (wrapped, iv) = deviceRepository.encryptSecret(pairwiseSecret)

                    val hostName = finalResp.hostName ?: targetHost.deviceName
                    val peerRecord = AndroidPeerAuth(
                        keyId = finalResp.assignedKeyId,
                        peerDeviceId = targetHost.deviceId,
                        label = hostName,
                        wrappedSecret = wrapped,
                        iv = iv,
                        sessionEpoch = finalResp.sessionEpoch,
                        highestSeq = 0L,
                        targetScope = "default",
                        hostIp = targetHost.ipAddress,
                        port = targetHost.socketPort
                    )

                    deviceRepository.savePeer(peerRecord)
                    deviceRepository.isPrimaryHost = false
                    hostBeaconManager.evaluateHostElection()

                    _statusMessage.value = "Joined household '$hostName' successfully!"
                    syncPeerDirectoryFromHost(peerRecord)
                    onResult?.invoke(true, "Joined household '$hostName' successfully!")
                } else if (finalResp.status == "declined") {
                    _statusMessage.value = "Join request was declined by host"
                    onResult?.invoke(false, "Join request declined")
                } else {
                    _statusMessage.value = "Timed out waiting for host approval"
                    onResult?.invoke(false, "Approval timed out")
                }
            } catch (e: Exception) {
                android.util.Log.e("BuzzMe", "Exception during sendJoinRequest", e)
                val msg = e.message ?: "Connection error"
                _statusMessage.value = "Join failed: $msg"
                onResult?.invoke(false, msg)
            }
        }
    }

    fun requestDesignateAsHost(
        onBlocked: ((existingHostName: String, existingHostIp: String) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val myDeviceId = deviceRepository.hostDeviceId.toString()
            val existingHost = discoveredHost.value?.takeIf { it.deviceId != myDeviceId }
                ?: currentActiveHost.value?.takeIf { it.deviceId != myDeviceId && it.role == com.buzzme.app.data.model.HostRole.PRIMARY_HOST }?.let {
                    com.buzzme.app.network.DiscoveredHost(
                        deviceId = it.deviceId,
                        deviceName = it.deviceName,
                        ipAddress = it.ipAddress
                    )
                }

            if (existingHost != null) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Cannot become Host: '${existingHost.deviceName}' is already the Household Host."
                    onBlocked?.invoke(existingHost.deviceName, existingHost.ipAddress)
                }
                return@launch
            }

            // Check if active beacon election has detected another primary host
            val activeBeacon = hostBeaconManager.currentActiveHost.value
            if (activeBeacon != null && activeBeacon.deviceId != myDeviceId && activeBeacon.role == com.buzzme.app.data.model.HostRole.PRIMARY_HOST) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Cannot become Host: '${activeBeacon.deviceName}' is already the Household Host."
                    onBlocked?.invoke(activeBeacon.deviceName, activeBeacon.ipAddress)
                }
                return@launch
            }

            // Check if we are already paired to a host peer as a member
            val hostPeer = peers.value.firstOrNull { it.targetScope == "default" && !it.keyId.startsWith("relay-") }
            if (hostPeer != null) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Cannot become Host: This device is already joined to '${hostPeer.label}'."
                    onBlocked?.invoke(hostPeer.label, hostPeer.hostIp ?: "")
                }
                return@launch
            }

            deviceRepository.isPrimaryHost = true
            sessionController.updateMdnsAdvertising()
            hostBeaconManager.evaluateHostElection()
            withContext(Dispatchers.Main) {
                _statusMessage.value = "This device is now the Household Host."
                onSuccess?.invoke()
            }
        }
    }

    fun setIsPrimaryHost(isPrimary: Boolean) {
        if (isPrimary) {
            requestDesignateAsHost()
        } else {
            deviceRepository.isPrimaryHost = false
            sessionController.updateMdnsAdvertising()
            hostBeaconManager.evaluateHostElection()
        }
    }

    private val _lastReceivedBuzz = MutableStateFlow<ReceivedBuzz?>(
        deviceRepository.getLastReceivedNote()?.let { (sender, msg, time) ->
            ReceivedBuzz(sender, msg, time)
        }
    )
    val lastReceivedBuzz: StateFlow<ReceivedBuzz?> = _lastReceivedBuzz.asStateFlow()

    fun setLastReceivedBuzz(senderLabel: String, message: String) {
        val now = System.currentTimeMillis()
        val buzz = ReceivedBuzz(senderLabel, message, now)
        _lastReceivedBuzz.value = buzz
        deviceRepository.saveLastReceivedNote(senderLabel, message, now)
    }

    fun clearLastReceivedBuzz() {
        _lastReceivedBuzz.value = null
        deviceRepository.clearLastReceivedNote()
    }

    fun setDeviceName(name: String) {
        deviceRepository.setDeviceName(name)
    }

    fun getSuggestedDeviceName(): String {
        val model = Build.MODEL ?: ""
        return if (model.isNotBlank()) model else "My Phone"
    }

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient()

    init {
        app.socketServer.alertListener = { sender, msg ->
            setLastReceivedBuzz(sender, msg)
        }

        // When a new peer pairs with us (as host), push the updated directory to all peers
        app.httpGateway.onNewPeerPaired = {
            pushPeerDirectoryToAllPeers()
        }

        // Auto-start the session whenever a WiFi IP is available.
        viewModelScope.launch {
            networkManager.wifiIpAddress.collect { ip ->
                val sessionActive = sessionController.sessionState.value is SessionState.Active
                if (!ip.isNullOrBlank() && !sessionActive) {
                    android.util.Log.i("BuzzMe", "WiFi available ($ip) — auto-starting session")
                    startSession()
                }
            }
        }

        // Periodic directory sync for client peers (every 5 seconds) so new household
        // members appear automatically without requiring re-pairing or manual restart.
        viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(5_000L)
                refreshPeerDirectory()
            }
        }
    }

    fun refreshPeerDirectory() {
        // Any device that enrolled with a household host has a record with targetScope == "default".
        // It should always sync its household directory from that host when reachable.
        val hostRecord = peers.value.firstOrNull { it.targetScope == "default" && !it.keyId.startsWith("relay-") }
        if (hostRecord != null) {
            val activeHostIp = currentActiveHost.value?.ipAddress
                ?: discoveredHost.value?.ipAddress
                ?: hostRecord.hostIp
            if (!activeHostIp.isNullOrBlank()) {
                if (activeHostIp != hostRecord.hostIp) {
                    viewModelScope.launch(Dispatchers.IO) {
                        deviceRepository.updatePeerIp(hostRecord.keyId, activeHostIp)
                    }
                }
                syncPeerDirectoryFromHost(hostRecord.copy(hostIp = activeHostIp))
            }
        }
    }

    fun clearStatus() {
        _statusMessage.value = null
    }

    fun startSession() {
        // Persist the user's intent to auto-restart on future reboots
        deviceRepository.autoStartEnabled = true
        val intent = Intent(app, BuzzForegroundService::class.java).apply {
            action = BuzzForegroundService.ACTION_START_SESSION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun stopSession() {
        // User explicitly stopped — disable auto-restart on next boot
        deviceRepository.autoStartEnabled = false
        val intent = Intent(app, BuzzForegroundService::class.java).apply {
            action = BuzzForegroundService.ACTION_STOP_SESSION
        }
        app.startService(intent)
    }

    fun triggerTestAlert() {
        AlertEngine.dispatchBuzzAlert(app, "Test Alert", "Testing sound, vibration, and DND bypass", senderId = "test_device")
        val level = AlertEngine.getEscalationLevel("test_device")
        _statusMessage.value = "Test alert dispatched (Level $level)"
    }

    /**
     * Sends a buzz to [peer]. If we are a non-host device and the target peer's device ID
     * matches the Host's known device ID, we send directly. For any other peer (i.e., a
     * household sibling discovered via directory sync), we route through the Host relay endpoint.
     */
    fun sendBuzzToPeer(peer: AndroidPeerAuth, message: String = "Buzz!") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.i("BuzzMe", "sendBuzzToPeer triggered for '${peer.label}', keyId=${peer.keyId}, ip=${peer.hostIp}")

                // Relay-stubs are created by syncPeerDirectoryFromHost with keyId prefixed "relay-".
                // They don't have a real pairwise secret and must always go through the Host relay endpoint.
                val isRelayStub = peer.keyId.startsWith("relay-")

                if (isRelayStub) {
                    // Find the Host peer record to relay through.
                    // Prefer the one matching the current active host beacon; fall back to the first
                    // non-relay peer (there should only ever be one real host record on a peer device).
                    val hostRecord = peers.value.firstOrNull {
                        !it.keyId.startsWith("relay-") &&
                        it.peerDeviceId == (currentActiveHost.value?.deviceId ?: "")
                    } ?: peers.value.firstOrNull { !it.keyId.startsWith("relay-") }

                    if (hostRecord == null) {
                        _statusMessage.value = "Cannot buzz ${peer.label}: Host not found. Make sure you're paired with the household Host."
                        android.util.Log.e("BuzzMe", "sendBuzzToPeer relay failed: no host record found in peer list")
                        return@launch
                    }
                    android.util.Log.i("BuzzMe", "Relay-stub detected — relaying buzz for '${peer.label}' via Host '${hostRecord.label}' at ${hostRecord.hostIp}")
                    sendBuzzViaRelay(hostRecord, peer.peerDeviceId, peer.label, message)
                    return@launch
                }

                // Direct TCP buzz — peer is the Host or another directly-reachable device
                val hostIp = peer.hostIp
                if (hostIp.isNullOrBlank()) {
                    _statusMessage.value = "Cannot buzz ${peer.label}: IP address unknown"
                    android.util.Log.e("BuzzMe", "sendBuzzToPeer failed: hostIp is blank for '${peer.label}'")
                    return@launch
                }
                val secret = deviceRepository.decryptSecret(peer.wrappedSecret, peer.iv)
                val newSeq = peer.highestSeq + 1
                android.util.Log.i("BuzzMe", "Direct TCP buzz to '${peer.label}' at $hostIp:${peer.port}, seq=$newSeq")
                val result = SocketClient.sendBuzz(
                    host = hostIp,
                    port = peer.port,
                    senderKeyId = UUID.fromString(peer.keyId),
                    targetDeviceId = UUID.fromString(peer.peerDeviceId),
                    sessionEpoch = peer.sessionEpoch,
                    sequence = newSeq,
                    message = message,
                    pairwiseSecret = secret
                )
                android.util.Log.i("BuzzMe", "SocketClient.sendBuzz result: $result")
                when (result) {
                    is SocketClient.SendResult.Success -> {
                        deviceRepository.updatePeerSequence(peer.keyId, newSeq)
                        _statusMessage.value = "Buzz delivered to ${peer.label}!"
                        android.util.Log.i("BuzzMe", "Buzz delivered to '${peer.label}', seq=$newSeq")
                    }
                    is SocketClient.SendResult.Error -> {
                        _statusMessage.value = "Buzz to ${peer.label} failed: ${result.message}"
                        android.util.Log.e("BuzzMe", "Buzz delivery failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BuzzMe", "Exception in sendBuzzToPeer", e)
                _statusMessage.value = "Error buzzing ${peer.label}: ${e.message}"
            }
        }
    }

    /**
     * Sends a relay request to the Host so it can forward the buzz to [targetPeerDeviceId].
     * Authenticated with HMAC-SHA256 of the request body using the Host pairwise secret.
     */
    private suspend fun sendBuzzViaRelay(
        hostPeer: AndroidPeerAuth,
        targetPeerDeviceId: String,
        targetLabel: String,
        message: String = "Buzz!"
    ) {
        val hostIp = hostPeer.hostIp ?: run {
            _statusMessage.value = "Cannot relay: Host IP unknown"
            return
        }
        val myLabel = deviceRepository.deviceName.value?.takeIf { it.isNotBlank() } ?: "Peer"
        val relayBody = json.encodeToString(
            RelayBuzzPayload(
                targetPeerDeviceId = targetPeerDeviceId,
                senderLabel = myLabel,
                message = message
            )
        )
        val bodyBytes = relayBody.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val secret = deviceRepository.decryptSecret(hostPeer.wrappedSecret, hostPeer.iv)
        val hmacBytes = com.buzzme.app.crypto.CryptoUtils.hmacSha256(secret, bodyBytes)
        val hmacHex = hmacBytes.joinToString("") { "%02x".format(it) }

        val relayUrl = "http://$hostIp:8900/api/v1/buzz/relay"
        android.util.Log.i("BuzzMe", "sendBuzzViaRelay: posting to $relayUrl, target=$targetPeerDeviceId ($targetLabel)")
        val request = okhttp3.Request.Builder()
            .url(relayUrl)
            .addHeader("X-BuzzMe-Relay-HMAC", hmacHex)
            .addHeader("X-BuzzMe-Sender-KeyId", hostPeer.keyId)
            .post(relayBody.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            // Use a dedicated relay client with shorter timeouts for a snappier error experience
            val relayClient = httpClient.newBuilder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val response = relayClient.newCall(request).execute()
            if (response.isSuccessful) {
                android.util.Log.i("BuzzMe", "Relay buzz delivered to '$targetLabel' successfully via Host")
                _statusMessage.value = "Buzz delivered to $targetLabel!"
            } else {
                val rawErr = response.body?.string() ?: ""
                // Try to extract the "error" field from JSON, otherwise show the raw body
                val errMsg = runCatching {
                    json.decodeFromString<Map<String, String>>(rawErr)["error"]
                }.getOrNull() ?: rawErr.ifBlank { "HTTP ${response.code}" }
                android.util.Log.e("BuzzMe", "Relay buzz to '$targetLabel' failed (${ response.code}): $errMsg")
                _statusMessage.value = "Could not buzz $targetLabel: $errMsg"
            }
        } catch (e: Exception) {
            android.util.Log.e("BuzzMe", "Relay request exception for '$targetLabel'", e)
            _statusMessage.value = "Could not reach $targetLabel via relay: ${e.message}"
        }
    }

    /**
     * Fetches the peer directory from the Host and merges it into local storage.
     * Called after successful pairing to discover sibling peer devices.
     */
    fun syncPeerDirectoryFromHost(hostPeer: AndroidPeerAuth) {
        viewModelScope.launch(Dispatchers.IO) {
            val hostIp = hostPeer.hostIp ?: return@launch
            android.util.Log.i("BuzzMe", "syncPeerDirectoryFromHost: fetching from $hostIp:8900")
            val directoryUrl = "http://$hostIp:8900/api/v1/peers/directory"
            val request = okhttp3.Request.Builder()
                .url(directoryUrl)
                .addHeader("X-BuzzMe-Sender-KeyId", hostPeer.keyId)
                .get()
                .build()
            try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@launch
                    val entries = runCatching {
                        json.decodeFromString<List<PeerDirectoryEntry>>(body)
                    }.getOrNull() ?: return@launch
                    android.util.Log.i("BuzzMe", "Directory sync received ${entries.size} peer entries")
                    
                    var addedCount = 0
                    for (entry in entries) {
                        if (entry.peerDeviceId == deviceRepository.hostDeviceId.toString()) continue
                        if (entry.isHost) {
                            if (!entry.ip.isNullOrBlank() && entry.ip != hostPeer.hostIp) {
                                deviceRepository.updatePeerIp(hostPeer.keyId, entry.ip)
                            }
                            continue
                        }

                        // Check if we already have this peer
                        val existing = deviceRepository.peers.value.find { it.peerDeviceId == entry.peerDeviceId }
                        if (existing != null) {
                            val newIp = entry.ip ?: existing.hostIp
                            if (existing.label != entry.label || existing.hostIp != newIp) {
                                val updated = existing.copy(
                                    label = entry.label,
                                    hostIp = newIp
                                )
                                deviceRepository.savePeerSync(updated)
                            }
                            continue
                        }

                        // Create a relay-stub peer record — actual buzzes will route via Host relay
                        val stubRecord = com.buzzme.app.data.model.AndroidPeerAuth(
                            keyId = "relay-${entry.peerDeviceId}",
                            peerDeviceId = entry.peerDeviceId,
                            label = entry.label,
                            wrappedSecret = hostPeer.wrappedSecret,
                            iv = hostPeer.iv,
                            sessionEpoch = hostPeer.sessionEpoch,
                            highestSeq = 0L,
                            targetScope = "relay",
                            hostIp = entry.ip ?: hostPeer.hostIp,
                            port = entry.port
                        )
                        deviceRepository.savePeer(stubRecord)
                        addedCount++
                        android.util.Log.i("BuzzMe", "Saved relay-stub peer: ${entry.label} (${entry.deviceType})")
                    }
                    if (addedCount > 0) {
                        _statusMessage.value = "Discovered $addedCount household member(s)"
                    }
                } else {
                    android.util.Log.w("BuzzMe", "Directory sync failed: ${response.code}")
                }
            } catch (e: Exception) {
                android.util.Log.w("BuzzMe", "Directory sync exception", e)
            }
        }
    }

    /**
     * After a new peer joins, the Host pushes the updated full peer list to all existing peers.
     * This ensures every household member can see (and relay-buzz) the newcomer.
     */
    fun pushPeerDirectoryToAllPeers() {
        if (!deviceRepository.isPrimaryHost) return
        viewModelScope.launch(Dispatchers.IO) {
            val hostName = deviceRepository.deviceName.value?.takeIf { it.isNotBlank() } ?: "Household Host"
            val hostWifiIp = networkManager.wifiIpAddress.value ?: "127.0.0.1"

            val roster = mutableListOf<PeerDirectoryEntry>()
            // 1. Host
            roster.add(
                PeerDirectoryEntry(
                    peerDeviceId = deviceRepository.hostDeviceId.toString(),
                    label = hostName,
                    ip = hostWifiIp,
                    port = 8899,
                    deviceType = "android",
                    isHost = true
                )
            )
            // 2. Android peers
            deviceRepository.peers.value
                .filter { !it.keyId.startsWith("relay-") }
                .forEach {
                    roster.add(
                        PeerDirectoryEntry(
                            peerDeviceId = it.peerDeviceId,
                            label = it.label,
                            ip = it.hostIp,
                            port = it.port,
                            deviceType = "android",
                            isHost = false
                        )
                    )
                }
            // 3. iOS tokens
            deviceRepository.capabilityTokens.value
                .filter { !it.revoked }
                .forEach {
                    roster.add(
                        PeerDirectoryEntry(
                            peerDeviceId = it.lookupId,
                            label = it.label,
                            ip = null,
                            port = 0,
                            deviceType = "ios",
                            isHost = false
                        )
                    )
                }

            val rosterJson = json.encodeToString(roster)
            for (peer in deviceRepository.peers.value) {
                if (peer.keyId.startsWith("relay-")) continue
                val ip = peer.hostIp ?: continue
                if (ip.startsWith("10.0.2.")) continue // Skip emulator virtual IPs
                val syncUrl = "http://$ip:8900/api/v1/peers/sync"
                android.util.Log.i("BuzzMe", "pushPeerDirectoryToAllPeers: syncing to ${peer.label} at $ip")
                try {
                    val request = okhttp3.Request.Builder()
                        .url(syncUrl)
                        .post(rosterJson.toRequestBody("application/json".toMediaType()))
                        .build()
                    val response = httpClient.newBuilder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        .newCall(request).execute()
                    android.util.Log.i("BuzzMe", "Peer directory pushed to ${peer.label}: ${response.code}")
                } catch (e: Exception) {
                    android.util.Log.w("BuzzMe", "Failed to push directory to ${peer.label}: ${e.message}")
                }
            }
        }
    }


    fun sendBuzzToPush(sub: PushSubscription, message: String = "Buzz!") {
        viewModelScope.launch {
            val result = webPushManager.dispatchBuzzPush(sub, message)
            when (result) {
                is WebPushManager.PushResult.Success -> {
                    _statusMessage.value = "APNs push delivered to ${sub.label}!"
                }
                is WebPushManager.PushResult.Failure -> {
                    _statusMessage.value = "Push delivery failed: ${result.reason}"
                }
            }
        }
    }

    // --- Pairing Actions ---

    fun startShortcutPairing(label: String, targetScope: String = "Home") {
        deviceRepository.createPendingShortcutPairing(targetScope, label)
    }

    fun startAndroidPairing(label: String, targetScope: String = "Home") {
        if (!deviceRepository.isPrimaryHost) {
            requestDesignateAsHost(
                onBlocked = { hostName, hostIp ->
                    _statusMessage.value = "Cannot host: '$hostName' ($hostIp) is already the Household Host."
                },
                onSuccess = {
                    deviceRepository.createPendingAndroidPairing(targetScope, label)
                }
            )
            return
        }
        deviceRepository.createPendingAndroidPairing(targetScope, label)
    }

    fun approvePairing(pairingId: String) {
        val approved = deviceRepository.approvePairingSession(pairingId)
        if (approved) {
            _statusMessage.value = "Pairing approved."
        }
    }

    fun rejectPairing(pairingId: String) {
        deviceRepository.rejectPairingSession(pairingId)
        _statusMessage.value = "Pairing rejected"
    }

    fun dismissPairingDialog() {
        deviceRepository.clearCurrentPairingSession()
    }

    data class PendingDeepLinkPairing(
        val rawUrl: String,
        val hostPayload: HostQrPayload
    )

    private val _pendingDeepLinkPairing = MutableStateFlow<PendingDeepLinkPairing?>(null)
    val pendingDeepLinkPairing: StateFlow<PendingDeepLinkPairing?> = _pendingDeepLinkPairing.asStateFlow()

    fun handleIncomingDeepLink(url: String): Boolean {
        return try {
            val payload = HostQrPayload.parse(url)
            _pendingDeepLinkPairing.value = PendingDeepLinkPairing(url, payload)
            true
        } catch (e: Exception) {
            android.util.Log.e("BuzzMe", "Failed to parse incoming deep link: $url", e)
            _statusMessage.value = "Invalid pairing link: ${e.message}"
            false
        }
    }

    fun dismissPendingDeepLink() {
        _pendingDeepLinkPairing.value = null
    }

    /**
     * Optical Enrollment for iOS PWA:
     * Parses QR code JSON:
     * { "v": 1, "type": "IOS_PWA", "deviceId": "...", "label": "...", "endpoint": "https://web.push.apple.com/...", "p256dh": "...", "auth": "..." }
     */
    fun enrollScannedIosPwa(qrJson: String): Boolean {
        return try {
            val pwaData = json.decodeFromString<IosPwaQrPayload>(qrJson)
            require(pwaData.type == "IOS_PWA") { "Not an iOS PWA subscription QR" }

            // Strictly validate endpoint domain
            webPushManager.validateEndpoint(pwaData.endpoint)

            val subscription = PushSubscription(
                id = UUID.randomUUID().toString(),
                deviceId = pwaData.deviceId,
                label = pwaData.label.ifBlank { "iOS PWA Receiver" },
                endpoint = pwaData.endpoint,
                p256dh = pwaData.p256dh,
                auth = pwaData.auth
            )

            viewModelScope.launch {
                deviceRepository.savePushSubscription(subscription)
                _statusMessage.value = "Enrolled iOS receiver: ${subscription.label}"
            }
            true
        } catch (e: Exception) {
            _statusMessage.value = "Enrollment error: ${e.message}"
            false
        }
    }

    /**
     * Android Initiator Peer Handshake:
     * Scans Host QR: { "hostIp": "...", "port": 8900, "pairingId": "...", "nonce": "...", "hostPubKey": "..." }
     * OR deep link URL: https://buzzme.app/pair?ip=...&port=8900&pid=...
     */
    fun executePeerHandshakeFromQr(
        qrJson: String,
        peerLabel: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        android.util.Log.i("BuzzMe", "executePeerHandshakeFromQr started with label=$peerLabel, qrJson=$qrJson")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hostData = HostQrPayload.parse(qrJson)
                android.util.Log.i("BuzzMe", "Decoded hostData: $hostData")
                val peerKeyPair = CryptoUtils.generateEcKeyPair()
                val peerPubKeyBytes = CryptoUtils.encodeUncompressedP256(peerKeyPair.public as ECPublicKey)
                val peerPubKeyBase64 = CryptoUtils.base64UrlEncode(peerPubKeyBytes)

                val hostPubKeyBytes = CryptoUtils.base64UrlDecode(hostData.hostPubKey)
                val hostPublicKey = CryptoUtils.decodeUncompressedP256(hostPubKeyBytes)

                val sharedSecret = CryptoUtils.computeEcdhSharedSecret(peerKeyPair.private, hostPublicKey)
                val pairingNonceBytes = CryptoUtils.base64UrlDecode(hostData.nonce)
                val handshakeKey = CryptoUtils.deriveHandshakeKey(sharedSecret, pairingNonceBytes)

                val pairingIdBytes = CryptoUtils.base64UrlDecode(hostData.pairingId)
                val hostUuid = UUID.fromString(hostData.hostDeviceId)
                val peerUuid = deviceRepository.hostDeviceId

                val transcript = CryptoUtils.buildCanonicalTranscript(
                    pairingId = pairingIdBytes,
                    pairingNonce = pairingNonceBytes,
                    hostDeviceId = hostUuid,
                    peerDeviceId = peerUuid,
                    hostEphemeralPublicKey = hostPubKeyBytes,
                    peerEphemeralPublicKey = peerPubKeyBytes
                )
                val transcriptHash = CryptoUtils.sha256(transcript)
                val verificationCode = CryptoUtils.computeVerificationCode(handshakeKey, transcriptHash)
                val nonceProof = CryptoUtils.computeNonceProof(handshakeKey, transcriptHash)
                val nonceProofBase64 = CryptoUtils.base64UrlEncode(nonceProof)

                // POST to Host /api/v1/pair/android-confirm
                val myWifiIp = networkManager.wifiIpAddress.value
                val myDeviceName = deviceRepository.deviceName.value?.takeIf { it.isNotBlank() } ?: peerLabel

                val requestBodyJson = json.encodeToString(
                    AndroidPairConfirmPayload(
                        pairingId = hostData.pairingId,
                        peerId = peerUuid.toString(),
                        peerPubKey = peerPubKeyBase64,
                        nonceProof = nonceProofBase64,
                        peerIp = myWifiIp,
                        peerDeviceName = myDeviceName
                    )
                )

                val hostUrl = "http://${hostData.hostIp}:${hostData.port}/api/v1/pair/android-confirm"
                android.util.Log.i("BuzzMe", "Sending confirm request to hostUrl=$hostUrl")
                val request = Request.Builder()
                    .url(hostUrl)
                    .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val pairingHttpClient = httpClient.newBuilder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(70, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = pairingHttpClient.newCall(request).execute()
                android.util.Log.i("BuzzMe", "Host confirm response code=${response.code}")
                if (response.isSuccessful && response.code == 200) {
                    val respBody = response.body?.string() ?: ""
                    android.util.Log.i("BuzzMe", "Host response body=$respBody")
                    val respMap = runCatching { json.decodeFromString<Map<String, String>>(respBody) }.getOrNull()
                    val returnedKeyId = respMap?.get("keyId")
                    val returnedEpoch = respMap?.get("sessionEpoch")?.toLongOrNull()
                    val returnedHostName = respMap?.get("hostDeviceName")?.takeIf { it.isNotBlank() }
                        ?: hostData.hostDeviceName?.takeIf { it.isNotBlank() }
                        ?: "Host"

                    if (returnedKeyId != null && returnedEpoch != null) {
                        val pairwiseSecret = CryptoUtils.derivePairwiseSecret(sharedSecret, pairingNonceBytes)
                        val (wrapped, iv) = deviceRepository.encryptSecret(pairwiseSecret)
                        val peerRecord = AndroidPeerAuth(
                            keyId = returnedKeyId,
                            peerDeviceId = hostData.hostDeviceId,
                            label = returnedHostName,
                            wrappedSecret = wrapped,
                            iv = iv,
                            sessionEpoch = returnedEpoch,
                            highestSeq = 0L,
                            targetScope = "default",
                            hostIp = hostData.hostIp,
                            port = 8899
                        )
                        deviceRepository.savePeer(peerRecord)
                        // This device joined as a peer/client, so it is not the primary host
                        deviceRepository.isPrimaryHost = false
                        hostBeaconManager.evaluateHostElection()
                        android.util.Log.i("BuzzMe", "Saved peer record: $peerRecord")
                        _statusMessage.value = "Paired successfully with $returnedHostName!"
                        // Kick off directory sync to discover sibling household peers
                        syncPeerDirectoryFromHost(peerRecord)
                        onResult?.invoke(true, "Paired successfully with $returnedHostName!")
                    } else {
                        android.util.Log.e("BuzzMe", "Pairing response missing keyId or sessionEpoch")
                        val msg = "Missing credentials from host"
                        _statusMessage.value = "Pairing failed: $msg"
                        onResult?.invoke(false, msg)
                    }
                } else {
                    android.util.Log.e("BuzzMe", "Pairing rejected by Host (${response.code})")
                    val msg = "Host rejected pairing (${response.code})"
                    _statusMessage.value = msg
                    onResult?.invoke(false, msg)
                }
            } catch (e: Exception) {
                android.util.Log.e("BuzzMe", "Handshake failed", e)
                val msg = e.message ?: "Connection error"
                _statusMessage.value = "Handshake failed: $msg"
                onResult?.invoke(false, msg)
            }
        }
    }

    fun revokeCapabilityToken(lookupId: String) {
        viewModelScope.launch {
            deviceRepository.revokeCapabilityToken(lookupId)
            _statusMessage.value = "Capability token revoked"
        }
    }

    fun removePeer(keyId: String) {
        viewModelScope.launch {
            deviceRepository.removePeer(keyId)
            _statusMessage.value = "Peer removed"
        }
    }

    fun updatePeerIp(keyId: String, newIp: String) {
        viewModelScope.launch {
            deviceRepository.updatePeerIp(keyId, newIp)
            _statusMessage.value = "Peer IP updated to $newIp"
        }
    }

    fun removeSubscription(id: String) {
        viewModelScope.launch {
            deviceRepository.removePushSubscription(id)
            _statusMessage.value = "Subscription removed"
        }
    }

    @Serializable
    data class IosPwaQrPayload(
        val v: Int = 1,
        val type: String,
        val deviceId: String,
        val label: String,
        val endpoint: String,
        val p256dh: String,
        val auth: String
    )

    @Serializable
    data class HostQrPayload(
        val hostIp: String,
        val port: Int = 8900,
        val pairingId: String,
        val nonce: String,
        val hostPubKey: String,
        val hostDeviceId: String,
        val hostDeviceName: String? = null
    ) {
        fun toDeepLinkUrl(): String {
            val encIp = java.net.URLEncoder.encode(hostIp, "UTF-8")
            val encPid = java.net.URLEncoder.encode(pairingId, "UTF-8")
            val encNonce = java.net.URLEncoder.encode(nonce, "UTF-8")
            val encPub = java.net.URLEncoder.encode(hostPubKey, "UTF-8")
            val encId = java.net.URLEncoder.encode(hostDeviceId, "UTF-8")
            val encName = java.net.URLEncoder.encode(hostDeviceName ?: "Host", "UTF-8")
            return "https://buzzme.app/pair?ip=$encIp&port=$port&pid=$encPid&nonce=$encNonce&pub=$encPub&id=$encId&name=$encName"
        }

        fun toAlternateDeepLinkUrl(): String {
            val encIp = java.net.URLEncoder.encode(hostIp, "UTF-8")
            val encPid = java.net.URLEncoder.encode(pairingId, "UTF-8")
            val encNonce = java.net.URLEncoder.encode(nonce, "UTF-8")
            val encPub = java.net.URLEncoder.encode(hostPubKey, "UTF-8")
            val encId = java.net.URLEncoder.encode(hostDeviceId, "UTF-8")
            val encName = java.net.URLEncoder.encode(hostDeviceName ?: "Host", "UTF-8")
            return "buzzme://pair?ip=$encIp&port=$port&pid=$encPid&nonce=$encNonce&pub=$encPub&id=$encId&name=$encName"
        }

        companion object {
            fun parse(raw: String): HostQrPayload {
                val trimmed = raw.trim()
                if (trimmed.startsWith("{")) {
                    return Json { ignoreUnknownKeys = true }.decodeFromString<HostQrPayload>(trimmed)
                }

                val queryParams = parseQueryParams(trimmed)
                val ip = queryParams["ip"] ?: queryParams["hostIp"]
                    ?: throw IllegalArgumentException("Missing host IP in pairing link")
                val port = queryParams["port"]?.toIntOrNull() ?: 8900
                val pid = (queryParams["pid"] ?: queryParams["pairingId"])?.replace(' ', '+')
                    ?: throw IllegalArgumentException("Missing pairing ID in pairing link")
                val nonce = (queryParams["nonce"])?.replace(' ', '+')
                    ?: throw IllegalArgumentException("Missing nonce in pairing link")
                val pub = (queryParams["pub"] ?: queryParams["hostPubKey"])?.replace(' ', '+')
                    ?: throw IllegalArgumentException("Missing host public key in pairing link")
                val id = queryParams["id"] ?: queryParams["hostDeviceId"]
                    ?: throw IllegalArgumentException("Missing host device ID in pairing link")
                val name = queryParams["name"] ?: queryParams["hostDeviceName"] ?: "Host"

                return HostQrPayload(
                    hostIp = ip,
                    port = port,
                    pairingId = pid,
                    nonce = nonce,
                    hostPubKey = pub,
                    hostDeviceId = id,
                    hostDeviceName = name
                )
            }

            private fun parseQueryParams(url: String): Map<String, String> {
                val qIndex = url.indexOf('?')
                if (qIndex == -1 || qIndex == url.length - 1) return emptyMap()
                val query = url.substring(qIndex + 1).split('#')[0]
                return query.split('&').mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val key = try {
                            java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        } catch (e: Exception) {
                            pair.substring(0, idx)
                        }
                        val value = if (idx < pair.length - 1) {
                            try {
                                java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                            } catch (e: Exception) {
                                pair.substring(idx + 1)
                            }
                        } else ""
                        key to value
                    } else null
                }.toMap()
            }
        }
    }

    @Serializable
    data class AndroidPairConfirmPayload(
        val pairingId: String,
        val peerId: String,
        val peerPubKey: String,
        val nonceProof: String,
        val peerIp: String? = null,
        val peerDeviceName: String? = null
    )

    @Serializable
    data class RelayBuzzPayload(
        val targetPeerDeviceId: String,
        val senderLabel: String? = null,
        val message: String? = null
    )

    @Serializable
    data class PeerDirectoryEntry(
        val peerDeviceId: String,
        val label: String,
        val ip: String? = null,
        val port: Int = 8899,
        val deviceType: String = "android",
        val isHost: Boolean = false
    )

    @Serializable
    data class ZeroScanJoinPayload(
        val deviceName: String,
        val deviceType: String = "android",
        val peerId: String? = null,
        val peerPubKey: String? = null
    )

    @Serializable
    data class ZeroScanJoinResponse(
        val requestId: String,
        val status: String,
        val deviceName: String,
        val hostName: String? = null,
        val iosToken: String? = null,
        val assignedKeyId: String? = null,
        val sessionEpoch: Long? = null,
        val hostPublicKey: String? = null
    )
}
