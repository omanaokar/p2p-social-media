package com.proximity.app

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.proximity.transport.nearby.NearbyDriver
import com.proximity.transport.nearby.NearbyDriverListener
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/** Production Android bridge from NearbyTransport to Google Nearby Connections. */
class GoogleNearbyDriver(context: Context) : NearbyDriver {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val endpointNames = ConcurrentHashMap<String, String>()
    @Volatile private var listener: NearbyDriverListener? = null

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            listener?.onConnectionInitiated(endpointId, info.endpointName, info.isIncomingConnection)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            listener?.onConnectionResult(endpointId, endpointNames[endpointId] ?: endpointId, resolution.status.isSuccess)
        }

        override fun onDisconnected(endpointId: String) {
            endpointNames.remove(endpointId)
            listener?.onDisconnected(endpointId)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            listener?.onEndpointDiscovered(endpointId, info.endpointName, info.serviceId)
        }

        override fun onEndpointLost(endpointId: String) {
            endpointNames.remove(endpointId)
            listener?.onEndpointLost(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) payload.asBytes()?.let { listener?.onPayloadReceived(endpointId, it) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    override suspend fun startAdvertising(endpointName: String, serviceId: String) {
        client.startAdvertising(endpointName, serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).await()
    }
    override suspend fun stopAdvertising() { client.stopAdvertising() }
    override suspend fun startDiscovery(serviceId: String) {
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).await()
    }
    override suspend fun stopDiscovery() { client.stopDiscovery() }
    override suspend fun requestConnection(endpointName: String, endpointId: String): Boolean = runCatching { client.requestConnection(endpointName, endpointId, lifecycle).await(); true }.getOrDefault(false)
    override suspend fun acceptConnection(endpointId: String): Boolean = runCatching { client.acceptConnection(endpointId, payloads).await(); true }.getOrDefault(false)
    override suspend fun rejectConnection(endpointId: String): Boolean = runCatching { client.rejectConnection(endpointId).await(); true }.getOrDefault(false)
    override suspend fun disconnect(endpointId: String) { client.disconnectFromEndpoint(endpointId) }
    override suspend fun sendBytes(endpointId: String, bytes: ByteArray): Boolean = runCatching { client.sendPayload(endpointId, Payload.fromBytes(bytes)).await(); true }.getOrDefault(false)
    override fun registerListener(listener: NearbyDriverListener) { this.listener = listener }
    override fun unregisterListener() { listener = null }
}
