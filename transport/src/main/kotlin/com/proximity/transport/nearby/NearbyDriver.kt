package com.proximity.transport.nearby

interface NearbyDriverListener {
    /**
     * Called when a nearby device is discovered by radio scan.
     */
    fun onEndpointDiscovered(endpointId: String, endpointName: String, serviceId: String)

    /**
     * Called when a previously discovered endpoint is no longer in radio range.
     */
    fun onEndpointLost(endpointId: String)

    /**
     * Called when a connection handshake is initiated (incoming or outgoing).
     */
    fun onConnectionInitiated(endpointId: String, endpointName: String, isIncoming: Boolean)

    /**
     * Called when the connection handshake completes (successful or failed).
     */
    fun onConnectionResult(endpointId: String, endpointName: String, success: Boolean)

    /**
     * Called when a connected link drops or disconnects.
     */
    fun onDisconnected(endpointId: String)

    /**
     * Called when a byte payload arrives from a peer.
     */
    fun onPayloadReceived(endpointId: String, bytes: ByteArray)

    /**
     * Called when radio signal strength (RSSI) is updated for an endpoint.
     */
    fun onSignalStrengthChanged(endpointId: String, rssi: Int)
}

/**
 * Driver abstraction over the underlying peer-to-peer radio.
 * Decouples the transport protocol engine from Android Google Play Services
 * so the entire stack is testable on JVM.
 */
interface NearbyDriver {
    suspend fun startAdvertising(endpointName: String, serviceId: String)
    suspend fun stopAdvertising()

    suspend fun startDiscovery(serviceId: String)
    suspend fun stopDiscovery()

    suspend fun requestConnection(endpointName: String, endpointId: String): Boolean
    suspend fun acceptConnection(endpointId: String): Boolean
    suspend fun rejectConnection(endpointId: String): Boolean
    suspend fun disconnect(endpointId: String)

    suspend fun sendBytes(endpointId: String, bytes: ByteArray): Boolean

    fun registerListener(listener: NearbyDriverListener)
    fun unregisterListener()
}

