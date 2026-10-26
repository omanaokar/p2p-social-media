package com.proximity.transport

import com.proximity.contracts.ReceivedPayload
import com.proximity.transport.nearby.InProcessNearbyDriver
import com.proximity.transport.nearby.NearbyTransport
import com.proximity.transport.nearby.RadioMedium
import com.proximity.transport.simulation.TransportSimulator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NearbyTransportTest {

    @Test
    fun `two nodes discover, connect, and exchange bytes`() = runBlocking {
        val medium = RadioMedium()

        val driverA = InProcessNearbyDriver("id-a", "PhoneA", medium)
        val driverB = InProcessNearbyDriver("id-b", "PhoneB", medium)

        val transportA = NearbyTransport("PhoneA", driver = driverA)
        val transportB = NearbyTransport("PhoneB", driver = driverB)

        transportA.start()
        transportB.start()

        // Wait for connection to establish on both sides
        val peersA = withTimeout(5000) {
            transportA.peers.first { list -> list.any { it.id == "id-b" } }
        }
        assertTrue(peersA.any { it.id == "id-b" })

        val peersB = withTimeout(5000) {
            transportB.peers.first { list -> list.any { it.id == "id-a" } }
        }
        assertTrue(peersB.any { it.id == "id-a" })

        val receivedDeferred = async { transportB.onReceive.first() }

        // Send opaque bytes from A to B
        val message = "Hello from phone A to phone B!".toByteArray(Charsets.UTF_8)
        val sent = transportA.send("id-b", message)
        assertTrue(sent)

        val receivedAtB = withTimeout(5000) {
            receivedDeferred.await()
        }

        assertEquals("id-a", receivedAtB.peerId)
        assertTrue(message.contentEquals(receivedAtB.bytes))

        transportA.stop()
        transportB.stop()
    }

    @Test
    fun `large payload exceeding chunk size is transparently transferred and reassembled`() = runBlocking {
        val medium = RadioMedium()

        val driverA = InProcessNearbyDriver("node-1", "A-Node", medium)
        val driverB = InProcessNearbyDriver("node-2", "B-Node", medium)

        val config = TransportConfig(chunkSizeBytes = 8 * 1024)
        val transportA = NearbyTransport("A-Node", config = config, driver = driverA)
        val transportB = NearbyTransport("B-Node", config = config, driver = driverB)

        transportA.start()
        transportB.start()

        withTimeout(5000) {
            transportA.peers.first { list -> list.any { it.id == "node-2" } }
        }

        val receivedDeferred = async { transportB.onReceive.first() }

        // 100 KB payload (split across ~13 chunks)
        val largeImageBytes = Random.nextBytes(100 * 1024)
        val sent = transportA.send("node-2", largeImageBytes)
        assertTrue(sent)

        val received = withTimeout(5000) {
            receivedDeferred.await()
        }

        assertEquals("node-1", received.peerId)
        assertEquals(largeImageBytes.size, received.bytes.size)
        assertTrue(largeImageBytes.contentEquals(received.bytes))

        transportA.stop()
        transportB.stop()
    }

    @Test
    fun `simulator models 4 phones in room and one person walking away`() = runBlocking {
        val sim = TransportSimulator(radioRangeMeters = 15.0)

        // 4 phones in a room (all within 5 meters of center)
        val tA = sim.createNode("phone-a", 0.0, 0.0)
        val tB = sim.createNode("phone-b", 2.0, 1.0)
        val tC = sim.createNode("phone-c", 1.0, 3.0)
        val tD = sim.createNode("phone-d", 3.0, 2.0)

        tA.start()
        tB.start()
        tC.start()
        tD.start()

        // Verify Phone A sees Phone D in range
        withTimeout(5000) {
            tA.peers.first { list -> list.any { it.id == "phone-d" } }
        }

        val lostDeferred = async { tA.onPeerLost.first { it == "phone-d" } }

        // Phone D walks away past the 15 meter radio boundary!
        sim.walkAway("phone-d")

        // Phone A detects phone D is lost
        val lostPeer = withTimeout(5000) {
            lostDeferred.await()
        }
        assertEquals("phone-d", lostPeer)

        // Verify tA's active peer list no longer includes phone-d
        val remaining = withTimeout(5000) {
            tA.peers.first { list -> list.none { it.id == "phone-d" } }
        }
        assertTrue(remaining.none { it.id == "phone-d" })

        tA.stop()
        tB.stop()
        tC.stop()
        tD.stop()
    }
}

