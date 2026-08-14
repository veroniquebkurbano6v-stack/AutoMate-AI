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

        @JvmStatic
        fun success(data: String, metadata: Map<String, Any> = emptyMap()): ToolResult =
            ToolResult(true, data, null, metadata)

        @JvmStatic
        fun error(error: String): ToolResult =
            ToolResult(false, null, error, emptyMap())

        @JvmStatic
        fun error(error: String, metadata: Map<String, Any>): ToolResult =
            ToolResult(false, null, error, metadata)

        /** 带结构化错误信息的工厂方法 */
        @JvmStatic
        fun error(
            error: String,
            errorType: String,
            failureCategory: String? = null,
            suggestion: String = ""
        ): ToolResult {
            val meta = mutableMapOf<String, Any>(META_ERROR_TYPE to errorType)
            if (failureCategory != null) meta[META_FAILURE_CATEGORY] = failureCategory
            if (suggestion.isNotBlank()) meta[META_ERROR_SUGGESTION] = suggestion
            return ToolResult(false, null, error, meta)
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
