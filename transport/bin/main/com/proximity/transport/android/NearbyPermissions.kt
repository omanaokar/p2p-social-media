package com.proximity.transport.android

/**
 * Android permissions manager and rationale provider for Nearby Connections P2P networking.
 * Covers requirements across Android 6 through Android 15+.
 */
object NearbyPermissions {

    // Android 12+ (API 31+) permissions
    const val PERMISSION_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val PERMISSION_BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

    // Android 13+ (API 33+) permissions
    const val PERMISSION_NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    // Android 14+ (API 34+) permissions
    const val PERMISSION_FOREGROUND_SERVICE_CONNECTED_DEVICE = "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"

    // Legacy permissions (API 23 - 30)
    const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val PERMISSION_ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val PERMISSION_BLUETOOTH = "android.permission.BLUETOOTH"
    const val PERMISSION_BLUETOOTH_ADMIN = "android.permission.BLUETOOTH_ADMIN"
    const val PERMISSION_ACCESS_WIFI_STATE = "android.permission.ACCESS_WIFI_STATE"
    const val PERMISSION_CHANGE_WIFI_STATE = "android.permission.CHANGE_WIFI_STATE"

    // General foreground service
    const val PERMISSION_FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"

    /**
     * Returns the exact set of runtime permissions required for the current Android SDK level.
     */
    fun requiredRuntimePermissions(sdkInt: Int): List<String> {
        val permissions = mutableListOf<String>()

        if (sdkInt >= 31) {
            // Android 12+ Nearby device permissions
            permissions.add(PERMISSION_BLUETOOTH_SCAN)
            permissions.add(PERMISSION_BLUETOOTH_ADVERTISE)
            permissions.add(PERMISSION_BLUETOOTH_CONNECT)
        }

        // Nearby Connections requires location on Android 10-12, and the
        // nearby Wi-Fi runtime permission starting with Android 12L.
        if (sdkInt in 29..31) permissions.add(PERMISSION_ACCESS_FINE_LOCATION)
        if (sdkInt <= 28) permissions.add(PERMISSION_ACCESS_COARSE_LOCATION)
        if (sdkInt >= 32) permissions.add(PERMISSION_NEARBY_WIFI_DEVICES)

        return permissions
    }

    /**
     * Clear, user-friendly rationale explanations for Sarthak's permission screens.
     */
    fun getRationale(permission: String): String = when (permission) {
        PERMISSION_BLUETOOTH_SCAN ->
            "Allows the app to scan for nearby peers over Bluetooth without using GPS."
        PERMISSION_BLUETOOTH_ADVERTISE ->
            "Allows your phone to broadcast its presence so nearby friends can find your posts."
        PERMISSION_BLUETOOTH_CONNECT ->
            "Allows your phone to establish direct peer-to-peer links with people nearby."
        PERMISSION_NEARBY_WIFI_DEVICES ->
            "Allows high-speed Wi-Fi Direct connections to exchange images and posts quickly."
        PERMISSION_ACCESS_FINE_LOCATION, PERMISSION_ACCESS_COARSE_LOCATION ->
            "Android requires location permission for Bluetooth scanning on this version of Android. No location data is ever stored or transmitted."
        PERMISSION_POST_NOTIFICATIONS ->
            "Displays a background indicator when scanning for nearby people."
        else ->
            "Required for peer-to-peer connectivity."
    }
}

