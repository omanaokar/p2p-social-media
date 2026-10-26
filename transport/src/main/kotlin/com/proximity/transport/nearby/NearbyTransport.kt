package com.proximity.transport.nearby

import com.proximity.contracts.ReceivedPayload
import com.proximity.contracts.Transport
import com.proximity.contracts.TransportPeer
import com.proximity.transport.TransportConfig
import com.proximity.transport.battery.DutyCycleController
import com.proximity.transport.connection.ConnectionLifecycleManager
import com.proximity.transport.framing.ChunkFrame
import com.proximity.transport.framing.FrameCodec
import com.proximity.transport.framing.HeartbeatFrame
import com.proximity.transport.framing.PayloadAssembler
import com.proximity.transport.framing.PayloadChunker
import com.proximity.transport.framing.SingleFrame
import com.proximity.transport.peer.PeerTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * P2P Transport implementation using Nearby Connections mesh architecture (P2P_CLUSTER).
 * Implements [Transport] to move opaque bytes between nearby devices.
 *
 * Responsibilities:
 * - Simultaneous discovery and advertising
 * - Connection lifecycle & capping simultaneous links
 * - Collision resolution between simultaneous connection attempts
 * - Transparent chunking & reassembly for large payloads (e.g. images)
 * - Signal strength tracking and EWMA smoothing for Neil's proximity ranking
 * - Battery-friendly discovery duty cycling
 */
class NearbyTransport(
    val localEndpointName: String = UUID.randomUUID().toString().substring(0, 8),
    val config: TransportConfig = TransportConfig(),
    private val driver: NearbyDriver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Transport, NearbyDriverListener {

    @Volatile
    private var running = false

    private val peerTable = PeerTable(smoothingAlpha = config.signalSmoothingAlpha)
    private val lifecycleManager = ConnectionLifecycleManager(
        localEndpointName = localEndpointName,
        maxSimultaneousLinks = config.maxSimultaneousLinks,
        maxReconnectAttempts = config.maxReconnectAttempts,
        initialBackoff = config.reconnectInitialBackoff,
        maxBackoff = config.reconnectMaxBackoff,
    )
    private val chunker = PayloadChunker(
        chunkSizeBytes = config.chunkSizeBytes,
        maxPayloadSizeBytes = config.maxPayloadSizeBytes,
    )
    private val assembler = PayloadAssembler(
        assemblyTimeout = config.chunkAssemblyTimeout,
    )

    private val _onReceive = MutableSharedFlow<ReceivedPayload>(replay = 16, extraBufferCapacity = 4096)
    override val onReceive: Flow<ReceivedPayload> = _onReceive.asSharedFlow()

    override val peers: Flow<List<TransportPeer>> = peerTable.peers
    override val onPeerConnected: Flow<TransportPeer> = peerTable.onPeerConnected
    override val onPeerLost: Flow<String> = peerTable.onPeerLost

    private val dutyCycleController = DutyCycleController(
        scope = scope,
        onStartDiscovery = { driver.startDiscovery(config.serviceId) },
        onStopDiscovery = { driver.stopDiscovery() },
        defaultActiveDuration = config.dutyCycleActiveScanDuration,
        defaultIdleDuration = config.dutyCycleIdleDuration,
    ).apply {
        dutyCycleEnabled = config.dutyCycleEnabled
    }

    override suspend fun start() {
        if (running) return
        running = true

        driver.registerListener(this)
        driver.startAdvertising(localEndpointName, config.serviceId)
        dutyCycleController.start()
    }

    override suspend fun stop() {
        if (!running) return
        running = false

        dutyCycleController.stop()
        driver.stopAdvertising()
        driver.stopDiscovery()

        val connected = peerTable.clear()
        for (peerId in connected) {
            driver.disconnect(peerId)
        }
        assembler.cleanupExpired()
        driver.unregisterListener()
    }

    override suspend fun send(peerId: String, bytes: ByteArray): Boolean {
        if (!running || !peerTable.contains(peerId)) {
            return false
        }

        return try {
            val frames = chunker.chunk(bytes)
            for (frame in frames) {
                val encoded = FrameCodec.encode(frame)
                val delivered = driver.sendBytes(peerId, encoded)
                if (!delivered) {
                    return false
                }
            }
            peerTable.recordBytesSent(peerId, bytes.size.toLong())
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- NearbyDriverListener Callbacks ---

    override fun onEndpointDiscovered(endpointId: String, endpointName: String, serviceId: String) {
        if (!running) return
        val shouldInitiate = lifecycleManager.onEndpointDiscovered(endpointId, endpointName)
        if (shouldInitiate) {
            scope.launch {
                driver.requestConnection(localEndpointName, endpointId)
            }
        }
    }

    override fun onEndpointLost(endpointId: String) {
        // Discovered peer moved out of radio range before connecting
    }

    override fun onConnectionInitiated(endpointId: String, endpointName: String, isIncoming: Boolean) {
        if (!running) {
            scope.launch { driver.rejectConnection(endpointId) }
            return
        }

        val canAccept = if (isIncoming) {
            lifecycleManager.onIncomingConnection(endpointId, endpointName)
        } else {
            lifecycleManager.canAcceptMoreLinks()
        }

        scope.launch {
            if (canAccept) {
                driver.acceptConnection(endpointId)
            } else {
                driver.rejectConnection(endpointId)
            }
        }
    }

    override fun onConnectionResult(endpointId: String, endpointName: String, success: Boolean) {
        if (!running) return

        if (success) {
            lifecycleManager.onConnected(endpointId, endpointName)
            peerTable.addPeer(endpointId, endpointName)
        } else {
            lifecycleManager.onDisconnected(endpointId, intentional = false)
        }
    }

    override fun onDisconnected(endpointId: String) {
        peerTable.removePeer(endpointId)
        assembler.clearPeer(endpointId)
        val backoff = lifecycleManager.onDisconnected(endpointId, intentional = false)
        if (backoff != null && running) {
            // Peer may be rediscovered later; backoff prevents rapid reconnect churn
        }
    }

    override fun onPayloadReceived(endpointId: String, bytes: ByteArray) {
        if (!running) return

        val frame = FrameCodec.decode(bytes)
        if (frame != null) {
            when (frame) {
                is SingleFrame -> {
                    peerTable.recordBytesReceived(endpointId, frame.payload.size.toLong())
                    _onReceive.tryEmit(ReceivedPayload(endpointId, frame.payload))
                }
                is ChunkFrame -> {
                    val completed = assembler.receiveChunk(endpointId, frame)
                    if (completed != null) {
                        peerTable.recordBytesReceived(endpointId, completed.size.toLong())
                        _onReceive.tryEmit(ReceivedPayload(endpointId, completed))
                    }
                }
                is HeartbeatFrame -> {
                    frame.reportedSignal?.let { peerTable.updateSignal(endpointId, it) }
                }
                else -> {}
            }
        } else {
            // Fallback for un-framed raw byte payloads
            peerTable.recordBytesReceived(endpointId, bytes.size.toLong())
            _onReceive.tryEmit(ReceivedPayload(endpointId, bytes))
        }
    }

    override fun onSignalStrengthChanged(endpointId: String, rssi: Int) {
        peerTable.updateSignal(endpointId, rssi)
    }

    fun isRunning(): Boolean = running

    fun peerTable(): PeerTable = peerTable

    fun lifecycleManager(): ConnectionLifecycleManager = lifecycleManager

    fun close() {
        scope.cancel()
    }
}
