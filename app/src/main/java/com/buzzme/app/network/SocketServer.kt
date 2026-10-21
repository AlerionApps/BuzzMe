package com.buzzme.app.network

import android.content.Context
import com.buzzme.app.crypto.CryptoUtils
import com.buzzme.app.data.repository.DeviceRepository
import com.buzzme.app.service.AlertEngine
import kotlinx.coroutines.*
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.UUID

class SocketServer(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val onAlertReceived: ((senderLabel: String, message: String) -> Unit)? = null
) {
    var alertListener: ((senderLabel: String, message: String) -> Unit)? = null
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start(port: Int = 8899, bindAddress: String? = null) {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        if (bindAddress != null) {
            ss.bind(InetSocketAddress(bindAddress, port))
        } else {
            ss.bind(InetSocketAddress(port))
        }
        serverSocket = ss

        serverJob = scope.launch {
            while (isActive && !ss.isClosed) {
                try {
                    val clientSocket = ss.accept()
                    launch {
                        handleClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (ss.isClosed) break
                }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = SO_TIMEOUT_MS
            val input = s.getInputStream()
            val output = s.getOutputStream()

            try {
                android.util.Log.i("BuzzMe", "SocketServer accepted incoming TCP connection from ${socket.remoteSocketAddress}")
                // 1. Read fixed 60-byte header
                val headerBytes = ByteArray(FIXED_HEADER_SIZE)
                readExact(input, headerBytes, 0, FIXED_HEADER_SIZE)

                val version = headerBytes[0].toInt() and 0xFF
                if (version != PROTOCOL_VERSION) {
                    android.util.Log.e("BuzzMe", "SocketServer rejected protocol version: $version")
                    return // Reject unrecognized protocol version
                }

                val senderKeyIdBytes = headerBytes.copyOfRange(1, 17)
                val senderKeyId = CryptoUtils.bytesToUuid(senderKeyIdBytes).toString()

                val targetDeviceIdBytes = headerBytes.copyOfRange(17, 33)
                val targetDeviceId = CryptoUtils.bytesToUuid(targetDeviceIdBytes)
                if (targetDeviceId != deviceRepository.hostDeviceId) {
                    android.util.Log.e("BuzzMe", "SocketServer rejected frame: targetDeviceId mismatch ($targetDeviceId != ${deviceRepository.hostDeviceId})")
                    return
                }

                val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
                buffer.position(33)
                val sessionEpoch = buffer.long
                val sequence = buffer.long
                val timestampMs = buffer.long
                val action = buffer.get().toInt() and 0xFF
                val messageLen = buffer.short.toInt() and 0xFFFF

                android.util.Log.i("BuzzMe", "SocketServer header parsed: senderKeyId=$senderKeyId, epoch=$sessionEpoch, seq=$sequence, msgLen=$messageLen, action=$action")

                // Action MUST be 0x01 (BUZZ)
                if (action != ACTION_BUZZ) {
                    android.util.Log.e("BuzzMe", "SocketServer rejected unrecognized action: $action")
                    return
                }

                // Message length must be 0 to 160
                if (messageLen > MAX_MESSAGE_LEN) {
                    android.util.Log.e("BuzzMe", "SocketServer rejected oversized message length: $messageLen")
                    return
                }

                // 2. Read N message bytes + 32 signature bytes
                val remainingLen = messageLen + HMAC_SIZE
                val remainingBytes = ByteArray(remainingLen)
                readExact(input, remainingBytes, 0, remainingLen)

                // 3. Verify no trailing bytes
                if (input.available() > 0) {
                    android.util.Log.e("BuzzMe", "SocketServer rejected packet with trailing bytes")
                    return // Malformed: trailing bytes present
                }

                // 4. Resolve sender key record
                val peer = deviceRepository.findPeerByKeyId(senderKeyId)
                if (peer == null) {
                    android.util.Log.e("BuzzMe", "SocketServer unknown senderKeyId: $senderKeyId. Peer not found in repository.")
                    return
                }
                val pairwiseSecret = deviceRepository.decryptSecret(peer.wrappedSecret, peer.iv)

                // 5. Verify HMAC-SHA256 signature
                val fullPacketWithoutSig = ByteArray(FIXED_HEADER_SIZE + messageLen)
                System.arraycopy(headerBytes, 0, fullPacketWithoutSig, 0, FIXED_HEADER_SIZE)
                System.arraycopy(remainingBytes, 0, fullPacketWithoutSig, FIXED_HEADER_SIZE, messageLen)

                val expectedHmac = CryptoUtils.hmacSha256(pairwiseSecret, fullPacketWithoutSig)
                val providedHmac = remainingBytes.copyOfRange(messageLen, remainingLen)

                if (!CryptoUtils.constantTimeEquals(expectedHmac, providedHmac)) {
                    android.util.Log.e("BuzzMe", "SocketServer rejected frame: HMAC signature mismatch")
                    return // Reject invalid HMAC signature
                }

                // 6. Validate signed timestamp drift <= 120,000ms
                val now = System.currentTimeMillis()
                if (Math.abs(now - timestampMs) > MAX_TIMESTAMP_DRIFT_MS) {
                    android.util.Log.e("BuzzMe", "SocketServer rejected frame: timestamp drift too large (${Math.abs(now - timestampMs)}ms)")
                    return // Stale or invalid clock drift
                }

                // 7. Parse & NFC-normalize message
                val rawMessage = String(remainingBytes, 0, messageLen, StandardCharsets.UTF_8)
                val normalizedMessage = Normalizer.normalize(rawMessage, Normalizer.Form.NFC)

                // 8. Idempotent Sequence Processing
                val currentHighestSeq = peer.highestSeq
                android.util.Log.i("BuzzMe", "SocketServer validating sequence: incoming=$sequence, currentHighest=$currentHighestSeq for '${peer.label}'")

                if (sequence < currentHighestSeq) {
                    android.util.Log.w("BuzzMe", "SocketServer dropped stale/replayed frame ($sequence < $currentHighestSeq)")
                    return
                }

                val ackBytes = buildCompactAck(sequence, pairwiseSecret)

                if (sequence == currentHighestSeq) {
                    android.util.Log.i("BuzzMe", "SocketServer duplicate sequence ($sequence == $currentHighestSeq): returning ACK without alert")
                    output.write(ackBytes)
                    output.flush()
                    return
                }

                // Extract remote IPv4 address from socket if available (filtering virtual/loopback IPs)
                val candidateIp = (socket.inetAddress as? java.net.Inet4Address)?.hostAddress
                    ?: (socket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress
                val remoteIp = if (candidateIp != null && candidateIp != "127.0.0.1" && candidateIp != "10.0.2.2" && candidateIp != "10.0.2.15") {
                    candidateIp
                } else null

                // Fresh frame: sequence > currentHighestSeq
                // Update sequence & peer IP durably
                deviceRepository.updatePeerIpAndSequence(senderKeyId, remoteIp, sequence)
                android.util.Log.i("BuzzMe", "SocketServer updated sequence to $sequence (IP: $remoteIp). Dispatching alert for '${peer.label}': '$normalizedMessage'")

                // Dispatch alert
                AlertEngine.dispatchBuzzAlert(context, peer.label, normalizedMessage, senderId = peer.keyId)
                onAlertReceived?.invoke(peer.label, normalizedMessage)
                alertListener?.invoke(peer.label, normalizedMessage)

                // Transmit ACK
                output.write(ackBytes)
                output.flush()
                android.util.Log.i("BuzzMe", "SocketServer sent 41-byte authenticated ACK to client")

            } catch (e: SocketTimeoutException) {
                android.util.Log.e("BuzzMe", "SocketServer SocketTimeoutException", e)
            } catch (e: EOFException) {
                android.util.Log.e("BuzzMe", "SocketServer EOFException", e)
            } catch (e: Exception) {
                android.util.Log.e("BuzzMe", "SocketServer Exception", e)
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

    /**
     * Compact Authenticated ACK Format (41 bytes):
     * 0x02 || Sequence Echo (8 bytes) || HMAC-SHA256(pairwiseSecret, bytes[0..8]) (32 bytes)
     */
    fun buildCompactAck(sequence: Long, pairwiseSecret: ByteArray): ByteArray {
        val ackHeader = ByteArray(9)
        ackHeader[0] = ACK_VERSION.toByte()
        val buffer = ByteBuffer.wrap(ackHeader).order(ByteOrder.BIG_ENDIAN)
        buffer.position(1)
        buffer.putLong(sequence)

        val hmac = CryptoUtils.hmacSha256(pairwiseSecret, ackHeader)
        val fullAck = ByteArray(41)
        System.arraycopy(ackHeader, 0, fullAck, 0, 9)
        System.arraycopy(hmac, 0, fullAck, 9, 32)
        return fullAck
    }

    companion object {
        const val PROTOCOL_VERSION = 0x01
        const val ACK_VERSION = 0x02
        const val ACTION_BUZZ = 0x01
        const val FIXED_HEADER_SIZE = 60
        const val HMAC_SIZE = 32
        const val MAX_MESSAGE_LEN = 160
        const val SO_TIMEOUT_MS = 2000
        const val MAX_TIMESTAMP_DRIFT_MS = 300_000L // 300s (5 min) tolerant to local clock skew
    }
}
