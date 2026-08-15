package com.palmagent.app.agent

data class AgentConfig(
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val userPrompt: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxIterations: Int = 60,
    val temperature: Double = 0.1,
    val streaming: Boolean = false,
    val contextMaxTokens: Int = 8000,
    val contextCompactionThreshold: Double = 0.75,
    val contextKeepRecentRounds: Int = 4
) {
    companion object {
        // 系统提示词统一由 PromptBuilder.getSystemPrompt() 管理，此处保留空字符串避免重复维护
        const val DEFAULT_SYSTEM_PROMPT = ""
    }

    class Builder {

        private var apiKey: String = ""
        private var baseUrl: String = ""
        private var modelName: String = ""
        private var userPrompt: String = ""
        private var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
        private var maxIterations: Int = 60
        private var temperature: Double = 0.1
        private var streaming: Boolean = false
        private var contextMaxTokens: Int = 8000
        private var contextCompactionThreshold: Double = 0.75
        private var contextKeepRecentRounds: Int = 4

        fun apiKey(apiKey: String) = apply { this.apiKey = apiKey }
        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }
        fun modelName(modelName: String) = apply { this.modelName = modelName }
        fun userPrompt(userPrompt: String) = apply { this.userPrompt = userPrompt }
        fun systemPrompt(systemPrompt: String) = apply { this.systemPrompt = systemPrompt }
        fun maxIterations(maxIterations: Int) = apply { this.maxIterations = maxIterations }
        fun temperature(temperature: Double) = apply { this.temperature = temperature }
        fun streaming(streaming: Boolean) = apply { this.streaming = streaming }
        fun contextMaxTokens(contextMaxTokens: Int) = apply { this.contextMaxTokens = contextMaxTokens }
        fun contextCompactionThreshold(contextCompactionThreshold: Double) = apply { this.contextCompactionThreshold = contextCompactionThreshold }
        fun contextKeepRecentRounds(contextKeepRecentRounds: Int) = apply { this.contextKeepRecentRounds = contextKeepRecentRounds }

        fun build(): AgentConfig {
            return AgentConfig(apiKey, baseUrl, modelName, userPrompt, systemPrompt, maxIterations, temperature, streaming, contextMaxTokens, contextCompactionThreshold, contextKeepRecentRounds)
        }
    }
}