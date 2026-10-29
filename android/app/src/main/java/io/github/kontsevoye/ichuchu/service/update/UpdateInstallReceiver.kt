package io.github.kontsevoye.ichuchu.service.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only the explicit system callback PendingIntent can reach this non-exported receiver. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateServices.get(context).receiveInstallResult(intent)
    }
}
