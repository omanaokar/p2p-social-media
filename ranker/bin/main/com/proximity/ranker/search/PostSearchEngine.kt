package com.proximity.ranker.search

import com.proximity.contracts.FeedCandidate
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Public models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single ranked search result.
 *
 * @param candidate The matching post with its full local metadata.
 * @param relevanceScore Normalised score (higher = more relevant). The scale
 *   depends on the implementation — keyword BM25 and cosine-similarity scores
 *   are not directly comparable across engines.
 * @param explanation Human-readable reason for the match.
 */
data class SearchResult(
    val candidate: FeedCandidate,
    val relevanceScore: Double,
    val explanation: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Core contracts
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Contract for local post search. Implementations may range from keyword
 * matching to full sentence-embedding semantic search.
 *
 * Contract guarantees:
 * - Null or blank queries return an empty list (never throw).
 * - Results are deduplicated by post ID.
 * - Results are ranked descending by [SearchResult.relevanceScore].
 * - Only the provided [posts] are searched; no network calls are made.
 */
interface PostSearchEngine {
    /**
     * @param query   Natural-language search query.
     * @param posts   Caller-supplied candidate posts (typically non-expired local posts).
     * @param limit   Maximum results to return. 0 = no limit.
     * @return        Ranked, deduplicated results.
     */
    fun search(query: String?, posts: List<FeedCandidate>, limit: Int = 20): List<SearchResult>
}

/**
 * Produces a dense floating-point embedding for a text input.
 *
 * Designed as an abstraction point so [SemanticPostSearchEngine] can accept a
 * real pretrained model when project constraints allow it. See
 * [SemanticPostSearchEngine]'s class-level KDoc for trade-off details.
 */
interface EmbeddingProvider {
    /** Dimensionality of every embedding produced by this provider. */
    val dimension: Int

    /**
     * Returns a dense embedding vector for [text], or null when the text
     * cannot be processed (empty, too long, runtime unavailable, etc.).
     */
    fun embed(text: String?): FloatArray?
}

// ─────────────────────────────────────────────────────────────────────────────
// Utility
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cosine similarity between two equal-dimension float vectors.
 *
 * Returns 0.0 when either vector is all-zero (undefined cosine similarity
 * treated as no similarity rather than an error).
 *
 * @throws IllegalArgumentException if the vectors differ in size.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "Vectors must have the same dimension: ${a.size} vs ${b.size}" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot   += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom < 1e-10) 0.0 else dot / denom
}
