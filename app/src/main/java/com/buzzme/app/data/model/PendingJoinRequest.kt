package com.buzzme.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PendingJoinRequest(
    val requestId: String,
    val deviceType: String, // "android" or "ios"
    val deviceName: String,
    val deviceIp: String,
    val peerId: String? = null, // Android client device UUID
    val peerPubKey: String? = null, // Base64Url uncompressed P-256 public key
    val requestedAt: Long = System.currentTimeMillis(),
    var status: String = "pending", // "pending", "approved", "declined"
    var assignedKeyId: String? = null,
    var sessionEpoch: Long? = null,
    var hostName: String? = null,
    var iosToken: String? = null,
    var hostPublicKey: String? = null
)
