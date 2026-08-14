package com.palmagent.app.tool.impl

import kotlin.math.min
import kotlin.random.Random

/**
 * 重试策略
 *
 * 综合LangGraph、AutoGen、Prefect实践，针对Android自动化场景调优：
 * - maxAttempts: 3（含首次），LangGraph/AutoGen 共识
 * - initialIntervalMs: 500ms，Android UI 动画通常 300ms 内完成
 * - backoffFactor: 2.0，LangGraph 默认值
 * - maxIntervalMs: 8000ms，避免单步重试阻塞过久
 * - jitter: true，防止多设备并发重试同步
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialIntervalMs: Long = 500,
    val backoffFactor: Double = 2.0,
    val maxIntervalMs: Long = 8000,
    val jitter: Boolean = true
) {
    /**
     * 计算第 attempt 次重试的退避时间（attempt 从 1 开始，表示首次重试）
     *
     * 公式：delay = min(maxIntervalMs, initialIntervalMs * backoffFactor^(attempt-1))
     *       若 jitter=true，叠加 random(0, delay * 0.3)
     */
    fun computeBackoff(attempt: Int): Long {
        val baseDelay = initialIntervalMs * Math.pow(backoffFactor, (attempt - 1).toDouble())
        val cappedDelay = min(maxIntervalMs, baseDelay.toLong())
        return if (jitter) {
            val jitterAmount = (cappedDelay * 0.3).toLong()
            cappedDelay + Random.nextLong(0, jitterAmount + 1)
        } else {
            cappedDelay
        }
    }
}

/**
 * 步骤上下文：负责步骤间数据传递与坐标管理
 *
 * 借鉴 Airflow XComs + LangGraph Checkpointer：
 * - 步骤输出注册表：stepIndex -> 输出数据
 * - 最近一次定位坐标（兼容现有 x="prev" 机制）
 * - 已完成步骤数（用于崩溃恢复）
 */
class StepContext {
    /** 步骤输出注册表：stepIndex -> 输出数据 */
    private val outputs = mutableMapOf<Int, Map<String, Any>>()

    /** 最近一次定位坐标（兼容现有 x="prev" 机制） */
    var lastGroundX: Int? = null
        private set

    var lastGroundY: Int? = null
        private set

    /** 已完成步骤数（用于崩溃恢复） */
    val completedStepCount: Int get() = outputs.size

    /**
     * 记录步骤输出
     */
    fun recordOutput(stepIndex: Int, result: com.palmagent.app.tool.ToolResult) {
        val meta = result.metadata
        if (meta.isNotEmpty()) outputs[stepIndex] = meta

        // 捕获定位坐标（兼容现有 x="prev" 机制）
        result.coordinate?.let { (x, y) ->
            lastGroundX = x
            lastGroundY = y
        }
    }

    /**
     * 获取指定步骤的输出
     */
    fun getOutput(stepIndex: Int, key: String): Any? = outputs[stepIndex]?.get(key)

    /**
     * 获取指定步骤的全部输出
     */
    fun getOutput(stepIndex: Int): Map<String, Any>? = outputs[stepIndex]
}
