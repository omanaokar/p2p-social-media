package com.proximity.ranker.classifier

/**
 * Contract for text content categorization and topic identification.
 */
interface TextContentClassifier {
    /**
     * Classifies the given text into educational topics, non-educational content,
     * inappropriate content, or uncertain relevance.
     *
     * @param text The body text of a post or message.
     * @return [ClassificationResult] with determined category, topics, confidence, and scores.
     */
    fun classify(text: String?): ClassificationResult
}

/**
 * Lightweight, deterministic, offline contextual heuristic text classifier.
 *
 * NOTE: This is a transparent rule-based and phrase/density heuristic classifier,
 * NOT a trained machine learning model. It evaluates domain-specific keyword densities,
 * phrase boundaries, syntax markers, intent signals, and anti-educational/dismissal
 * patterns without external network or ML dependencies.
 */
class HeuristicTextClassifier : TextContentClassifier {

    private data class WeightedTerm(val term: String, val weight: Double)

    // Explicit sexual / adult content indicators
    private val adultIndicators = listOf(
        "porn", "pornography", "xxx", "nsfw", "erotic", "nudity", "nude", "naked",
        "onlyfans", "sex video", "hardcore sex", "adult cam", "camgirl", "blowjob",
        "escort service", "adult entertainment", "hentai", "penis", "vagina", "dildo",
        "sex chat", "strip club", "incest", "anal sex"
    ).map { term -> Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE) }

    // Abusive, violent, hate speech, or prohibited content indicators
    private val inappropriateIndicators = listOf(
        "kill yourself", "kys", "die in a fire", "hang yourself", "go die",
        "death threat", "bomb threat", "mass shooting", "terrorist attack",
        "buy drugs", "sell drugs", "buy cocaine", "sell cocaine", "buy heroin",
        "buy meth", "buy weed",
        // Profanity, cuss words and personal abuse (English)
        "fuck", "fucks", "fucked", "fucking", "fucker", "motherfucker",
        "shit", "shitty", "bullshit", "horseshit", "bitch", "bitches", "asshole", "arsehole",
        "dumbass", "jackass", "bastard", "dickhead", "cunt", "twat", "wanker", "slut",
        "whore", "piss off", "screw you", "shut the fuck up", "stfu",
        // Profanity and abuse (Hindi / Marathi, romanised)
        "madarchod", "maderchod", "behenchod", "bhenchod", "bhosdike", "bhosdi", "bhosda",
        "chutiya", "chutiye", "gandu", "harami", "haramkhor", "lauda", "lavda", "bhadwa", "bhadva",
        "saala kutta", "tujhi aai", "aaichi gand", "faggot", "nigger"
    ).map { term -> Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE) }

    // Anti-educational dismissals, negations, and casual misuse of academic keywords
    private val antiEducationalPatterns = listOf(
        Regex("\\b(skip|skipping|skipped)\\b.*\\b(class|lecture|school|study|studying|homework|exam|test|calculus|math|physics)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(hate|hating|despise)\\b.*\\b(homework|studying|study|math|coding|physics|school|classes|calculus|exams)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(bored of|tired of)\\b.*\\b(studying|homework|coding|math|physics|school)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(too bored to|can't be bothered to)\\b.*\\b(study|do homework|code)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(used|using|use)\\b.*\\b(as a|for a|as|for)\\b.*\\b(mousepad|doorstop|pillow|coaster|firewood)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(textbook|textbooks|notes|laptop|calculator)\\b.*\\b(mousepad|doorstop|pillow|coaster|firewood)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(mousepad|doorstop|pillow|coaster|firewood)\\b.*\\b(textbook|textbooks|notes|laptop|calculator)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(failed|fail)\\b.*\\b(exam|test|midterm|class|physics|calculus)\\b.*\\b(so|let's|haha|lol|party|beer)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(don't want to|not gonna)\\b.*\\b(study|do homework|go to class)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(watch|watching|play|playing|grab)\\b.*\\b(netflix|movie|anime|game|games|beer|tacos)\\b.*\\binstead of\\b.*\\b(study|studying|homework|class)\\b", RegexOption.IGNORE_CASE),
        Regex("\\binstead of\\b.*\\b(studying|homework|class|lecture)\\b.*\\b(party|watch|play|sleep|hang out)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(studying|homework|study|class)\\b.*\\b(is so boring|sucks|is lame)\\b.*\\b(watching|gaming|anime|netflix|playing)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(ai|robots)\\b.*\\b(will take over the world anyway|is taking over)\\b.*\\b(why study|so why|let's party)\\b", RegexOption.IGNORE_CASE)
    )

    // Commercial ads, spam, book selling, marketplace listings
    private val commercialSpamPatterns = listOf(
        Regex("\\b(dm|message|inbox|contact)\\s+(me\\s+)?(for\\s+price|to\\s+buy|for\\s+link|for\\s+discount)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(sell|selling|for sale)\\b.*\\b(textbook|textbooks|notes|calculator|course)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(use\\s+promo\\s+code|discount\\s+code|coupon\\s+code)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(earn|make)\\s+\\$?[0-9]+\\s*(daily|fast|per\\s+day|online)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(crypto|bitcoin|ethereum|solana)\\s+(giveaway|investment|doubler|airdrop|pump)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(forex|binary\\s+options|trading\\s+signals)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(click\\s+here\\s+to\\s+buy|order\\s+now\\s+at)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(follow\\s+my\\s+instagram|subscribe\\s+to\\s+my\\s+channel|check\\s+my\\s+onlyfans)\\b", RegexOption.IGNORE_CASE)
    )

    // Polysemous / ambiguous words that only qualify as educational when supported by context
    private val polysemousTerms = setOf(
        "tree", "trees", "stack", "stacks", "queue", "queues", "rust", "matrix", "matrices",
        "cloud", "clouds", "heap", "heaps", "bug", "bugs", "spring", "terminal", "terminals",
        "net", "nets", "thread", "threads", "apple", "server", "servers", "driver", "drivers",
        "root", "roots", "branch", "branches", "fork", "forks", "pipe", "pipes", "channel", "channels"
    )

    // Educational intent and structural patterns
    private val educationalIntentTerms = listOf(
        WeightedTerm("tutorial", 2.0),
        WeightedTerm("tutorials", 2.0),
        WeightedTerm("how to implement", 2.5),
        WeightedTerm("how to solve", 2.5),
        WeightedTerm("how does", 1.8),
        WeightedTerm("why does", 1.8),
        WeightedTerm("can someone explain", 2.2),
        WeightedTerm("help me understand", 2.2),
        WeightedTerm("need help with", 1.8),
        WeightedTerm("question about", 1.8),
        WeightedTerm("explanation of", 2.5),
        WeightedTerm("explaining", 1.8),
        WeightedTerm("step by step guide", 2.5),
        WeightedTerm("study notes", 2.5),
        WeightedTerm("lecture notes", 2.5),
        WeightedTerm("revision notes", 2.2),
        WeightedTerm("exam preparation", 2.2),
        WeightedTerm("preparing for an exam", 2.2),
        WeightedTerm("studying for", 2.0),
        WeightedTerm("learning about", 1.8),
        WeightedTerm("working on", 1.5),
        WeightedTerm("assignment help", 2.0),
        WeightedTerm("homework help", 2.0),
        WeightedTerm("lecture summary", 2.5),
        WeightedTerm("time complexity", 2.5),
        WeightedTerm("space complexity", 2.5),
        WeightedTerm("asymptotic analysis", 2.5),
        WeightedTerm("proof by induction", 2.5),
        WeightedTerm("mathematical proof", 2.5),
        WeightedTerm("theorem", 2.0),
        WeightedTerm("definition of", 2.0),
        WeightedTerm("architecture overview", 2.2),
        WeightedTerm("code snippet", 2.0),
        WeightedTerm("practice problem", 2.2),
        WeightedTerm("exercise", 1.5),
        WeightedTerm("research paper", 2.2),
        WeightedTerm("problem formulation", 2.2),
        WeightedTerm("best practices for", 2.0),
        WeightedTerm("key concepts of", 2.0),
        WeightedTerm("difference between", 1.8),
        WeightedTerm("comparison between", 1.8),
        WeightedTerm("trade-offs between", 2.0),
        WeightedTerm("tradeoffs between", 2.0),
        WeightedTerm("basics for beginners", 2.0),
        WeightedTerm("for beginners", 1.8),
        WeightedTerm("basics", 1.5),
        WeightedTerm("introduction to", 1.8),
        WeightedTerm("guide", 1.5),
        WeightedTerm("overview of", 1.5)
    )

    // Code syntax markers
    private val codeSyntaxRegex = listOf(
        Regex("```[a-zA-Z0-9_-]*\\n[\\s\\S]*?\\n```"),
        Regex("`[^`\\n]{2,60}`"),
        Regex("\\b(fun|def|val|var|const|class|struct|interface|enum class|pub fn)\\s+[a-zA-Z0-9_]+"),
        Regex("\\bimport\\s+[a-zA-Z0-9_.]+"),
        Regex("\\bpublic\\s+(static\\s+)?(void|int|String|boolean)\\b"),
        Regex("\\b(return|async|await|yield|throw|catch|finally)\\b"),
        Regex("\\b[a-zA-Z0-9_]+\\s*\\(\\s*[a-zA-Z0-9_]+\\s*:\\s*[a-zA-Z0-9_<>]+\\s*\\)")
    )

    // Math syntax markers
    private val mathSyntaxRegex = listOf(
        Regex("\\bO\\([0-9a-zA-Z\\s+*^/()log]+\\)", RegexOption.IGNORE_CASE),
        Regex("\\b(Theta|Omega)\\([0-9a-zA-Z\\s+*^/()log]+\\)", RegexOption.IGNORE_CASE),
        Regex("\\b[a-zA-Z]\\s*=\\s*[0-9a-zA-Z_()]+\\s*[+\\-*/^]\\s*[0-9a-zA-Z_()]+"),
        Regex("\\b(f\\(x\\)|g\\(x\\)|dy/dx|p\\s*<\\s*0\\.0[0-9]+)\\b")
    )

    private val topicDictionaries: Map<EducationalTopic, List<WeightedTerm>> = mapOf(
        EducationalTopic.AI_ML to listOf(
            WeightedTerm("machine learning", 2.5),
            WeightedTerm("deep learning", 2.5),
            WeightedTerm("neural network", 2.5),
            WeightedTerm("neural networks", 2.5),
            WeightedTerm("gradient descent", 2.5),
            WeightedTerm("large language model", 2.5),
            WeightedTerm("large language models", 2.5),
            WeightedTerm("supervised learning", 2.5),
            WeightedTerm("unsupervised learning", 2.5),
            WeightedTerm("reinforcement learning", 2.5),
            WeightedTerm("computer vision", 2.5),
            WeightedTerm("natural language processing", 2.5),
            WeightedTerm("random forest", 2.5),
            WeightedTerm("support vector machine", 2.5),
            WeightedTerm("loss function", 2.0),
            WeightedTerm("fine-tuning", 2.0),
            WeightedTerm("fine tuning", 2.0),
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
            WeightedTerm("convolutional neural", 2.5),
            WeightedTerm("recurrent neural", 2.5),
            WeightedTerm("gradient boosting", 2.2),
            WeightedTerm("cross validation", 2.0),
            WeightedTerm("diffusion model", 2.2),
            WeightedTerm("ai", 1.0),
            WeightedTerm("ml", 1.0),
            WeightedTerm("nlp", 1.2)
        ),
        EducationalTopic.PROGRAMMING to listOf(
            WeightedTerm("programming", 2.0),
            WeightedTerm("computer science", 2.2),
            WeightedTerm("software development", 2.2),
            WeightedTerm("programming language", 2.2),
            WeightedTerm("programming tutorial", 2.2),
            WeightedTerm("software engineering", 2.2),
            WeightedTerm("object oriented programming", 2.5),
            WeightedTerm("functional programming", 2.5),
            WeightedTerm("design patterns", 2.5),
            WeightedTerm("design pattern", 2.5),
            WeightedTerm("dependency injection", 2.5),
            WeightedTerm("garbage collection", 2.5),
            WeightedTerm("memory leak", 2.5),
            WeightedTerm("memory leaks", 2.5),
            WeightedTerm("exception handling", 2.0),
            WeightedTerm("unit test", 2.0),
            WeightedTerm("unit testing", 2.0),
            WeightedTerm("async await", 2.0),
            WeightedTerm("kotlin", 2.0),
            WeightedTerm("golang", 2.0),
            WeightedTerm("rust programming", 2.5),
            WeightedTerm("rust language", 2.5),
            WeightedTerm("rust", 1.8),
            WeightedTerm("typescript", 1.8),
            WeightedTerm("javascript", 1.8),
            WeightedTerm("python", 1.8),
            WeightedTerm("c++", 2.0),
            WeightedTerm("c#", 2.0),
            WeightedTerm("java", 1.8),
            WeightedTerm("compiler", 1.8),
            WeightedTerm("compilers", 1.8),
            WeightedTerm("interpreter", 1.8),
            WeightedTerm("coroutine", 2.0),
            WeightedTerm("coroutines", 2.0),
            WeightedTerm("polymorphism", 2.0),
            WeightedTerm("inheritance", 1.5),
            WeightedTerm("refactoring", 1.8),
            WeightedTerm("concurrency", 1.8),
            WeightedTerm("multithreading", 1.8),
            WeightedTerm("thread pool", 2.2),
            WeightedTerm("syntax", 1.2),
            WeightedTerm("bytecode", 1.8),
            WeightedTerm("debugging", 1.2),
            WeightedTerm("type safety", 2.0),
            WeightedTerm("generics", 1.5),
            WeightedTerm("memory safety", 2.0),
            WeightedTerm("relational database", 2.5),
            WeightedTerm("relational databases", 2.5),
            WeightedTerm("database", 2.0),
            WeightedTerm("databases", 2.0),
            WeightedTerm("sql query", 2.2),
            WeightedTerm("query indexing", 2.2),
            WeightedTerm("indexing", 1.8),
            WeightedTerm("android development", 2.4),
            WeightedTerm("android studio", 2.2),
            WeightedTerm("activity lifecycle", 2.4),
            WeightedTerm("mobile development", 2.2),
            WeightedTerm("web development", 2.2),
            WeightedTerm("frontend", 1.8),
            WeightedTerm("backend", 1.8),
            WeightedTerm("html", 1.6),
            WeightedTerm("css", 1.6),
            WeightedTerm("react", 1.6),
            WeightedTerm("node.js", 1.8),
            WeightedTerm("nodejs", 1.8),
            WeightedTerm("git", 1.5),
            WeightedTerm("github", 1.5),
            WeightedTerm("api", 1.5),
            WeightedTerm("apis", 1.5),
            WeightedTerm("operating systems", 2.2),
            WeightedTerm("operating system", 2.0),
            WeightedTerm("os buffer", 2.2),
            WeightedTerm("buffer management", 2.2),
            WeightedTerm("buffer cache", 2.2),
            WeightedTerm("memory buffer", 1.8),
            WeightedTerm("computer networks", 2.2),
            WeightedTerm("networking", 1.5),
            WeightedTerm("dbms", 2.0),
            WeightedTerm("normalization", 1.8),
            WeightedTerm("deadlock", 1.8),
            WeightedTerm("deadlocks", 1.8),
            WeightedTerm("process scheduling", 2.2)
        ),
        EducationalTopic.DSA to listOf(
            WeightedTerm("algorithm", 2.0),
            WeightedTerm("algorithms", 2.0),
            WeightedTerm("sorting algorithm", 2.5),
            WeightedTerm("sorting algorithms", 2.5),
            WeightedTerm("breadth first search", 2.5),
            WeightedTerm("depth first search", 2.5),
            WeightedTerm("graph traversal", 2.5),
            WeightedTerm("graph algorithms", 2.5),
            WeightedTerm("graph algorithm", 2.5),
            WeightedTerm("data structure", 2.5),
            WeightedTerm("data structures", 2.5),
            WeightedTerm("dsa", 2.0),
            WeightedTerm("leetcode", 2.0),
            WeightedTerm("coding problem", 1.8),
            WeightedTerm("coding problems", 1.8),
            WeightedTerm("array", 1.5),
            WeightedTerm("arrays", 1.5),
            WeightedTerm("loop", 1.2),
            WeightedTerm("loops", 1.2),
            WeightedTerm("binary search", 2.5),
            WeightedTerm("binary search tree", 2.5),
            WeightedTerm("binary tree", 2.2),
            WeightedTerm("binary trees", 2.2),
            WeightedTerm("dynamic programming", 2.5),
            WeightedTerm("time complexity", 2.5),
            WeightedTerm("space complexity", 2.5),
            WeightedTerm("linked list", 2.5),
            WeightedTerm("linked lists", 2.5),
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
            WeightedTerm("heapsort", 2.2),
            WeightedTerm("bfs", 1.8),
            WeightedTerm("dfs", 1.8),
            WeightedTerm("trie", 2.0),
            WeightedTerm("memoization", 2.0),
            WeightedTerm("recursion", 1.5),
            WeightedTerm("recursive", 1.5),
            WeightedTerm("big o", 2.0),
            WeightedTerm("big-o", 2.0),
            WeightedTerm("asymptotic", 2.0),
            WeightedTerm("stack overflow", 2.2),
            WeightedTerm("call stack", 2.2),
            WeightedTerm("tree traversal", 2.5),
            WeightedTerm("stack", 1.2),
            WeightedTerm("queue", 1.2),
            WeightedTerm("heap", 1.5)
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
            WeightedTerm("authorization", 1.2),
            WeightedTerm("ed25519", 2.0),
            WeightedTerm("chacha20", 2.0),
            WeightedTerm("x25519", 2.0)
        ),
        EducationalTopic.MATHEMATICS to listOf(
            WeightedTerm("linear algebra", 2.5),
            WeightedTerm("discrete math", 2.5),
            WeightedTerm("discrete mathematics", 2.5),
            WeightedTerm("differential equation", 2.5),
            WeightedTerm("differential equations", 2.5),
            WeightedTerm("matrix multiplication", 2.5),
            WeightedTerm("matrix inversion", 2.5),
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
            WeightedTerm("prime number", 2.0),
            WeightedTerm("prime numbers", 2.0),
            WeightedTerm("theorem", 1.5),
            WeightedTerm("lemma", 1.8),
            WeightedTerm("matrices", 1.5),
            WeightedTerm("matrix", 1.2),
            WeightedTerm("mathematics", 1.5),
            WeightedTerm("math", 1.5),
            WeightedTerm("maths", 1.5),
            WeightedTerm("probability", 1.2),
            WeightedTerm("statistics", 1.2),
            WeightedTerm("stochastic", 2.0)
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
            WeightedTerm("containers", 1.2),
            WeightedTerm("cloud infrastructure", 2.2)
        ),
        EducationalTopic.SCIENCE_ENGINEERING to listOf(
            WeightedTerm("quantum mechanics", 2.5),
            WeightedTerm("thermodynamics", 2.5),
            WeightedTerm("organic chemistry", 2.5),
            WeightedTerm("biochemistry", 2.5),
            WeightedTerm("molecular biology", 2.5),
            WeightedTerm("genetics", 2.2),
            WeightedTerm("electromagnetism", 2.5),
            WeightedTerm("fluid dynamics", 2.5),
            WeightedTerm("materials science", 2.5),
            WeightedTerm("signal processing", 2.5),
            WeightedTerm("circuit analysis", 2.5),
            WeightedTerm("semiconductor", 2.2),
            WeightedTerm("kinematics", 2.2),
            WeightedTerm("newtonian mechanics", 2.5),
            WeightedTerm("astrophysics", 2.5),
            WeightedTerm("general relativity", 2.5),
            WeightedTerm("particle physics", 2.5),
            WeightedTerm("aerospace engineering", 2.5),
            WeightedTerm("electrical engineering", 2.5),
            WeightedTerm("mechanical engineering", 2.5),
            WeightedTerm("spectroscopy", 2.2),
            WeightedTerm("thermodynamic", 2.0),
            WeightedTerm("entropy", 1.8),
            WeightedTerm("physics", 1.8),
            WeightedTerm("chemistry", 1.8),
            WeightedTerm("biology", 1.8)
        ),
        EducationalTopic.ACADEMIC_RESEARCH to listOf(
            WeightedTerm("literature review", 2.5),
            WeightedTerm("peer reviewed", 2.5),
            WeightedTerm("peer-reviewed", 2.5),
            WeightedTerm("research methodology", 2.5),
            WeightedTerm("empirical study", 2.5),
            WeightedTerm("statistical significance", 2.5),
            WeightedTerm("hypothesis testing", 2.5),
            WeightedTerm("ablation study", 2.5),
            WeightedTerm("quantitative analysis", 2.5),
            WeightedTerm("qualitative analysis", 2.5),
            WeightedTerm("dissertation", 2.2),
            WeightedTerm("academic paper", 2.2),
            WeightedTerm("meta-analysis", 2.5),
            WeightedTerm("systematic review", 2.5),
            WeightedTerm("experimental design", 2.5),
            WeightedTerm("conference proceedings", 2.2),
            WeightedTerm("journal article", 2.2),
            WeightedTerm("scientific method", 2.2),
            WeightedTerm("p-value", 2.0),
            WeightedTerm("bibliography", 1.8),
            WeightedTerm("citations", 1.8),
            WeightedTerm("citation", 1.8),
            WeightedTerm("methodology", 1.8),
            WeightedTerm("arxiv", 2.0),
            WeightedTerm("economics", 1.8),
            WeightedTerm("supply and demand", 2.2),
            WeightedTerm("history", 1.5),
            WeightedTerm("geography", 1.5),
            WeightedTerm("english literature", 2.0),
            WeightedTerm("essay", 1.5),
            WeightedTerm("research question", 2.2)
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
        WeightedTerm("beer", 1.5),
        WeightedTerm("wine", 1.5),
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
        WeightedTerm("cute puppy", 2.2),
        WeightedTerm("cute kitten", 2.2),
        WeightedTerm("cat video", 2.2),
        WeightedTerm("dog meme", 2.2),
        WeightedTerm("meme", 2.0),
        WeightedTerm("memes", 2.0),
        WeightedTerm("hilarious", 1.8),
        WeightedTerm("lmao", 1.8),
        WeightedTerm("rofl", 1.8),
        WeightedTerm("lol", 1.5),
        WeightedTerm("funny joke", 2.0),
        WeightedTerm("joke", 1.5),
        WeightedTerm("hang out", 1.8),
        WeightedTerm("hanging out", 1.8),
        WeightedTerm("video games", 2.0),
        WeightedTerm("playstation", 2.0),
        WeightedTerm("xbox", 2.0),
        WeightedTerm("fortnite", 2.2),
        WeightedTerm("valorant", 2.2),
        WeightedTerm("fifa", 2.0),
        WeightedTerm("cricket match", 2.2),
        WeightedTerm("football match", 2.2),
        WeightedTerm("soccer", 2.0),
        WeightedTerm("movie night", 2.2),
        WeightedTerm("popcorn", 1.8),
        WeightedTerm("haircut", 2.0),
        WeightedTerm("outfit of the day", 2.2),
        WeightedTerm("ootd", 2.0),
        WeightedTerm("good morning", 1.8),
        WeightedTerm("good night", 1.8),
        WeightedTerm("happy birthday", 2.0),
        WeightedTerm("congratulations bro", 2.0),
        WeightedTerm("sup bro", 1.8),
        WeightedTerm("what's up", 1.8),
        WeightedTerm("how are you guys doing", 2.0),
        WeightedTerm("weather is so nice", 2.2),
        WeightedTerm("stuck in traffic", 2.2),
        WeightedTerm("airport selfie", 2.2),
        WeightedTerm("selfie", 1.8),
        WeightedTerm("gym workout", 2.2),
        WeightedTerm("leg day", 2.0),
        WeightedTerm("bicep curls", 2.0),
        WeightedTerm("pancakes", 1.8),
        WeightedTerm("burger", 1.5),
        WeightedTerm("pizza", 1.5),
        WeightedTerm("tacos", 1.8),
        WeightedTerm("coffee break", 1.8),
        WeightedTerm("vintage cars", 2.0),
        WeightedTerm("cars", 1.2),
        WeightedTerm("movie", 1.5),
        WeightedTerm("cinema", 1.8)
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

        // 1. Inappropriate / Adult Content Gate (Priority Gate)
        val flaggedAdult = adultIndicators.any { it.containsMatchIn(cleanText) }
        val flaggedInappropriate = inappropriateIndicators.any { it.containsMatchIn(cleanText) }
        if (flaggedAdult || flaggedInappropriate) {
            val adultFlags = if (flaggedAdult) setOf(AdultFlag.EXPLICIT_SEXUAL) else emptySet()
            val inappropriateFlags = mutableSetOf<InappropriateFlag>()
            if (flaggedAdult) inappropriateFlags += InappropriateFlag.EXPLICIT_SEXUAL
            if (flaggedInappropriate) inappropriateFlags += InappropriateFlag.HARASSMENT_ABUSE

            return ClassificationResult(
                category = PrimaryCategory.INAPPROPRIATE,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.HIGH,
                topicScores = emptyMap(),
                adultFlags = adultFlags,
                inappropriateFlags = inappropriateFlags,
                summary = "Detected inappropriate, explicit, or prohibited content indicator."
            )
        }

        // 2. Commercial Spam / Marketplace Scheme Gate
        val isCommercialSpam = commercialSpamPatterns.any { it.containsMatchIn(cleanText) }
        if (isCommercialSpam) {
            return ClassificationResult(
                category = PrimaryCategory.NON_EDUCATIONAL,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.HIGH,
                topicScores = emptyMap(),
                inappropriateFlags = setOf(InappropriateFlag.PROHIBITED_COMMERCIAL),
                summary = "Detected commercial advertisement, marketplace sale, or spam scheme."
            )
        }

        // 3. Anti-Educational Dismissal / Negation Gate
        val isAntiEducational = antiEducationalPatterns.any { it.containsMatchIn(cleanText) }
        if (isAntiEducational) {
            return ClassificationResult(
                category = PrimaryCategory.NON_EDUCATIONAL,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.HIGH,
                topicScores = emptyMap(),
                summary = "Keywords appear in an anti-educational, dismissive, or casual context."
            )
        }

        // 4. Intent & Structure Indicators
        var intentBonus = 0.0
        for ((term, weight) in educationalIntentTerms) {
            val pattern = Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(cleanText)) {
                intentBonus += weight
            }
        }
        val hasCodeSyntax = codeSyntaxRegex.any { it.containsMatchIn(cleanText) }
        if (hasCodeSyntax) {
            intentBonus += 2.0
        }
        val hasMathSyntax = mathSyntaxRegex.any { it.containsMatchIn(cleanText) }
        if (hasMathSyntax) {
            intentBonus += 2.0
        }

        // Pre-scan: Identify non-polysemous educational terms present in the text
        val matchedUnambiguousTerms = mutableSetOf<String>()
        for ((_, terms) in topicDictionaries) {
            for ((term, _) in terms) {
                if (term !in polysemousTerms && Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanText)) {
                    matchedUnambiguousTerms += term
                }
            }
        }
        val hasStrongContext = matchedUnambiguousTerms.isNotEmpty() || hasCodeSyntax || hasMathSyntax || intentBonus >= 1.5

        // 5. Score Educational Topics
        val scores = mutableMapOf<EducationalTopic, Double>()
        for ((topic, terms) in topicDictionaries) {
            var topicScore = 0.0
            for ((term, weight) in terms) {
                // If the term is polysemous, only score it if strong contextual evidence is present
                if (term in polysemousTerms && !hasStrongContext) {
                    continue
                }

                val pattern = Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
                val matchCount = pattern.findAll(cleanText).count()
                if (matchCount > 0) {
                    topicScore += weight + (matchCount - 1) * (weight * 0.4)
                }
            }
            if (topicScore > 0.0) {
                // Distribute intent bonus to matched topics
                val boostedScore = topicScore + (if (intentBonus > 0.0) 1.0 else 0.0)
                scores[topic] = boostedScore
            }
        }

        // 6. Score Non-Educational / Casual Indicators
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

        // 7. Contextual Ambiguity / Evidence Gate
        // Case A: No signals at all
        if (maxEduScore == 0.0 && nonEduScore == 0.0 && intentBonus == 0.0) {
            return ClassificationResult(
                category = PrimaryCategory.UNCERTAIN,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.UNCERTAIN,
                topicScores = emptyMap(),
                summary = "Insufficient topical evidence; ambiguous content."
            )
        }

        // Case B: Pure non-educational content
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

        // Case C: Casual content dominates over incidental technical words
        if (nonEduScore >= 2.0 && nonEduScore > maxEduScore * 1.3 && intentBonus < 2.0) {
            return ClassificationResult(
                category = PrimaryCategory.NON_EDUCATIONAL,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.HIGH,
                topicScores = scores,
                summary = "Casual or entertainment content dominates with incidental technical keywords."
            )
        }

        // Case D: Weak educational match in long or conversational text without intent signals
        if (maxEduScore < 1.5 && intentBonus == 0.0 && (wordCount > 12 || nonEduScore > 0.0)) {
            return ClassificationResult(
                category = PrimaryCategory.UNCERTAIN,
                educationalTopics = emptySet(),
                primaryTopic = scores.maxByOrNull { it.value }?.key,
                confidence = ConfidenceLevel.UNCERTAIN,
                topicScores = scores,
                summary = "Low educational keyword density in conversational context; requires verification."
            )
        }

        // Case E: Educational score below threshold without structural intent
        if (maxEduScore < 1.2 && intentBonus == 0.0) {
            return ClassificationResult(
                category = PrimaryCategory.UNCERTAIN,
                educationalTopics = emptySet(),
                primaryTopic = null,
                confidence = ConfidenceLevel.UNCERTAIN,
                topicScores = scores,
                summary = "Educational evidence is below confident threshold."
            )
        }

        // 8. Successful Educational Classification
        val primary = scores.maxByOrNull { it.value }?.key
        val matchedTopics = scores.filter { entry ->
            entry.value >= 1.5 || (entry.value >= 1.0 && entry.value >= maxEduScore * 0.6)
        }.keys

        val confidence = when {
            maxEduScore >= 3.0 || (maxEduScore >= 2.0 && intentBonus >= 1.5) -> ConfidenceLevel.HIGH
            // A clear topic term is enough for an ordinary study post. Earlier
            // thresholds incorrectly rejected concise learner updates.
            maxEduScore >= 1.5 -> ConfidenceLevel.MEDIUM
            maxEduScore >= 1.2 -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.UNCERTAIN
        }

        val primaryCategory = if (confidence == ConfidenceLevel.UNCERTAIN || confidence == ConfidenceLevel.LOW) {
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
            educationalTopics = if (primaryCategory == PrimaryCategory.EDUCATIONAL) matchedTopics else emptySet(),
            primaryTopic = if (primaryCategory == PrimaryCategory.EDUCATIONAL) primary else null,
            confidence = confidence,
            topicScores = scores,
            summary = summaryText
        )
    }
}
