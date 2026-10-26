package com.buzzme.app.ui

import org.junit.Assert.*
import org.junit.Test

class HostQrPayloadTest {

    @Test
    fun testToDeepLinkUrlAndParse() {
        val original = MainViewModel.HostQrPayload(
            hostIp = "192.168.1.155",
            port = 8900,
            pairingId = "testPairingId123",
            nonce = "testNonce456",
            hostPubKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7XYZ",
            hostDeviceId = "b845df71-1234-5678-9abc-def012345678",
            hostDeviceName = "Living Room Hub"
        )

        val url = original.toDeepLinkUrl()
        assertTrue("URL must start with buzzme://pair", url.startsWith("buzzme://pair"))
        assertTrue("URL must contain host IP", url.contains("ip=192.168.1.155"))
        assertTrue("URL must contain port", url.contains("port=8900"))
        assertTrue("URL must contain pairingId", url.contains("pid=testPairingId123"))

        val parsed = MainViewModel.HostQrPayload.parse(url)
        assertEquals(original.hostIp, parsed.hostIp)
        assertEquals(original.port, parsed.port)
        assertEquals(original.pairingId, parsed.pairingId)
        assertEquals(original.nonce, parsed.nonce)
        assertEquals(original.hostPubKey, parsed.hostPubKey)
        assertEquals(original.hostDeviceId, parsed.hostDeviceId)
        assertEquals(original.hostDeviceName, parsed.hostDeviceName)
    }

    @Test
    fun testAlternateDeepLinkUrl() {
        val original = MainViewModel.HostQrPayload(
            hostIp = "10.0.0.5",
            port = 8900,
            pairingId = "pairIdABC",
            nonce = "nonceDEF",
            hostPubKey = "pubKeyGHI",
            hostDeviceId = "11111111-2222-3333-4444-555555555555",
            hostDeviceName = "Mum's Phone"
        )

        val altUrl = original.toAlternateDeepLinkUrl()
        assertTrue("URL must start with https://buzzme.app/pair", altUrl.startsWith("https://buzzme.app/pair"))

        val parsed = MainViewModel.HostQrPayload.parse(altUrl)
        assertEquals(original.hostIp, parsed.hostIp)
        assertEquals(original.pairingId, parsed.pairingId)
        assertEquals(original.hostDeviceName, parsed.hostDeviceName)
    }

    @Test
    fun testParseLegacyJsonPayload() {
        val jsonString = """
            {
                "hostIp": "192.168.0.22",
                "port": 8900,
                "pairingId": "legacyPairingId",
                "nonce": "legacyNonce",
                "hostPubKey": "legacyKeyBytes",
                "hostDeviceId": "22222222-3333-4444-5555-666666666666",
                "hostDeviceName": "Legacy Host"
            }
        """.trimIndent()

        val parsed = MainViewModel.HostQrPayload.parse(jsonString)
        assertEquals("192.168.0.22", parsed.hostIp)
        assertEquals(8900, parsed.port)
        assertEquals("legacyPairingId", parsed.pairingId)
        assertEquals("legacyNonce", parsed.nonce)
        assertEquals("legacyKeyBytes", parsed.hostPubKey)
        assertEquals("22222222-3333-4444-5555-666666666666", parsed.hostDeviceId)
        assertEquals("Legacy Host", parsed.hostDeviceName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseMissingRequiredParameterThrows() {
        val invalidUrl = "https://buzzme.app/pair?port=8900&pid=123"
        MainViewModel.HostQrPayload.parse(invalidUrl)
    }

    @Test
    fun testBase64WithPlusAndSpaceRecovery() {
        val originalPubKey = "BDg4rPRtUNFXmEu3soO8Bpxd8gkNcKjSpJ3SFHxrl8yWikvOuRUIpAFgP20uz/1hm2TP5uxgryipv18t79raAQk="
        val original = MainViewModel.HostQrPayload(
            hostIp = "192.168.0.44",
            port = 8900,
            pairingId = "pair+123",
            nonce = "nonce+456",
            hostPubKey = originalPubKey,
            hostDeviceId = "b3cbb3ce-6a83-44c2-beb2-5b3cc7a96c44",
            hostDeviceName = "Host Phone"
        )

        val url = original.toDeepLinkUrl()
        assertTrue("URL-encoded public key should contain %2B instead of raw plus", url.contains("%2B"))

        // Test normal parsing from URL
        val parsed = MainViewModel.HostQrPayload.parse(url)
        assertEquals(originalPubKey, parsed.hostPubKey)

        // Test parsing where a 3rd party scanner converted '+' to ' ' in raw URL
        val urlWithSpaces = "https://buzzme.app/pair?ip=192.168.0.44&port=8900&pid=pair+123&nonce=nonce+456&pub=" +
                originalPubKey.replace('+', ' ') + "&id=b3cbb3ce-6a83-44c2-beb2-5b3cc7a96c44&name=Host"
        val parsedWithSpaces = MainViewModel.HostQrPayload.parse(urlWithSpaces)
        assertEquals(originalPubKey, parsedWithSpaces.hostPubKey)

        // Verify CryptoUtils decodes it without 'Illegal base64 character 20' exception
        val decodedBytes = com.buzzme.app.crypto.CryptoUtils.base64UrlDecode(parsedWithSpaces.hostPubKey)
        assertNotNull(decodedBytes)
        assertEquals(65, decodedBytes.size)
    }
}
