package com.proximity.ranker.profile

import com.proximity.contracts.InteractionType
import com.proximity.contracts.Post
import com.proximity.ranker.classifier.ClassificationResult
import com.proximity.ranker.classifier.ConfidenceLevel
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.studygroup.StudyGoal
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserInterestProfileTest {

    private fun samplePost(id: String, body: String): Post = Post(
        id = id,
        authorId = "author1",
        body = body,
        image = null,
        createdAt = Instant.now(),
        ttl = Duration.ofHours(6),
        hopCount = 0,
        signature = "sig-$id"
    )

    private fun sampleClassification(
        category: PrimaryCategory = PrimaryCategory.EDUCATIONAL,
        primaryTopic: EducationalTopic? = null,
        topics: Set<EducationalTopic> = emptySet()
    ): ClassificationResult = ClassificationResult(
        category = category,
        confidence = ConfidenceLevel.HIGH,
        educationalTopics = topics,
        primaryTopic = primaryTopic,
        topicScores = emptyMap(),
        summary = "test"
    )

    @Test
    fun `in-memory store defaults to empty preferences and neutral cold-start`() {
        val store = InMemoryUserInterestProfileStore()
        val profile = store.getProfile()
        assertTrue(profile.selectedTopics.isEmpty())
        assertTrue(profile.studyGoals.isEmpty())
        assertTrue(profile.topicScores.isEmpty())
        assertTrue(profile.isColdStart)
        assertEquals(0.50, profile.getInterestScore(EducationalTopic.AI_ML))
    }

    @Test
    fun `in-memory store updates selected topics`() {
        val store = InMemoryUserInterestProfileStore()
        store.updateSelectedTopics(setOf(EducationalTopic.AI_ML, EducationalTopic.DSA))

        val profile = store.getProfile()
        assertEquals(2, profile.selectedTopics.size)
        assertTrue(profile.selectedTopics.contains(EducationalTopic.AI_ML))
        assertTrue(profile.selectedTopics.contains(EducationalTopic.DSA))
        assertFalse(profile.isColdStart)
        assertEquals(0.80, profile.getInterestScore(EducationalTopic.AI_ML))
        assertEquals(0.10, profile.getInterestScore(EducationalTopic.MATHEMATICS))
    }

    @Test
    fun `in-memory store updates study goals without clearing topics`() {
        val store = InMemoryUserInterestProfileStore()
        store.updateSelectedTopics(setOf(EducationalTopic.PROGRAMMING))
        store.updateStudyGoals(setOf(StudyGoal.EXAM_PREP, StudyGoal.PROBLEM_SOLVING))

        val profile = store.getProfile()
        assertEquals(1, profile.selectedTopics.size)
        assertEquals(2, profile.studyGoals.size)
        assertTrue(profile.studyGoals.contains(StudyGoal.EXAM_PREP))
        assertTrue(profile.studyGoals.contains(StudyGoal.PROBLEM_SOLVING))
    }

    @Test
    fun `recordInteraction increases score on like, open, comment and reduces on hide`() {
        val store = InMemoryUserInterestProfileStore()
        val post = samplePost("p1", "Binary search tree and graph algorithms")
        val dsaClass = sampleClassification(
            primaryTopic = EducationalTopic.DSA,
            topics = setOf(EducationalTopic.DSA)
        )

        // 1. Initial react/like: baseline 0.50 + (0.15 / 1.0) = 0.65
        store.recordInteraction(post, InteractionType.REACT, dsaClass)
        var profile = store.getProfile()
        assertEquals(0.65, profile.getInterestScore(EducationalTopic.DSA), 0.001)
        assertEquals(1, profile.interactionCounts[EducationalTopic.DSA])

        // 2. Comment: 0.65 + (0.25 / (1.0 + 0.10 * 1)) = 0.65 + 0.22727 = 0.877
        store.recordInteraction(post, InteractionType.COMMENT, dsaClass)
        profile = store.getProfile()
        val scoreAfterComment = profile.getInterestScore(EducationalTopic.DSA)
        assertTrue(scoreAfterComment > 0.85 && scoreAfterComment < 0.90)

        // 3. Open: score increases further
        store.recordInteraction(post, InteractionType.OPEN, dsaClass)
        profile = store.getProfile()
        val scoreAfterOpen = profile.getInterestScore(EducationalTopic.DSA)
        assertTrue(scoreAfterOpen > scoreAfterComment)

        // 4. Hide: reduces topic interest significantly
        store.recordInteraction(post, InteractionType.HIDE, dsaClass)
        profile = store.getProfile()
        val scoreAfterHide = profile.getInterestScore(EducationalTopic.DSA)
        assertEquals(scoreAfterOpen - 0.30, scoreAfterHide, 0.001)
        assertEquals(4, profile.interactionCounts[EducationalTopic.DSA])
    }

    @Test
    fun `recordInteraction ignores non-educational posts`() {
        val store = InMemoryUserInterestProfileStore()
        val post = samplePost("p-casual", "Having lunch with friends")
        val nonEduClass = sampleClassification(
            category = PrimaryCategory.NON_EDUCATIONAL,
            topics = setOf(EducationalTopic.PROGRAMMING)
        )

        store.recordInteraction(post, InteractionType.REACT, nonEduClass)
        assertTrue(store.getProfile().isColdStart)
        assertTrue(store.getProfile().topicScores.isEmpty())
    }

    @Test
    fun `file store persists and restores selected topics, goals, and interaction topic scores`() {
        val tempDir = Files.createTempDirectory("user_profile_test").toFile()
        val profileFile = File(tempDir, "user_interests.txt")

        try {
            val store1 = FileUserInterestProfileStore(profileFile)
            assertTrue(store1.getProfile().selectedTopics.isEmpty())
            assertTrue(store1.getProfile().studyGoals.isEmpty())

            store1.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY, EducationalTopic.CLOUD_COMPUTING))
            store1.updateStudyGoals(setOf(StudyGoal.PROJECT_COLLABORATION))

            val post = samplePost("p-cyber", "Network vulnerability analysis and penetration testing")
            val cyberClass = sampleClassification(
                primaryTopic = EducationalTopic.CYBERSECURITY,
                topics = setOf(EducationalTopic.CYBERSECURITY)
            )
            store1.recordInteraction(post, InteractionType.REACT, cyberClass)

            // Reopen store with same file to verify cold restart
            val store2 = FileUserInterestProfileStore(profileFile)
            val restored = store2.getProfile()

            assertEquals(2, restored.selectedTopics.size)
            assertTrue(restored.selectedTopics.contains(EducationalTopic.CYBERSECURITY))
            assertTrue(restored.selectedTopics.contains(EducationalTopic.CLOUD_COMPUTING))
            assertEquals(1, restored.studyGoals.size)
            assertTrue(restored.studyGoals.contains(StudyGoal.PROJECT_COLLABORATION))

            // Score for CYBERSECURITY: initial was 0.70 (since selected) + 0.15 = 0.85
            assertEquals(0.85, restored.getInterestScore(EducationalTopic.CYBERSECURITY), 0.001)
            assertEquals(1, restored.interactionCounts[EducationalTopic.CYBERSECURITY])
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `file store handles corrupt or missing file gracefully`() {
        val tempDir = Files.createTempDirectory("user_profile_corrupt_test").toFile()
        val profileFile = File(tempDir, "corrupted.txt")

        try {
            profileFile.writeText("INVALID_TOPIC,NOT_A_TOPIC, ,,,\nINVALID_GOALS\nBAD_LINE")
            val store = FileUserInterestProfileStore(profileFile)
            assertTrue(store.getProfile().selectedTopics.isEmpty())
            assertTrue(store.getProfile().studyGoals.isEmpty())
            assertTrue(store.getProfile().topicScores.isEmpty())
            assertTrue(store.getProfile().isColdStart)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
