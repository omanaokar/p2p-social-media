package com.proximity.transport.peer

import com.proximity.contracts.TransportPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Categorized proximity tier based on calibrated Bluetooth / Wi-Fi RSSI values.
 */
enum class ProximityTier {
    IMMEDIATE, // Close proximity (< 2 meters, strong signal, RSSI >= -60 dBm)
    NEAR,      // Room proximity (2 - 6 meters, moderate signal, -75 dBm <= RSSI < -60 dBm)
    FAR,       // Edge of radio range (> 6 meters, weak signal, RSSI < -75 dBm)
    UNKNOWN;   // No signal strength reported by hardware

    companion object {
        fun fromRssi(rssi: Int?): ProximityTier = when {
            rssi == null -> UNKNOWN
            rssi >= -60 -> IMMEDIATE
            rssi >= -75 -> NEAR
            else -> FAR
        }
    }
}

/**
 * Detailed metadata maintained for a connected peer endpoint.
 */
data class PeerEntry(
    val id: String,
    val name: String,
    val connectedAt: Instant,
    var lastSeenAt: Instant = connectedAt,
    var rawSignalStrength: Int? = null,
    var smoothedSignalStrength: Double? = rawSignalStrength?.toDouble(),
    var bytesSent: Long = 0L,
    var bytesReceived: Long = 0L,
) {
    val proximityTier: ProximityTier get() = ProximityTier.fromRssi(effectiveSignalStrength)

    /**
     * Integer representation of signal strength for [TransportPeer], rounded from EWMA.
     */
    val effectiveSignalStrength: Int?
        get() = smoothedSignalStrength?.let { Math.round(it).toInt() } ?: rawSignalStrength

    fun toTransportPeer(): TransportPeer = TransportPeer(
        id = id,
        name = name,
        signalStrength = effectiveSignalStrength,
        connectedAt = connectedAt,
    )
}

/**
 * Thread-safe table of connected peers and radio signal histories.
 */
class PeerTable(
    private val smoothingAlpha: Double = 0.3,
) {
    private val entries = LinkedHashMap<String, PeerEntry>()

    private val _peers = MutableStateFlow<List<TransportPeer>>(emptyList())
    val peers: Flow<List<TransportPeer>> = _peers.asStateFlow()

    private val _onPeerConnected = MutableSharedFlow<TransportPeer>(replay = 1, extraBufferCapacity = 64)
    val onPeerConnected: Flow<TransportPeer> = _onPeerConnected.asSharedFlow()

    private val _onPeerLost = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)
    val onPeerLost: Flow<String> = _onPeerLost.asSharedFlow()

    @Synchronized
    fun addPeer(
        id: String,
        name: String,
        signalStrength: Int? = null,
        connectedAt: Instant = Instant.now(),
    ): TransportPeer {
        val entry = PeerEntry(
            id = id,
            name = name,
            connectedAt = connectedAt,
            rawSignalStrength = signalStrength,
            smoothedSignalStrength = signalStrength?.toDouble(),
        )
        entries[id] = entry
        val transportPeer = entry.toTransportPeer()
        _onPeerConnected.tryEmit(transportPeer)
        updatePeersFlow()
        return transportPeer
    }

    @Synchronized
    fun removePeer(id: String): PeerEntry? {
        val removed = entries.remove(id) ?: return null
        _onPeerLost.tryEmit(id)
        updatePeersFlow()
        return removed
    }

    @Synchronized
    fun updateSignal(id: String, signalStrength: Int, now: Instant = Instant.now()) {
        val entry = entries[id] ?: return
        entry.rawSignalStrength = signalStrength
        entry.lastSeenAt = now

        // Exponentially Weighted Moving Average (EWMA) smoothing: S_t = α * Y_t + (1 - α) * S_{t-1}
        val currentSmoothed = entry.smoothedSignalStrength
        entry.smoothedSignalStrength = if (currentSmoothed == null) {
            signalStrength.toDouble()
        } else {
            smoothingAlpha * signalStrength + (1.0 - smoothingAlpha) * currentSmoothed
        }
        updatePeersFlow()
    }

    @Synchronized
    fun recordBytesSent(id: String, count: Long) {
        entries[id]?.let { it.bytesSent += count }
    }

    @Synchronized
    fun recordBytesReceived(id: String, count: Long, now: Instant = Instant.now()) {
        entries[id]?.let {
            it.bytesReceived += count
            it.lastSeenAt = now
        }
    }

    @Synchronized
    fun getPeer(id: String): PeerEntry? = entries[id]?.copy()

    @Synchronized
    fun contains(id: String): Boolean = entries.containsKey(id)

    @Synchronized
    fun connectedCount(): Int = entries.size

    @Synchronized
    fun connectedPeerIds(): Set<String> = entries.keys.toSet()

    @Synchronized
    fun clear(): List<String> {
        val ids = entries.keys.toList()
        entries.clear()
        for (id in ids) {
            _onPeerLost.tryEmit(id)
        }
        updatePeersFlow()
        return ids
    }

    private fun updatePeersFlow() {
        _peers.value = entries.values.map { it.toTransportPeer() }
    }
}
