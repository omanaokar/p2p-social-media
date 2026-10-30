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
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import com.proximity.ranker.profile.UserInterestProfileStore
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Weights used by [LocalRanker] to compute composite relevance scores.
 */
data class RankerWeights(
    val signalWeight: Double = 0.25,
    val freshnessWeight: Double = 0.25,
    val hopCountWeight: Double = 0.15,
    val engagementWeight: Double = 0.15,
    val authorAffinityWeight: Double = 0.15,
    /** Bonus multiplier for educational topics. */
    val educationalBoostWeight: Double = 0.15,
    /** Bonus multiplier for matching user-selected cold-start interest topics. */
    val topicPreferenceWeight: Double = 0.25
)

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
    private val adaptiveRecommender: AdaptiveLearningRecommender? = null
) : Ranker {

    // Maps authorId -> affinity score bounded in [-1.0, 1.0]
    private val authorAffinities = ConcurrentHashMap<String, Double>()

    // Maps postId -> authorId to resolve postId in recordInteraction
    private val postAuthors = ConcurrentHashMap<String, String>()

    // Cache postId -> Post to forward interactions to adaptiveRecommender
    private val postCache = ConcurrentHashMap<String, Post>()

    override suspend fun rank(candidates: List<FeedCandidate>, context: RankContext): List<RankedPost> {
        if (candidates.isEmpty()) return emptyList()

        val userProfile = profileStore.getProfile()
        val selectedTopics = userProfile.selectedTopics

        return candidates.mapNotNull { candidate ->
            val post = candidate.post
            // Cache mapping for future interaction tracking
            postAuthors[post.id] = post.authorId
            postCache[post.id] = post

            // Content classification & moderation check: strictly enforce educational-only content
            val classification: ClassificationResult = classifier.classify(post.body)
            if (!classification.isEducational || classification.category != PrimaryCategory.EDUCATIONAL) {
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
            val educationalScore = when (classification.confidence) {
                ConfidenceLevel.HIGH -> 1.0
                ConfidenceLevel.MEDIUM -> 0.7
                ConfidenceLevel.LOW -> 0.4
                ConfidenceLevel.UNCERTAIN -> 0.0
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
                    0.0
                }
            } else {
                // Learned profile: highest interest score across the post's topics
                val relevantTopics = if (classification.educationalTopics.isNotEmpty()) {
                    classification.educationalTopics
                } else if (classification.primaryTopic != null) {
                    setOf(classification.primaryTopic)
                } else {
                    emptySet()
                }
                relevantTopics.maxOfOrNull { userProfile.getInterestScore(it) } ?: 0.0
            }

            // Composite score
            val compositeScore = (weights.freshnessWeight * freshnessScore) +
                (weights.signalWeight * signalScore) +
                (weights.hopCountWeight * hopScore) +
                (weights.engagementWeight * engagementScore) +
                (weights.authorAffinityWeight * authorScore) +
                (weights.educationalBoostWeight * educationalScore) +
                (weights.topicPreferenceWeight * preferenceScore)

            // Human-readable reason string
            val reason = buildString {
                if (classification.category == PrimaryCategory.EDUCATIONAL && classification.primaryTopic != null) {
                    append(classification.primaryTopic.displayName)
                    if (matchedPreferences.isNotEmpty()) {
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
        val authorId = postAuthors[postId]
        val post = postCache[postId]

        if (authorId != null) {
            val delta = when (type) {
                InteractionType.OPEN -> 0.10
                InteractionType.DWELL -> {
                    val seconds = dwell?.toSeconds() ?: 0L
                    (seconds * 0.015).coerceIn(0.02, 0.20)
                }
                InteractionType.REACT -> 0.25
                InteractionType.COMMENT -> 0.35
                InteractionType.HIDE -> -0.60
            }

            authorAffinities.compute(authorId) { _, current ->
                val updated = (current ?: 0.0) + delta
                updated.coerceIn(-1.0, 1.0)
            }
        }

        if (post != null) {
            if (adaptiveRecommender != null) {
                adaptiveRecommender.recordInteraction(post, type, dwell)
            } else {
                val classification = classifier.classify(post.body)
                profileStore.recordInteraction(post, type, classification)
            }
        }
    }

    /**
     * Inspect learned affinity for testing/debugging.
     */
    fun getAuthorAffinity(authorId: String): Double = authorAffinities[authorId] ?: 0.0
}
