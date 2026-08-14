package com.palmagent.app.model

/**
 * 统一的 Agent 错误类型，区分可重试与不可重试错误
 */
sealed class AgentError {
    abstract val message: String

    /** 网络错误，可重试 */
    data class NetworkError(override val message: String) : AgentError()

    /** API 限流，可延迟重试 */
    data class ApiLimitError(val retryAfterMs: Long, override val message: String = "API限流") : AgentError()

    /** 响应解析失败，可重试 */
    data class ParseError(val raw: String, override val message: String = "响应解析失败") : AgentError()

    /** API Key 未配置，不可重试 */
    data class ConfigError(override val message: String) : AgentError()

    /** 不可恢复的致命错误 */
    data class FatalError(override val message: String) : AgentError()

    /** 任务被取消 */
    data class Cancelled(override val message: String = "任务被取消") : AgentError()

    /** 是否可重试 */
    val isRetryable: Boolean get() = when (this) {
        is NetworkError, is ApiLimitError, is ParseError -> true
        is ConfigError, is FatalError, is Cancelled -> false
    }
}
