package io.github.kontsevoye.ichuchu.service.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.kontsevoye.ichuchu.MainActivity
import io.github.kontsevoye.ichuchu.R

object UpdateNotifications {
    const val OPEN_UPDATES = "ichuchu.open_updates"
    private const val CHANNEL = "ichuchu-updates"

    fun show(context: Context, title: String, body: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val system = context.getSystemService(NotificationManager::class.java)
            system.createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
            if (
                system.getNotificationChannel(CHANNEL)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
            )
                return false
        }
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra(OPEN_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending =
            PendingIntent.getActivity(
                context,
                841,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return try {
            manager.notify(
                841,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build(),
            )
            true
        } catch (error: SecurityException) {
            false
        }
    }
}
