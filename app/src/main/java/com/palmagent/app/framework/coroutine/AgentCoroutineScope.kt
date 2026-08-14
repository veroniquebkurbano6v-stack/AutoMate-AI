package com.palmagent.app.framework.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentCoroutineScope @Inject constructor(
    dispatcherProvider: CoroutineDispatcherProvider
) : CoroutineScope by CoroutineScope(
    dispatcherProvider.default + SupervisorJob()
) {
    fun cancelAll() {
        coroutineContext.cancelChildren()
    }
}
