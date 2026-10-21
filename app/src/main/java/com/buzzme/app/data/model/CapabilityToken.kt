package com.buzzme.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CapabilityToken(
    val lookupId: String,            // HMAC-SHA256(tokenLookupKey, token) in Base64URL
    val tokenHash: String,           // SHA-256(token) digest in Base64URL
    val targetDeviceId: String,      // Scoped recipient device UUID
    val label: String,               // e.g. "Dave's iPhone Shortcut"
    val createdAt: Long = System.currentTimeMillis(),
    val revoked: Boolean = false
)
