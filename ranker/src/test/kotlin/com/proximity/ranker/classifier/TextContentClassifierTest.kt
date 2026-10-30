package com.proximity.ranker.classifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextContentClassifierTest {

    private val classifier = HeuristicTextClassifier()

    @Test
    fun `classifies AI and Machine Learning content with high confidence`() {
        val text = "We trained a deep learning neural network with PyTorch using gradient descent and backpropagation."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.AI_ML, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.AI_ML))
        assertEquals(ConfidenceLevel.HIGH, result.confidence)
        assertFalse(result.isInappropriate)
    }

    @Test
    fun `classifies Programming content`() {
        val text = "Kotlin coroutines offer structured concurrency and avoid thread-blocking memory leaks."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.PROGRAMMING, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.PROGRAMMING))
        assertFalse(result.isInappropriate)
    }

    @Test
    fun `classifies Data Structures and Algorithms content`() {
        val text = "Comparing time complexity of quicksort and mergesort using asymptotic big-o notation on binary trees."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.DSA, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.DSA))
        assertEquals(ConfidenceLevel.HIGH, result.confidence)
    }

    @Test
    fun `classifies Cybersecurity content`() {
        val text = "Penetration testing revealed a critical sql injection and cross-site scripting vulnerability."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.CYBERSECURITY, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.CYBERSECURITY))
        assertEquals(ConfidenceLevel.HIGH, result.confidence)
    }

    @Test
    fun `classifies Mathematics content`() {
        val text = "Computing matrix eigenvalues and solving linear algebra differential equations with calculus."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.MATHEMATICS, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.MATHEMATICS))
    }

    @Test
    fun `classifies Cloud Computing content`() {
        val text = "Deploying microservices with Docker and Kubernetes using Terraform on AWS cloud architecture."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.CLOUD_COMPUTING, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.CLOUD_COMPUTING))
    }

    @Test
    fun `classifies Science and Engineering content`() {
        val text = "An overview of thermodynamic entropy, quantum mechanics wavefunctions, and electromagnetism in physics."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.SCIENCE_ENGINEERING, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.SCIENCE_ENGINEERING))
        assertTrue(result.isEducational)
    }

    @Test
    fun `classifies Academic Research content`() {
        val text = "Our empirical study conducts a systematic literature review and hypothesis testing with statistical significance p < 0.01."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.ACADEMIC_RESEARCH, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.ACADEMIC_RESEARCH))
        assertTrue(result.isEducational)
    }

    @Test
    fun `detects mixed topics accurately`() {
        val text = "Building an AI machine learning model with PyTorch and deploying it on Kubernetes using Docker containers."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertTrue(result.educationalTopics.contains(EducationalTopic.AI_ML))
        assertTrue(result.educationalTopics.contains(EducationalTopic.CLOUD_COMPUTING))
        assertNotNull(result.primaryTopic)
    }

    @Test
    fun `detects adult and explicit content indicators`() {
        val text = "Check out my new adult nsfw video and naked photos on onlyfans."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.INAPPROPRIATE, result.category)
        assertTrue(result.isInappropriate)
        assertTrue(result.isAdult)
        assertEquals(ConfidenceLevel.HIGH, result.confidence)
        assertTrue(result.educationalTopics.isEmpty())
        assertNull(result.primaryTopic)
    }

    @Test
    fun `detects abusive and violent prohibited content as INAPPROPRIATE`() {
        val violentText = "I will send a bomb threat and death threat to the school!"
        val resultViolent = classifier.classify(violentText)
        assertEquals(PrimaryCategory.INAPPROPRIATE, resultViolent.category)
        assertTrue(resultViolent.isInappropriate)

        val drugText = "Contact me on telegram to buy cocaine and buy weed online."
        val resultDrug = classifier.classify(drugText)
        assertEquals(PrimaryCategory.INAPPROPRIATE, resultDrug.category)
        assertTrue(resultDrug.isInappropriate)
    }

    @Test
    fun `rejects misleading anti-educational dismissals containing academic keywords`() {
        val misleadingCases = listOf(
            "Skipping calculus class today to go play soccer with friends!",
            "I hate homework, let's watch netflix instead.",
            "Bought a huge algorithms textbook just to use it as a mousepad lol",
            "Failed my physics exam haha let's party tonight with beer!",
            "Studying machine learning is so boring, watching anime all day.",
            "AI will take over the world anyway so why study, let's party"
        )

        for (text in misleadingCases) {
            val result = classifier.classify(text)
            assertEquals(
                PrimaryCategory.NON_EDUCATIONAL,
                result.category,
                "Text '$text' must be NON_EDUCATIONAL dismissal, but was ${result.category}"
            )
            assertFalse(result.isEducational, "Text '$text' must not qualify as educational")
            assertTrue(result.educationalTopics.isEmpty())
        }
    }

    @Test
    fun `rejects commercial ads, spam, and textbook sales as NON_EDUCATIONAL`() {
        val spamCases = listOf(
            "Selling my python programming textbook for \$20 dm me for price!",
            "Use promo code DISCOUNT50 to buy courses now!",
            "Earn \$500 daily with crypto investment doubler airdrop!",
            "Forex trading signals and binary options link in bio."
        )

        for (text in spamCases) {
            val result = classifier.classify(text)
            assertEquals(
                PrimaryCategory.NON_EDUCATIONAL,
                result.category,
                "Text '$text' must be rejected as commercial/spam, but was ${result.category}"
            )
            assertFalse(result.isEducational)
        }
    }

    @Test
    fun `does not classify isolated polysemous words as educational`() {
        val polysemousCases = listOf(
            "I love rust on old vintage cars.",
            "Saw a beautiful bird sitting on a high tree branch.",
            "Dark clouds in the sky today, looks like rain.",
            "Waiting in a long queue at the movie cinema.",
            "Eating a tall stack of pancakes with maple syrup for breakfast.",
            "My favorite movie of all time is The Matrix."
        )

        for (text in polysemousCases) {
            val result = classifier.classify(text)
            assertFalse(
                result.isEducational,
                "Polysemous phrase '$text' should not qualify as educational without technical context"
            )
            assertFalse(result.category == PrimaryCategory.EDUCATIONAL)
        }
    }

    @Test
    fun `classifies polysemous words with educational context or compounds as EDUCATIONAL`() {
        val validCases = listOf(
            "In this tutorial we implement a binary search tree in Rust with insertion and deletion.",
            "Understanding call stack frame execution and recursion in functional programming."
        )

        for (text in validCases) {
            val result = classifier.classify(text)
            assertEquals(PrimaryCategory.EDUCATIONAL, result.category, "Contextual phrase '$text' should be EDUCATIONAL")
            assertTrue(result.isEducational)
        }
    }

    @Test
    fun `handles mixed-context posts appropriately`() {
        // Casual chatter dominates with incidental technical word -> NON_EDUCATIONAL
        val casualDominant = "Went to the beach party with friends, had cocktails and tacos, also solved one quick math problem lol."
        val res1 = classifier.classify(casualDominant)
        assertEquals(PrimaryCategory.NON_EDUCATIONAL, res1.category)

        // Substantial educational post with incidental personal preface -> EDUCATIONAL
        val educationalDominant = "Had breakfast and coffee, now writing a step by step guide on Dijkstra's algorithm using priority queue in Kotlin."
        val res2 = classifier.classify(educationalDominant)
        assertEquals(PrimaryCategory.EDUCATIONAL, res2.category)
        assertTrue(res2.isEducational)
    }

    @Test
    fun `recognizes code syntax and math markers as educational intent`() {
        val codeText = """
            Here is how to solve the problem:
            ```kotlin
            fun fibonacci(n: Int): Long {
                if (n <= 1) return n.toLong()
                return fibonacci(n - 1) + fibonacci(n - 2)
            }
            ```
            Time complexity is O(2^n).
        """.trimIndent()

        val result = classifier.classify(codeText)
        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(ConfidenceLevel.HIGH, result.confidence)
        assertTrue(result.isEducational)
    }

    @Test
    fun `categorizes casual non-educational content`() {
        val text = "Going out for dinner and brunch with friends at the beach party this weekend! Weekend vibes."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.NON_EDUCATIONAL, result.category)
        assertFalse(result.isEducational)
        assertFalse(result.isInappropriate)
        assertTrue(result.educationalTopics.isEmpty())
    }

    @Test
    fun `returns UNCERTAIN for empty and whitespace input`() {
        val nullResult = classifier.classify(null)
        val emptyResult = classifier.classify("")
        val blankResult = classifier.classify("   \n\t   ")

        assertEquals(PrimaryCategory.UNCERTAIN, nullResult.category)
        assertEquals(ConfidenceLevel.UNCERTAIN, nullResult.confidence)

        assertEquals(PrimaryCategory.UNCERTAIN, emptyResult.category)
        assertEquals(ConfidenceLevel.UNCERTAIN, emptyResult.confidence)

        assertEquals(PrimaryCategory.UNCERTAIN, blankResult.category)
        assertEquals(ConfidenceLevel.UNCERTAIN, blankResult.confidence)
    }

    @Test
    fun `returns UNCERTAIN for ambiguous conversational text without technical terms`() {
        val ambiguousTexts = listOf(
            "Hey check this out!",
            "What do you guys think about this?",
            "Hello everyone, nice to meet you all.",
            "Can someone please tell me what is going on today?"
        )

        for (text in ambiguousTexts) {
            val result = classifier.classify(text)
            assertEquals(
                PrimaryCategory.UNCERTAIN,
                result.category,
                "Text '$text' should be UNCERTAIN but was ${result.category}"
            )
            assertEquals(ConfidenceLevel.UNCERTAIN, result.confidence)
        }
    }
}
