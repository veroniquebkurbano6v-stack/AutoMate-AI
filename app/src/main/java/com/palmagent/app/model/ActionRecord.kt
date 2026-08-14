package com.palmagent.app.model

/**
 * 操作记录，独立于 DefaultAgentService，供 ContextManager 等模块使用
 */
data class ActionRecord(
    val round: Int,
    val actionType: String,
    val params: Map<String, Any?>,
    val description: String,
    val screenPackage: String?,
    val success: Boolean,
    val resultSummary: String,
    val screenChange: String? = null,
    val executionTimeMs: Long = 0,
    // 本轮执行模型填写的 visual_question（下轮屏幕描述按需回答；空则仅结构描述）
    val visualQuestion: String? = null
)
