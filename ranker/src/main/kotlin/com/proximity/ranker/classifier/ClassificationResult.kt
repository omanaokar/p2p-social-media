package com.proximity.ranker.classifier

/**
 * High-level category determined for post content.
 */
enum class PrimaryCategory {
    EDUCATIONAL,
    NON_EDUCATIONAL,
    ADULT,
    UNCERTAIN
}

/**
 * Confidence level of the heuristic categorization.
 * UNCERTAIN is used when textual evidence is ambiguous or insufficient.
 */
enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
    UNCERTAIN
}

/**
 * Indicators for detected adult, explicit, or sexually oriented content.
 */
enum class AdultFlag {
    EXPLICIT_SEXUAL,
    PORNOGRAPHY,
    SOLICITATION
}

/**
 * Outcome of text classification.
 *
 * @param category High-level determined category.
 * @param educationalTopics All matched educational topics above threshold.
 * @param primaryTopic The top-scoring educational topic, or null if none.
 * @param confidence Confidence in the classification.
 * @param topicScores Detailed heuristic match scores per educational topic.
 * @param adultFlags Specific adult indicators flagged, if any.
 * @param summary Human-readable classification explanation.
 */
data class ClassificationResult(
    val category: PrimaryCategory,
    val educationalTopics: Set<EducationalTopic>,
    val primaryTopic: EducationalTopic?,
    val confidence: ConfidenceLevel,
    val topicScores: Map<EducationalTopic, Double>,
    val adultFlags: Set<AdultFlag> = emptySet(),
    val summary: String
) {
    val isEducational: Boolean get() = category == PrimaryCategory.EDUCATIONAL
    val isAdult: Boolean get() = category == PrimaryCategory.ADULT || adultFlags.isNotEmpty()
}
