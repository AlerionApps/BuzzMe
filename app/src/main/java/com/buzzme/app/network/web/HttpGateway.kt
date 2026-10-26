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
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
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
            // 0. Root Mobile Web Page for Zero-Scan Web & iOS Joining
            get("/") {
                val hostName = deviceRepository.deviceName.value?.takeIf { it.isNotBlank() } ?: "Household Host"
                val html = renderMobileJoinHtml(hostName)
                call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
            }

            get("/manifest.json") {
                val manifestJson = """
                    {
                      "name": "BuzzMe Household Pager",
                      "short_name": "BuzzMe",
                      "description": "Private, household pager for instant family buzzes",
                      "start_url": "/",
                      "display": "standalone",
                      "background_color": "#0f172a",
                      "theme_color": "#0284c7",
                      "icons": [
                        {
                          "src": "/icon.png",
                          "sizes": "192x192",
                          "type": "image/png"
                        }
                      ]
                    }
                """.trimIndent()
                call.respondText(manifestJson, ContentType.Application.Json, HttpStatusCode.OK)
            }

            get("/sw.js") {
                val swJs = """
                    self.addEventListener('install', (e) => self.skipWaiting());
                    self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
                    self.addEventListener('push', (event) => {
                        let title = "⚡ BUZZ!";
                        let body = "Household buzz received!";
                        if (event.data) {
                            try {
                                const data = event.data.json();
                                if (data.title) title = data.title;
                                if (data.body) body = data.body;
                            } catch (e) {
                                body = event.data.text();
                            }
                        }
                        event.waitUntil(
                            self.registration.showNotification(title, {
                                body: body,
                                tag: 'buzzme-' + Date.now(),
                                renotify: true,
                                requireInteraction: true
                            })
                        );
                    });
                """.trimIndent()
                call.respondText(swJs, ContentType.parse("application/javascript"), HttpStatusCode.OK)
            }

            // iOS Web Push Subscription Direct Registration
            post("/api/v1/pwa/enroll") {
                val rawBody = call.receiveText()
                val req = runCatching { json.decodeFromString<PwaEnrollRequest>(rawBody) }.getOrNull()
                if (req == null || req.endpoint.isBlank() || req.p256dh.isBlank() || req.auth.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid PWA push registration payload"))
                    return@post
                }

                val subscription = com.buzzme.app.data.model.PushSubscription(
                    id = UUID.randomUUID().toString(),
                    deviceId = req.deviceId.ifBlank { UUID.randomUUID().toString() },
                    label = req.label.ifBlank { "iPhone Web Push" },
                    endpoint = req.endpoint,
                    p256dh = req.p256dh,
                    auth = req.auth
                )
                deviceRepository.savePushSubscriptionSync(subscription)
                onNewPeerPaired?.invoke()

                call.respond(HttpStatusCode.OK, mapOf("status" to "enrolled", "id" to subscription.id))
            }

            // Host Info Discovery Endpoint
            get("/api/v1/info") {
                val hostName = deviceRepository.deviceName.value?.takeIf { it.isNotBlank() } ?: "Household Host"
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "deviceId" to deviceRepository.hostDeviceId.toString(),
                        "deviceName" to hostName,
                        "status" to "online",
                        "isHost" to deviceRepository.isPrimaryHost
                    )
                )
            }

            // Zero-Scan Join Request Submission Endpoint
            post("/api/v1/join-request") {
                val rawBody = call.receiveText()
                val req = runCatching { json.decodeFromString<JoinRequestPayload>(rawBody) }.getOrNull()
                if (req == null || req.deviceName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid join request payload"))
                    return@post
                }

                val tcpRemoteIp = call.request.local.remoteHost
                val requestId = UUID.randomUUID().toString()
                val pendingReq = com.buzzme.app.data.model.PendingJoinRequest(
                    requestId = requestId,
                    deviceType = req.deviceType,
                    deviceName = req.deviceName.trim(),
                    deviceIp = tcpRemoteIp,
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
                    mapOf(
                        "requestId" to result.requestId,
                        "status" to result.status,
                        "deviceName" to result.deviceName,
                        "hostName" to (result.hostName ?: deviceRepository.deviceName.value ?: "Household Host"),
                        "iosToken" to result.iosToken,
                        "assignedKeyId" to result.assignedKeyId,
                        "sessionEpoch" to result.sessionEpoch,
                        "hostPublicKey" to result.hostPublicKey
                    )
                )
            }

            // Join Request Status Polling Endpoint
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
                    mapOf(
                        "requestId" to req.requestId,
                        "status" to req.status,
                        "deviceName" to req.deviceName,
                        "hostName" to (req.hostName ?: deviceRepository.deviceName.value ?: "Household Host"),
                        "iosToken" to req.iosToken,
                        "assignedKeyId" to req.assignedKeyId,
                        "sessionEpoch" to req.sessionEpoch,
                        "hostPublicKey" to req.hostPublicKey
                    )
                )
            }

            // 1. Buzz Dispatch Route (Port 8900)
            post("/api/v1/buzz") {
                val tokenHeader = call.request.headers["X-BuzzMe-Token"]
                if (tokenHeader.isNullOrBlank() || !DeviceRepository.TOKEN_REGEX.matches(tokenHeader)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized: Invalid or missing token"))
                    return@post
                }

                // Rate limiting: 1 buzz per 10 seconds per token
                val now = System.currentTimeMillis()
                val lastBuzz = rateLimitMap[tokenHeader] ?: 0L
                if (now - lastBuzz < 10_000L) {
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded. Wait 10 seconds."))
                    return@post
                }

                val validatedToken = deviceRepository.verifyCapabilityToken(tokenHeader)
                if (validatedToken == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized: Token revoked or invalid"))
                    return@post
                }

                val rawBody = call.receiveText()
                if (rawBody.toByteArray(StandardCharsets.UTF_8).size > 1024) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Payload exceeds 1 KiB"))
                    return@post
                }

                val requestObj = runCatching { json.decodeFromString<BuzzRequest>(rawBody) }.getOrNull()
                val messageText = requestObj?.message ?: "Buzz!"

                rateLimitMap[tokenHeader] = now

                // Dispatch Alert
                AlertEngine.dispatchBuzzAlert(context, validatedToken.label, messageText, senderId = validatedToken.lookupId)
                onAlertReceived?.invoke(validatedToken.label, messageText)

                call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "deliveredTo" to validatedToken.targetDeviceId))
            }

            // 2. Setup Shortcut Confirmation HTML
            get("/api/v1/setup-shortcut") {
                val pairingId = call.request.queryParameters["pairingId"]
                val nonce = call.request.queryParameters["nonce"]

                if (pairingId.isNullOrBlank() || nonce.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Missing pairingId or nonce")
                    return@get
                }

                val session = deviceRepository.getPairingSession(pairingId)
                if (session == null || session.expiresAt < System.currentTimeMillis()) {
                    call.respondText(
                        renderExpiredHtml(),
                        ContentType.Text.Html,
                        HttpStatusCode.Gone
                    )
                    return@get
                }

                val html = renderSetupShortcutHtml(pairingId, nonce, session.verificationCode, session.targetScope)
                call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
            }

            // 3. Confirm iOS Shortcut Pairing (Atomic Claim)
            post("/api/v1/pair/confirm") {
                val rawBody = call.receiveText()
                if (rawBody.toByteArray(StandardCharsets.UTF_8).size > 1024) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Payload exceeds 1 KiB"))
                    return@post
                }

                val req = runCatching { json.decodeFromString<PairConfirmRequest>(rawBody) }.getOrNull()
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON payload"))
                    return@post
                }

                val session = deviceRepository.getPairingSession(req.pairingId)
                if (session == null || session.expiresAt < System.currentTimeMillis()) {
                    call.respond(HttpStatusCode.Gone, mapOf("error" to "Pairing session expired or not found"))
                    return@post
                }

                if (session.state != PairingState.APPROVED_PENDING_CLAIM) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Pairing has not been approved by Host"))
                    return@post
                }

                val result = deviceRepository.consumeShortcutPairing(req.pairingId, req.pairingNonce)
                if (result == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Nonce mismatch or already consumed"))
                    return@post
                }

                val hostUrl = "http://${call.request.local.serverHost}:${call.request.local.serverPort}/api/v1/buzz"
                val responseData = mapOf(
                    "status" to "approved",
                    "token" to result.rawToken,
                    "targetDeviceId" to result.capabilityToken.targetDeviceId,
                    "endpoint" to hostUrl,
                    "label" to result.capabilityToken.label
                )
                call.respond(HttpStatusCode.OK, responseData)
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

                    // Prefer the actual TCP source address over the self-reported peerIp:
                    // – If peerIp is in the Android Emulator virtual range (10.0.2.x), it is not
                    //   reachable from physical devices, so use the real TCP remote address instead.
                    // – If peerIp is valid and routable, use it (it may be more accurate than the
                    //   server-side observed IP when the device is behind a domestic router).
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

                // 3. iOS Capability Tokens
                deviceRepository.capabilityTokens.value
                    .filter { !it.revoked && it.lookupId != senderDeviceId }
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

                // Check if target is an iOS capability token
                val capToken = deviceRepository.capabilityTokens.value.find { it.lookupId == req.targetPeerDeviceId && !it.revoked }
                if (capToken != null) {
                    AlertEngine.dispatchBuzzAlert(context, senderName, relayMsg, senderId = capToken.lookupId)
                    onAlertReceived?.invoke(senderName, relayMsg)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "relayed", "deliveredTo" to capToken.label))
                    return@post
                }

                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Target peer not found on this host"))
            }
        }
    }

    private fun renderSetupShortcutHtml(
        pairingId: String,
        nonce: String,
        code: String,
        target: String
    ): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>BuzzMe Shortcut Setup</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 24px; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 90vh; }
                    .card { background: #1e293b; border-radius: 16px; padding: 28px; max-width: 420px; width: 100%; box-shadow: 0 10px 25px rgba(0,0,0,0.5); text-align: center; border: 1px solid #334155; }
                    h1 { font-size: 22px; margin-bottom: 8px; color: #38bdf8; }
                    p { color: #94a3b8; font-size: 15px; line-height: 1.5; margin-bottom: 20px; }
                    .code-box { background: #0f172a; padding: 16px; border-radius: 12px; font-size: 32px; font-weight: 700; letter-spacing: 6px; color: #f59e0b; margin: 20px 0; border: 1px dashed #f59e0b; }
                    .btn { background: #0284c7; color: white; border: none; padding: 14px 24px; font-size: 16px; font-weight: 600; border-radius: 10px; cursor: pointer; width: 100%; transition: background 0.2s; }
                    .btn:hover { background: #0369a1; }
                    .btn:disabled { background: #475569; cursor: not-allowed; }
                    .result-box { display: none; margin-top: 20px; text-align: left; background: #0f172a; padding: 16px; border-radius: 10px; font-size: 13px; word-break: break-all; }
                    .notice { font-size: 12px; color: #64748b; margin-top: 14px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>Install Shortcut to Buzz $target</h1>
                    <p>Compare the verification code below with the one displayed on the Host device:</p>
                    <div class="code-box">$code</div>
                    <p style="font-size: 13px;">Host must tap <strong>Approve</strong> before you tap Confirm & Install.</p>
                    <button class="btn" id="confirmBtn" onclick="confirmPairing()">Confirm & Install</button>
                    <div id="resultBox" class="result-box"></div>
                    <div class="notice">One-time capability credential. Revocable at any time from Host.</div>
                </div>

                <script>
                    async function confirmPairing() {
                        const btn = document.getElementById('confirmBtn');
                        const resBox = document.getElementById('resultBox');
                        btn.disabled = true;
                        btn.innerText = "Verifying with Host...";

                        try {
                            const resp = await fetch('/api/v1/pair/confirm', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({
                                    pairingId: '$pairingId',
                                    pairingNonce: '$nonce'
                                })
                            });

                            const data = await resp.json();
                            if (resp.ok) {
                                btn.style.display = 'none';
                                resBox.style.display = 'block';
                                resBox.innerHTML = `
                                    <h3 style="color:#22c55e; margin-top:0;">Pairing Approved!</h3>
                                    <p style="color:#cbd5e1; font-size:13px;">Your Shortcut Capability Token:</p>
                                    <code style="display:block; background:#1e293b; padding:8px; border-radius:6px; color:#38bdf8;">` + data.token + `</code>
                                    <p style="color:#cbd5e1; font-size:13px; margin-top:12px;">Endpoint URL:</p>
                                    <code style="display:block; background:#1e293b; padding:8px; border-radius:6px; color:#38bdf8;">` + data.endpoint + `</code>
                                    <p style="color:#94a3b8; font-size:12px; margin-top:14px;">In iOS Shortcuts app: Add 'Get Contents of URL' -> POST to Endpoint with Header <code>X-BuzzMe-Token: ` + data.token + `</code>.</p>
                                `;
                            } else {
                                alert(data.error || "Confirmation failed. Ensure Host has approved the request.");
                                btn.disabled = false;
                                btn.innerText = "Confirm & Install";
                            }
                        } catch (e) {
                            alert("Network error communicating with Host: " + e.message);
                            btn.disabled = false;
                            btn.innerText = "Confirm & Install";
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderMobileJoinHtml(hostName: String): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>BuzzMe Household</title>
                <link rel="manifest" href="/manifest.json">
                <meta name="apple-mobile-web-app-capable" content="yes">
                <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
                <meta name="apple-mobile-web-app-title" content="BuzzMe">
                <style>
                    * { box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 20px; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; }
                    .card { background: #1e293b; border-radius: 20px; padding: 28px; max-width: 440px; width: 100%; box-shadow: 0 20px 40px rgba(0,0,0,0.5); text-align: center; border: 1px solid #334155; }
                    .icon { font-size: 48px; margin-bottom: 12px; }
                    h1 { font-size: 24px; margin: 0 0 8px 0; color: #38bdf8; font-weight: 700; }
                    .subtitle { color: #94a3b8; font-size: 15px; margin-bottom: 20px; line-height: 1.4; }
                    
                    /* iOS Add to Home Screen Banner */
                    .ios-pwa-banner {
                        display: none;
                        background: rgba(56, 189, 248, 0.1);
                        border: 1px solid #0284c7;
                        border-radius: 14px;
                        padding: 16px;
                        margin-bottom: 20px;
                        text-align: left;
                    }
                    .ios-pwa-banner h3 {
                        font-size: 15px;
                        color: #38bdf8;
                        margin: 0 0 8px 0;
                        display: flex;
                        align-items: center;
                        gap: 8px;
                    }
                    .ios-pwa-banner ol {
                        margin: 0;
                        padding-left: 20px;
                        font-size: 13px;
                        color: #cbd5e1;
                        line-height: 1.5;
                    }
                    .ios-pwa-banner li {
                        margin-bottom: 4px;
                    }

                    .input-group { text-align: left; margin-bottom: 20px; }
                    label { display: block; font-size: 13px; font-weight: 600; color: #cbd5e1; margin-bottom: 6px; text-transform: uppercase; letter-spacing: 0.5px; }
                    input[type="text"] { width: 100%; padding: 14px; background: #0f172a; border: 1px solid #475569; border-radius: 12px; color: #f8fafc; font-size: 16px; outline: none; transition: border-color 0.2s; }
                    input[type="text"]:focus { border-color: #38bdf8; }
                    .btn { background: #0284c7; color: white; border: none; padding: 16px 24px; font-size: 17px; font-weight: 600; border-radius: 12px; cursor: pointer; width: 100%; transition: transform 0.1s, background 0.2s; box-shadow: 0 4px 12px rgba(2, 132, 199, 0.4); }
                    .btn:active { transform: scale(0.98); }
                    .btn:disabled { background: #475569; box-shadow: none; cursor: not-allowed; }
                    .btn-buzz { background: #f59e0b; box-shadow: 0 4px 12px rgba(245, 158, 11, 0.4); margin-top: 16px; }
                    .btn-buzz:hover { background: #d97706; }
                    .btn-push { background: #10b981; box-shadow: 0 4px 12px rgba(16, 185, 129, 0.4); margin-top: 12px; }
                    .status-box { display: none; margin-top: 20px; background: #0f172a; padding: 20px; border-radius: 14px; text-align: left; border: 1px solid #334155; }
                    .status-title { font-weight: 700; font-size: 16px; margin-bottom: 8px; color: #22c55e; }
                    .token-display { background: #1e293b; padding: 12px; border-radius: 8px; font-family: monospace; font-size: 13px; color: #38bdf8; word-break: break-all; margin: 10px 0; border: 1px solid #334155; }
                    .spinner { display: inline-block; width: 18px; height: 18px; border: 2px solid rgba(255,255,255,0.3); border-radius: 50%; border-top-color: white; animation: spin 0.8s linear infinite; vertical-align: middle; margin-right: 8px; }
                    @keyframes spin { to { transform: rotate(360deg); } }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="icon">🐝</div>
                    <h1>BuzzMe Household</h1>
                    <div class="subtitle">Host: <strong>$hostName</strong><br>Private Home Wi-Fi Network</div>

                    <!-- iOS Safari Add to Home Screen Instructions -->
                    <div id="iosHomePrompt" class="ios-pwa-banner">
                        <h3>📲 Save as App to Home Screen</h3>
                        <ol>
                            <li>Tap the Safari <strong>Share</strong> button (box with arrow <span style="font-size:16px;">⎋</span> at bottom).</li>
                            <li>Scroll down and tap <strong>"Add to Home Screen"</strong> (<strong>+</strong>).</li>
                            <li>Open <strong>BuzzMe</strong> from your Home Screen for instant alerts!</li>
                        </ol>
                    </div>

                    <div id="joinForm">
                        <div class="input-group">
                            <label for="deviceName">Your Device Name</label>
                            <input type="text" id="deviceName" value="My iPhone" placeholder="e.g. Mum's iPhone">
                        </div>
                        <button class="btn" id="joinBtn" onclick="requestToJoin()">Request to Join Household</button>
                    </div>

                    <div id="statusBox" class="status-box"></div>
                </div>

                <script>
                    let activeToken = "";
                    let currentDeviceName = "My iPhone";

                    // Check if running on iOS outside standalone mode
                    const isIos = /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;
                    const isStandalone = window.navigator.standalone === true || window.matchMedia('(display-mode: standalone)').matches;
                    if (isIos && !isStandalone) {
                        document.getElementById('iosHomePrompt').style.display = 'block';
                    }

                    // Register Service Worker if supported
                    if ('serviceWorker' in navigator) {
                        navigator.serviceWorker.register('/sw.js').catch(console.error);
                    }

                    async function requestToJoin() {
                        currentDeviceName = document.getElementById('deviceName').value.trim() || "My iPhone";
                        const btn = document.getElementById('joinBtn');
                        btn.disabled = true;
                        btn.innerHTML = '<span class="spinner"></span> Requesting Access...';

                        try {
                            const resp = await fetch('/api/v1/join-request', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ deviceName: currentDeviceName, deviceType: 'ios' })
                            });

                            const data = await resp.json();
                            if (resp.ok) {
                                handleResponse(data);
                            } else {
                                alert(data.error || "Join request failed");
                                btn.disabled = false;
                                btn.innerText = "Request to Join Household";
                            }
                        } catch (e) {
                            alert("Network error: " + e.message);
                            btn.disabled = false;
                            btn.innerText = "Request to Join Household";
                        }
                    }

                    function handleResponse(data) {
                        const form = document.getElementById('joinForm');
                        const statusBox = document.getElementById('statusBox');

                        if (data.status === 'approved') {
                            form.style.display = 'none';
                            statusBox.style.display = 'block';
                            activeToken = data.iosToken;
                            statusBox.innerHTML = `
                                <div class="status-title">✓ Connected to Household!</div>
                                <p style="color:#cbd5e1; font-size:14px; margin-bottom:12px;">You are joined with host <strong>` + data.hostName + `</strong>.</p>
                                
                                <div id="pushEnrollSection" style="margin-bottom:16px;">
                                    <button class="btn btn-push" id="enablePushBtn" onclick="enablePushNotifications()">🔔 Enable Inbound Buzz Notifications</button>
                                    <div id="pushStatus" style="font-size:12px; color:#94a3b8; margin-top:6px;">Enables this iPhone to ring & vibrate when someone buzzes you.</div>
                                </div>

                                <div class="input-group">
                                    <label for="buzzMsg">Message to Host (Optional)</label>
                                    <input type="text" id="buzzMsg" placeholder="Buzz! 👋">
                                </div>
                                <button class="btn btn-buzz" onclick="sendBuzz()">🐝 Buzz Host Now!</button>
                                <div id="buzzResult" style="margin-top:10px; font-size:13px; text-align:center;"></div>
                            `;

                            // If standalone PWA, auto-prompt for push
                            if (isStandalone && 'Notification' in window && Notification.permission !== 'granted') {
                                setTimeout(enablePushNotifications, 500);
                            }
                        } else if (data.status === 'pending') {
                            form.style.display = 'none';
                            statusBox.style.display = 'block';
                            statusBox.innerHTML = `
                                <div class="status-title" style="color:#f59e0b;"><span class="spinner"></span> Waiting for Approval</div>
                                <p style="color:#94a3b8; font-size:14px;">Please ask host (<strong>` + data.hostName + `</strong>) to tap Approve on their device screen.</p>
                            `;
                            pollStatus(data.requestId);
                        } else {
                            alert("Request was declined by host.");
                            form.style.display = 'block';
                            statusBox.style.display = 'none';
                        }
                    }

                    async function pollStatus(requestId) {
                        const interval = setInterval(async () => {
                            try {
                                const resp = await fetch('/api/v1/join-request/status?requestId=' + requestId);
                                const data = await resp.json();
                                if (data.status === 'approved' || data.status === 'declined') {
                                    clearInterval(interval);
                                    handleResponse(data);
                                }
                            } catch (e) {}
                        }, 1000);
                    }

                    async function enablePushNotifications() {
                        const btn = document.getElementById('enablePushBtn');
                        const status = document.getElementById('pushStatus');
                        if (btn) btn.disabled = true;

                        if (!('Notification' in window) || !('serviceWorker' in navigator)) {
                            if (status) status.innerText = "Add BuzzMe to Home Screen first to enable notifications.";
                            return;
                        }

                        try {
                            const permission = await Notification.requestPermission();
                            if (permission !== 'granted') {
                                if (status) status.innerText = "Notification permission denied in Settings.";
                                return;
                            }

                            const reg = await navigator.serviceWorker.ready;
                            let sub = await reg.pushManager.getSubscription();
                            if (!sub) {
                                sub = await reg.pushManager.subscribe({ userVisibleOnly: true });
                            }

                            const subJson = sub.toJSON();
                            await fetch('/api/v1/pwa/enroll', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({
                                    endpoint: subJson.endpoint,
                                    p256dh: subJson.keys.p256dh,
                                    auth: subJson.keys.auth,
                                    label: currentDeviceName
                                })
                            });

                            if (status) {
                                status.style.color = "#22c55e";
                                status.innerText = "✓ Inbound buzz notifications active on this iPhone!";
                            }
                            if (btn) btn.style.display = 'none';
                        } catch (err) {
                            if (status) status.innerText = "Push setup: " + err.message;
                            if (btn) btn.disabled = false;
                        }
                    }

                    async function sendBuzz() {
                        const msgInput = document.getElementById('buzzMsg');
                        const resDiv = document.getElementById('buzzResult');
                        const msg = msgInput ? msgInput.value.trim() : "Buzz!";

                        resDiv.style.color = "#94a3b8";
                        resDiv.innerText = "Sending buzz...";

                        try {
                            const resp = await fetch('/api/v1/buzz', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                    'X-BuzzMe-Token': activeToken
                                },
                                body: JSON.stringify({ message: msg })
                            });

                            if (resp.ok) {
                                resDiv.style.color = "#22c55e";
                                resDiv.innerText = "✓ Buzz delivered!";
                                setTimeout(() => { resDiv.innerText = ""; }, 3000);
                            } else {
                                const data = await resp.json();
                                resDiv.style.color = "#ef4444";
                                resDiv.innerText = "Failed: " + (data.error || "Server error");
                            }
                        } catch (e) {
                            resDiv.style.color = "#ef4444";
                            resDiv.innerText = "Network error: " + e.message;
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderExpiredHtml(): String {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><title>Pairing Expired</title>
            <style>body { font-family: sans-serif; background:#0f172a; color:#f8fafc; padding:30px; text-align:center; }</style>
            </head><body>
            <h2>Pairing Session Expired</h2>
            <p style="color:#94a3b8;">This pairing session has expired or has already been used. Please generate a new QR code on the Host device.</p>
            </body></html>
        """.trimIndent()
    }

    @Serializable
    data class PwaEnrollRequest(
        val endpoint: String,
        val p256dh: String,
        val auth: String,
        val label: String = "iPhone",
        val deviceId: String = ""
    )

    @Serializable
    data class BuzzRequest(
        val message: String? = null,
        val targetId: String? = null
    )

    @Serializable
    data class JoinRequestPayload(
        val deviceName: String,
        val deviceType: String = "ios",
        val peerId: String? = null,
        val peerPubKey: String? = null
    )

    @Serializable
    data class PairConfirmRequest(
        val pairingId: String,
        val pairingNonce: String
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

