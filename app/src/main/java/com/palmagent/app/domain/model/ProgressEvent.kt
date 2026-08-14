package com.palmagent.app.domain.model

sealed class ProgressEvent {
    data class Update(val round: Int, val action: String) : ProgressEvent()
    data object Idle : ProgressEvent()
    data class Paused(val reason: String) : ProgressEvent()
}
