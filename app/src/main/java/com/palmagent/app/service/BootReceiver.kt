package com.palmagent.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 开机广播：启动前台服务 + 延迟重试恢复无障碍服务。
 *
 * 无障碍服务在 force-stop / 厂商省电策略后会从系统列表移除，重启后需重新写入。
 * 系统开机后 ServiceManager 就绪需要一点时间，因此首次写入失败后按 5s/30s 递进重试（最多 3 次），
 * 兜底由 KeepAliveJobService（JobScheduler, setPersisted）周期检查。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val MAX_ATTEMPTS = 3
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, starting ForegroundService")
            ForegroundService.start(context)
            // 前台服务已保活进程，postDelayed 可靠执行
            retryRestoreAccessibility(context, attempt = 0)
        }
    }

    private fun retryRestoreAccessibility(context: Context, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS) return
        // 已运行则无需恢复
        if (GUIAccessibilityService.isRunning) return
        // 无 WRITE_SECURE_SETTINGS 授权时直接放弃（用户需手动开启或 ADB 授权）
        if (!AccessibilityServiceHelper.canWriteSecureSettings(context)) {
            Log.i(TAG, "无 WRITE_SECURE_SETTINGS 权限，跳过自动恢复")
            return
        }

        val delay = when (attempt) {
            0 -> 0L
            1 -> 5000L
            else -> 30000L
        }
        Handler(Looper.getMainLooper()).postDelayed({
            val restored = AccessibilityServiceHelper.ensureServiceEnabled(context)
            Log.i(TAG, "开机恢复无障碍服务尝试${attempt + 1}/$MAX_ATTEMPTS: $restored")
            if (!restored) {
                retryRestoreAccessibility(context, attempt + 1)
            }
        }, delay)
    }
}
