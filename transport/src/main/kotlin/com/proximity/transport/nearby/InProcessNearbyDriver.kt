package com.proximity.transport.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared medium simulating the physical radio environment (Bluetooth/Wi-Fi)
 * across multiple [InProcessNearbyDriver] nodes in a process or test suite.
 */
class RadioMedium {
    private val drivers = ConcurrentHashMap<String, InProcessNearbyDriver>()
    private val advertising = ConcurrentHashMap<String, Pair<String, String>>() // endpointId -> (endpointName, serviceId)
    private val discovering = ConcurrentHashMap<String, String>() // endpointId -> serviceId

    fun register(endpointId: String, driver: InProcessNearbyDriver) {
        drivers[endpointId] = driver
    }

    fun unregister(endpointId: String) {
        drivers.remove(endpointId)
        advertising.remove(endpointId)
        discovering.remove(endpointId)
    }

    fun startAdvertising(endpointId: String, endpointName: String, serviceId: String) {
        advertising[endpointId] = endpointName to serviceId
        // Notify any existing discoverers looking for this service
        for ((discovererId, targetService) in discovering) {
            if (discovererId != endpointId && targetService == serviceId) {
                drivers[discovererId]?.deliverDiscovery(endpointId, endpointName, serviceId)
            }
        }
    }

    fun stopAdvertising(endpointId: String) {
        advertising.remove(endpointId)
    }

    fun startDiscovery(endpointId: String, serviceId: String) {
        discovering[endpointId] = serviceId
        // Find existing advertisers matching this service
        for ((advertiserId, info) in advertising) {
            if (advertiserId != endpointId && info.second == serviceId) {
                drivers[endpointId]?.deliverDiscovery(advertiserId, info.first, serviceId)
            }
        }
    }

    fun stopDiscovery(endpointId: String) {
        discovering.remove(endpointId)
    }

    fun sendConnectionRequest(fromId: String, fromName: String, toId: String) {
        drivers[toId]?.deliverConnectionInitiated(fromId, fromName, isIncoming = true)
    }

    fun sendConnectionResponse(fromId: String, toId: String, accepted: Boolean) {
        val fromDriver = drivers[fromId]
        val toDriver = drivers[toId]
        if (accepted && fromDriver != null && toDriver != null) {
            fromDriver.deliverConnectionResult(toId, toDriver.endpointName, success = true)
            toDriver.deliverConnectionResult(fromId, fromDriver.endpointName, success = true)
        } else {
            drivers[toId]?.deliverConnectionResult(fromId, fromDriver?.endpointName ?: fromId, success = false)
        }
    }

    fun sendBytes(fromId: String, toId: String, bytes: ByteArray): Boolean {
        val target = drivers[toId] ?: return false
        target.deliverPayload(fromId, bytes)
        return true
    }

    fun disconnect(fromId: String, toId: String) {
        drivers[toId]?.deliverDisconnected(fromId)
        drivers[fromId]?.deliverDisconnected(toId)
    }

    fun updateSignal(endpointA: String, endpointB: String, rssi: Int) {
        drivers[endpointA]?.deliverSignalStrength(endpointB, rssi)
        drivers[endpointB]?.deliverSignalStrength(endpointA, rssi)
    }
}

/**
 * In-process implementation of [NearbyDriver] for JVM unit tests, emulator simulations,
 * and multi-phone synthetic mesh testing.
 */
class InProcessNearbyDriver(
    val endpointId: String,
    val endpointName: String,
    private val medium: RadioMedium,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : NearbyDriver {

    private var listener: NearbyDriverListener? = null

    init {
        medium.register(endpointId, this)
    }

    override suspend fun startAdvertising(endpointName: String, serviceId: String) {
        medium.startAdvertising(endpointId, endpointName, serviceId)
    }

    override suspend fun stopAdvertising() {
        medium.stopAdvertising(endpointId)
    }

    override suspend fun startDiscovery(serviceId: String) {
        medium.startDiscovery(endpointId, serviceId)
    }

    override suspend fun stopDiscovery() {
        medium.stopDiscovery(endpointId)
    }

    override suspend fun requestConnection(endpointName: String, endpointId: String): Boolean {
        medium.sendConnectionRequest(fromId = this.endpointId, fromName = endpointName, toId = endpointId)
        return true
    }

    override suspend fun acceptConnection(endpointId: String): Boolean {
        medium.sendConnectionResponse(fromId = this.endpointId, toId = endpointId, accepted = true)
        return true
    }

    override suspend fun rejectConnection(endpointId: String): Boolean {
        medium.sendConnectionResponse(fromId = this.endpointId, toId = endpointId, accepted = false)
        return true
    }

    override suspend fun disconnect(endpointId: String) {
        medium.disconnect(this.endpointId, endpointId)
    }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray): Boolean {
        return medium.sendBytes(this.endpointId, endpointId, bytes)
    }

    override fun registerListener(listener: NearbyDriverListener) {
        this.listener = listener
    }

    override fun unregisterListener() {
        this.listener = null
    }

    internal fun deliverDiscovery(id: String, name: String, serviceId: String) {
        scope.launch { listener?.onEndpointDiscovered(id, name, serviceId) }
    }

    internal fun deliverConnectionInitiated(id: String, name: String, isIncoming: Boolean) {
        scope.launch { listener?.onConnectionInitiated(id, name, isIncoming) }
    }

    internal fun deliverConnectionResult(id: String, name: String, success: Boolean) {
        scope.launch { listener?.onConnectionResult(id, name, success) }
    }

    internal fun deliverDisconnected(id: String) {
        scope.launch { listener?.onDisconnected(id) }
    }

    internal fun deliverPayload(fromId: String, bytes: ByteArray) {
        scope.launch { listener?.onPayloadReceived(fromId, bytes) }
    }

    internal fun deliverSignalStrength(fromId: String, rssi: Int) {
        scope.launch { listener?.onSignalStrengthChanged(fromId, rssi) }
    }

    fun close() {
        medium.unregister(endpointId)
    }
}

