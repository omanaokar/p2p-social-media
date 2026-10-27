package com.proximity.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.proximity.app.MainActivity
import com.proximity.app.ProximityApp
import com.proximity.transport.android.NearbyForegroundServiceDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service hosting the Nearby Connections mesh background execution.
 * Ensures the P2P radio remains active even when the app is backgrounded.
 */
class NearbyForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var peerCollectionJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == NearbyForegroundServiceDescriptor.ACTION_STOP_FOREGROUND) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(isScanning = true, connectedPeerCount = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NearbyForegroundServiceDescriptor.NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
            )
        } else {
            startForeground(NearbyForegroundServiceDescriptor.NOTIFICATION_ID, notification)
        }

        observePeers()

        return START_STICKY
    }

    override fun onDestroy() {
        peerCollectionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observePeers() {
        peerCollectionJob?.cancel()
        val app = application as? ProximityApp ?: return
        peerCollectionJob = serviceScope.launch {
            try {
                app.container.transport.peers.collect { peerList ->
                    updateNotification(isScanning = true, connectedPeerCount = peerList.size)
                }
            } catch (_: Exception) {
                // Transport might not be active yet or closed
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NearbyForegroundServiceDescriptor.CHANNEL_ID,
                NearbyForegroundServiceDescriptor.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors proximity peer mesh network in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isScanning: Boolean, connectedPeerCount: Int): Notification =
        NotificationCompat.Builder(this, NearbyForegroundServiceDescriptor.CHANNEL_ID)
            .setContentTitle("Proximity Mesh Active")
            .setContentText(
                NearbyForegroundServiceDescriptor.formatNotificationText(isScanning, connectedPeerCount)
            )
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun updateNotification(isScanning: Boolean, connectedPeerCount: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(
            NearbyForegroundServiceDescriptor.NOTIFICATION_ID,
            buildNotification(isScanning, connectedPeerCount)
        )
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, NearbyForegroundService::class.java).apply {
                action = NearbyForegroundServiceDescriptor.ACTION_START_FOREGROUND
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NearbyForegroundService::class.java).apply {
                action = NearbyForegroundServiceDescriptor.ACTION_STOP_FOREGROUND
            }
            context.startService(intent)
        }
    }
}
