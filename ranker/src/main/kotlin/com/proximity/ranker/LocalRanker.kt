package com.proximity.ranker

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.InteractionType
import com.proximity.contracts.Post
import com.proximity.contracts.RankContext
import com.proximity.contracts.RankedPost
import com.proximity.contracts.Ranker
import com.proximity.ranker.classifier.ClassificationResult
import com.proximity.ranker.classifier.ConfidenceLevel
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.learning.AdaptiveLearningRecommender
import com.proximity.ranker.moderation.ContentModerator
import com.proximity.ranker.profile.EventAwareUserInterestProfileStore
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import com.proximity.ranker.profile.UserInterestProfileStore
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Weights used by [LocalRanker] to compute composite relevance scores.
 * All default weights are strictly normalized to sum to 1.0.
 */
data class RankerWeights(
    val signalWeight: Double = 0.15,
    val freshnessWeight: Double = 0.20,
    val hopCountWeight: Double = 0.10,
    val engagementWeight: Double = 0.15,
    val authorAffinityWeight: Double = 0.10,
    /** Bonus multiplier for educational topics. */
    val educationalBoostWeight: Double = 0.10,
    /** Bonus multiplier for matching user-selected cold-start interest topics. */
    val topicPreferenceWeight: Double = 0.20
) {
    val totalWeight: Double
        get() = signalWeight + freshnessWeight + hopCountWeight +
                engagementWeight + authorAffinityWeight + educationalBoostWeight + topicPreferenceWeight

    init {
        val weights = listOf(
            "signalWeight" to signalWeight,
            "freshnessWeight" to freshnessWeight,
            "hopCountWeight" to hopCountWeight,
            "engagementWeight" to engagementWeight,
            "authorAffinityWeight" to authorAffinityWeight,
            "educationalBoostWeight" to educationalBoostWeight,
            "topicPreferenceWeight" to topicPreferenceWeight
        )
        for ((name, w) in weights) {
            require(!w.isNaN()) { "Weight '$name' cannot be NaN" }
            require(!w.isInfinite()) { "Weight '$name' cannot be infinite, found $w" }
            require(w >= 0.0) { "Weight '$name' must be non-negative, found $w" }
        }
        require(!totalWeight.isNaN() && !totalWeight.isInfinite() && totalWeight > 0.0) {
            "Total weight must be positive and finite, found $totalWeight"
        }
    }
}

/**
 * In-memory local ranking engine implementing [Ranker].
 *
 * Combines physical proximity (radio signal), network distance (hops),
 * recency, engagement metrics, local author affinity learned from interaction,
 * educational topic classification, and user cold-start interest profile to produce
 * deterministic feed ordering.
 */
