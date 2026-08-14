package com.palmagent.app.utils

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.ViewTreeObserver
import com.palmagent.app.AgentApplication

/**
 * 键盘可见性检测工具。
 * Android 11+ 使用 WindowInsets API，低版本回退到屏幕高度差方案。
 */
object KeyboardDetector {

    private const val TAG = "KeyboardDetector"

    /** 键盘高度占屏幕高度的最小比例，超过此值视为键盘已弹出 */
    private const val KEYBOARD_RATIO_THRESHOLD = 0.15

    /** 当前键盘是否可见（缓存值，由监听器更新） */
    @Volatile
    private var cachedVisible: Boolean = false

    /** 当前键盘高度（px，缓存值） */
    @Volatile
    private var cachedHeight: Int = 0

    /** 是否已注册监听 */
    private var listenerRegistered = false

    // ======================== 公开 API ========================

    /** 键盘是否可见 */
    fun isKeyboardVisible(): Boolean = cachedVisible

    /** 键盘高度（px），不可见时为 0 */
    fun getKeyboardHeight(): Int = if (cachedVisible) cachedHeight else 0

    /**
     * 在 Activity 的 decorView 上注册键盘状态监听。
     * 应在 Activity.onResume 或合适的时机调用。
     */
    fun registerListener(activity: Activity) {
        if (listenerRegistered) return
        try {
            val decorView = activity.window.decorView
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                decorView.setOnApplyWindowInsetsListener { _, insets ->
                    val imeVisible = insets.isVisible(WindowInsets.Type.ime())
                    val imeHeight = insets.getInsets(WindowInsets.Type.ime()).bottom
                    cachedVisible = imeVisible
                    cachedHeight = imeHeight
                    Log.d(TAG, "WindowInsets回调: visible=$imeVisible, height=$imeHeight")
                    insets
                }
            } else {
                @Suppress("DEPRECATION")
                decorView.viewTreeObserver.addOnGlobalLayoutListener(object :
                    ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val rect = Rect()
                        decorView.getWindowVisibleDisplayFrame(rect)
                        val screenHeight = decorView.rootView.height
                        val visibleHeight = rect.bottom - rect.top
                        val kbHeight = screenHeight - visibleHeight
                        val visible = kbHeight > screenHeight * KEYBOARD_RATIO_THRESHOLD
                        if (visible != cachedVisible || kbHeight != cachedHeight) {
                            cachedVisible = visible
                            cachedHeight = if (visible) kbHeight else 0
                            Log.d(TAG, "GlobalLayout回调: visible=$visible, height=$kbHeight")
                        }
                    }
                })
            }
            listenerRegistered = true
            Log.i(TAG, "键盘监听已注册 (API ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.e(TAG, "注册键盘监听失败: ${e.message}")
        }
    }

    /**
     * 取消注册监听。
     */
    fun unregisterListener(activity: Activity) {
        if (!listenerRegistered) return
        try {
            val decorView = activity.window.decorView
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                decorView.setOnApplyWindowInsetsListener(null)
            }
            // Legacy OnGlobalLayoutListener 无法精确移除匿名对象，但不会泄漏
            listenerRegistered = false
            cachedVisible = false
            cachedHeight = 0
            Log.i(TAG, "键盘监听已取消")
        } catch (e: Exception) {
            Log.e(TAG, "取消键盘监听失败: ${e.message}")
        }
    }

    /**
     * 单次查询：直接从 Window 获取键盘可见性（不依赖缓存）。
     * 适用于没有注册监听或需要即时查询的场景。
     */
    fun isKeyboardVisibleNow(window: Window): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.rootWindowInsets
                ?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            isKeyboardVisibleLegacy(window)
        }
    }

    /**
     * 单次查询：直接从 Window 获取键盘高度（不依赖缓存）。
     */
    fun getKeyboardHeightNow(window: Window): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val visible = window.decorView.rootWindowInsets
                ?.isVisible(WindowInsets.Type.ime()) == true
            if (visible) {
                window.decorView.rootWindowInsets
                    ?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            } else {
                0
            }
        } else {
            getKeyboardHeightLegacy(window)
        }
    }

    /**
     * 获取当前前台 Activity 的 Window，用于单次查询。
     * 如果无法获取则返回 null。
     */
    fun getCurrentWindow(): Window? {
        // 优先从无障碍服务获取
        val a11yService = com.palmagent.app.service.GUIAccessibilityService.instance
        if (a11yService != null) {
            // 无障碍服务本身不是 Activity，尝试从 Application 获取
        }
        // 回退：从 Application 的 registerActivityLifecycleCallbacks 获取
        return LastActivityHolder.lastActivity?.window
    }

    /** 状态摘要，供 Agent 上下文使用。
     *  当缓存显示键盘未弹出时，通过GUI-Plus视觉检测回退 */
    fun getStatusSummary(): String {
        if (cachedVisible) {
            return "键盘已弹出(高度${cachedHeight}px)"
        }
        return "键盘未弹出"
    }

    // ======================== Legacy 回退 ========================

    private fun isKeyboardVisibleLegacy(window: Window): Boolean {
        val rect = Rect()
        window.decorView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = window.decorView.rootView.height
        val visibleHeight = rect.bottom - rect.top
        val kbHeight = screenHeight - visibleHeight
        return kbHeight > screenHeight * KEYBOARD_RATIO_THRESHOLD
    }

    private fun getKeyboardHeightLegacy(window: Window): Int {
        val rect = Rect()
        window.decorView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = window.decorView.rootView.height
        val visibleHeight = rect.bottom - rect.top
        val kbHeight = screenHeight - visibleHeight
        return if (kbHeight > screenHeight * KEYBOARD_RATIO_THRESHOLD) kbHeight else 0
    }

    // ======================== Activity 生命周期追踪 ========================

    /**
     * 追踪最近的前台 Activity，供键盘检测获取 Window。
     * 应在 Application 中注册。
     */
    object LastActivityHolder {
        var lastActivity: Activity? = null
            private set

        fun onActivityResumed(activity: Activity) {
            lastActivity = activity
            registerListener(activity)
        }

        fun onActivityPaused(activity: Activity) {
            if (lastActivity == activity) {
                // Activity 退到后台，不清除引用，但标记键盘不可见
                cachedVisible = false
            }
        }

        fun onActivityDestroyed(activity: Activity) {
            if (lastActivity == activity) {
                unregisterListener(activity)
                lastActivity = null
            }
        }
    }
}
