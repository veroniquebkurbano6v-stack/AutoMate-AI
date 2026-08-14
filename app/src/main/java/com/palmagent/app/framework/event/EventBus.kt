package com.palmagent.app.framework.event

import com.palmagent.app.domain.model.ProgressEvent
import com.palmagent.app.domain.model.ScreenEvent
import com.palmagent.app.domain.model.TaskEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventBus @Inject constructor() {
    private val _logEvents = MutableSharedFlow<String>(replay = 100)
    val logEvents: SharedFlow<String> = _logEvents.asSharedFlow()

    private val _progressEvents = MutableSharedFlow<ProgressEvent>()
    val progressEvents: SharedFlow<ProgressEvent> = _progressEvents.asSharedFlow()

    private val _taskEvents = MutableSharedFlow<TaskEvent>()
    val taskEvents: SharedFlow<TaskEvent> = _taskEvents.asSharedFlow()

    private val _screenEvents = MutableSharedFlow<ScreenEvent>()
    val screenEvents: SharedFlow<ScreenEvent> = _screenEvents.asSharedFlow()

    suspend fun emitLog(message: String) {
        _logEvents.emit(message)
    }

    suspend fun emitProgress(event: ProgressEvent) {
        _progressEvents.emit(event)
    }

    suspend fun emitTaskEvent(event: TaskEvent) {
        _taskEvents.emit(event)
    }

    suspend fun emitScreenEvent(event: ScreenEvent) {
        _screenEvents.emit(event)
    }
}
