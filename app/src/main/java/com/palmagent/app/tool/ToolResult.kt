package com.palmagent.app.tool

class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?,
    val metadata: Map<String, Any>
) {
    companion object {
        /** 错误信息标准字段（存入 metadata） */
        const val META_ERROR_TYPE = "error_type"              // TRANSIENT / FATAL / VALIDATION
        const val META_ERROR_SUGGESTION = "suggestion"         // 恢复建议
        const val META_FAILURE_CATEGORY = "failure_category"   // 具体分类: APP_NOT_INSTALLED / SERVICE_UNAVAILABLE / ...
        /** 机器可读稳定错误码（如 TARGET_NOT_FOUND），供模型分支判断 */
        const val META_ERROR_CODE = "error_code"
        /** 是否可重试（仅瞬时错误为 true） */
        const val META_RETRIABLE = "retriable"
        /** 严重级别: info / warning / error / fatal */
        const val META_SEVERITY = "severity"

        // 错误信封长度上限（源头锁死单条错误 token，业界规范：短陈述句、无堆栈）
        private const val MAX_ERROR_LEN = 80
        private const val MAX_SUGGESTION_LEN = 60
        private const val MAX_CODE_LEN = 32

        @JvmStatic
        fun success(data: String, metadata: Map<String, Any> = emptyMap()): ToolResult =
            ToolResult(true, data, null, metadata)

        @JvmStatic
        fun error(error: String): ToolResult =
            ToolResult(false, null, error.take(MAX_ERROR_LEN), emptyMap())

        @JvmStatic
        fun error(error: String, metadata: Map<String, Any>): ToolResult =
            ToolResult(false, null, error.take(MAX_ERROR_LEN), metadata)

        /** 带结构化错误信息的工厂方法 */
        @JvmStatic
        fun error(
            error: String,
            errorType: String,
            failureCategory: String? = null,
            suggestion: String = "",
            code: String? = null,
            retriable: Boolean? = null,
            severity: String = "error"
        ): ToolResult {
            val meta = mutableMapOf<String, Any>(META_ERROR_TYPE to errorType)
            if (failureCategory != null) meta[META_FAILURE_CATEGORY] = failureCategory
            if (suggestion.isNotBlank()) meta[META_ERROR_SUGGESTION] = suggestion.take(MAX_SUGGESTION_LEN)
            // 未显式指定时按 errorType 派生：仅 TRANSIENT 可重试
            meta[META_RETRIABLE] = retriable ?: (errorType == "TRANSIENT")
            meta[META_SEVERITY] = severity
            if (code != null) meta[META_ERROR_CODE] = code.take(MAX_CODE_LEN)
            return ToolResult(false, null, error.take(MAX_ERROR_LEN), meta)
        }
    }

    /** 获取坐标元数据 */
    val coordinate: Pair<Int, Int>?
        get() = (metadata["x"] as? Int)?.let { x ->
            (metadata["y"] as? Int)?.let { y -> x to y }
        }

    override fun toString(): String = if (isSuccess) {
        "ToolResult{success=true, data='$data'}"
    } else {
        "ToolResult{success=false, error='$error'}"
    }
}
