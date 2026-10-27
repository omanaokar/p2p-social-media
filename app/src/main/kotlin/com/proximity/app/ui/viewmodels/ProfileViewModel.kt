package com.proximity.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proximity.app.AppContainer
import com.proximity.contracts.LocalUser
import com.proximity.contracts.Profile
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
 * UI State for the user's local cryptographic profile, identity, and moderation blocklist.
 */
data class ProfileUiState(
    val isLoading: Boolean = true,
    val currentUser: LocalUser? = null,
    val profile: Profile? = null,
    val blockedUserIds: List<String> = emptyList(),
    val isUpdating: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * ViewModel managing the current user's profile information, public key display,
 * avatar updates, and local peer blocklist.
 * Connects Om's [com.proximity.contracts.ProfileRepository] and [com.proximity.contracts.IdentityService].
 */
class ProfileViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _isUpdating = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ProfileUiState> = container.core
        .flatMapLatest { core ->
            if (core == null) {
                flowOf(ProfileUiState(isLoading = true))
            } else {
                combine(
                    core.profiles.watchMyProfile(),
                    core.profiles.watchBlocked(),
                    _isUpdating,
                    _errorMessage,
                ) { myProfile, blockedList, isUpdating, error ->
                    ProfileUiState(
                        isLoading = false,
                        currentUser = core.identity.currentUser,
                        profile = myProfile,
                        blockedUserIds = blockedList,
                        isUpdating = isUpdating,
                        errorMessage = error,
                    )
                }
            }
        }
        .catch { e ->
            emit(ProfileUiState(isLoading = false, errorMessage = e.message ?: "Failed to load profile"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ProfileUiState(isLoading = true)
        )

    fun updateDisplayName(newDisplayName: String) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            _isUpdating.value = true
            _errorMessage.value = null
            try {
                core.profiles.updateMyProfile(displayName = newDisplayName.trim())
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update profile"
            } finally {
                _isUpdating.value = false
            }
        }
    }

    fun updateAvatar(avatarBytes: ByteArray?) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            _isUpdating.value = true
            _errorMessage.value = null
            try {
                core.profiles.updateMyProfile(avatar = avatarBytes)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update avatar"
            } finally {
                _isUpdating.value = false
            }
        }
    }

    fun blockUser(userId: String) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            try {
                core.profiles.block(userId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to block user"
            }
        }
    }

    fun unblockUser(userId: String) {
        val core = container.core.value ?: return
        viewModelScope.launch {
            try {
                core.profiles.unblock(userId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to unblock user"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
