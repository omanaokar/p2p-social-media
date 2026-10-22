package com.proximity.core.calls

import com.proximity.contracts.CallPhase
import com.proximity.contracts.CallService
import com.proximity.contracts.CallState
import com.proximity.contracts.IdentityService
import com.proximity.core.AppConfig
import com.proximity.core.Clock
import com.proximity.core.canonicalJson
import com.proximity.core.randomBytes
import com.proximity.core.sync.PeerTable
import com.proximity.core.sync.SyncEngine
import com.proximity.core.toHex
import com.proximity.core.wire.SyncMessage
import com.proximity.core.wire.primitiveString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant

/**
 * Audio media for one call. The Android app implements this with WebRTC
 * (e.g. io.getstream:stream-webrtc-android); this layer only moves signalling.
 *
 * WebRTC needs an IP path between the phones (same Wi-Fi or hotspot). Nearby
 * Connections alone does not provide one, which is part of why calls are the
 * first thing to cut.
 */
interface MediaEngine {
    suspend fun createOffer(): String

    /** Applies the remote offer and returns the local answer. */
    suspend fun acceptOffer(offer: String): String
    suspend fun acceptAnswer(answer: String)
    suspend fun addRemoteCandidate(candidate: String)
    val localCandidates: Flow<String>

    /** Emits true once media flows, false if the connection drops. */
    val connected: Flow<Boolean>
    suspend fun close()
}

fun interface MediaEngineFactory {
    fun create(): MediaEngine
}

class CallServiceImpl(
    private val sync: SyncEngine,
    private val peers: PeerTable,
    private val identity: IdentityService,
    private val mediaFactory: MediaEngineFactory,
    private val config: AppConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : CallService {
    private val _state = MutableStateFlow(CallState.IDLE)
    override val state: StateFlow<CallState> = _state

    private val mutex = Mutex()
    private val pendingCandidates = mutableListOf<String>()
    private var mediaJobs = emptyList<Job>()
    private var media: MediaEngine? = null
    private var remoteOffer: String? = null

    private val current get() = _state.value
    private val busy get() = current.phase != CallPhase.IDLE && current.phase != CallPhase.ENDED

    init {
        scope.launch { sync.callSignals.collect { (transportId, signal) -> mutex.withLock { onSignal(transportId, signal) } } }
        scope.launch { peers.changes.collect { mutex.withLock { onPeersChanged() } } }
    }

    override suspend fun startCall(userId: String) = mutex.withLock {
        check(!busy) { "Already in a call" }
        check(userId in peers.nearbyUserIds) { "$userId is not directly connected" }
        _state.value = CallState(CallPhase.OUTGOING, callId = randomBytes(16).toHex(), peerId = userId)
        val offer = attachMedia().createOffer()
        if (!signal("offer", mapOf("sdp" to offer))) end("Could not reach peer")
    }

    override suspend fun accept() = mutex.withLock {
        val offer = remoteOffer
        check(current.phase == CallPhase.INCOMING && offer != null) { "No incoming call" }
        _state.value = current.copy(phase = CallPhase.CONNECTING)
        val answer = attachMedia().acceptOffer(offer)
        flushCandidates()
        if (!signal("answer", mapOf("sdp" to answer))) end("Could not reach peer")
    }

    override suspend fun hangup() = mutex.withLock {
        if (!busy) return@withLock
        signal(if (current.phase == CallPhase.INCOMING) "reject" else "hangup", emptyMap())
        end()
    }

    private suspend fun onSignal(transportId: String, signal: SyncMessage.CallSignal) {
        val f = signal.fields
        val from = f["from"]?.primitiveString() ?: return
        val type = f["type"]?.primitiveString() ?: return
        val callId = f["callId"]?.primitiveString() ?: return
        val ts = (f["ts"] as? JsonPrimitive)?.longOrNull ?: return
        if (f["to"]?.primitiveString() != identity.currentUser.id) return
        // The link must belong to the claimed sender, and the sender must have
        // signed this exact message recently.
        if (peers.byTransportId(transportId)?.userId != from) return
        if (Duration.between(Instant.ofEpochMilli(ts), clock.now()).abs() > config.maxClockSkew) return
        if (!identity.verify(canonicalJson(f), signal.signature, from)) return

        if (type == "offer") {
            if (busy) {
                sync.sendCallSignal(from, signed(from, callId, "busy", emptyMap()))
                return
            }
            remoteOffer = f["sdp"]?.primitiveString() ?: return
            _state.value = CallState(CallPhase.INCOMING, callId = callId, peerId = from)
            return
        }
        if (callId != current.callId || from != current.peerId) return
        when (type) {
            "answer" -> if (current.phase == CallPhase.OUTGOING) {
                _state.value = current.copy(phase = CallPhase.CONNECTING)
                media?.acceptAnswer(f["sdp"]?.primitiveString() ?: return)
                flushCandidates()
            }
            "ice" -> {
                val candidate = f["candidate"]?.primitiveString() ?: return
                val media = media
                if (media == null || current.phase == CallPhase.OUTGOING || current.phase == CallPhase.INCOMING) {
                    pendingCandidates += candidate
                } else {
                    media.addRemoteCandidate(candidate)
                }
            }
            "hangup" -> end()
            "reject" -> end("Declined")
            "busy" -> end("Busy")
        }
    }

    private suspend fun onPeersChanged() {
        val peer = current.peerId
        if (busy && peer != null && peer !in peers.nearbyUserIds) end("Peer went out of range")
    }

    private fun attachMedia(): MediaEngine {
        val engine = mediaFactory.create()
        media = engine
        mediaJobs = listOf(
            scope.launch { engine.localCandidates.collect { c -> mutex.withLock { signal("ice", mapOf("candidate" to c)) } } },
            scope.launch {
                engine.connected.collect { up ->
                    mutex.withLock {
                        if (up && current.phase == CallPhase.CONNECTING) {
                            _state.value = current.copy(phase = CallPhase.ACTIVE, startedAt = clock.now())
                        } else if (!up && current.phase == CallPhase.ACTIVE) {
                            end("Connection lost")
                        }
                    }
                }
            },
        )
        return engine
    }

    private suspend fun flushCandidates() {
        val engine = media ?: return
        val pending = pendingCandidates.toList()
        pendingCandidates.clear()
        pending.forEach { engine.addRemoteCandidate(it) }
    }

    private suspend fun signal(type: String, extra: Map<String, String>): Boolean {
        val peer = current.peerId ?: return false
        val callId = current.callId ?: return false
        return sync.sendCallSignal(peer, signed(peer, callId, type, extra))
    }

    private fun signed(to: String, callId: String, type: String, extra: Map<String, String>): SyncMessage.CallSignal {
        val fields = JsonObject(
            mapOf(
                "from" to JsonPrimitive(identity.currentUser.id),
                "to" to JsonPrimitive(to),
                "callId" to JsonPrimitive(callId),
                "type" to JsonPrimitive(type),
                "ts" to JsonPrimitive(clock.now().toEpochMilli()),
            ) + extra.mapValues { JsonPrimitive(it.value) },
        )
        return SyncMessage.CallSignal(fields, identity.sign(canonicalJson(fields)))
    }

    private suspend fun end(error: String? = null) {
        mediaJobs.forEach { it.cancel() }
        mediaJobs = emptyList()
        pendingCandidates.clear()
        remoteOffer = null
        media?.close()
        media = null
        _state.value = current.copy(phase = CallPhase.ENDED, error = error)
    }
}
