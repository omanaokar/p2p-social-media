package com.proximity.ranker.learning

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.Post
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.studygroup.StudyGroupCohort
import com.proximity.ranker.studygroup.StudyGroupMatcher
import com.proximity.ranker.studygroup.StudyGroupMatchingContext
import java.time.Instant

/**
 * Exploration category for an educational topic in the user's adaptive learning journey.
 */
enum class ExplorationStatus(val displayName: String) {
    /**
     * User has declared interest in this topic but has little to no engagement.
     * High priority for foundational recommendations.
     */
    UNDEREXPLORED("Underexplored Topic"),

    /**
     * Topic the user has not explicitly chosen, but is adjacent to active topics or
     * suggested as a foundational starting point.
     */
    EXPLORATION_OPPORTUNITY("New Exploration Opportunity"),

    /**
     * Topic with active, positive interactions (reading, reacting, commenting).
     */
    ACTIVE_STUDY("Active Focus Area"),

    /**
     * Topic with extensive recent engagement; branching to adjacent subjects is advised.
     */
    SATURATED("Well Explored")
}

/**
 * Aggregated local interaction signals for a specific educational topic.
 *
 * NOTE: Tracks strictly local behavioral metrics (opens, dwells, reactions, hides).
 * Does NOT diagnose or measure academic ability or pedagogical mastery.
 */
data class TopicEngagement(
    val topic: EducationalTopic,
    val openCount: Int = 0,
    val reactCount: Int = 0,
    val commentCount: Int = 0,
    val hideCount: Int = 0,
    val totalDwellSeconds: Long = 0,
    val lastInteractedAt: Instant? = null,
    val saveCount: Int = 0
) {
    val totalInteractions: Int get() = openCount + reactCount + commentCount + hideCount + saveCount
    val positiveInteractions: Int get() = reactCount + commentCount + saveCount + (if (totalDwellSeconds >= 30) 1 else 0)

    /**
     * Net behavioral score bounded in [-1.0, 5.0].
     */
    val engagementScore: Double
        get() {
            if (totalInteractions == 0) return 0.0
            val positive = (openCount * 0.2) + (reactCount * 0.5) + (saveCount * 0.6) + (commentCount * 0.8) + (totalDwellSeconds / 60.0 * 0.3)
            val negative = hideCount * 1.0
            return (positive - negative).coerceIn(-1.0, 5.0)
        }
}

/**
 * Recommended educational topic to explore next.
 *
 * @param topic The recommended educational subject.
 * @param score Priority score in [0.0, 1.0] (higher = higher priority to explore next).
 * @param status Categorization of the user's current relationship with this topic.
 * @param explanation Human-readable rationale grounded in interaction history and declared interests.
 */
data class TopicRecommendation(
    val topic: EducationalTopic,
    val score: Double,
    val status: ExplorationStatus,
    val explanation: String
)

/**
 * Recommended post aligned with the user's adaptive learning focus.
 *
 * @param candidate The matching educational post.
 * @param topic Primary educational subject of the post.
 * @param score Overall recommendation score.
 * @param status Exploration status of the underlying topic.
 * @param explanation Contextual reason why this post is recommended next.
 */
data class AdaptivePostRecommendation(
    val candidate: FeedCandidate,
    val topic: EducationalTopic,
    val score: Double,
    val status: ExplorationStatus,
    val explanation: String
)

/**
 * Input context for generating adaptive learning recommendations.
 *
 * ── P2P Architecture Constraints & Pedagogical Limitations ──────────────────
 * 1. No Centralized Curriculum or Assessment Data: The P2P network does not host
 *    graded quizzes, exams, or verified pedagogical rubrics. The engine infers
 *    "underexplored" topics from engagement telemetry (dwell time, reactions, skips)
 *    and cold-start declared interests — it does not measure academic competency.
 * 2. Privacy-Preserving: Interaction counters and learning profiles are stored
 *    strictly on the local device. No learning telemetry is relayed across peers.
 *
 * @param candidatePosts Available non-expired posts in the local database.
 * @param seenPostIds IDs of posts already viewed or recommended, to avoid repetitive suggestions.
 * @param customEngagements Optional override for topic engagements (e.g. for testing or external stores).
 * @param now Reference timestamp for recency calculation.
 */
data class AdaptiveLearningContext(
    val candidatePosts: List<FeedCandidate> = emptyList(),
    val seenPostIds: Set<String> = emptySet(),
    val customEngagements: Map<EducationalTopic, TopicEngagement> = emptyMap(),
    val now: Instant = Instant.now()
)

/**
 * Contract for adaptive learning recommendations in the P2P network.
 */
interface AdaptiveLearningRecommender {
    /**
     * Recommends educational topics the user should explore next.
     */
    fun recommendTopics(context: AdaptiveLearningContext): List<TopicRecommendation>

    /**
     * Recommends curated educational posts to explore next, avoiding repeated content.
     */
    fun recommendPosts(context: AdaptiveLearningContext, limit: Int = 10): List<AdaptivePostRecommendation>

    /**
     * Records a local user interaction with an educational post.
     */
    fun recordInteraction(post: Post, type: com.proximity.contracts.InteractionType, dwell: java.time.Duration? = null)

    /**
     * Retrieves current tracked engagement for a topic.
     */
    fun getTopicEngagement(topic: EducationalTopic): TopicEngagement

    /**
     * Bridges an adaptive topic recommendation with study-group matching to form a peer study group.
     */
    fun suggestStudyGroupForTopic(
        topic: EducationalTopic,
        studyGroupMatcher: StudyGroupMatcher,
        matchingContext: StudyGroupMatchingContext,
        maxMembers: Int = 4
    ): StudyGroupCohort?
}
