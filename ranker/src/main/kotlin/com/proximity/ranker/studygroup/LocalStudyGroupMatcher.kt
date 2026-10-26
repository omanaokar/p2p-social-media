package com.proximity.ranker.studygroup

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.Profile
import com.proximity.ranker.classifier.ClassificationResult
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import com.proximity.ranker.profile.UserInterestProfileStore

/**
 * Deterministic, offline, privacy-preserving study-group matching engine.
 *
 * Scoring signals:
 * 1. Topic Overlap (weight 0.45): Inferred from candidate posts vs user's selected topics (or targetTopic).
 * 2. Study Goal Compatibility (weight 0.25): Inferred from candidate posts and/or explicit candidate goals vs user's goals.
 * 3. Subject Activity / Depth (weight 0.15): Number of educational posts authored by candidate in the matched topics.
 * 4. Physical / Radio Proximity (weight 0.15): Whether the peer is currently in direct Bluetooth / Wi-Fi Direct range.
 */
class LocalStudyGroupMatcher(
    private val profileStore: UserInterestProfileStore = InMemoryUserInterestProfileStore(),
    private val classifier: TextContentClassifier = HeuristicTextClassifier()
) : StudyGroupMatcher {

    override fun match(context: StudyGroupMatchingContext): List<StudyGroupMatch> {
        val currentUserId = context.currentUserId
        val blocked = context.blockedUserIds
        val friends = context.existingFriendIds

        // 1. Sanitize, deduplicate, and exclude invalid / blocked / self / friend profiles
        val candidatesByUserId = context.candidateProfiles
            .filter { profile ->
                val uid = profile.publicKey
                uid.isNotBlank() &&
                    uid != currentUserId &&
                    uid !in blocked &&
                    (!context.excludeExistingFriends || uid !in friends)
            }
            .associateBy { it.publicKey }

        if (candidatesByUserId.isEmpty()) return emptyList()

        // 2. Map nearby status
        val nearbyMap = context.nearbyPeerProfiles.associate { it.userId to it }

        // 3. Process candidate posts: infer educational topics, study goals, and count active posts
        val candidateTopics = mutableMapOf<String, MutableSet<EducationalTopic>>()
        val candidateInferredGoals = mutableMapOf<String, MutableSet<StudyGoal>>()
        val candidatePostCounts = mutableMapOf<String, MutableMap<EducationalTopic, Int>>()

        for (candidatePost in context.candidatePosts) {
            val authorId = candidatePost.post.authorId
            if (authorId in candidatesByUserId) {
                val classification: ClassificationResult = classifier.classify(candidatePost.post.body)
                if (classification.category == PrimaryCategory.EDUCATIONAL) {
                    val topics = candidateTopics.getOrPut(authorId) { mutableSetOf() }
                    topics.addAll(classification.educationalTopics)

                    val counts = candidatePostCounts.getOrPut(authorId) { mutableMapOf() }
                    for (t in classification.educationalTopics) {
                        counts[t] = (counts[t] ?: 0) + 1
                    }
                }
                // Infer study goals from post content
                val inferred = inferStudyGoalsFromText(candidatePost.post.body)
                if (inferred.isNotEmpty()) {
                    val goals = candidateInferredGoals.getOrPut(authorId) { mutableSetOf() }
                    goals.addAll(inferred)
                }
            }
        }

        // 4. Resolve user's topics and study goals
        val userProfile = profileStore.getProfile()
        val userTopics = if (context.targetTopic != null) {
            setOf(context.targetTopic)
        } else {
            userProfile.selectedTopics
        }
        val userGoals = userProfile.studyGoals

        // 5. Compute compatibility scores for each candidate
        val matches = mutableListOf<StudyGroupMatch>()

        for ((userId, profile) in candidatesByUserId) {
            val peerTopics = candidateTopics[userId] ?: emptySet()
            val sharedTopics = if (userTopics.isNotEmpty()) {
                peerTopics.intersect(userTopics)
            } else {
                emptySet()
            }

            // Exclude if required topic overlap is enforced
            if (context.requireTopicOverlap && userTopics.isNotEmpty() && sharedTopics.isEmpty()) {
                continue
            }

            // Resolve peer goals: explicit goals merge with inferred
            val peerGoals = mutableSetOf<StudyGoal>().apply {
                addAll(context.candidateStudyGoals[userId] ?: emptySet())
                addAll(candidateInferredGoals[userId] ?: emptySet())
            }
            val sharedGoals = if (userGoals.isNotEmpty()) {
                peerGoals.intersect(userGoals)
            } else {
                emptySet()
            }

            // Active posts in shared/target topics
            val peerCounts = candidatePostCounts[userId] ?: emptyMap()
            val activeTopicPostCount = if (sharedTopics.isNotEmpty()) {
                sharedTopics.sumOf { peerCounts[it] ?: 0 }
            } else if (context.targetTopic != null) {
                peerCounts[context.targetTopic] ?: 0
            } else {
                peerCounts.values.sum()
            }

            // Signal 1: Topic Overlap Score (weight: 0.45)
            val topicScore = if (userTopics.isNotEmpty()) {
                if (sharedTopics.isNotEmpty()) {
                    (sharedTopics.size.toDouble() / userTopics.size).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
            } else if (peerTopics.isNotEmpty()) {
                // Cold-start user without preferences: modest baseline for active educational peers
                0.20
            } else {
                0.0
            }

            // Signal 2: Study Goal Compatibility Score (weight: 0.25)
            val goalScore = if (userGoals.isNotEmpty() && peerGoals.isNotEmpty()) {
                (sharedGoals.size.toDouble() / userGoals.size).coerceIn(0.0, 1.0)
            } else if (peerGoals.isNotEmpty() && userGoals.isEmpty()) {
                0.15
            } else {
                0.0
            }

            // Signal 3: Subject Activity / Depth Score (weight: 0.15)
            val activityScore = (activeTopicPostCount / 3.0).coerceIn(0.0, 1.0)

            // Signal 4: Proximity Score (weight: 0.15)
            val nearbyInfo = nearbyMap[userId]
            val isNearby = nearbyInfo?.isNearby == true
            val proximityScore = if (isNearby) 1.0 else 0.0

            // Composite score
            val totalScore = (0.45 * topicScore) +
                             (0.25 * goalScore) +
                             (0.15 * activityScore) +
                             (0.15 * proximityScore)

            // Human-readable explanation
            val explanation = buildExplanation(
                sharedTopics = sharedTopics,
                userTopics = userTopics,
                peerTopics = peerTopics,
                sharedGoals = sharedGoals,
                activeTopicPostCount = activeTopicPostCount,
                isNearby = isNearby
            )

            matches += StudyGroupMatch(
                profile = profile,
                score = totalScore,
                sharedTopics = sharedTopics,
                sharedGoals = sharedGoals,
                explanation = explanation,
                isNearby = isNearby,
                activeTopicPosts = activeTopicPostCount
            )
        }

        // Deterministic sort: score descending, then public key ascending
        return matches.sortedWith(
            compareByDescending<StudyGroupMatch> { it.score }
                .thenBy { it.profile.publicKey }
        )
    }

    override fun formGroup(
        topic: EducationalTopic,
        context: StudyGroupMatchingContext,
        maxMembers: Int
    ): StudyGroupCohort? {
        val topicContext = context.copy(targetTopic = topic, requireTopicOverlap = true)
        val matches = match(topicContext).take(maxMembers)
        if (matches.isEmpty()) return null

        val avgScore = matches.map { it.score }.average()
        val rationale = buildString {
            append("Study group for ${topic.displayName} with ${matches.size} candidate(s)")
            val nearbyCount = matches.count { it.isNearby }
            if (nearbyCount > 0) {
                append(" • $nearbyCount nearby")
            }
        }

        return StudyGroupCohort(
            topic = topic,
            members = matches,
            averageScore = avgScore,
            rationale = rationale
        )
    }

    private fun buildExplanation(
        sharedTopics: Set<EducationalTopic>,
        userTopics: Set<EducationalTopic>,
        peerTopics: Set<EducationalTopic>,
        sharedGoals: Set<StudyGoal>,
        activeTopicPostCount: Int,
        isNearby: Boolean
    ): String {
        val reasons = mutableListOf<String>()

        if (sharedTopics.isNotEmpty()) {
            reasons += "Shares ${sharedTopics.size} topic(s): ${sharedTopics.joinToString { it.displayName }}"
        } else if (userTopics.isNotEmpty()) {
            reasons += "No shared educational topics identified"
        } else if (peerTopics.isNotEmpty()) {
            reasons += "Active in ${peerTopics.joinToString { it.displayName }}"
        } else {
            reasons += "Discovered peer (no educational posts found)"
        }

        if (sharedGoals.isNotEmpty()) {
            reasons += "Compatible goal(s): ${sharedGoals.joinToString { it.displayName }}"
        }

        if (activeTopicPostCount > 0) {
            reasons += "$activeTopicPostCount active post(s)"
        }

        if (isNearby) {
            reasons += "Currently nearby"
        }

        return reasons.joinToString(" • ")
    }

    companion object {
        /**
         * Offline heuristic keyword matcher to infer study goals from post text.
         */
        fun inferStudyGoalsFromText(text: String?): Set<StudyGoal> {
            if (text.isNullOrBlank()) return emptySet()
            val lower = text.lowercase()
            val goals = mutableSetOf<StudyGoal>()

            if (lower.contains("exam") || lower.contains("midterm") || lower.contains("final exam") ||
                lower.contains("test prep") || lower.contains("quiz") || lower.contains("revision") ||
                lower.contains("syllabus") || lower.contains("cheatsheet") || lower.contains("past paper")) {
                goals += StudyGoal.EXAM_PREP
            }

            if (lower.contains("project") || lower.contains("hackathon") || lower.contains("build") ||
                lower.contains("building") || lower.contains("github") || lower.contains("collaborat") ||
                lower.contains("open source") || lower.contains("fullstack") || lower.contains("app dev")) {
                goals += StudyGoal.PROJECT_COLLABORATION
            }

            if (lower.contains("leetcode") || lower.contains("problem solving") || lower.contains("coding interview") ||
                lower.contains("interview prep") || lower.contains("hackerrank") || lower.contains("codeforces") ||
                lower.contains("algorithm practice")) {
                goals += StudyGoal.PROBLEM_SOLVING
            }

            if (lower.contains("deep dive") || lower.contains("tutorial") || lower.contains("concept") ||
                lower.contains("fundamentals") || lower.contains("under the hood") ||
                lower.contains("understanding") || lower.contains("mastering")) {
                goals += StudyGoal.CONCEPT_MASTERY
            }

            if (lower.contains("paper") || lower.contains("arxiv") || lower.contains("research") ||
                lower.contains("reading group") || lower.contains("benchmark") ||
                lower.contains("state of the art")) {
                goals += StudyGoal.RESEARCH_AND_READING
            }

            return goals
        }
    }
}
