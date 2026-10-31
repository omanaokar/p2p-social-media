package com.proximity.ranker.profile

import com.proximity.contracts.InteractionType
import com.proximity.contracts.Post
import com.proximity.ranker.classifier.ClassificationResult
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.studygroup.StudyGoal
import java.io.File
import kotlin.math.roundToInt

/**
 * User's cold-start, explicit, and learned interest profile.
 *
 * @param selectedTopics The set of educational topics explicitly selected by the user.
 * @param studyGoals The set of compatible study goals selected by the user.
 * @param topicScores Persistent continuous interest scores per [EducationalTopic], normalized in [0.0, 1.0].
 * @param interactionCounts Total tracked user interactions across all topics.
 */
data class UserInterestProfile(
    val selectedTopics: Set<EducationalTopic> = emptySet(),
    val studyGoals: Set<StudyGoal> = emptySet(),
    val topicScores: Map<EducationalTopic, Double> = emptyMap(),
    val interactionCounts: Map<EducationalTopic, Int> = emptyMap()
) {
    /**
     * Returns true if the user has no declared topics and no recorded topic interaction history.
     */
    val isColdStart: Boolean get() = selectedTopics.isEmpty() && topicScores.isEmpty()

    /**
     * Retrieves the interest score for a topic in [0.0, 1.0].
     * - For new users (cold start), returns a neutral score (0.50).
     * - If scores exist for the user:
     *   - returns recorded score if present,
     *   - if explicitly selected but no score recorded, defaults to high interest (0.80),
     *   - otherwise falls back to a neutral baseline (0.10).
     */
    fun getInterestScore(topic: EducationalTopic): Double {
        if (isColdStart) return COLD_START_NEUTRAL_SCORE
        val score = topicScores[topic]
        if (score != null) return score
        return if (topic in selectedTopics) 0.80 else 0.10
    }

    companion object {
        const val COLD_START_NEUTRAL_SCORE = 0.50
    }
}

/**
 * Repository/store for reading and updating the user's educational interest profile.
 */
interface UserInterestProfileStore {
    fun getProfile(): UserInterestProfile
    fun updateProfile(profile: UserInterestProfile)
    fun updateSelectedTopics(topics: Set<EducationalTopic>)
    fun updateStudyGoals(goals: Set<StudyGoal>)
    fun recordInteraction(post: Post, type: InteractionType, classification: ClassificationResult)
}

/**
 * Optional capability for stores that can durably couple an interaction ID to
 * its profile update.  A false return means the profile already contains the
 * event; an exception means no durable acknowledgement was made.
 */
interface EventAwareUserInterestProfileStore : UserInterestProfileStore {
    fun recordInteractionOnce(
        eventId: String,
        post: Post,
        type: InteractionType,
        classification: ClassificationResult,
    ): Boolean
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

    override fun recordInteraction(post: Post, type: InteractionType, classification: ClassificationResult) {
        if (classification.category != PrimaryCategory.EDUCATIONAL) return
        val topics = classification.educationalTopics
        if (topics.isEmpty()) return

        synchronized(this) {
            currentProfile = computeUpdatedProfile(currentProfile, topics, type)
        }
    }
}

/**
 * File-backed persistence for [UserInterestProfile] using pure Kotlin/JVM file I/O.
 *
 * File structure:
 * Line 1: Comma-separated enum names for selected topics.
 * Line 2: Comma-separated enum names for study goals.
 * Line 3+: Topic scores and counts formatted as "TOPIC_NAME=score:count".
 *
 * Automatically falls back to neutral/cold-start profile if the file does not exist or is corrupted.
 */
