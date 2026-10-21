package com.buzzme.app.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AlertEngineTest {

    @Before
    fun setUp() {
        AlertEngine.resetEscalation()
    }

    @Test
    fun testVibrationPatternsMatchUserSpecification() {
        // Level 1: 3 times for 2 seconds
        assertArrayEquals(
            longArrayOf(0, 2000, 500, 2000, 500, 2000),
            AlertEngine.PATTERN_LEVEL_1
        )
        assertArrayEquals(
            intArrayOf(0, 255, 0, 255, 0, 255),
            AlertEngine.AMPLITUDES_LEVEL_1
        )

        // Level 2: 3 times for 5 seconds
        assertArrayEquals(
            longArrayOf(0, 5000, 500, 5000, 500, 5000),
            AlertEngine.PATTERN_LEVEL_2
        )
        assertArrayEquals(
            intArrayOf(0, 255, 0, 255, 0, 255),
            AlertEngine.AMPLITUDES_LEVEL_2
        )

        // Level 3: 20 seconds straight
        assertArrayEquals(
            longArrayOf(0, 20000),
            AlertEngine.PATTERN_LEVEL_3
        )
        assertArrayEquals(
            intArrayOf(0, 255),
            AlertEngine.AMPLITUDES_LEVEL_3
        )
    }

    @Test
    fun testEscalationSequencePerPerson() {
        val sender = "mom-device"
        val baseTime = 1000000L

        // 1st buzz: level 1 (3 times for 2s)
        val count1 = AlertEngine.recordBuzz(sender, nowMs = baseTime)
        assertEquals(1, count1)
        assertEquals(1, AlertEngine.getEscalationLevel(sender, nowMs = baseTime))

        // 2nd buzz: level 2 (3 times for 5s)
        val count2 = AlertEngine.recordBuzz(sender, nowMs = baseTime + 30000)
        assertEquals(2, count2)
        assertEquals(2, AlertEngine.getEscalationLevel(sender, nowMs = baseTime + 30000))

        // 3rd buzz: level 3 (20s straight)
        val count3 = AlertEngine.recordBuzz(sender, nowMs = baseTime + 60000)
        assertEquals(3, count3)
        assertEquals(3, AlertEngine.getEscalationLevel(sender, nowMs = baseTime + 60000))

        // Subsequent buzzes remain at level 3
        val count4 = AlertEngine.recordBuzz(sender, nowMs = baseTime + 90000)
        assertEquals(4, count4)
        assertEquals(3, AlertEngine.getEscalationLevel(sender, nowMs = baseTime + 90000))
    }

    @Test
    fun testPerSenderIsolation() {
        val alice = "alice"
        val bob = "bob"
        val t0 = 2000000L

        // Alice buzzes twice -> level 2
        AlertEngine.recordBuzz(alice, t0)
        AlertEngine.recordBuzz(alice, t0 + 5000)
        assertEquals(2, AlertEngine.getEscalationLevel(alice, t0 + 5000))

        // Bob buzzes once -> level 1
        AlertEngine.recordBuzz(bob, t0 + 10000)
        assertEquals(1, AlertEngine.getEscalationLevel(bob, t0 + 10000))

        // Alice is still at level 2
        assertEquals(2, AlertEngine.getEscalationLevel(alice, t0 + 10000))
    }

    @Test
    fun testEscalationTimeoutResetAfter15Minutes() {
        val sender = "dad-device"
        val t0 = 5000000L

        // Buzz 3 times to reach level 3
        AlertEngine.recordBuzz(sender, t0)
        AlertEngine.recordBuzz(sender, t0 + 1000)
        AlertEngine.recordBuzz(sender, t0 + 2000)
        assertEquals(3, AlertEngine.getEscalationLevel(sender, t0 + 2000))

        // 14 minutes later: still level 3
        val t14m = t0 + 2000 + (14 * 60 * 1000L)
        assertEquals(3, AlertEngine.getEscalationLevel(sender, t14m))

        // 16 minutes later (> 15 min reset timeout): reset to level 1
        val t16m = t0 + 2000 + (16 * 60 * 1000L)
        assertEquals(1, AlertEngine.getEscalationLevel(sender, t16m))

        // Recording a new buzz after timeout starts at count 1
        val newCount = AlertEngine.recordBuzz(sender, t16m)
        assertEquals(1, newCount)
        assertEquals(1, AlertEngine.getEscalationLevel(sender, t16m))
    }

    @Test
    fun testExplicitReset() {
        val sender = "child-phone"
        val t0 = 8000000L

        AlertEngine.recordBuzz(sender, t0)
        AlertEngine.recordBuzz(sender, t0 + 1000)
        assertEquals(2, AlertEngine.getEscalationLevel(sender, t0 + 1000))

        AlertEngine.resetEscalation(sender)
        assertEquals(1, AlertEngine.getEscalationLevel(sender, t0 + 1000))

        val countAfterReset = AlertEngine.recordBuzz(sender, t0 + 2000)
        assertEquals(1, countAfterReset)
    }
}
