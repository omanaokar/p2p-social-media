package com.proximity.ranker

import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.InteractionType
import com.proximity.contracts.RankContext
import com.proximity.contracts.RankedPost
import com.proximity.ranker.classifier.EducationalTopic
import com.proximity.ranker.classifier.HeuristicTextClassifier
import com.proximity.ranker.classifier.TextContentClassifier
import com.proximity.ranker.learning.AdaptiveLearningContext
import com.proximity.ranker.learning.AdaptiveLearningRecommender
import com.proximity.ranker.learning.AdaptivePostRecommendation
import com.proximity.ranker.learning.LocalAdaptiveLearningRecommender
import com.proximity.ranker.learning.TopicRecommendation
import com.proximity.ranker.moderation.ContentModerator
import com.proximity.ranker.moderation.DefaultContentModerator
import com.proximity.ranker.profile.InMemoryUserInterestProfileStore
import com.proximity.ranker.profile.UserInterestProfileStore
import com.proximity.ranker.recommendation.LocalPeerRecommender
import com.proximity.ranker.recommendation.PeerRecommendation
import com.proximity.ranker.recommendation.PeerRecommendationContext
import com.proximity.ranker.recommendation.PeerRecommender
import com.proximity.ranker.search.EmbeddingProvider
import com.proximity.ranker.search.KeywordPostSearchEngine
import com.proximity.ranker.search.PostSearchEngine
import com.proximity.ranker.search.SearchResult
import com.proximity.ranker.search.SemanticPostSearchEngine
import com.proximity.ranker.studygroup.LocalStudyGroupMatcher
import com.proximity.ranker.studygroup.StudyGroupCohort
import com.proximity.ranker.studygroup.StudyGroupMatch
import com.proximity.ranker.studygroup.StudyGroupMatcher
import com.proximity.ranker.studygroup.StudyGroupMatchingContext
import java.time.Duration
import java.time.Instant

/**
 * Cohesive coordinator for all local ML and recommendation components in Proximity.
 *
 * Provides a unified assembly with shared profile store, content classifier,
 * moderation engine, and adaptive interaction loop.
 *
 * Architectural Features:
 * - Shared Profile: Updates to [profileStore] immediately affect feed ranking,
 *   friend recommendations, study group matching, and adaptive learning topics.
 * - Interaction Loop: Feed interactions via [recordFeedInteraction] automatically update
 *   author affinities in [ranker] AND topic engagements in [adaptiveRecommender].
 * - Safety & Educational Enforcement: Moderation and classification strictly filter out
 *   non-educational, inappropriate, or uncertain content from feed ranking, search,
 *   and peer/study-group recommendations.
 */
class RecommendationSystem(
    val profileStore: UserInterestProfileStore = InMemoryUserInterestProfileStore(),
    val classifier: TextContentClassifier = HeuristicTextClassifier(),
    val moderator: ContentModerator = DefaultContentModerator(classifier = classifier),
    val clock: () -> Instant = { Instant.now() },
    val adaptiveRecommender: AdaptiveLearningRecommender = LocalAdaptiveLearningRecommender(
        profileStore = profileStore,
        classifier = classifier
    ),
    val ranker: LocalRanker = LocalRanker(
        classifier = classifier,
        profileStore = profileStore,
        moderator = moderator,
        adaptiveRecommender = adaptiveRecommender
    ),
    val embeddingProvider: EmbeddingProvider? = null,
    val searchEngine: PostSearchEngine = if (embeddingProvider != null) {
        SemanticPostSearchEngine(
            embeddingProvider = embeddingProvider,
            fallbackEngine = KeywordPostSearchEngine(classifier = classifier, moderator = moderator, clock = clock),
            classifier = classifier,
            moderator = moderator,
            clock = clock
        )
    } else {
        KeywordPostSearchEngine(
            classifier = classifier,
            moderator = moderator,
            clock = clock
        )
    },
    val peerRecommender: PeerRecommender = LocalPeerRecommender(
        profileStore = profileStore,
        classifier = classifier
    ),
    val studyGroupMatcher: StudyGroupMatcher = LocalStudyGroupMatcher(
        profileStore = profileStore,
        classifier = classifier
    )
) {
    /** Ranks feed candidates using proximity, engagement, freshness, and user interests. */
    suspend fun rankFeed(candidates: List<FeedCandidate>, context: RankContext): List<RankedPost> =
        ranker.rank(candidates, context)

    /** Records user interaction on a feed post, updating both author affinity and topic learning models. */
    suspend fun recordFeedInteraction(postId: String, type: InteractionType, dwell: Duration? = null) =
        ranker.recordInteraction(postId, type, dwell)

    /** Searches available candidates using BM25+ term matching and educational topic bonus. */
    fun search(query: String?, candidates: List<FeedCandidate>, limit: Int = 20): List<SearchResult> =
        searchEngine.search(query, candidates, limit)

    /** Recommends educational topics to focus on next based on profile and interaction history. */
    fun recommendTopics(context: AdaptiveLearningContext): List<TopicRecommendation> =
        adaptiveRecommender.recommendTopics(context)

    /** Recommends educational posts to explore next, suppressing previously seen content. */
    fun recommendAdaptivePosts(context: AdaptiveLearningContext, limit: Int = 10): List<AdaptivePostRecommendation> =
        adaptiveRecommender.recommendPosts(context, limit)

    /** Recommends peers based on shared educational interests and mutual connections. */
    fun recommendPeers(context: PeerRecommendationContext): List<PeerRecommendation> =
        peerRecommender.recommend(context)

    /** Matches individual study partners based on topic overlap and study goals. */
    fun matchStudyPartners(context: StudyGroupMatchingContext): List<StudyGroupMatch> =
        studyGroupMatcher.match(context)

    /** Forms a recommended study group cohort for a specific educational subject. */
    fun formStudyGroup(
        topic: EducationalTopic,
        context: StudyGroupMatchingContext,
        maxMembers: Int = 4
    ): StudyGroupCohort? =
        studyGroupMatcher.formGroup(topic, context, maxMembers)
}
