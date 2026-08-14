package com.palmagent.app.domain.repository

import com.palmagent.app.model.AgentAction

interface AIRepository {
    suspend fun generateAction(
        userRequest: String,
        screenInfo: com.palmagent.app.model.ScreenInfo?,
        knowledgeContext: String,
        actionHistory: List<String>
    ): AgentAction?
    fun isReady(): Boolean
}
