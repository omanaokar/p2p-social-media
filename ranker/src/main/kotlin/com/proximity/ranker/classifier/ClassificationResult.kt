package com.proximity.ranker.classifier

/**
 * High-level category determined for post content.
 */
enum class PrimaryCategory {
    EDUCATIONAL,
    NON_EDUCATIONAL,
    INAPPROPRIATE,
    UNCERTAIN;

    companion object {
        @Deprecated("Use INAPPROPRIATE instead", ReplaceWith("INAPPROPRIATE"))
        val ADULT: PrimaryCategory get() = INAPPROPRIATE
    }
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
 * Specific indicators flagged for inappropriate, abusive, or prohibited content.
 */
enum class InappropriateFlag {
    EXPLICIT_SEXUAL,
    PORNOGRAPHY,
    SOLICITATION,
    HARASSMENT_ABUSE,
    HATE_SPEECH,
    VIOLENCE_THREAT,
    PROHIBITED_COMMERCIAL
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
 * @param inappropriateFlags Specific inappropriate indicators flagged, if any.
 * @param summary Human-readable classification explanation.
 */
data class ClassificationResult(
    val category: PrimaryCategory,
    val educationalTopics: Set<EducationalTopic>,
    val primaryTopic: EducationalTopic?,
    val confidence: ConfidenceLevel,
    val topicScores: Map<EducationalTopic, Double>,
    val adultFlags: Set<AdultFlag> = emptySet(),
    val inappropriateFlags: Set<InappropriateFlag> = emptySet(),
    val summary: String
) {
    val isEducational: Boolean get() = category == PrimaryCategory.EDUCATIONAL && confidence != ConfidenceLevel.UNCERTAIN
    val isInappropriate: Boolean get() = category == PrimaryCategory.INAPPROPRIATE || adultFlags.isNotEmpty() || inappropriateFlags.isNotEmpty()
    val isAdult: Boolean get() = isInappropriate
}
