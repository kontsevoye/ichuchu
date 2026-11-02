package io.github.kontsevoye.ichuchu.ui.screens.agents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.kontsevoye.ichuchu.service.agents.AgentConnections
import io.github.kontsevoye.ichuchu.service.agents.DeckNativeHistory
import io.github.kontsevoye.ichuchu.service.agents.DeckSend

/** Navigation facade; an explicitly connected workspace survives leaving this screen. */
class AgentViewModel(application: Application, val hostId: Long) : AndroidViewModel(application) {
    private val connections = AgentConnections.get(application)
    private val workspace = connections.acquire(hostId)
    val state = workspace.state
    val hostKeyPrompt = workspace.hostKeyPrompt

    fun setForeground(value: Boolean) = workspace.setForeground(this, value)

    fun authenticate(request: Long, accepted: Boolean) = workspace.authenticate(request, accepted)

    fun respondToHostKey(accepted: Boolean) = workspace.respondToHostKey(accepted)

    fun clearMessage() = workspace.clearMessage()

    fun reconnect() = workspace.reconnect()

    fun disconnect() = workspace.disconnect()

    fun refresh() = workspace.refresh()

    fun openSession(id: String) = workspace.openSession(id)

    fun back() = workspace.back()

    fun loadOlder() = workspace.loadOlder()

    fun updateDraft(text: String) = workspace.updateDraft(text)

    fun send() = workspace.send()

    fun cancel(send: DeckSend) = workspace.cancel(send)

    fun recoverDraft(send: DeckSend) = workspace.recoverDraft(send)

    fun showCreate() = workspace.showCreate()

    fun updateCreate(form: AgentCreateForm) = workspace.updateCreate(form)

    fun acknowledgeCreation() = workspace.acknowledgeCreation()

    fun create() = workspace.create()

    fun enableRecall() = workspace.enableRecall()

    fun openTerminal(onOpened: () -> Unit) = workspace.openTerminal(onOpened)

    fun clearCachedHistory() = workspace.clearCachedHistory()

    fun refreshProjects() = workspace.refreshProjects()

    fun retryHistory() = workspace.retryHistory()

    fun chooseHistory() = workspace.chooseHistory()

    fun dismissHistoryChoices() = workspace.dismissHistoryChoices()

    fun linkHistory(history: DeckNativeHistory) = workspace.linkHistory(history)

    override fun onCleared() {
        workspace.setForeground(this, false)
        connections.release(hostId)
    }

    companion object {
        fun factory(application: Application, hostId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AgentViewModel(application, hostId) as T
                }
            }
    }
}
