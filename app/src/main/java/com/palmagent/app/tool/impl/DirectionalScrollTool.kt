package com.palmagent.app.tool.impl

import android.util.Log
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import kotlinx.coroutines.delay

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * 方向滑动工具基类
 *
 * 统一处理4个方向的滑动逻辑，子类只需指定方向和描述文本。
 * 消除 ScrollDownTool/ScrollUpTool/ScrollLeftTool/ScrollRightTool 的代码重复。
 *
 * 实现策略（对齐业界主流 GUI Agent 实践）：**纯手势滑动**，不依赖无障碍原生滚动 API。
 * - 手势从屏幕中部起滑，天然不会命中顶部分类 Tab 行 / 底部导航栏（规避"滑动切标签"问题）
 * - 滑动后通过无障碍树签名对比校验是否真正生效（避免误报成功）
 * - 页面无变化视为未生效，返回失败，由执行模型感知后换策略（如已到页面边界）
 */
abstract class DirectionalScrollTool(
    private val direction: ScrollDirection
) : BaseTool() {

    companion object {
        private const val SWIPE_RATIO = 0.6f
        private const val CENTER_RATIO = 0.5f
        private const val TAG = "DirectionalScrollTool"
        /** 滑动后等待页面稳定的时长（与 ActionExecutor 的轮询间隔一致） */
        private const val STABLE_WAIT_MS = 300L
    }

    override fun getParameters(): List<ToolParameter> = when (direction) {
        ScrollDirection.UP, ScrollDirection.DOWN -> listOf(
            ToolParameter("start_y", "integer", "可选：滑动起始Y坐标，不传则自动计算", false),
            ToolParameter("start_x", "integer", "可选：滑动起始X坐标，不传则默认屏幕水平居中", false),
            ToolParameter("duration_ms", "integer", "滑动持续时间(ms)，默认300", false)
        )
        ScrollDirection.LEFT, ScrollDirection.RIGHT -> listOf(
            ToolParameter("start_x", "integer", "可选：滑动起始X坐标，不传则自动计算", false),
            ToolParameter("start_y", "integer", "可选：滑动起始Y坐标，不传则默认屏幕垂直居中", false),
            ToolParameter("duration_ms", "integer", "滑动持续时间(ms)，默认300", false)
        )
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val size = getScreenSize()
        val screenWidth = size[0]
        val screenHeight = size[1]

        if (screenWidth <= 0 || screenHeight <= 0) {
            return ToolResult.error("无法获取屏幕尺寸，滑动失败")
        }

        val isVertical = direction == ScrollDirection.UP || direction == ScrollDirection.DOWN
        val dirName = when (direction) {
            ScrollDirection.UP -> "上"; ScrollDirection.DOWN -> "下"
            ScrollDirection.LEFT -> "左"; ScrollDirection.RIGHT -> "右"
        }

        // ============ 纯手势滑动（不依赖无障碍原生滚动容器） ============
        val mainAxisSize = if (isVertical) screenHeight else screenWidth
        val crossAxisSize = if (isVertical) screenWidth else screenHeight
        val swipeDistance = (mainAxisSize * SWIPE_RATIO).toInt()
        val userDuration = optionalLong(params, "duration_ms", -1)

        // 解析主轴和交叉轴起始坐标
        // 默认起点取屏幕中部（CENTER_RATIO=0.5），不会落在顶部分类 Tab 行或底部导航栏，
        // 从机制上规避"原生滚动作用在 Tab 行导致切换标签"的问题
        val mainStart: Int
        val crossStart: Int
        if (isVertical) {
            mainStart = optionalInt(params, "start_y", -1)
                .let { if (it >= 0) it.coerceIn(0, mainAxisSize - 1) else (mainAxisSize * CENTER_RATIO).toInt() }
            crossStart = optionalInt(params, "start_x", -1)
                .let { if (it >= 0) it.coerceIn(0, crossAxisSize - 1) else (crossAxisSize * CENTER_RATIO).toInt() }
        } else {
            mainStart = optionalInt(params, "start_x", -1)
                .let { if (it >= 0) it.coerceIn(0, mainAxisSize - 1) else (mainAxisSize * CENTER_RATIO).toInt() }
            crossStart = optionalInt(params, "start_y", -1)
                .let { if (it >= 0) it.coerceIn(0, crossAxisSize - 1) else (crossAxisSize * CENTER_RATIO).toInt() }
        }

        // 计算滑动方向符号
        // Android 触摸滑动：手指方向与内容滚动方向相反
        // scroll_down = 看下方内容 = 手指向上滑 = endY < startY → sign=-1
        // scroll_up = 看上方内容 = 手指向下滑 = endY > startY → sign=+1
        val sign = if (direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT) -1 else 1
        val mainEnd = (mainStart + swipeDistance * sign).coerceIn(0, mainAxisSize - 1)

        // 映射到屏幕坐标
        val startX: Int
        val startY: Int
        val endX: Int
        val endY: Int
        if (isVertical) {
            startX = crossStart
            startY = mainStart
            endX = crossStart
            endY = mainEnd
        } else {
            startX = mainStart
            startY = crossStart
            endX = mainEnd
            endY = crossStart
        }

        // 动态计算 duration（用户未指定时根据距离计算）
        val duration = if (userDuration > 0) userDuration else calculateDuration(startX, startY, endX, endY)

        // 滑动前记录无障碍树签名，用于校验滚动是否真正生效
        val service = getA11yService()
        val beforeSig = service?.getTreeSignature()

        val result = performSwipe(startX, startY, endX, endY, duration)
        if (!result.isSuccess) {
            return result
        }

        // 等页面稳定后校验：签名变化 = 真的滚动了；无变化 = 已到边界/未生效
        delay(STABLE_WAIT_MS)
        val afterSig = service?.getTreeSignature()
        if (afterSig != null && beforeSig != null && afterSig == beforeSig) {
            Log.w(TAG, "滑动后页面签名无变化，滚动可能未生效（已到页面边界？）")
            return ToolResult.error(
                "向${dirName}滑动后页面无变化，可能已到页面边界或滑动未生效",
                errorType = "VALIDATION",
                failureCategory = "SCROLL_NO_EFFECT",
                code = "SCROLL_NO_EFFECT",
                suggestion = "已到页面边界或内容不可滚动，请尝试返回、换入口或改用其他操作，不要重复相同滑动"
            )
        }

        return ToolResult.success("向${dirName}滑动完成 ($startX,$startY) → ($endX,$endY)，屏幕尺寸: ${screenWidth}x${screenHeight}")
    }

    /**
     * 根据滑动距离动态计算 duration（模拟真实手指：距离越远，时间越长）
     */
    private fun calculateDuration(startX: Int, startY: Int, endX: Int, endY: Int): Long {
        val dx = (endX - startX).toDouble()
        val dy = (endY - startY).toDouble()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        return when {
            distance < 200 -> 200L   // 短距离快速
            distance < 500 -> 300L   // 中距离标准
            distance < 1000 -> 400L  // 长距离慢速
            else -> 500L
        }
    }
}
