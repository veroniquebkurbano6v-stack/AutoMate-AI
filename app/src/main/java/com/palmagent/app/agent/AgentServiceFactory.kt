package com.palmagent.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent 服务工厂
 *
 * 重构后由 Hilt 负责依赖注入，工厂仅作为获取 AgentService 的入口。
 * 所有依赖（AIService、ScreenDescriptor、ActionExecutor 等）均由 Hilt 提供。
 */
@Singleton
class AgentServiceFactory @Inject constructor(
    private val defaultAgentService: DefaultAgentService
) {

    fun create(): AgentService = defaultAgentService
}
