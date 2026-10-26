package com.proximity.transport

import com.proximity.transport.peer.PeerTable
import com.proximity.transport.peer.ProximityTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerTableTest {

    @Test
    fun `adding and removing peers updates table and flows`() = runBlocking {
        val table = PeerTable()

        val peer = table.addPeer("peer-1", "Alice", signalStrength = -55)
        assertEquals("peer-1", peer.id)
        assertEquals("Alice", peer.name)
        assertEquals(-55, peer.signalStrength)
        assertEquals(1, table.connectedCount())

        val currentPeers = table.peers.first()
        assertEquals(1, currentPeers.size)
        assertEquals("peer-1", currentPeers.first().id)

        val removed = table.removePeer("peer-1")
        assertNotNull(removed)
        assertEquals(0, table.connectedCount())
        assertEquals(0, table.peers.first().size)
    }

    @Test
    fun `signal strength smoothing calculates EWMA correctly`() {
        val table = PeerTable(smoothingAlpha = 0.5)
        table.addPeer("peer-1", "Bob", signalStrength = -60)

        // Initial smoothed signal is -60.0
        var entry = table.getPeer("peer-1")
        assertNotNull(entry)
        assertEquals(-60.0, entry.smoothedSignalStrength)

        // New signal arrives: -40.0
        // EWMA: 0.5 * (-40) + 0.5 * (-60) = -50.0
        table.updateSignal("peer-1", -40)
        entry = table.getPeer("peer-1")
        assertNotNull(entry)
        assertEquals(-50.0, entry.smoothedSignalStrength)
        assertEquals(-50, entry.effectiveSignalStrength)
    }

    @Test
    fun `proximity tiers correctly categorize RSSI ranges`() {
        assertEquals(ProximityTier.IMMEDIATE, ProximityTier.fromRssi(-45))
        assertEquals(ProximityTier.NEAR, ProximityTier.fromRssi(-68))
        assertEquals(ProximityTier.FAR, ProximityTier.fromRssi(-85))
        assertEquals(ProximityTier.UNKNOWN, ProximityTier.fromRssi(null))
    }

    @Test
    fun `tracks bytes sent and received per peer`() {
        val table = PeerTable()
        table.addPeer("peer-1", "Charlie")

        table.recordBytesSent("peer-1", 1024)
        table.recordBytesReceived("peer-1", 2048)

        val peer = table.getPeer("peer-1")
        assertNotNull(peer)
        assertEquals(1024L, peer.bytesSent)
        assertEquals(2048L, peer.bytesReceived)
    }
}

