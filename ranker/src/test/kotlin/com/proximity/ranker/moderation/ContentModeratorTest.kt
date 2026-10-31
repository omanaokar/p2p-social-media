package com.proximity.ranker.moderation

import com.proximity.ranker.classifier.AdultFlag
import com.proximity.ranker.classifier.ClassificationResult
import com.proximity.ranker.classifier.ConfidenceLevel
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.PrimaryCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentModeratorTest {

    /** Default policy: only abusive, profane, hateful and adult content is blocked. */
    private val moderator = DefaultContentModerator()

    /** Old education-only mode, kept available behind an explicit switch. */
    private val strictModerator = DefaultContentModerator(
        config = ModerationPolicyConfig(strictEducationalOnly = true)
    )

    @Test
    fun `approves verified educational content`() {
        val decision = moderator.evaluate("A comprehensive guide to dynamic programming algorithms with memoization.")
        assertEquals(ModerationAction.ALLOW, decision.action)
        assertTrue(decision.isApproved)
        assertFalse(decision.isRejected)
        assertFalse(decision.requiresReview)
    }

    @Test
    fun `rejects adult and explicit content immediately`() {
        val decision = moderator.evaluate("Exclusive nsfw porn videos and photos available now.")
        assertEquals(ModerationAction.REJECT, decision.action)
        assertTrue(decision.isRejected)
        assertFalse(decision.isApproved)
    }

    @Test
    fun `strict mode flags ambiguous or uncertain content for review and never silently approves`() {
        val ambiguousInputs = listOf(
            null,
            "",
            "   ",
            "Hey what do you think?",
            "Can anyone check this?"
        )

        for (input in ambiguousInputs) {
            val decision = strictModerator.evaluate(input)
            assertEquals(
                ModerationAction.FLAG_FOR_REVIEW,
                decision.action,
                "Input '$input' must be flagged for review, not silently approved!"
            )
            assertTrue(decision.requiresReview)
            assertFalse(decision.isApproved)
        }
    }

    @Test
    fun `flags casual non-educational content when strict educational policy is enabled`() {
        val decision = strictModerator.evaluate("Let's grab lunch and tacos at the beach party this afternoon!")
        assertEquals(ModerationAction.FLAG_FOR_REVIEW, decision.action)
        assertTrue(decision.requiresReview)
    }

    @Test
    fun `allows casual content when relaxed policy is configured`() {
        val relaxedModerator = DefaultContentModerator(
            classifier = HeuristicTextClassifier(),
            config = ModerationPolicyConfig(strictEducationalOnly = false)
        )
        val decision = relaxedModerator.evaluate("Let's grab lunch and tacos at the beach party this afternoon!")
        assertEquals(ModerationAction.ALLOW, decision.action)
        assertTrue(decision.isApproved)
    }

    @Test
    fun `flags low confidence educational content for review`() {
        val lowConfidenceResult = ClassificationResult(
            category = PrimaryCategory.EDUCATIONAL,
            educationalTopics = setOf(EducationalTopic.PROGRAMMING),
            primaryTopic = EducationalTopic.PROGRAMMING,
            confidence = ConfidenceLevel.LOW,
            topicScores = mapOf(EducationalTopic.PROGRAMMING to 1.0),
            summary = "Weak single-word indicator"
        )

        val decision = strictModerator.decide(lowConfidenceResult)
        assertEquals(ModerationAction.FLAG_FOR_REVIEW, decision.action)
        assertTrue(decision.requiresReview)
    }

    @Test
    fun `default policy allows casual and ambiguous content`() {
        val inputs = listOf(
            null,
            "",
            "Hey what do you think?",
            "Let's grab lunch and tacos at the beach party this afternoon!",
            "Skipping calculus class today to play soccer with friends!"
        )
        for (input in inputs) {
            val decision = moderator.evaluate(input)
            assertEquals(ModerationAction.ALLOW, decision.action, "Input '$input' must be allowed by default")
            assertTrue(decision.isApproved)
        }
    }

    @Test
    fun `default policy rejects profanity and abuse`() {
        val inputs = listOf(
            "What the fuck is this garbage?",
            "Our professor is an asshole and this lab is bullshit.",
            "tu chutiya hai yaar",
            "I will make a death threat, go die."
        )
        for (input in inputs) {
            val decision = moderator.evaluate(input)
            assertEquals(ModerationAction.REJECT, decision.action, "Input '$input' must be rejected")
            assertTrue(decision.isRejected)
        }
    }

    @Test
    fun `default policy does not block clean educational wording`() {
        val decision = moderator.evaluate(
            "Lienard-Wiechert retarded potentials in classical electromagnetism, and the Python assert statement."
        )
        assertTrue(decision.isApproved)
    }
}
