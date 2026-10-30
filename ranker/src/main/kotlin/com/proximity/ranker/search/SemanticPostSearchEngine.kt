package com.proximity.ranker.search

import com.proximity.contracts.FeedCandidate
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.PrimaryCategory
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.moderation.ContentModerator
import java.time.Instant

/**
 * Semantic post search that matches documents using cosine similarity between
 * dense text embeddings.
 */
class SemanticPostSearchEngine(
    private val embeddingProvider: EmbeddingProvider,
    private val fallbackEngine: PostSearchEngine? = null,
    private val classifier: TextContentClassifier = HeuristicTextClassifier(),
    private val moderator: ContentModerator? = null,
    private val clock: () -> Instant = { Instant.now() }
) : PostSearchEngine {

    override fun search(
        query: String?,
        posts: List<FeedCandidate>,
        limit: Int
    ): List<SearchResult> {
        if (query.isNullOrBlank()) return emptyList()

        val currentTime = clock()

        // 1. Deduplicate by post ID and exclude expired and unapproved posts
        val uniqueNonExpiredPosts = posts
            .distinctBy { it.post.id }
            .filter { !it.post.expiresAt.isBefore(currentTime) }
            .filter { candidate ->
                if (moderator != null) {
                    moderator.evaluate(candidate.post.body).isApproved
                } else {
                    true
                }
            }

        if (uniqueNonExpiredPosts.isEmpty()) return emptyList()

        // 2. Obtain query embedding vector
        val queryEmbedding = embeddingProvider.embed(query)
        if (queryEmbedding == null) {
            // Semantic embedding unavailable for query -> delegate to fallback engine if configured
            return fallbackEngine?.search(query, uniqueNonExpiredPosts, limit) ?: emptyList()
        }

        // 3. Compute cosine similarity against candidate posts
        val results = uniqueNonExpiredPosts.mapNotNull { candidate ->
            val text = candidate.post.body
            val postEmbedding = embeddingProvider.embed(text) ?: return@mapNotNull null

            val similarity = cosineSimilarity(queryEmbedding, postEmbedding)
            if (similarity <= 0.0) null
            else SearchResult(
                candidate = candidate,
                relevanceScore = similarity,
                explanation = "Semantic similarity: ${"%.3f".format(similarity)}"
            )
        }.sortedByDescending { it.relevanceScore }

        // 4. If no semantic results were produced and fallback exists, delegate
        if (results.isEmpty() && fallbackEngine != null) {
            return fallbackEngine.search(query, uniqueNonExpiredPosts, limit)
        }

        return if (limit > 0) results.take(limit) else results
    }
}
