package com.palmagent.app.agent

import android.util.Log
import com.palmagent.app.model.ScreenInfo

/**
 * 任务进度追踪器
 *
 * 从 DefaultAgentService 中拆分，负责：
 * - 追踪任务执行进度（是否到达桌面、是否打开目标应用等）
 * - HOME 键无效检测
 * - 生成进度上下文信息
 */
class TaskProgressTracker {

    companion object {
        private const val TAG = "TaskProgressTracker"
    }

    data class TaskProgress(
        var reachedHome: Boolean = false,
        var reachedLastRound: Int = 0,
        var lastScreenPackage: String = "",
        var lastNonHomePackage: String = "",
        var consecutiveHomeCount: Int = 0,
        var homeNotWorking: Boolean = false
    ) {
        fun reset() {
            reachedHome = false
            reachedLastRound = 0
            lastScreenPackage = ""
            lastNonHomePackage = ""
            consecutiveHomeCount = 0
            homeNotWorking = false
        }
    }

    val progress = TaskProgress()

    fun reset() = progress.reset()

    /**
     * 更新任务进度（仅追踪通用系统状态，任务级进度由 LLM 自管理）
     */
    fun track(screenInfo: ScreenInfo?, round: Int) {
        progress.reachedLastRound = round
        val pkg = screenInfo?.currentPackage ?: return

        if (pkg != progress.lastScreenPackage || round == 1) {
            progress.lastScreenPackage = pkg
        }

        if (pkg == "com.miui.home" || pkg.contains("launcher")) {
            progress.reachedHome = true
            progress.homeNotWorking = false
            progress.consecutiveHomeCount = 0
        }

        if (pkg !in listOf("com.miui.home", "com.android.systemui") && !pkg.contains("launcher")) {
            progress.lastNonHomePackage = pkg
        }
    }

    /**
     * 记录 HOME 键尝试结果
     */
    fun recordHomeAttempt(actionType: String, currentPkg: String?) {
        if (actionType != "HOME") return
        val pkg = currentPkg ?: ""
        val stillInApp = pkg.isNotBlank() &&
            pkg != "com.miui.home" && !pkg.contains("launcher") &&
            pkg != "com.android.systemui"

        if (stillInApp && progress.lastNonHomePackage == pkg) {
            progress.consecutiveHomeCount++
            if (progress.consecutiveHomeCount >= 2) {
                progress.homeNotWorking = true
                Log.w(TAG, "HOME键连续${progress.consecutiveHomeCount}次无效(pkg=$pkg)，建议使用滑动替代")
            }
        } else {
            progress.consecutiveHomeCount = 0
        }
    }

    /**
     * 构建进度上下文信息（仅通用系统状态，任务级进度由 LLM 自管理）
     */
    fun buildProgressContext(): String {
        val p = progress
        if (p.reachedLastRound <= 1) return ""

        val currentPkg = p.lastScreenPackage
        val systemPackages = listOf("com.miui.home", "com.android.systemui", "com.palmagent.app")
        val isSystemOrLauncher = systemPackages.any { currentPkg == it } || currentPkg.contains("launcher")

        val steps = mutableListOf<String>()
        if (currentPkg.isNotBlank() && !isSystemOrLauncher) {
            steps.add("📱$currentPkg")
        } else if (isSystemOrLauncher) {
            steps.add("🏠桌面")
        }

        val homeNote = if (p.homeNotWorking) "\n⚠️ HOME键无效，请用底部上滑返回桌面" else ""
        val desktopHint = if (p.reachedHome && isSystemOrLauncher) {
            "\n💡 已在桌面，请查找并打开目标应用"
        } else ""

        return "【进度】${p.reachedLastRound}轮 | ${steps.joinToString(" ")}$homeNote$desktopHint"
    }
}
