package com.buzzme.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AndroidPeerAuth(
    val keyId: String,               // Pairwise UUID identifying this key relationship
    val peerDeviceId: String,        // Remote peer's device UUID
    val label: String,               // User-friendly device name (e.g. "Living Room Tablet")
    val wrappedSecret: String,       // AES-256-GCM encrypted pairwiseSecret (Base64)
    val iv: String,                  // AES-256-GCM initialization vector (Base64)
    var sessionEpoch: Long,          // Non-zero 64-bit epoch
    var highestSeq: Long,            // Highest seen sequence counter (for replay protection & deduplication)
    val targetScope: String,         // Permitted target scope (e.g. "default" or device UUID)
    var hostIp: String? = null,      // Last known IP address
    var port: Int = 8899             // TCP port (default 8899)
)
