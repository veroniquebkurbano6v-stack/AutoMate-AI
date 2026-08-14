package com.palmagent.app.ui.viewmodel

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.framework.event.EventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 日志页 UI 状态
 * - logText 改为 CharSequence 以承载 Spannable 上色文本
 */
data class LogUiState(
    val logText: CharSequence = "",
    val autoScroll: Boolean = true
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val eventBus: EventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    private val liveLogListener: () -> Unit = { refreshLogs() }

    init {
        LiveLogBuffer.addListener(liveLogListener)
    }

    fun startAutoRefresh() {
        stopAutoRefresh()
        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                refreshLogs()
                delay(1000L)
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * 刷新日志：构建带颜色分级的 Spannable 文本。
     * - 时间戳：暗灰色
     * - 时间分隔行（── 开头）：青色
     * - 成功类（✓/✅/完成/成功）：绿色
     * - 警告类（⚠/警告）：黄色
     * - 错误类（‼/✗/错误/失败/异常）：红色
     * - 信息类（🔁/开始/轮）：蓝色
     * - 默认：浅灰白
     */
    fun refreshLogs() {
        val entries = LiveLogBuffer.getEntries()
        val sb = buildColoredLog(entries)
        _uiState.value = _uiState.value.copy(logText = sb)
    }

    /**
     * 构建带颜色分级的日志文本。
     */
    private fun buildColoredLog(entries: List<LiveLogBuffer.LogEntry>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (entry in entries) {
            // 时间戳部分：暗灰色
            val tsStart = sb.length
            sb.append(entry.timestamp)
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#5A6579")),
                tsStart, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append("  ")

            // 消息部分：按内容前缀分级上色
            val msgStart = sb.length
            sb.append(entry.message)
            sb.setSpan(
                ForegroundColorSpan(getMessageColor(entry.message)),
                msgStart, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append("\n")
        }
        return sb
    }

    /**
     * 根据消息内容判断显示颜色。
     */
    private fun getMessageColor(message: String): Int {
        return when {
            message.startsWith("──") -> Color.parseColor("#22D3EE") // 时间分隔行：青色
            message.contains("✓") || message.contains("✅") ||
                message.contains("成功") || message.contains("完成") ->
                Color.parseColor("#34D399") // 成功：绿色
            message.contains("⚠") || message.contains("警告") ->
                Color.parseColor("#FBBF24") // 警告：黄色
            message.contains("‼") || message.contains("✗") ||
                message.contains("错误") || message.contains("失败") ||
                message.contains("异常") ->
                Color.parseColor("#F87171") // 错误：红色
            message.contains("🔁") || message.contains("开始") ||
                message.contains("轮") ->
                Color.parseColor("#60A5FA") // 信息：蓝色
            else -> Color.parseColor("#C0C9D6") // 默认：浅灰白
        }
    }

    fun clearLogs() {
        LiveLogBuffer.clear()
        _uiState.value = _uiState.value.copy(logText = "")
    }

    /**
     * 复制全部日志：返回纯文本（不含颜色 span）。
     */
    fun copyAllLogs(): String {
        val entries = LiveLogBuffer.getEntries()
        val sb = StringBuilder()
        for (entry in entries) {
            sb.append(entry.timestamp)
            sb.append("  ")
            sb.append(entry.message)
            sb.append("\n")
        }
        return sb.toString()
    }

    fun setAutoScroll(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoScroll = enabled)
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoRefresh()
        LiveLogBuffer.removeListener(liveLogListener)
    }
}
