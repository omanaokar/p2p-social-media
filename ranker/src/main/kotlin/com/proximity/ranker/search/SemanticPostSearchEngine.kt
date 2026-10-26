package com.proximity.ranker.search

import com.proximity.contracts.FeedCandidate

/**
 * Semantic post search using dense sentence embeddings and cosine similarity.
 *
 * ── STATUS: ARCHITECTURE COMPLETE — NO DEFAULT EMBEDDING PROVIDER SHIPS ────────
 *
 * Genuine semantic search (finding posts by meaning, not just keyword overlap)
 * requires a pretrained sentence-embedding model. The most suitable lightweight
 * option for JVM is **all-MiniLM-L6-v2** via ONNX Runtime, whose combined
 * dependency footprint conflicts with this project's no-new-external-dependencies
 * policy and mobile-distribution budget:
 *
 * | Component                                    | Size      |
 * |----------------------------------------------|-----------|
 * | `com.microsoft.onnxruntime:onnxruntime`       | ~50 MB    |
 * | `all-MiniLM-L6-v2.onnx` model file           | ~22 MB    |
 * | `com.github.huggingface:tokenizers-java`      |  ~8 MB    |
 * | **Total new additions**                       | **~80 MB**|
 *
 * Alternative (DJL + PyTorch): similar footprint (~70 MB+ native libs).
 *
 * ── WHAT THIS CLASS DOES TODAY ───────────────────────────────────────────────
 * The wiring is complete and correct. Callers supply an [EmbeddingProvider];
 * this class handles:
 *   1. Null/blank query guard
 *   2. Post deduplication by ID
 *   3. Embedding every unique post body (skipping posts whose text cannot be
 *      embedded — they are silently excluded, not errors)
 *   4. Cosine similarity ranking (via [cosineSimilarity])
 *   5. Result limiting
 *
 * ── HOW TO ACTIVATE GENUINE SEMANTIC SEARCH ──────────────────────────────────
 * When the project approves the dependency budget, implement [EmbeddingProvider]
 * backed by ONNX Runtime:
 *
 * ```kotlin
 * class OnnxSentenceTransformerProvider(modelPath: String) : EmbeddingProvider {
 *     override val dimension = 384   // all-MiniLM-L6-v2
 *     private val session = OrtEnvironment.getEnvironment()
 *         .createSession(modelPath, OrtSession.SessionOptions())
 *     override fun embed(text: String?): FloatArray? { … }
 * }
 *
 * val search = SemanticPostSearchEngine(
 *     OnnxSentenceTransformerProvider("models/all-MiniLM-L6-v2.onnx")
 * )
 * ```
 *
 * For unit tests, use a [MockEmbeddingProvider] with pre-assigned vectors.
 *
 * ── WHAT THIS IS NOT ─────────────────────────────────────────────────────────
 * This file does NOT implement keyword matching or hand-written similarity rules
 * and does NOT label such approaches as semantic search. For keyword-based
 * retrieval use [KeywordPostSearchEngine].
 */
class SemanticPostSearchEngine(
    private val embeddingProvider: EmbeddingProvider
) : PostSearchEngine {

    override fun search(
        query: String?,
        posts: List<FeedCandidate>,
        limit: Int
    ): List<SearchResult> {
        if (query.isNullOrBlank()) return emptyList()

        val queryEmbedding = embeddingProvider.embed(query) ?: return emptyList()

        // Deduplicate by post ID before scoring
        val uniquePosts = posts.distinctBy { it.post.id }

        val results = uniquePosts.mapNotNull { candidate ->
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

        return if (limit > 0) results.take(limit) else results
    }
}
