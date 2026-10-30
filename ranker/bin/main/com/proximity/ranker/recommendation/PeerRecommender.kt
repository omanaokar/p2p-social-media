package com.proximity.ranker.recommendation

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.PeerProfile
import com.proximity.contracts.Profile
import com.proximity.ranker.classifier.ClassificationResult
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import com.proximity.ranker.profile.UserInterestProfileStore

/**
 * Explanation reasons for why a candidate peer was recommended.
 */
data class RecommendationReason(
    val sharedTopics: Set<EducationalTopic> = emptySet(),
    val mutualInteractionsCount: Int = 0,
    val isNearby: Boolean = false,
    val summary: String
)

/**
 * Ranked recommendation for a potential peer or study buddy.
 *
 * @param profile Candidate user's profile.
 * @param score Computed ranking score (higher is stronger recommendation).
 * @param reason Human-readable explanation.
 * @param sharedTopics Educational interests identified as shared between current user and peer.
 */
data class PeerRecommendation(
    val profile: Profile,
    val score: Double,
    val reason: String,
    val sharedTopics: Set<EducationalTopic> = emptySet()
)

/**
 * Context provided to [PeerRecommender] containing available local data.
 *
 * NOTE on P2P API constraints:
 * In Proximity's serverless mesh, there is no centralized social graph or global friend table.
 * Friendship/connection status locally manifests as established contacts (e.g. users with active
 * direct message conversations or explicit friend IDs). Mutual connections are derived from
 * shared local interactions (e.g., peers collaborating on the same posts or relaying messages).
 *
 * @param currentUserId Local user's ID (Ed25519 public key hex).
 * @param candidateProfiles Available candidate profiles learned from sync, nearby scans, or post authors.
 * @param existingFriendIds Users already connected/friended with the local user (to be excluded).
 * @param blockedUserIds Users blocked locally (to be excluded).
 * @param candidatePosts Stored posts authored by candidates to infer candidate educational interests.
 * @param mutualContactInteractions Map of candidateUserId -> count of mutual touchpoints (e.g., commenting/reacting on the same posts).
 */
data class PeerRecommendationContext(
    val currentUserId: String,
    val candidateProfiles: List<Profile>,
    val existingFriendIds: Set<String> = emptySet(),
    val blockedUserIds: Set<String> = emptySet(),
    val candidatePosts: List<FeedCandidate> = emptyList(),
    val nearbyPeerProfiles: List<PeerProfile> = emptyList(),
    val mutualContactInteractions: Map<String, Int> = emptyMap()
)

/**
 * Contract for recommending peers and study partners based on shared interests and network proximity.
 */
interface PeerRecommender {
    fun recommend(context: PeerRecommendationContext): List<PeerRecommendation>
}

/**
 * Deterministic, offline, privacy-preserving recommendation engine.
 *
 * Scoring signals:
 * 1. Shared Educational Interests: Inferred from the candidate's authored posts compared to the user's selected interests.
 * 2. Mutual Connections / Interactions: Derived from shared touchpoints on the local device.
 * 3. Proximity: Bonus if the peer is currently nearby or has strong radio signal.
 */
class LocalPeerRecommender(
    private val profileStore: UserInterestProfileStore = InMemoryUserInterestProfileStore(),
    private val classifier: TextContentClassifier = HeuristicTextClassifier()
) : PeerRecommender {

    override fun recommend(context: PeerRecommendationContext): List<PeerRecommendation> {
        val currentUserId = context.currentUserId
        val mySelectedTopics = profileStore.getProfile().selectedTopics
        val blocked = context.blockedUserIds
        val friends = context.existingFriendIds

        // 1. Deduplicate candidate profiles by public key and sanitize
        val candidatesByUserId = context.candidateProfiles
            .filter { profile ->
                val uid = profile.publicKey
                uid.isNotBlank() &&
                    uid != currentUserId &&
                    uid !in friends &&
                    uid !in blocked
            }
            .associateBy { it.publicKey }

        if (candidatesByUserId.isEmpty()) return emptyList()

        // 2. Map nearby status for quick lookup
        val nearbyMap = context.nearbyPeerProfiles.associate { it.userId to it }

        // 3. Infer candidate educational topics from their posts
        val candidateTopics = mutableMapOf<String, MutableSet<EducationalTopic>>()
        for (candidatePost in context.candidatePosts) {
            val authorId = candidatePost.post.authorId
            if (authorId in candidatesByUserId) {
                val classification: ClassificationResult = classifier.classify(candidatePost.post.body)
                if (classification.category == PrimaryCategory.EDUCATIONAL) {
                    val topics = candidateTopics.getOrPut(authorId) { mutableSetOf() }
                    topics.addAll(classification.educationalTopics)
                }
            }
        }

        // 4. Compute recommendation scores for each valid candidate
        val recommendations = mutableListOf<PeerRecommendation>()

        for ((userId, profile) in candidatesByUserId) {
            val peerTopics = candidateTopics[userId] ?: emptySet()
            val sharedTopics = if (mySelectedTopics.isNotEmpty()) {
                peerTopics.intersect(mySelectedTopics)
            } else {
                emptySet()
            }

            // Signal 1: Shared interest score (up to 1.0)
            val interestScore = if (sharedTopics.isNotEmpty()) {
                (sharedTopics.size.toDouble() / mySelectedTopics.size.coerceAtLeast(1)).coerceAtMost(1.0)
            } else if (peerTopics.isNotEmpty() && mySelectedTopics.isEmpty()) {
                // If current user hasn't selected topics yet, give modest baseline for educational contributors
                0.2
            } else {
                0.0
            }

            // Signal 2: Mutual connection / interaction score (log scale)
            val mutualInteractions = context.mutualContactInteractions[userId] ?: 0
            val mutualScore = (mutualInteractions / 10.0).coerceIn(0.0, 1.0)

            // Signal 3: Physical proximity bonus
            val nearbyInfo = nearbyMap[userId]
            val isNearby = nearbyInfo?.isNearby == true
            val proximityScore = if (isNearby) 0.3 else 0.0

            // Composite score calculation
            val totalScore = (0.50 * interestScore) + (0.35 * mutualScore) + (0.15 * proximityScore)

            // Construct meaningful explanation
            val reasonSummary = buildString {
                val reasons = mutableListOf<String>()
                if (sharedTopics.isNotEmpty()) {
                    reasons += "Shares ${sharedTopics.size} topic(s): ${sharedTopics.joinToString { it.displayName }}"
                }
                if (mutualInteractions > 0) {
                    reasons += "$mutualInteractions mutual interaction(s)"
                }
                if (isNearby) {
                    reasons += "Currently nearby"
                }
                if (reasons.isEmpty()) {
                    if (peerTopics.isNotEmpty()) {
                        reasons += "Active in ${peerTopics.joinToString { it.displayName }}"
                    } else {
                        reasons += "Discovered peer"
                    }
                }
                append(reasons.joinToString(" • "))
            }

            recommendations += PeerRecommendation(
                profile = profile,
                score = totalScore,
                reason = reasonSummary,
                sharedTopics = sharedTopics
            )
        }

        // Stable deterministic sort by score descending, then by user ID
        return recommendations.sortedWith(
            compareByDescending<PeerRecommendation> { it.score }
                .thenBy { it.profile.publicKey }
        )
    }
}
