package com.proximity.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proximity.app.AppContainer
import com.proximity.contracts.CommentView
import com.proximity.contracts.InteractionType
import com.proximity.contracts.ReactionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI State for a post's comments and associated reactions.
 */
data class CommentsUiState(
    val postId: String = "",
    val isLoading: Boolean = true,
    val comments: List<CommentView> = emptyList(),
    val reactionSummaries: Map<String, ReactionSummary> = emptyMap(),
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * ViewModel managing the comments stream, reactions, and submission for a specific post.
 * Connects Om's [com.proximity.contracts.CommentRepository] and [com.proximity.contracts.ReactionRepository].
 */
class CommentsViewModel(
    private val container: AppContainer,
    private val postId: String,
) : ViewModel() {

    private val _isSubmitting = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<CommentsUiState> = container.core
        .flatMapLatest { core ->
            if (core == null || postId.isBlank()) {
                flowOf(CommentsUiState(postId = postId, isLoading = true))
            } else {
                combine(
                    core.comments.watchForPost(postId),
                    core.reactions.watchForPost(postId),
                    _isSubmitting,
                    _errorMessage,
                ) { commentsList, reactionsMap, isSubmitting, error ->
                    CommentsUiState(
                        postId = postId,
                        isLoading = false,
                        comments = commentsList,
                        reactionSummaries = reactionsMap,
                        isSubmitting = isSubmitting,
                        errorMessage = error,
                    )
                }
            }
        }
        .catch { e ->
            emit(
                CommentsUiState(
                    postId = postId,
                    isLoading = false,
                    errorMessage = e.message ?: "Failed to load comments"
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CommentsUiState(postId = postId, isLoading = true)
        )

    fun addComment(body: String) {
        val core = container.core.value ?: return
        if (body.isBlank()) return
        viewModelScope.launch {
            _isSubmitting.value = true
            _errorMessage.value = null
            try {
                core.comments.add(postId, body.trim())
                container.ranker.recordFeedInteraction(postId, InteractionType.COMMENT)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to add comment"
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun reactToComment(commentId: String, type: String) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            try {
                core.reactions.add(targetId = commentId, type = type)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to react to comment"
            }
        }
    }

    fun removeCommentReaction(commentId: String) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            try {
                core.reactions.remove(targetId = commentId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to remove comment reaction"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
