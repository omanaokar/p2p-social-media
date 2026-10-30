package com.proximity.app

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.proximity.app.identity.AndroidKeyStore
import com.proximity.app.service.NearbyForegroundService
import com.proximity.contracts.Transport
import com.proximity.core.AppConfig
import com.proximity.core.AppCore
import com.proximity.core.identity.KeyStore
import com.proximity.ranker.RecommendationSystem
import com.proximity.transport.TransportConfig
import com.proximity.transport.android.AndroidNearbyDriver
import com.proximity.transport.mock.MockTransport
import com.proximity.transport.nearby.NearbyTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Operating mode for the P2P transport layer.
 */
enum class TransportMode {
    /** Real Google Nearby Connections mesh running P2P_CLUSTER. */
    REAL_NEARBY,

    /** In-memory mock transport allowing single-device demos and testing. */
    SIMULATED_MOCK
}

/**
 * Top-level application container managing the lifecycle of [AppCore],
 * cryptographic [KeyStore], [Transport], and the [RecommendationSystem].
 */
class AppContainer(
    val context: Context,
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val prefs = context.getSharedPreferences("proximity_app_settings", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    val keyStore: KeyStore = AndroidKeyStore(context)
    val ranker: RecommendationSystem = RecommendationSystem()

    private val _transportMode = MutableStateFlow(loadTransportMode())
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    @Volatile
    private var currentTransport: Transport? = null

    val transport: Transport
        get() = currentTransport ?: error("Transport has not been initialized yet. Call start() first.")

    private val _core = MutableStateFlow<AppCore?>(null)
    val core: StateFlow<AppCore?> = _core.asStateFlow()

    suspend fun start() = mutex.withLock {
        if (_core.value != null) return@withLock

        val mode = _transportMode.value
        val endpointName = getOrCreateEndpointName()

        val activeTransport: Transport = when (mode) {
            TransportMode.REAL_NEARBY -> NearbyTransport(
                localEndpointName = endpointName,
                config = TransportConfig(
                    serviceId = "com.proximity.p2p",
                    maxSimultaneousLinks = 6,
                    chunkSizeBytes = 24 * 1024,
                    dutyCycleEnabled = true,
                ),
                driver = AndroidNearbyDriver(contextProvider = { context }),
            )
            TransportMode.SIMULATED_MOCK -> MockTransport(
                localId = endpointName,
            )
        }
        currentTransport = activeTransport

        val newCore = try {
            AppCore.open(
                transport = activeTransport,
                keyStore = keyStore,
                driver = BundledSQLiteDriver(),
                databasePath = context.getDatabasePath("proximity.db").path,
                config = AppConfig(),
            )
        } catch (failure: Exception) {
            currentTransport = null
            throw failure
        }

        try {
            // AppCore.SyncEngine subscribes to peer events before it starts the
            // transport. Starting the driver here first could miss an early
            // connection event and leave the phones connected but unsynced.
            newCore.start()
            _core.value = newCore
        } catch (failure: Exception) {
            runCatching { newCore.close() }
            currentTransport = null
            throw failure
        }
    }

    suspend fun stop() = mutex.withLock {
        val existingCore = _core.value
        if (existingCore != null) {
            existingCore.stop()
            existingCore.close()
            _core.value = null
        }
        currentTransport?.stop()
        currentTransport = null
    }

    suspend fun switchTransportMode(newMode: TransportMode) {
        if (_transportMode.value == newMode) return
        stop()
        _transportMode.value = newMode
        prefs.edit().putString(KEY_TRANSPORT_MODE, newMode.name).apply()
        start()
    }

    fun startForegroundService() {
        NearbyForegroundService.start(context)
        _isServiceRunning.value = true
    }

    fun stopForegroundService() {
        NearbyForegroundService.stop(context)
        _isServiceRunning.value = false
    }

    private fun loadTransportMode(): TransportMode {
        val saved = prefs.getString(KEY_TRANSPORT_MODE, null)
        return if (saved != null) {
            try { TransportMode.valueOf(saved) } catch (_: Exception) { TransportMode.REAL_NEARBY }
        } else {
            TransportMode.REAL_NEARBY
        }
    }

    private fun getOrCreateEndpointName(): String {
        var name = prefs.getString(KEY_ENDPOINT_NAME, null)
        if (name == null) {
            name = "User-${UUID.randomUUID().toString().take(6)}"
            prefs.edit().putString(KEY_ENDPOINT_NAME, name).apply()
        }
        return name
    }

    companion object {
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        private const val KEY_ENDPOINT_NAME = "local_endpoint_name"
    }
}
