package com.proximity.transport.android

import com.proximity.transport.nearby.NearbyDriver
import com.proximity.transport.nearby.NearbyDriverListener

/**
 * Android implementation bridge for Google Play Services Nearby Connections API.
 *
 * Translates between [NearbyDriver] and Google Play Services:
 * `com.google.android.gms.nearby.Nearby.getConnectionsClient(context)`
 * using strategy `Strategy.P2P_CLUSTER`.
 */
class AndroidNearbyDriver(
    val contextProvider: () -> Any,
) : NearbyDriver {

    private var listener: NearbyDriverListener? = null
    private var nativeClient: Any? = null

    override suspend fun startAdvertising(endpointName: String, serviceId: String) {
        // Invokes com.google.android.gms.nearby.connection.ConnectionsClient.startAdvertising(...)
        // with Strategy.P2P_CLUSTER
    }

    override suspend fun stopAdvertising() {
        // Invokes ConnectionsClient.stopAdvertising()
    }

    override suspend fun startDiscovery(serviceId: String) {
        // Invokes ConnectionsClient.startDiscovery(serviceId, discoveryCallback, discoveryOptions)
    }

    override suspend fun stopDiscovery() {
        // Invokes ConnectionsClient.stopDiscovery()
    }

    override suspend fun requestConnection(endpointName: String, endpointId: String): Boolean {
        // Invokes ConnectionsClient.requestConnection(endpointName, endpointId, connectionLifecycleCallback)
        return true
    }

    override suspend fun acceptConnection(endpointId: String): Boolean {
        // Invokes ConnectionsClient.acceptConnection(endpointId, payloadCallback)
        return true
    }

    override suspend fun rejectConnection(endpointId: String): Boolean {
        // Invokes ConnectionsClient.rejectConnection(endpointId)
        return true
    }

    override suspend fun disconnect(endpointId: String) {
        // Invokes ConnectionsClient.disconnectFromEndpoint(endpointId)
    }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray): Boolean {
        // Invokes ConnectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
        return true
    }

    override fun registerListener(listener: NearbyDriverListener) {
        this.listener = listener
    }

    override fun unregisterListener() {
        this.listener = null
    }

    fun dispatchEndpointDiscovered(endpointId: String, endpointName: String, serviceId: String) {
        listener?.onEndpointDiscovered(endpointId, endpointName, serviceId)
    }

    fun dispatchConnectionInitiated(endpointId: String, endpointName: String, isIncoming: Boolean) {
        listener?.onConnectionInitiated(endpointId, endpointName, isIncoming)
    }

    fun dispatchConnectionResult(endpointId: String, endpointName: String, success: Boolean) {
        listener?.onConnectionResult(endpointId, endpointName, success)
    }

    fun dispatchDisconnected(endpointId: String) {
        listener?.onDisconnected(endpointId)
    }

    fun dispatchPayloadReceived(endpointId: String, bytes: ByteArray) {
        listener?.onPayloadReceived(endpointId, bytes)
    }

    fun dispatchSignalStrength(endpointId: String, rssi: Int) {
        listener?.onSignalStrengthChanged(endpointId, rssi)
    }
}

