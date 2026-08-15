package com.palmagent.app.framework.config

import com.palmagent.app.utils.KVUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfig @Inject constructor() {
    // LLM 配置
    val llmApiKey: String get() = KVUtils.getLlmApiKey()
    val llmBaseUrl: String get() = KVUtils.getLlmBaseUrl()
    val llmModelName: String get() = KVUtils.getLlmModelName()
    val vlmModelName: String get() = KVUtils.getVlmModelName()

    // GUI-Plus 配置
    val guiOwlApiUrl: String get() = KVUtils.getGuiOwlApiUrl()

    // 决策模型配置
    val plannerApiKey: String get() = KVUtils.getPlannerApiKey()
    val plannerApiUrl: String get() = KVUtils.getPlannerApiUrl()
    val plannerModel: String get() = KVUtils.getPlannerModel()
    // isPlannerSearchEnabled 已删除 — 联网搜索由 web_search 工具提供（与执行模型统一）

    // 任务配置
    val maxIterations: Int get() = KVUtils.getInt("max_iterations", 60)
    val contextMaxTokens: Int get() = KVUtils.getInt("context_max_tokens", 8192)

    // 屏幕检测配置
    val screenChangeThreshold: Float get() = KVUtils.getFloat("screen_change_threshold", 0.05f)
    val waitTimeoutMs: Long get() = KVUtils.getLong("wait_timeout_ms", 5000L)

    fun hasLlmConfig(): Boolean = KVUtils.hasLlmConfig()
    fun hasPlannerConfig(): Boolean = KVUtils.hasPlannerConfig()
}
