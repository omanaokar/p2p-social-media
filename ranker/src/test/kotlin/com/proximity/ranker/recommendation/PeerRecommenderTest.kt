package com.proximity.ranker.recommendation

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.PeerProfile
import com.proximity.contracts.Post
import com.proximity.contracts.Profile
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerRecommenderTest {

    private val profileStore = InMemoryUserInterestProfileStore()
    private val recommender = LocalPeerRecommender(profileStore = profileStore)
    private val now = Instant.parse("2026-10-27T00:00:00Z")

    private fun profile(userId: String, name: String = "User $userId"): Profile =
        Profile(
            id = "id-$userId",
            publicKey = userId,
            encryptionKey = "enc-$userId",
            displayName = name,
            avatar = null,
            updatedAt = now,
            signature = "sig-$userId"
        )

    private fun candidatePost(postId: String, authorId: String, body: String): FeedCandidate {
        val post = Post(
            id = postId,
            authorId = authorId,
            body = body,
            image = null,
            createdAt = now,
            ttl = Duration.ofHours(6),
            hopCount = 0,
            signature = "sig-$postId"
        )
        return FeedCandidate(
            post = post,
            author = profile(authorId),
            receivedAt = now,
            receivedFrom = null,
            receivedSignalStrength = -50,
            commentCount = 1,
            reactionCounts = emptyMap(),
            myReaction = null,
            isMine = false
        )
    }

    @Test
    fun `returns empty list on empty candidates`() {
        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = emptyList()
        )
        val result = recommender.recommend(context)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `excludes current user, existing friends, and blocked users`() {
        val candidateProfiles = listOf(
            profile("me", "Myself"),
            profile("friend1", "Best Friend"),
            profile("blocked1", "Blocked User"),
            profile("candidate1", "Valid Candidate")
        )

        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = candidateProfiles,
            existingFriendIds = setOf("friend1"),
            blockedUserIds = setOf("blocked1")
        )

        val recommendations = recommender.recommend(context)
        assertEquals(1, recommendations.size)
        assertEquals("candidate1", recommendations.first().profile.publicKey)
    }

    @Test
    fun `deduplicates candidate profiles by public key`() {
        val candidateProfiles = listOf(
            profile("user1", "User One"),
            profile("user1", "User One Duplicate"),
            profile("user2", "User Two")
        )

        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = candidateProfiles
        )

        val recommendations = recommender.recommend(context)
        assertEquals(2, recommendations.size)
        val ids = recommendations.map { it.profile.publicKey }
        assertEquals(listOf("user1", "user2"), ids)
    }

    @Test
    fun `ranks peers with shared educational interests higher`() {
        // Local user interested in AI/ML and DSA
        profileStore.updateSelectedTopics(setOf(EducationalTopic.AI_ML, EducationalTopic.DSA))

        val candidateProfiles = listOf(
            profile("alice", "Alice"),
            profile("bob", "Bob")
        )

        // Alice writes about AI/ML & PyTorch
        val alicePost = candidatePost("p1", "alice", "Deep learning transformer neural network implementation with PyTorch.")
        // Bob writes about cooking and lunch (non-educational)
        val bobPost = candidatePost("p2", "bob", "Having a wonderful brunch and lunch at the beach party this weekend.")

        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = candidateProfiles,
            candidatePosts = listOf(alicePost, bobPost)
        )

        val recommendations = recommender.recommend(context)
        assertEquals(2, recommendations.size)
        assertEquals("alice", recommendations[0].profile.publicKey)
        assertTrue(recommendations[0].sharedTopics.contains(EducationalTopic.AI_ML))
        assertTrue(recommendations[0].reason.contains("AI / Machine Learning"))
        assertTrue(recommendations[0].score > recommendations[1].score)
    }

    @Test
    fun `boosts peers with mutual connections or interactions`() {
        profileStore.updateSelectedTopics(emptySet())

        val candidateProfiles = listOf(
            profile("userA", "User A"),
            profile("userB", "User B")
        )

        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = candidateProfiles,
            mutualContactInteractions = mapOf("userA" to 5, "userB" to 0)
        )

        val recommendations = recommender.recommend(context)
        assertEquals("userA", recommendations[0].profile.publicKey)
        assertTrue(recommendations[0].reason.contains("5 mutual interaction(s)"))
        assertTrue(recommendations[0].score > recommendations[1].score)
    }

    @Test
    fun `gives proximity bonus to currently nearby peers`() {
        profileStore.updateSelectedTopics(emptySet())

        val userA = profile("userA", "User A")
        val userB = profile("userB", "User B")

        val nearbyA = PeerProfile(
            profile = userA,
            lastSeenAt = now,
            lastSignalStrength = -40,
            isNearby = true
        )

        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = listOf(userA, userB),
            nearbyPeerProfiles = listOf(nearbyA)
        )

        val recommendations = recommender.recommend(context)
        assertEquals("userA", recommendations[0].profile.publicKey)
        assertTrue(recommendations[0].reason.contains("Currently nearby"))
    }

    @Test
    fun `handles missing candidate posts or peer data safely`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY))

        val candidateProfiles = listOf(
            profile("stranger", "Stranger without posts")
        )

        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = candidateProfiles,
            candidatePosts = emptyList(),
            nearbyPeerProfiles = emptyList()
        )

        val recommendations = recommender.recommend(context)
        assertEquals(1, recommendations.size)
        assertEquals("stranger", recommendations[0].profile.publicKey)
        assertEquals(0.0, recommendations[0].score)
        assertTrue(recommendations[0].sharedTopics.isEmpty())
    }

    @Test
    fun `ranks peers with higher Jaccard similarity on liked educational posts higher`() {
        val user1 = profile("peer1", "Peer One")
        val user2 = profile("peer2", "Peer Two")

        val p1 = candidatePost("p1", "authorX", "Data structures binary search tree algorithms.")
        val p2 = candidatePost("p2", "authorX", "Graph theory and Dijkstra shortest path algorithm.")
        val p3 = candidatePost("p3", "authorX", "Database query optimization and index design.")
        val pCasual = candidatePost("pCasual", "authorY", "Went to beach for weekend party and music festival.")

        // Current user liked p1 and p2
        val myLikes = setOf("p1", "p2", "pCasual")
        // Peer1 liked p1, p2 (Jaccard on educational: 2 / 2 = 1.0)
        val peer1Likes = setOf("p1", "p2")
        // Peer2 liked p2, p3 (Jaccard on educational: 1 / 3 = 0.33)
        val peer2Likes = setOf("p2", "p3")

        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = listOf(user2, user1),
            candidatePosts = listOf(p1, p2, p3, pCasual),
            myLikedPostIds = myLikes,
            peerLikedPostIds = mapOf("peer1" to peer1Likes, "peer2" to peer2Likes)
        )

        val recommendations = recommender.recommend(context)
        assertEquals(2, recommendations.size)
        assertEquals("peer1", recommendations[0].profile.publicKey, "Peer 1 with identical educational liked set should rank first")
        assertTrue(recommendations[0].score > recommendations[1].score)
        assertTrue(recommendations[0].reason.contains("shared liked educational post(s)"))
    }

    @Test
    fun `ignores non-educational liked posts in Jaccard similarity calculation`() {
        val user1 = profile("peer1", "Peer One")

        val pCasual = candidatePost("pCasual", "authorY", "Went to beach for weekend party and music festival.")
        val pEdu = candidatePost("pEdu", "authorX", "Quantum physics and quantum computing algorithms.")

        // Both only liked a casual post
        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = listOf(user1),
            candidatePosts = listOf(pCasual, pEdu),
            myLikedPostIds = setOf("pCasual"),
            peerLikedPostIds = mapOf("peer1" to setOf("pCasual"))
        )

        val recommendations = recommender.recommend(context)
        assertEquals(1, recommendations.size)
        assertFalse(recommendations[0].reason.contains("shared liked educational post(s)"), "Non-educational liked posts must be ignored")
    }

    @Test
    fun `handles cold-start user without liked posts or topic preferences gracefully`() {
        profileStore.updateSelectedTopics(emptySet())
        val user1 = profile("peer1", "Active Contributor")
        val eduPost = candidatePost("p1", "peer1", "Artificial intelligence neural networks and deep learning.")

        val context = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = listOf(user1),
            candidatePosts = listOf(eduPost),
            myLikedPostIds = emptySet(),
            peerLikedPostIds = emptyMap()
        )

        val recommendations = recommender.recommend(context)
        assertEquals(1, recommendations.size)
        assertEquals("peer1", recommendations[0].profile.publicKey)
        assertTrue(recommendations[0].score > 0.0, "Educational contributor gets a modest baseline in cold-start")
    }
}
