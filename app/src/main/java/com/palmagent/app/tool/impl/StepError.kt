package com.palmagent.app.tool.impl

/**
 * 步骤错误三分类模型
 *
 * 借鉴 LangGraph default_retry_on 设计哲学：编程 bug 不重试，环境故障才重试
 *
 * - Transient：瞬时错误，重试可能成功（如点击被取消、OCR 偶发失败）
 * - Fatal：致命错误，重试无意义（如服务未就绪、应用未安装）
 * - Validation：校验错误，需修正而非重试（如参数缺失、坐标越界、目标不存在）
 */
sealed class StepError {
    abstract val original: Throwable
    abstract val category: String

    /** 瞬时错误：环境波动，重试可能成功 */
    class Transient(override val original: Throwable) : StepError() {
        override val category = "transient"
    }

    /** 致命错误：不可恢复，重试无意义 */
    class Fatal(override val original: Throwable) : StepError() {
        override val category = "fatal"
    }

    /** 校验错误：输入不合法，需修正而非重试 */
    class Validation(override val original: Throwable) : StepError() {
        override val category = "validation"
    }
}

/**
 * 工具执行异常：将 ToolResult.error 包装为可分类的异常
 */
class ToolExecutionException(
    val errorMessage: String,
    val toolName: String,
    val params: Map<String, Any>,
    val toolResultMetadata: Map<String, Any> = emptyMap()
) : Exception(errorMessage)

/**
 * 错误分类器
 *
 * 双轨分类机制：
 * 1. 异常类型匹配（优先级最高）：IllegalArgumentException → Validation
 * 2. 错误消息模式匹配（次之）：匹配关键词判断类别
 * 3. 默认兜底（保守策略）：未知错误归为 Transient
 */
object ErrorClassifier {

    // 校验错误关键词：参数问题、目标不存在
    private val validationPatterns = listOf(
        "参数", "不能为空", "坐标.*超出", "越界",
        "未找到.*文字", "未找到.*元素", "无法解析",
        "格式错误", "不合法", "invalid", "illegal",
        "target not found", "element not found"
    )

    // 致命错误关键词：服务未就绪、应用未安装、权限问题
    private val fatalPatterns = listOf(
        "服务未运行", "服务未就绪", "未开启", "未安装",
        "权限", "设备离线", "adb.*断开", "连接.*失败",
        "空指针", "not installed", "permission denied",
        "app_not_installed", "应用未安装", "目标应用不存在",
        "无法打开应用商店"
    )

    /**
     * 分类入口
     */
    fun classify(t: Throwable): StepError {
        return when (t) {
            // 1. Java 异常类型匹配（优先级最高）
            is IllegalArgumentException,
            is NumberFormatException -> StepError.Validation(t)

            is NullPointerException,
            is IllegalStateException,
            is SecurityException -> StepError.Fatal(t)

            // 2. 网络与超时类（瞬时）
            is kotlinx.coroutines.TimeoutCancellationException,
            is java.net.SocketTimeoutException,
            is java.io.IOException -> StepError.Transient(t)

            // 3. 工具执行异常：进入消息模式匹配
            is ToolExecutionException -> classifyToolError(t)

            // 4. 默认兜底：保守策略，认为可重试
            else -> StepError.Transient(t)
        }
    }

    /**
     * 工具执行异常分类：通过错误消息模式匹配
     */
    private fun classifyToolError(e: ToolExecutionException): StepError {
        val msg = e.errorMessage.lowercase()

        // 校验错误：参数问题、目标不存在
        if (validationPatterns.any { msg.contains(it.lowercase()) }) {
            return StepError.Validation(e)
        }

        // 致命错误：服务未就绪、应用未安装、权限问题
        if (fatalPatterns.any { msg.contains(it.lowercase()) }) {
            return StepError.Fatal(e)
        }

        // 瞬时错误：偶发失败，重试可能成功（如点击被取消、OCR 未识别到文字）
        return StepError.Transient(e)
    }
}
