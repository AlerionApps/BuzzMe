package com.buzzme.app.network.web

import android.content.Context
import com.buzzme.app.crypto.CryptoUtils
import com.buzzme.app.data.model.AndroidPeerAuth
import com.buzzme.app.data.model.PairingState
import com.buzzme.app.data.repository.DeviceRepository
import com.buzzme.app.network.SocketClient
import com.buzzme.app.service.AlertEngine
import kotlinx.serialization.encodeToString
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.ktor.server.engine.EmbeddedServer

class HttpGateway(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val onAlertReceived: ((senderLabel: String, message: String) -> Unit)? = null
) {
    private var server: EmbeddedServer<*, *>? = null
    private val rateLimitMap = ConcurrentHashMap<String, Long>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Set by MainViewModel after init to receive new-peer-paired notifications. */
    var onNewPeerPaired: (() -> Unit)? = null

    val isRunning: Boolean
        get() = server != null

    fun start(port: Int = 8900, host: String = "0.0.0.0") {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = host) {
            configureServer()
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }

    private fun Application.configureServer() {
        install(ContentNegotiation) {
            json()
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "Internal error")))
            }
        }

        routing {
            // 1. Host Info Discovery Endpoint
            get("/api/v1/info") {
                val hostName = deviceRepository.deviceName.value?.takeIf { it.isNotBlank() } ?: "Household Host"
                call.respond(
                    HttpStatusCode.OK,
                    HostInfoResponse(
                        deviceId = deviceRepository.hostDeviceId.toString(),
                        deviceName = hostName,
                        status = "online",
                        isHost = deviceRepository.isPrimaryHost
                    )
                )
            }

            // 2. Zero-Scan Android Join Request Submission Endpoint
            post("/api/v1/join-request") {
                val rawBody = call.receiveText()
                val req = runCatching { json.decodeFromString<JoinRequestPayload>(rawBody) }.getOrNull()
                if (req == null || req.deviceName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid join request payload"))
                    return@post
                }

                val tcpRemoteIp = call.request.local.remoteHost
                val isEmulatorVirtualIp = req.peerIp?.startsWith("10.0.2.") == true
                val resolvedPeerIp = when {
                    !req.peerIp.isNullOrBlank() &&
                        !isEmulatorVirtualIp &&
                        req.peerIp.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) -> req.peerIp
                    !tcpRemoteIp.isNullOrBlank() &&
                        tcpRemoteIp != "0:0:0:0:0:0:0:1" &&
                        tcpRemoteIp != "127.0.0.1" -> tcpRemoteIp
                    else -> req.peerIp ?: tcpRemoteIp
                }

                val requestId = UUID.randomUUID().toString()
                val pendingReq = com.buzzme.app.data.model.PendingJoinRequest(
                    requestId = requestId,
                    deviceType = req.deviceType,
                    deviceName = req.deviceName.trim(),
                    deviceIp = resolvedPeerIp,
                    peerId = req.peerId,
                    peerPubKey = req.peerPubKey
                )

                deviceRepository.addPendingJoinRequest(pendingReq)

                // Auto-approve if host enabled autoApproveOnWifi
                val result = if (deviceRepository.autoApproveOnWifi) {
                    deviceRepository.approveJoinRequest(requestId) ?: pendingReq
                } else {
                    pendingReq
                }

                if (result.status == "approved") {
                    onNewPeerPaired?.invoke()
                }

                call.respond(
                    HttpStatusCode.OK,
                    JoinRequestStatusResponse(
                        requestId = result.requestId,
                        status = result.status,
                        deviceName = result.deviceName,
                        hostName = result.hostName ?: deviceRepository.deviceName.value ?: "Household Host",
                        assignedKeyId = result.assignedKeyId,
                        sessionEpoch = result.sessionEpoch,
                        hostPublicKey = result.hostPublicKey
                    )
                )
            }

            // 3. Join Request Status Polling Endpoint
            get("/api/v1/join-request/status") {
                val requestId = call.request.queryParameters["requestId"]
                if (requestId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing requestId parameter"))
                    return@get
                }

                val req = deviceRepository.getPendingJoinRequest(requestId)
                if (req == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Join request not found"))
                    return@get
                }

                call.respond(
                    HttpStatusCode.OK,
                    JoinRequestStatusResponse(
                        requestId = req.requestId,
                        status = req.status,
                        deviceName = req.deviceName,
                        hostName = req.hostName ?: deviceRepository.deviceName.value ?: "Household Host",
                        assignedKeyId = req.assignedKeyId,
                        sessionEpoch = req.sessionEpoch,
                        hostPublicKey = req.hostPublicKey
                    )
                )
            }

            // 4. Android-to-Android Pairwise Confirmation
            post("/api/v1/pair/android-confirm") {
                val rawBody = call.receiveText()
                if (rawBody.toByteArray(StandardCharsets.UTF_8).size > 2048) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Payload exceeds 2 KiB"))
                    return@post
                }

                val req = runCatching { json.decodeFromString<AndroidPairConfirmRequest>(rawBody) }.getOrNull()
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON payload"))
                    return@post
                }

                val session = deviceRepository.getPairingSession(req.pairingId)
                if (session == null || session.expiresAt < System.currentTimeMillis()) {
                    call.respond(HttpStatusCode.Gone, mapOf("error" to "Pairing session expired"))
                    return@post
                }

                val hostPrivKeyBase64 = session.hostEphemeralPrivateKeyPkcs8
                val hostPubKeyBytesBase64 = session.hostEphemeralPublicKeyBytes
                if (hostPrivKeyBase64 == null || hostPubKeyBytesBase64 == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid session state"))
                    return@post
                }

                try {
                    // Reconstruct host private key
                    val keyFactory = KeyFactory.getInstance("EC")
                    val privKeyBytes = Base64.getDecoder().decode(hostPrivKeyBase64)
                    val hostPrivateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privKeyBytes))

                    val peerPubKeyBytes = CryptoUtils.base64UrlDecode(req.peerPubKey)
                    val peerPublicKey = CryptoUtils.decodeUncompressedP256(peerPubKeyBytes)

                    val hostPubKeyBytes = Base64.getDecoder().decode(hostPubKeyBytesBase64)

                    // Compute ECDH shared secret
                    val sharedSecret = CryptoUtils.computeEcdhSharedSecret(hostPrivateKey, peerPublicKey)
                    val pairingNonceBytes = CryptoUtils.base64UrlDecode(session.pairingNonce)
                    val handshakeKey = CryptoUtils.deriveHandshakeKey(sharedSecret, pairingNonceBytes)

                    // Transcript Hash
                    val pairingIdBytes = CryptoUtils.base64UrlDecode(session.pairingId)
                    val peerUuid = UUID.fromString(req.peerId)
                    val transcript = CryptoUtils.buildCanonicalTranscript(
                        pairingId = pairingIdBytes,
                        pairingNonce = pairingNonceBytes,
                        hostDeviceId = deviceRepository.hostDeviceId,
                        peerDeviceId = peerUuid,
                        hostEphemeralPublicKey = hostPubKeyBytes,
                        peerEphemeralPublicKey = peerPubKeyBytes
                    )
                    val transcriptHash = CryptoUtils.sha256(transcript)

                    // Validate nonceProof
                    val expectedNonceProof = CryptoUtils.computeNonceProof(handshakeKey, transcriptHash)
                    val receivedNonceProof = CryptoUtils.base64UrlDecode(req.nonceProof)
                    if (!CryptoUtils.constantTimeEquals(expectedNonceProof, receivedNonceProof)) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid nonce proof"))
                        return@post
                    }

                    // Compute verification code
                    val verificationCode = CryptoUtils.computeVerificationCode(handshakeKey, transcriptHash)

                    val tcpRemoteIp = call.request.local.remoteHost
                    val isEmulatorVirtualIp = req.peerIp?.startsWith("10.0.2.") == true
                    val resolvedPeerIp = when {
                        !req.peerIp.isNullOrBlank() &&
                            !isEmulatorVirtualIp &&
                            req.peerIp.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) -> req.peerIp
                        !tcpRemoteIp.isNullOrBlank() &&
                            tcpRemoteIp != "0:0:0:0:0:0:0:1" &&
                            tcpRemoteIp != "127.0.0.1" -> tcpRemoteIp
                        else -> req.peerIp
                    }
                    android.util.Log.i("BuzzMe", "android-confirm: self-reported peerIp=${req.peerIp}, tcpRemote=$tcpRemoteIp, resolved=$resolvedPeerIp")

                    // Store derived secret & peer info in session
                    val pairwiseSecret = CryptoUtils.derivePairwiseSecret(sharedSecret, pairingNonceBytes)
                    val assignedLabel = req.peerDeviceName?.takeIf { it.isNotBlank() }
                        ?: session.label?.takeIf { it.isNotBlank() }
                        ?: "Android Peer"

                    session.verificationCode = verificationCode
                    session.derivedPairwiseSecretBase64 = Base64.getEncoder().encodeToString(pairwiseSecret)
                    session.peerEphemeralPublicKeyBytes = req.peerPubKey
                    session.remotePeerDeviceId = req.peerId
                    session.remotePeerIp = resolvedPeerIp
                    session.label = assignedLabel
                    session.state = PairingState.CONSUMED
                    session.approvalDeferred.complete(true)

                    val (wrapped, iv) = deviceRepository.encryptSecret(pairwiseSecret)
                    val keyId = session.savedPeerKeyId ?: UUID.randomUUID().toString()
                    val sessionEpoch = session.savedSessionEpoch ?: CryptoUtils.secureRandomBytes(8).let {
                        java.nio.ByteBuffer.wrap(it).long.let { l -> if (l == 0L) 1L else l }
                    }
                    session.savedPeerKeyId = keyId
                    session.savedSessionEpoch = sessionEpoch

                    val peerRecord = AndroidPeerAuth(
                        keyId = keyId,
                        peerDeviceId = req.peerId,
                        label = assignedLabel,
                        wrappedSecret = wrapped,
                        iv = iv,
                        sessionEpoch = sessionEpoch,
                        highestSeq = 0L,
                        targetScope = session.targetScope,
                        hostIp = resolvedPeerIp,
                        port = 8899
                    )
                    deviceRepository.savePeer(peerRecord)
                    deviceRepository.isPrimaryHost = true
                    deviceRepository.updatePairingSession(session)

                    val hostName = deviceRepository.deviceName.value?.takeIf { it.isNotBlank() } ?: "Host"
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "status" to "confirmed",
                            "keyId" to peerRecord.keyId,
                            "sessionEpoch" to peerRecord.sessionEpoch.toString(),
                            "hostDeviceName" to hostName
                        )
                    )
                    // Notify the ViewModel to push the updated peer directory to all known peers
                    onNewPeerPaired?.invoke()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Pairing failed")))
                }
            }

            // 5. Peer Directory Sync Endpoint
            post("/api/v1/peers/sync") {
                val rawBody = call.receiveText()
                val entries = runCatching { json.decodeFromString<List<PeerDirectoryEntry>>(rawBody) }.getOrNull()
                if (entries != null) {
                    val myDeviceId = deviceRepository.hostDeviceId.toString()
                    val hostPeer = deviceRepository.peers.value.firstOrNull { it.targetScope == "default" && !it.keyId.startsWith("relay-") }
                    if (hostPeer != null) {
                        for (entry in entries) {
                            if (entry.peerDeviceId == myDeviceId) continue
                            if (entry.isHost) {
                                if (!entry.ip.isNullOrBlank() && entry.ip != hostPeer.hostIp) {
                                    deviceRepository.updatePeerIp(hostPeer.keyId, entry.ip)
                                }
                            } else {
                                val existing = deviceRepository.peers.value.find { it.peerDeviceId == entry.peerDeviceId }
                                if (existing != null) {
                                    if (existing.label != entry.label || (entry.ip != null && existing.hostIp != entry.ip)) {
                                        val updated = existing.copy(
                                            label = entry.label,
                                            hostIp = entry.ip ?: existing.hostIp
                                        )
                                        deviceRepository.savePeerSync(updated)
                                    }
                                } else {
                                    val stub = AndroidPeerAuth(
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
                                    deviceRepository.savePeerSync(stub)
                                }
                            }
                        }
                    }
                    call.respond(HttpStatusCode.OK, mapOf("status" to "synced", "count" to entries.size.toString()))
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid peer sync payload"))
                }
            }

            // 6. Peer Directory — returns all known household members through the Host
            get("/api/v1/peers/directory") {
                val hostName = deviceRepository.deviceName.value?.takeIf { it.isNotBlank() } ?: "Household Host"
                val hostWifiIp = call.request.local.serverHost

                val senderKeyId = call.request.headers["X-BuzzMe-Sender-KeyId"]
                val senderPeer = senderKeyId?.let { deviceRepository.findPeerByKeyId(it) }
                val senderDeviceId = senderPeer?.peerDeviceId

                val roster = mutableListOf<PeerDirectoryEntry>()

                // 1. Host Entry
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

                // 2. Android Peers (filter out requesting sender and relay stubs)
                deviceRepository.peers.value
                    .filter { it.peerDeviceId != senderDeviceId && it.keyId != senderKeyId && !it.keyId.startsWith("relay-") }
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

                call.respond(HttpStatusCode.OK, roster)
            }

            // 7. Buzz Relay — non-host peer asks Host to forward a buzz to another peer
            post("/api/v1/buzz/relay") {
                val rawBody = call.receiveText()
                if (rawBody.toByteArray(StandardCharsets.UTF_8).size > 2048) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Payload exceeds 2 KiB"))
                    return@post
                }

                val hmacHeader = call.request.headers["X-BuzzMe-Relay-HMAC"]
                val senderKeyIdHeader = call.request.headers["X-BuzzMe-Sender-KeyId"]
                if (hmacHeader.isNullOrBlank() || senderKeyIdHeader.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing relay authentication headers"))
                    return@post
                }

                // Look up sender's peer record
                val senderPeer = deviceRepository.findPeerByKeyId(senderKeyIdHeader)
                if (senderPeer == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unknown sender key ID"))
                    return@post
                }

                // Rate limiting: 1 relay per 1 second per sender key
                val now = System.currentTimeMillis()
                val lastRelay = rateLimitMap[senderKeyIdHeader] ?: 0L
                if (now - lastRelay < 1_000L) {
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded. Wait 1 second."))
                    return@post
                }

                // Verify HMAC-SHA256(pairwiseSecret, rawBody) to authenticate the relay request
                val senderSecret = try {
                    deviceRepository.decryptSecret(senderPeer.wrappedSecret, senderPeer.iv)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to resolve sender secret"))
                    return@post
                }
                val bodyBytes = rawBody.toByteArray(StandardCharsets.UTF_8)
                val expectedHmac = CryptoUtils.hmacSha256(senderSecret, bodyBytes)
                val expectedHmacHex = expectedHmac.joinToString("") { "%02x".format(it) }
                if (!CryptoUtils.constantTimeEquals(
                        expectedHmacHex.toByteArray(StandardCharsets.UTF_8),
                        hmacHeader.toByteArray(StandardCharsets.UTF_8)
                    )
                ) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid relay HMAC"))
                    return@post
                }

                val req = runCatching { json.decodeFromString<BuzzRelayRequest>(rawBody) }.getOrNull()
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid relay payload"))
                    return@post
                }

                rateLimitMap[senderKeyIdHeader] = now
                val senderName = req.senderLabel?.takeIf { it.isNotBlank() } ?: senderPeer.label
                val relayMsg = if (req.message.isNullOrBlank() || req.message == "Buzz!") {
                    "Buzz! (from $senderName)"
                } else {
                    "${req.message} (from $senderName)"
                }

                // Check if target is the Host itself
                if (req.targetPeerDeviceId == deviceRepository.hostDeviceId.toString()) {
                    AlertEngine.dispatchBuzzAlert(context, senderName, relayMsg, senderId = senderPeer.peerDeviceId)
                    onAlertReceived?.invoke(senderName, relayMsg)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "relayed", "deliveredTo" to "Host"))
                    return@post
                }

                // Look up target peer
                val targetPeer = deviceRepository.peers.value.find { it.peerDeviceId == req.targetPeerDeviceId }
                if (targetPeer != null) {
                    val targetIp = targetPeer.hostIp
                    if (targetIp.isNullOrBlank()) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Target peer IP unknown"))
                        return@post
                    }

                    // Relay: host sends buzz to target using Host↔Target pairwise secret
                    val targetSecret = try {
                        deviceRepository.decryptSecret(targetPeer.wrappedSecret, targetPeer.iv)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to resolve target secret"))
                        return@post
                    }

                    val relaySeq = targetPeer.highestSeq + 1
                    val truncatedMsg = if (relayMsg.toByteArray(StandardCharsets.UTF_8).size > 160) {
                        "Buzz! (from $senderName)"
                    } else {
                        relayMsg
                    }

                    val result = withContext(Dispatchers.IO) {
                        SocketClient.sendBuzz(
                            host = targetIp,
                            port = targetPeer.port,
                            senderKeyId = UUID.fromString(targetPeer.keyId),
                            targetDeviceId = UUID.fromString(targetPeer.peerDeviceId),
                            sessionEpoch = targetPeer.sessionEpoch,
                            sequence = relaySeq,
                            message = truncatedMsg,
                            pairwiseSecret = targetSecret
                        )
                    }

                    when (result) {
                        is SocketClient.SendResult.Success -> {
                            deviceRepository.updatePeerSequence(targetPeer.keyId, relaySeq)
                            call.respond(HttpStatusCode.OK, mapOf("status" to "relayed", "deliveredTo" to req.targetPeerDeviceId))
                        }
                        is SocketClient.SendResult.Error -> {
                            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Relay delivery failed: ${result.message}"))
                        }
                    }
                    return@post
                }

                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Target peer not found on this host"))
            }
        }
    }

    @Serializable
    data class HostInfoResponse(
        val deviceId: String,
        val deviceName: String,
        val status: String = "online",
        val isHost: Boolean = false
    )

    @Serializable
    data class JoinRequestPayload(
        val deviceName: String,
        val deviceType: String = "android",
        val peerId: String? = null,
        val peerPubKey: String? = null,
        val peerIp: String? = null
    )

    @Serializable
    data class JoinRequestStatusResponse(
        val requestId: String,
        val status: String,
        val deviceName: String,
        val hostName: String? = null,
        val assignedKeyId: String? = null,
        val sessionEpoch: Long? = null,
        val hostPublicKey: String? = null
    )

    @Serializable
    data class AndroidPairConfirmRequest(
        val pairingId: String,
        val peerId: String,
        val peerPubKey: String,
        val nonceProof: String,
        val peerIp: String? = null,
        val peerDeviceName: String? = null
    )

    @Serializable
    data class BuzzRelayRequest(
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
}

