package com.palmagent.app.agent

interface AgentService {
    val isRunning: Boolean
    fun initialize(config: AgentConfig)
    fun updateConfig(config: AgentConfig)

    /**
     * 执行任务
     * @param userPrompt 用户需求（复杂模式下为决策模型复述的需求，不含 plan）
     * @param plan 决策模型生成的结构化计划（复杂模式传入；简单模式/渠道为空）。注入执行模型【决策模型任务计划】区域
     * @param callback 回调
     */
    suspend fun executeTask(userPrompt: String, callback: AgentCallback, plan: Plan? = null)
    fun cancel()
    fun shutdown()
}