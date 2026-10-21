package com.buzzme.app.data.repository

import org.junit.Assert.*
import org.junit.Test

class DeviceNameValidationTest {

    @Test
    fun testDeviceNameValidationAndTrimming() {
        val rawInput = "   Mom's Phone   "
        val trimmed = rawInput.trim()
        assertEquals("Mom's Phone", trimmed)
        assertTrue(trimmed.isNotBlank())

        val emptyInput = "    "
        assertFalse(emptyInput.trim().isNotBlank())
    }

    @Test
    fun testDefaultDeviceNameFallbacks() {
        fun resolveDefaultName(customName: String?, fallbackModel: String): String {
            return customName?.trim()?.ifBlank { null }
                ?: fallbackModel.trim().ifBlank { null }
                ?: "My Phone"
        }

        assertEquals("Mom's Phone", resolveDefaultName("Mom's Phone", "Pixel 10 Pro"))
        assertEquals("Pixel 10 Pro", resolveDefaultName(null, "Pixel 10 Pro"))
        assertEquals("Pixel 10 Pro", resolveDefaultName("   ", "Pixel 10 Pro"))
        assertEquals("My Phone", resolveDefaultName("", ""))
    }
}
