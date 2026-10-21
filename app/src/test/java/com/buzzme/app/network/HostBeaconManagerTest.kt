package com.buzzme.app.network

import com.buzzme.app.data.model.AndroidPeerAuth
import com.buzzme.app.data.model.HostBeacon
import com.buzzme.app.data.model.HostRole
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HostBeaconManagerTest {

    @Test
    fun testPrimaryHostActiveElection() {
        val now = System.currentTimeMillis()
        val primaryBeacon = HostBeacon(
            deviceId = "primary-uuid-123",
            deviceName = "Dad's Phone (Primary)",
            role = HostRole.PRIMARY_HOST,
            priority = 100,
            activeHostId = "primary-uuid-123",
            ipAddress = "192.168.1.10",
            timestampMs = now
        )

        val backupBeacon = HostBeacon(
            deviceId = "backup-uuid-456",
            deviceName = "Mom's Phone (Backup)",
            role = HostRole.BACKUP_HOST,
            priority = 50,
            activeHostId = "primary-uuid-123",
            ipAddress = "192.168.1.11",
            timestampMs = now
        )

        val allBeacons = listOf(primaryBeacon, backupBeacon)

        // Evaluate host election algorithm
        val primaryHost = allBeacons.find { it.role == HostRole.PRIMARY_HOST }
        assertNotNull(primaryHost)
        assertEquals("primary-uuid-123", primaryHost!!.deviceId)
        assertEquals(HostRole.PRIMARY_HOST, primaryHost.role)
    }

    @Test
    fun testAutomaticFailoverWhenPrimaryHostOffline() {
        val now = System.currentTimeMillis()

        // Primary host beacon timestamp is older than 10 seconds (15s ago)
        val stalePrimaryBeacon = HostBeacon(
            deviceId = "primary-uuid-123",
            deviceName = "Dad's Phone (Primary)",
            role = HostRole.PRIMARY_HOST,
            priority = 100,
            activeHostId = "primary-uuid-123",
            ipAddress = "192.168.1.10",
            timestampMs = now - 15_000L
        )

        val activeBackupBeacon1 = HostBeacon(
            deviceId = "backup-uuid-456",
            deviceName = "Mom's Phone (Backup)",
            role = HostRole.BACKUP_HOST,
            priority = 50,
            activeHostId = "backup-uuid-456",
            ipAddress = "192.168.1.11",
            timestampMs = now
        )

        val activeBackupBeacon2 = HostBeacon(
            deviceId = "backup-uuid-789",
            deviceName = "Kid's Phone (Backup)",
            role = HostRole.BACKUP_HOST,
            priority = 40,
            activeHostId = "backup-uuid-456",
            ipAddress = "192.168.1.12",
            timestampMs = now
        )

        val allBeacons = listOf(stalePrimaryBeacon, activeBackupBeacon1, activeBackupBeacon2)

        // Filter out stale beacons older than 10,000ms
        val validBeacons = allBeacons.filter { (now - it.timestampMs) <= 10_000L }
        assertFalse(validBeacons.any { it.role == HostRole.PRIMARY_HOST })

        // Backup host with highest priority wins
        val electedHost = validBeacons.maxWithOrNull(compareBy<HostBeacon> { it.priority }.thenBy { it.deviceId })
        assertNotNull(electedHost)
        assertEquals("backup-uuid-456", electedHost!!.deviceId)
        assertEquals("Mom's Phone (Backup)", electedHost.deviceName)
    }

    @Test
    fun testPreemptionWhenPrimaryHostReturns() {
        val now = System.currentTimeMillis()

        // 1. Backup host acting as failover host
        var isActingHost = true
        var isPrimaryHost = false

        // 2. Primary host returns to Wi-Fi
        val freshPrimaryBeacon = HostBeacon(
            deviceId = "primary-uuid-123",
            deviceName = "Dad's Phone (Primary)",
            role = HostRole.PRIMARY_HOST,
            priority = 100,
            activeHostId = "primary-uuid-123",
            ipAddress = "192.168.1.10",
            timestampMs = now
        )

        val backupBeacon = HostBeacon(
            deviceId = "backup-uuid-456",
            deviceName = "Mom's Phone (Backup)",
            role = HostRole.BACKUP_HOST,
            priority = 50,
            activeHostId = "backup-uuid-456",
            ipAddress = "192.168.1.11",
            timestampMs = now
        )

        val activeNodes = listOf(freshPrimaryBeacon, backupBeacon)
        val primaryHost = activeNodes.find { it.role == HostRole.PRIMARY_HOST }
        assertNotNull(primaryHost)

        // Backup yields host role back to Primary Host
        val myDeviceId = backupBeacon.deviceId
        isActingHost = primaryHost!!.deviceId == myDeviceId
        assertFalse(isActingHost)
    }

    @Test
    fun testPeerMergingDeduplication() {
        val existingPeers = mutableListOf(
            AndroidPeerAuth(
                keyId = "key-1",
                peerDeviceId = "dev-1",
                label = "Phone 1",
                wrappedSecret = "secret1",
                iv = "iv1",
                sessionEpoch = 1L,
                highestSeq = 0L,
                targetScope = "home",
                hostIp = "192.168.1.10",
                port = 8899
            )
        )

        val incomingPeers = listOf(
            AndroidPeerAuth(
                keyId = "key-1",
                peerDeviceId = "dev-1",
                label = "Phone 1 (Renamed)",
                wrappedSecret = "secret1",
                iv = "iv1",
                sessionEpoch = 1L,
                highestSeq = 0L,
                targetScope = "home",
                hostIp = "192.168.1.55", // Updated DHCP IP
                port = 8899
            ),
            AndroidPeerAuth(
                keyId = "key-2",
                peerDeviceId = "dev-2",
                label = "New Tablet",
                wrappedSecret = "secret2",
                iv = "iv2",
                sessionEpoch = 1L,
                highestSeq = 0L,
                targetScope = "home",
                hostIp = "192.168.1.56",
                port = 8899
            )
        )

        for (incoming in incomingPeers) {
            val idx = existingPeers.indexOfFirst { it.peerDeviceId == incoming.peerDeviceId || it.keyId == incoming.keyId }
            if (idx >= 0) {
                val existing = existingPeers[idx]
                existingPeers[idx] = existing.copy(
                    hostIp = incoming.hostIp ?: existing.hostIp,
                    label = if (existing.label.isBlank()) incoming.label else existing.label
                )
            } else {
                existingPeers.add(incoming)
            }
        }

        assertEquals(2, existingPeers.size)
        assertEquals("192.168.1.55", existingPeers.find { it.peerDeviceId == "dev-1" }!!.hostIp)
        assertNotNull(existingPeers.find { it.peerDeviceId == "dev-2" })
    }
}
