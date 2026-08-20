package com.palmagent.app.tool.impl

import com.palmagent.app.model.ToolCallResult
import com.palmagent.app.service.SearchResultCache
import com.palmagent.app.service.WebSearchService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

/**
 * 联网搜索工具（注册到 ToolRegistry，仅执行模型使用）。
 *
 * 工具名：web_search
 * 参数：query(必填) + count(可选, 默认5, 范围1-10)
 * 内部调用 WebSearchService.search()，字段映射：
 *   ToolCallResult.success → ToolResult.isSuccess
 *   ToolCallResult.content → ToolResult.data
 */
class WebSearchTool : BaseTool() {

    private val webSearchService get() = WebSearchService

    override fun getName(): String = "web_search"

    override fun getDisplayName(): String = "联网搜索"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            name = "query",
            type = "string",
            description = "搜索关键词",
            isRequired = true
        ),
        ToolParameter(
            name = "count",
            type = "integer",
            description = "返回结果数（默认5，最大10）",
            isRequired = false,
            default = 5,
            minValue = 1,
            maxValue = 10
        )
    )

    override fun getDescriptionEN(): String =
        "Search the web for real-time information, news, prices, weather, etc."

    override fun getDescriptionCN(): String =
        "联网搜索互联网最新信息、新闻、实时数据、价格、天气等。当用户问题涉及实时信息、近期事件时必须调用此工具。"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val query = requireString(params, "query")
        // BaseTool.optionalInt 已含下限保护（coerceAtLeast），此处追加 coerceIn 限制上限 10
        val count = optionalInt(params, "count", 5).coerceIn(1, 10)
        val result: ToolCallResult = webSearchService.search(query, count)
        return if (result.success) {
            ToolResult.success(result.content)
        } else {
            ToolResult.error(result.error ?: "搜索失败")
        }
    }
}

/**
 * 取回缓存搜索结果工具（注册到 ToolRegistry）。
 *
 * 工具名：web_search_fetch
 * 参数：ref(必填, 如 "ws-3-2")
 * 从 SearchResultCache 按 ref 取回该条完整结果（snippet + summary），仅供本轮参考。
 */
class WebSearchFetchTool : BaseTool() {

    override fun getName(): String = "web_search_fetch"

    override fun getDisplayName(): String = "取回搜索结果"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            name = "ref",
            type = "string",
            description = "搜索结果缓存条目 ref（如 ws-3-2）",
            isRequired = true
        )
    )

    override fun getDescriptionEN(): String =
        "Fetch the full cached content of a previous web search result by ref. The fetched content is for current-round reference only and is not persisted to working memory."

    override fun getDescriptionCN(): String =
        "按 ref 取回之前联网搜索缓存的完整内容（仅本轮参考，不写入工作记忆；需要保留的要点请自行提炼）。"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val ref = requireString(params, "ref").trim()
        if (ref.isEmpty()) {
            return ToolResult.error("ref 不能为空")
        }
        val cached = SearchResultCache.get(ref)
        if (cached == null) {
            return ToolResult.error("ref=$ref 不存在或已清理，请重新搜索")
        }
        val content = buildString {
            appendLine("【取回搜索结果 ${cached.ref}】${cached.title}")
            if (cached.url.isNotBlank()) appendLine("URL: ${cached.url}")
            if (cached.snippet.isNotBlank()) appendLine("片段: ${cached.snippet}")
            if (!cached.summary.isNullOrBlank()) {
                val cut = if (cached.summary.length > 800) "${cached.summary.take(800)}…" else cached.summary
                appendLine("原文摘要: $cut")
            }
            appendLine("（本内容仅供本轮参考，不写入工作记忆；需要保留的要点请自行提炼）")
        }
        return ToolResult.success(content)
    }
}
