package com.palmagent.app.agent

/**
 * 决策模型输出的结构化任务计划。
 * 替代旧的 plan: String，避免长文本字段的 JSON 转义问题。
 */
data class Plan(
    val requirement: String = "",
    val goal: String = "",
    val steps: List<PlanStep> = emptyList()
)

/**
 * 单个执行步骤。
 * supervised=true 的步骤执行前需用户确认（激活原 task_classification 设计）。
 */
data class PlanStep(
    val order: Int = 0,
    val goal: String = "",
    val successCriteria: String = "",
    val supervised: Boolean = false
)
