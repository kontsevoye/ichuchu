package io.github.kontsevoye.ichuchu.service.agents

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.kontsevoye.ichuchu.MainActivity
import io.github.kontsevoye.ichuchu.R

object AgentNotifications {
    const val CHANNEL = "ichuchu_agent_status"
    const val HOST_ID = "agent_host_id"
    const val SESSION_ID = "agent_session_id"
    private const val ID = 3002

    fun enabled(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 26 ||
                context
                    .getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(CHANNEL)
                    ?.importance != NotificationManager.IMPORTANCE_NONE)

    fun chatIntent(context: Context, hostId: Long, sessionId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data =
                Uri.Builder()
                    .scheme("ichuchu")
                    .authority("agent")
                    .appendPath(hostId.toString())
                    .appendPath(sessionId)
                    .build()
            putExtra(HOST_ID, hostId)
            putExtra(SESSION_ID, sessionId)
        }

    fun show(
        context: Context,
        hostId: Long,
        hostName: String,
        session: DeckSession,
        updateExisting: Boolean = false,
    ) {
        val manager = NotificationManagerCompat.from(context)
        val tag = "agent:$hostId:${session.id}"
        val status =
            when (session.status) {
                AgentStatus.Ready -> "Ready for input"
                AgentStatus.Waiting -> "Waiting"
                AgentStatus.Attention ->
                    if (session.needsTerminal) "Input needed in terminal" else "Needs attention"
                AgentStatus.Error -> "Agent error"
                else -> {
                    manager.cancel(tag, ID)
                    return
                }
            }
        if (Build.VERSION.SDK_INT >= 26) {
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                            CHANNEL,
                            "Agent status",
                            NotificationManager.IMPORTANCE_HIGH,
                        )
                        .apply {
                            description = "Ready and waiting alerts for connected agent sessions"
                            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                        }
                )
        }
        if (!enabled(context)) return
        val tap =
            PendingIntent.getActivity(
                context,
                0,
                chatIntent(context, hostId, session.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val public =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Ichuchu agent status")
                .setContentText("Open the app to view this session")
                .build()
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${session.title} · $status")
                .setContentText("$hostName · ${session.project}")
                .setContentIntent(tap)
                .setOnlyAlertOnce(updateExisting)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .build()
        // Revoking permission between the check and notify must not terminate the connection.
        runCatching { manager.notify(tag, ID, notification) }
    }
}
