package com.proximity.core.testing

import com.proximity.contracts.ReceivedPayload
import com.proximity.contracts.Transport
import com.proximity.contracts.TransportPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * A simulated radio neighbourhood so app-core runs without phones. Connect and
 * disconnect transports to model people walking in and out of range. Harshil's
 * mock transport replaces this for the rest of the app.
 */
class InMemoryNetwork {
    private val nodes = mutableMapOf<String, InMemoryTransport>()
    private val links = mutableMapOf<String, MutableMap<String, Int?>>()

    @Synchronized
    fun createTransport(id: String): InMemoryTransport =
        InMemoryTransport(this, id).also {
            nodes[id] = it
            links[id] = mutableMapOf()
        }

    fun connect(a: InMemoryTransport, b: InMemoryTransport, signal: Int? = null) {
        synchronized(this) {
            if (links.getValue(a.id).containsKey(b.id)) return
            links.getValue(a.id)[b.id] = signal
            links.getValue(b.id)[a.id] = signal
        }
        if (a.running && b.running) {
            a.peerConnected(b.id, signal)
            b.peerConnected(a.id, signal)
        }
    }

    fun disconnect(a: InMemoryTransport, b: InMemoryTransport) {
        synchronized(this) {
            if (links.getValue(a.id).remove(b.id) == null && !links.getValue(b.id).containsKey(a.id)) return
            links.getValue(b.id).remove(a.id)
        }
        a.peerLost(b.id)
        b.peerLost(a.id)
    }

    @Synchronized
    internal fun linksOf(id: String): Map<String, Int?> = links.getValue(id).toMap()

    @Synchronized
    internal fun node(id: String): InMemoryTransport? = nodes[id]

    internal fun deliver(from: String, to: String, bytes: ByteArray): Boolean {
        val target = synchronized(this) { nodes[to]?.takeIf { links.getValue(from).containsKey(to) } } ?: return false
        if (!target.running) return false
        return target.receive.tryEmit(ReceivedPayload(from, bytes.copyOf()))
    }
}

class InMemoryTransport internal constructor(private val network: InMemoryNetwork, val id: String) : Transport {
    @Volatile
    internal var running = false

    internal val receive = MutableSharedFlow<ReceivedPayload>(extraBufferCapacity = BUFFER)
    private val connected = MutableSharedFlow<TransportPeer>(extraBufferCapacity = BUFFER)
    private val lost = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)
    private val peerList = MutableSharedFlow<List<TransportPeer>>(extraBufferCapacity = BUFFER)
    private val connectedPeers = LinkedHashMap<String, TransportPeer>()

    /** Total messages this transport has sent; handy for asserting traffic. */
    val sentCount = AtomicInteger()

    override suspend fun start() {
        running = true
        for ((other, signal) in network.linksOf(id)) {
            val node = network.node(other) ?: continue
            if (node.running) {
                peerConnected(other, signal)
                node.peerConnected(id, signal)
            }
        }
    }

    override suspend fun stop() {
        running = false
        for (peer in synchronized(connectedPeers) { connectedPeers.keys.toList() }) {
            peerLost(peer)
            network.node(peer)?.peerLost(id)
        }
    }

    override val peers: Flow<List<TransportPeer>> = peerList

    override suspend fun send(peerId: String, bytes: ByteArray): Boolean {
        if (!running) return false
        sentCount.incrementAndGet()
        return network.deliver(id, peerId, bytes)
    }

    override val onReceive: Flow<ReceivedPayload> = receive
    override val onPeerConnected: Flow<TransportPeer> = connected
    override val onPeerLost: Flow<String> = lost

    internal fun peerConnected(peerId: String, signal: Int?) {
        val snapshot = synchronized(connectedPeers) {
            if (connectedPeers.containsKey(peerId)) return
            connectedPeers[peerId] = TransportPeer(peerId, peerId, signal, Instant.now())
            connectedPeers.values.toList()
        }
        connected.tryEmit(snapshot.last())
        peerList.tryEmit(snapshot)
    }

    internal fun peerLost(peerId: String) {
        val snapshot = synchronized(connectedPeers) {
            connectedPeers.remove(peerId) ?: return
            connectedPeers.values.toList()
        }
        lost.tryEmit(peerId)
        peerList.tryEmit(snapshot)
    }

    private companion object {
        const val BUFFER = 4096
    }
}
