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
import kotlin.math.roundToInt

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
 * @param nearbyPeerProfiles Currently detected peers over local radios.
 * @param mutualContactInteractions Map of candidateUserId -> count of mutual touchpoints.
 * @param myLikedPostIds Set of post IDs liked/saved/reacted to by current user.
 * @param peerLikedPostIds Map of candidateUserId -> set of post IDs liked/reacted to by that peer.
 */
data class PeerRecommendationContext(
    val currentUserId: String,
    val candidateProfiles: List<Profile>,
    val existingFriendIds: Set<String> = emptySet(),
    val blockedUserIds: Set<String> = emptySet(),
    val candidatePosts: List<FeedCandidate> = emptyList(),
    val nearbyPeerProfiles: List<PeerProfile> = emptyList(),
    val mutualContactInteractions: Map<String, Int> = emptyMap(),
    val myLikedPostIds: Set<String> = emptySet(),
    val peerLikedPostIds: Map<String, Set<String>> = emptyMap()
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
 * 1. Jaccard Similarity on Liked Educational Posts:
 *    Intersection / Union of liked educational post IDs between current user and peer.
 * 2. Topic-Level Profile Similarity:
 *    Shared topic interests between the user's persistent profile (or declared topics) and the peer's topics.
 * 3. Mutual Connections / Interactions:
 *    Derived from shared touchpoints on the local device.
 * 4. Physical / Radio Proximity:
 *    Bonus if the peer is currently in direct Bluetooth / Wi-Fi Direct range.
 */
class LocalPeerRecommender(
    private val profileStore: UserInterestProfileStore = InMemoryUserInterestProfileStore(),
    private val classifier: TextContentClassifier = HeuristicTextClassifier()
) : PeerRecommender {

    override fun recommend(context: PeerRecommendationContext): List<PeerRecommendation> {
        val currentUserId = context.currentUserId
        val userProfile = profileStore.getProfile()
        val mySelectedTopics = userProfile.selectedTopics
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

        // 3. Filter educational candidate posts and map their primary/detected topics
        val educationalPostsById = mutableMapOf<String, Set<EducationalTopic>>()
        val candidateTopics = mutableMapOf<String, MutableSet<EducationalTopic>>()

        for (candidatePost in context.candidatePosts) {
            val post = candidatePost.post
            val classification: ClassificationResult = classifier.classify(post.body)
            if (classification.category == PrimaryCategory.EDUCATIONAL) {
                educationalPostsById[post.id] = classification.educationalTopics
                val authorId = post.authorId
                if (authorId in candidatesByUserId) {
                    val topics = candidateTopics.getOrPut(authorId) { mutableSetOf() }
                    topics.addAll(classification.educationalTopics)
                }
            }
        }

        // Sanitize liked posts: only consider educational posts in Jaccard similarity
        // If educational status of a liked post is known, only keep if educational;
        // if not in candidatePosts cache, keep only if it matches known educational posts or treat as educational.
        val myEducationalLiked = if (educationalPostsById.isNotEmpty()) {
            context.myLikedPostIds.filter { id -> educationalPostsById[id] != null }.toSet()
        } else {
            context.myLikedPostIds
        }

        // 4. Compute recommendation scores for each valid candidate
        val recommendations = mutableListOf<PeerRecommendation>()

        for ((userId, profile) in candidatesByUserId) {
            val peerTopics = candidateTopics[userId]?.toSet() ?: emptySet()

            // ── Signal 1: Jaccard Similarity on Liked Educational Posts ────────────────
            val peerLikedRaw = context.peerLikedPostIds[userId] ?: emptySet()
            val peerEducationalLiked = if (educationalPostsById.isNotEmpty()) {
                peerLikedRaw.filter { id -> educationalPostsById[id] != null }.toSet()
            } else {
                peerLikedRaw
            }

            val jaccardScore: Double = if (myEducationalLiked.isNotEmpty() && peerEducationalLiked.isNotEmpty()) {
                val intersectionSize = myEducationalLiked.intersect(peerEducationalLiked).size
                val unionSize = myEducationalLiked.union(peerEducationalLiked).size
                if (unionSize > 0) intersectionSize.toDouble() / unionSize else 0.0
            } else {
                0.0
            }

            // Also derive topics from posts the peer liked
            val peerLikedTopics = peerEducationalLiked.flatMap { educationalPostsById[it] ?: emptySet() }.toSet()
            val effectivePeerTopics = peerTopics + peerLikedTopics

            // ── Signal 2: Topic-Level Profile Similarity ─────────────────────────────
            val sharedTopics = if (mySelectedTopics.isNotEmpty()) {
                effectivePeerTopics.intersect(mySelectedTopics)
            } else if (!userProfile.isColdStart) {
                effectivePeerTopics.filter { userProfile.getInterestScore(it) > 0.5 }.toSet()
            } else {
                emptySet()
            }

            val topicSimilarity: Double = if (sharedTopics.isNotEmpty()) {
                val base = if (mySelectedTopics.isNotEmpty()) {
                    sharedTopics.size.toDouble() / mySelectedTopics.size.coerceAtLeast(1)
                } else {
                    sharedTopics.map { userProfile.getInterestScore(it) }.average()
                }
                base.coerceIn(0.0, 1.0)
            } else if (effectivePeerTopics.isNotEmpty() && userProfile.isColdStart) {
                // Cold-start fallback: gentle baseline for active educational peers without bias
                0.20
            } else {
                0.0
            }

            // Combined educational interest score (liked post similarity + topic profile similarity)
            // When liked posts overlap exists, heavily weight Jaccard; otherwise fallback gracefully to topic similarity
            val interestScore = if (jaccardScore > 0.0) {
                (0.60 * jaccardScore) + (0.40 * topicSimilarity)
            } else {
                topicSimilarity
            }

            // ── Signal 3: Mutual Connections / Touchpoints ────────────────────────────
            val mutualInteractions = context.mutualContactInteractions[userId] ?: 0
            val mutualScore = (mutualInteractions / 10.0).coerceIn(0.0, 1.0)

            // ── Signal 4: Physical Radio Proximity ────────────────────────────────────
            val nearbyInfo = nearbyMap[userId]
            val isNearby = nearbyInfo?.isNearby == true
            val proximityScore = if (isNearby) 0.30 else 0.0

            // ── Composite Score Calculation ──────────────────────────────────────────
            val totalScore = (0.50 * interestScore) + (0.35 * mutualScore) + (0.15 * proximityScore)

            // ── Human-Readable Explanation ────────────────────────────────────────────
            val reasonSummary = buildString {
                val reasons = mutableListOf<String>()
                if (jaccardScore > 0.0) {
                    val sharedCount = myEducationalLiked.intersect(peerEducationalLiked).size
                    reasons += "$sharedCount shared liked educational post(s)"
                }
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
                    if (effectivePeerTopics.isNotEmpty()) {
                        reasons += "Active in ${effectivePeerTopics.joinToString { it.displayName }}"
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

        // Stable deterministic sort by score descending, then by public key
        return recommendations.sortedWith(
            compareByDescending<PeerRecommendation> { it.score }
                .thenBy { it.profile.publicKey }
        )
    }
}
