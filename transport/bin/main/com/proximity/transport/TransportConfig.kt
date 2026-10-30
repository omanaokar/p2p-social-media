package com.proximity.transport

import java.time.Duration

/**
 * Configuration for the P2P networking and connectivity layer.
 */
data class TransportConfig(
    /**
     * Service ID for Google Nearby Connections discovery and advertising.
     * Must be unique to the app.
     */
    val serviceId: String = "com.proximity.p2p",

    /**
     * Strategy name. Default is P2P_CLUSTER (M:N mesh network), allowing
     * all nearby phones to connect to each other.
     */
    val strategyName: String = "P2P_CLUSTER",

    /**
     * Maximum number of simultaneous connected links per phone.
     * Caps radio resource usage and maintains stable connections.
     */
    val maxSimultaneousLinks: Int = 6,

    /**
     * Maximum size of a single chunk in bytes.
     * Nearby Connections single-byte payload limit is 32 KB.
     * We use 24 KB per chunk to leave headroom for transport headers and framing.
     */
    val chunkSizeBytes: Int = 24 * 1024,

    /**
     * Maximum allowed payload size in bytes (e.g. 10 MB for compressed images).
     * Payloads larger than this are rejected to prevent memory exhaustion.
     */
    val maxPayloadSizeBytes: Int = 10 * 1024 * 1024,

    /**
     * Timeout for assembling all chunks of a transfer before discarding partial buffers.
     */
    val chunkAssemblyTimeout: Duration = Duration.ofSeconds(30),

    /**
     * Maximum reconnection attempts after an unexpected link drop.
     */
    val maxReconnectAttempts: Int = 3,

    /**
     * Initial backoff delay for reconnection.
     */
    val reconnectInitialBackoff: Duration = Duration.ofMillis(1000),

    /**
     * Maximum backoff delay for reconnection.
     */
    val reconnectMaxBackoff: Duration = Duration.ofSeconds(16),

    /**
     * Active scanning window duration during duty cycling.
     */
    val dutyCycleActiveScanDuration: Duration = Duration.ofSeconds(15),

    /**
     * Idle (sleep) window duration between active scans to conserve battery.
     */
    val dutyCycleIdleDuration: Duration = Duration.ofSeconds(30),

    /**
     * Whether duty cycling of discovery is enabled.
     */
    val dutyCycleEnabled: Boolean = true,

    /**
     * Exponential smoothing factor for RSSI / signal strength (alpha between 0.0 and 1.0).
     * Higher gives more weight to recent readings; lower produces smoother signal history.
     */
    val signalSmoothingAlpha: Double = 0.3,
)

