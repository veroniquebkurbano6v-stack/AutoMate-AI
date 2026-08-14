package com.palmagent.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.palmagent.app.framework.config.AppConfig
import com.palmagent.app.framework.event.EventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class HomeUiState(
    val isServiceRunning: Boolean = false,
    val isTaskRunning: Boolean = false,
    val currentTask: String = "",
    val logMessages: List<String> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val appConfig: AppConfig,
    private val eventBus: EventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val hasLlmConfig: Boolean get() = appConfig.hasLlmConfig()

    fun updateServiceRunning(running: Boolean) {
        _uiState.value = _uiState.value.copy(isServiceRunning = running)
    }

    fun updateTaskRunning(running: Boolean, task: String = "") {
        _uiState.value = _uiState.value.copy(isTaskRunning = running, currentTask = task)
    }
}
