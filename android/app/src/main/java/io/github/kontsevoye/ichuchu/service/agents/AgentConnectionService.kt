package io.github.kontsevoye.ichuchu.service.agents

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
import io.github.kontsevoye.ichuchu.MainActivity
import io.github.kontsevoye.ichuchu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Visible user-controlled connection, including status monitoring while the UI is backgrounded. */
class AgentConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connections by lazy { AgentConnections.get(this) }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Agent connections",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
        }
        scope.launch {
            connections.active.collect { hosts ->
                if (hosts.isNotEmpty()) {
                    getSystemService(NotificationManager::class.java)
                        .notify(ID, notification(hosts.values.joinToString()))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) {
            connections.disconnectAll()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            ID,
            notification(connections.active.value.values.joinToString()),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (connections.active.value.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        connections.disconnectAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(label: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ichuchu · agents connected")
            .setContentText(label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    ID,
                    connections.active.value.keys.firstOrNull()?.let {
                        AgentNotifications.chatIntent(this, it, "")
                    } ?: Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0,
                "Disconnect",
                PendingIntent.getService(
                    this,
                    ID,
                    Intent(this, AgentConnectionService::class.java).setAction(DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val CHANNEL = "ichuchu_agent_connections"
        private const val ID = 3001
        private const val DISCONNECT = "io.github.kontsevoye.ichuchu.DISCONNECT_AGENTS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentConnectionService::class.java),
            )
        }
    }
}
