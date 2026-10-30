package com.proximity.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proximity.app.AppContainer
import com.proximity.contracts.ChatMessage
import com.proximity.contracts.Conversation
import com.proximity.contracts.PeerProfile
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

/**
 * UI State for direct peer-to-peer messaging and conversation threads.
 */
data class ChatUiState(
    val isLoading: Boolean = true,
    val inbox: List<Conversation> = emptyList(),
    val nearbyPeers: List<PeerProfile> = emptyList(),
    val activePeerId: String? = null,
    val activeMessages: List<ChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * ViewModel managing the encrypted P2P messaging inbox and conversation view.
 * Connects Om's [com.proximity.contracts.MessageRepository].
 */
class ChatViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _activePeerId = MutableStateFlow<String?>(null)
    val activePeerId: StateFlow<String?> = _activePeerId.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ChatUiState> = container.core
        .flatMapLatest { core ->
            if (core == null) {
                flowOf(ChatUiState(isLoading = true))
            } else {
                val inboxAndPeers = combine(
                    core.messages.watchInbox(),
                    core.profiles.watchNearbyPeers(),
                ) { inboxList, nearbyPeers -> inboxList to nearbyPeers }

                combine(
                    inboxAndPeers,
                    _activePeerId.flatMapLatest { peerId ->
                        if (peerId == null) flowOf(emptyList())
                        else core.messages.watchConversation(peerId)
                    },
                    _activePeerId,
                    _isSending,
                    _errorMessage,
                ) { (inboxList, nearbyPeers), messagesList, peerId, isSending, error ->
                    ChatUiState(
                        isLoading = false,
                        inbox = inboxList,
                        nearbyPeers = nearbyPeers,
                        activePeerId = peerId,
                        activeMessages = messagesList,
                        isSending = isSending,
                        errorMessage = error,
                    )
                }
            }
        }
        .catch { e ->
            emit(ChatUiState(isLoading = false, errorMessage = e.message ?: "Failed to load messages"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ChatUiState(isLoading = true)
        )

    fun openConversation(peerUserId: String) {
        _activePeerId.value = peerUserId
        _errorMessage.value = null
    }

    fun closeConversation() {
        _activePeerId.value = null
    }

    fun sendMessage(toUserId: String, text: String) {
        val core = container.core.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            try {
                core.messages.send(toUserId, text.trim())
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to send message"
            } finally {
                _isSending.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
