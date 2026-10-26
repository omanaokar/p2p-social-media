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
        assertFalse(result.isAdult)
    }

    @Test
    fun `classifies Programming content`() {
        val text = "Kotlin coroutines offer structured concurrency and avoid thread-blocking memory leaks."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.EDUCATIONAL, result.category)
        assertEquals(EducationalTopic.PROGRAMMING, result.primaryTopic)
        assertTrue(result.educationalTopics.contains(EducationalTopic.PROGRAMMING))
        assertFalse(result.isAdult)
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

        assertEquals(PrimaryCategory.ADULT, result.category)
        assertTrue(result.isAdult)
        assertEquals(ConfidenceLevel.HIGH, result.confidence)
        assertTrue(result.educationalTopics.isEmpty())
        assertNull(result.primaryTopic)
    }

    @Test
    fun `categorizes casual non-educational content`() {
        val text = "Going out for dinner and brunch with friends at the beach party this weekend! Weekend vibes."
        val result = classifier.classify(text)

        assertEquals(PrimaryCategory.NON_EDUCATIONAL, result.category)
        assertFalse(result.isEducational)
        assertFalse(result.isAdult)
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
