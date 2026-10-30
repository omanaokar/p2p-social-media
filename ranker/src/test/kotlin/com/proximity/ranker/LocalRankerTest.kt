package com.proximity.ranker

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.InteractionType
import com.proximity.contracts.Post
import com.proximity.contracts.Profile
import com.proximity.contracts.RankContext
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRankerTest {

    private val ranker = LocalRanker()
    private val baseTime = Instant.parse("2026-10-27T00:00:00Z")

    private fun candidate(
        id: String,
        authorId: String = "author1",
        body: String = "Educational post about algorithms and data structures.",
        createdAt: Instant = baseTime,
        hopCount: Int = 0,
        signal: Int? = -45,
        commentCount: Int = 0,
        reactions: Map<String, Int> = emptyMap(),
        isMine: Boolean = false
    ): FeedCandidate {
        val post = Post(
            id = id,
            authorId = authorId,
            body = body,
            image = null,
            createdAt = createdAt,
            ttl = Duration.ofHours(6),
            hopCount = hopCount,
            signature = "sig-$id"
        )
        val profile = Profile(
            id = "prof-$authorId",
            publicKey = authorId,
            encryptionKey = "enc-$authorId",
            displayName = "User $authorId",
            avatar = null,
            updatedAt = createdAt,
            signature = "sig-prof"
        )
        return FeedCandidate(
            post = post,
            author = profile,
            receivedAt = createdAt,
            receivedFrom = null,
            receivedSignalStrength = signal,
            commentCount = commentCount,
            reactionCounts = reactions,
            myReaction = null,
            isMine = isMine
        )
    }

    @Test
    fun `returns empty list on empty candidate input`() = runBlocking {
        val ranked = ranker.rank(emptyList(), RankContext(baseTime, "me"))
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `handles missing signal strength safely without errors`() = runBlocking {
        val candidate = candidate("post-no-signal", signal = null)
        val ranked = ranker.rank(listOf(candidate), RankContext(baseTime, "me"))

        assertEquals(1, ranked.size)
        assertTrue(ranked.first().score > 0.0)
    }

    @Test
    fun `ranks closer peer with stronger signal higher than distant peer`() = runBlocking {
        val closeCandidate = candidate("close", signal = -35, hopCount = 0)
        val farCandidate = candidate("far", signal = -90, hopCount = 2)

        val ranked = ranker.rank(listOf(farCandidate, closeCandidate), RankContext(baseTime, "me"))

        assertEquals("close", ranked[0].candidate.post.id)
        assertEquals("far", ranked[1].candidate.post.id)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun `ranks fresh post higher than old post with all else equal`() = runBlocking {
        val freshCandidate = candidate("fresh", createdAt = baseTime)
        val oldCandidate = candidate("old", createdAt = baseTime.minus(Duration.ofHours(5)))

        val ranked = ranker.rank(listOf(oldCandidate, freshCandidate), RankContext(baseTime, "me"))

        assertEquals("fresh", ranked[0].candidate.post.id)
        assertEquals("old", ranked[1].candidate.post.id)
    }

    @Test
    fun `learns author preference through interactions and boosts subsequent ranking`() = runBlocking {
        val candidateA = candidate("post-a", authorId = "author-a", createdAt = baseTime)
        val candidateB = candidate("post-b", authorId = "author-b", createdAt = baseTime)

        // Initial ranking
        ranker.rank(listOf(candidateA, candidateB), RankContext(baseTime, "me"))

        // Interact positively with author A (react + comment)
        ranker.recordInteraction("post-a", InteractionType.REACT)
        ranker.recordInteraction("post-a", InteractionType.COMMENT)

        assertTrue(ranker.getAuthorAffinity("author-a") > 0.0)

        val rankedAfter = ranker.rank(listOf(candidateB, candidateA), RankContext(baseTime, "me"))
        assertEquals("post-a", rankedAfter[0].candidate.post.id)
    }

    @Test
    fun `penalizes author preference on HIDE interaction`() = runBlocking {
        val candidateA = candidate("post-a", authorId = "author-a", createdAt = baseTime)
        val candidateB = candidate("post-b", authorId = "author-b", createdAt = baseTime)

        ranker.rank(listOf(candidateA, candidateB), RankContext(baseTime, "me"))
        ranker.recordInteraction("post-a", InteractionType.HIDE)

        assertTrue(ranker.getAuthorAffinity("author-a") < 0.0)

        val ranked = ranker.rank(listOf(candidateA, candidateB), RankContext(baseTime, "me"))
        assertEquals("post-b", ranked[0].candidate.post.id)
    }

    @Test
    fun `boosts posts matching user selected cold-start topics without prior interactions`() = runBlocking {
        val store = InMemoryUserInterestProfileStore()
        val customRanker = LocalRanker(profileStore = store)

        // Two identical candidates, one on Cybersecurity, one on Mathematics
        val cyberPost = candidate(
            id = "cyber",
            authorId = "author-cyber",
            body = "Penetration testing and finding sql injection vulnerabilities in web apps."
        )
        val mathPost = candidate(
            id = "math",
            authorId = "author-math",
            body = "Solving differential equations and finding matrix eigenvalues in calculus."
        )

        val context = RankContext(baseTime, "me")

        // 1. Initially without preferences, both rank according to default scoring
        val initialRank = customRanker.rank(listOf(cyberPost, mathPost), context)
        assertEquals(2, initialRank.size)

        // 2. User selects Cybersecurity as an interest topic
        store.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY))

        val rankedWithCyber = customRanker.rank(listOf(mathPost, cyberPost), context)
        assertEquals("cyber", rankedWithCyber[0].candidate.post.id)
        assertTrue(rankedWithCyber[0].reason.contains("preferred topic"))

        // 3. User switches preference to Mathematics
        store.updateSelectedTopics(setOf(EducationalTopic.MATHEMATICS))

        val rankedWithMath = customRanker.rank(listOf(cyberPost, mathPost), context)
        assertEquals("math", rankedWithMath[0].candidate.post.id)
        assertTrue(rankedWithMath[0].reason.contains("preferred topic"))

        // 4. User clears preferences; ranking operates safely with empty profile
        store.updateSelectedTopics(emptySet())
        val rankedNeutral = customRanker.rank(listOf(cyberPost, mathPost), context)
        assertEquals(2, rankedNeutral.size)
    }

    @Test
    fun `deterministic score stability across multiple rank calls`() = runBlocking {
        val candidates = listOf(
            candidate("p1", signal = -40, commentCount = 2),
            candidate("p2", signal = -75, reactions = mapOf("like" to 5)),
            candidate("p3", signal = -55, hopCount = 1)
        )
        val context = RankContext(baseTime, "me")

        val run1 = ranker.rank(candidates, context)
        val run2 = ranker.rank(candidates, context)

        assertEquals(run1.map { it.candidate.post.id }, run2.map { it.candidate.post.id })
        assertEquals(run1.map { it.score }, run2.map { it.score })
    }

    @Test
    fun `default RankerWeights sum to exactly 1_0`() {
        val weights = RankerWeights()
        assertEquals(1.0, weights.totalWeight, 0.0001)
    }

    @Test
    fun `final ranking scores are strictly bounded within 0 to 1 under maximum signals and exploration bonus`() = runBlocking {
        val store = InMemoryUserInterestProfileStore()
        store.updateSelectedTopics(setOf(EducationalTopic.AI_ML))
        val adaptiveRecommender = com.proximity.ranker.learning.LocalAdaptiveLearningRecommender(profileStore = store)

        // Super-boosted candidate: maximum freshness (age 0), 0 hops, maximum RSSI (-30), massive reactions (50+), author affinity
        val candidate = candidate(
            id = "perfect-post",
            authorId = "author-perfect",
            body = "Deep learning and neural networks PyTorch model implementation with gradient descent.",
            createdAt = baseTime,
            hopCount = 0,
            signal = -30,
            commentCount = 50,
            reactions = mapOf("like" to 100)
        )

        val boundedRanker = LocalRanker(
            profileStore = store,
            adaptiveRecommender = adaptiveRecommender
        )

        // Maximize author affinity
        boundedRanker.rank(listOf(candidate), RankContext(baseTime, "me"))
        boundedRanker.recordInteraction("perfect-post", InteractionType.REACT)
        boundedRanker.recordInteraction("perfect-post", InteractionType.COMMENT)
        boundedRanker.recordInteraction("perfect-post", InteractionType.SAVE)

        val ranked = boundedRanker.rank(listOf(candidate), RankContext(baseTime, "me"))
        assertEquals(1, ranked.size)
        val score = ranked.first().score
        assertTrue(score in 0.0..1.0, "Score $score must be bounded within [0.0, 1.0]")
    }

    @Test
    fun `custom RankerWeights that do not sum to 1_0 are properly normalized`() = runBlocking {
        // Deliberately oversized custom weights summing to 2.5
        val oversizedWeights = RankerWeights(
            signalWeight = 0.50,
            freshnessWeight = 0.50,
            hopCountWeight = 0.30,
            engagementWeight = 0.30,
            authorAffinityWeight = 0.30,
            educationalBoostWeight = 0.30,
            topicPreferenceWeight = 0.30
        )
        val customRanker = LocalRanker(weights = oversizedWeights)

        val candidate = candidate("test-post", signal = -40)
        val ranked = customRanker.rank(listOf(candidate), RankContext(baseTime, "me"))
        assertEquals(1, ranked.size)
        val score = ranked.first().score
        assertTrue(score in 0.0..1.0, "Normalized score $score must be in [0.0, 1.0]")
    }

    @Test
    fun `SAVE interaction updates author affinity and persistent profile`() = runBlocking {
        val store = InMemoryUserInterestProfileStore()
        val customRanker = LocalRanker(profileStore = store)

        val post = candidate(
            id = "post-save",
            authorId = "author-save",
            body = "Algorithms and data structures dynamic programming tutorial."
        )

        customRanker.rank(listOf(post), RankContext(baseTime, "me"))
        customRanker.recordInteraction("post-save", InteractionType.SAVE)

        // Author affinity should be positive (+0.30)
        assertTrue(customRanker.getAuthorAffinity("author-save") > 0.25)

        // Topic score for DSA should have increased by +0.20
        val profile = store.getProfile()
        assertEquals(0.70, profile.getInterestScore(EducationalTopic.DSA), 0.001)
        assertEquals(1, profile.interactionCounts[EducationalTopic.DSA])
    }

    @Test
    fun `uncached post interaction via explicit Post updates affinity and profile`() = runBlocking {
        val store = InMemoryUserInterestProfileStore()
        val customRanker = LocalRanker(profileStore = store)

        val uncachedPost = candidate(
            id = "uncached-1",
            authorId = "author-uncached",
            body = "Cybersecurity penetration testing and ethical hacking tutorial."
        ).post

        // Never called rank() on uncachedPost! Directly record interaction with explicit Post
        customRanker.recordInteraction(uncachedPost, InteractionType.REACT)

        // Author affinity must be updated
        assertEquals(0.25, customRanker.getAuthorAffinity("author-uncached"), 0.001)

        // Interest profile must be updated
        val profile = store.getProfile()
        assertEquals(0.65, profile.getInterestScore(EducationalTopic.CYBERSECURITY), 0.001)
        assertEquals(1, profile.interactionCounts[EducationalTopic.CYBERSECURITY])
    }

    @Test
    fun `uncached post interaction via postResolver updates affinity and profile`() = runBlocking {
        val store = InMemoryUserInterestProfileStore()
        val resolvedPost = candidate(
            id = "resolver-post",
            authorId = "author-resolved",
            body = "Mathematics calculus differential equations notes."
        ).post

        val customRanker = LocalRanker(
            profileStore = store,
            postResolver = { id -> if (id == "resolver-post") resolvedPost else null }
        )

        // Never called rank() on resolver-post! Directly record interaction with ID only
        customRanker.recordInteraction("resolver-post", InteractionType.SAVE)

        assertEquals(0.30, customRanker.getAuthorAffinity("author-resolved"), 0.001)

        val profile = store.getProfile()
        assertEquals(0.70, profile.getInterestScore(EducationalTopic.MATHEMATICS), 0.001)
    }

    @Test
    fun `repeated legitimate interactions on the same post are recorded and progressively damped`() = runBlocking {
        val store = InMemoryUserInterestProfileStore()
        val customRanker = LocalRanker(profileStore = store)

        val post = candidate(
            id = "repeated-post",
            authorId = "author-rep",
            body = "Programming in Rust and concurrency concepts."
        )

        customRanker.rank(listOf(post), RankContext(baseTime, "me"))

        // Multiple legitimate comments on the same post (e.g. back-and-forth discussion)
        customRanker.recordInteraction("repeated-post", InteractionType.COMMENT)
        assertEquals(0.35, customRanker.getAuthorAffinity("author-rep"), 0.001)
        assertEquals(1, store.getProfile().interactionCounts[EducationalTopic.PROGRAMMING])
        val score1 = store.getProfile().getInterestScore(EducationalTopic.PROGRAMMING)

        // Second comment on the same post
        customRanker.recordInteraction("repeated-post", InteractionType.COMMENT)
        assertEquals(0.70, customRanker.getAuthorAffinity("author-rep"), 0.001)
        assertEquals(2, store.getProfile().interactionCounts[EducationalTopic.PROGRAMMING])
        val score2 = store.getProfile().getInterestScore(EducationalTopic.PROGRAMMING)
        assertTrue(score2 > score1, "Second comment must increase topic interest")

        // Delta should be damped (diminishing return)
        val delta1 = score1 - 0.50
        val delta2 = score2 - score1
        assertTrue(delta2 < delta1, "Damping must reduce gain on repeated interaction: delta2 ($delta2) < delta1 ($delta1)")
    }

    @Test
    fun `duplicate event dispatch with same eventId is idempotent`() = runBlocking {
        val store = InMemoryUserInterestProfileStore()
        val customRanker = LocalRanker(profileStore = store)

        val post = candidate(
            id = "idempotent-post",
            authorId = "author-idem",
            body = "Cloud computing architectures and Docker containerization."
        )

        customRanker.rank(listOf(post), RankContext(baseTime, "me"))

        val eventKey = "event-rx-987"

        // First dispatch of eventKey
        customRanker.recordInteraction("idempotent-post", InteractionType.REACT, eventId = eventKey)
        assertEquals(0.25, customRanker.getAuthorAffinity("author-idem"), 0.001)
        assertEquals(1, store.getProfile().interactionCounts[EducationalTopic.CLOUD_COMPUTING])
        assertTrue(customRanker.isEventProcessed(eventKey))

        // Duplicate dispatch with exact same eventKey
        customRanker.recordInteraction("idempotent-post", InteractionType.REACT, eventId = eventKey)

        // Affinity and counts must remain unchanged
        assertEquals(0.25, customRanker.getAuthorAffinity("author-idem"), 0.001)
        assertEquals(1, store.getProfile().interactionCounts[EducationalTopic.CLOUD_COMPUTING])

        // Different eventId for another interaction on the same post succeeds
        val secondEventKey = "event-save-988"
        customRanker.recordInteraction("idempotent-post", InteractionType.SAVE, eventId = secondEventKey)
        assertEquals(0.55, customRanker.getAuthorAffinity("author-idem"), 0.001)
        assertEquals(2, store.getProfile().interactionCounts[EducationalTopic.CLOUD_COMPUTING])
        assertTrue(customRanker.isEventProcessed(secondEventKey))
    }

    @Test
    fun `SAVE weighting reflects deliberate commitment hierarchy`() = runBlocking {
        // Hierarchy: OPEN < REACT < SAVE < COMMENT
        // Topic interest: OPEN (0.08) < REACT (0.15) < SAVE (0.20) < COMMENT (0.25)
        // Author affinity: OPEN (0.10) < REACT (0.25) < SAVE (0.30) < COMMENT (0.35)

        val post = candidate("hierarchy-post", authorId = "author-h", body = "Data structures and algorithms.").post

        val rankerSave = LocalRanker(profileStore = InMemoryUserInterestProfileStore())
        val rankerReact = LocalRanker(profileStore = InMemoryUserInterestProfileStore())
        val rankerComment = LocalRanker(profileStore = InMemoryUserInterestProfileStore())

        rankerSave.recordInteraction(post, InteractionType.SAVE)
        rankerReact.recordInteraction(post, InteractionType.REACT)
        rankerComment.recordInteraction(post, InteractionType.COMMENT)

        // Author affinity check
        val affinityReact = rankerReact.getAuthorAffinity("author-h")
        val affinitySave = rankerSave.getAuthorAffinity("author-h")
        val affinityComment = rankerComment.getAuthorAffinity("author-h")
        assertTrue(affinityReact < affinitySave && affinitySave < affinityComment,
            "Author affinity hierarchy: $affinityReact (REACT) < $affinitySave (SAVE) < $affinityComment (COMMENT)")

        // Topic interest check
        val topicReact = rankerReact.profileStore.getProfile().getInterestScore(EducationalTopic.DSA)
        val topicSave = rankerSave.profileStore.getProfile().getInterestScore(EducationalTopic.DSA)
        val topicComment = rankerComment.profileStore.getProfile().getInterestScore(EducationalTopic.DSA)
        assertTrue(topicReact < topicSave && topicSave < topicComment,
            "Topic interest hierarchy: $topicReact (REACT) < $topicSave (SAVE) < $topicComment (COMMENT)")
    }

    @Test
    fun `custom RankerWeights rejects invalid weights with IllegalArgumentException`() {
        // Negative weight
        assertFailsWith<IllegalArgumentException> {
            RankerWeights(signalWeight = -0.1)
        }

        // NaN weight
        assertFailsWith<IllegalArgumentException> {
            RankerWeights(freshnessWeight = Double.NaN)
        }

        // Infinite weight
        assertFailsWith<IllegalArgumentException> {
            RankerWeights(hopCountWeight = Double.POSITIVE_INFINITY)
        }

        // All zero weights
        assertFailsWith<IllegalArgumentException> {
            RankerWeights(
                signalWeight = 0.0,
                freshnessWeight = 0.0,
                hopCountWeight = 0.0,
                engagementWeight = 0.0,
                authorAffinityWeight = 0.0,
                educationalBoostWeight = 0.0,
                topicPreferenceWeight = 0.0
            )
        }
    }
}
