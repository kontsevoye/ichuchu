package io.github.kontsevoye.ichuchu.ui.screens.ServerList

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.data.repository.HostRepository
import io.github.kontsevoye.ichuchu.model.HostProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServerListViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val hostRepository = HostRepository(db.hostProfileDao())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }

    private val searchQuery = MutableStateFlow("")

    val allHosts: StateFlow<List<HostProfile>> =
        hostRepository
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hosts: StateFlow<List<HostProfile>> =
        allHosts
            .combine(searchQuery) { hosts, query ->
                val trimmed = query.trim()
                if (trimmed.isEmpty()) return@combine hosts
                hosts.filter { host ->
                    host.name.contains(trimmed, ignoreCase = true) ||
                        host.host.contains(trimmed, ignoreCase = true) ||
                        host.username.contains(trimmed, ignoreCase = true)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateSearchQuery(value: String) {
        searchQuery.value = value
    }

    suspend fun reorderHosts(ids: List<Long>): Boolean =
        try {
            hostRepository.reorder(ids)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not save host order"
            false
        }

    fun deleteServer(id: Long) {
        viewModelScope.launch {
            val host = hostRepository.getById(id) ?: return@launch
            try {
                hostRepository.delete(host)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    val search: StateFlow<String> =
        searchQuery.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ServerListViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ServerListViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
