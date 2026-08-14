package com.palmagent.app.data.repository

import com.palmagent.app.AppCoordinator
import com.palmagent.app.TaskOrchestrator
import com.palmagent.app.agent.AgentCallback
import com.palmagent.app.channel.Channel
import com.palmagent.app.domain.repository.TaskRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val appCoordinator: AppCoordinator
) : TaskRepository {

    override suspend fun startTask(prompt: String, callback: AgentCallback): Boolean {
        return try {
            val channel = appCoordinator.taskOrchestrator.activeChannel ?: Channel.WECHAT
            val messageID = java.util.UUID.randomUUID().toString()
            appCoordinator.startNewTask(channel, prompt, messageID)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun cancelTask() {
        appCoordinator.cancelCurrentTask()
    }

    override fun isRunning(): Boolean {
        return appCoordinator.isTaskRunning()
    }
}
