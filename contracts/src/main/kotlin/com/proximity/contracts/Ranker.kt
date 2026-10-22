package com.proximity.contracts

import java.time.Duration
import java.time.Instant

/** Owned by the ranking layer (Neil). Runs fully on device. */
interface Ranker {
    suspend fun rank(candidates: List<FeedCandidate>, context: RankContext): List<RankedPost>

    /** Updates the local interest profile. Never leaves the device. */
    suspend fun recordInteraction(postId: String, type: InteractionType, dwell: Duration? = null)
}

data class RankContext(val now: Instant, val currentUserId: String)

class RankedPost(
    val candidate: FeedCandidate,
    val score: Double,
    /** Short human-readable explanation, e.g. "nearby, 3 reactions". */
    val reason: String,
)

enum class InteractionType { OPEN, REACT, COMMENT, DWELL, HIDE }
