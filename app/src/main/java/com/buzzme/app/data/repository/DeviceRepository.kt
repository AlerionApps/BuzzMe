package com.buzzme.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.buzzme.app.crypto.CryptoUtils
import com.buzzme.app.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DeviceRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _peers = MutableStateFlow<List<AndroidPeerAuth>>(emptyList())
    val peers: StateFlow<List<AndroidPeerAuth>> = _peers.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<PushSubscription>>(emptyList())
    val subscriptions: StateFlow<List<PushSubscription>> = _subscriptions.asStateFlow()

    private val _capabilityTokens = MutableStateFlow<List<CapabilityToken>>(emptyList())
    val capabilityTokens: StateFlow<List<CapabilityToken>> = _capabilityTokens.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(prefs.getString(KEY_DEVICE_NAME, null))
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    val isDeviceNameSet: Boolean
        get() = !prefs.getString(KEY_DEVICE_NAME, null).isNullOrBlank()

    fun getDeviceName(): String = _deviceName.value ?: ""

    fun setDeviceName(name: String) {
        val trimmed = name.trim()
        prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply()
        _deviceName.value = trimmed
    }

    private val _isPrimaryHostFlow = MutableStateFlow(false)
    val isPrimaryHostFlow: StateFlow<Boolean> = _isPrimaryHostFlow.asStateFlow()

    var hostDesignatedTimestamp: Long
        get() = prefs.getLong("host_designated_timestamp", 0L)
        set(value) { prefs.edit().putLong("host_designated_timestamp", value).apply() }

    var isPrimaryHost: Boolean
        get() {
            // A device that enrolled as a peer with a household host (targetScope == "default")
            // is a client/backup host, not the primary host.
            val hasHostPeer = _peers.value.any { it.targetScope == "default" && !it.keyId.startsWith("relay-") }
            if (hasHostPeer) return false
            return prefs.getBoolean(KEY_IS_PRIMARY_HOST, false)
        }
        set(value) {
            prefs.edit().putBoolean(KEY_IS_PRIMARY_HOST, value).apply()
            if (value && hostDesignatedTimestamp == 0L) {
                hostDesignatedTimestamp = System.currentTimeMillis()
            } else if (!value) {
                hostDesignatedTimestamp = 0L
            }
            _isPrimaryHostFlow.value = isPrimaryHost
        }

    suspend fun mergePeers(incomingPeers: List<AndroidPeerAuth>) = mutex.withLock {
        val current = _peers.value.toMutableList()
        var updated = false
        for (incoming in incomingPeers) {
            if (incoming.peerDeviceId == hostDeviceId.toString()) continue
            val existingIndex = current.indexOfFirst { it.peerDeviceId == incoming.peerDeviceId || it.keyId == incoming.keyId }
            if (existingIndex >= 0) {
                val existing = current[existingIndex]
                val updatedPeer = existing.copy(
                    hostIp = incoming.hostIp ?: existing.hostIp,
                    label = if (existing.label.isBlank()) incoming.label else existing.label
                )
                if (updatedPeer != existing) {
                    current[existingIndex] = updatedPeer
                    updated = true
                }
            } else {
                current.add(incoming)
                updated = true
            }
        }
        if (updated) {
            _peers.value = current
            prefs.edit().putString(KEY_PEERS, json.encodeToString(current)).apply()
        }
    }

    private val _currentPairingSession = MutableStateFlow<PairingSession?>(null)
    val currentPairingSession: StateFlow<PairingSession?> = _currentPairingSession.asStateFlow()

    private val activePairingSessions = ConcurrentHashMap<String, PairingSession>()

    val hostDeviceId: UUID
    private val tokenLookupKey: ByteArray

    init {
        // Initialize or load host device UUID
        val storedHostId = prefs.getString(KEY_HOST_DEVICE_ID, null)
        hostDeviceId = if (storedHostId != null) {
            UUID.fromString(storedHostId)
        } else {
            val newId = UUID.randomUUID()
            prefs.edit().putString(KEY_HOST_DEVICE_ID, newId.toString()).apply()
            newId
        }

        // Initialize master AES key and tokenLookupKey
        initMasterKey()
        tokenLookupKey = initTokenLookupKey()

        // Ensure VAPID EC key exists
        initVapidKey()

        // Load stored state
        loadAll()
    }

    private fun initMasterKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            // In unit testing JVM environment or legacy tests, keystore might not be present
        }
    }

    private fun getMasterKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
                ?: getFallbackSoftwareMasterKey()
        } catch (e: Exception) {
            getFallbackSoftwareMasterKey()
        }
    }

    private var fallbackSoftwareKey: SecretKey? = null
    private fun getFallbackSoftwareMasterKey(): SecretKey {
        var key = fallbackSoftwareKey
        if (key == null) {
            val stored = prefs.getString("KEY_FALLBACK_MASTER", null)
            val bytes = if (stored != null) {
                Base64.getDecoder().decode(stored)
            } else {
                val b = CryptoUtils.secureRandomBytes(32)
                prefs.edit().putString("KEY_FALLBACK_MASTER", Base64.getEncoder().encodeToString(b)).apply()
                b
            }
            key = SecretKeySpec(bytes, "AES")
            fallbackSoftwareKey = key
        }
        return key
    }

    private fun initTokenLookupKey(): ByteArray {
        val storedEncrypted = prefs.getString(KEY_TOKEN_LOOKUP_KEY, null)
        val storedIv = prefs.getString(KEY_TOKEN_LOOKUP_IV, null)
        if (storedEncrypted != null && storedIv != null) {
            return decryptSecret(storedEncrypted, storedIv)
        }
        val rawKey = CryptoUtils.secureRandomBytes(32)
        val (encrypted, iv) = encryptSecret(rawKey)
        prefs.edit()
            .putString(KEY_TOKEN_LOOKUP_KEY, encrypted)
            .putString(KEY_TOKEN_LOOKUP_IV, iv)
            .apply()
        return rawKey
    }

    private fun initVapidKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(VAPID_KEY_ALIAS)) {
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    VAPID_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }
        } catch (e: Exception) {
            // Handled for unit tests / fallback
        }
    }

    fun getVapidKeyPair(): KeyPair {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val cert = keyStore.getCertificate(VAPID_KEY_ALIAS)
            val privateKey = keyStore.getKey(VAPID_KEY_ALIAS, null) as? PrivateKey
            if (cert != null && privateKey != null) {
                KeyPair(cert.publicKey, privateKey)
            } else {
                getFallbackVapidKeyPair()
            }
        } catch (e: Exception) {
            getFallbackVapidKeyPair()
        }
    }

    private var fallbackVapidKeyPair: KeyPair? = null
    private fun getFallbackVapidKeyPair(): KeyPair {
        var kp = fallbackVapidKeyPair
        if (kp == null) {
            kp = CryptoUtils.generateEcKeyPair()
            fallbackVapidKeyPair = kp
        }
        return kp
    }

    fun encryptSecret(secret: ByteArray): Pair<String, String> {
        val masterKey = getMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(secret)
        return Pair(
            Base64.getEncoder().encodeToString(ciphertext),
            Base64.getEncoder().encodeToString(iv)
        )
    }

    fun decryptSecret(wrappedSecretBase64: String, ivBase64: String): ByteArray {
        val masterKey = getMasterKey()
        val ciphertext = Base64.getDecoder().decode(wrappedSecretBase64)
        val iv = Base64.getDecoder().decode(ivBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        return cipher.doFinal(ciphertext)
    }

    private fun loadAll() {
        val peersRaw = prefs.getString(KEY_PEERS, "[]") ?: "[]"
        val subsRaw = prefs.getString(KEY_SUBSCRIPTIONS, "[]") ?: "[]"
        val tokensRaw = prefs.getString(KEY_CAPABILITY_TOKENS, "[]") ?: "[]"

        _peers.value = runCatching { json.decodeFromString<List<AndroidPeerAuth>>(peersRaw) }.getOrDefault(emptyList())
        _subscriptions.value = runCatching { json.decodeFromString<List<PushSubscription>>(subsRaw) }.getOrDefault(emptyList())
        _capabilityTokens.value = runCatching { json.decodeFromString<List<CapabilityToken>>(tokensRaw) }.getOrDefault(emptyList())
        _isPrimaryHostFlow.value = isPrimaryHost
    }

    fun savePeerSync(peer: AndroidPeerAuth) {
        synchronized(this) {
            val updated = _peers.value.filter { it.keyId != peer.keyId && it.peerDeviceId != peer.peerDeviceId } + peer
            _peers.value = updated
            prefs.edit().putString(KEY_PEERS, json.encodeToString(updated)).apply()
            _isPrimaryHostFlow.value = isPrimaryHost
        }
    }

    suspend fun savePeer(peer: AndroidPeerAuth) = mutex.withLock {
        val updated = _peers.value.filter { it.keyId != peer.keyId && it.peerDeviceId != peer.peerDeviceId } + peer
        _peers.value = updated
        prefs.edit().putString(KEY_PEERS, json.encodeToString(updated)).apply()
        _isPrimaryHostFlow.value = isPrimaryHost
    }

    suspend fun updatePeerSequence(keyId: String, newSequence: Long) = mutex.withLock {
        val updated = _peers.value.map {
            if (it.keyId == keyId) it.copy(highestSeq = newSequence) else it
        }
        _peers.value = updated
        prefs.edit().putString(KEY_PEERS, json.encodeToString(updated)).apply()
    }

    suspend fun updatePeerIpAndSequence(keyId: String, newIp: String?, newSequence: Long) = mutex.withLock {
        val updated = _peers.value.map {
            if (it.keyId == keyId) {
                it.copy(
                    highestSeq = newSequence,
                    hostIp = newIp ?: it.hostIp
                )
            } else it
        }
        _peers.value = updated
        prefs.edit().putString(KEY_PEERS, json.encodeToString(updated)).apply()
    }

    suspend fun updatePeerIp(keyId: String, newIp: String) = mutex.withLock {
        val updated = _peers.value.map {
            if (it.keyId == keyId) it.copy(hostIp = newIp) else it
        }
        _peers.value = updated
        prefs.edit().putString(KEY_PEERS, json.encodeToString(updated)).apply()
    }

    suspend fun removePeer(keyId: String) = mutex.withLock {
        val updated = _peers.value.filter { it.keyId != keyId }
        _peers.value = updated
        prefs.edit().putString(KEY_PEERS, json.encodeToString(updated)).apply()
        _isPrimaryHostFlow.value = isPrimaryHost
    }

    fun findPeerByKeyId(keyId: String): AndroidPeerAuth? {
        return _peers.value.find { it.keyId == keyId }
    }

    suspend fun savePushSubscription(sub: PushSubscription) = mutex.withLock {
        val updated = _subscriptions.value.filter { it.id != sub.id } + sub
        _subscriptions.value = updated
        prefs.edit().putString(KEY_SUBSCRIPTIONS, json.encodeToString(updated)).apply()
    }

    fun savePushSubscriptionSync(sub: PushSubscription) {
        val current = _subscriptions.value.filter { it.id != sub.id && it.endpoint != sub.endpoint } + sub
        _subscriptions.value = current
        prefs.edit().putString(KEY_SUBSCRIPTIONS, json.encodeToString(current)).apply()
    }

    suspend fun removePushSubscription(id: String) = mutex.withLock {
        val updated = _subscriptions.value.filter { it.id != id }
        _subscriptions.value = updated
        prefs.edit().putString(KEY_SUBSCRIPTIONS, json.encodeToString(updated)).apply()
    }

    suspend fun saveCapabilityToken(token: CapabilityToken) = mutex.withLock {
        val updated = _capabilityTokens.value.filter { it.lookupId != token.lookupId } + token
        _capabilityTokens.value = updated
        prefs.edit().putString(KEY_CAPABILITY_TOKENS, json.encodeToString(updated)).apply()
    }

    suspend fun revokeCapabilityToken(lookupId: String) = mutex.withLock {
        val updated = _capabilityTokens.value.map {
            if (it.lookupId == lookupId) it.copy(revoked = true) else it
        }
        _capabilityTokens.value = updated
        prefs.edit().putString(KEY_CAPABILITY_TOKENS, json.encodeToString(updated)).apply()
    }

    /**
     * Verifies an incoming HTTP bearer token:
     * 1. Header matches ^[A-Za-z0-9_-]{43}$
     * 2. lookupId = HMAC-SHA256(tokenLookupKey, token)
     * 3. Retrieves record indexed by lookupId. If not found or revoked, returns null.
     * 4. Compares SHA-256(token) against stored digest using MessageDigest.isEqual()
     */
    fun verifyCapabilityToken(token: String): CapabilityToken? {
        if (!TOKEN_REGEX.matches(token)) {
            return null
        }
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
        val lookupId = CryptoUtils.base64UrlEncode(CryptoUtils.hmacSha256(tokenLookupKey, tokenBytes))
        val candidate = _capabilityTokens.value.find { it.lookupId == lookupId } ?: return null

        if (candidate.revoked) {
            return null
        }

        val expectedHash = CryptoUtils.base64UrlEncode(CryptoUtils.sha256(tokenBytes))
        val valid = CryptoUtils.constantTimeEquals(
            candidate.tokenHash.toByteArray(StandardCharsets.UTF_8),
            expectedHash.toByteArray(StandardCharsets.UTF_8)
        )
        return if (valid) candidate else null
    }

    fun computeLookupIdForToken(token: String): String {
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
        return CryptoUtils.base64UrlEncode(CryptoUtils.hmacSha256(tokenLookupKey, tokenBytes))
    }

    // --- Pairing Session State Machine ---

    fun createPendingShortcutPairing(targetScope: String, label: String): PairingSession {
        cleanupExpiredSessions()
        val pairingIdBytes = CryptoUtils.secureRandomBytes(16)
        val nonceBytes = CryptoUtils.secureRandomBytes(32)

        val pairingId = CryptoUtils.base64UrlEncode(pairingIdBytes)
        val pairingNonce = CryptoUtils.base64UrlEncode(nonceBytes)
        val verificationCode = CryptoUtils.computeShortcutVerificationCode(nonceBytes)

        val session = PairingSession(
            pairingId = pairingId,
            pairingNonce = pairingNonce,
            verificationCode = verificationCode,
            targetScope = targetScope,
            type = PairingType.IOS_SHORTCUT,
            expiresAt = System.currentTimeMillis() + PAIRING_EXPIRY_MS,
            state = PairingState.PENDING,
            label = label
        )
        activePairingSessions[pairingId] = session
        _currentPairingSession.value = session
        return session
    }

    fun createPendingAndroidPairing(targetScope: String, label: String): PairingSession {
        cleanupExpiredSessions()
        val pairingIdBytes = CryptoUtils.secureRandomBytes(16)
        val nonceBytes = CryptoUtils.secureRandomBytes(32)
        val ephemeralKeyPair = CryptoUtils.generateEcKeyPair()
        val pubKeyBytes = CryptoUtils.encodeUncompressedP256(ephemeralKeyPair.public as ECPublicKey)

        val pairingId = CryptoUtils.base64UrlEncode(pairingIdBytes)
        val pairingNonce = CryptoUtils.base64UrlEncode(nonceBytes)

        val session = PairingSession(
            pairingId = pairingId,
            pairingNonce = pairingNonce,
            verificationCode = "PENDING",
            targetScope = targetScope,
            type = PairingType.ANDROID_PEER,
            expiresAt = System.currentTimeMillis() + PAIRING_EXPIRY_MS,
            state = PairingState.PENDING,
            hostEphemeralPrivateKeyPkcs8 = Base64.getEncoder().encodeToString(ephemeralKeyPair.private.encoded),
            hostEphemeralPublicKeyBytes = Base64.getEncoder().encodeToString(pubKeyBytes),
            label = label
        )
        activePairingSessions[pairingId] = session
        _currentPairingSession.value = session
        return session
    }

    fun getPairingSession(pairingId: String): PairingSession? {
        val session = activePairingSessions[pairingId] ?: return null
        if (System.currentTimeMillis() > session.expiresAt) {
            session.state = PairingState.EXPIRED
            activePairingSessions.remove(pairingId)
            if (_currentPairingSession.value?.pairingId == pairingId) {
                _currentPairingSession.value = null
            }
            return null
        }
        return session
    }

    fun updatePairingSession(session: PairingSession) {
        activePairingSessions[session.pairingId] = session
        _currentPairingSession.value = session.copy()
    }

    fun clearCurrentPairingSession() {
        _currentPairingSession.value = null
    }

    fun approvePairingSession(pairingId: String): Boolean {
        val session = activePairingSessions[pairingId] ?: return false
        if ((session.state == PairingState.PENDING || session.state == PairingState.APPROVED_PENDING_CLAIM) && System.currentTimeMillis() <= session.expiresAt) {
            session.state = PairingState.APPROVED_PENDING_CLAIM
            session.approvalDeferred.complete(true)
            _currentPairingSession.value = session.copy()
            return true
        }
        return false
    }

    fun rejectPairingSession(pairingId: String) {
        val session = activePairingSessions.remove(pairingId)
        if (session != null) {
            session.state = PairingState.REJECTED
            session.approvalDeferred.complete(false)
            if (_currentPairingSession.value?.pairingId == pairingId) {
                _currentPairingSession.value = null
            }
        }
    }

    fun consumeShortcutPairing(pairingId: String, submittedNonce: String): CapabilityTokenResult? {
        synchronized(this) {
            val session = activePairingSessions[pairingId] ?: return null
            if (session.state != PairingState.APPROVED_PENDING_CLAIM) return null
            if (System.currentTimeMillis() > session.expiresAt) {
                session.state = PairingState.EXPIRED
                activePairingSessions.remove(pairingId)
                return null
            }

            // Constant time check of nonce
            val nonceMatch = CryptoUtils.constantTimeEquals(
                session.pairingNonce.toByteArray(StandardCharsets.UTF_8),
                submittedNonce.toByteArray(StandardCharsets.UTF_8)
            )
            if (!nonceMatch) return null

            // Mark consumed immediately
            session.state = PairingState.CONSUMED
            activePairingSessions.remove(pairingId)

            // Generate 32-byte cryptographically random token (43 chars Base64URL)
            val tokenBytes = CryptoUtils.secureRandomBytes(32)
            val rawToken = CryptoUtils.base64UrlEncode(tokenBytes)
            val lookupId = computeLookupIdForToken(rawToken)
            val tokenHash = CryptoUtils.base64UrlEncode(CryptoUtils.sha256(rawToken.toByteArray(StandardCharsets.UTF_8)))

            val capToken = CapabilityToken(
                lookupId = lookupId,
                tokenHash = tokenHash,
                targetDeviceId = session.targetScope,
                label = session.label ?: "iOS Shortcut",
                createdAt = System.currentTimeMillis(),
                revoked = false
            )

            val updated = _capabilityTokens.value.filter { it.lookupId != capToken.lookupId } + capToken
            _capabilityTokens.value = updated
            prefs.edit().putString(KEY_CAPABILITY_TOKENS, json.encodeToString(updated)).apply()

            return CapabilityTokenResult(rawToken, capToken)
        }
    }

    fun createCapabilityTokenForDevice(label: String, targetDeviceId: String = "Home"): CapabilityTokenResult {
        val tokenBytes = CryptoUtils.secureRandomBytes(32)
        val rawToken = CryptoUtils.base64UrlEncode(tokenBytes)
        val lookupId = computeLookupIdForToken(rawToken)
        val tokenHash = CryptoUtils.base64UrlEncode(CryptoUtils.sha256(rawToken.toByteArray(StandardCharsets.UTF_8)))

        val capToken = CapabilityToken(
            lookupId = lookupId,
            tokenHash = tokenHash,
            targetDeviceId = targetDeviceId,
            label = label,
            createdAt = System.currentTimeMillis(),
            revoked = false
        )

        val updated = _capabilityTokens.value.filter { it.lookupId != capToken.lookupId } + capToken
        _capabilityTokens.value = updated
        prefs.edit().putString(KEY_CAPABILITY_TOKENS, json.encodeToString(updated)).apply()

        return CapabilityTokenResult(rawToken, capToken)
    }

    // --- Local Wi-Fi Join Requests ---

    private val _pendingJoinRequests = MutableStateFlow<List<com.buzzme.app.data.model.PendingJoinRequest>>(emptyList())
    val pendingJoinRequests: StateFlow<List<com.buzzme.app.data.model.PendingJoinRequest>> = _pendingJoinRequests.asStateFlow()

    private val allJoinRequests = ConcurrentHashMap<String, com.buzzme.app.data.model.PendingJoinRequest>()

    var autoApproveOnWifi: Boolean
        get() = prefs.getBoolean("auto_approve_on_wifi", true)
        set(value) { prefs.edit().putBoolean("auto_approve_on_wifi", value).apply() }

    fun addPendingJoinRequest(request: com.buzzme.app.data.model.PendingJoinRequest) {
        allJoinRequests[request.requestId] = request
        _pendingJoinRequests.value = allJoinRequests.values.filter { it.status == "pending" }
    }

    fun getPendingJoinRequest(requestId: String): com.buzzme.app.data.model.PendingJoinRequest? {
        return allJoinRequests[requestId]
    }

    fun approveJoinRequest(requestId: String): com.buzzme.app.data.model.PendingJoinRequest? {
        val request = allJoinRequests[requestId] ?: return null
        if (request.status == "approved") return request

        if (request.deviceType == "ios") {
            val tokenResult = createCapabilityTokenForDevice(label = request.deviceName)
            request.iosToken = tokenResult.rawToken
            request.hostName = getDeviceName().ifBlank { "Household Host" }
            request.status = "approved"
        } else if (request.deviceType == "android" && !request.peerPubKey.isNullOrBlank() && !request.peerId.isNullOrBlank()) {
            val ephemeralKeyPair = CryptoUtils.generateEcKeyPair()
            val hostPubKeyBytes = CryptoUtils.encodeUncompressedP256(ephemeralKeyPair.public as ECPublicKey)
            val peerPubKeyBytes = CryptoUtils.base64UrlDecode(request.peerPubKey)
            val peerPublicKey = CryptoUtils.decodeUncompressedP256(peerPubKeyBytes)

            val sharedSecret = CryptoUtils.computeEcdhSharedSecret(ephemeralKeyPair.private, peerPublicKey)
            val pairwiseSecret = CryptoUtils.derivePairwiseSecret(sharedSecret, ByteArray(32))
            val (wrapped, iv) = encryptSecret(pairwiseSecret)

            val keyId = UUID.randomUUID().toString()
            val sessionEpoch = System.currentTimeMillis()

            val peerRecord = AndroidPeerAuth(
                keyId = keyId,
                peerDeviceId = request.peerId,
                label = request.deviceName,
                wrappedSecret = wrapped,
                iv = iv,
                sessionEpoch = sessionEpoch,
                highestSeq = 0L,
                targetScope = "Home",
                hostIp = request.deviceIp,
                port = 8899
            )
            savePeerSync(peerRecord)

            request.assignedKeyId = keyId
            request.sessionEpoch = sessionEpoch
            request.hostName = getDeviceName().ifBlank { "Household Host" }
            request.hostPublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(hostPubKeyBytes)
            request.status = "approved"
        }

        allJoinRequests[requestId] = request
        _pendingJoinRequests.value = allJoinRequests.values.filter { it.status == "pending" }
        return request
    }

    fun declineJoinRequest(requestId: String) {
        val request = allJoinRequests[requestId] ?: return
        request.status = "declined"
        allJoinRequests[requestId] = request
        _pendingJoinRequests.value = allJoinRequests.values.filter { it.status == "pending" }
    }

    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expired = activePairingSessions.filterValues { it.expiresAt < now }.keys
        expired.forEach { activePairingSessions.remove(it) }
    }

    data class CapabilityTokenResult(
        val rawToken: String,
        val capabilityToken: CapabilityToken
    )

    companion object {
        private const val PREFS_NAME = "buzzme_encrypted_prefs"
        private const val KEY_HOST_DEVICE_ID = "host_device_id"
        private const val KEY_TOKEN_LOOKUP_KEY = "token_lookup_key"
        private const val KEY_TOKEN_LOOKUP_IV = "token_lookup_iv"
        private const val KEY_PEERS = "peers_json"
        private const val KEY_SUBSCRIPTIONS = "subscriptions_json"
        private const val KEY_CAPABILITY_TOKENS = "capability_tokens_json"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_IS_PRIMARY_HOST = "is_primary_host"
        private const val KEY_LAST_NOTE_SENDER = "last_note_sender"
        private const val KEY_LAST_NOTE_MESSAGE = "last_note_message"
        private const val KEY_LAST_NOTE_TIME = "last_note_time"

        private const val KEY_AUTO_START_SESSION = "auto_start_session"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "BuzzMeKeyStoreMaster"
        private const val VAPID_KEY_ALIAS = "BuzzMeVapidKey"

        const val PAIRING_EXPIRY_MS = 120_000L // 120 seconds
        val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{43}$")
    }

    /** True if the user has ever explicitly started a session (persisted across reboots). */
    var autoStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_SESSION, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_START_SESSION, value).apply() }

    fun saveLastReceivedNote(senderLabel: String, message: String, timestampMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_NOTE_SENDER, senderLabel)
            .putString(KEY_LAST_NOTE_MESSAGE, message)
            .putLong(KEY_LAST_NOTE_TIME, timestampMs)
            .apply()
    }

    fun getLastReceivedNote(): Triple<String, String, Long>? {
        val sender = prefs.getString(KEY_LAST_NOTE_SENDER, null) ?: return null
        val message = prefs.getString(KEY_LAST_NOTE_MESSAGE, null) ?: return null
        val time = prefs.getLong(KEY_LAST_NOTE_TIME, 0L)
        return Triple(sender, message, time)
    }

    fun clearLastReceivedNote() {
        prefs.edit()
            .remove(KEY_LAST_NOTE_SENDER)
            .remove(KEY_LAST_NOTE_MESSAGE)
            .remove(KEY_LAST_NOTE_TIME)
            .apply()
    }
}