class LocalRanker(
    private val weights: RankerWeights = RankerWeights(),
    private val classifier: TextContentClassifier = HeuristicTextClassifier(),
    val profileStore: UserInterestProfileStore = InMemoryUserInterestProfileStore(),
    private val moderator: ContentModerator? = null,
    private val adaptiveRecommender: AdaptiveLearningRecommender? = null,
    private val postResolver: (suspend (String) -> Post?)? = null,
    private var interactionEventLedger: InteractionEventLedger? = null,
) : Ranker {

    // Maps authorId -> affinity score bounded in [-1.0, 1.0]
    private val authorAffinities = ConcurrentHashMap<String, Double>()

    // Maps postId -> authorId to resolve postId in recordInteraction
    private val postAuthors = ConcurrentHashMap<String, String>()

    // Cache postId -> Post to forward interactions to adaptiveRecommender
    private val postCache = ConcurrentHashMap<String, Post>()

    // Tracks processed event IDs / idempotency keys to ensure duplicate event dispatches are idempotent
    // while allowing repeated legitimate interactions on the same post.
    private val processedEventIds = ConcurrentHashMap.newKeySet<String>()

    override suspend fun rank(candidates: List<FeedCandidate>, context: RankContext): List<RankedPost> {
        if (candidates.isEmpty()) return emptyList()

        val userProfile = profileStore.getProfile()
        val selectedTopics = userProfile.selectedTopics

        return candidates.mapNotNull { candidate ->
            val post = candidate.post
            // Cache mapping for future interaction tracking
            postAuthors[post.id] = post.authorId
            postCache[post.id] = post

            // Content classification & moderation check: abusive, profane, hateful and adult
            // posts are never shown. Educational relevance only boosts ranking; it is not required.
            val classification: ClassificationResult = classifier.classify(post.body)
            if (classification.isInappropriate) {
                return@mapNotNull null
            }

            if (moderator != null) {
                val decision = moderator.decide(classification)
                if (!decision.isApproved) {
                    return@mapNotNull null
                }
            }

            val now = context.now

            // 1. Freshness score (exponential decay with half-life of 4 hours)
            val ageHours = max(0.0, Duration.between(post.createdAt, now).toMinutes() / 60.0)
            val freshnessScore = exp(-0.173 * ageHours) // ~0.5 at 4 hours

            // 2. Hop count score (0 hops = 1.0, 1 hop = 0.67, 2 hops = 0.33, 3+ hops = 0.0)
            val hops = post.hopCount.coerceIn(0, 3)
            val hopScore = 1.0 - (hops / 3.0)

            // 3. Proximity / Signal score (RSSI normalized from [-100, -30] to [0.0, 1.0])
            val signalScore = candidate.receivedSignalStrength?.let { rssi ->
                ((rssi.coerceIn(-100, -30) + 100) / 70.0)
            } ?: 0.35 // Neutral fallback when signal is unavailable

            // 4. Engagement score (logarithmic to prevent viral saturation)
            val totalReactions = candidate.reactionCounts.values.sum()
            val totalInteractions = candidate.commentCount + totalReactions
            val engagementScore = (ln(1.0 + totalInteractions) / ln(1.0 + 20.0)).coerceIn(0.0, 1.0)

            // 5. Author affinity (learned locally, default 0.0)
            val affinity = authorAffinities[post.authorId] ?: 0.0
            val authorScore = ((affinity + 1.0) / 2.0).coerceIn(0.0, 1.0)

            // 6. Educational Content Boost & User Interest Matching
            val educationalScore = if (classification.category != PrimaryCategory.EDUCATIONAL) {
                0.0
            } else {
                when (classification.confidence) {
                    ConfidenceLevel.HIGH -> 1.0
                    ConfidenceLevel.MEDIUM -> 0.7
                    ConfidenceLevel.LOW -> 0.4
                    ConfidenceLevel.UNCERTAIN -> 0.0
                }
            }

            // 7. Topic Preference Score (Cold-start interest alignment and learned topic scores)
            // Checks if any detected educational topic in the post matches the user's selected interests or learned profile
            val matchedPreferences = if (selectedTopics.isNotEmpty()) {
                classification.educationalTopics.intersect(selectedTopics)
            } else {
                emptySet()
            }

            val preferenceScore = if (userProfile.isColdStart) {
                if (matchedPreferences.isNotEmpty()) {
                    val isPrimaryMatch = classification.primaryTopic != null && classification.primaryTopic in selectedTopics
                    if (isPrimaryMatch) 1.0 else 0.7
                } else {
                    0.5 // Neutral baseline across all educational topics for cold-start users
                }
            } else {
                // Learned profile: combine continuous topic interest score with adaptive learning status
                val relevantTopics = if (classification.educationalTopics.isNotEmpty()) {
                    classification.educationalTopics
                } else if (classification.primaryTopic != null) {
                    setOf(classification.primaryTopic)
                } else {
                    emptySet()
                }
                
                val bestTopicScore = relevantTopics.maxOfOrNull { userProfile.getInterestScore(it) } ?: 0.5
                
                // If adaptive recommender is available, incorporate underexplored exploration bonus
                val explorationBonus = if (adaptiveRecommender != null && classification.primaryTopic != null) {
                    val eng = adaptiveRecommender.getTopicEngagement(classification.primaryTopic)
                    // Boost underexplored topics slightly so users discover new curriculum areas
                    if (eng.totalInteractions == 0 && classification.primaryTopic in selectedTopics) 0.15 else 0.0
                } else {
                    0.0
                }
                (bestTopicScore + explorationBonus).coerceIn(0.0, 1.0)
            }

            // Composite score
            val rawCompositeScore = (weights.freshnessWeight * freshnessScore) +
                (weights.signalWeight * signalScore) +
                (weights.hopCountWeight * hopScore) +
                (weights.engagementWeight * engagementScore) +
                (weights.authorAffinityWeight * authorScore) +
                (weights.educationalBoostWeight * educationalScore) +
                (weights.topicPreferenceWeight * preferenceScore)

            // Normalize by total weight and clamp strictly in [0.0, 1.0]
            val compositeScore = (rawCompositeScore / weights.totalWeight).coerceIn(0.0, 1.0)

            // Human-readable reason string
            val reason = buildString {
                if (classification.category == PrimaryCategory.EDUCATIONAL && classification.primaryTopic != null) {
                    append(classification.primaryTopic.displayName)
                    if (matchedPreferences.isNotEmpty() || (!userProfile.isColdStart && userProfile.getInterestScore(classification.primaryTopic) > 0.6)) {
                        append(" (preferred topic)")
                    }
                    append(" • ")
                }
                if (candidate.receivedSignalStrength != null) {
                    append("${candidate.receivedSignalStrength} dBm")
                } else if (candidate.post.hopCount == 0) {
                    append("local")
                } else {
                    append("${candidate.post.hopCount} hops")
                }
                if (totalInteractions > 0) {
                    append(", $totalInteractions interactions")
                }
                if (affinity > 0.2) {
                    append(", preferred author")
                }
            }

            RankedPost(
                candidate = candidate,
                score = compositeScore,
                reason = reason
            )
        }.sortedByDescending { it.score }
    }

    override suspend fun recordInteraction(postId: String, type: InteractionType, dwell: Duration?) {
        recordInteraction(postId, type, dwell, explicitPost = null, eventId = null)
    }

    /**
     * Records an interaction on a post, supporting uncached posts via explicit [explicitPost]
     * or fallback [postResolver].
     *
     * @param eventId Optional idempotency key / event ID. When provided, duplicate dispatches
     * with the same event ID are ignored. When null, repeated legitimate interactions are recorded.
     */
    suspend fun recordInteraction(
        postId: String,
        type: InteractionType,
        dwell: Duration? = null,
        explicitPost: Post? = null,
        eventId: String? = null
    ) {
        if (eventId != null && !processedEventIds.add(eventId)) {
            // Duplicate event dispatch already processed
            return
        }

        // Cache explicit post if provided
        if (explicitPost != null) {
            postAuthors[explicitPost.id] = explicitPost.authorId
            postCache[explicitPost.id] = explicitPost
        }

        // Resolve post: 1. from postCache, 2. from postResolver
        val post = postCache[postId] ?: postResolver?.invoke(postId)?.also {
            postAuthors[it.id] = it.authorId
            postCache[it.id] = it
        }

        val authorId = postAuthors[postId] ?: post?.authorId

        if (authorId == null && post == null) {
            // Post could not be resolved; rollback event ID so future retry can succeed
            if (eventId != null) {
                processedEventIds.remove(eventId)
            }
            return
        }

        // Hold the configured ledger for this whole interaction so a later
        // configuration change cannot split begin/complete across instances.
        val eventLedger = interactionEventLedger
        if (eventId != null && eventLedger != null) {
            try {
                if (!eventLedger.begin(eventId)) return
            } catch (e: Throwable) {
                processedEventIds.remove(eventId)
                throw e
            }
        }

        if (authorId != null) {
            // Author affinity sits on a [-1.0, 1.0] scale centered at 0.0 with single-author specificity.
            // In contrast, topic interest in UserInterestProfile sits on a [0.0, 1.0] scale centered at 0.50
            // and is shared across multiple matched topics with logarithmic diminishing returns damping.
            // On the author affinity scale, SAVE (+0.30) sits proportionally between REACT (+0.25) and
            // COMMENT (+0.35), reflecting the user commitment hierarchy: OPEN (0.10) < REACT (0.25) < SAVE (0.30) < COMMENT (0.35).
            // On the topic interest scale, SAVE (+0.20) sits between REACT (+0.15) and COMMENT (+0.25).
            val delta = when (type) {
                InteractionType.OPEN -> 0.10
                InteractionType.DWELL -> {
                    val seconds = dwell?.toSeconds() ?: 0L
                    (seconds * 0.015).coerceIn(0.02, 0.20)
                }
                InteractionType.REACT -> 0.25
                InteractionType.SAVE -> 0.30
                InteractionType.COMMENT -> 0.35
                InteractionType.HIDE -> -0.60
            }

            authorAffinities.compute(authorId) { _, current ->
                val updated = (current ?: 0.0) + delta
                updated.coerceIn(-1.0, 1.0)
            }
        }

        try {
            if (post != null) {
                val classification = classifier.classify(post.body)
                val profileWasRecorded = if (eventId != null && profileStore is EventAwareUserInterestProfileStore) {
                    profileStore.recordInteractionOnce(eventId, post, type, classification)
                    true
                } else {
                    false
                }
                if (adaptiveRecommender != null) {
                    adaptiveRecommender.recordInteraction(post, type, dwell, profileAlreadyRecorded = profileWasRecorded)
                } else if (!profileWasRecorded) {
                    profileStore.recordInteraction(post, type, classification)
                }
            }

            if (eventId != null && eventLedger != null) {
                eventLedger.complete(eventId)
            }
        } catch (e: Throwable) {
            if (eventId != null) processedEventIds.remove(eventId)
            throw e
        }
    }

    /**
     * Overload accepting an explicit [Post] and optional [eventId].
     */
    suspend fun recordInteraction(
        post: Post,
        type: InteractionType,
        dwell: Duration? = null,
        eventId: String? = null
    ) {
        recordInteraction(post.id, type, dwell, post, eventId)
    }

    /**
     * Inspect learned affinity for testing/debugging.
     */
    fun getAuthorAffinity(authorId: String): Double = authorAffinities[authorId] ?: 0.0

    /**
     * Checks if an event ID has already been processed.
     */
    fun isEventProcessed(eventId: String): Boolean = processedEventIds.contains(eventId)

    /**
     * Resets processed event history (for testing).
     */
    fun resetEventHistory() {
        processedEventIds.clear()
    }

    /** Configures optional application-owned durable event handling after construction. */
    internal fun setInteractionEventLedger(ledger: InteractionEventLedger?) {
        interactionEventLedger = ledger
    }
}
