package com.palmagent.app.tool.impl

import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

class FinishTool : BaseTool() {
    override fun getName(): String = "finish"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("summary", "string", "已完成的操作摘要，简明描述模型为用户完成了什么", false),
        ToolParameter("next_action", "string", "用户接下来需要做什么，如'请您自行选择医生和就诊时间段'", false)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val summary = (params["summary"]?.toString() ?: "").ifBlank { "任务已完成" }
        val nextAction = (params["next_action"]?.toString() ?: "").ifBlank { "任务已完成" }
        return ToolResult.success("✅ $summary\n$nextAction")
    }

    override fun getDescriptionEN(): String =
        "Finish the current task. Must provide summary (what was done) and next_action (what user should do next). Call when task is complete to notify user."

    override fun getDescriptionCN(): String =
        "结束当前任务。必须提供summary（已完成什么）和next_action（用户接下来做什么）。任务完成时调用，通知用户操作结果和后续指引。"
}
