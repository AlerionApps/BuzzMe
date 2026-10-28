package com.buzzme.app.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    private const val EC_CURVE_NAME = "secp256r1" // NIST P-256
    private const val HMAC_SHA256 = "HmacSHA256"

    /**
     * Generates an ephemeral NIST P-256 EC KeyPair.
     */
    fun generateEcKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        val ecGenParameterSpec = ECGenParameterSpec(EC_CURVE_NAME)
        keyPairGenerator.initialize(ecGenParameterSpec, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Converts an ECPublicKey to 65-byte uncompressed point format:
     * 0x04 || X (32 bytes big-endian) || Y (32 bytes big-endian)
     */
    fun encodeUncompressedP256(publicKey: ECPublicKey): ByteArray {
        val point = publicKey.w
        val x = point.affineX.toByteArray()
        val y = point.affineY.toByteArray()

        val result = ByteArray(65)
        result[0] = 0x04

        // Copy X (right-aligned 32 bytes)
        val xOffset = if (x.size > 32) x.size - 32 else 0
        val xLen = minOf(x.size, 32)
        System.arraycopy(x, xOffset, result, 1 + (32 - xLen), xLen)

        // Copy Y (right-aligned 32 bytes)
        val yOffset = if (y.size > 32) y.size - 32 else 0
        val yLen = minOf(y.size, 32)
        System.arraycopy(y, yOffset, result, 33 + (32 - yLen), yLen)

        return result
    }

    /**
     * Decodes 65-byte uncompressed point format (0x04 || X || Y) back into ECPublicKey.
     */
    fun decodeUncompressedP256(encoded: ByteArray): ECPublicKey {
        require(encoded.size == 65 && encoded[0] == 0x04.toByte()) {
            "Invalid uncompressed P-256 public key point (length=${encoded.size}, prefix=${encoded.firstOrNull()})"
        }
        val xBytes = encoded.copyOfRange(1, 33)
        val yBytes = encoded.copyOfRange(33, 65)

        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val ecPoint = ECPoint(x, y)

        val algorithmParameters = AlgorithmParameters.getInstance("EC")
        algorithmParameters.init(ECGenParameterSpec(EC_CURVE_NAME))
        val ecParameterSpec = algorithmParameters.getParameterSpec(ECParameterSpec::class.java)

        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(ECPublicKeySpec(ecPoint, ecParameterSpec)) as ECPublicKey
    }

    /**
     * Computes ECDH shared secret between local private key and peer's public key.
     */
    fun computeEcdhSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    /**
     * Computes HMAC-SHA256 over data using key.
     */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(data)
    }

    /**
     * Computes SHA-256 digest of data.
     */
    fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    /**
     * Constant-time comparison to prevent timing attacks.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Converts a UUID to 16 bytes.
     */
    fun uuidToBytes(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    /**
     * Converts 16 bytes to a UUID.
     */
    fun bytesToUuid(bytes: ByteArray): UUID {
        require(bytes.size == 16) { "UUID requires exactly 16 bytes, got ${bytes.size}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val high = buffer.long
        val low = buffer.long
        return UUID(high, low)
    }

    /**
     * Encodes bytes as unpadded Base64URL string.
     */
    fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Decodes unpadded Base64URL string to bytes.
     */
    fun base64UrlDecode(str: String): ByteArray {
        val sanitized = str.trim().replace(' ', '+').replace('-', '+').replace('_', '/')
        val padded = when (sanitized.length % 4) {
            2 -> "$sanitized=="
            3 -> "$sanitized="
            else -> sanitized
        }
        return Base64.getDecoder().decode(padded)
    }

    /**
     * Generates N cryptographically secure random bytes.
     */
    fun secureRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * Builds canonical transcript bytes for Android-to-Android pairing:
     * protocolVersion (1 byte = 0x01)
     * || pairingId (16 bytes)
     * || pairingNonce (32 bytes)
     * || hostDeviceId (16 bytes UUID)
     * || peerDeviceId (16 bytes UUID)
     * || hostEphemeralPublicKey (65 bytes uncompressed P-256 point)
     * || peerEphemeralPublicKey (65 bytes uncompressed P-256 point)
     */
    fun buildCanonicalTranscript(
        pairingId: ByteArray,
        pairingNonce: ByteArray,
        hostDeviceId: UUID,
        peerDeviceId: UUID,
        hostEphemeralPublicKey: ByteArray,
        peerEphemeralPublicKey: ByteArray
    ): ByteArray {
        require(pairingId.size == 16) { "pairingId must be 16 bytes" }
        require(pairingNonce.size == 32) { "pairingNonce must be 32 bytes" }
        require(hostEphemeralPublicKey.size == 65) { "hostEphemeralPublicKey must be 65 bytes" }
        require(peerEphemeralPublicKey.size == 65) { "peerEphemeralPublicKey must be 65 bytes" }

        val hostUuidBytes = uuidToBytes(hostDeviceId)
        val peerUuidBytes = uuidToBytes(peerDeviceId)

        val totalLen = 1 + 16 + 32 + 16 + 16 + 65 + 65 // 211 bytes
        val buffer = ByteBuffer.allocate(totalLen)
        buffer.put(0x01.toByte())
        buffer.put(pairingId)
        buffer.put(pairingNonce)
        buffer.put(hostUuidBytes)
        buffer.put(peerUuidBytes)
        buffer.put(hostEphemeralPublicKey)
        buffer.put(peerEphemeralPublicKey)
        return buffer.array()
    }

    /**
     * Derives handshakeKey = HKDF-SHA256(ikm = sharedSecret, salt = pairingNonce, info = "BuzzMe-v1-handshake", len = 32)
     */
    fun deriveHandshakeKey(sharedSecret: ByteArray, pairingNonce: ByteArray): ByteArray {
        return Hkdf.deriveKey(
            salt = pairingNonce,
            ikm = sharedSecret,
            info = "BuzzMe-v1-handshake".toByteArray(StandardCharsets.UTF_8),
            length = 32
        )
    }

    /**
     * Derives pairwiseSecret = HKDF-SHA256(ikm = sharedSecret, salt = pairingNonce, info = "BuzzMe-v1-pairwise-hmac", len = 32)
     */
    fun derivePairwiseSecret(sharedSecret: ByteArray, pairingNonce: ByteArray): ByteArray {
        return Hkdf.deriveKey(
            salt = pairingNonce,
            ikm = sharedSecret,
            info = "BuzzMe-v1-pairwise-hmac".toByteArray(StandardCharsets.UTF_8),
            length = 32
        )
    }

    /**
     * Computes 6-digit verification code:
     * verificationCode = (first 4 bytes of HMAC-SHA256(handshakeKey, transcriptHash) as UInt32 BigEnd) mod 1,000,000
     */
    fun computeVerificationCode(handshakeKey: ByteArray, transcriptHash: ByteArray): String {
        val hmac = hmacSha256(handshakeKey, transcriptHash)
        val buffer = ByteBuffer.wrap(hmac).order(ByteOrder.BIG_ENDIAN)
        val intVal = buffer.int.toLong() and 0xFFFFFFFFL
        val codeNum = (intVal % 1_000_000L).toInt()
        return String.format(java.util.Locale.US, "%06d", codeNum)
    }

    /**
     * Computes nonceProof = HMAC-SHA256(handshakeKey, "BuzzMe-v1-confirm" (UTF-8) || transcriptHash)
     */
    fun computeNonceProof(handshakeKey: ByteArray, transcriptHash: ByteArray): ByteArray {
        val prefix = "BuzzMe-v1-confirm".toByteArray(StandardCharsets.UTF_8)
        val combined = ByteArray(prefix.size + transcriptHash.size)
        System.arraycopy(prefix, 0, combined, 0, prefix.size)
        System.arraycopy(transcriptHash, 0, combined, prefix.size, transcriptHash.size)
        return hmacSha256(handshakeKey, combined)
    }

    /**
     * Converts ASN.1 DER ECDSA signature to 64-byte JOSE / IEEE P1363 (R || S) format.
     */
    fun derToJose(der: ByteArray): ByteArray {
        var offset = 0
        require(der[offset++] == 0x30.toByte()) { "Invalid DER sequence header" }
        var len = der[offset++].toInt() and 0xFF
        if (len and 0x80 != 0) {
            val numBytes = len and 0x7F
            offset += numBytes
        }

        require(der[offset++] == 0x02.toByte()) { "Invalid DER integer header for R" }
        var rLen = der[offset++].toInt() and 0xFF
        var rOffset = offset
        offset += rLen

        require(der[offset++] == 0x02.toByte()) { "Invalid DER integer header for S" }
        var sLen = der[offset++].toInt() and 0xFF
        var sOffset = offset

        // Skip leading zeroes if length > 32
        while (rLen > 32 && der[rOffset] == 0x00.toByte()) {
            rOffset++
            rLen--
        }
        while (sLen > 32 && der[sOffset] == 0x00.toByte()) {
            sOffset++
            sLen--
        }

        val jose = ByteArray(64)
        System.arraycopy(der, rOffset, jose, 32 - rLen, rLen)
        System.arraycopy(der, sOffset, jose, 64 - sLen, sLen)
        return jose
    }
}
