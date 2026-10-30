package com.proximity.ranker.integration

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.InteractionType
import com.proximity.contracts.Post
import com.proximity.contracts.Profile
import com.proximity.contracts.RankContext
import com.proximity.ranker.RecommendationSystem
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.learning.AdaptiveLearningContext
import com.proximity.ranker.learning.ExplorationStatus
import com.proximity.ranker.recommendation.PeerRecommendationContext
import com.proximity.ranker.studygroup.StudyGoal
import com.proximity.ranker.studygroup.StudyGroupMatchingContext
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecommendationSystemIntegrationTest {

    private val now = Instant.now()

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
    fun `educational classification feeds into feed ranking and boosts preferred topic`() = runBlocking {
        val system = RecommendationSystem()
        // Set user cold-start interest in AI/ML
        system.profileStore.updateSelectedTopics(setOf(EducationalTopic.AI_ML))

                val aiPost = candidate("p1", "Deep neural network backpropagation and PyTorch gradient descent.")
        val otherEduPost = candidate("p2", "Kotlin coroutines structured concurrency and memory management tutorial.")

        val ranked = system.rankFeed(listOf(otherEduPost, aiPost), RankContext(now, "me"))

        assertEquals(2, ranked.size)
        assertEquals("p1", ranked[0].candidate.post.id, "AI/ML post should rank highest due to topic match")
        assertTrue(ranked[0].score > ranked[1].score)
        assertTrue(ranked[0].reason.contains("AI / Machine Learning (preferred topic)"))

        val casualPost = candidate("p3", "Having a wonderful weekend brunch with friends at the park.")
        val rankedWithCasual = system.rankFeed(listOf(casualPost, aiPost), RankContext(now, "me"))
        assertEquals(1, rankedWithCasual.size, "Casual content must be excluded from feed ranking")
        assertEquals("p1", rankedWithCasual[0].candidate.post.id)
    }

    @Test
    fun `content moderation rejects adult posts from feed ranking`() = runBlocking {
        val system = RecommendationSystem()

        val eduPost = candidate("p1", "Algorithms for graph traversal: Breadth first search.")
        val adultPost = candidate("p2", "Explicit pornography xxx adult content video download.")

        val ranked = system.rankFeed(listOf(eduPost, adultPost), RankContext(now, "me"))

        assertEquals(1, ranked.size, "Adult content must be excluded from feed ranking")
        assertEquals("p1", ranked.first().candidate.post.id)
    }

    @Test
    fun `content moderation rejects adult posts from search results`() {
        val system = RecommendationSystem()

        val eduPost = candidate("p1", "Computer science algorithms and data structures analysis.")
        val adultPost = candidate("p2", "Adult xxx video search for algorithms.")

        val results = system.search("algorithms", listOf(eduPost, adultPost))

        assertEquals(1, results.size, "Adult content matching query keywords must be excluded from search")
        assertEquals("p1", results.first().candidate.post.id)
    }

    @Test
    fun `educational classification provides topic bonus in search results`() {
        val system = RecommendationSystem()

        val eduPost = candidate("p1", "Data structures: Binary search tree and AVL balanced tree algorithms.")
        val casualPost = candidate("p2", "Camping trip near the big oak tree.")

        val results = system.search("tree", listOf(casualPost, eduPost))

        assertTrue(results.isNotEmpty())
        assertEquals("p1", results.first().candidate.post.id, "Educational post should receive topic bonus")
        assertTrue(results.first().explanation.contains("Data Structures & Algorithms"))
    }

    @Test
    fun `feed interaction recording flows into adaptive learning topic model`() = runBlocking {
        val system = RecommendationSystem()
        system.profileStore.updateSelectedTopics(setOf(EducationalTopic.DSA))

        val dsaCandidate = candidate("p-dsa", "Binary search tree recursion and algorithmic complexity.")
        // Feed ranks the post, caching it internally
        system.rankFeed(listOf(dsaCandidate), RankContext(now, "me"))

        // User interacts with the post in feed
        system.recordFeedInteraction("p-dsa", InteractionType.REACT)
        system.recordFeedInteraction("p-dsa", InteractionType.COMMENT)

        // Verify adaptive recommender received interaction and updated engagement
        val engagement = system.adaptiveRecommender.getTopicEngagement(EducationalTopic.DSA)
        assertEquals(1, engagement.reactCount)
        assertEquals(1, engagement.commentCount)
        assertTrue(engagement.engagementScore > 0.0)

        // Check topic recommendation reflects active engagement
        val topics = system.recommendTopics(AdaptiveLearningContext(now = now))
        val dsaRec = topics.first { it.topic == EducationalTopic.DSA }
        assertEquals(ExplorationStatus.ACTIVE_STUDY, dsaRec.status)
        assertTrue(dsaRec.explanation.contains("Active learning focus"))
    }

    @Test
    fun `feed dismissal records negative signal in adaptive learning model`() = runBlocking {
        val system = RecommendationSystem()
        system.profileStore.updateSelectedTopics(setOf(EducationalTopic.MATHEMATICS))

        val mathCandidate = candidate("p-math", "Differential calculus and continuous functions.")
        system.rankFeed(listOf(mathCandidate), RankContext(now, "me"))

        // User hides the post
        system.recordFeedInteraction("p-math", InteractionType.HIDE)

        val topics = system.recommendTopics(AdaptiveLearningContext(now = now))
        val mathRec = topics.first { it.topic == EducationalTopic.MATHEMATICS }

        assertEquals(ExplorationStatus.UNDEREXPLORED, mathRec.status)
        assertTrue(mathRec.explanation.contains("1 dismissal(s) in local history"))
    }

    @Test
    fun `cold-start user with no selected topics receives fallback foundational curriculum`() {
        val system = RecommendationSystem()
        system.profileStore.updateSelectedTopics(emptySet())

        val topics = system.recommendTopics(AdaptiveLearningContext(now = now))
        val top3 = topics.take(3).map { it.topic }.toSet()

        assertTrue(top3.contains(EducationalTopic.PROGRAMMING))
        assertTrue(top3.contains(EducationalTopic.DSA))
        assertTrue(top3.contains(EducationalTopic.MATHEMATICS))
    }

    @Test
    fun `cold-start user with selected topics receives synchronized prioritization across components`() = runBlocking {
        val system = RecommendationSystem()
        system.profileStore.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY))
        system.profileStore.updateStudyGoals(setOf(StudyGoal.EXAM_PREP))

        val cyberCandidate = candidate("p-cyber", "Network vulnerability scanning and penetration testing firewall.")
        val dsaCandidate = candidate("p-dsa", "Data structures array sorting algorithms.")

        // 1. Feed Ranking prioritizes cybersecurity
        val ranked = system.rankFeed(listOf(dsaCandidate, cyberCandidate), RankContext(now, "me"))
        assertEquals("p-cyber", ranked[0].candidate.post.id)

        // 2. Peer Recommendation prioritizes cybersecurity contributor
        val peerContext = PeerRecommendationContext(
            currentUserId = "me",
            candidateProfiles = listOf(profile("author-cyber"), profile("author-dsa")),
            candidatePosts = listOf(
                candidate("p-c", "Network security firewall.", authorId = "author-cyber"),
                candidate("p-d", "QuickSort algorithm.", authorId = "author-dsa")
            )
        )
        val peers = system.recommendPeers(peerContext)
        assertEquals("author-cyber", peers[0].profile.publicKey)

        // 3. Adaptive Learning marks cybersecurity as top underexplored priority
        val adaptiveTopics = system.recommendTopics(AdaptiveLearningContext(now = now))
        assertEquals(EducationalTopic.CYBERSECURITY, adaptiveTopics[0].topic)
        assertEquals(ExplorationStatus.UNDEREXPLORED, adaptiveTopics[0].status)

        // 4. Study Group matching prioritizes cybersecurity partner
        val studyContext = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(profile("author-cyber"), profile("author-dsa")),
            candidatePosts = listOf(
                candidate("p-c", "Network security firewall test revision exam prep.", authorId = "author-cyber"),
                candidate("p-d", "QuickSort algorithm.", authorId = "author-dsa")
            )
        )
        val matches = system.matchStudyPartners(studyContext)
        assertEquals("author-cyber", matches[0].profile.publicKey)
    }

    @Test
    fun `adaptive learning recommendations bridge to study group cohort formation`() {
        val system = RecommendationSystem()

        val peer1 = profile("peer1")
        val peer2 = profile("peer2")
        val posts = listOf(
            candidate("p1", "Deep learning transformer neural network.", authorId = "peer1"),
            candidate("p2", "PyTorch deep neural network model.", authorId = "peer2")
        )

        val studyContext = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(peer1, peer2),
            candidatePosts = posts
        )

        val cohort = system.formStudyGroup(
            topic = EducationalTopic.AI_ML,
            context = studyContext,
            maxMembers = 2
        )

        assertNotNull(cohort)
        assertEquals(EducationalTopic.AI_ML, cohort.topic)
        assertEquals(2, cohort.members.size)
        assertTrue(cohort.averageScore > 0.0)
    }

    @Test
    fun `adaptive learning post recommendations suppress previously seen posts`() {
        val system = RecommendationSystem()
        system.profileStore.updateSelectedTopics(setOf(EducationalTopic.DSA))

        val post1 = candidate("seen-post", "Binary search tree and heap algorithms.")
        val post2 = candidate("unseen-post", "Graph topological sort and shortest path algorithms.")

        val context = AdaptiveLearningContext(
            candidatePosts = listOf(post1, post2),
            seenPostIds = setOf("seen-post"),
            now = now
        )

        val recommendations = system.recommendAdaptivePosts(context)
        assertEquals(1, recommendations.size)
        assertEquals("unseen-post", recommendations.first().candidate.post.id)
    }
}
