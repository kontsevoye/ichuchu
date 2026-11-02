package io.github.kontsevoye.ichuchu.service.agents

import android.app.Application
import android.content.Context
import android.content.Intent
import io.github.kontsevoye.ichuchu.ui.screens.agents.AgentWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentChatTarget(
    val hostId: Long,
    val sessionId: String,
    val request: Long = System.nanoTime(),
)

/**
 * Main-thread owner for explicitly connected hosts. The service never restores credentials itself.
 */
class AgentConnections private constructor(private val application: Application) {
    private val workspaces = mutableMapOf<Long, AgentWorkspace>()
    private val references = mutableMapOf<Long, Int>()
    private val mutableActive = MutableStateFlow<Map<Long, String>>(emptyMap())
    val active = mutableActive.asStateFlow()
    private val mutableTarget = MutableStateFlow<AgentChatTarget?>(null)
    val target = mutableTarget.asStateFlow()

    fun workspace(hostId: Long): AgentWorkspace =
        workspaces.getOrPut(hostId) { AgentWorkspace(application, hostId, this) }

    fun acquire(hostId: Long): AgentWorkspace {
        references[hostId] = references.getOrDefault(hostId, 0) + 1
        return workspace(hostId)
    }

    fun isActive(hostId: Long): Boolean = hostId in mutableActive.value

    fun activate(hostId: Long, name: String) {
        val first = mutableActive.value.isEmpty()
        mutableActive.value = mutableActive.value + (hostId to name)
        if (first) {
            try {
                AgentConnectionService.start(application)
            } catch (e: Exception) {
                mutableActive.value = mutableActive.value - hostId
                throw IllegalStateException("Open the app to keep this agent connection active", e)
            }
        }
    }

    fun deactivate(hostId: Long) {
        mutableActive.value = mutableActive.value - hostId
        if (mutableActive.value.isEmpty()) {
            application.stopService(Intent(application, AgentConnectionService::class.java))
        }
        releaseUnused(hostId)
    }

    fun disconnect(hostId: Long) {
        workspaces[hostId]?.disconnect()
    }

    fun disconnectAll() = workspaces.values.toList().forEach { it.disconnect() }

    fun release(hostId: Long) {
        val remaining = (references.getOrDefault(hostId, 1) - 1).coerceAtLeast(0)
        if (remaining == 0) references.remove(hostId) else references[hostId] = remaining
        releaseUnused(hostId)
    }

    private fun releaseUnused(hostId: Long) {
        if (hostId !in references && !isActive(hostId)) workspaces.remove(hostId)?.dispose()
    }

    fun acceptIntent(intent: Intent) {
        val hostId = intent.getLongExtra(AgentNotifications.HOST_ID, 0L)
        val id = intent.getStringExtra(AgentNotifications.SESSION_ID) ?: return
        if (hostId > 0L) mutableTarget.value = AgentChatTarget(hostId, id)
    }

    fun consumeTarget(value: AgentChatTarget) {
        mutableTarget.compareAndSet(value, null)
    }

    companion object {
        @Volatile private var instance: AgentConnections? = null

        fun get(context: Context): AgentConnections =
            instance
                ?: synchronized(this) {
                    instance
                        ?: AgentConnections(context.applicationContext as Application).also {
                            instance = it
                        }
                }
    }
}
