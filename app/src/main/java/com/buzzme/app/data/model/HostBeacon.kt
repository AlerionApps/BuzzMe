package com.buzzme.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class HostRole {
    PRIMARY_HOST,
    BACKUP_HOST
}

@Serializable
data class HostBeacon(
    val deviceId: String,
    val deviceName: String,
    val role: HostRole,
    val priority: Int,
    val activeHostId: String,
    val ipAddress: String,
    val timestampMs: Long = System.currentTimeMillis()
)
