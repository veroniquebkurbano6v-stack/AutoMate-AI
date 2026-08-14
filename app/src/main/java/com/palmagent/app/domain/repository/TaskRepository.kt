package com.palmagent.app.domain.repository

interface TaskRepository {
    suspend fun startTask(prompt: String, callback: com.palmagent.app.agent.AgentCallback): Boolean
    suspend fun cancelTask()
    fun isRunning(): Boolean
}
