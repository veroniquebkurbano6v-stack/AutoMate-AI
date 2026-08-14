package com.palmagent.app.agent

import android.util.Log
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.ScreenChangeDetector
import com.palmagent.app.utils.KVUtils
import com.palmagent.app.utils.recycleSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 智能等待策略
 *
 * 从 DefaultAgentService 中拆分，负责：
 * - 操作后等待页面稳定
 * - 检测加载状态
 * - 检测无障碍树变化
 * - 无障碍树为空时回退到图像哈希检测
 *
 * 优化项（基于屏幕变化检测调研报告）：
 * - MAX_DURATION_MS 可配置（通过 KVUtils）
 * - 无障碍树持续为空时回退 pHash 检测
 * - 输出等待耗时日志
 */
class SmartWaitStrategy {

    companion object {
        private const val TAG = "SmartWaitStrategy"
        private const val POLL_INTERVAL_MS = 200L
        private const val DEFAULT_MAX_DURATION_MS = 8000L
        private const val STABLE_COUNT = 2
        // 事件流静默窗口：距最后一次无障碍事件超过该时长即认为页面渲染/动画已收敛
        private const val SILENT_WINDOW_MS = 700L
    }

    /** 取消检查回调，由 DefaultAgentService 注入 */
    var isCancelled: () -> Boolean = { false }

    /**
     * 智能等待页面稳定
     *
     * 稳定判定以【无障碍事件流静默】为主信号：轮询期间距最后一次无障碍事件
     * （TYPE_WINDOW_CONTENT_CHANGED 等）持续超过 SILENT_WINDOW_MS 且连续 STABLE_COUNT
     * 次满足，即认为页面已稳定——事件流静默意味着渲染/动画已收敛，比"元素数量不变"
     * 更可靠（元素数不变时页面可能仍在动画/加载中）。
     * 保留 loading 关键词检测（命中重置静默计数）；无障碍树持续为空时回退到图像哈希检测。
     */
    suspend fun waitForPageStable() {
        val maxDurationMs = KVUtils.getSmartWaitTimeoutMs()
        val startTime = System.currentTimeMillis()
        var silentCount = 0
        var emptyTreeCount = 0

        // 图像哈希回退：当无障碍树持续为空时使用
        var lastImageHash = ""
        var imageHashStableCount = 0

        while (System.currentTimeMillis() - startTime < maxDurationMs) {
            if (isCancelled()) {
                Log.d(TAG, "智能等待: 检测到取消信号，提前退出")
                return
            }
            delay(POLL_INTERVAL_MS)
            val currentInfo = withContext(Dispatchers.Main) {
                GUIAccessibilityService.instance?.getCurrentScreenInfo()
            }
            val currentCount = currentInfo?.uiElements?.size ?: 0

            if (currentCount == 0) {
                emptyTreeCount++
                if (emptyTreeCount >= 3) {
                    // 无障碍树持续为空，回退到图像哈希检测
                    val currentHash = withContext(Dispatchers.IO) {
                        val bmp = GUIAccessibilityService.instance?.takeScreenshot()
                        if (bmp != null) {
                            // P1-4 修复：try-finally 确保 Bitmap 在异常路径下也回收
                            try {
                                ScreenChangeDetector.computePerceptualHashPublic(bmp)
                            } finally {
                                bmp.recycleSafely()
                            }
                        } else ""
                    }
                    if (currentHash.isNotBlank()) {
                        if (currentHash == lastImageHash && lastImageHash.isNotBlank()) {
                            imageHashStableCount++
                            if (imageHashStableCount >= STABLE_COUNT) {
                                val waited = System.currentTimeMillis() - startTime
                                Log.d(TAG, "智能等待[pHash回退]: 页面已稳定 (${waited}ms)")
                                return
                            }
                        } else {
                            imageHashStableCount = 0
                            lastImageHash = currentHash
                        }
                    }
                    // 继续轮询，不提前退出
                    continue
                }
            } else {
                emptyTreeCount = 0
            }

            val texts = currentInfo?.uiElements?.mapNotNull { it.text ?: it.contentDescription } ?: emptyList()
            val isLoading = texts.any {
                it.contains("正在加载") || it.contains("加载中") || it.contains("loading", ignoreCase = true) ||
                it.contains("正在缓冲") || it.contains("请稍候")
            }

            if (isLoading) {
                silentCount = 0
                continue
            }

            // 事件流静默判定：距最后一次无障碍事件 >= SILENT_WINDOW_MS 视为本采样点静默
            val lastEventTime = GUIAccessibilityService.instance?.getLastAccessibilityEventTime() ?: 0L
            val eventIdleMs = if (lastEventTime > 0) {
                System.currentTimeMillis() - lastEventTime
            } else {
                // 尚未收到任何事件：保守处理，不视为静默，靠超时或 pHash 兜底
                -1L
            }

            if (eventIdleMs >= SILENT_WINDOW_MS) {
                silentCount++
                if (silentCount >= STABLE_COUNT) {
                    val waited = System.currentTimeMillis() - startTime
                    if (waited > 300) {
                        Log.d(TAG, "智能等待[事件流静默]: 页面已稳定 (距最后事件 ${eventIdleMs}ms, ${waited}ms)")
                    }
                    return
                }
            } else {
                silentCount = 0
            }
        }

        val waited = System.currentTimeMillis() - startTime
        Log.d(TAG, "智能等待: 超时退出 (${waited}ms, max=${maxDurationMs}ms)")
    }
}
