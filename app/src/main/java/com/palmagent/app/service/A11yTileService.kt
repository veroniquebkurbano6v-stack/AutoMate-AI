package com.palmagent.app.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log

/**
 * 通知栏快捷开关
 *
 * 用户下拉通知栏可一键重启无障碍服务。
 * 如果拥有 WRITE_SECURE_SETTINGS 权限，可编程恢复；否则跳转系统设置页。
 */
class A11yTileService : TileService() {

    companion object {
        private const val TAG = "A11yTileService"
    }

    override fun onClick() {
        super.onClick()
        if (GUIAccessibilityService.isRunning) {
            Log.i(TAG, "无障碍服务已在运行")
            return
        }

        Log.i(TAG, "用户通过快捷开关请求恢复无障碍服务")

        // 优先尝试编程恢复
        if (AccessibilityServiceHelper.ensureServiceEnabled(this)) {
            Log.i(TAG, "已通过编程方式恢复无障碍服务")
        } else {
            // 降级：跳转系统无障碍设置页
            try {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(intent)
            } catch (e: Exception) {
                Log.e(TAG, "跳转无障碍设置页失败: ${e.message}")
            }
        }
    }
}
