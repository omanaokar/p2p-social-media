package com.proximity.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.proximity.app.AppContainer

/**
 * Dependency-injection factory supplying [AppContainer] to application ViewModels.
 */
class ViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(FeedViewModel::class.java) -> {
                FeedViewModel(container) as T
            }
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> {
                ChatViewModel(container) as T
            }
            modelClass.isAssignableFrom(MeshViewModel::class.java) -> {
                MeshViewModel(container) as T
            }
            modelClass.isAssignableFrom(ProfileViewModel::class.java) -> {
                ProfileViewModel(container) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

/**
 * Dedicated factory supplying [AppContainer] and a target [postId] to [CommentsViewModel].
 */
class CommentsViewModelFactory(
    private val container: AppContainer,
    private val postId: String,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CommentsViewModel::class.java)) {
            return CommentsViewModel(container, postId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
