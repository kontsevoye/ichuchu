package io.github.kontsevoye.ichuchu.service.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.kontsevoye.ichuchu.data.update.UpdateCandidate
import io.github.kontsevoye.ichuchu.data.update.UpdateException
import io.github.kontsevoye.ichuchu.data.update.UpdateFailure
import io.github.kontsevoye.ichuchu.data.update.UpdateStore
import java.io.File
import java.util.UUID

class UpdateInstaller(private val context: Context, private val store: UpdateStore) {
    private val installer
        get() = context.packageManager.packageInstaller

    fun permitted(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun liveSession(): Boolean =
        installer.getSessionInfo(store.sessionId)?.let {
            it.installerPackageName == context.packageName &&
                it.appPackageName == context.packageName
        } == true

    fun processing(): Boolean = installer.getSessionInfo(store.sessionId)?.isActive == true

    fun start(file: File, candidate: UpdateCandidate, active: () -> Boolean) {
        if (!active()) throw kotlinx.coroutines.CancellationException()
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= 31)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { target ->
                    file.inputStream().use { input ->
                        val bytes = ByteArray(32 * 1024)
                        while (true) {
                            if (!active()) throw kotlinx.coroutines.CancellationException()
                            val count = input.read(bytes)
                            if (count < 0) break
                            target.write(bytes, 0, count)
                        }
                    }
                    session.fsync(target)
                }
                val action = "${context.packageName}.UPDATE_INSTALL.${UUID.randomUUID()}"
                store.saveSession(id, candidate.manifest.versionCode, action)
                if (!active()) throw kotlinx.coroutines.CancellationException()
                session.commit(callback(id, action).intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            store.clearSession()
            throw error
        }
    }

    /**
     * A live sealed session can reissue confirmation after process death. Never recreate a
     * tag/session blindly.
     */
    fun resume() {
        if (!liveSession())
            throw UpdateException(UpdateFailure.Install, "Installation session no longer exists")
        val action =
            store.callbackAction
                ?: throw UpdateException(UpdateFailure.Install, "Installation state is missing")
        installer.openSession(store.sessionId).use {
            it.commit(callback(store.sessionId, action).intentSender)
        }
    }

    fun abandon() {
        if (store.sessionId >= 0) runCatching { installer.abandonSession(store.sessionId) }
        store.clearSession()
    }

    private fun callback(id: Int, action: String): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(action)
        // Android 15+ rejects immutable commit callbacks; the explicit receiver is private.
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }
}
