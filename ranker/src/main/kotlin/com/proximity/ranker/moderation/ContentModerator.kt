package com.proximity.ranker.moderation

import com.proximity.ranker.classifier.ClassificationResult
import com.proximity.ranker.classifier.ConfidenceLevel
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.classifier.TextContentClassifier

/**
 * Action recommended by the moderation engine.
 */
enum class ModerationAction {
    /** Content meets educational standards with sufficient confidence and no violations. */
    ALLOW,

    /** Content is ambiguous, low confidence, or non-educational. Requires manual or community review. */
    FLAG_FOR_REVIEW,

    /** Content violates community safety policies (e.g. explicit/adult/abusive material). Rejected immediately. */
    REJECT
}

/**
 * Result of a moderation evaluation, separating classification metrics from enforcement policy.
 */
data class ModerationDecision(
    val action: ModerationAction,
    val reason: String,
    val classification: ClassificationResult
) {
    val isApproved: Boolean get() = action == ModerationAction.ALLOW
    val isRejected: Boolean get() = action == ModerationAction.REJECT
    val requiresReview: Boolean get() = action == ModerationAction.FLAG_FOR_REVIEW
}

/**
 * Configuration tunables for moderation enforcement policy.
 */
data class ModerationPolicyConfig(
    /** If true, posts classified as casual non-educational will be flagged/rejected rather than allowed. */
    val strictEducationalOnly: Boolean = true,
    /** Minimum confidence required for automatic approval of educational posts. */
    val minConfidenceForAutoApproval: ConfidenceLevel = ConfidenceLevel.MEDIUM
)

/**
 * Contract separating classification from moderation policy enforcement.
 */
interface ContentModerator {
    fun evaluate(text: String?): ModerationDecision
    fun decide(classification: ClassificationResult): ModerationDecision
}

/**
 * Default implementation of content moderation policy.
 *
 * It enforces:
 * - Immediate REJECT for inappropriate, adult, abusive, or prohibited content.
 * - FLAG_FOR_REVIEW for ambiguous/UNCERTAIN content (never silently approving ambiguous inputs).
 * - FLAG_FOR_REVIEW for non-educational casual content when [ModerationPolicyConfig.strictEducationalOnly] is true.
 * - ALLOW only for verified educational content meeting the minimum confidence threshold.
 */
class DefaultContentModerator(
    private val classifier: TextContentClassifier = HeuristicTextClassifier(),
    private val config: ModerationPolicyConfig = ModerationPolicyConfig()
) : ContentModerator {

    override fun evaluate(text: String?): ModerationDecision {
        val classification = classifier.classify(text)
        return decide(classification)
    }

    override fun decide(classification: ClassificationResult): ModerationDecision {
        // 1. Inappropriate / Adult Content Enforcement
        if (classification.isInappropriate || classification.isAdult) {
            return ModerationDecision(
                action = ModerationAction.REJECT,
                reason = "Post contains inappropriate, abusive, or sexually explicit indicators, which are strictly prohibited.",
                classification = classification
            )
        }

        // 2. Ambiguity / Insufficient Evidence Enforcement
        if (classification.category == PrimaryCategory.UNCERTAIN || classification.confidence == ConfidenceLevel.UNCERTAIN) {
            return ModerationDecision(
                action = ModerationAction.FLAG_FOR_REVIEW,
                reason = "Content is ambiguous or lacks sufficient educational indicators. Flagged for review.",
                classification = classification
            )
        }

        // 3. Non-Educational Content Handling
        if (classification.category == PrimaryCategory.NON_EDUCATIONAL) {
            return if (config.strictEducationalOnly) {
                ModerationDecision(
                    action = ModerationAction.FLAG_FOR_REVIEW,
                    reason = "Post appears to be casual or non-educational content in an educational space.",
                    classification = classification
                )
            } else {
                ModerationDecision(
                    action = ModerationAction.ALLOW,
                    reason = "Casual post allowed under relaxed policy.",
                    classification = classification
                )
            }
        }

        // 4. Educational Content Verification
        if (classification.category == PrimaryCategory.EDUCATIONAL) {
            return when (classification.confidence) {
                ConfidenceLevel.HIGH -> ModerationDecision(
                    action = ModerationAction.ALLOW,
                    reason = "Educational content verified (${classification.primaryTopic?.displayName}).",
                    classification = classification
                )
                ConfidenceLevel.MEDIUM -> {
                    if (config.minConfidenceForAutoApproval == ConfidenceLevel.HIGH) {
                        ModerationDecision(
                            action = ModerationAction.FLAG_FOR_REVIEW,
                            reason = "Educational content has moderate confidence; requires confirmation.",
                            classification = classification
                        )
                    } else {
                        ModerationDecision(
                            action = ModerationAction.ALLOW,
                            reason = "Educational content verified (${classification.primaryTopic?.displayName}).",
                            classification = classification
                        )
                    }
                }
                ConfidenceLevel.LOW, ConfidenceLevel.UNCERTAIN -> ModerationDecision(
                    action = ModerationAction.FLAG_FOR_REVIEW,
                    reason = "Educational indicators are weak; held for review.",
                    classification = classification
                )
            }
        }

        // Default safeguard: flag anything not explicitly allowed
        return ModerationDecision(
            action = ModerationAction.FLAG_FOR_REVIEW,
            reason = "Content requires manual review.",
            classification = classification
        )
    }
}
