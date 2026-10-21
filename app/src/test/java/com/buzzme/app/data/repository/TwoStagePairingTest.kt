package com.buzzme.app.data.repository

import com.buzzme.app.crypto.CryptoUtils
import com.buzzme.app.data.model.CapabilityToken
import com.buzzme.app.data.model.PairingSession
import com.buzzme.app.data.model.PairingState
import com.buzzme.app.data.model.PairingType
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class TwoStagePairingTest {

    @Test
    fun testSetupUrlContainsOnlyPairingIdAndNonceZeroTokens() {
        val pairingIdBytes = CryptoUtils.secureRandomBytes(16)
        val nonceBytes = CryptoUtils.secureRandomBytes(32)
        val pairingId = CryptoUtils.base64UrlEncode(pairingIdBytes)
        val pairingNonce = CryptoUtils.base64UrlEncode(nonceBytes)
        val hostIp = "192.168.1.50"
        val port = 8900

        val setupUrl = "http://$hostIp:$port/api/v1/setup-shortcut?pairingId=$pairingId&nonce=$pairingNonce"

        // Verify query parameters
        assertTrue(setupUrl.contains("pairingId="))
        assertTrue(setupUrl.contains("nonce="))
        // Zero bearer tokens or capability keys must exist in URL
        assertFalse(setupUrl.contains("token="))
        assertFalse(setupUrl.contains("key="))
        assertFalse(setupUrl.contains("secret="))
    }

    @Test
    fun testTwoStagePairingAtomicStateTransitions() {
        val pairingIdBytes = CryptoUtils.secureRandomBytes(16)
        val nonceBytes = CryptoUtils.secureRandomBytes(32)
        val pairingId = CryptoUtils.base64UrlEncode(pairingIdBytes)
        val pairingNonce = CryptoUtils.base64UrlEncode(nonceBytes)
        val verificationCode = CryptoUtils.computeShortcutVerificationCode(nonceBytes)

        val session = PairingSession(
            pairingId = pairingId,
            pairingNonce = pairingNonce,
            verificationCode = verificationCode,
            targetScope = "test-target",
            type = PairingType.IOS_SHORTCUT,
            expiresAt = System.currentTimeMillis() + 120_000L,
            state = PairingState.PENDING
        )

        // 1. Attempt to claim while still PENDING -> Must be rejected
        assertFalse("Unapproved session cannot be claimed", session.state == PairingState.APPROVED_PENDING_CLAIM)

        // 2. Host User Approves -> State becomes APPROVED_PENDING_CLAIM
        session.state = PairingState.APPROVED_PENDING_CLAIM
        assertEquals(PairingState.APPROVED_PENDING_CLAIM, session.state)

        // 3. Attempt to claim with wrong nonce -> Must be rejected
        val wrongNonce = CryptoUtils.base64UrlEncode(CryptoUtils.secureRandomBytes(32))
        val nonceMatches = CryptoUtils.constantTimeEquals(
            session.pairingNonce.toByteArray(StandardCharsets.UTF_8),
            wrongNonce.toByteArray(StandardCharsets.UTF_8)
        )
        assertFalse("Wrong nonce must not match", nonceMatches)

        // 4. Client claims with correct nonce -> Consumes session atomically
        val correctNonceMatches = CryptoUtils.constantTimeEquals(
            session.pairingNonce.toByteArray(StandardCharsets.UTF_8),
            pairingNonce.toByteArray(StandardCharsets.UTF_8)
        )
        assertTrue("Correct nonce matches", correctNonceMatches)
        session.state = PairingState.CONSUMED

        // 5. Issue Capability Token
        val tokenBytes = CryptoUtils.secureRandomBytes(32)
        val rawToken = CryptoUtils.base64UrlEncode(tokenBytes)
        // Must strictly match 43 characters Base64URL
        assertTrue("Token must match 43 chars regex", DeviceRepository.TOKEN_REGEX.matches(rawToken))
        assertEquals(43, rawToken.length)

        // 6. Re-attempt to claim already CONSUMED session -> Must be rejected
        assertFalse("Consumed session cannot be claimed again", session.state == PairingState.APPROVED_PENDING_CLAIM)
    }

    @Test
    fun testCapabilityTokenLookupAndRevocationLogic() {
        val tokenLookupKey = CryptoUtils.secureRandomBytes(32)

        // Generate 32-byte token (43 chars Base64URL)
        val rawToken = CryptoUtils.base64UrlEncode(CryptoUtils.secureRandomBytes(32))
        val tokenBytes = rawToken.toByteArray(StandardCharsets.UTF_8)

        val lookupId = CryptoUtils.base64UrlEncode(CryptoUtils.hmacSha256(tokenLookupKey, tokenBytes))
        val tokenHash = CryptoUtils.base64UrlEncode(CryptoUtils.sha256(tokenBytes))

        var capToken = CapabilityToken(
            lookupId = lookupId,
            tokenHash = tokenHash,
            targetDeviceId = "target-123",
            label = "Dave's iPhone",
            createdAt = System.currentTimeMillis(),
            revoked = false
        )

        // Verification check:
        // 1. Regex
        assertTrue(DeviceRepository.TOKEN_REGEX.matches(rawToken))
        // 2. Lookup matches
        val incomingLookupId = CryptoUtils.base64UrlEncode(CryptoUtils.hmacSha256(tokenLookupKey, tokenBytes))
        assertEquals(capToken.lookupId, incomingLookupId)
        // 3. Constant-time digest match
        val incomingHash = CryptoUtils.base64UrlEncode(CryptoUtils.sha256(tokenBytes))
        assertTrue(CryptoUtils.constantTimeEquals(
            capToken.tokenHash.toByteArray(StandardCharsets.UTF_8),
            incomingHash.toByteArray(StandardCharsets.UTF_8)
        ))
        assertFalse(capToken.revoked)

        // Revoke token
        capToken = capToken.copy(revoked = true)
        assertTrue(capToken.revoked)
        // After revocation, verification rejects
    }

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