class FileUserInterestProfileStore(
    private val storageFile: File
) : EventAwareUserInterestProfileStore {

    @Volatile
    private var cachedProfile: UserInterestProfile = loadFromFile()
    private val processedEventIds = loadProcessedEventIds()

    override fun getProfile(): UserInterestProfile = cachedProfile

    override fun updateProfile(profile: UserInterestProfile) {
        synchronized(this) {
            cachedProfile = profile
            saveToFile(profile)
        }
    }

    override fun updateSelectedTopics(topics: Set<EducationalTopic>) {
        synchronized(this) {
            val updated = cachedProfile.copy(selectedTopics = topics)
            cachedProfile = updated
            saveToFile(updated)
        }
    }

    override fun updateStudyGoals(goals: Set<StudyGoal>) {
        synchronized(this) {
            val updated = cachedProfile.copy(studyGoals = goals)
            cachedProfile = updated
            saveToFile(updated)
        }
    }

    override fun recordInteraction(post: Post, type: InteractionType, classification: ClassificationResult) {
        if (classification.category != PrimaryCategory.EDUCATIONAL) return
        val topics = classification.educationalTopics
        if (topics.isEmpty()) return

        synchronized(this) {
            val updated = computeUpdatedProfile(cachedProfile, topics, type)
            cachedProfile = updated
            saveToFile(updated)
        }
    }

    override fun recordInteractionOnce(
        eventId: String,
        post: Post,
        type: InteractionType,
        classification: ClassificationResult,
    ): Boolean {
        if (classification.category != PrimaryCategory.EDUCATIONAL || classification.educationalTopics.isEmpty()) return false
        synchronized(this) {
            if (eventId in processedEventIds) return false
            val updated = computeUpdatedProfile(cachedProfile, classification.educationalTopics, type)
            saveToFile(updated, processedEventIds + eventId)
            cachedProfile = updated
            processedEventIds += eventId
            return true
        }
    }

    private fun loadFromFile(): UserInterestProfile {
        if (!storageFile.exists()) return UserInterestProfile()
        return try {
            val lines = storageFile.readLines().map { it.trim() }
            if (lines.all { it.isEmpty() }) {
                UserInterestProfile()
            } else {
                val topics = lines.getOrNull(0)?.let { parseTopics(it) } ?: emptySet()
                val goals = lines.getOrNull(1)?.let { parseGoals(it) } ?: emptySet()
                val scores = mutableMapOf<EducationalTopic, Double>()
                val counts = mutableMapOf<EducationalTopic, Int>()

                for (i in 2 until lines.size) {
                    val line = lines[i]
                    val parts = line.split("=")
                    if (parts.size == 2) {
                        val topic = runCatching { EducationalTopic.valueOf(parts[0].trim()) }.getOrNull()
                        if (topic != null) {
                            val dataParts = parts[1].split(":")
                            val score = dataParts.getOrNull(0)?.toDoubleOrNull()
                            val count = dataParts.getOrNull(1)?.toIntOrNull() ?: 1
                            if (score != null) {
                                scores[topic] = score.coerceIn(0.0, 1.0)
                                counts[topic] = count
                            }
                        }
                    }
                }

                UserInterestProfile(
                    selectedTopics = topics,
                    studyGoals = goals,
                    topicScores = scores,
                    interactionCounts = counts
                )
            }
        } catch (_: Exception) {
            UserInterestProfile()
        }
    }

    private fun parseTopics(line: String): Set<EducationalTopic> {
        return line.split(",")
            .mapNotNull { name ->
                runCatching { EducationalTopic.valueOf(name.trim()) }.getOrNull()
            }
            .toSet()
    }

    private fun parseGoals(line: String): Set<StudyGoal> {
        return line.split(",")
            .mapNotNull { name ->
                runCatching { StudyGoal.valueOf(name.trim()) }.getOrNull()
            }
            .toSet()
    }

    private fun loadProcessedEventIds(): MutableSet<String> =
        if (!storageFile.exists()) mutableSetOf() else runCatching {
            storageFile.readLines().mapNotNull { line ->
                line.trim().removePrefix(EVENT_PREFIX).takeIf { line.trim().startsWith(EVENT_PREFIX) && it.isNotBlank() }
            }.toMutableSet()
        }.getOrElse { mutableSetOf() }

    private fun saveToFile(profile: UserInterestProfile, eventIds: Set<String> = processedEventIds) {
        storageFile.parentFile?.mkdirs()
        val topics = profile.selectedTopics.joinToString(",") { it.name }
        val goals = profile.studyGoals.joinToString(",") { it.name }
        val scoreLines = profile.topicScores.map { (topic, score) ->
            val count = profile.interactionCounts[topic] ?: 1
            // 3 decimal precision
            val roundedScore = ((score * 1000.0).roundToInt()) / 1000.0
            "${topic.name}=$roundedScore:$count"
        }
        val content = buildString {
            appendLine(topics)
            appendLine(goals)
            for (sl in scoreLines) appendLine(sl)
            for (eventId in eventIds.sorted()) appendLine("$EVENT_PREFIX$eventId")
        }
        val temporary = File(storageFile.parentFile ?: File("."), ".${storageFile.name}.tmp")
        try {
            temporary.writeText(content)
            java.nio.file.Files.move(
                temporary.toPath(), storageFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Exception) {
            temporary.delete()
            throw IllegalStateException("Could not persist interest profile", e)
        }
    }

    private companion object { const val EVENT_PREFIX = "event:" }
}

/**
 * Calculates updated [UserInterestProfile] following interaction signals.
 *
 * Interaction weights:
 * - Like / React: +0.15
 * - Save: +0.20
 * - Comment: +0.25
 * - Open / Read: +0.08
 * - Dwell (extended reading): +0.05
 * - Hide: -0.30
 *
 * Diminishing returns:
 * To avoid uncontrolled score inflation from repeated interactions on the same topic,
 * positive deltas are scaled down by the count of prior interactions:
 * deltaEffective = delta / (1.0 + 0.10 * priorCount)
 */
internal fun computeUpdatedProfile(
    current: UserInterestProfile,
    topics: Set<EducationalTopic>,
    type: InteractionType
): UserInterestProfile {
    val baseDelta = when (type) {
        InteractionType.REACT -> 0.15
        InteractionType.SAVE -> 0.20
        InteractionType.COMMENT -> 0.25
        InteractionType.OPEN -> 0.08
        InteractionType.DWELL -> 0.05
        InteractionType.HIDE -> -0.30
    }

    val updatedScores = current.topicScores.toMutableMap()
    val updatedCounts = current.interactionCounts.toMutableMap()

    for (topic in topics) {
        val existingScore = updatedScores[topic] ?: if (topic in current.selectedTopics) 0.70 else 0.50
        val priorCount = updatedCounts[topic] ?: 0

        val effectiveDelta = if (baseDelta > 0) {
            // Logarithmic/damping scaling to prevent uncontrolled inflation
            baseDelta / (1.0 + (0.10 * priorCount))
        } else {
            // Immediate, un-damped negative penalty on hide
            baseDelta
        }

        val newScore = (existingScore + effectiveDelta).coerceIn(0.0, 1.0)
        updatedScores[topic] = newScore
        updatedCounts[topic] = priorCount + 1
    }

    return current.copy(
        topicScores = updatedScores,
        interactionCounts = updatedCounts
    )
}
