package com.palmagent.app.domain.model

sealed class TaskState {
    data object Idle : TaskState()
    data object Loading : TaskState()
    data class Running(val round: Int, val action: String, val progress: Int) : TaskState()
    data class Completed(val result: String) : TaskState()
    data class Error(val message: String) : TaskState()
}
