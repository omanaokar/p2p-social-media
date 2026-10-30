package com.proximity.transport.mock

import com.proximity.contracts.ReceivedPayload
import com.proximity.contracts.Transport
import com.proximity.contracts.TransportPeer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mock transport delivered in Week 1 so Om (App-Core), Neil (Ranking), and
 * Sarthak (UI) can develop and test without physical devices.
 *
 * Implements [Transport] and provides programmable methods to simulate peers
 * walking in and out of range, signal strength fluctuations, and packet transmission.
 */
class MockTransport(
    val localId: String = "local-device",
) : Transport {

    @Volatile
    var isRunning: Boolean = false
        private set

    var failSends: Boolean = false
    var sendLatencyMs: Long = 0L

    private val connectedPeers = LinkedHashMap<String, TransportPeer>()
    private val sentLog = CopyOnWriteArrayList<Pair<String, ByteArray>>()

    private val _peers = MutableStateFlow<List<TransportPeer>>(emptyList())
    override val peers: Flow<List<TransportPeer>> = _peers.asStateFlow()

    private val _onReceive = MutableSharedFlow<ReceivedPayload>(extraBufferCapacity = 4096)
    override val onReceive: Flow<ReceivedPayload> = _onReceive.asSharedFlow()

    private val _onPeerConnected = MutableSharedFlow<TransportPeer>(extraBufferCapacity = 256)
    override val onPeerConnected: Flow<TransportPeer> = _onPeerConnected.asSharedFlow()

    private val _onPeerLost = MutableSharedFlow<String>(extraBufferCapacity = 256)
    override val onPeerLost: Flow<String> = _onPeerLost.asSharedFlow()

    override suspend fun start() {
        isRunning = true
    }

    override suspend fun stop() {
        isRunning = false
        val peerIds = synchronized(connectedPeers) {
            val ids = connectedPeers.keys.toList()
            connectedPeers.clear()
            _peers.value = emptyList()
            ids
        }
        for (id in peerIds) {
            _onPeerLost.tryEmit(id)
        }
    }

    override suspend fun send(peerId: String, bytes: ByteArray): Boolean {
        if (!isRunning || failSends) return false
        val isConnected = synchronized(connectedPeers) { connectedPeers.containsKey(peerId) }
        if (!isConnected) return false

        if (sendLatencyMs > 0) {
            delay(sendLatencyMs)
        }

        sentLog.add(peerId to bytes.copyOf())
        return true
    }

    // --- Simulation helper APIs ---

    fun simulatePeerJoin(
        peerId: String,
        name: String = "Peer-$peerId",
        signalStrength: Int? = -55,
        connectedAt: Instant = Instant.now(),
    ): TransportPeer {
        val peer = TransportPeer(
            id = peerId,
            name = name,
            signalStrength = signalStrength,
            connectedAt = connectedAt,
        )
        synchronized(connectedPeers) {
            connectedPeers[peerId] = peer
            _peers.value = connectedPeers.values.toList()
        }
        _onPeerConnected.tryEmit(peer)
        return peer
    }

    fun simulatePeerLeave(peerId: String): Boolean {
        val removed = synchronized(connectedPeers) {
            val p = connectedPeers.remove(peerId)
            if (p != null) {
                _peers.value = connectedPeers.values.toList()
            }
            p
        }
        if (removed != null) {
            _onPeerLost.tryEmit(peerId)
            return true
        }
        return false
    }

    fun simulateSignalChange(peerId: String, newSignal: Int) {
        synchronized(connectedPeers) {
            val existing = connectedPeers[peerId] ?: return
            val updated = existing.copy(signalStrength = newSignal)
            connectedPeers[peerId] = updated
            _peers.value = connectedPeers.values.toList()
        }
    }

    fun simulateIncomingPayload(fromPeerId: String, bytes: ByteArray) {
        _onReceive.tryEmit(ReceivedPayload(fromPeerId, bytes.copyOf()))
    }

    fun getSentPayloads(): List<Pair<String, ByteArray>> = sentLog.toList()

    fun clearSentLog() {
        sentLog.clear()
    }
}

