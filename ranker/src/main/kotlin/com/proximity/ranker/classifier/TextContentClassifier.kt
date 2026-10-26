package com.proximity.ranker.classifier

/**
 * Contract for text content categorization and topic identification.
 */
interface TextContentClassifier {
    /**
     * Classifies the given text into educational topics, non-educational content,
     * or adult indicators.
     *
     * @param text The body text of a post or message.
     * @return [ClassificationResult] with determined category, topics, confidence, and scores.
     */
    fun classify(text: String?): ClassificationResult
}

/**
 * Lightweight, deterministic, offline heuristic text classifier.
 *
 * NOTE: This is a transparent rule-based and keyword/phrase heuristic classifier,
 * NOT a trained machine learning model. It evaluates domain-specific keyword densities,
 * phrase boundaries, and explicit indicators without external network or ML dependencies.
 */
class HeuristicTextClassifier : TextContentClassifier {

    private data class WeightedTerm(val term: String, val weight: Double)

    private val adultIndicators = listOf(
        "porn", "pornography", "xxx", "nsfw", "erotic", "nudity", "nude",
        "onlyfans", "sex video", "hardcore sex", "adult cam", "camgirl",
        "escort service", "adult entertainment"
    ).map { term ->
        val regex = Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
        regex
    }

    private val topicDictionaries: Map<EducationalTopic, List<WeightedTerm>> = mapOf(
        EducationalTopic.AI_ML to listOf(
            // High-weight multi-word phrases
            WeightedTerm("machine learning", 2.5),
            WeightedTerm("deep learning", 2.5),
            WeightedTerm("neural network", 2.5),
            WeightedTerm("neural networks", 2.5),
            WeightedTerm("gradient descent", 2.5),
            WeightedTerm("large language model", 2.5),
            WeightedTerm("supervised learning", 2.5),
            WeightedTerm("unsupervised learning", 2.5),
            WeightedTerm("reinforcement learning", 2.5),
            WeightedTerm("computer vision", 2.5),
            WeightedTerm("natural language processing", 2.5),
            WeightedTerm("random forest", 2.5),
            WeightedTerm("loss function", 2.0),
            WeightedTerm("fine-tuning", 2.0),
            WeightedTerm("fine tuning", 2.0),
            // Distinctive terms
            WeightedTerm("pytorch", 2.0),
            WeightedTerm("tensorflow", 2.0),
            WeightedTerm("backpropagation", 2.0),
            WeightedTerm("transformer", 1.8),
            WeightedTerm("transformers", 1.8),
            WeightedTerm("overfitting", 1.8),
            WeightedTerm("underfitting", 1.8),
            WeightedTerm("hyperparameter", 1.8),
            WeightedTerm("embeddings", 1.8),
            WeightedTerm("scikit-learn", 2.0),
            WeightedTerm("huggingface", 2.0),
            WeightedTerm("llm", 1.5),
            WeightedTerm("llms", 1.5),
            WeightedTerm("clustering", 1.2),
            WeightedTerm("perceptron", 1.5),
            WeightedTerm("ai", 1.0),
            WeightedTerm("ml", 1.0),
            WeightedTerm("nlp", 1.2)
        ),
        EducationalTopic.PROGRAMMING to listOf(
            WeightedTerm("object oriented programming", 2.5),
            WeightedTerm("functional programming", 2.5),
            WeightedTerm("design patterns", 2.5),
            WeightedTerm("design pattern", 2.5),
            WeightedTerm("dependency injection", 2.5),
            WeightedTerm("garbage collection", 2.5),
            WeightedTerm("memory leak", 2.5),
            WeightedTerm("exception handling", 2.0),
            WeightedTerm("unit test", 2.0),
            WeightedTerm("unit testing", 2.0),
            WeightedTerm("async await", 2.0),
            WeightedTerm("kotlin", 2.0),
            WeightedTerm("golang", 2.0),
            WeightedTerm("rust", 1.8),
            WeightedTerm("typescript", 1.8),
            WeightedTerm("javascript", 1.8),
            WeightedTerm("python", 1.5),
            WeightedTerm("c++", 2.0),
            WeightedTerm("c#", 2.0),
            WeightedTerm("java", 1.5),
            WeightedTerm("compiler", 1.8),
            WeightedTerm("interpreter", 1.8),
            WeightedTerm("coroutine", 2.0),
            WeightedTerm("coroutines", 2.0),
            WeightedTerm("polymorphism", 2.0),
            WeightedTerm("inheritance", 1.5),
            WeightedTerm("refactoring", 1.8),
            WeightedTerm("concurrency", 1.8),
            WeightedTerm("multithreading", 1.8),
            WeightedTerm("syntax", 1.2),
            WeightedTerm("bytecode", 1.8),
            WeightedTerm("debugging", 1.2)
        ),
        EducationalTopic.DSA to listOf(
            WeightedTerm("data structure", 2.5),
            WeightedTerm("data structures", 2.5),
            WeightedTerm("binary search", 2.5),
            WeightedTerm("binary search tree", 2.5),
            WeightedTerm("dynamic programming", 2.5),
            WeightedTerm("time complexity", 2.5),
            WeightedTerm("space complexity", 2.5),
            WeightedTerm("linked list", 2.5),
            WeightedTerm("hash table", 2.5),
            WeightedTerm("hashmap", 2.0),
            WeightedTerm("hash map", 2.0),
            WeightedTerm("priority queue", 2.5),
            WeightedTerm("topological sort", 2.5),
            WeightedTerm("sliding window", 2.5),
            WeightedTerm("two pointers", 2.5),
            WeightedTerm("avl tree", 2.5),
            WeightedTerm("red-black tree", 2.5),
            WeightedTerm("dijkstra", 2.2),
            WeightedTerm("quicksort", 2.2),
            WeightedTerm("mergesort", 2.2),
            WeightedTerm("bfs", 1.8),
            WeightedTerm("dfs", 1.8),
            WeightedTerm("trie", 2.0),
            WeightedTerm("memoization", 2.0),
            WeightedTerm("recursion", 1.5),
            WeightedTerm("recursive", 1.5),
            WeightedTerm("big o", 2.0),
            WeightedTerm("big-o", 2.0),
            WeightedTerm("stack", 1.2),
            WeightedTerm("queue", 1.2),
            WeightedTerm("heap", 1.5),
            WeightedTerm("algorithm", 1.5),
            WeightedTerm("algorithms", 1.5)
        ),
        EducationalTopic.CYBERSECURITY to listOf(
            WeightedTerm("cybersecurity", 2.5),
            WeightedTerm("penetration testing", 2.5),
            WeightedTerm("buffer overflow", 2.5),
            WeightedTerm("cross-site scripting", 2.5),
            WeightedTerm("sql injection", 2.5),
            WeightedTerm("zero day", 2.5),
            WeightedTerm("zero-day", 2.5),
            WeightedTerm("man in the middle", 2.5),
            WeightedTerm("public key infrastructure", 2.5),
            WeightedTerm("denial of service", 2.5),
            WeightedTerm("reverse engineering", 2.5),
            WeightedTerm("infosec", 2.2),
            WeightedTerm("vulnerability", 2.0),
            WeightedTerm("vulnerabilities", 2.0),
            WeightedTerm("exploit", 2.0),
            WeightedTerm("exploits", 2.0),
            WeightedTerm("xss", 2.0),
            WeightedTerm("sqli", 2.0),
            WeightedTerm("csrf", 2.0),
            WeightedTerm("malware", 2.0),
            WeightedTerm("ransomware", 2.0),
            WeightedTerm("phishing", 2.0),
            WeightedTerm("cryptography", 2.0),
            WeightedTerm("firewall", 1.8),
            WeightedTerm("mitm", 2.0),
            WeightedTerm("pki", 2.0),
            WeightedTerm("wireshark", 2.2),
            WeightedTerm("cve", 2.0),
            WeightedTerm("encryption", 1.5),
            WeightedTerm("decryption", 1.5),
            WeightedTerm("authentication", 1.2),
            WeightedTerm("authorization", 1.2)
        ),
        EducationalTopic.MATHEMATICS to listOf(
            WeightedTerm("linear algebra", 2.5),
            WeightedTerm("discrete math", 2.5),
            WeightedTerm("discrete mathematics", 2.5),
            WeightedTerm("differential equation", 2.5),
            WeightedTerm("differential equations", 2.5),
            WeightedTerm("matrix multiplication", 2.5),
            WeightedTerm("fourier transform", 2.5),
            WeightedTerm("modular arithmetic", 2.5),
            WeightedTerm("boolean algebra", 2.5),
            WeightedTerm("graph theory", 2.5),
            WeightedTerm("probability theory", 2.5),
            WeightedTerm("calculus", 2.0),
            WeightedTerm("eigenvalue", 2.2),
            WeightedTerm("eigenvalues", 2.2),
            WeightedTerm("eigenvector", 2.2),
            WeightedTerm("eigenvectors", 2.2),
            WeightedTerm("derivative", 1.8),
            WeightedTerm("derivatives", 1.8),
            WeightedTerm("integral", 1.8),
            WeightedTerm("integrals", 1.8),
            WeightedTerm("combinatorics", 2.0),
            WeightedTerm("trigonometry", 2.0),
            WeightedTerm("topology", 2.0),
            WeightedTerm("theorem", 1.5),
            WeightedTerm("lemma", 1.8),
            WeightedTerm("prime number", 2.0),
            WeightedTerm("primes", 1.2),
            WeightedTerm("matrices", 1.5),
            WeightedTerm("mathematics", 1.5),
            WeightedTerm("probability", 1.2),
            WeightedTerm("statistics", 1.2)
        ),
        EducationalTopic.CLOUD_COMPUTING to listOf(
            WeightedTerm("cloud computing", 2.5),
            WeightedTerm("cloud storage", 2.2),
            WeightedTerm("load balancer", 2.5),
            WeightedTerm("load balancing", 2.5),
            WeightedTerm("serverless computing", 2.5),
            WeightedTerm("cloud architecture", 2.5),
            WeightedTerm("virtual machine", 2.2),
            WeightedTerm("virtual machines", 2.2),
            WeightedTerm("service mesh", 2.5),
            WeightedTerm("infrastructure as code", 2.5),
            WeightedTerm("kubernetes", 2.2),
            WeightedTerm("k8s", 2.2),
            WeightedTerm("docker", 2.0),
            WeightedTerm("serverless", 1.8),
            WeightedTerm("microservices", 2.0),
            WeightedTerm("terraform", 2.2),
            WeightedTerm("autoscaling", 2.0),
            WeightedTerm("aws", 1.5),
            WeightedTerm("gcp", 1.5),
            WeightedTerm("azure", 1.5),
            WeightedTerm("devops", 1.8),
            WeightedTerm("helm", 1.8),
            WeightedTerm("container", 1.2),
            WeightedTerm("containers", 1.2)
        )
    )

