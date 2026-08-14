package com.palmagent.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palmagent.app.domain.model.TaskState
import com.palmagent.app.domain.repository.TaskRepository
import com.palmagent.app.framework.event.EventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaskViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val eventBus: EventBus
) : ViewModel() {

    private val _taskState = MutableStateFlow<TaskState>(TaskState.Idle)
    val taskState: StateFlow<TaskState> = _taskState.asStateFlow()

    fun startTask(prompt: String) {
        viewModelScope.launch {
            _taskState.value = TaskState.Loading
            try {
                val callback = object : com.palmagent.app.agent.AgentCallback {
                    override fun onLoopStart(round: Int) {}
                    override fun onContent(round: Int, content: String) {}
                    override fun onToolCall(round: Int, toolId: String, toolName: String, displayName: String, parameters: String) {}
                    override fun onToolResult(round: Int, toolId: String, toolName: String, displayName: String, parameters: String, result: com.palmagent.app.tool.ToolResult) {
                        _taskState.value = TaskState.Running(
                            round = round,
                            action = toolName,
                            progress = 0
                        )
                    }
                    override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                        _taskState.value = TaskState.Completed(finalAnswer)
                    }
                    override fun onError(round: Int, error: Exception, totalTokens: Int) {
                        _taskState.value = TaskState.Error(error.message ?: "未知错误")
                    }
                }
                val success = taskRepository.startTask(prompt, callback)
                if (!success) {
                    _taskState.value = TaskState.Error("任务启动失败")
                }
            } catch (e: Exception) {
                _taskState.value = TaskState.Error(e.message ?: "未知错误")
            }
        }
    }

    fun cancelTask() {
        viewModelScope.launch {
            taskRepository.cancelTask()
            _taskState.value = TaskState.Idle
        }
    }

    fun resetState() {
        _taskState.value = TaskState.Idle
    }
}
