package com.proximity.ranker.learning

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.InteractionType
import com.proximity.contracts.Post
import com.proximity.ranker.classifier.ClassificationResult
import com.proximity.ranker.classifier.ConfidenceLevel
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import com.proximity.ranker.profile.UserInterestProfileStore
import com.proximity.ranker.studygroup.StudyGroupCohort
import com.proximity.ranker.studygroup.StudyGroupMatcher
import com.proximity.ranker.studygroup.StudyGroupMatchingContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Offline, deterministic adaptive learning recommender.
 *
 * Recommends topics and posts to explore based on:
 * 1. Declared cold-start interests in [UserInterestProfile].
 * 2. Interaction history (opens, dwell times, reactions, comments, dismissals) per topic.
 * 3. Learning pathways and adjacent topic relationships (e.g. Programming -> DSA).
 * 4. Repetition suppression via [AdaptiveLearningContext.seenPostIds].
 *
 * Pedagogical Limitation:
 * Signals track user engagement and exploration density only. No assessment or quiz data
 * is captured; "underexplored" indicates low interaction activity, not low mastery.
 */
class LocalAdaptiveLearningRecommender(
    private val profileStore: UserInterestProfileStore = InMemoryUserInterestProfileStore(),
    private val classifier: TextContentClassifier = HeuristicTextClassifier()
) : AdaptiveLearningRecommender {

    // Thread-safe map of local engagement per topic
    private val engagements = ConcurrentHashMap<EducationalTopic, TopicEngagement>()

    override fun recordInteraction(post: Post, type: InteractionType, dwell: Duration?) {
        val classification = classifier.classify(post.body)
        if (classification.category != PrimaryCategory.EDUCATIONAL) return

        // Update persistent profileStore topic scores
        profileStore.recordInteraction(post, type, classification)

        val topics = classification.educationalTopics
        if (topics.isEmpty()) return

        for (topic in topics) {
            engagements.compute(topic) { _, current ->
                val prev = current ?: TopicEngagement(topic = topic)
                val dwellSecs = dwell?.toSeconds() ?: 0L
                when (type) {
                    InteractionType.OPEN -> prev.copy(
                        openCount = prev.openCount + 1,
                        totalDwellSeconds = prev.totalDwellSeconds + dwellSecs,
                        lastInteractedAt = Instant.now()
                    )
                    InteractionType.DWELL -> prev.copy(
                        totalDwellSeconds = prev.totalDwellSeconds + dwellSecs,
                        lastInteractedAt = Instant.now()
                    )
                    InteractionType.REACT -> prev.copy(
                        reactCount = prev.reactCount + 1,
                        lastInteractedAt = Instant.now()
                    )
                    InteractionType.COMMENT -> prev.copy(
                        commentCount = prev.commentCount + 1,
                        lastInteractedAt = Instant.now()
                    )
                    InteractionType.HIDE -> prev.copy(
                        hideCount = prev.hideCount + 1,
                        lastInteractedAt = Instant.now()
                    )
                }
            }
        }
    }

    override fun getTopicEngagement(topic: EducationalTopic): TopicEngagement {
        return engagements[topic] ?: TopicEngagement(topic = topic)
    }

    override fun recommendTopics(context: AdaptiveLearningContext): List<TopicRecommendation> {
        val userProfile = profileStore.getProfile()
        val selectedTopics = userProfile.selectedTopics

        // Merge runtime engagements with any context overrides
        val activeEngagements = EducationalTopic.values().associateWith { topic ->
            context.customEngagements[topic] ?: getTopicEngagement(topic)
        }

        val recommendations = mutableListOf<TopicRecommendation>()

        // 1. Identify which topics are actively studied
        val activeTopics = activeEngagements.filter { (_, eng) ->
            eng.engagementScore >= 1.5 && eng.positiveInteractions >= 2
        }.keys

        for (topic in EducationalTopic.values()) {
            val eng = activeEngagements[topic] ?: TopicEngagement(topic = topic)
            val isSelected = topic in selectedTopics

            val (status, score, explanation) = evaluateTopic(
                topic = topic,
                isSelected = isSelected,
                engagement = eng,
                hasAnySelectedTopics = selectedTopics.isNotEmpty(),
                hasAnyInteractions = activeEngagements.values.any { it.totalInteractions > 0 },
                activeTopics = activeTopics
            )

            recommendations += TopicRecommendation(
                topic = topic,
                score = score,
                status = status,
                explanation = explanation
            )
        }

        // Sort descending by score, tie-break by topic name
        return recommendations.sortedWith(
            compareByDescending<TopicRecommendation> { it.score }
                .thenBy { it.topic.displayName }
        )
    }

    override fun recommendPosts(
        context: AdaptiveLearningContext,
        limit: Int
    ): List<AdaptivePostRecommendation> {
        if (context.candidatePosts.isEmpty()) return emptyList()

        val topicRecommendations = recommendTopics(context).associateBy { it.topic }
        val seen = context.seenPostIds
        val now = context.now

        // Deduplicate incoming candidate posts by ID
        val uniqueCandidates = context.candidatePosts.distinctBy { it.post.id }

        // Filter valid educational, non-expired, and unseen posts
        val eligible = uniqueCandidates.filter { candidate ->
            val post = candidate.post
            post.id !in seen && !post.expiresAt.isBefore(now)
        }

        if (eligible.isEmpty()) return emptyList()

        val scoredPosts = eligible.mapNotNull { candidate ->
            val classification = classifier.classify(candidate.post.body)
            if (classification.category != PrimaryCategory.EDUCATIONAL) return@mapNotNull null

            val primaryTopic = classification.primaryTopic
                ?: classification.educationalTopics.firstOrNull()
                ?: return@mapNotNull null

            val topicRec = topicRecommendations[primaryTopic] ?: return@mapNotNull null

            // Signal 1: Topic priority (0.50)
            val topicPriority = topicRec.score

            // Signal 2: Content classification confidence (0.20)
            val confidenceScore = when (classification.confidence) {
                ConfidenceLevel.HIGH -> 1.0
                ConfidenceLevel.MEDIUM -> 0.75
                ConfidenceLevel.LOW -> 0.50
                ConfidenceLevel.UNCERTAIN -> 0.25
            }

            // Signal 3: Freshness (0.15) - 6 hour half life
            val ageHours = max(0.0, Duration.between(candidate.post.createdAt, now).toMinutes() / 60.0)
            val freshnessScore = exp(-0.115 * ageHours)

            // Signal 4: Peer engagement (0.15)
            val totalInteractions = candidate.commentCount + candidate.reactionCounts.values.sum()
            val engagementScore = (ln(1.0 + totalInteractions) / ln(1.0 + 15.0)).coerceIn(0.0, 1.0)

            val totalScore = (0.50 * topicPriority) +
                             (0.20 * confidenceScore) +
                             (0.15 * freshnessScore) +
                             (0.15 * engagementScore)

            val postExplanation = buildString {
                append("Recommended for ${primaryTopic.displayName} (${topicRec.status.displayName})")
                if (topicRec.status == ExplorationStatus.UNDEREXPLORED) {
                    append(" • Foundational reading to build engagement")
                } else if (topicRec.status == ExplorationStatus.ACTIVE_STUDY) {
                    append(" • Active focus area")
                } else if (topicRec.status == ExplorationStatus.EXPLORATION_OPPORTUNITY) {
                    append(" • Broaden learning pathway")
                }
                if (totalInteractions > 0) {
                    append(" • $totalInteractions peer interaction(s)")
                }
            }

            AdaptivePostRecommendation(
                candidate = candidate,
                topic = primaryTopic,
                score = totalScore,
                status = topicRec.status,
                explanation = postExplanation
            )
        }

        // Sort descending by score
        val sorted = scoredPosts.sortedByDescending { it.score }

        // Apply author diversity: avoid more than 2 consecutive posts from same author
        val balanced = mutableListOf<AdaptivePostRecommendation>()
        var lastAuthorId: String? = null
        var repeatCount = 0

        for (rec in sorted) {
            val authorId = rec.candidate.post.authorId
            if (authorId == lastAuthorId) {
                repeatCount++
                if (repeatCount >= 2) continue
            } else {
                lastAuthorId = authorId
                repeatCount = 1
            }
            balanced += rec
            if (limit > 0 && balanced.size >= limit) break
        }

        return balanced
    }

    override fun suggestStudyGroupForTopic(
        topic: EducationalTopic,
        studyGroupMatcher: StudyGroupMatcher,
        matchingContext: StudyGroupMatchingContext,
        maxMembers: Int
    ): StudyGroupCohort? {
        return studyGroupMatcher.formGroup(
            topic = topic,
            context = matchingContext,
            maxMembers = maxMembers
        )
    }

    private fun evaluateTopic(
        topic: EducationalTopic,
        isSelected: Boolean,
        engagement: TopicEngagement,
        hasAnySelectedTopics: Boolean,
        hasAnyInteractions: Boolean,
        activeTopics: Set<EducationalTopic>
    ): Triple<ExplorationStatus, Double, String> {

        // 1. Cold-start case: User has selected topics, but 0 interactions with this topic
        if (isSelected && engagement.totalInteractions == 0) {
            return Triple(
                ExplorationStatus.UNDEREXPLORED,
                0.90,
                "Selected interest in profile with 0 recorded interactions — recommended for foundational exploration."
            )
        }

        // 2. Saturated topic: high recent engagement, suggest diversifying
        if (engagement.engagementScore > 4.0) {
            return Triple(
                ExplorationStatus.SATURATED,
                0.45,
                "Topic is well-explored locally (${engagement.positiveInteractions} positive interactions) — consider branching to adjacent subjects."
            )
        }

        // 3. Active focus: user engages regularly and positively
        if (engagement.engagementScore in 1.2..4.0 && engagement.positiveInteractions >= 2) {
            return Triple(
                ExplorationStatus.ACTIVE_STUDY,
                0.75,
                "Active learning focus with ${engagement.positiveInteractions} positive interaction(s) — continuing progression."
            )
        }

        // 4. Underexplored interest: user declared interest or interacted, but has low engagement or dismissals
        if (isSelected && (engagement.engagementScore < 1.2 || engagement.hideCount > 0)) {
            val reason = if (engagement.hideCount > 0) {
                "Topic has ${engagement.hideCount} dismissal(s) in local history — alternative foundational content recommended."
            } else {
                "Selected interest with sparse engagement (${engagement.totalInteractions} interaction(s)) — explore key concepts."
            }
            return Triple(ExplorationStatus.UNDEREXPLORED, 0.80, reason)
        }

        // 5. Adjacent progression from an actively studied topic
        val adjacentParent = activeTopics.firstOrNull { active -> isAdjacentTopic(active, topic) }
        if (adjacentParent != null && !isSelected) {
            return Triple(
                ExplorationStatus.EXPLORATION_OPPORTUNITY,
                0.70,
                "Suggested natural progression from active focus in ${adjacentParent.displayName}."
            )
        }

        // 6. Complete cold-start fallback (no declared topics and no interactions)
        if (!hasAnySelectedTopics && !hasAnyInteractions) {
            val isFoundational = topic in FOUNDATIONAL_STARTER_TOPICS
            val score = if (isFoundational) 0.65 else 0.40
            return Triple(
                ExplorationStatus.EXPLORATION_OPPORTUNITY,
                score,
                if (isFoundational) {
                    "Foundational starter subject for new learners joining the local mesh."
                } else {
                    "Exploration opportunity available in the local network."
                }
            )
        }

        // 7. General fallback for unselected topic
        val baselineScore = if (isSelected) 0.60 else 0.35
        return Triple(
            ExplorationStatus.EXPLORATION_OPPORTUNITY,
            baselineScore,
            "Exploration opportunity based on available local peer activity."
        )
    }

    companion object {
        /**
         * Suggested foundational topics for complete cold-start users with no preferences.
         */
        val FOUNDATIONAL_STARTER_TOPICS = setOf(
            EducationalTopic.PROGRAMMING,
            EducationalTopic.DSA,
            EducationalTopic.MATHEMATICS
        )

        /**
         * Deterministic curriculum relationships between topics.
         */
        fun isAdjacentTopic(from: EducationalTopic, to: EducationalTopic): Boolean = when (from) {
            EducationalTopic.PROGRAMMING -> to == EducationalTopic.DSA || to == EducationalTopic.CLOUD_COMPUTING
            EducationalTopic.DSA -> to == EducationalTopic.CYBERSECURITY || to == EducationalTopic.AI_ML
            EducationalTopic.MATHEMATICS -> to == EducationalTopic.AI_ML || to == EducationalTopic.DSA
            EducationalTopic.AI_ML -> to == EducationalTopic.MATHEMATICS || to == EducationalTopic.PROGRAMMING
            EducationalTopic.CYBERSECURITY -> to == EducationalTopic.CLOUD_COMPUTING || to == EducationalTopic.PROGRAMMING
            EducationalTopic.CLOUD_COMPUTING -> to == EducationalTopic.CYBERSECURITY || to == EducationalTopic.PROGRAMMING
            EducationalTopic.SCIENCE_ENGINEERING -> to == EducationalTopic.MATHEMATICS || to == EducationalTopic.ACADEMIC_RESEARCH
            EducationalTopic.ACADEMIC_RESEARCH -> to == EducationalTopic.SCIENCE_ENGINEERING || to == EducationalTopic.AI_ML
        }
    }
}
