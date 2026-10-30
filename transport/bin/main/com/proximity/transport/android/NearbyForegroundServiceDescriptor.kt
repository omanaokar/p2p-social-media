package com.proximity.transport.android

/**
 * Service specifications and intent definitions for running Nearby Connections
 * in an Android Foreground Service.
 */
object NearbyForegroundServiceDescriptor {
    const val CHANNEL_ID = "proximity_p2p_channel"
    const val CHANNEL_NAME = "Proximity P2P Network"
    const val NOTIFICATION_ID = 1001

    const val ACTION_START_FOREGROUND = "com.proximity.transport.action.START_FOREGROUND"
    const val ACTION_STOP_FOREGROUND = "com.proximity.transport.action.STOP_FOREGROUND"

    // Foreground service type constant for Android 14+ (FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    const val FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE = 16

    /**
     * Formats notification text dynamically based on mesh connectivity state.
     */
    fun formatNotificationText(isScanning: Boolean, connectedPeerCount: Int): String {
        val status = if (isScanning) "Scanning for nearby peers" else "Mesh active"
        return when (connectedPeerCount) {
            0 -> "$status • No one nearby yet"
            1 -> "$status • 1 person nearby"
            else -> "$status • $connectedPeerCount people nearby"
        }
    }
}

