package com.proximity.transport.battery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

enum class DutyCycleState {
    STOPPED,
    SCANNING,
    IDLE,
}

enum class PowerProfile {
    NORMAL,
    BATTERY_SAVER,
    AGGRESSIVE_DISCOVERY,
}

/**
 * Duty cycles Bluetooth/Wi-Fi discovery to avoid draining the phone battery
 * while keeping advertising responsive.
 */
class DutyCycleController(
    private val scope: CoroutineScope,
    private val onStartDiscovery: suspend () -> Unit,
    private val onStopDiscovery: suspend () -> Unit,
    private val defaultActiveDuration: Duration = Duration.ofSeconds(15),
    private val defaultIdleDuration: Duration = Duration.ofSeconds(30),
) {
    private var cycleJob: Job? = null

    private val _state = MutableStateFlow(DutyCycleState.STOPPED)
    val state: StateFlow<DutyCycleState> = _state.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    @Volatile
    var powerProfile: PowerProfile = PowerProfile.NORMAL

    @Volatile
    var dutyCycleEnabled: Boolean = true

    /**
     * Starts the duty cycling loop.
     */
    @Synchronized
    fun start() {
        if (cycleJob != null) return

        cycleJob = scope.launch {
            while (isActive) {
                if (!dutyCycleEnabled) {
                    // Continuous discovery mode
                    _state.value = DutyCycleState.SCANNING
                    _isScanning.value = true
                    onStartDiscovery()
                    // Stay scanning until stopped or enabled
                    while (!dutyCycleEnabled && isActive) {
                        delay(1000)
                    }
                    continue
                }

                val (activeDuration, idleDuration) = computeDurations()

                // Phase 1: Active Discovery
                _state.value = DutyCycleState.SCANNING
                _isScanning.value = true
                onStartDiscovery()
                delay(activeDuration.toMillis())

                if (!isActive) break

                // Phase 2: Idle (Sleep) Window
                _state.value = DutyCycleState.IDLE
                _isScanning.value = false
                onStopDiscovery()
                delay(idleDuration.toMillis())
            }
        }
    }

    /**
     * Stops duty cycling and ensures discovery is turned off.
     */
    @Synchronized
    fun stop() {
        cycleJob?.cancel()
        cycleJob = null
        _state.value = DutyCycleState.STOPPED
        _isScanning.value = false
        scope.launch {
            onStopDiscovery()
        }
    }

    private fun computeDurations(): Pair<Duration, Duration> {
        return when (powerProfile) {
            PowerProfile.NORMAL -> defaultActiveDuration to defaultIdleDuration
            PowerProfile.BATTERY_SAVER -> Duration.ofSeconds(10) to Duration.ofSeconds(60)
            PowerProfile.AGGRESSIVE_DISCOVERY -> Duration.ofSeconds(20) to Duration.ofSeconds(10)
        }
    }
}

