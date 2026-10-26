package com.proximity.ranker.profile

import com.proximity.ranker.classifier.EducationalTopic
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
    fun `file store persists and restores selected topics`() {
        val tempDir = Files.createTempDirectory("user_profile_test").toFile()
        val profileFile = File(tempDir, "user_interests.txt")

        try {
            val store1 = FileUserInterestProfileStore(profileFile)
            assertTrue(store1.getProfile().selectedTopics.isEmpty())

            store1.updateSelectedTopics(setOf(EducationalTopic.CYBERSECURITY, EducationalTopic.CLOUD_COMPUTING))

            // Reopen store with same file
            val store2 = FileUserInterestProfileStore(profileFile)
            val restored = store2.getProfile()

            assertEquals(2, restored.selectedTopics.size)
            assertTrue(restored.selectedTopics.contains(EducationalTopic.CYBERSECURITY))
            assertTrue(restored.selectedTopics.contains(EducationalTopic.CLOUD_COMPUTING))
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
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
