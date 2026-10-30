package com.proximity.core.testing

import com.proximity.core.calls.MediaEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Collections

/** Pretends to negotiate media: connects as soon as an offer/answer pair has been exchanged. */
class FakeMediaEngine : MediaEngine {
    private val candidates = MutableSharedFlow<String>(replay = 1)
    private val _connected = MutableSharedFlow<Boolean>(replay = 1)
    val remoteCandidates: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var closed = false
        private set

    override suspend fun createOffer(): String {
        candidates.emit("candidate-from-caller")
        return "offer-sdp"
    }

    override suspend fun acceptOffer(offer: String): String {
        _connected.emit(true)
        return "answer-sdp"
    }

    override suspend fun acceptAnswer(answer: String) = _connected.emit(true)

    override suspend fun addRemoteCandidate(candidate: String) {
        remoteCandidates += candidate
    }

    override val localCandidates: Flow<String> = candidates
    override val connected: Flow<Boolean> = _connected

    override suspend fun close() {
        closed = true
    }
}
