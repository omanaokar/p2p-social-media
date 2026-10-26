package com.proximity.ranker.search

import com.proximity.contracts.FeedCandidate
import java.time.Instant

/**
 * Semantic post search using dense sentence embeddings and cosine similarity.
 *
 * ── STATUS: ARCHITECTURAL PIPELINE COMPLETE — EMBEDDING PROVIDER CONFIGURABLE ──
 *
 * Genuine semantic search (finding posts by meaning, not just keyword overlap)
 * requires a pretrained sentence-embedding model. The most suitable lightweight
 * option for JVM is **all-MiniLM-L6-v2** via ONNX Runtime:
 *
 * | Component                                    | Size      | License      |
 * |----------------------------------------------|-----------|--------------|
 * | `all-MiniLM-L6-v2.onnx` (INT8 quantized)     | ~23 MB    | Apache 2.0   |
 * | `com.microsoft.onnxruntime:onnxruntime`       | ~50 MB    | MIT          |
 * | HuggingFace Tokenizers (`tokenizers-java`)   |  ~8 MB    | Apache 2.0   |
 * | **Total new additions**                       | **~80 MB**| Permissive   |
 *
 * ── TRADE-OFF ANALYSIS & WHY ZERO-DEPENDENCY CONSTRAINT PREVENTS BUNDLING ───
 * 1. Package Size: Adding ~80 MB of native shared libraries (.so, .dll, .dylib)
 *    and model weights increases the pure Kotlin JVM repository size by over
 *    8,000%, conflicting with mobile package limits and P2P mesh distribution.
 * 2. Inference Overhead: On-device CPU inference takes ~15-40ms per query and
 *    ~20ms per post. Without persistent caching of post vector embeddings,
 *    re-embedding a 100-post feed on every search causes noticeable UI latency.
 * 3. Zero-External-Dependency Rule: `:ranker` strictly depends only on `:contracts`.
 *    Adding native C++ runtimes breaks this architectural boundary.
 *
 * ── CONCRETE INTEGRATION PATH TO BUNDLE A REAL MODEL ─────────────────────────
 * When the dependency budget allows, follow these steps to activate on-device ONNX inference:
 *
 * 1. Add ONNX Runtime and Tokenizers to `gradle/libs.versions.toml`:
 *    ```toml
 *    onnxruntime = "1.17.3"
 *    tokenizers = "0.15.1"
 *    ```
 * 2. Place `all-MiniLM-L6-v2.onnx` (INT8, 23MB) and `vocab.txt` (232KB) in `resources/models/`.
 * 3. Implement [EmbeddingProvider]:
 *    ```kotlin
 *    class OnnxSentenceTransformerProvider(
 *        modelBytes: ByteArray,
 *        tokenizer: HuggingFaceTokenizer
 *    ) : EmbeddingProvider {
 *        override val dimension = 384
 *        private val session = OrtEnvironment.getEnvironment().createSession(modelBytes)
 *        override fun embed(text: String?): FloatArray? {
 *            if (text.isNullOrBlank()) return null
 *            val encoding = tokenizer.encode(text)
 *            // Run session on input_ids, attention_mask, token_type_ids
 *            // Compute mean-pooling with attention mask, apply L2-normalization
 *            return normalizedVector
 *        }
 *    }
 *    ```
 * 4. Pass the provider to [SemanticPostSearchEngine] with [fallbackEngine] configured.
 *
 * ── FALLBACK BEHAVIOUR ───────────────────────────────────────────────────────
 * When [embeddingProvider] cannot embed a query or candidates (e.g. model unavailable,
 * out-of-vocabulary, or unconfigured), this engine seamlessly delegates to [fallbackEngine]
 * (typically [KeywordPostSearchEngine]).
 */
class SemanticPostSearchEngine(
    private val embeddingProvider: EmbeddingProvider,
    private val fallbackEngine: PostSearchEngine? = null,
    private val clock: () -> Instant = { Instant.now() }
) : PostSearchEngine {

    override fun search(
        query: String?,
        posts: List<FeedCandidate>,
        limit: Int
    ): List<SearchResult> {
        if (query.isNullOrBlank()) return emptyList()

        val currentTime = clock()

        // 1. Deduplicate by post ID and exclude expired posts
        val uniqueNonExpiredPosts = posts
            .distinctBy { it.post.id }
            .filter { !it.post.expiresAt.isBefore(currentTime) }

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

        // 4. If no semantic results were produced (e.g. posts had missing embeddings) and fallback exists, delegate
        if (results.isEmpty() && fallbackEngine != null) {
            return fallbackEngine.search(query, uniqueNonExpiredPosts, limit)
        }

        return if (limit > 0) results.take(limit) else results
    }
}
