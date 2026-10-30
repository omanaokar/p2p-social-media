package com.proximity.core.sync

import com.proximity.contracts.TransportPeer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant

data class PeerInfo(
    val transportId: String,
    val name: String,
    val signalStrength: Int?,
    val connectedAt: Instant,
    /** Learned from the peer's signed profile in its hello. */
    val userId: String? = null,
)

/** Directly connected peers and which user sits behind each transport id. */
class PeerTable {
    private val peers = LinkedHashMap<String, PeerInfo>()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val changes: Flow<Unit> = _changes

    val all: List<PeerInfo> get() = synchronized(this) { peers.values.toList() }

    val nearbyUserIds: Set<String> get() = synchronized(this) { peers.values.mapNotNullTo(mutableSetOf()) { it.userId } }

    fun byTransportId(id: String): PeerInfo? = synchronized(this) { peers[id] }

    fun transportIdFor(userId: String): String? = synchronized(this) { peers.values.firstOrNull { it.userId == userId }?.transportId }

    fun upsert(peer: TransportPeer): PeerInfo = update {
        val existing = peers[peer.id]
        val info = existing?.copy(name = peer.name, signalStrength = peer.signalStrength ?: existing.signalStrength)
            ?: PeerInfo(peer.id, peer.name, peer.signalStrength, peer.connectedAt)
        peers[peer.id] = info
        info
    }

    /** Applies the transport's authoritative peer list. */
    fun sync(list: List<TransportPeer>) = update {
        val ids = list.mapTo(mutableSetOf()) { it.id }
        peers.keys.retainAll(ids)
        for (peer in list) {
            val existing = peers[peer.id]
            peers[peer.id] = existing?.copy(name = peer.name, signalStrength = peer.signalStrength ?: existing.signalStrength)
                ?: PeerInfo(peer.id, peer.name, peer.signalStrength, peer.connectedAt)
        }
    }

    fun setUser(transportId: String, userId: String) = update {
        peers[transportId]?.let { peers[transportId] = it.copy(userId = userId) }
    }

    fun remove(transportId: String) = update { peers.remove(transportId) }

    private fun <T> update(body: () -> T): T {
        val result = synchronized(this, body)
        _changes.tryEmit(Unit)
        return result
    }
}
