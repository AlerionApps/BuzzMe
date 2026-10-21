package com.buzzme.app.network.web

import android.content.Context
import com.buzzme.app.crypto.CryptoUtils
import com.buzzme.app.data.repository.DeviceRepository
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.interfaces.ECPublicKey
import java.util.Locale

class WebPushValidationTest {

    private fun validateEndpointCanonical(endpoint: String): URI {
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

    @Test
    fun testValidApplePushEndpoints() {
        val valid1 = "https://web.push.apple.com/QNAD9.../token"
        val valid2 = "https://push.apple.com/token"
        val valid3 = "https://api.push.apple.com:443/3/device/token"

        assertEquals("web.push.apple.com", validateEndpointCanonical(valid1).host)
        assertEquals("push.apple.com", validateEndpointCanonical(valid2).host)
        assertEquals("api.push.apple.com", validateEndpointCanonical(valid3).host)
    }

    @Test
    fun testRejectNonHttps() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEndpointCanonical("http://web.push.apple.com/token")
        }
    }

    @Test
    fun testRejectNonAppleHost() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEndpointCanonical("https://attacker.com/token")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateEndpointCanonical("https://fakeapple.push.apple.com.evil.com/token")
        }
    }

    @Test
    fun testRejectUserInfoAndInvalidPort() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEndpointCanonical("https://admin:pass@web.push.apple.com/token")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateEndpointCanonical("https://web.push.apple.com:8080/token")
        }
    }

    @Test
    fun testRejectTrailingDotAndNonAscii() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEndpointCanonical("https://web.push.apple.com./token")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateEndpointCanonical("https://web.push.appl\u00E9.com/token")
        }
    }

    @Test
    fun testVapidJwtES256Generation() {
        val keyPair = CryptoUtils.generateEcKeyPair()
        val audience = "https://web.push.apple.com"

        val headerJson = """{"typ":"JWT","alg":"ES256"}"""
        val nowSec = System.currentTimeMillis() / 1000L
        val expSec = nowSec + 43200L
        val claimsJson = """{"aud":"$audience","exp":$expSec,"sub":"mailto:household@local.lan"}"""

        val headerEncoded = CryptoUtils.base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))
        val claimsEncoded = CryptoUtils.base64UrlEncode(claimsJson.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$headerEncoded.$claimsEncoded".toByteArray(StandardCharsets.UTF_8)

        val signer = java.security.Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(signingInput)
        val derSignature = signer.sign()
        val joseSignature = CryptoUtils.derToJose(derSignature)

        // JOSE signature must be exactly 64 bytes (32 bytes R + 32 bytes S)
        assertEquals(64, joseSignature.size)

        val signatureEncoded = CryptoUtils.base64UrlEncode(joseSignature)
        val token = "$headerEncoded.$claimsEncoded.$signatureEncoded"

        val parts = token.split(".")
        assertEquals(3, parts.size)
        val decodedHeader = String(CryptoUtils.base64UrlDecode(parts[0]), StandardCharsets.UTF_8)
        assertTrue(decodedHeader.contains("ES256"))
    }
}
