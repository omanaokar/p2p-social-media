package com.proximity.ranker.learning

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.InteractionType
import com.proximity.contracts.Post
import com.proximity.contracts.Profile
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import com.proximity.ranker.studygroup.LocalStudyGroupMatcher
import com.proximity.ranker.studygroup.StudyGroupMatchingContext
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptiveLearningRecommenderTest {

    private val profileStore = InMemoryUserInterestProfileStore()
    private val recommender = LocalAdaptiveLearningRecommender(profileStore = profileStore)
    private val now = Instant.parse("2026-10-27T00:00:00Z")

    private fun profile(userId: String): Profile = Profile(
        id = "id-$userId",
        publicKey = userId,
        encryptionKey = "enc-$userId",
        displayName = "User $userId",
        avatar = null,
        updatedAt = now,
        signature = "sig-$userId"
    )

    private fun candidate(
        id: String,
        body: String,
        authorId: String = "author1",
        createdAt: Instant = now,
        ttlHours: Long = 6,
        rssi: Int? = -45,
        commentCount: Int = 0,
        reactionCounts: Map<String, Int> = emptyMap()
    ): FeedCandidate {
        val post = Post(
            id = id,
            authorId = authorId,
            body = body,
            image = null,
            createdAt = createdAt,
            ttl = Duration.ofHours(ttlHours),
            hopCount = 0,
            signature = "sig-$id"
        )
        return FeedCandidate(
            post = post,
            author = profile(authorId),
            receivedAt = createdAt,
            receivedFrom = null,
            receivedSignalStrength = rssi,
            commentCount = commentCount,
            reactionCounts = reactionCounts,
            myReaction = null,
            isMine = false
        )
    }

    @Test
    fun `cold-start user with selected topics ranks underexplored interests highest`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.AI_ML, EducationalTopic.CYBERSECURITY))

        val recs = recommender.recommendTopics(AdaptiveLearningContext(now = now))
        assertTrue(recs.isNotEmpty())

        val topTopics = recs.take(2).map { it.topic }.toSet()
        assertTrue(topTopics.contains(EducationalTopic.AI_ML))
        assertTrue(topTopics.contains(EducationalTopic.CYBERSECURITY))

        val aiRec = recs.first { it.topic == EducationalTopic.AI_ML }
        assertEquals(ExplorationStatus.UNDEREXPLORED, aiRec.status)
        assertTrue(aiRec.explanation.contains("0 recorded interactions"))
    }

    @Test
    fun `complete cold-start user with no selected topics falls back to foundational curriculum`() {
        profileStore.updateSelectedTopics(emptySet())

        val recs = recommender.recommendTopics(AdaptiveLearningContext(now = now))
        val top3 = recs.take(3).map { it.topic }.toSet()

        // Foundational starter topics: PROGRAMMING, DSA, MATHEMATICS
        assertTrue(top3.contains(EducationalTopic.PROGRAMMING))
        assertTrue(top3.contains(EducationalTopic.DSA))
        assertTrue(top3.contains(EducationalTopic.MATHEMATICS))

        val programmingRec = recs.first { it.topic == EducationalTopic.PROGRAMMING }
        assertEquals(ExplorationStatus.EXPLORATION_OPPORTUNITY, programmingRec.status)
        assertTrue(programmingRec.explanation.contains("Foundational starter subject"))
    }

    @Test
    fun `identifies underexplored topics with zero engagement vs heavily engaged topics`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.DSA, EducationalTopic.CLOUD_COMPUTING))

        // Active positive engagement in DSA
        val dsaPost = candidate("p1", "Data structures and algorithms: binary search tree implementation.").post
        repeat(2) {
            recommender.recordInteraction(dsaPost, InteractionType.REACT)
            recommender.recordInteraction(dsaPost, InteractionType.COMMENT)
        }

        // Zero interactions in CLOUD_COMPUTING
        val recs = recommender.recommendTopics(AdaptiveLearningContext(now = now))

        val cloudRec = recs.first { it.topic == EducationalTopic.CLOUD_COMPUTING }
        val dsaRec = recs.first { it.topic == EducationalTopic.DSA }

        assertEquals(ExplorationStatus.UNDEREXPLORED, cloudRec.status)
        assertEquals(ExplorationStatus.ACTIVE_STUDY, dsaRec.status)
        // Underexplored selected topic receives higher exploration priority score
        assertTrue(cloudRec.score > dsaRec.score)
    }

    @Test
    fun `sparse interaction data is safely handled without errors`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.PROGRAMMING))

        // Single open interaction with 10 seconds dwell
        val post = candidate("p1", "Kotlin programming coroutines and structured concurrency.").post
        recommender.recordInteraction(post, InteractionType.OPEN, Duration.ofSeconds(10))

        val engagement = recommender.getTopicEngagement(EducationalTopic.PROGRAMMING)
        assertEquals(1, engagement.openCount)
        assertEquals(10L, engagement.totalDwellSeconds)
        assertTrue(engagement.engagementScore > 0.0)

        val recs = recommender.recommendTopics(AdaptiveLearningContext(now = now))
        assertTrue(recs.isNotEmpty())
    }

    @Test
    fun `dismissed or hidden topic is marked as underexplored with respectful explanation`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.MATHEMATICS))

        val mathPost = candidate("p1", "Linear algebra matrix transformations and eigenvectors.").post
        recommender.recordInteraction(mathPost, InteractionType.HIDE)

        val recs = recommender.recommendTopics(AdaptiveLearningContext(now = now))
        val mathRec = recs.first { it.topic == EducationalTopic.MATHEMATICS }

        assertEquals(ExplorationStatus.UNDEREXPLORED, mathRec.status)
        assertTrue(mathRec.explanation.contains("dismissal(s) in local history"))
        assertFalse(mathRec.explanation.contains("low ability"))
        assertFalse(mathRec.explanation.contains("failed"))
    }

    @Test
    fun `actively studied topic suggests adjacent learning pathways`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.PROGRAMMING))

        val post = candidate("p1", "Object oriented programming and functions in Python.").post
        repeat(4) {
            recommender.recordInteraction(post, InteractionType.REACT)
            recommender.recordInteraction(post, InteractionType.COMMENT)
        }

        val recs = recommender.recommendTopics(AdaptiveLearningContext(now = now))

        // DSA is adjacent to PROGRAMMING
        val dsaRec = recs.first { it.topic == EducationalTopic.DSA }
        assertEquals(ExplorationStatus.EXPLORATION_OPPORTUNITY, dsaRec.status)
        assertTrue(dsaRec.explanation.contains("progression from active focus in Programming"))
    }

    @Test
    fun `saturated topic scores lower to encourage branching out`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.AI_ML))

        val post = candidate("p1", "Deep learning neural network PyTorch model training.").post
        repeat(15) {
            recommender.recordInteraction(post, InteractionType.COMMENT)
            recommender.recordInteraction(post, InteractionType.REACT)
        }

        val recs = recommender.recommendTopics(AdaptiveLearningContext(now = now))
        val aiRec = recs.first { it.topic == EducationalTopic.AI_ML }

        assertEquals(ExplorationStatus.SATURATED, aiRec.status)
        assertTrue(aiRec.explanation.contains("well-explored locally"))
        assertTrue(aiRec.score < 0.60)
    }

    @Test
    fun `recommendPosts excludes posts in seenPostIds to prevent repeating content`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.DSA))

        val post1 = candidate("p1", "Data structures: AVL tree balancing and rotations.")
        val post2 = candidate("p2", "Data structures: QuickSort and MergeSort algorithm complexity.")

        val contextWithSeen = AdaptiveLearningContext(
            candidatePosts = listOf(post1, post2),
            seenPostIds = setOf("p1"),
            now = now
        )

        val recommendations = recommender.recommendPosts(contextWithSeen)
        assertEquals(1, recommendations.size)
        assertEquals("p2", recommendations.first().candidate.post.id)
    }

    @Test
    fun `recommendPosts excludes expired posts`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.DSA))

        val validPost = candidate("p1", "Binary search tree in Kotlin.", createdAt = now, ttlHours = 4)
        val expiredPost = candidate("p2", "Graph BFS algorithm in Kotlin.", createdAt = now.minus(Duration.ofHours(10)), ttlHours = 2)

        val recommendations = recommender.recommendPosts(
            AdaptiveLearningContext(
                candidatePosts = listOf(validPost, expiredPost),
                now = now
            )
        )

        assertEquals(1, recommendations.size)
        assertEquals("p1", recommendations.first().candidate.post.id)
    }

    @Test
    fun `recommendPosts handles empty candidate list safely`() {
        val recs = recommender.recommendPosts(AdaptiveLearningContext(candidatePosts = emptyList()))
        assertTrue(recs.isEmpty())
    }

    @Test
    fun `recommendPosts handles missing signals safely without crashing`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY))

        val postNoSignal = candidate(
            id = "p1",
            body = "Network security firewall rules and TLS encryption.",
            rssi = null,
            commentCount = 0,
            reactionCounts = emptyMap()
        )

        val recs = recommender.recommendPosts(
            AdaptiveLearningContext(candidatePosts = listOf(postNoSignal), now = now)
        )

        assertEquals(1, recs.size)
        assertTrue(recs.first().score > 0.0)
    }

    @Test
    fun `author diversity limits consecutive posts from same author`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.PROGRAMMING))

        // 4 posts by authorA and 1 by authorB
        val a1 = candidate("p1", "Kotlin functions tutorial.", authorId = "authorA")
        val a2 = candidate("p2", "Kotlin classes tutorial.", authorId = "authorA")
        val a3 = candidate("p3", "Kotlin coroutines tutorial.", authorId = "authorA")
        val a4 = candidate("p4", "Kotlin collections tutorial.", authorId = "authorA")
        val b1 = candidate("p5", "Java lambdas tutorial.", authorId = "authorB")

        val recs = recommender.recommendPosts(
            AdaptiveLearningContext(candidatePosts = listOf(a1, a2, a3, a4, b1), now = now),
            limit = 5
        )

        // Count occurrences of authorA in recommendations
        val authorACount = recs.count { it.candidate.post.authorId == "authorA" }
        assertTrue(authorACount <= 2, "Author diversity should throttle consecutive posts from same author")
    }

    @Test
    fun `suggestStudyGroupForTopic links adaptive recommendations with study-group matcher`() {
        val studyGroupMatcher = LocalStudyGroupMatcher(profileStore = profileStore)
        val peer = profile("peer1")
        val peerPost = candidate("post-peer", "Data structures and algorithms tree traversals.", authorId = "peer1")

        val matchingContext = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(peer),
            candidatePosts = listOf(peerPost)
        )

        val cohort = recommender.suggestStudyGroupForTopic(
            topic = EducationalTopic.DSA,
            studyGroupMatcher = studyGroupMatcher,
            matchingContext = matchingContext,
            maxMembers = 4
        )

        assertNotNull(cohort)
        assertEquals(EducationalTopic.DSA, cohort.topic)
        assertEquals(1, cohort.members.size)
    }
}
