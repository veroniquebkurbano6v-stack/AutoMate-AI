package com.palmagent.app.agent

/**
 * 把 Plan 对象格式化为执行模型可读的文本。
 * 格式由代码控制，不依赖模型输出转义。
 */
object PlanFormatter {

    /** 格式化为执行模型 prompt 中的【决策模型任务计划】区域文本 */
    fun format(plan: Plan): String = buildString {
        if (plan.requirement.isNotBlank()) {
            appendLine("需求：${plan.requirement}")
            appendLine()
        }
        if (plan.goal.isNotBlank()) {
            appendLine("目标：${plan.goal}")
            appendLine()
        }
        if (plan.steps.isEmpty()) {
            appendLine("（无具体步骤，直接执行用户请求）")
        } else {
            plan.steps.forEach { step ->
                appendLine("步骤${step.order}：${step.goal}")
                appendLine("完成标志：${step.successCriteria}")
                if (step.supervised) {
                    appendLine("（此步骤需用户确认后执行）")
                }
                appendLine()
            }
        }
    }

    /** 提取 user_summary 兜底值（从 Plan.goal） */
    fun extractSummary(plan: Plan): String {
        return plan.goal.takeIf { it.isNotBlank() }?.take(50) ?: "任务执行中"
    }

    /** 格式化为日志/持久化用的紧凑文本 */
    fun formatForLog(plan: Plan): String {
        return "需求=${plan.requirement.take(40)} 目标=${plan.goal.take(40)} 步骤数=${plan.steps.size}"
    }
}
