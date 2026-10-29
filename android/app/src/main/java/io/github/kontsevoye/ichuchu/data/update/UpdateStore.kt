package io.github.kontsevoye.ichuchu.data.update

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("ichuchu_updates", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    var lastChecked: Long
        get() = prefs.getLong("checked", 0)
        set(value) {
            prefs.edit().putLong("checked", value).apply()
        }

    var retryAt: Long
        get() = prefs.getLong("retryAt", 0)
        set(value) {
            prefs.edit().putLong("retryAt", value).apply()
        }

    var etag: String?
        get() = prefs.getString("etag", null)
        set(value) {
            prefs.edit().putString("etag", value).apply()
        }

    var releaseJson: String?
        get() = prefs.getString("release", null)
        set(value) {
            prefs.edit().putString("release", value).apply()
        }

    var lastNotifiedCode: Long
        get() = prefs.getLong("notified", 0)
        set(value) {
            prefs.edit().putLong("notified", value).commit()
        }

    var automatic: Boolean
        get() = prefs.getBoolean("automatic", false)
        set(value) {
            prefs.edit().putBoolean("automatic", value).commit()
        }

    var filename: String?
        get() = prefs.getString("filename", null)
        set(value) {
            prefs.edit().putString("filename", value).apply()
        }

    var candidate: UpdateCandidate?
        get() =
            prefs.getString("candidate", null)?.let {
                runCatching { json.decodeFromString<UpdateCandidate>(it) }.getOrNull()
            }
        set(value) {
            prefs.edit().putString("candidate", value?.let { json.encodeToString(it) }).apply()
        }

    val sessionId: Int
        get() = prefs.getInt("session", -1)

    val expectedCode: Long
        get() = prefs.getLong("expected", 0)

    val callbackAction: String?
        get() = prefs.getString("callback", null)

    var waitingForUser: Boolean
        get() = prefs.getBoolean("waiting", false)
        set(value) {
            prefs.edit().putBoolean("waiting", value).commit()
        }

    var installMessage: String?
        get() = prefs.getString("installMessage", null)
        set(value) {
            prefs.edit().putString("installMessage", value).commit()
        }

    fun saveSession(id: Int, code: Long, action: String) {
        if (
            !prefs
                .edit()
                .putInt("session", id)
                .putLong("expected", code)
                .putString("callback", action)
                .putBoolean("waiting", false)
                .remove("installMessage")
                .commit()
        )
            throw UpdateException(UpdateFailure.Install, "Could not save installation state")
    }

    fun clearSession() {
        prefs
            .edit()
            .remove("session")
            .remove("expected")
            .remove("callback")
            .remove("waiting")
            .commit()
    }
}
