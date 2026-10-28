package com.buzzme.app.data.repository

import org.junit.Assert.*
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun testHostQrPayloadSerializationWithDeviceName() {
        val payload = com.buzzme.app.ui.MainViewModel.HostQrPayload(
            hostIp = "192.168.0.37",
            port = 8900,
            pairingId = "pair-123",
            nonce = "nonce-456",
            hostPubKey = "pubkey-789",
            hostDeviceId = "dev-123",
            hostDeviceName = "Dad's Phone"
        )
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(payload)
        val decoded = kotlinx.serialization.json.Json.decodeFromString<com.buzzme.app.ui.MainViewModel.HostQrPayload>(jsonStr)
        assertEquals("Dad's Phone", decoded.hostDeviceName)
        assertEquals("192.168.0.37", decoded.hostIp)

        // Backward compatibility: JSON without hostDeviceName
        val legacyJson = """{"hostIp":"192.168.0.37","port":8900,"pairingId":"p","nonce":"n","hostPubKey":"k","hostDeviceId":"d"}"""
        val decodedLegacy = kotlinx.serialization.json.Json.decodeFromString<com.buzzme.app.ui.MainViewModel.HostQrPayload>(legacyJson)
        assertNull(decodedLegacy.hostDeviceName)
    }

    @Test
    fun testAndroidPairConfirmPayloadWithDeviceName() {
        val payload = com.buzzme.app.ui.MainViewModel.AndroidPairConfirmPayload(
            pairingId = "pair-123",
            peerId = "peer-456",
            peerPubKey = "pubkey-abc",
            nonceProof = "proof-xyz",
            peerIp = "192.168.0.38",
            peerDeviceName = "Leo's Phone"
        )
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(payload)
        val decoded = kotlinx.serialization.json.Json.decodeFromString<com.buzzme.app.ui.MainViewModel.AndroidPairConfirmPayload>(jsonStr)
        assertEquals("Leo's Phone", decoded.peerDeviceName)
        assertEquals("192.168.0.38", decoded.peerIp)

        // Backward compatibility: JSON without peerDeviceName
        val legacyJson = """{"pairingId":"p","peerId":"id","peerPubKey":"k","nonceProof":"proof"}"""
        val decodedLegacy = kotlinx.serialization.json.Json.decodeFromString<com.buzzme.app.ui.MainViewModel.AndroidPairConfirmPayload>(legacyJson)
        assertNull(decodedLegacy.peerDeviceName)
    }
}
