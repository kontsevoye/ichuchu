package io.github.kontsevoye.ichuchu.ui.screens.Settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kontsevoye.ichuchu.service.update.UpdateServices
import kotlinx.coroutines.launch

class SettingsUpdateViewModel(application: Application) : AndroidViewModel(application) {
    val updates = UpdateServices.get(application)

    fun check() {
        viewModelScope.launch { updates.check() }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SettingsUpdateViewModel::class.java))
                    @Suppress("UNCHECKED_CAST")
                    return SettingsUpdateViewModel(application) as T
                }
            }
    }
}
