package com.proximity.ranker.studygroup

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.PeerProfile
import com.proximity.contracts.Profile
import com.proximity.ranker.classifier.EducationalTopic

/**
 * Educational study goals/styles supported for study-group matching.
 */
enum class StudyGoal(val displayName: String) {
    EXAM_PREP("Exam Preparation"),
    PROJECT_COLLABORATION("Project Collaboration"),
    CONCEPT_MASTERY("Concept Learning & Deep Dive"),
    PROBLEM_SOLVING("Problem Solving & Interview Prep"),
    RESEARCH_AND_READING("Research & Paper Reading")
}

/**
 * A ranked match for a study group or study partnership.
 *
 * @param profile Candidate peer's profile.
 * @param score Normalised compatibility score in [0.0, 1.0] (higher = stronger fit).
 * @param sharedTopics Educational topics shared between user (or target subject) and candidate.
 * @param sharedGoals Compatible study goals shared between user and candidate.
 * @param explanation Human-readable summary explaining why this peer was matched.
 * @param isNearby Whether the peer is currently in local radio range (Bluetooth / Wi-Fi Direct).
 * @param activeTopicPosts Number of educational posts by candidate in the matched topics.
 */
data class StudyGroupMatch(
    val profile: Profile,
    val score: Double,
    val sharedTopics: Set<EducationalTopic>,
    val sharedGoals: Set<StudyGoal>,
    val explanation: String,
    val isNearby: Boolean = false,
    val activeTopicPosts: Int = 0
)

/**
 * Recommended study group cohort formed from top matched peers for a subject.
 *
 * @param topic Subject of the study group.
 * @param members Matched peer profiles forming the group.
 * @param averageScore Average compatibility score among members.
 * @param rationale Summary explanation of cohort suitability.
 */
data class StudyGroupCohort(
    val topic: EducationalTopic,
    val members: List<StudyGroupMatch>,
    val averageScore: Double,
    val rationale: String
)

/**
 * Context provided for local study-group matching.
 *
 * ── P2P Architecture Constraints & Documented Limitations ─────────────────────
 * 1. P2P Profile Schema: The signed [Profile] in `:contracts` does not provide
 *    structured study-group, department, or goal fields. The matcher infers
 *    educational topics and study goals from candidate posts using the existing
 *    [TextContentClassifier] and deterministic keyword heuristics, unless explicit
 *    [candidateStudyGoals] are passed.
 * 2. Serverless Mesh Graph: In Proximity's serverless mesh, there is no centralized
 *    group directory. Candidates are drawn from locally known profiles (synced peers,
 *    post authors, nearby radio scans).
 * 3. Privacy & Local Operation: All matching runs 100% locally on device without
 *    network requests or cloud analytics.
 *
 * @param currentUserId Local user's ID (Ed25519 public key hex).
 * @param targetTopic Optional specific educational topic to form a study group for.
 *                    When null, matching uses the user's full interest profile.
 * @param candidateProfiles All candidate profiles known locally.
 * @param candidatePosts Stored posts authored by candidates to infer topics and goals.
 * @param candidateStudyGoals Optional explicit study goals per candidate user ID if available.
 * @param nearbyPeerProfiles Locally observed peers with radio connectivity signals.
 * @param existingFriendIds Connected friends (excluded when [excludeExistingFriends] is true).
 * @param blockedUserIds Users blocked locally (always excluded).
 * @param excludeExistingFriends Whether to exclude existing friends (default true).
 * @param requireTopicOverlap If true, only candidates sharing at least one topic are returned.
 */
data class StudyGroupMatchingContext(
    val currentUserId: String,
    val targetTopic: EducationalTopic? = null,
    val candidateProfiles: List<Profile>,
    val candidatePosts: List<FeedCandidate> = emptyList(),
    val candidateStudyGoals: Map<String, Set<StudyGoal>> = emptyMap(),
    val nearbyPeerProfiles: List<PeerProfile> = emptyList(),
    val existingFriendIds: Set<String> = emptySet(),
    val blockedUserIds: Set<String> = emptySet(),
    val excludeExistingFriends: Boolean = true,
    val requireTopicOverlap: Boolean = false
)

/**
 * Contract for matching study partners and forming local study groups.
 */
interface StudyGroupMatcher {
    /**
     * Matches and ranks individual candidate peers for study partnerships.
     */
    fun match(context: StudyGroupMatchingContext): List<StudyGroupMatch>

    /**
     * Forms a recommended study group cohort of up to [maxMembers] for [topic].
     */
    fun formGroup(
        topic: EducationalTopic,
        context: StudyGroupMatchingContext,
        maxMembers: Int = 4
    ): StudyGroupCohort?
}
