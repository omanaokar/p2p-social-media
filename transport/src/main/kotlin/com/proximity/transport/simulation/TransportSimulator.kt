package com.proximity.transport.simulation

import com.proximity.contracts.Transport
import com.proximity.transport.nearby.InProcessNearbyDriver
import com.proximity.transport.nearby.NearbyTransport
import com.proximity.transport.nearby.RadioMedium
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 2D physical plane simulation for testing proximity mesh behavior.
 * Models radio propagation, distance-based RSSI attenuation, and movement.
 *
 * Implements the Final Demo scenario: "4 phones in a room, then one person walks away".
 */
class TransportSimulator(
    val radioRangeMeters: Double = 15.0,
    private val pathLossExponent: Double = 2.5,
    private val referenceRssiAt1Meter: Double = -40.0,
) {
    data class SimulatedPosition(var x: Double, var y: Double)

    private val medium = RadioMedium()
    private val positions = ConcurrentHashMap<String, SimulatedPosition>()
    private val transports = ConcurrentHashMap<String, NearbyTransport>()
    private val drivers = ConcurrentHashMap<String, InProcessNearbyDriver>()

    /**
     * Creates and registers a new simulated phone node at position ([x], [y]).
     */
    fun createNode(nodeId: String, x: Double, y: Double): NearbyTransport {
        val driver = InProcessNearbyDriver(nodeId, "Device-$nodeId", medium)
        val transport = NearbyTransport(
            localEndpointName = "Device-$nodeId",
            driver = driver,
        )
        positions[nodeId] = SimulatedPosition(x, y)
        transports[nodeId] = transport
        drivers[nodeId] = driver

        recalculateMesh()
        return transport
    }

    /**
     * Moves a node to new coordinates ([x], [y]) and updates signal strengths / link connections.
     */
    fun moveNode(nodeId: String, newX: Double, newY: Double) {
        val pos = positions[nodeId] ?: return
        pos.x = newX
        pos.y = newY
        recalculateMesh()
    }

    /**
     * Moves a node far away past the radio range (e.g. 50 meters away),
     * simulating a user walking away from the room.
     */
    fun walkAway(nodeId: String) {
        val pos = positions[nodeId] ?: return
        moveNode(nodeId, pos.x + radioRangeMeters * 3, pos.y + radioRangeMeters * 3)
    }

    /**
     * Walks a node back into proximity range.
     */
    fun walkBack(nodeId: String, targetX: Double = 0.0, targetY: Double = 0.0) {
        moveNode(nodeId, targetX, targetY)
    }

    fun distance(nodeA: String, nodeB: String): Double? {
        val posA = positions[nodeA] ?: return null
        val posB = positions[nodeB] ?: return null
        val dx = posA.x - posB.x
        val dy = posA.y - posB.y
        return sqrt(dx * dx + dy * dy)
    }

    fun calculateRssi(distanceMeters: Double): Int {
        val d = maxOf(1.0, distanceMeters)
        val rssi = referenceRssiAt1Meter - (10 * pathLossExponent * log10(d))
        return Math.round(rssi).toInt()
    }

    private fun recalculateMesh() {
        val nodeIds = positions.keys.toList()
        for (i in 0 until nodeIds.size) {
            for (j in i + 1 until nodeIds.size) {
                val a = nodeIds[i]
                val b = nodeIds[j]
                val dist = distance(a, b) ?: continue

                if (dist <= radioRangeMeters) {
                    val rssi = calculateRssi(dist)
                    medium.updateSignal(a, b, rssi)
                } else {
                    // Out of range: drop the link
                    medium.disconnect(a, b)
                }
            }
        }
    }

    fun getTransport(nodeId: String): NearbyTransport? = transports[nodeId]

    fun removeNode(nodeId: String) {
        transports.remove(nodeId)?.let {
            positions.remove(nodeId)
            drivers.remove(nodeId)?.close()
        }
        recalculateMesh()
    }
}

