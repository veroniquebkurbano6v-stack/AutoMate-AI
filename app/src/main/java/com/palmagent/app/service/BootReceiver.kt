package com.palmagent.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, starting ForegroundService")
            ForegroundService.start(context)
            // 尝试恢复无障碍服务
            if (!GUIAccessibilityService.isRunning) {
                val restored = AccessibilityServiceHelper.ensureServiceEnabled(context)
                Log.i(TAG, "开机恢复无障碍服务结果: $restored")
            }
        }
    }
}