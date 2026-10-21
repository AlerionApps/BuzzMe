package com.buzzme.app.network.web

import com.buzzme.app.crypto.CryptoUtils
import com.buzzme.app.crypto.Hkdf
import com.buzzme.app.data.model.PushSubscription
import com.buzzme.app.data.repository.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WebPushManager(private val deviceRepository: DeviceRepository) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed class PushResult {
        data class Success(val statusCode: Int) : PushResult()
        data class Failure(val statusCode: Int?, val reason: String) : PushResult()
    }

    /**
     * Strictly validates the Apple Push Notification service endpoint according to canonical specs.
     */
    fun validateEndpoint(endpoint: String): URI {
        val uri = URI(endpoint)
        val rawHost = uri.host ?: throw IllegalArgumentException("Endpoint has no host")
        require(rawHost.all { it.code in 33..126 }) { "Endpoint host contains non-ASCII characters" }
        require(!rawHost.endsWith(".")) { "Endpoint host must not contain trailing dot" }
        val host = rawHost.lowercase(Locale.ROOT)

        require(uri.scheme.equals("https", ignoreCase = true)) { "Push endpoint must be HTTPS" }
        require(uri.userInfo == null) { "Push endpoint must not contain user-info" }
        require(uri.port == -1 || uri.port == 443) { "Push endpoint port must be 443" }
        require(host == "push.apple.com" || host.endsWith(".push.apple.com")) {
            "Unauthorized push provider host: $host"
        }
        return uri
    }

    suspend fun dispatchBuzzPush(
        subscription: PushSubscription,
        message: String = "Buzz!"
    ): PushResult = withContext(Dispatchers.IO) {
        try {
            val uri = validateEndpoint(subscription.endpoint)
            val audience = "${uri.scheme}://${uri.authority}"

            val vapidKeyPair = deviceRepository.getVapidKeyPair()
            val vapidToken = createVapidJwt(audience, vapidKeyPair)
            val vapidPublicKeyBytes = CryptoUtils.encodeUncompressedP256(vapidKeyPair.public as ECPublicKey)
            val vapidPublicKeyBase64Url = CryptoUtils.base64UrlEncode(vapidPublicKeyBytes)

            // Encrypt payload via RFC 8291 (aes128gcm)
            val payloadJson = """{"title":"BUZZ!","body":"$message","timestamp":${System.currentTimeMillis()}}"""
            val encryptedBody = encryptPayloadAes128Gcm(
                payloadBytes = payloadJson.toByteArray(StandardCharsets.UTF_8),
                recipientP256dhBase64Url = subscription.p256dh,
                recipientAuthBase64Url = subscription.auth
            )

            val requestBody = encryptedBody.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(subscription.endpoint)
                .post(requestBody)
                .header("Authorization", "vapid t=$vapidToken, k=$vapidPublicKeyBase64Url")
                .header("Content-Encoding", "aes128gcm")
                .header("TTL", "60")
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    PushResult.Success(resp.code)
                } else {
                    PushResult.Failure(resp.code, resp.body?.string() ?: "Push request failed")
                }
            }
        } catch (e: Exception) {
            PushResult.Failure(null, e.message ?: "Web Push delivery error")
        }
    }

    /**
     * Generates a signed VAPID JWT (RFC 8292).
     */
    fun createVapidJwt(audience: String, keyPair: java.security.KeyPair): String {
        val headerJson = """{"typ":"JWT","alg":"ES256"}"""
        val nowSec = System.currentTimeMillis() / 1000L
        val expSec = nowSec + 43200L // 12 hours
        val claimsJson = """{"aud":"$audience","exp":$expSec,"sub":"mailto:household@local.lan"}"""

        val headerEncoded = CryptoUtils.base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))
        val claimsEncoded = CryptoUtils.base64UrlEncode(claimsJson.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$headerEncoded.$claimsEncoded".toByteArray(StandardCharsets.UTF_8)

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(signingInput)
        val derSignature = signer.sign()
        val joseSignature = CryptoUtils.derToJose(derSignature)
        val signatureEncoded = CryptoUtils.base64UrlEncode(joseSignature)

        return "$headerEncoded.$claimsEncoded.$signatureEncoded"
    }

    /**
     * Implements RFC 8291 Message Encryption for Web Push (aes128gcm).
     */
    fun encryptPayloadAes128Gcm(
        payloadBytes: ByteArray,
        recipientP256dhBase64Url: String,
        recipientAuthBase64Url: String
    ): ByteArray {
        val recipientPubKeyBytes = CryptoUtils.base64UrlDecode(recipientP256dhBase64Url)
        val recipientAuthSecret = CryptoUtils.base64UrlDecode(recipientAuthBase64Url)
        val recipientPublicKey = CryptoUtils.decodeUncompressedP256(recipientPubKeyBytes)

        // 1. Generate local sender ephemeral EC P-256 keypair
        val senderKeyPair = CryptoUtils.generateEcKeyPair()
        val senderPubKeyBytes = CryptoUtils.encodeUncompressedP256(senderKeyPair.public as ECPublicKey)

        // 2. Generate 16 bytes cryptographic random salt
        val salt = CryptoUtils.secureRandomBytes(16)

        // 3. Compute ECDH shared secret
        val ecdhSecret = CryptoUtils.computeEcdhSharedSecret(senderKeyPair.private, recipientPublicKey)

        // 4. Derive IKM:
        // key_info = "WebPush: info" || 0x00 || recipientPublicKey || senderPublicKey
        val keyInfoPrefix = "WebPush: info".toByteArray(StandardCharsets.UTF_8)
        val keyInfo = ByteArray(keyInfoPrefix.size + 1 + recipientPubKeyBytes.size + senderPubKeyBytes.size)
        var offset = 0
        System.arraycopy(keyInfoPrefix, 0, keyInfo, offset, keyInfoPrefix.size)
        offset += keyInfoPrefix.size
        keyInfo[offset++] = 0x00
        System.arraycopy(recipientPubKeyBytes, 0, keyInfo, offset, recipientPubKeyBytes.size)
        offset += recipientPubKeyBytes.size
        System.arraycopy(senderPubKeyBytes, 0, keyInfo, offset, senderPubKeyBytes.size)

        val prk = Hkdf.extract(salt = recipientAuthSecret, ikm = ecdhSecret)
        val ikm = Hkdf.expand(prk = prk, info = keyInfo, length = 32)

        // 5. Derive CEK and Nonce:
        // cek_info = "Content-Encoding: aes128gcm" || 0x00
        val cekInfoPrefix = "Content-Encoding: aes128gcm".toByteArray(StandardCharsets.UTF_8)
        val cekInfo = ByteArray(cekInfoPrefix.size + 1).apply {
            System.arraycopy(cekInfoPrefix, 0, this, 0, cekInfoPrefix.size)
            this[cekInfoPrefix.size] = 0x00
        }
        val prkCek = Hkdf.extract(salt = salt, ikm = ikm)
        val cek = Hkdf.expand(prk = prkCek, info = cekInfo, length = 16)

        // nonce_info = "Content-Encoding: nonce" || 0x00
        val nonceInfoPrefix = "Content-Encoding: nonce".toByteArray(StandardCharsets.UTF_8)
        val nonceInfo = ByteArray(nonceInfoPrefix.size + 1).apply {
            System.arraycopy(nonceInfoPrefix, 0, this, 0, nonceInfoPrefix.size)
            this[nonceInfoPrefix.size] = 0x00
        }
        val nonce = Hkdf.expand(prk = prkCek, info = nonceInfo, length = 12)

        // 6. Record padding: payload || 0x02 (last record delimiter)
        val plaintext = ByteArray(payloadBytes.size + 1)
        System.arraycopy(payloadBytes, 0, plaintext, 0, payloadBytes.size)
        plaintext[payloadBytes.size] = 0x02.toByte()

        // 7. Encrypt with AES-128-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        // 8. Build RFC 8291 header:
        // salt (16 bytes) || rs (4 bytes) || idlen (1 byte = 65) || keyid (65 bytes)
        val header = ByteArray(16 + 4 + 1 + 65)
        System.arraycopy(salt, 0, header, 0, 16)
        val rsBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        rsBuffer.putInt(4096)
        System.arraycopy(rsBuffer.array(), 0, header, 16, 4)
        header[20] = 65.toByte() // senderPubKeyBytes length
        System.arraycopy(senderPubKeyBytes, 0, header, 21, 65)

        val fullBody = ByteArray(header.size + ciphertext.size)
        System.arraycopy(header, 0, fullBody, 0, header.size)
        System.arraycopy(ciphertext, 0, fullBody, header.size, ciphertext.size)

        return fullBody
    }
}
