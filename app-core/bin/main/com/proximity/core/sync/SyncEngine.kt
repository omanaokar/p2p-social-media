package com.proximity.core.sync

import com.proximity.contracts.IdentityService
import com.proximity.contracts.ReceivedPayload
import com.proximity.contracts.Transport
import com.proximity.contracts.TransportPeer
import com.proximity.core.AppConfig
import com.proximity.core.Clock
import com.proximity.core.store.ObjectStore
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import com.proximity.core.wire.SyncMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Keeps this device's store in sync with every directly connected peer.
 *
 * On connect both sides send a Hello with their profile and the ids of
 * everything they can forward. Each side then Wants what it lacks. Newly stored
 * objects are announced to the other peers with Have, so content spreads hop by
 * hop without central coordination.
 *
 * All work runs in [scope]; give it a single-threaded dispatcher so messages
 * are handled one at a time.
 */
class SyncEngine(
    private val transport: Transport,
    private val store: ObjectStore,
    private val identity: IdentityService,
    private val peers: PeerTable,
    private val config: AppConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableListOf<Job>()
    private val helloSent = mutableSetOf<String>()

    /** Ids we asked a peer for, so a second peer offering them is not asked too. */
    private val requested = mutableMapOf<String, Instant>()

    private val _callSignals = MutableSharedFlow<Pair<String, SyncMessage.CallSignal>>(extraBufferCapacity = 64)

    /** Call signalling messages with the transport id they came from. */
    val callSignals: Flow<Pair<String, SyncMessage.CallSignal>> = _callSignals

    suspend fun start() {
        store.cleanup()
        // Undispatched so every collector is subscribed before the transport emits.
        fun <T> collect(flow: Flow<T>, handler: suspend (T) -> Unit) =
            jobs.add(scope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { handler(it) } })
        collect(transport.onPeerConnected, ::onConnected)
        collect(transport.onPeerLost, ::onLost)
        collect(transport.onReceive, ::onReceive)
        collect(transport.peers, ::onPeers)
        jobs += scope.launch {
            while (isActive) {
                delay(config.cleanupInterval.toMillis())
                runCleanup()
            }
        }
        transport.start()
    }

    suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        synchronized(helloSent) { helloSent.clear() }
        transport.stop()
    }

    fun runCleanup() {
        store.cleanup()
        val cutoff = clock.now().minus(config.wantRetryAfter)
        synchronized(requested) { requested.values.removeIf { it.isBefore(cutoff) } }
    }

    /** Announces a locally created object to everyone connected. */
    fun publish(obj: SignedObject) {
        scope.launch { announce(listOf(obj.id)) }
    }

    /** Sends a call signal to a directly connected user. False if they are not connected or the send failed. */
    suspend fun sendCallSignal(userId: String, signal: SyncMessage.CallSignal): Boolean {
        val transportId = peers.transportIdFor(userId) ?: return false
        return send(transportId, signal)
    }

    private suspend fun onConnected(peer: TransportPeer) {
        peers.upsert(peer)
        sendHello(peer.id)
    }

    private fun onLost(transportId: String) {
        peers.remove(transportId)
        synchronized(helloSent) { helloSent.remove(transportId) }
    }

    private fun onPeers(list: List<TransportPeer>) {
        peers.sync(list)
        for (peer in peers.all) {
            peer.userId?.let { store.touchPeer(it, peer.signalStrength) }
        }
    }

    private suspend fun sendHello(transportId: String) {
        if (!synchronized(helloSent) { helloSent.add(transportId) }) return
        val profile = checkNotNull(store.profileObject(identity.currentUser.id)) { "Own profile missing; create it before starting sync" }
        send(transportId, SyncMessage.Hello(profile.withHopCount(0), store.forwardableIds()))
    }

    private suspend fun onReceive(payload: ReceivedPayload) {
        val message = try {
            SyncMessage.decode(payload.bytes)
        } catch (e: IllegalArgumentException) {
            return // Corrupt or not ours: drop it.
        }
        val from = payload.peerId
        if (peers.byTransportId(from) == null) peers.upsert(TransportPeer(from, "", null, clock.now()))
        when (message) {
            is SyncMessage.Hello -> onHello(from, message.profile, message.ids)
            is SyncMessage.Have -> requestMissing(from, message.ids)
            is SyncMessage.Want -> sendObjects(from, message.ids)
            is SyncMessage.Objects -> ingest(from, message.objects)
            is SyncMessage.CallSignal -> _callSignals.emit(from to message)
        }
    }

    private suspend fun onHello(from: String, profile: SignedObject, ids: List<String>) {
        if (profile.kind != ObjectKind.PROFILE || !profile.verify(identity)) return
        val signal = peers.byTransportId(from)?.signalStrength
        store.ingest(profile, signal = signal)
        peers.setUser(from, profile.signerId)
        store.touchPeer(profile.signerId, signal)
        sendHello(from)
        requestMissing(from, ids)
    }

    private suspend fun requestMissing(from: String, ids: List<String>) {
        val now = clock.now()
        val missing = synchronized(requested) {
            store.unknownIds(ids)
                .filter { id -> requested[id].let { it == null || it.plus(config.wantRetryAfter).isBefore(now) } }
                .onEach { requested[it] = now }
        }
        if (missing.isNotEmpty()) send(from, SyncMessage.Want(missing))
    }

    private suspend fun sendObjects(to: String, ids: List<String>) {
        val forwardable = store.forwardableIds().toSet()
        store.getObjects(ids.filter(forwardable::contains))
            .chunked(config.maxObjectsPerMessage)
            .forEach { send(to, SyncMessage.Objects(it)) }
    }

    private suspend fun ingest(from: String, objects: List<SignedObject>) {
        val info = peers.byTransportId(from)
        val relay = mutableListOf<String>()
        val created = mutableListOf<String>()
        for (obj in objects.sortedBy { it.kind.ordinal }) {
            synchronized(requested) { requested.remove(obj.id) }
            val result = store.ingest(obj, info?.userId, info?.signalStrength)
            if (result.stored && store.isForwardable(obj, obj.hopCount + 1)) relay += obj.id
            created += result.created.map { it.id }
        }
        if (relay.isNotEmpty()) announce(relay, except = from)
        if (created.isNotEmpty()) announce(created)
    }

    private suspend fun announce(ids: List<String>, except: String? = null) {
        for (peer in peers.all) {
            if (peer.transportId != except) send(peer.transportId, SyncMessage.Have(ids))
        }
    }

    private suspend fun send(transportId: String, message: SyncMessage): Boolean = try {
        transport.send(transportId, message.encode())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
