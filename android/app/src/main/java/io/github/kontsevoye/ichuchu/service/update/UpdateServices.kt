package io.github.kontsevoye.ichuchu.service.update

import android.app.Application
import android.content.Context
import io.github.kontsevoye.ichuchu.BuildConfig
import io.github.kontsevoye.ichuchu.data.update.UpdateConfig
import io.github.kontsevoye.ichuchu.data.update.UpdateRepository

object UpdateServices {
    @Volatile private var instance: UpdateRepository? = null

    fun get(context: Context): UpdateRepository =
        instance
            ?: synchronized(this) {
                val app = context.applicationContext as Application
                instance
                    ?: UpdateRepository(
                            app,
                            UpdateConfig(
                                BuildConfig.SELF_UPDATE_ENABLED,
                                BuildConfig.UPDATE_REPOSITORY,
                                BuildConfig.UPDATE_DISTRIBUTION,
                            ),
                        )
                        .also {
                            instance = it
                            it.onScheduleChanged = { UpdateScheduler.reconcile(app) }
                            it.onAttention = {
                                UpdateNotifications.show(
                                    app,
                                    "Finish installing the update",
                                    "Open ichuchu to continue with Android's installer.",
                                )
                            }
                            UpdateScheduler.reconcile(app)
                        }
            }
}
