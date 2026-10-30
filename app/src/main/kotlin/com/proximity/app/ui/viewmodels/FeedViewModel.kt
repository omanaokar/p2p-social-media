package com.proximity.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proximity.app.AppContainer
import com.proximity.contracts.FeedCandidate
import com.proximity.contracts.InteractionType
import com.proximity.contracts.RankContext
import com.proximity.contracts.RankedPost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * UI State for the Proximity ranked feed.
 */
data class FeedUiState(
    val isLoading: Boolean = true,
    val posts: List<RankedPost> = emptyList(),
    val searchQuery: String? = null,
    val isCreatingPost: Boolean = false,
    val errorMessage: String? = null,
    val selectedPostImage: ByteArray? = null,
    val imageError: String? = null,
)

/**
 * ViewModel managing the ranked post feed, search filtering, reactions, and interaction tracking.
 * Connects Om's [com.proximity.contracts.PostRepository], [com.proximity.contracts.ReactionRepository]
 * and Neil's [com.proximity.ranker.RecommendationSystem].
 */
class FeedViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    private val _isCreatingPost = MutableStateFlow(false)
    val isCreatingPost: StateFlow<Boolean> = _isCreatingPost.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val _selectedPostImage = MutableStateFlow<ByteArray?>(null)
    private val _imageError = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<FeedUiState> = container.core
        .flatMapLatest { core ->
            if (core == null) {
                flowOf(FeedUiState(isLoading = true))
            } else {
                val feedState = combine(
                    core.posts.watchFeedCandidates(),
                    _searchQuery,
                    _isCreatingPost,
                    _errorMessage,
                ) { candidates, query, isCreating, error ->
                    val ranked = if (!query.isNullOrBlank()) {
                        val searchResults = container.ranker.search(query, candidates)
                        searchResults.map { res ->
                            RankedPost(
                                candidate = res.candidate,
                                score = res.relevanceScore,
                                reason = res.explanation
                            )
                        }
                    } else {
                        val context = RankContext(
                            now = Instant.now(),
                            currentUserId = core.identity.currentUser.id,
                        )
                        container.ranker.rankFeed(candidates, context)
                    }

                    FeedUiState(
                        isLoading = false,
                        posts = ranked,
                        searchQuery = query,
                        isCreatingPost = isCreating,
                        errorMessage = error,
                    )
                }
                combine(feedState, _selectedPostImage, _imageError) { state, selectedImage, imageError ->
                    state.copy(selectedPostImage = selectedImage, imageError = imageError)
                }
            }
        }
        .catch { e ->
            emit(FeedUiState(isLoading = false, errorMessage = e.message ?: "Failed to load feed"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FeedUiState(isLoading = true)
        )

    fun createPost(body: String, image: ByteArray? = null) {
        val core = container.core.value ?: return
        if (body.isBlank() && image == null) return
        viewModelScope.launch {
            _isCreatingPost.value = true
            _errorMessage.value = null
            try {
                core.posts.create(body.trim(), image)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to create post"
            } finally {
                _isCreatingPost.value = false
                _selectedPostImage.value = null
            }
        }
    }

    fun setSelectedPostImage(image: ByteArray?) {
        _selectedPostImage.value = image
        _imageError.value = null
    }

    fun setImageError(message: String?) {
        _imageError.value = message
    }

    fun deletePost(postId: String) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            try {
                core.posts.delete(postId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to delete post"
            }
        }
    }

    fun reportPost(postId: String, blockAuthor: Boolean = true) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            try {
                core.posts.report(postId, blockAuthor)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to report post"
            }
        }
    }

    fun react(postId: String, type: String) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            try {
                core.reactions.add(targetId = postId, type = type)
                container.ranker.recordFeedInteraction(postId, InteractionType.REACT)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to react"
            }
        }
    }

    fun removeReaction(postId: String) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            try {
                core.reactions.remove(targetId = postId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to remove reaction"
            }
        }
    }

    fun recordInteraction(postId: String, type: InteractionType, dwell: Duration? = null) {
        viewModelScope.launch {
            container.ranker.recordFeedInteraction(postId, type, dwell)
        }
    }

    fun setSearchQuery(query: String?) {
        _searchQuery.value = query
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
