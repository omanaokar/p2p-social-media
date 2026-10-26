package com.proximity.ranker.profile

import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.studygroup.StudyGoal
import java.io.File

/**
 * User's cold-start and ongoing interest profile.
 *
 * @param selectedTopics The set of educational topics explicitly selected by the user.
 * @param studyGoals The set of compatible study goals selected by the user.
 */
data class UserInterestProfile(
    val selectedTopics: Set<EducationalTopic> = emptySet(),
    val studyGoals: Set<StudyGoal> = emptySet()
)

/**
 * Repository/store for reading and updating the user's educational interest profile.
 */
interface UserInterestProfileStore {
    fun getProfile(): UserInterestProfile
    fun updateProfile(profile: UserInterestProfile)
    fun updateSelectedTopics(topics: Set<EducationalTopic>)
    fun updateStudyGoals(goals: Set<StudyGoal>)
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
        currentProfile = currentProfile.copy(selectedTopics = topics)
    }

    override fun updateStudyGoals(goals: Set<StudyGoal>) {
        currentProfile = currentProfile.copy(studyGoals = goals)
    }
}

/**
 * Simple file-backed persistence for [UserInterestProfile] using pure Kotlin/JVM file I/O.
 * Persists selected topics as comma-separated enum names on line 1,
 * and optional study goals on line 2.
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
        updateProfile(cachedProfile.copy(selectedTopics = topics))
    }

    override fun updateStudyGoals(goals: Set<StudyGoal>) {
        updateProfile(cachedProfile.copy(studyGoals = goals))
    }

    private fun loadFromFile(): UserInterestProfile {
        if (!storageFile.exists()) return UserInterestProfile()
        return try {
            val lines = storageFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                UserInterestProfile()
            } else {
                val topics = lines.getOrNull(0)?.let { parseTopics(it) } ?: emptySet()
                val goals = lines.getOrNull(1)?.let { parseGoals(it) } ?: emptySet()
                UserInterestProfile(selectedTopics = topics, studyGoals = goals)
            }
        } catch (e: Exception) {
            UserInterestProfile()
        }
    }

    private fun parseTopics(line: String): Set<EducationalTopic> {
        return line.split(",")
            .mapNotNull { name ->
                try {
                    EducationalTopic.valueOf(name.trim())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .toSet()
    }

    private fun parseGoals(line: String): Set<StudyGoal> {
        return line.split(",")
            .mapNotNull { name ->
                try {
                    StudyGoal.valueOf(name.trim())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .toSet()
    }

    private fun saveToFile(profile: UserInterestProfile) {
        try {
            storageFile.parentFile?.mkdirs()
            val topics = profile.selectedTopics.joinToString(",") { it.name }
            val goals = profile.studyGoals.joinToString(",") { it.name }
            storageFile.writeText("$topics\n$goals")
        } catch (_: Exception) {
            // Keep memory cache intact even if disk write fails
        }
    }
}
