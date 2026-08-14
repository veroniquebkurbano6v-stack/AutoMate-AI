package com.palmagent.app

import android.os.Handler
import android.os.Looper
import java.io.StringWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LiveLogBuffer {

    private const val MAX_ENTRIES = 1000

    data class LogEntry(
        val timestamp: String,
        val message: String,
        val timeMs: Long = System.currentTimeMillis()
    )

    private val entries = CopyOnWriteArrayList<LogEntry>()
    private var lastHourMinute = ""

    // 多消费者列表：支持多个 UI 同时订阅日志变化
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 注册日志变化监听器。
     * 注意：调用方应在销毁时调用 [removeListener] 避免泄漏，不要依赖 setter 清空。
     *
     * @return true 表示注册成功，false 表示已存在相同监听器
     */
    fun addListener(listener: () -> Unit): Boolean {
        if (listeners.contains(listener)) return false
        return listeners.add(listener)
    }

    /**
     * 注销日志变化监听器。
     *
     * @return true 表示注销成功，false 表示监听器不存在
     */
    fun removeListener(listener: () -> Unit): Boolean {
        return listeners.remove(listener)
    }

    fun append(message: String) {
        val now = Date()
        val currentHourMinute = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)

        val entry = if (currentHourMinute != lastHourMinute) {
            lastHourMinute = currentHourMinute
            LogEntry(
                timestamp = "── ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(now)} ──",
                message = message,
                timeMs = System.currentTimeMillis()
            )
        } else {
            LogEntry(
                timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(now),
                message = message,
                timeMs = System.currentTimeMillis()
            )
        }

        entries.add(entry)

        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }

        // 层2a：双写 logcat——此前 LiveLogBuffer 仅进内存+UI，决策/执行全链路在 logcat 不可见；
        // 统一在此双写，让 logcat 可完整追踪（配合 `adb logcat -s PalmAgentLive:*` 过滤）
        android.util.Log.d("PalmAgentLive", message)

        notifyListeners()
    }

    fun logException(tag: String, e: Exception) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        append("‼ [$tag] ${e.message ?: "未知异常"}\n${sw.toString().take(500)}")
    }

    fun getEntries(): List<LogEntry> = entries.toList()

    fun getRecentEntries(count: Int = 50): List<LogEntry> {
        val list = entries.toList()
        return if (list.size <= count) list else list.takeLast(count)
    }

    fun clear() {
        entries.clear()
        notifyListeners()
    }

    private fun notifyListeners() {
        // 复制一份再 post 到主线程，避免遍历期间外部 removeListener 导致 ConcurrentModification
        val snapshot = listeners.toList()
        mainHandler.post {
            for (listener in snapshot) {
                try {
                    listener.invoke()
                } catch (e: Exception) {
                    // 单个 listener 异常不应影响其他 listener
                    android.util.Log.w("LiveLogBuffer", "监听器异常: ${e.message}")
                }
            }
        }
    }
}
