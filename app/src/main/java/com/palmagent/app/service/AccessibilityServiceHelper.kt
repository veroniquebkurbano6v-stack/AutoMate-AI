package com.palmagent.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 无障碍服务辅助工具
 *
 * 通过 WRITE_SECURE_SETTINGS 权限实现编程方式恢复无障碍服务。
 * 需要一次性 ADB 授权：adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
 * 授权后永久有效（卸载重装才失效）。
 */
object AccessibilityServiceHelper {
    private const val TAG = "A11yHelper"

    /**
     * 检查是否拥有 WRITE_SECURE_SETTINGS 权限
     */
    fun canWriteSecureSettings(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 通过 WRITE_SECURE_SETTINGS 编程启用无障碍服务
     *
     * 原理：直接写入 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES，
     * 让系统重新绑定无障碍服务，无需用户手动到设置页开启。
     *
     * @return true 表示成功写入或服务已启用
     */
    fun ensureServiceEnabled(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) {
            Log.w(TAG, "无 WRITE_SECURE_SETTINGS 权限，无法编程恢复")
            return false
        }

        return try {
            val serviceName = "${context.packageName}/${context.packageName}.service.GUIAccessibilityService"

            // 读取当前已启用的无障碍服务列表
            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            // 检查是否已包含本服务
            if (currentServices.contains(serviceName)) {
                // 已在列表中但服务未运行，先移除再添加以触发系统重新绑定
                val cleaned = currentServices.split(":")
                    .filter { it.isNotBlank() && it != serviceName }
                    .joinToString(":")
                val newServices = if (cleaned.isBlank()) serviceName else "$cleaned:$serviceName"

                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
                Log.i(TAG, "已通过 WRITE_SECURE_SETTINGS 重新绑定无障碍服务")
            } else {
                // 不在列表中，直接添加
                val newServices = if (currentServices.isBlank()) {
                    serviceName
                } else {
                    "$currentServices:$serviceName"
                }

                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
                Log.i(TAG, "已通过 WRITE_SECURE_SETTINGS 启用无障碍服务")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "编程启用无障碍服务失败: ${e.message}")
            false
        }
    }
}
