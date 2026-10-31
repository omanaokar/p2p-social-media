package com.proximity.ranker.search

import com.proximity.contracts.FeedCandidate
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.moderation.ContentModerator
import java.time.Instant
import kotlin.math.ln

/**
 * Keyword-based post search using the BM25+ term-weighting algorithm with an
 * educational-topic alignment bonus derived from the existing
 * [TextContentClassifier].
 *
 * When configured with a [ContentModerator], strictly enforces educational policy
 * so non-educational, inappropriate, or uncertain posts are filtered.
 */
class KeywordPostSearchEngine(
    private val classifier: TextContentClassifier = HeuristicTextClassifier(),
    private val moderator: ContentModerator? = null,
    private val k1: Double = 1.5,
    private val b: Double = 0.75,
    private val clock: () -> Instant = { Instant.now() }
) : PostSearchEngine {

    override fun search(
        query: String?,
        posts: List<FeedCandidate>,
        limit: Int
    ): List<SearchResult> {
        if (query.isNullOrBlank()) return emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()   // e.g. all stop-words

        val currentTime = clock()

        // 1. Deduplicate incoming posts by ID and exclude expired posts
        val nonExpiredPosts = posts
            .distinctBy { it.post.id }
            .filter { !it.post.expiresAt.isBefore(currentTime) }

        if (nonExpiredPosts.isEmpty()) return emptyList()

        // 2. Filter out content rejected by moderation policy if moderator is present
        val eligiblePosts = if (moderator != null) {
            nonExpiredPosts.filter { candidate ->
                moderator.evaluate(candidate.post.body).isApproved
            }
        } else {
            nonExpiredPosts
        }
        if (eligiblePosts.isEmpty()) return emptyList()

        // Tokenise every document once
        val tokenizedDocs = eligiblePosts.map { candidate ->
            candidate to tokenize(candidate.post.body)
        }
        val docCount = eligiblePosts.size.toDouble()
        val avgDocLen = tokenizedDocs.map { (_, terms) -> terms.size }
            .average().takeIf { it.isFinite() && it > 0.0 } ?: 1.0

        // Document frequency: how many documents contain each query term?
        val df = mutableMapOf<String, Int>()
        for ((_, docTerms) in tokenizedDocs) {
            val termSet = docTerms.toSet()
            for (term in queryTerms) {
                if (term in termSet) df[term] = (df[term] ?: 0) + 1
            }
        }

        // Score each document
        val results = mutableListOf<SearchResult>()
        for ((candidate, docTerms) in tokenizedDocs) {
            val tf = docTerms.groupingBy { it }.eachCount()
            val docLen = docTerms.size.toDouble()

            var bm25 = 0.0
            val matchedTerms = mutableSetOf<String>()

            for (term in queryTerms) {
                val termTf = tf[term] ?: 0
                if (termTf == 0) continue
                matchedTerms += term

                // BM25+ IDF — never negative thanks to +1
                val termDf = df[term] ?: 1
                val idf = ln((docCount - termDf + 0.5) / (termDf + 0.5) + 1.0)
                val normTf = (termTf * (k1 + 1.0)) /
                    (termTf + k1 * (1.0 - b + b * docLen / avgDocLen))
                bm25 += idf * normTf
            }

            if (bm25 <= 0.0) continue

            // Educational topic alignment bonus
            val classification = classifier.classify(candidate.post.body)
            val topicBonus = when (classification.category) {
                PrimaryCategory.EDUCATIONAL -> 0.20
                else -> 0.0
            }

            val explanation = buildString {
                append("Terms: ${matchedTerms.joinToString()}")
                if (classification.primaryTopic != null) {
                    append(" • ${classification.primaryTopic.displayName}")
                }
            }

            results += SearchResult(candidate, bm25 + topicBonus, explanation)
        }

        val sorted = results.sortedByDescending { it.relevanceScore }
        return if (limit > 0) sorted.take(limit) else sorted
    }

    /**
     * Lowercases, strips non-alphanumeric characters, and removes stop-words.
     * Returns a List (not Set) so term frequency can be counted by the caller.
     */
    internal fun tokenize(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all",
            "can", "had", "her", "was", "one", "our", "out", "day",
            "get", "has", "him", "his", "how", "its", "may", "new",
            "now", "old", "see", "two", "way", "who", "with", "this",
            "that", "have", "from", "they", "will", "your", "when",
            "about", "been", "into", "more", "some", "than", "then",
            "time", "very", "what", "also"
        )
    }
}
