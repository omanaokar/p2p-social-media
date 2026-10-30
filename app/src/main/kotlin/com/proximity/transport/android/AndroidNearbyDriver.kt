package com.proximity.transport.android

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import com.proximity.transport.nearby.NearbyDriver
import com.proximity.transport.nearby.NearbyDriverListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android implementation backed by Google Play Services Nearby Connections. */
class AndroidNearbyDriver(
    private val contextProvider: () -> Context,
) : NearbyDriver {
    private val client: ConnectionsClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Nearby.getConnectionsClient(contextProvider().applicationContext)
    }

    @Volatile
    private var listener: NearbyDriverListener? = null
    private val endpointNames = ConcurrentHashMap<String, String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { listener?.onPayloadReceived(endpointId, it) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    override suspend fun startAdvertising(endpointName: String, serviceId: String) {
        client.startAdvertising(
            endpointName,
            serviceId,
            lifecycleCallback(isIncoming = true),
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).awaitResult()
    }

    override suspend fun stopAdvertising() = client.stopAdvertising()

    override suspend fun startDiscovery(serviceId: String) {
        client.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    endpointNames[endpointId] = info.endpointName
                    listener?.onEndpointDiscovered(endpointId, info.endpointName, serviceId)
                }

                override fun onEndpointLost(endpointId: String) {
                    endpointNames.remove(endpointId)
                    listener?.onEndpointLost(endpointId)
                }
            },
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).awaitResult()
    }

    override suspend fun stopDiscovery() = client.stopDiscovery()

    override suspend fun requestConnection(endpointName: String, endpointId: String): Boolean = runCatching {
        client.requestConnection(endpointName, endpointId, lifecycleCallback(isIncoming = false)).awaitResult()
    }.isSuccess

    override suspend fun acceptConnection(endpointId: String): Boolean = runCatching {
        client.acceptConnection(endpointId, payloadCallback).awaitResult()
    }.isSuccess

    override suspend fun rejectConnection(endpointId: String): Boolean = runCatching {
        client.rejectConnection(endpointId).awaitResult()
    }.isSuccess

    override suspend fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray): Boolean = runCatching {
        client.sendPayload(endpointId, Payload.fromBytes(bytes)).awaitResult()
    }.isSuccess

    override fun registerListener(listener: NearbyDriverListener) {
        this.listener = listener
    }

    override fun unregisterListener() {
        listener = null
        endpointNames.clear()
    }

    private fun lifecycleCallback(isIncoming: Boolean) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            listener?.onConnectionInitiated(endpointId, info.endpointName, isIncoming)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            listener?.onConnectionResult(
                endpointId,
                endpointNames[endpointId].orEmpty(),
                resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK,
            )
        }

        override fun onDisconnected(endpointId: String) {
            endpointNames.remove(endpointId)
            listener?.onDisconnected(endpointId)
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }
}
