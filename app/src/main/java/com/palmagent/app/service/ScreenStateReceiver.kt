package com.palmagent.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 屏幕状态广播接收器
 *
 * 屏幕亮起时检查无障碍服务状态，如果服务断开则尝试编程恢复。
 */
class ScreenStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                if (!GUIAccessibilityService.isRunning) {
                    Log.i(TAG, "屏幕亮起，无障碍服务未运行，尝试恢复")
                    AccessibilityServiceHelper.ensureServiceEnabled(context)
                }
            }
        }
    }
}
