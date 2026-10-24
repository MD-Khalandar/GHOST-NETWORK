package org.ghost.app

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
import androidx.core.content.ContextCompat
import org.ghost.R

/** A user-started connection continues independently of the activity's lifecycle. */
class GhostConnectionService : Service() {
    private val graph get() = (application as GhostApplication).graph

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "GHOST connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Nearby sharing and online coordination while connected to GHOST"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != CONNECT) {
            graph.endConnection()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PENDING_FLAGS)
            val disconnect = PendingIntent.getService(this, 1,
                Intent(this, GhostConnectionService::class.java).setAction(DISCONNECT), PENDING_FLAGS)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_connection)
                .setContentTitle("GHOST is active")
                .setContentText("Nearby sharing and online sync stay active. Tap to open GHOST.")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, "Disconnect", disconnect)
                .build()
            // Show the notification before opening storage or starting network work.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            graph.beginConnection()
        } catch (_: Exception) {
            graph.connectionFailed()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Force-stop/process termination requires another Connect tap. No boot receiver.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        graph.endConnection()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val CHANNEL = "ghost_connection"
        internal const val NOTIFICATION_ID = 1001
        internal const val CONNECT = "org.ghost.CONNECT"
        internal const val DISCONNECT = "org.ghost.DISCONNECT"
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun connect(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, GhostConnectionService::class.java).setAction(CONNECT))
        }

        fun disconnect(context: Context) {
            context.stopService(Intent(context, GhostConnectionService::class.java))
        }
    }
}
