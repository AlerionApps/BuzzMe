package com.buzzme.app.network

import com.buzzme.app.crypto.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.UUID

object SocketClient {

    private const val SO_TIMEOUT_MS = 2000
    private const val ACK_SIZE = 41
    private const val ACK_VERSION = 0x02

    sealed class SendResult {
        object Success : SendResult()
        data class Error(val message: String) : SendResult()
    }

    suspend fun sendBuzz(
        host: String,
        port: Int = 8899,
        senderKeyId: UUID,
        targetDeviceId: UUID,
        sessionEpoch: Long,
        sequence: Long,
        message: String,
        pairwiseSecret: ByteArray
    ): SendResult = withContext(Dispatchers.IO) {
        val attempt1Result = trySendOnce(
            host, port, senderKeyId, targetDeviceId, sessionEpoch, sequence, message, pairwiseSecret, timeoutMs = 2000
        )
        if (attempt1Result is SendResult.Success) {
            return@withContext attempt1Result
        }

        android.util.Log.w("BuzzMe", "SocketClient attempt 1 failed ($attempt1Result). Backing off 300ms before attempt 2...")
        kotlinx.coroutines.delay(300)

        val attempt2Result = trySendOnce(
            host, port, senderKeyId, targetDeviceId, sessionEpoch, sequence, message, pairwiseSecret, timeoutMs = 2500
        )
        if (attempt2Result is SendResult.Success) {
            android.util.Log.i("BuzzMe", "SocketClient attempt 2 succeeded")
            return@withContext attempt2Result
        }

        android.util.Log.e("BuzzMe", "SocketClient attempt 2 failed ($attempt2Result)")
        attempt2Result
    }

    private fun trySendOnce(
        host: String,
        port: Int,
        senderKeyId: UUID,
        targetDeviceId: UUID,
        sessionEpoch: Long,
        sequence: Long,
        message: String,
        pairwiseSecret: ByteArray,
        timeoutMs: Int
    ): SendResult {
        val socket = Socket()
        try {
            android.util.Log.i("BuzzMe", "SocketClient connecting to $host:$port (timeout: ${timeoutMs}ms)...")
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            android.util.Log.i("BuzzMe", "Socket connected to $host:$port")

            val normalizedMsg = Normalizer.normalize(message, Normalizer.Form.NFC)
            val msgBytes = normalizedMsg.toByteArray(StandardCharsets.UTF_8)
            val n = msgBytes.size
            require(n <= 160) { "Message byte length cannot exceed 160, got $n" }

            // 1. Build 60-byte fixed header
            val header = ByteArray(60)
            header[0] = 0x01.toByte() // Version 0x01

            val senderKeyBytes = CryptoUtils.uuidToBytes(senderKeyId)
            val targetDeviceBytes = CryptoUtils.uuidToBytes(targetDeviceId)
            System.arraycopy(senderKeyBytes, 0, header, 1, 16)
            System.arraycopy(targetDeviceBytes, 0, header, 17, 16)

            val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            buffer.position(33)
            buffer.putLong(sessionEpoch)
            buffer.putLong(sequence)
            buffer.putLong(System.currentTimeMillis())
            buffer.put(0x01.toByte()) // Action BUZZ
            buffer.putShort(n.toShort())

            // 2. Build packet payload
            val fullPacketWithoutSig = ByteArray(60 + n)
            System.arraycopy(header, 0, fullPacketWithoutSig, 0, 60)
            if (n > 0) {
                System.arraycopy(msgBytes, 0, fullPacketWithoutSig, 60, n)
            }

            // 3. Compute HMAC-SHA256 signature
            val signature = CryptoUtils.hmacSha256(pairwiseSecret, fullPacketWithoutSig)

            // 4. Send frame: (60 + n + 32) bytes
            val out = socket.getOutputStream()
            out.write(fullPacketWithoutSig)
            out.write(signature)
            out.flush()
            android.util.Log.i("BuzzMe", "SocketClient transmitted frame (${60 + n + 32} bytes)")

            // 5. Read 41-byte ACK
            val input = socket.getInputStream()
            val ackBytes = ByteArray(ACK_SIZE)
            readExact(input, ackBytes, 0, ACK_SIZE)
            android.util.Log.i("BuzzMe", "SocketClient received 41-byte ACK from server")

            // 7. Verify ACK
            val ackVersion = ackBytes[0].toInt() and 0xFF
            if (ackVersion != ACK_VERSION) {
                android.util.Log.e("BuzzMe", "Invalid ACK version: $ackVersion")
                return SendResult.Error("Invalid ACK version: $ackVersion")
            }

            val ackBuf = ByteBuffer.wrap(ackBytes).order(ByteOrder.BIG_ENDIAN)
            ackBuf.position(1)
            val ackSeq = ackBuf.long
            if (ackSeq != sequence) {
                android.util.Log.e("BuzzMe", "ACK sequence mismatch: expected $sequence, got $ackSeq")
                return SendResult.Error("ACK sequence mismatch: expected $sequence, got $ackSeq")
            }

            val ackHeader = ackBytes.copyOfRange(0, 9)
            val expectedAckHmac = CryptoUtils.hmacSha256(pairwiseSecret, ackHeader)
            val receivedAckHmac = ackBytes.copyOfRange(9, 41)

            if (!CryptoUtils.constantTimeEquals(expectedAckHmac, receivedAckHmac)) {
                android.util.Log.e("BuzzMe", "Invalid ACK HMAC signature")
                return SendResult.Error("Invalid ACK HMAC signature")
            }

            android.util.Log.i("BuzzMe", "SocketClient ACK verified successfully!")
            return SendResult.Success
        } catch (e: Exception) {
            android.util.Log.e("BuzzMe", "SocketClient exception: ${e.message}", e)
            return SendResult.Error(e.message ?: "Socket connection failed")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun readExact(input: InputStream, target: ByteArray, offset: Int, length: Int) {
        var bytesRead = 0
        while (bytesRead < length) {
            val count = input.read(target, offset + bytesRead, length - bytesRead)
            if (count == -1) {
                throw EOFException("Reached EOF before reading $length bytes (read $bytesRead)")
            }
            bytesRead += count
        }
    }
}
