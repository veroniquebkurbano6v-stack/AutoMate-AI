package com.palmagent.app.data.repository

import com.palmagent.app.domain.repository.AIRepository
import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.service.AIService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIRepositoryImpl @Inject constructor(
    private val aiService: AIService
) : AIRepository {

    override suspend fun generateAction(
        userRequest: String,
        screenInfo: ScreenInfo?,
        knowledgeContext: String,
        actionHistory: List<String>
    ): AgentAction? {
        return try {
            // B7 清理：AIService.generateAction 已移除 actionHistory 参数（screenInfo 仍保留用于坐标解析）
            aiService.generateAction(
                userRequest = userRequest,
                screenInfo = screenInfo,
                knowledgeContext = knowledgeContext
            )
        } catch (e: Exception) {
            null
        }
    }

    

    override fun isReady(): Boolean {
        return true
    }
}
