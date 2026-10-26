package com.proximity.transport.connection

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

enum class PeerConnectionState {
    DISCONNECTED,
    DISCOVERED,
    CONNECTING_OUTGOING,
    CONNECTING_INCOMING,
    CONNECTED,
    BACKOFF,
}

data class PeerStateInfo(
    val endpointId: String,
    val endpointName: String,
    var state: PeerConnectionState,
    var reconnectAttempts: Int = 0,
    var nextAllowedConnectAt: Instant = Instant.EPOCH,
    var lastStateChange: Instant = Instant.now(),
)

/**
 * Manages connection lifecycle, simultaneous link caps, collision resolution,
 * and auto-reconnection with exponential backoff.
 */
class ConnectionLifecycleManager(
    private val localEndpointName: String,
    private val maxSimultaneousLinks: Int = 6,
    private val maxReconnectAttempts: Int = 3,
    private val initialBackoff: Duration = Duration.ofMillis(1000),
    private val maxBackoff: Duration = Duration.ofSeconds(16),
) {
    private val peers = mutableMapOf<String, PeerStateInfo>()

    /**
     * Called when an endpoint is discovered by scanning.
     * Returns true if we should actively initiate an outgoing connection to this endpoint.
     */
    @Synchronized
    fun onEndpointDiscovered(endpointId: String, endpointName: String, now: Instant = Instant.now()): Boolean {
        val info = peers.getOrPut(endpointId) {
            PeerStateInfo(
                endpointId = endpointId,
                endpointName = endpointName,
                state = PeerConnectionState.DISCOVERED,
                lastStateChange = now,
            )
        }

        // Already connected or in-flight
        if (info.state == PeerConnectionState.CONNECTED ||
            info.state == PeerConnectionState.CONNECTING_OUTGOING ||
            info.state == PeerConnectionState.CONNECTING_INCOMING
        ) {
            return false
        }

        // Check if currently under backoff
        if (info.state == PeerConnectionState.BACKOFF && now.isBefore(info.nextAllowedConnectAt)) {
            return false
        }

        // Check simultaneous connection cap
        if (currentConnectedCount() >= maxSimultaneousLinks) {
            return false
        }

        // Deterministic collision resolution:
        // When both nodes discover each other, only the node with lexicographically smaller
        // endpoint name initiates. The other node listens and accepts.
        val shouldInitiate = shouldInitiateConnection(localEndpointName, endpointName)
        if (shouldInitiate) {
            info.state = PeerConnectionState.CONNECTING_OUTGOING
            info.lastStateChange = now
            return true
        } else {
            info.state = PeerConnectionState.DISCOVERED
            info.lastStateChange = now
            return false
        }
    }

    /**
     * Called when an incoming connection request is received from a peer.
     * Returns true if the connection should be accepted, false if rejected.
     */
    @Synchronized
    fun onIncomingConnection(endpointId: String, endpointName: String, now: Instant = Instant.now()): Boolean {
        // Reject if cap is reached
        if (currentConnectedCount() >= maxSimultaneousLinks) {
            return false
        }

        val info = peers.getOrPut(endpointId) {
            PeerStateInfo(
                endpointId = endpointId,
                endpointName = endpointName,
                state = PeerConnectionState.CONNECTING_INCOMING,
                lastStateChange = now,
            )
        }

        info.state = PeerConnectionState.CONNECTING_INCOMING
        info.lastStateChange = now
        return true
    }

    /**
     * Called when a connection handshake succeeds and the link is established.
     */
    @Synchronized
    fun onConnected(endpointId: String, endpointName: String, now: Instant = Instant.now()) {
        val info = peers.getOrPut(endpointId) {
            PeerStateInfo(
                endpointId = endpointId,
                endpointName = endpointName,
                state = PeerConnectionState.CONNECTED,
                lastStateChange = now,
            )
        }
        info.state = PeerConnectionState.CONNECTED
        info.reconnectAttempts = 0
        info.lastStateChange = now
    }

    /**
     * Called when a peer connection is lost or disconnected.
     * Returns the backoff duration if reconnection should be attempted, or null if giving up.
     */
    @Synchronized
    fun onDisconnected(
        endpointId: String,
        intentional: Boolean = false,
        now: Instant = Instant.now(),
    ): Duration? {
        val info = peers[endpointId] ?: return null

        if (intentional || info.reconnectAttempts >= maxReconnectAttempts) {
            info.state = PeerConnectionState.DISCONNECTED
            info.lastStateChange = now
            return null
        }

        // Calculate exponential backoff with jitter: delay = min(initial * 2^attempts, max) + jitter
        val baseDelayMs = initialBackoff.toMillis() * (1 shl minOf(info.reconnectAttempts, 10))
        val cappedDelayMs = minOf(baseDelayMs, maxBackoff.toMillis())
        val jitterMs = Random.nextLong(0, minOf(500L, cappedDelayMs / 4 + 1))
        val totalDelay = Duration.ofMillis(cappedDelayMs + jitterMs)

        info.reconnectAttempts++
        info.state = PeerConnectionState.BACKOFF
        info.nextAllowedConnectAt = now.plus(totalDelay)
        info.lastStateChange = now

        return totalDelay
    }

    @Synchronized
    fun getState(endpointId: String): PeerConnectionState =
        peers[endpointId]?.state ?: PeerConnectionState.DISCONNECTED

    @Synchronized
    fun currentConnectedCount(): Int =
        peers.values.count { it.state == PeerConnectionState.CONNECTED }

    @Synchronized
    fun canAcceptMoreLinks(): Boolean = currentConnectedCount() < maxSimultaneousLinks

    @Synchronized
    fun reset() {
        peers.clear()
    }

    companion object {
        /**
         * Deterministic tie-breaker for connection collisions:
         * The lower endpoint name initiates; the higher endpoint name awaits incoming connection.
         */
        fun shouldInitiateConnection(localName: String, remoteName: String): Boolean {
            return localName < remoteName
        }
    }
}

