package com.palmagent.app.tool.impl

import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

class SwipeTool : BaseTool() {

    override fun getName(): String = "swipe"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("start_x", "integer", "Swipe start X coordinate", true),
        ToolParameter("start_y", "integer", "Swipe start Y coordinate", true),
        ToolParameter("end_x", "integer", "Swipe end X coordinate", true),
        ToolParameter("end_y", "integer", "Swipe end Y coordinate", true),
        ToolParameter("duration_ms", "integer", "Swipe duration in ms (default 300)", false)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val startX = requireInt(params, "start_x")
        val startY = requireInt(params, "start_y")
        val endX = requireInt(params, "end_x")
        val endY = requireInt(params, "end_y")
        val duration = optionalLong(params, "duration_ms", 300)

        validateCoordinates(startX, startY)?.let { return ToolResult.error("start: $it") }
        validateCoordinates(endX, endY)?.let { return ToolResult.error("end: $it") }

        return performSwipe(startX, startY, endX, endY, duration)
    }

    override fun getDescriptionEN(): String =
        "Perform a swipe gesture from (start_x, start_y) to (end_x, end_y)."

    override fun getDescriptionCN(): String =
        "滑动屏幕，参数 start_x/start_y/end_x/end_y/duration_ms。用于：浏览列表(上滑)、切换标签页(左/右滑)、全面屏手势返回(start_x=0,end_x=屏幕宽度的15%)。"
}