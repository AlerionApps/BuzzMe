package com.buzzme.app.data.repository

import com.buzzme.app.crypto.CryptoUtils
import com.buzzme.app.data.model.AndroidPeerAuth
import com.buzzme.app.data.model.PairingSession
import com.buzzme.app.data.model.PairingState
import com.buzzme.app.data.model.PairingType
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MultiDevicePairingTest {

    @Test
    fun testPeerDeduplicationByDeviceIdAndKeyId() {
        val peers = mutableListOf<AndroidPeerAuth>()

        val peer1DeviceUuid = UUID.randomUUID().toString()
        val peer2DeviceUuid = UUID.randomUUID().toString()

        val peer1FirstSession = AndroidPeerAuth(
            keyId = UUID.randomUUID().toString(),
            peerDeviceId = peer1DeviceUuid,
            label = "Phone 1 (Old Key)",
            wrappedSecret = "secret1",
            iv = "iv1",
            sessionEpoch = 1L,
            highestSeq = 5L,
            targetScope = "home",
            hostIp = "192.168.1.10",
            port = 8899
        )

        val peer2Session = AndroidPeerAuth(
            keyId = UUID.randomUUID().toString(),
            peerDeviceId = peer2DeviceUuid,
            label = "Phone 2",
            wrappedSecret = "secret2",
            iv = "iv2",
            sessionEpoch = 1L,
            highestSeq = 0L,
            targetScope = "home",
            hostIp = "192.168.1.11",
            port = 8899
        )

        // Save Peer 1 and Peer 2
        peers.removeAll { it.keyId == peer1FirstSession.keyId || it.peerDeviceId == peer1FirstSession.peerDeviceId }
        peers.add(peer1FirstSession)

        peers.removeAll { it.keyId == peer2Session.keyId || it.peerDeviceId == peer2Session.peerDeviceId }
        peers.add(peer2Session)

        assertEquals(2, peers.size)

        // Now re-pair Peer 1 (same peerDeviceId, new keyId)
        val peer1RePairSession = AndroidPeerAuth(
            keyId = UUID.randomUUID().toString(),
            peerDeviceId = peer1DeviceUuid,
            label = "Phone 1 (New Key)",
            wrappedSecret = "secret1_new",
            iv = "iv1_new",
            sessionEpoch = 2L,
            highestSeq = 0L,
            targetScope = "home",
            hostIp = "192.168.1.10",
            port = 8899
        )

        peers.removeAll { it.keyId == peer1RePairSession.keyId || it.peerDeviceId == peer1RePairSession.peerDeviceId }
        peers.add(peer1RePairSession)

        // Verify total size remains 2 and Peer 1 record was updated
        assertEquals(2, peers.size)
        val storedPeer1 = peers.find { it.peerDeviceId == peer1DeviceUuid }
        assertNotNull(storedPeer1)
        assertEquals("Phone 1 (New Key)", storedPeer1!!.label)
        assertEquals(peer1RePairSession.keyId, storedPeer1.keyId)
    }

    @Test
    fun testSequentialMultiDevicePairingSessions() {
        val activeSessions = mutableMapOf<String, PairingSession>()

        // 1. Host creates Session for Device B
        val sessionB = createSession("Host", activeSessions)
        assertEquals(PairingState.PENDING, sessionB.state)
        assertEquals(1, activeSessions.size)

        // Device B completes pairing
        sessionB.state = PairingState.CONSUMED

        // 2. Host creates fresh Session for Device C
        val sessionC = createSession("Host", activeSessions)
        assertEquals(PairingState.PENDING, sessionC.state)
        assertEquals(2, activeSessions.size)

        // Session B and Session C have different pairingIds and nonces
        assertNotEquals(sessionB.pairingId, sessionC.pairingId)
        assertNotEquals(sessionB.pairingNonce, sessionC.pairingNonce)

        // Device C completes pairing
        sessionC.state = PairingState.CONSUMED

        // 3. Host creates fresh Session for Device D
        val sessionD = createSession("Host", activeSessions)
        assertEquals(PairingState.PENDING, sessionD.state)
        assertEquals(3, activeSessions.size)

        assertTrue(activeSessions.values.count { it.state == PairingState.CONSUMED } == 2)
        assertTrue(activeSessions.values.count { it.state == PairingState.PENDING } == 1)
    }

    private fun createSession(label: String, map: MutableMap<String, PairingSession>): PairingSession {
        val pId = CryptoUtils.base64UrlEncode(CryptoUtils.secureRandomBytes(16))
        val nonce = CryptoUtils.base64UrlEncode(CryptoUtils.secureRandomBytes(32))
        val sess = PairingSession(
            pairingId = pId,
            pairingNonce = nonce,
            verificationCode = "PENDING",
            targetScope = "Home",
            type = PairingType.ANDROID_PEER,
            expiresAt = System.currentTimeMillis() + 120_000L,
            state = PairingState.PENDING,
            label = label
        )
        map[pId] = sess
        return sess
    }
}
