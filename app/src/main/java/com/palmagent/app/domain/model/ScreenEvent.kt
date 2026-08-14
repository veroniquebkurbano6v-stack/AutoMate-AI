package com.palmagent.app.domain.model

sealed class ScreenEvent {
    data class Captured(val packageName: String?, val elementCount: Int) : ScreenEvent()
    data class Changed(val changeDescription: String) : ScreenEvent()
    data object NoChange : ScreenEvent()
}
