package com.palmagent.app.tool.impl

import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

class BackTool : BaseTool() {
    override fun getName(): String = "back"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val service = getA11yService()
        if (service != null) {
            val ok = service.performAccessibilityBack()
            return if (ok) ToolResult.success("已按返回键")
            else ToolResult.error(
                "返回操作失败",
                errorType = "TRANSIENT",
                code = "BACK_CANCELLED",
                suggestion = "返回操作被系统取消，可重试"
            )
        }
        return ToolResult.error(
            "无障碍服务未运行",
            errorType = "FATAL",
            failureCategory = "SERVICE_UNAVAILABLE",
            code = "A11Y_SERVICE_UNAVAILABLE",
            suggestion = "无障碍服务未运行，需用户开启服务"
        )
    }

    override fun getDescriptionEN(): String =
        "Press the system Back button."

    override fun getDescriptionCN(): String =
        "执行系统返回键。用于：返回上一页/退出弹窗/退出应用。比点击坐标更可靠，无障碍无节点时优先使用。"
}