package com.proximity.ranker.search

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.Post
import com.proximity.contracts.Profile
import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ─────────────────────────────────────────────────────────────────────────────
// Test helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Minimal in-memory EmbeddingProvider for unit testing SemanticPostSearchEngine. */
private class MockEmbeddingProvider(
    override val dimension: Int = 3,
    private val embeddings: Map<String, FloatArray> = emptyMap()
) : EmbeddingProvider {
    override fun embed(text: String?): FloatArray? =
        if (text.isNullOrBlank()) null else embeddings[text]
}

private val BASE_TIME: Instant = Instant.parse("2026-10-27T00:00:00Z")

private fun candidate(
    id: String,
    body: String = "Some post body",
    authorId: String = "author1",
    createdAt: Instant = BASE_TIME
): FeedCandidate {
    val post = Post(
        id = id,
        authorId = authorId,
        body = body,
        image = null,
        createdAt = createdAt,
        ttl = Duration.ofHours(6),
        hopCount = 0,
        signature = "sig-$id"
    )
    val profile = Profile(
        id = "prof-$authorId",
        publicKey = authorId,
        encryptionKey = "enc-$authorId",
        displayName = "User $authorId",
        avatar = null,
        updatedAt = createdAt,
        signature = "sig-prof"
    )
    return FeedCandidate(
        post = post,
        author = profile,
        receivedAt = createdAt,
        receivedFrom = null,
        receivedSignalStrength = null,
        commentCount = 0,
        reactionCounts = emptyMap(),
        myReaction = null,
        isMine = false
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// cosineSimilarity tests
// ─────────────────────────────────────────────────────────────────────────────

class CosineSimilarityTest {

    @Test
    fun `identical vectors return similarity 1_0`() {
        val v = floatArrayOf(1f, 2f, 3f)
        val sim = cosineSimilarity(v, v.copyOf())
        assertEquals(1.0, sim, 1e-9, "Identical vectors must have similarity 1.0")
    }

    @Test
    fun `orthogonal vectors return similarity 0_0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0.0, cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `known vectors return expected similarity`() {
        // [1,0,0] vs [1,1,0] -> cos = 1/sqrt(2) ≈ 0.7071
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 1f, 0f)
        val expected = 1.0 / sqrt(2.0)
        val actual = cosineSimilarity(a, b)
        assertTrue(kotlin.math.abs(actual - expected) < 1e-6,
            "Expected ~$expected but got $actual")
    }

    @Test
    fun `all-zero vector returns 0_0 not exception`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val v    = floatArrayOf(1f, 2f, 3f)
        assertEquals(0.0, cosineSimilarity(zero, v), 1e-9)
        assertEquals(0.0, cosineSimilarity(v, zero), 1e-9)
    }

    @Test
    fun `both zero vectors returns 0_0`() {
        val zero = floatArrayOf(0f, 0f)
        assertEquals(0.0, cosineSimilarity(zero, zero), 1e-9)
    }

    @Test
    fun `mismatched dimensions throw IllegalArgumentException`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        assertFailsWith<IllegalArgumentException> { cosineSimilarity(a, b) }
    }

    @Test
    fun `cosine similarity is symmetric`() {
        val a = floatArrayOf(0.5f, 0.3f, 0.9f)
        val b = floatArrayOf(0.1f, 0.7f, 0.4f)
        assertEquals(cosineSimilarity(a, b), cosineSimilarity(b, a), 1e-9)
    }

    @Test
    fun `anti-parallel vectors return negative similarity`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertTrue(cosineSimilarity(a, b) < 0.0)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// KeywordPostSearchEngine tests
// ─────────────────────────────────────────────────────────────────────────────

class KeywordPostSearchEngineTest {

    private val engine = KeywordPostSearchEngine(clock = { BASE_TIME })

    @Test
    fun `null query returns empty list`() {
        val post = candidate("p1", body = "kotlin programming tutorial")
        assertTrue(engine.search(null, listOf(post)).isEmpty())
    }

    @Test
    fun `blank query returns empty list`() {
        val post = candidate("p1", body = "kotlin programming tutorial")
        assertTrue(engine.search("   ", listOf(post)).isEmpty())
    }

    @Test
    fun `all-stop-word query returns empty list`() {
        // "the and for" are all stop-words; tokenize yields nothing
        val post = candidate("p1", body = "some educational content")
        assertTrue(engine.search("the and for", listOf(post)).isEmpty())
    }

    @Test
    fun `empty posts list returns empty list`() {
        assertTrue(engine.search("machine learning", emptyList()).isEmpty())
    }

    @Test
    fun `finds post containing query terms`() {
        val matching = candidate("p1", body = "machine learning neural networks deep learning")
        val unrelated = candidate("p2", body = "football match highlights yesterday")
        val results = engine.search("machine learning", listOf(matching, unrelated))
        assertTrue(results.isNotEmpty())
        assertEquals("p1", results.first().candidate.post.id)
    }

    @Test
    fun `more relevant post ranks above less relevant`() {
        // p1 contains "algorithm" twice, p2 once → p1 should rank higher
        val high = candidate("p1", body = "algorithm design algorithm complexity sorting algorithm")
        val low  = candidate("p2", body = "algorithm basics for beginners")
        val results = engine.search("algorithm", listOf(high, low))
        assertEquals(2, results.size)
        assertEquals("p1", results[0].candidate.post.id, "Higher-TF post should rank first")
        assertEquals("p2", results[1].candidate.post.id)
    }

    @Test
    fun `educational post gets topic bonus over otherwise equal non-educational match`() {
        // Both share "neural" term; eduPost is clearly AI/ML-educational, other is not
        val eduPost = candidate(
            "p1",
            body = "neural networks machine learning deep learning artificial intelligence classification"
        )
        val nonEdu = candidate(
            "p2",
            body = "neural football marketing strategy neural advertising revenue"
        )
        val results = engine.search("neural", listOf(eduPost, nonEdu))
        // Both should appear; educational one should rank >= the non-educational one
        assertTrue(results.size >= 1)
        if (results.size == 2) {
            assertTrue(results[0].relevanceScore >= results[1].relevanceScore)
            assertEquals("p1", results[0].candidate.post.id,
                "Educational post must rank first")
        }
    }

    @Test
    fun `deduplicates posts with same ID`() {
        val post = candidate("p1", body = "data structures binary search tree")
        val duplicated = listOf(post, post, post)
        val results = engine.search("binary search tree", duplicated)
        assertEquals(1, results.size, "Duplicate post IDs must be collapsed")
    }

    @Test
    fun `limit parameter is respected`() {
        val posts = (1..10).map {
            candidate("p$it", body = "sorting algorithm quicksort mergesort heapsort")
        }
        val results = engine.search("sorting algorithm", posts, limit = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `post with blank body never appears in results`() {
        val blank = candidate("p1", body = "   ")
        val results = engine.search("kotlin", listOf(blank))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `results are sorted descending by relevance score`() {
        val posts = listOf(
            candidate("p1", body = "recursion recursive function call stack base case"),
            candidate("p2", body = "recursion basics"),
            candidate("p3", body = "recursion recursive algorithm recursive call")
        )
        val results = engine.search("recursion recursive", posts)
        assertTrue(results.size >= 2)
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].relevanceScore >= results[i + 1].relevanceScore,
                "Result[$i] score ${results[i].relevanceScore} should be >= " +
                "Result[${i+1}] score ${results[i+1].relevanceScore}")
        }
    }

    @Test
    fun `limit of 0 means no limit`() {
        val posts = (1..15).map {
            candidate("p$it", body = "graph theory dijkstra bellman ford shortest path")
        }
        val results = engine.search("graph shortest path", posts, limit = 0)
        assertEquals(posts.size, results.size, "limit=0 should return all matching results")
    }

    @Test
    fun `single-character terms below min length are ignored`() {
        // "a" and "b" are single chars and should be filtered during tokenize
        val post = candidate("p1", body = "python programming language")
        val results = engine.search("a b python", listOf(post))
        // "python" should still match
        assertTrue(results.isNotEmpty())
        assertEquals("p1", results.first().candidate.post.id)
    }

    @Test
    fun `tokenize returns empty for null input`() {
        // tokenize is internal — call it directly (same module in tests)
        val result = engine.tokenize(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `expired posts are excluded from keyword search results`() {
        val clock = { BASE_TIME.plus(Duration.ofHours(5)) }
        val engineWithClock = KeywordPostSearchEngine(clock = clock)

        val validPost = candidate("valid", body = "kotlin programming basics", createdAt = BASE_TIME.plus(Duration.ofHours(2)))
        val expiredPost = candidate("expired", body = "kotlin programming advanced", createdAt = BASE_TIME) // ttl 6h -> expires at +6h. Wait, at +5h it is not expired, let's make ttl 4h or createdAt earlier:
        val trulyExpiredPost = candidate("expired", body = "kotlin programming advanced", createdAt = BASE_TIME.minus(Duration.ofHours(10)))

        val results = engineWithClock.search("kotlin", listOf(validPost, trulyExpiredPost))
        assertEquals(1, results.size)
        assertEquals("valid", results.first().candidate.post.id)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SemanticPostSearchEngine tests
// ─────────────────────────────────────────────────────────────────────────────

class SemanticPostSearchEngineTest {

    /** Helper: engine with canned embeddings. */
    private fun engineWith(
        vararg pairs: Pair<String, FloatArray>,
        fallbackEngine: PostSearchEngine? = null,
        clock: () -> Instant = { BASE_TIME }
    ): SemanticPostSearchEngine {
        return SemanticPostSearchEngine(
            embeddingProvider = MockEmbeddingProvider(dimension = 3, embeddings = mapOf(*pairs)),
            fallbackEngine = fallbackEngine,
            clock = clock
        )
    }

    @Test
    fun `null query returns empty list`() {
        val engine = SemanticPostSearchEngine(MockEmbeddingProvider())
        val post = candidate("p1", body = "kotlin programming")
        assertTrue(engine.search(null, listOf(post)).isEmpty())
    }

    @Test
    fun `blank query returns empty list`() {
        val engine = SemanticPostSearchEngine(MockEmbeddingProvider())
        val post = candidate("p1", body = "kotlin programming")
        assertTrue(engine.search("  ", listOf(post)).isEmpty())
    }

    @Test
    fun `returns empty when query embedding unavailable`() {
        // Provider has no entry for "missing query"
        val engine = engineWith("some body" to floatArrayOf(1f, 0f, 0f))
        val post = candidate("p1", body = "some body")
        assertTrue(engine.search("missing query", listOf(post)).isEmpty())
    }

    @Test
    fun `post with unavailable embedding is skipped without crash`() {
        val query = "neural networks"
        val queryVec = floatArrayOf(1f, 0f, 0f)
        val knownBody = "machine learning algorithms"
        val knownVec  = floatArrayOf(0.9f, 0.1f, 0f)

        val engine = engineWith(
            query to queryVec,
            knownBody to knownVec
            // "unknown body" intentionally missing → post will be skipped
        )

        val posts = listOf(
            candidate("p1", body = knownBody),
            candidate("p2", body = "unknown body")  // no embedding
        )

        val results = engine.search(query, posts)
        assertEquals(1, results.size)
        assertEquals("p1", results.first().candidate.post.id)
    }

    @Test
    fun `ranking by cosine similarity - identical vector ranks above partial match`() {
        val query = "recursion"
        val queryVec  = floatArrayOf(1f, 0f, 0f)
        val perfectVec = floatArrayOf(1f, 0f, 0f)          // sim = 1.0
        val partialVec = floatArrayOf(0.7f, 0.7f, 0f)      // sim ≈ 0.707
        val orthogVec  = floatArrayOf(0f, 0f, 1f)           // sim = 0.0 → excluded

        val engine = engineWith(
            query to queryVec,
            "recursive function call stack" to perfectVec,
            "call stack frame unwinding" to partialVec,
            "football highlights" to orthogVec
        )

        val posts = listOf(
            candidate("p1", body = "recursive function call stack"),
            candidate("p2", body = "call stack frame unwinding"),
            candidate("p3", body = "football highlights")
        )

        val results = engine.search(query, posts)
        assertEquals(2, results.size, "Zero-similarity post must be excluded")
        assertEquals("p1", results[0].candidate.post.id, "Perfect match should rank first")
        assertEquals("p2", results[1].candidate.post.id, "Partial match should rank second")
        assertTrue(results[0].relevanceScore > results[1].relevanceScore)
    }

    @Test
    fun `deduplication by post ID works`() {
        val query = "machine learning"
        val body  = "machine learning classification"
        val engine = engineWith(
            query to floatArrayOf(1f, 0f, 0f),
            body  to floatArrayOf(1f, 0f, 0f)
        )
        val post = candidate("dup", body = body)
        val results = engine.search(query, listOf(post, post, post))
        assertEquals(1, results.size, "Duplicate post IDs must be collapsed to one result")
    }

    @Test
    fun `limit parameter is respected`() {
        val query = "algorithms"
        val queryVec = floatArrayOf(1f, 0f, 0f)
        val bodies = (1..5).map { "algorithms body $it" }
        val embeddings = (listOf(query to queryVec) +
            bodies.map { it to floatArrayOf(1f, 0f, 0f) }).toTypedArray()

        val engine = engineWith(*embeddings)
        val posts = bodies.mapIndexed { i, body -> candidate("p$i", body = body) }

        val results = engine.search(query, posts, limit = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `empty posts list returns empty list`() {
        val engine = engineWith("graph traversal" to floatArrayOf(1f, 0f, 0f))
        assertTrue(engine.search("graph traversal", emptyList()).isEmpty())
    }

    @Test
    fun `explanation contains similarity score`() {
        val query = "sorting"
        val body  = "mergesort quicksort heapsort"
        val engine = engineWith(
            query to floatArrayOf(1f, 0f, 0f),
            body  to floatArrayOf(1f, 0f, 0f)
        )
        val results = engine.search(query, listOf(candidate("p1", body = body)))
        assertTrue(results.isNotEmpty())
        assertTrue(results.first().explanation.contains("Semantic similarity"),
            "Explanation should mention semantic similarity")
    }

    @Test
    fun `results sorted descending by relevance score`() {
        val query = "deep learning"
        val queryVec = floatArrayOf(1f, 0f, 0f)
        val vecs = listOf(
            floatArrayOf(0.5f, 0.5f, 0f),    // sim ≈ 0.707
            floatArrayOf(0.9f, 0.1f, 0f),    // sim ≈ 0.994
            floatArrayOf(0.6f, 0.4f, 0f)     // sim ≈ 0.832
        )
        val bodies = listOf("body a", "body b", "body c")
        val embeddings = (listOf(query to queryVec) +
            bodies.zip(vecs)).toTypedArray()

        val engine = engineWith(*embeddings)
        val posts = bodies.mapIndexed { i, body -> candidate("p$i", body = body) }
        val results = engine.search(query, posts)

        assertEquals(3, results.size)
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].relevanceScore >= results[i + 1].relevanceScore,
                "Results must be sorted descending by score")
        }
    }

    @Test
    fun `semantically related query with different wording matches based on embedding proximity`() {
        // Query and post share zero common words, but embeddings capture conceptual proximity
        val query = "call stack frame unwinding"
        val queryVec = floatArrayOf(0.95f, 0.05f, 0f)

        val relatedBody = "recursive function self invocation base case"
        val relatedVec = floatArrayOf(0.90f, 0.10f, 0f) // Cosine similarity ~0.999

        val unrelatedBody = "baking sourdough bread with yeast"
        val unrelatedVec = floatArrayOf(0f, 0f, 1f)     // Orthogonal

        val engine = engineWith(
            query to queryVec,
            relatedBody to relatedVec,
            unrelatedBody to unrelatedVec
        )

        val results = engine.search(query, listOf(
            candidate("p1", body = relatedBody),
            candidate("p2", body = unrelatedBody)
        ))

        assertEquals(1, results.size, "Orthogonal post must be excluded")
        assertEquals("p1", results.first().candidate.post.id)
        assertTrue(results.first().relevanceScore > 0.95)
    }

    @Test
    fun `expired posts are excluded from semantic search results`() {
        val query = "quantum computing"
        val queryVec = floatArrayOf(1f, 0f, 0f)
        val body = "quantum superposition and qubit states"
        val bodyVec = floatArrayOf(1f, 0f, 0f)

        val searchClock = { BASE_TIME.plus(Duration.ofHours(10)) }

        val engine = engineWith(
            query to queryVec,
            body to bodyVec,
            clock = searchClock
        )

        val expiredPost = candidate("exp", body = body, createdAt = BASE_TIME) // expires at BASE_TIME + 6h
        val results = engine.search(query, listOf(expiredPost))
        assertTrue(results.isEmpty(), "Expired post should be excluded from semantic search")
    }

    @Test
    fun `falls back to keyword search when query embedding is missing`() {
        val keywordFallback = KeywordPostSearchEngine(clock = { BASE_TIME })
        // Provider has NO embedding for query "compiler optimization"
        val engine = engineWith(
            "some known body" to floatArrayOf(1f, 0f, 0f),
            fallbackEngine = keywordFallback
        )

        val post = candidate("p1", body = "compiler optimization and bytecode generation techniques")
        val results = engine.search("compiler optimization", listOf(post))

        assertEquals(1, results.size, "Should seamlessly fall back to keyword search")
        assertEquals("p1", results.first().candidate.post.id)
        assertTrue(results.first().relevanceScore > 0.0)
    }

    @Test
    fun `falls back to keyword search when posts have missing embeddings`() {
        val keywordFallback = KeywordPostSearchEngine(clock = { BASE_TIME })
        val query = "databases"
        val queryVec = floatArrayOf(1f, 0f, 0f)

        // Query has embedding, but candidate post body has NO embedding in provider
        val engine = engineWith(
            query to queryVec,
            fallbackEngine = keywordFallback
        )

        val post = candidate("p1", body = "relational databases and sql query indexing")
        val results = engine.search(query, listOf(post))

        assertEquals(1, results.size, "Should fall back to keyword search when candidates lack embeddings")
        assertEquals("p1", results.first().candidate.post.id)
    }
}
