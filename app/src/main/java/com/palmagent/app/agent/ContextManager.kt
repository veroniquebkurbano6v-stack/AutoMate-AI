package com.palmagent.app.agent

import android.util.Log
import com.palmagent.app.model.ActionRecord

object ContextManager {

    private const val TAG = "ContextManager"
    private const val SAFETY_MARGIN = 1.2

    data class AssembledContext(
        val text: String,
        val estimatedTokens: Int
    )

    fun reset() {
        // 已移除压缩摘要状态，保留 reset 以兼容旧调用方
    }

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0.0
        for (ch in text) {
            tokens += when {
                ch.code <= 127 -> 0.25
                ch in '\u4e00'..'\u9fff' -> 1.6
                else -> 0.8
            }
        }
        return (tokens * SAFETY_MARGIN).toInt().coerceAtLeast(1)
    }

    fun estimateTokensSafe(text: String): Int {
        return estimateTokens(text).coerceAtMost(Int.MAX_VALUE / 2)
    }

    suspend fun assemble(
        deviceCtx: String,
        screenOcrText: String,
        actionHistory: List<ActionRecord>,
        isTreeEmpty: Boolean,
        waitConsecutiveCount: Int,
        maxTokens: Int,
        keepRecentRounds: Int
    ): AssembledContext {
        return buildAssembledContext(
            deviceCtx = deviceCtx,
            screenOcrText = screenOcrText,
            actionHistory = actionHistory,
            isTreeEmpty = isTreeEmpty,
            waitConsecutiveCount = waitConsecutiveCount,
            maxTokens = maxTokens,
            keepRecentRounds = keepRecentRounds
        )
    }

    private fun buildAssembledContext(
        deviceCtx: String,
        screenOcrText: String,
        actionHistory: List<ActionRecord>,
        isTreeEmpty: Boolean,
        waitConsecutiveCount: Int,
        maxTokens: Int,
        keepRecentRounds: Int
    ): AssembledContext {
        val budget = (maxTokens * 0.85).toInt()

        val fixedPortion = buildString {
            appendLine(deviceCtx)
        }
        var usedTokens = estimateTokens(fixedPortion)

        // P1-5 问题②修复：clamp 防负数导致 takeLast 崩溃
        val recentHistory = actionHistory.takeLast(keepRecentRounds.coerceAtLeast(0))
        var historyPortion = buildActionHistoryText(recentHistory)

        // P1-5 问题③修复：historyPortion 计入预算，超限时从最旧的开始丢弃
        val historyTokens = estimateTokens(historyPortion)
        val historyBudget = (budget - usedTokens) * 0.4
        if (historyTokens > historyBudget && recentHistory.size > 1) {
            val dropCount = (recentHistory.size * (1 - historyBudget / historyTokens)).toInt()
                .coerceIn(1, recentHistory.size - 1)
            val trimmedHistory = recentHistory.drop(dropCount)
            historyPortion = buildActionHistoryText(trimmedHistory)
            Log.d(TAG, "历史裁剪: 丢弃前 $dropCount 轮，剩余 ${trimmedHistory.size}/${recentHistory.size}")
        }
        usedTokens += estimateTokens(historyPortion)

        val strategyPortion = buildString {
            // P1-5 问题①修复：isTreeEmpty 输出告警，让模型感知无障碍树为空
            if (isTreeEmpty) {
                appendLine("- ⚠️ 无障碍树为空，屏幕信息可能不可靠")
            }
            if (screenOcrText.isNotBlank()) {
                val a11yBudget = ((budget - usedTokens) * 0.3).toInt().coerceAtLeast(100)
                val trimmedA11y = if (estimateTokens(screenOcrText) > a11yBudget) {
                    screenOcrText.take(a11yBudget * 2)
                } else screenOcrText
                if (trimmedA11y.isNotBlank()) {
                    appendLine(trimmedA11y)
                }
            }
            appendLine()
            if (waitConsecutiveCount >= 5) {
                appendLine("- 🚨 已连续${waitConsecutiveCount}轮WAIT，界面可能无变化")
            }
        }

        val finalContext = buildString {
            append(fixedPortion)
            appendLine("【最近操作回顾】")
            append(historyPortion)
            appendLine()
            append(strategyPortion)
        }

        val totalTokens = estimateTokens(finalContext)
        Log.d(TAG, "上下文组装完成: ~${totalTokens}/${budget} tokens (预算${maxTokens})")

        return AssembledContext(
            text = finalContext,
            estimatedTokens = totalTokens
        )
    }

    private fun buildActionHistoryText(history: List<ActionRecord>): String {
        if (history.isEmpty()) return ""
        return buildString {
            history.forEach { record ->
                val status = if (record.success) "✓" else "✗"

                append("  第${record.round}轮: $status ${record.description}")
                if (record.screenChange != null && record.screenChange.isNotBlank()) {
                    append(" → [变化: ${record.screenChange}]")
                }
                append(" → ${record.resultSummary}")

                if (record.executionTimeMs > 0) {
                    append(" (${record.executionTimeMs}ms)")
                }
                appendLine()
            }
        }
    }
}
