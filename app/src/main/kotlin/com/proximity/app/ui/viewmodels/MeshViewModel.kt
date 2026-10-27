package com.proximity.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proximity.app.AppContainer
import com.proximity.app.TransportMode
import com.proximity.contracts.PeerProfile
import com.proximity.contracts.TransportPeer
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
 * UI State for the P2P mesh network, connected peers, and hardware background service.
 */
data class MeshUiState(
    val isCoreReady: Boolean = false,
    val transportMode: TransportMode = TransportMode.REAL_NEARBY,
    val isForegroundServiceActive: Boolean = false,
    val nearbyProfiles: List<PeerProfile> = emptyList(),
    val transportPeers: List<TransportPeer> = emptyList(),
    val isSwitchingMode: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * ViewModel managing the P2P connectivity status, Nearby Connections peer table,
 * transport mode switching, and foreground service lifecycle.
 * Connects Harshil's [com.proximity.contracts.Transport] and Om's [com.proximity.contracts.ProfileRepository].
 */
class MeshViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _isSwitchingMode = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<MeshUiState> = container.core
        .flatMapLatest { core ->
            if (core == null) {
                combine(
                    container.transportMode,
                    container.isServiceRunning,
                    _isSwitchingMode,
                    _errorMessage,
                ) { mode, serviceRunning, isSwitching, error ->
                    MeshUiState(
                        isCoreReady = false,
                        transportMode = mode,
                        isForegroundServiceActive = serviceRunning,
                        isSwitchingMode = isSwitching,
                        errorMessage = error,
                    )
                }
            } else {
                val peersFlow = try {
                    container.transport.peers
                } catch (_: Exception) {
                    flowOf(emptyList())
                }

                val statusFlow = combine(_isSwitchingMode, _errorMessage) { switching, err ->
                    switching to err
                }

                combine(
                    container.transportMode,
                    container.isServiceRunning,
                    core.profiles.watchNearbyPeers(),
                    peersFlow,
                    statusFlow,
                ) { mode, serviceRunning, profiles, peers, (isSwitching, error) ->
                    MeshUiState(
                        isCoreReady = true,
                        transportMode = mode,
                        isForegroundServiceActive = serviceRunning,
                        nearbyProfiles = profiles,
                        transportPeers = peers,
                        isSwitchingMode = isSwitching,
                        errorMessage = error,
                    )
                }
            }
        }
        .catch { e ->
            emit(MeshUiState(errorMessage = e.message ?: "Mesh connectivity error"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MeshUiState()
        )

    fun switchTransportMode(newMode: TransportMode) {
        viewModelScope.launch {
            _isSwitchingMode.value = true
            _errorMessage.value = null
            try {
                container.switchTransportMode(newMode)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to switch transport mode"
            } finally {
                _isSwitchingMode.value = false
            }
        }
    }

    fun startForegroundService() {
        try {
            container.startForegroundService()
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "Failed to start background mesh"
        }
    }

    fun stopForegroundService() {
        try {
            container.stopForegroundService()
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "Failed to stop background mesh"
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