    private val nonEducationalTerms = listOf(
        WeightedTerm("weekend vibes", 2.5),
        WeightedTerm("out with friends", 2.5),
        WeightedTerm("party", 1.8),
        WeightedTerm("parties", 1.8),
        WeightedTerm("dinner", 1.5),
        WeightedTerm("breakfast", 1.5),
        WeightedTerm("lunch", 1.5),
        WeightedTerm("brunch", 1.5),
        WeightedTerm("cocktails", 2.0),
        WeightedTerm("concert", 2.0),
        WeightedTerm("vacation", 2.0),
        WeightedTerm("beach", 1.8),
        WeightedTerm("shopping", 2.0),
        WeightedTerm("shoes", 1.8),
        WeightedTerm("fashion", 1.8),
        WeightedTerm("gossip", 2.0),
        WeightedTerm("dating", 2.0),
        WeightedTerm("netflix", 1.8),
        WeightedTerm("bored", 1.5),
        WeightedTerm("sale", 1.5),
        WeightedTerm("discount", 1.5),
        WeightedTerm("delicious food", 2.2),
        WeightedTerm("cute puppy", 2.2)
    )

    override fun classify(text: String?): ClassificationResult {
        if (text.isNullOrBlank()) {
            return ClassificationResult(
                category = PrimaryCategory.UNCERTAIN,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.UNCERTAIN,
                topicScores = emptyMap(),
                summary = "Input text is empty or blank."
            )
        }

        val cleanText = text.trim()
        val normalized = " ${cleanText.lowercase()} "

        // 1. Adult / Explicit Content Check (Priority Gate)
        val flaggedAdult = adultIndicators.any { it.containsMatchIn(cleanText) }
        if (flaggedAdult) {
            return ClassificationResult(
                category = PrimaryCategory.ADULT,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.HIGH,
                topicScores = emptyMap(),
                adultFlags = setOf(AdultFlag.EXPLICIT_SEXUAL),
                summary = "Detected explicit or adult content indicator."
            )
        }

        // 2. Score Educational Topics
        val scores = mutableMapOf<EducationalTopic, Double>()
        for ((topic, terms) in topicDictionaries) {
            var topicScore = 0.0
            for ((term, weight) in terms) {
                // Check whole-word / whole-phrase matches
                val pattern = Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
                val matchCount = pattern.findAll(cleanText).count()
                if (matchCount > 0) {
                    topicScore += weight + (matchCount - 1) * (weight * 0.4)
                }
            }
            if (topicScore > 0.0) {
                scores[topic] = topicScore
            }
        }

        // 3. Score Non-Educational / Casual Indicators
        var nonEduScore = 0.0
        for ((term, weight) in nonEducationalTerms) {
            val pattern = Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
            val matchCount = pattern.findAll(cleanText).count()
            if (matchCount > 0) {
                nonEduScore += weight + (matchCount - 1) * (weight * 0.4)
            }
        }

        val maxEduScore = scores.values.maxOrNull() ?: 0.0
        val wordCount = cleanText.split(Regex("\\s+")).count { it.isNotBlank() }

        // 4. Ambiguity / Insufficient Evidence Gate
        // Text is uncertain if:
        // - No educational matches and no non-educational matches
        // - Very short text with low signal
        // - Conflicting signals where non-educational and educational scores cancel out
        if (maxEduScore == 0.0 && nonEduScore == 0.0) {
            return ClassificationResult(
                category = PrimaryCategory.UNCERTAIN,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.UNCERTAIN,
                topicScores = emptyMap(),
                summary = "Insufficient topical evidence; ambiguous content."
            )
        }

        if (maxEduScore == 0.0 && nonEduScore > 0.0) {
            val conf = if (nonEduScore >= 2.0) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM
            return ClassificationResult(
                category = PrimaryCategory.NON_EDUCATIONAL,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = conf,
                topicScores = emptyMap(),
                summary = "Classified as casual or non-educational content."
            )
        }

        // Case: Both educational and non-educational signals present
        if (nonEduScore > maxEduScore * 1.5) {
            return ClassificationResult(
                category = PrimaryCategory.NON_EDUCATIONAL,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.MEDIUM,
                topicScores = scores,
                summary = "Casual content dominates with incidental technical keywords."
            )
        }

        // If the educational match is very weak in a long text or extremely short text
        if (maxEduScore < 1.5 && wordCount > 20) {
            return ClassificationResult(
                category = PrimaryCategory.UNCERTAIN,
                educationalTopics = emptySet(),
                primaryTopic = scores.maxByOrNull { it.value }?.key,
                confidence = ConfidenceLevel.UNCERTAIN,
                topicScores = scores,
                summary = "Low educational keyword density in conversational context; requires verification."
            )
        }

        // 5. Successful Educational Topic Categorization
        val primary = scores.maxByOrNull { it.value }?.key
        val matchedTopics = scores.filter { entry ->
            entry.value >= 1.5 || (entry.value >= 1.0 && entry.value >= maxEduScore * 0.6)
        }.keys

        val confidence = when {
            maxEduScore >= 3.0 -> ConfidenceLevel.HIGH
            maxEduScore >= 1.8 -> ConfidenceLevel.MEDIUM
            maxEduScore >= 1.0 -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.UNCERTAIN
        }

        val primaryCategory = if (confidence == ConfidenceLevel.UNCERTAIN) {
            PrimaryCategory.UNCERTAIN
        } else {
            PrimaryCategory.EDUCATIONAL
        }

        val summaryText = buildString {
            if (matchedTopics.size > 1) {
                append("Multi-disciplinary educational content. Primary: ${primary?.displayName ?: "Unknown"}. ")
                val others = matchedTopics.filter { it != primary }.joinToString { it.displayName }
                append("Additional: $others.")
            } else {
                append("Educational content focused on ${primary?.displayName ?: "general topics"}.")
            }
        }

        return ClassificationResult(
            category = primaryCategory,
            educationalTopics = matchedTopics,
            primaryTopic = primary,
            confidence = confidence,
            topicScores = scores,
            summary = summaryText
        )
    }
}
