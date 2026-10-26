package com.proximity.ranker.profile

import com.proximity.ranker.classifier.EducationalTopic
import java.io.File

/**
 * User's cold-start and ongoing interest profile.
 *
 * @param selectedTopics The set of educational topics explicitly selected by the user.
 */
data class UserInterestProfile(
    val selectedTopics: Set<EducationalTopic> = emptySet()
)

/**
 * Repository/store for reading and updating the user's educational interest profile.
 */
interface UserInterestProfileStore {
    fun getProfile(): UserInterestProfile
    fun updateProfile(profile: UserInterestProfile)
    fun updateSelectedTopics(topics: Set<EducationalTopic>)
}

/**
 * In-memory implementation of [UserInterestProfileStore].
 * Safe default when no file/disk storage is configured.
 */
class InMemoryUserInterestProfileStore(
    initialProfile: UserInterestProfile = UserInterestProfile()
) : UserInterestProfileStore {
    @Volatile
    private var currentProfile: UserInterestProfile = initialProfile

    override fun getProfile(): UserInterestProfile = currentProfile

    override fun updateProfile(profile: UserInterestProfile) {
        currentProfile = profile
    }

    override fun updateSelectedTopics(topics: Set<EducationalTopic>) {
        currentProfile = UserInterestProfile(selectedTopics = topics)
    }
}

/**
 * Simple file-backed persistence for [UserInterestProfile] using pure Kotlin/JVM file I/O.
 * Persists selected topics as comma-separated enum names.
 * Falls back safely to empty profile if the file does not exist or is corrupted.
 */
class FileUserInterestProfileStore(
    private val storageFile: File
) : UserInterestProfileStore {

    @Volatile
    private var cachedProfile: UserInterestProfile = loadFromFile()

    override fun getProfile(): UserInterestProfile = cachedProfile

    override fun updateProfile(profile: UserInterestProfile) {
        cachedProfile = profile
        saveToFile(profile)
    }

    override fun updateSelectedTopics(topics: Set<EducationalTopic>) {
        updateProfile(UserInterestProfile(selectedTopics = topics))
    }

    private fun loadFromFile(): UserInterestProfile {
        if (!storageFile.exists()) return UserInterestProfile()
        return try {
            val content = storageFile.readText().trim()
            if (content.isEmpty()) {
                UserInterestProfile()
            } else {
                val topics = content.split(",")
                    .mapNotNull { name ->
                        try {
                            EducationalTopic.valueOf(name.trim())
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    }
                    .toSet()
                UserInterestProfile(selectedTopics = topics)
            }
        } catch (e: Exception) {
            UserInterestProfile()
        }
    }

    private fun saveToFile(profile: UserInterestProfile) {
        try {
            storageFile.parentFile?.mkdirs()
            val text = profile.selectedTopics.joinToString(",") { it.name }
            storageFile.writeText(text)
        } catch (_: Exception) {
            // Keep memory cache intact even if disk write fails
        }
    }
}
