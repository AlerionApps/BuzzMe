package com.buzzme.app.crypto

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.interfaces.ECPublicKey
import java.util.UUID

class CryptoUtilsTest {

    @Test
    fun testEcKeyPairGenerationAndPointEncoding() {
        val keyPair = CryptoUtils.generateEcKeyPair()
        val pubKey = keyPair.public as ECPublicKey

        val encoded = CryptoUtils.encodeUncompressedP256(pubKey)
        assertEquals(65, encoded.size)
        assertEquals(0x04.toByte(), encoded[0])

        val decodedPubKey = CryptoUtils.decodeUncompressedP256(encoded)
        assertEquals(pubKey.w.affineX, decodedPubKey.w.affineX)
        assertEquals(pubKey.w.affineY, decodedPubKey.w.affineY)
    }

    @Test
    fun testEcdhKeyAgreementSymmetry() {
        val alice = CryptoUtils.generateEcKeyPair()
        val bob = CryptoUtils.generateEcKeyPair()

        val aliceShared = CryptoUtils.computeEcdhSharedSecret(alice.private, bob.public)
        val bobShared = CryptoUtils.computeEcdhSharedSecret(bob.private, alice.public)

        assertArrayEquals(aliceShared, bobShared)
    }

    @Test
    fun testHmacSha256AndConstantTimeEquals() {
        val key = "secret-key".toByteArray(StandardCharsets.UTF_8)
        val data = "buzz-message".toByteArray(StandardCharsets.UTF_8)

        val hmac1 = CryptoUtils.hmacSha256(key, data)
        val hmac2 = CryptoUtils.hmacSha256(key, data)

        assertEquals(32, hmac1.size)
        assertTrue(CryptoUtils.constantTimeEquals(hmac1, hmac2))

        val alteredData = "buzz-message-tampered".toByteArray(StandardCharsets.UTF_8)
        val hmacAltered = CryptoUtils.hmacSha256(key, data = alteredData)
        assertFalse(CryptoUtils.constantTimeEquals(hmac1, hmacAltered))
    }

    @Test
    fun testCanonicalTranscriptAndVerificationCodeDerivation() {
        val host = CryptoUtils.generateEcKeyPair()
        val peer = CryptoUtils.generateEcKeyPair()

        val pairingId = CryptoUtils.secureRandomBytes(16)
        val pairingNonce = CryptoUtils.secureRandomBytes(32)
        val hostDeviceId = UUID.randomUUID()
        val peerDeviceId = UUID.randomUUID()

        val hostPubKeyBytes = CryptoUtils.encodeUncompressedP256(host.public as ECPublicKey)
        val peerPubKeyBytes = CryptoUtils.encodeUncompressedP256(peer.public as ECPublicKey)

        // 1. Build Canonical Transcript
        val transcript = CryptoUtils.buildCanonicalTranscript(
            pairingId = pairingId,
            pairingNonce = pairingNonce,
            hostDeviceId = hostDeviceId,
            peerDeviceId = peerDeviceId,
            hostEphemeralPublicKey = hostPubKeyBytes,
            peerEphemeralPublicKey = peerPubKeyBytes
        )
        // 1 + 16 + 32 + 16 + 16 + 65 + 65 = 211 bytes
        assertEquals(211, transcript.size)
        assertEquals(0x01.toByte(), transcript[0])

        // 2. Both sides compute sharedSecret
        val hostSharedSecret = CryptoUtils.computeEcdhSharedSecret(host.private, peer.public)
        val peerSharedSecret = CryptoUtils.computeEcdhSharedSecret(peer.private, host.public)
        assertArrayEquals(hostSharedSecret, peerSharedSecret)

        // 3. Both sides derive handshakeKey
        val hostHandshakeKey = CryptoUtils.deriveHandshakeKey(hostSharedSecret, pairingNonce)
        val peerHandshakeKey = CryptoUtils.deriveHandshakeKey(peerSharedSecret, pairingNonce)
        assertArrayEquals(hostHandshakeKey, peerHandshakeKey)

        // 4. Transcript Hash & Verification Code
        val transcriptHash = CryptoUtils.sha256(transcript)
        val hostCode = CryptoUtils.computeVerificationCode(hostHandshakeKey, transcriptHash)
        val peerCode = CryptoUtils.computeVerificationCode(peerHandshakeKey, transcriptHash)

        assertEquals(6, hostCode.length)
        assertEquals(hostCode, peerCode)

        // 5. Nonce proof verification
        val hostNonceProof = CryptoUtils.computeNonceProof(hostHandshakeKey, transcriptHash)
        val peerNonceProof = CryptoUtils.computeNonceProof(peerHandshakeKey, transcriptHash)
        assertTrue(CryptoUtils.constantTimeEquals(hostNonceProof, peerNonceProof))

        // 6. Pairwise secret derivation
        val hostPairwiseSecret = CryptoUtils.derivePairwiseSecret(hostSharedSecret, pairingNonce)
        val peerPairwiseSecret = CryptoUtils.derivePairwiseSecret(peerSharedSecret, pairingNonce)
        assertEquals(32, hostPairwiseSecret.size)
        assertArrayEquals(hostPairwiseSecret, peerPairwiseSecret)
    }

    @Test
    fun testUuidConversionRoundTrip() {
        val originalUuid = UUID.randomUUID()
        val bytes = CryptoUtils.uuidToBytes(originalUuid)
        assertEquals(16, bytes.size)

        val restoredUuid = CryptoUtils.bytesToUuid(bytes)
        assertEquals(originalUuid, restoredUuid)
    }
}
