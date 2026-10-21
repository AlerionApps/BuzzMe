package com.buzzme.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SubscriptionStatus {
    ACTIVE,
    REVOKED
}

@Serializable
data class PushSubscription(
    val id: String,                  // Internal record UUID
    val deviceId: String,            // Remote device UUID
    val label: String,               // e.g. "Mum's iPhone"
    val endpoint: String,            // Must be https://*.push.apple.com/...
    val p256dh: String,              // Base64URL client P-256 public key
    val auth: String,                // Base64URL 16-byte auth secret
    val createdAt: Long = System.currentTimeMillis(),
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE
)
