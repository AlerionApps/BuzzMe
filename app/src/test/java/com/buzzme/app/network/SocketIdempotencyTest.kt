package com.buzzme.app.network

import com.buzzme.app.crypto.CryptoUtils
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.UUID

class SocketIdempotencyTest {

    private fun buildPacket(
        senderKeyId: UUID,
        targetDeviceId: UUID,
        sessionEpoch: Long,
        sequence: Long,
        timestampMs: Long,
        action: Int,
        message: String,
        pairwiseSecret: ByteArray
    ): ByteArray {
        val normalized = Normalizer.normalize(message, Normalizer.Form.NFC)
        val msgBytes = normalized.toByteArray(StandardCharsets.UTF_8)
        val n = msgBytes.size

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
        buf.putLong(timestampMs)
        buf.put(action.toByte())
        buf.putShort(n.toShort())

        val packetWithoutSig = ByteArray(60 + n)
        System.arraycopy(header, 0, packetWithoutSig, 0, 60)
        System.arraycopy(msgBytes, 0, packetWithoutSig, 60, n)

        val signature = CryptoUtils.hmacSha256(pairwiseSecret, packetWithoutSig)

        val fullFrame = ByteArray(60 + n + 32)
        System.arraycopy(packetWithoutSig, 0, fullFrame, 0, 60 + n)
        System.arraycopy(signature, 0, fullFrame, 60 + n, 32)
        return fullFrame
    }

    private data class ProcessResult(
        val ackSent: Boolean,
        val alertTriggered: Boolean,
        val newHighestSeq: Long
    )

    private fun simulateReceive(
        frame: ByteArray,
        currentHighestSeq: Long,
        pairwiseSecret: ByteArray
    ): ProcessResult {
        if (frame.size < 60 + 32) return ProcessResult(false, false, currentHighestSeq)

        val headerBytes = frame.copyOfRange(0, 60)
        val version = headerBytes[0].toInt() and 0xFF
        if (version != 0x01) return ProcessResult(false, false, currentHighestSeq)

        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(33)
        val sessionEpoch = buf.long
        val sequence = buf.long
        val timestampMs = buf.long
        val action = buf.get().toInt() and 0xFF
        val messageLen = buf.short.toInt() and 0xFFFF

        if (action != 0x01 || messageLen > 160) return ProcessResult(false, false, currentHighestSeq)
        if (frame.size != 60 + messageLen + 32) return ProcessResult(false, false, currentHighestSeq)

        // Verify HMAC
        val packetWithoutSig = frame.copyOfRange(0, 60 + messageLen)
        val expectedHmac = CryptoUtils.hmacSha256(pairwiseSecret, packetWithoutSig)
        val receivedHmac = frame.copyOfRange(60 + messageLen, frame.size)
        if (!CryptoUtils.constantTimeEquals(expectedHmac, receivedHmac)) {
            return ProcessResult(false, false, currentHighestSeq)
        }

        // Verify timestamp drift
        val now = System.currentTimeMillis()
        if (Math.abs(now - timestampMs) > 120_000L) {
            return ProcessResult(false, false, currentHighestSeq)
        }

        // Idempotency rules:
        return if (sequence < currentHighestSeq) {
            // Stale: drop silently
            ProcessResult(ackSent = false, alertTriggered = false, newHighestSeq = currentHighestSeq)
        } else if (sequence == currentHighestSeq) {
            // Duplicate: ACK sent, DO NOT re-vibrate
            ProcessResult(ackSent = true, alertTriggered = false, newHighestSeq = currentHighestSeq)
        } else {
            // Fresh: sequence > currentHighestSeq
            ProcessResult(ackSent = true, alertTriggered = true, newHighestSeq = sequence)
        }
    }

    @Test
    fun testSocketProtocolIdempotencyAndReplayProtection() {
        val pairwiseSecret = CryptoUtils.secureRandomBytes(32)
        val senderKeyId = UUID.randomUUID()
        val targetDeviceId = UUID.randomUUID()
        val epoch = 1001L

        var currentHighestSeq = 0L

        // 1. Fresh Frame (seq = 1)
        val frame1 = buildPacket(
            senderKeyId, targetDeviceId, epoch,
            sequence = 1L,
            timestampMs = System.currentTimeMillis(),
            action = 0x01,
            message = "Buzz 1",
            pairwiseSecret = pairwiseSecret
        )
        val res1 = simulateReceive(frame1, currentHighestSeq, pairwiseSecret)
        assertTrue("Fresh frame must send ACK", res1.ackSent)
        assertTrue("Fresh frame must trigger alert", res1.alertTriggered)
        assertEquals(1L, res1.newHighestSeq)
        currentHighestSeq = res1.newHighestSeq

        // 2. Duplicate Frame (seq = 1)
        val resDuplicate = simulateReceive(frame1, currentHighestSeq, pairwiseSecret)
        assertTrue("Duplicate frame must send ACK", resDuplicate.ackSent)
        assertFalse("Duplicate frame must NOT re-vibrate", resDuplicate.alertTriggered)
        assertEquals(1L, resDuplicate.newHighestSeq)

        // 3. Stale Frame (seq = 0 < highestSeq)
        val staleFrame = buildPacket(
            senderKeyId, targetDeviceId, epoch,
            sequence = 0L,
            timestampMs = System.currentTimeMillis(),
            action = 0x01,
            message = "Old buzz",
            pairwiseSecret = pairwiseSecret
        )
        val resStale = simulateReceive(staleFrame, currentHighestSeq, pairwiseSecret)
        assertFalse("Stale frame must drop silently (no ACK)", resStale.ackSent)
        assertFalse("Stale frame must not trigger alert", resStale.alertTriggered)

        // 4. Stale Timestamp (> 120s drift)
        val expiredTimestampFrame = buildPacket(
            senderKeyId, targetDeviceId, epoch,
            sequence = 2L,
            timestampMs = System.currentTimeMillis() - 150_000L, // 150s ago
            action = 0x01,
            message = "Expired clock",
            pairwiseSecret = pairwiseSecret
        )
        val resExpired = simulateReceive(expiredTimestampFrame, currentHighestSeq, pairwiseSecret)
        assertFalse("Frame with expired clock drift must be dropped", resExpired.ackSent)

        // 5. Fresh Frame (seq = 2)
        val frame2 = buildPacket(
            senderKeyId, targetDeviceId, epoch,
            sequence = 2L,
            timestampMs = System.currentTimeMillis(),
            action = 0x01,
            message = "Buzz 2",
            pairwiseSecret = pairwiseSecret
        )
        val res2 = simulateReceive(frame2, currentHighestSeq, pairwiseSecret)
        assertTrue("Fresh frame 2 must send ACK", res2.ackSent)
        assertTrue("Fresh frame 2 must trigger alert", res2.alertTriggered)
        assertEquals(2L, res2.newHighestSeq)
    }
}
