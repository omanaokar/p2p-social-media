package com.proximity.transport

import com.proximity.transport.connection.ConnectionLifecycleManager
import com.proximity.transport.connection.PeerConnectionState
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionLifecycleTest {

    @Test
    fun `enforces max simultaneous links cap`() {
        val manager = ConnectionLifecycleManager(
            localEndpointName = "local-node",
            maxSimultaneousLinks = 3,
        )

        assertTrue(manager.onIncomingConnection("p1", "node-1"))
        manager.onConnected("p1", "node-1")

        assertTrue(manager.onIncomingConnection("p2", "node-2"))
        manager.onConnected("p2", "node-2")

        assertTrue(manager.onIncomingConnection("p3", "node-3"))
        manager.onConnected("p3", "node-3")

        assertEquals(3, manager.currentConnectedCount())
        assertFalse(manager.canAcceptMoreLinks())

        // 4th link should be rejected
        assertFalse(manager.onIncomingConnection("p4", "node-4"))
        assertFalse(manager.onEndpointDiscovered("p5", "node-5"))
    }

    @Test
    fun `collision resolution avoids simultaneous connection race conditions`() {
        // Node A = "Alice", Node B = "Bob"
        // "Alice" < "Bob"
        // Alice should initiate when Bob is discovered
        val managerAlice = ConnectionLifecycleManager(localEndpointName = "Alice")
        assertTrue(managerAlice.onEndpointDiscovered("b", "Bob"))
        assertEquals(PeerConnectionState.CONNECTING_OUTGOING, managerAlice.getState("b"))

        // Bob should NOT initiate when Alice is discovered; Bob waits for Alice
        val managerBob = ConnectionLifecycleManager(localEndpointName = "Bob")
        assertFalse(managerBob.onEndpointDiscovered("a", "Alice"))
        assertEquals(PeerConnectionState.DISCOVERED, managerBob.getState("a"))
    }

    @Test
    fun `reconnect with exponential backoff on unexpected drop`() {
        val manager = ConnectionLifecycleManager(
            localEndpointName = "node-1",
            maxReconnectAttempts = 2,
            initialBackoff = Duration.ofSeconds(1),
            maxBackoff = Duration.ofSeconds(10),
        )

        manager.onConnected("peer-x", "node-x")
        val now = Instant.parse("2026-10-26T12:00:00Z")

        // First unexpected drop: backoff >= 1s
        val backoff1 = manager.onDisconnected("peer-x", intentional = false, now = now)
        assertNotNull(backoff1)
        assertTrue(backoff1.toMillis() >= 1000)
        assertEquals(PeerConnectionState.BACKOFF, manager.getState("peer-x"))

        // While backoff is active, discovery does not immediately reconnect
        assertFalse(manager.onEndpointDiscovered("peer-x", "node-x", now = now.plusMillis(500)))

        // Second unexpected drop
        val backoff2 = manager.onDisconnected("peer-x", intentional = false, now = now.plusSeconds(3))
        assertNotNull(backoff2)

        // Third drop exceeds maxReconnectAttempts (2) -> gives up, no backoff
        val backoff3 = manager.onDisconnected("peer-x", intentional = false, now = now.plusSeconds(10))
        assertNull(backoff3)
        assertEquals(PeerConnectionState.DISCONNECTED, manager.getState("peer-x"))
    }
}

