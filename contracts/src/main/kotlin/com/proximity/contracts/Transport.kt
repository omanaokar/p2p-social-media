package com.proximity.contracts

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Owned by the transport layer (Harshil). Moves opaque bytes between nearby
 * devices and never looks inside them.
 *
 * The event flows are hot: collectors subscribed before [start] see every
 * event.
 */
interface Transport {
    suspend fun start()
    suspend fun stop()

    /** Currently connected peers. Emits whenever the set or a signal changes. */
    val peers: Flow<List<TransportPeer>>

    /** True once the bytes were handed to the peer. */
    suspend fun send(peerId: String, bytes: ByteArray): Boolean

    val onReceive: Flow<ReceivedPayload>
    val onPeerConnected: Flow<TransportPeer>

    /** Emits the transport peer id of a peer that went away. */
    val onPeerLost: Flow<String>
}

data class TransportPeer(
    /**
     * Transport-level endpoint id. Not the same as a user id: the application
     * layer learns which user sits behind a peer during the sync handshake.
     */
    val id: String,
    val name: String,
    /** Higher is closer. Unit depends on the radio; treat it as relative. */
    val signalStrength: Int?,
    val connectedAt: Instant,
)

class ReceivedPayload(val peerId: String, val bytes: ByteArray)
