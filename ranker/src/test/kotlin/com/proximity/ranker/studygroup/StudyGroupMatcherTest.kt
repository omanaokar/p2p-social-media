package com.proximity.ranker.studygroup

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyGroupMatcherTest {

    private val profileStore = InMemoryUserInterestProfileStore()
    private val matcher = LocalStudyGroupMatcher(profileStore = profileStore)
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
    fun `returns empty list on empty candidate profiles`() {
        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = emptyList()
        )
        val matches = matcher.match(context)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `excludes current user, blocked users, and existing friends by default`() {
        val candidates = listOf(
            profile("me", "Myself"),
            profile("friend1", "Existing Friend"),
            profile("blocked1", "Blocked Peer"),
            profile("candidate1", "Valid Peer")
        )

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = candidates,
            existingFriendIds = setOf("friend1"),
            blockedUserIds = setOf("blocked1"),
            excludeExistingFriends = true
        )

        val matches = matcher.match(context)
        assertEquals(1, matches.size)
        assertEquals("candidate1", matches.first().profile.publicKey)
    }

    @Test
    fun `does not exclude existing friends when excludeExistingFriends is false`() {
        val candidates = listOf(
            profile("friend1", "Study Buddy Friend"),
            profile("stranger1", "Stranger")
        )

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = candidates,
            existingFriendIds = setOf("friend1"),
            excludeExistingFriends = false
        )

        val matches = matcher.match(context)
        assertEquals(2, matches.size)
        assertTrue(matches.any { it.profile.publicKey == "friend1" })
    }

    @Test
    fun `deduplicates candidate profiles by public key and filters blank keys`() {
        val candidates = listOf(
            profile("user1", "User One"),
            profile("user1", "Duplicate User One"),
            profile("user2", "User Two"),
            profile("", "Blank User")
        )

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = candidates
        )

        val matches = matcher.match(context)
        assertEquals(2, matches.size)
        val ids = matches.map { it.profile.publicKey }
        assertEquals(listOf("user1", "user2"), ids)
    }

    @Test
    fun `ranks peers with shared educational interests higher`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.AI_ML, EducationalTopic.PROGRAMMING))

        val alice = profile("alice", "Alice")
        val bob = profile("bob", "Bob")

        // Alice publishes AI/ML and Python programming content
        val alicePost = candidatePost(
            "p1", "alice",
            "PyTorch deep learning model tutorial for neural network image classification using Python."
        )
        // Bob publishes about non-educational food topics
        val bobPost = candidatePost(
            "p2", "bob",
            "Going out for lunch and coffee today with friends at the cafe."
        )

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(alice, bob),
            candidatePosts = listOf(alicePost, bobPost)
        )

        val matches = matcher.match(context)
        assertEquals(2, matches.size)
        assertEquals("alice", matches[0].profile.publicKey)
        assertTrue(matches[0].sharedTopics.contains(EducationalTopic.AI_ML))
        assertTrue(matches[0].score > matches[1].score)
        assertTrue(matches[0].explanation.contains("Shares"))
    }

    @Test
    fun `candidates with no shared interests score 0_0 and have clear explanation`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY))

        val bob = profile("bob", "Bob")
        val bobPost = candidatePost("p2", "bob", "Delicious pizza recipe and coffee tasting.")

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(bob),
            candidatePosts = listOf(bobPost),
            requireTopicOverlap = false
        )

        val matches = matcher.match(context)
        assertEquals(1, matches.size)
        assertEquals(0.0, matches[0].score)
        assertTrue(matches[0].sharedTopics.isEmpty())
        assertTrue(matches[0].explanation.contains("No shared educational topics identified"))
    }

    @Test
    fun `requireTopicOverlap flag filters out candidates with no shared interests`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY))

        val alice = profile("alice", "Alice")
        val bob = profile("bob", "Bob")

        val alicePost = candidatePost("p1", "alice", "Cryptography, TLS handshake, and network vulnerability security analysis.")
        val bobPost = candidatePost("p2", "bob", "Casual weekend chat about movies.")

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(alice, bob),
            candidatePosts = listOf(alicePost, bobPost),
            requireTopicOverlap = true
        )

        val matches = matcher.match(context)
        assertEquals(1, matches.size)
        assertEquals("alice", matches.first().profile.publicKey)
    }

    @Test
    fun `boosts candidates with compatible study goals`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.DSA))
        profileStore.updateStudyGoals(setOf(StudyGoal.PROBLEM_SOLVING, StudyGoal.EXAM_PREP))

        val alice = profile("alice", "Alice")
        val bob = profile("bob", "Bob")

        val alicePost = candidatePost(
            "p1", "alice",
            "Data structures and algorithms: binary search tree revision for upcoming midterm exam."
        )
        val bobPost = candidatePost(
            "p2", "bob",
            "Data structures and algorithms: binary search tree tutorial."
        )

        // Alice explicitly shares PROBLEM_SOLVING and EXAM_PREP goals
        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(alice, bob),
            candidatePosts = listOf(alicePost, bobPost),
            candidateStudyGoals = mapOf(
                "alice" to setOf(StudyGoal.EXAM_PREP, StudyGoal.PROBLEM_SOLVING),
                "bob" to setOf(StudyGoal.RESEARCH_AND_READING)
            )
        )

        val matches = matcher.match(context)
        assertEquals(2, matches.size)
        assertEquals("alice", matches[0].profile.publicKey)
        assertTrue(matches[0].sharedGoals.contains(StudyGoal.EXAM_PREP))
        assertTrue(matches[0].score > matches[1].score)
        assertTrue(matches[0].explanation.contains("Compatible goal(s)"))
    }

    @Test
    fun `infers study goals from candidate post text using keywords`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.PROGRAMMING))
        profileStore.updateStudyGoals(setOf(StudyGoal.PROJECT_COLLABORATION))

        val alice = profile("alice", "Alice")
        // Post text mentions project collaboration keywords
        val alicePost = candidatePost(
            "p1", "alice",
            "Building an open source GitHub project in Kotlin! Looking to collaborate with teammates."
        )

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(alice),
            candidatePosts = listOf(alicePost)
        )

        val matches = matcher.match(context)
        assertEquals(1, matches.size)
        assertTrue(matches[0].sharedGoals.contains(StudyGoal.PROJECT_COLLABORATION))
        assertTrue(matches[0].explanation.contains("Project Collaboration"))
    }

    @Test
    fun `boosts candidates who are more active in shared topic`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.MATHEMATICS))

        val alice = profile("alice", "Alice")
        val bob = profile("bob", "Bob")

        // Alice authors 3 calculus/algebra posts
        val a1 = candidatePost("p1", "alice", "Calculus derivative rules and limits.")
        val a2 = candidatePost("p2", "alice", "Linear algebra matrix transformations.")
        val a3 = candidatePost("p3", "alice", "Discrete mathematics probability distributions.")

        // Bob authors 1 post
        val b1 = candidatePost("p4", "bob", "Calculus integration techniques.")

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(alice, bob),
            candidatePosts = listOf(a1, a2, a3, b1)
        )

        val matches = matcher.match(context)
        assertEquals(2, matches.size)
        assertEquals("alice", matches[0].profile.publicKey)
        assertTrue(matches[0].activeTopicPosts > matches[1].activeTopicPosts)
        assertTrue(matches[0].score > matches[1].score)
    }

    @Test
    fun `gives proximity bonus to nearby candidates`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.CLOUD_COMPUTING))

        val alice = profile("alice", "Alice")
        val bob = profile("bob", "Bob")

        val postA = candidatePost("p1", "alice", "Kubernetes container orchestration cloud computing setup.")
        val postB = candidatePost("p2", "bob", "Docker containers and cloud computing architecture.")

        val nearbyAlice = PeerProfile(
            profile = alice,
            lastSeenAt = now,
            lastSignalStrength = -42,
            isNearby = true
        )

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(alice, bob),
            candidatePosts = listOf(postA, postB),
            nearbyPeerProfiles = listOf(nearbyAlice)
        )

        val matches = matcher.match(context)
        assertEquals("alice", matches[0].profile.publicKey)
        assertTrue(matches[0].isNearby)
        assertFalse(matches[1].isNearby)
        assertTrue(matches[0].score > matches[1].score)
        assertTrue(matches[0].explanation.contains("Currently nearby"))
    }

    @Test
    fun `handles missing candidate posts and unknown peers safely`() {
        profileStore.updateSelectedTopics(setOf(EducationalTopic.DSA))

        val stranger = profile("stranger", "Unknown Profile")

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = listOf(stranger),
            candidatePosts = emptyList(),
            nearbyPeerProfiles = emptyList()
        )

        val matches = matcher.match(context)
        assertEquals(1, matches.size)
        assertEquals("stranger", matches[0].profile.publicKey)
        assertEquals(0.0, matches[0].score)
        assertTrue(matches[0].sharedTopics.isEmpty())
        assertTrue(matches[0].explanation.contains("No shared educational topics identified"))
    }

    @Test
    fun `targetTopic parameter focuses matching on a specific subject`() {
        // User profile has multiple topics, but targetTopic restricts to CYBERSECURITY
        profileStore.updateSelectedTopics(setOf(EducationalTopic.AI_ML, EducationalTopic.CYBERSECURITY))

        val alice = profile("alice", "Alice") // Active in CYBERSECURITY
        val bob = profile("bob", "Bob")       // Active in AI_ML

        val alicePost = candidatePost("p1", "alice", "Penetration testing and network security firewall.")
        val bobPost = candidatePost("p2", "bob", "Deep neural network classification model.")

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            targetTopic = EducationalTopic.CYBERSECURITY,
            candidateProfiles = listOf(alice, bob),
            candidatePosts = listOf(alicePost, bobPost)
        )

        val matches = matcher.match(context)
        assertEquals(2, matches.size)
        assertEquals("alice", matches[0].profile.publicKey)
        assertTrue(matches[0].sharedTopics.contains(EducationalTopic.CYBERSECURITY))
        assertFalse(matches[0].sharedTopics.contains(EducationalTopic.AI_ML))
        assertTrue(matches[0].score > matches[1].score)
    }

    @Test
    fun `formGroup forms a cohort with top candidates and calculates average score`() {
        val candidates = (1..6).map { profile("user$it", "Student $it") }
        val posts = candidates.mapIndexed { idx, prof ->
            candidatePost("post$idx", prof.publicKey, "Data structures and algorithms tree traversal practice.")
        }

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = candidates,
            candidatePosts = posts
        )

        val cohort = matcher.formGroup(
            topic = EducationalTopic.DSA,
            context = context,
            maxMembers = 3
        )

        assertNotNull(cohort)
        assertEquals(EducationalTopic.DSA, cohort.topic)
        assertEquals(3, cohort.members.size)
        assertTrue(cohort.averageScore > 0.0)
        assertTrue(cohort.rationale.contains("Study group for Data Structures & Algorithms"))
    }

    @Test
    fun `formGroup returns null when no matching members exist`() {
        val candidates = listOf(profile("alice", "Alice"))
        val nonMatchingPost = candidatePost("p1", "alice", "Baking bread and making cookies.")

        val context = StudyGroupMatchingContext(
            currentUserId = "me",
            candidateProfiles = candidates,
            candidatePosts = listOf(nonMatchingPost)
        )

        val cohort = matcher.formGroup(
            topic = EducationalTopic.MATHEMATICS,
            context = context
        )

        assertNull(cohort)
    }
}
