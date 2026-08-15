package com.palmagent.app.config

import com.palmagent.app.utils.KVUtils

/**
 * 配置常量与动态配置入口
 *
 * 重构后：KVUtils 已包含所有配置的 getter，
 * Config 仅保留纯常量，动态配置统一通过 KVUtils 获取。
 * 保留此类是为了向后兼容，新代码应直接使用 KVUtils。
 */
@Deprecated("新代码请直接使用 KVUtils 获取配置", ReplaceWith("KVUtils"))
object Config {
    @Deprecated("使用 KVUtils.getLlmApiKey()", ReplaceWith("KVUtils.getLlmApiKey()"))
    val LLM_API_KEY: String get() = KVUtils.getLlmApiKey()

    @Deprecated("使用 KVUtils.getLlmBaseUrl()", ReplaceWith("KVUtils.getLlmBaseUrl()"))
    val LLM_API_URL: String get() = KVUtils.getLlmBaseUrl()

    @Deprecated("使用 KVUtils.getLlmModelName()", ReplaceWith("KVUtils.getLlmModelName()"))
    val LLM_MODEL: String get() = KVUtils.getLlmModelName()

    const val MAX_CONTEXT_LENGTH = 4096
    const val MAX_ACTIONS_PER_TASK = 20

    @Deprecated("使用 KVUtils.getGuiOwlApiUrl()", ReplaceWith("KVUtils.getGuiOwlApiUrl()"))
    val GUI_OWL_API_URL: String get() = KVUtils.getGuiOwlApiUrl()

    @Deprecated("使用 KVUtils.getGuiOwlApiKey()", ReplaceWith("KVUtils.getGuiOwlApiKey()"))
    val GUI_OWL_API_KEY: String get() = KVUtils.getGuiOwlApiKey()

    @Deprecated("使用 KVUtils.getGuiOwlModel()", ReplaceWith("KVUtils.getGuiOwlModel()"))
    val GUI_OWL_MODEL: String get() = KVUtils.getGuiOwlModel()

    @Deprecated("使用 KVUtils.getGuiOwlConnectTimeout()", ReplaceWith("KVUtils.getGuiOwlConnectTimeout()"))
    val GUI_OWL_CONNECT_TIMEOUT: Long get() = KVUtils.getGuiOwlConnectTimeout()

    @Deprecated("使用 KVUtils.getGuiOwlReadTimeout()", ReplaceWith("KVUtils.getGuiOwlReadTimeout()"))
    val GUI_OWL_READ_TIMEOUT: Long get() = KVUtils.getGuiOwlReadTimeout()

    @Deprecated("使用 KVUtils.getGuiOwlMaxRetries()", ReplaceWith("KVUtils.getGuiOwlMaxRetries()"))
    val GUI_OWL_MAX_RETRIES: Int get() = KVUtils.getGuiOwlMaxRetries()

    @Deprecated("使用 KVUtils.getGuiOwlRetryDelayMs()", ReplaceWith("KVUtils.getGuiOwlRetryDelayMs()"))
    val GUI_OWL_RETRY_DELAY_MS: Long get() = KVUtils.getGuiOwlRetryDelayMs()
}
