package com.buzzme.app.crypto

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class HkdfTest {

    @Test
    fun testHkdfBasicExtractAndExpand() {
        val ikm = "input key material".toByteArray(StandardCharsets.UTF_8)
        val salt = "salt12345678".toByteArray(StandardCharsets.UTF_8)
        val info = "info-label".toByteArray(StandardCharsets.UTF_8)

        val derivedKey1 = Hkdf.deriveKey(salt, ikm, info, 32)
        assertEquals(32, derivedKey1.size)

        // Derivation must be deterministic
        val derivedKey2 = Hkdf.deriveKey(salt, ikm, info, 32)
        assertArrayEquals(derivedKey1, derivedKey2)

        // Different info must produce different key
        val derivedKeyDiffInfo = Hkdf.deriveKey(salt, ikm, "different-info".toByteArray(StandardCharsets.UTF_8), 32)
        assertFalse(derivedKey1.contentEquals(derivedKeyDiffInfo))
    }

    @Test
    fun testHkdfNullSaltDefaultsToZeroes() {
        val ikm = "secret-key-material".toByteArray(StandardCharsets.UTF_8)
        val info = "BuzzMe-test".toByteArray(StandardCharsets.UTF_8)

        val keyWithNullSalt = Hkdf.deriveKey(null, ikm, info, 32)
        val keyWithZeroSalt = Hkdf.deriveKey(ByteArray(32), ikm, info, 32)

        assertArrayEquals(keyWithNullSalt, keyWithZeroSalt)
    }

    @Test
    fun testHkdfVariableOutputLengths() {
        val ikm = "test-ikm".toByteArray(StandardCharsets.UTF_8)
        val salt = "test-salt".toByteArray(StandardCharsets.UTF_8)
        val info = "test-info".toByteArray(StandardCharsets.UTF_8)

        val key16 = Hkdf.deriveKey(salt, ikm, info, 16)
        val key64 = Hkdf.deriveKey(salt, ikm, info, 64)

        assertEquals(16, key16.size)
        assertEquals(64, key64.size)
        // First 16 bytes of 64-byte key match key16
        assertArrayEquals(key16, key64.copyOfRange(0, 16))
    }
}
