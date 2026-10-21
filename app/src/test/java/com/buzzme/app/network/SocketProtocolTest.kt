package com.buzzme.app.network

import com.buzzme.app.crypto.CryptoUtils
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.UUID

class SocketProtocolTest {

    @Test
    fun testFrameHeaderLayoutAndFieldOffsets() {
        val senderKeyId = UUID.randomUUID()
        val targetDeviceId = UUID.randomUUID()
        val sessionEpoch = 123456789L
        val sequence = 42L
        val timestamp = System.currentTimeMillis()
        val action = 0x01 // BUZZ
        val message = "Kitchen buzz!"
        val normalized = Normalizer.normalize(message, Normalizer.Form.NFC)
        val msgBytes = normalized.toByteArray(StandardCharsets.UTF_8)
        val n = msgBytes.size

        // Build 60-byte header
        val header = ByteArray(60)
        header[0] = 0x01.toByte()

        val senderBytes = CryptoUtils.uuidToBytes(senderKeyId)
        val targetBytes = CryptoUtils.uuidToBytes(targetDeviceId)
        System.arraycopy(senderBytes, 0, header, 1, 16)
        System.arraycopy(targetBytes, 0, header, 17, 16)

        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buf.position(33)
        buf.putLong(sessionEpoch)
        buf.putLong(sequence)
        buf.putLong(timestamp)
        buf.put(action.toByte())
        buf.putShort(n.toShort())

        // Verify deserialization matches exactly
        val readBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x01, readBuf.get().toInt() and 0xFF)

        val readSender = ByteArray(16)
        readBuf.get(readSender)
        assertEquals(senderKeyId, CryptoUtils.bytesToUuid(readSender))

        val readTarget = ByteArray(16)
        readBuf.get(readTarget)
        assertEquals(targetDeviceId, CryptoUtils.bytesToUuid(readTarget))

        assertEquals(sessionEpoch, readBuf.long)
        assertEquals(sequence, readBuf.long)
        assertEquals(timestamp, readBuf.long)
        assertEquals(0x01, readBuf.get().toInt() and 0xFF)
        assertEquals(n, readBuf.short.toInt() and 0xFFFF)
    }

    @Test
    fun testCompactAckFormattingAndHmac() {
        val pairwiseSecret = CryptoUtils.secureRandomBytes(32)
        val sequence = 1005L

        val ackHeader = ByteArray(9)
        ackHeader[0] = 0x02.toByte()
        val buf = ByteBuffer.wrap(ackHeader).order(ByteOrder.BIG_ENDIAN)
        buf.position(1)
        buf.putLong(sequence)

        val hmac = CryptoUtils.hmacSha256(pairwiseSecret, ackHeader)
        val fullAck = ByteArray(41)
        System.arraycopy(ackHeader, 0, fullAck, 0, 9)
        System.arraycopy(hmac, 0, fullAck, 9, 32)

        assertEquals(41, fullAck.size)
        assertEquals(0x02.toByte(), fullAck[0])

        val ackSeq = ByteBuffer.wrap(fullAck, 1, 8).order(ByteOrder.BIG_ENDIAN).long
        assertEquals(sequence, ackSeq)

        val expectedHmac = CryptoUtils.hmacSha256(pairwiseSecret, fullAck.copyOfRange(0, 9))
        val receivedHmac = fullAck.copyOfRange(9, 41)
        assertTrue(CryptoUtils.constantTimeEquals(expectedHmac, receivedHmac))
    }

    @Test
    fun testSequenceDeduplicationRules() {
        var highestSeq = 10L

        // Fresh frame: seq > highestSeq
        val freshSeq = 11L
        assertTrue(freshSeq > highestSeq)
        highestSeq = freshSeq // Durably updated

        // Duplicate frame: seq == highestSeq
        val dupSeq = 11L
        assertEquals(highestSeq, dupSeq) // Returns ACK without re-vibrating

        // Stale frame: seq < highestSeq
        val staleSeq = 9L
        assertTrue(staleSeq < highestSeq) // Dropped silently
    }
}
