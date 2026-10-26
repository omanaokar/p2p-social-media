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
}
