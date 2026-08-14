package com.palmagent.app.domain.usecase

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动作追踪 Use Case
 *
 * 追踪动作签名，检测重复操作并生成警告提示执行模型换策略。
 * v9.1: 移除熔断器硬终止，仅保留警告机制（模型自行调整策略）
 */
@Singleton
class ActionTrackingUseCase @Inject constructor() {

    companion object {
        // P1-2：2 → 4（对齐 page-agent loop detection：连续 4 次相同动作才判定循环，
        // 2-3 次可能是有意重试（如重试一次失败的点击），4 次以上才是真正的死循环）
        private const val MAX_IDENTICAL_ACTION_BEFORE_WARN = 4
    }

    private var consecutiveSameActionCount = 0
    private var lastActionSignature: String = ""

    /** 动作追踪结果 */
    data class TrackingResult(
        val shouldWarn: Boolean,
        val warningMessage: String
    )

    /**
     * 生成动作签名（用于去重和追踪）
     * v9: 加入 screenPackage 防止不同应用中相同坐标点击被误判为重复
     */
    fun actionSignature(actionType: String, params: Map<String, Any?>, screenPackage: String? = null): String {
        val keyParams = params.entries
            .filter { it.key in listOf("x", "y", "start_x", "start_y", "text", "package_name", "query") }
            .joinToString(",") { "${it.key}=${it.value}" }
        return "$actionType($keyParams)@$screenPackage"
    }

    /**
     * 更新动作追踪状态，返回追踪结果
     * P2-8 修复：加 @Synchronized 防止并发调用导致状态不一致
     */
    @Synchronized
    fun track(actionSig: String): TrackingResult {
        // WAIT 是合法的"等待加载"动作，不计入重复检测（不更新签名、不累加计数）
        if (actionSig.startsWith("WAIT(")) {
            return TrackingResult(shouldWarn = false, warningMessage = "")
        }

        if (actionSig == lastActionSignature) {
            consecutiveSameActionCount++
        } else {
            consecutiveSameActionCount = 1
            lastActionSignature = actionSig
        }

        val shouldWarn = consecutiveSameActionCount >= MAX_IDENTICAL_ACTION_BEFORE_WARN
                && lastActionSignature.isNotBlank()
        val warningMessage = if (shouldWarn) {
            "‼️ 警告：你已连续执行 $consecutiveSameActionCount 次相同操作（$lastActionSignature），屏幕无进展。" +
                    "请立即换完全不同的策略（如直接点击列表中的具体元素、返回上一页、改用其他工具），" +
                    "不要重复相同操作；若需用户参与（人脸/密码等）用REQUEST_USER_ACTION。"
        } else ""

        return TrackingResult(
            shouldWarn = shouldWarn,
            warningMessage = warningMessage
        )
    }

    /**
     * 获取当前状态警告（不更新追踪状态）
     */
    @Synchronized
    fun getCurrentWarning(): String {
        if (consecutiveSameActionCount >= MAX_IDENTICAL_ACTION_BEFORE_WARN && lastActionSignature.isNotBlank()) {
            return "‼️ 警告：你已连续执行 $consecutiveSameActionCount 次相同操作（$lastActionSignature），屏幕无进展。" +
                    "这是死循环信号，请立即换完全不同的策略，例如：\n" +
                    "1. 直接点击列表中的具体元素（店铺/条目），而不是重复筛选/搜索/点标签\n" +
                    "2. 返回上一页或换一个入口重新开始\n" +
                    "3. 需要用户参与（人脸/密码）→ 立即用REQUEST_USER_ACTION\n" +
                    "禁止重复相同操作。"
        }
        return ""
    }

    /**
     * 重置追踪状态（新任务开始时调用）
     */
    @Synchronized
    fun reset() {
        consecutiveSameActionCount = 0
        lastActionSignature = ""
    }
}
