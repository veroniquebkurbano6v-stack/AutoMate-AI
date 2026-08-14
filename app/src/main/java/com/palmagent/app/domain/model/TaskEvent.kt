package com.palmagent.app.domain.model

sealed class TaskEvent {
    data class Started(val taskId: String) : TaskEvent()
    data class Completed(val taskId: String, val result: String) : TaskEvent()
    data class Failed(val taskId: String, val error: String) : TaskEvent()
    data class Cancelled(val taskId: String) : TaskEvent()
}
