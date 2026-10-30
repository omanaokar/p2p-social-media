package com.proximity.ranker.profile

import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.studygroup.StudyGoal
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserInterestProfileTest {

    @Test
    fun `in-memory store defaults to empty preferences`() {
        val store = InMemoryUserInterestProfileStore()
        assertTrue(store.getProfile().selectedTopics.isEmpty())
        assertTrue(store.getProfile().studyGoals.isEmpty())
    }

    @Test
    fun `in-memory store updates selected topics`() {
        val store = InMemoryUserInterestProfileStore()
        store.updateSelectedTopics(setOf(EducationalTopic.AI_ML, EducationalTopic.DSA))

        val profile = store.getProfile()
        assertEquals(2, profile.selectedTopics.size)
        assertTrue(profile.selectedTopics.contains(EducationalTopic.AI_ML))
        assertTrue(profile.selectedTopics.contains(EducationalTopic.DSA))
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
    fun `file store persists and restores selected topics and study goals`() {
        val tempDir = Files.createTempDirectory("user_profile_test").toFile()
        val profileFile = File(tempDir, "user_interests.txt")

        try {
            val store1 = FileUserInterestProfileStore(profileFile)
            assertTrue(store1.getProfile().selectedTopics.isEmpty())
            assertTrue(store1.getProfile().studyGoals.isEmpty())

            store1.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY, EducationalTopic.CLOUD_COMPUTING))
            store1.updateStudyGoals(setOf(StudyGoal.PROJECT_COLLABORATION))

            // Reopen store with same file
            val store2 = FileUserInterestProfileStore(profileFile)
            val restored = store2.getProfile()

            assertEquals(2, restored.selectedTopics.size)
            assertTrue(restored.selectedTopics.contains(EducationalTopic.CYBERSECURITY))
            assertTrue(restored.selectedTopics.contains(EducationalTopic.CLOUD_COMPUTING))
            assertEquals(1, restored.studyGoals.size)
            assertTrue(restored.studyGoals.contains(StudyGoal.PROJECT_COLLABORATION))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `file store handles corrupt or missing file gracefully`() {
        val tempDir = Files.createTempDirectory("user_profile_corrupt_test").toFile()
        val profileFile = File(tempDir, "corrupted.txt")

        try {
            profileFile.writeText("INVALID_TOPIC,NOT_A_TOPIC, ,,,")
            val store = FileUserInterestProfileStore(profileFile)
            assertTrue(store.getProfile().selectedTopics.isEmpty())
            assertTrue(store.getProfile().studyGoals.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
