package com.buzzme.app.network.web

import com.buzzme.app.ui.MainViewModel.ZeroScanJoinResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JoinRequestSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testJoinRequestStatusResponseSerialization() {
        val original = HttpGateway.JoinRequestStatusResponse(
            requestId = "req-12345",
            status = "approved",
            deviceName = "Mother's Phone",
            hostName = "Household Host",
            assignedKeyId = "key-abcde-67890",
            sessionEpoch = 1730133845000L,
            hostPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE..."
        )

        val serialized = json.encodeToString(original)
        assertNotNull(serialized)

        // Verify that MainViewModel can decode it without serialization exceptions
        val decoded = json.decodeFromString<ZeroScanJoinResponse>(serialized)
        assertEquals("req-12345", decoded.requestId)
        assertEquals("approved", decoded.status)
        assertEquals("Mother's Phone", decoded.deviceName)
        assertEquals("Household Host", decoded.hostName)
        assertEquals("key-abcde-67890", decoded.assignedKeyId)
        assertEquals(1730133845000L, decoded.sessionEpoch)
        assertEquals("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...", decoded.hostPublicKey)
    }

    @Test
    fun testJoinRequestStatusPendingSerialization() {
        val pending = HttpGateway.JoinRequestStatusResponse(
            requestId = "req-pending-1",
            status = "pending",
            deviceName = "Mother's Phone",
            hostName = "Household Host"
        )

        val serialized = json.encodeToString(pending)
        val decoded = json.decodeFromString<ZeroScanJoinResponse>(serialized)
        assertEquals("req-pending-1", decoded.requestId)
        assertEquals("pending", decoded.status)
        assertEquals(null, decoded.assignedKeyId)
        assertEquals(null, decoded.sessionEpoch)
    }
}
