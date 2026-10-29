package io.github.kontsevoye.ichuchu.service.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kontsevoye.ichuchu.BuildConfig
import io.github.kontsevoye.ichuchu.data.update.UpdatePhase
import io.github.kontsevoye.ichuchu.data.update.UpdateStore
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val WORK = "ichuchu-stable-update-check"

    fun reconcile(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!BuildConfig.SELF_UPDATE_ENABLED || !UpdateStore(context).automatic) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Checks and notifies only: no download, terminal teardown, or installation occurs here. */
class UpdateCheckWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val updates = UpdateServices.get(applicationContext)
        if (!updates.config.enabled || !updates.store.automatic) return Result.success()
        if (System.currentTimeMillis() < updates.store.retryAt) return Result.success()
        updates.check(background = true)
        if (!updates.store.automatic || !updates.config.enabled || isStopped)
            return Result.success()
        val state = updates.state.value
        if (state.phase in setOf(UpdatePhase.Installing, UpdatePhase.UserConfirmation))
            return Result.success()
        if (state.phase == UpdatePhase.Error)
            return if (
                state.failure == io.github.kontsevoye.ichuchu.data.update.UpdateFailure.Network
            )
                Result.retry()
            else Result.success()
        val candidate = state.candidate ?: return Result.success()
        if (
            candidate.manifest.versionCode > updates.installed.versionCode &&
                candidate.manifest.versionCode > updates.store.lastNotifiedCode &&
                UpdateNotifications.show(
                    applicationContext,
                    "ichuchu ${candidate.manifest.versionName} is available",
                    "Open settings to review and download the update.",
                )
        ) {
            updates.store.lastNotifiedCode = candidate.manifest.versionCode
        }
        return Result.success()
    }
}
