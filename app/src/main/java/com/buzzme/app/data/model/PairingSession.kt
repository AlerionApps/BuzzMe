package com.buzzme.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class PairingState {
    PENDING,
    APPROVED_PENDING_CLAIM,
    CONSUMED,
    EXPIRED,
    REJECTED
}

@Serializable
enum class PairingType {
    IOS_SHORTCUT,
    ANDROID_PEER
}

@Serializable
data class PairingSession(
    val pairingId: String,           // 16 bytes Base64URL
    val pairingNonce: String,        // 32 bytes Base64URL
    var verificationCode: String,    // 6 digits
    val targetScope: String,         // Recipient identifier
    val type: PairingType,
    val expiresAt: Long,             // now + 120s
    var state: PairingState = PairingState.PENDING,
    // Transient cryptographic fields for Android-to-Android ECDH
    var hostEphemeralPrivateKeyPkcs8: String? = null, // Base64 encoded PKCS#8 private key
    var hostEphemeralPublicKeyBytes: String? = null,   // Base64 encoded 65-byte uncompressed point
    var peerEphemeralPublicKeyBytes: String? = null,   // Base64 encoded 65-byte uncompressed point
    var derivedPairwiseSecretBase64: String? = null,
    var remotePeerDeviceId: String? = null,
    var remotePeerIp: String? = null,
    var label: String? = null,
    var savedPeerKeyId: String? = null,
    var savedSessionEpoch: Long? = null,
    @kotlinx.serialization.Transient
    val approvalDeferred: kotlinx.coroutines.CompletableDeferred<Boolean> = kotlinx.coroutines.CompletableDeferred()
)
