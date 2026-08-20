package com.palmagent.app.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 博查搜索结果缓存（完整保留 + 会话内去重）。
 *
 * 职责：
 * - 完整保存博查返回的每条结果（title/url/snippet/summary 全字段，不截断），按轮存文件
 * - cache_index：规范化 query → 轮次，同 query 再次搜索时命中复用，不重复调博查
 * - 保留最近 KEEP_ROUNDS 轮，任务结束/超出窗口自动清理
 *
 * ref 规则：ws-<round>-<序号>（1 起），与 ScratchpadEntry id 风格一致。
 */
object SearchResultCache {

    private const val TAG = "SearchResultCache"
    private const val DIR_NAME = "search_cache"
    private const val KEEP_ROUNDS = 4

    /** 单条缓存结果（含 ref 定位） */
    data class CachedEntry(
        val ref: String,
        val title: String,
        val url: String,
        val snippet: String,
        val summary: String?,
        val roundCreated: Int
    )

    private val gson = Gson()
    private val listType = object : TypeToken<List<CachedEntry>>() {}.type
    private var cacheDir: File? = null

    /** 规范化 query → 轮次（会话内去重索引） */
    private val index = mutableMapOf<String, Int>()

    fun init(context: Context) {
        if (cacheDir != null) return
        cacheDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    }

    /** 规范化 query：全角转半角 + 去标点/符号/空白 + lowercase，用于去重键 */
    fun normalizeQuery(query: String): String {
        val halfWidth = query.map { ch ->
            when {
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                ch == '\u3000' -> ' '
                else -> ch
            }
        }.joinToString("")
        return halfWidth
            .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
            .lowercase()
            .trim()
    }

    /**
     * 缓存一轮搜索结果（完整保存所有条目），返回带 ref 的缓存条目列表。
     * 写入失败返回空列表（调用方回退旧截断逻辑）。
     */
    fun putSearch(round: Int, query: String, items: List<WebSearchService.SearchItem>): List<CachedEntry> {
        val dir = cacheDir ?: return emptyList()
        val file = File(dir, "search_$round.json")
        try {
            val entries = items.mapIndexed { idx, item ->
                CachedEntry(
                    ref = "ws-$round-${idx + 1}",
                    title = item.title,
                    url = item.url,
                    snippet = item.snippet,
                    summary = item.summary,
                    roundCreated = round
                )
            }
            file.writeText(gson.toJson(entries))
            index[normalizeQuery(query)] = round
            cleanup()
            return entries
        } catch (e: Exception) {
            Log.w(TAG, "写缓存失败: ${e.message}")
            return emptyList()
        }
    }

    /** 同 query 是否命中缓存？返回命中轮次（文件仍存在时），否则 null */
    fun hitRound(query: String): Int? {
        val key = normalizeQuery(query)
        if (key.isBlank()) return null
        val round = index[key] ?: return null
        val dir = cacheDir ?: return null
        return round.takeIf { File(dir, "search_$round.json").exists() }
    }

    /** 按 ref 取回单条缓存（完整 snippet + summary），ref 非法或已清理返回 null */
    fun get(ref: String): CachedEntry? {
        val dir = cacheDir ?: return null
        val m = Regex("^ws-(\\d+)-(\\d+)$").find(ref) ?: return null
        val round = m.groupValues[1].toIntOrNull() ?: return null
        val idx = (m.groupValues[2].toIntOrNull() ?: return null) - 1
        return readEntries(round)?.getOrNull(idx)
    }

    /** 读取某轮全部缓存条目（供缓存命中复用摘要 / 按 ref 取回原文） */
    fun readEntries(round: Int): List<CachedEntry>? {
        val dir = cacheDir ?: return null
        val file = File(dir, "search_$round.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), listType)
        } catch (e: Exception) {
            Log.w(TAG, "读缓存失败 round=$round: ${e.message}")
            null
        }
    }

    /**
     * 生成摘要视图文本（供本轮上下文使用，仅本轮看、不入工作区）。
     * 每条：ref + title + snippet(截断)，并提示模型按 ref 取回全文。
     */
    fun buildSummary(query: String, entries: List<CachedEntry>, snippetLimit: Int = 120): String {
        if (entries.isEmpty()) return "【搜索结果摘要】查询: $query | 无结果"
        val sb = StringBuilder()
        sb.append("【搜索结果摘要】查询: $query | 共 ${entries.size} 条")
        sb.append("\n如需某条完整内容，调用 WEB_SEARCH_FETCH 传入 ref；请判断哪些与任务相关，把要点写入工作区。")
        entries.forEach { e ->
            sb.append("\n[${e.ref}] ${e.title}")
            if (e.snippet.isNotBlank()) {
                val cut = if (e.snippet.length > snippetLimit) "${e.snippet.take(snippetLimit)}…" else e.snippet
                sb.append("\n   摘要: $cut")
            }
        }
        return sb.toString()
    }

    /** 保留最近 KEEP_ROUNDS 轮文件，删除更早的（同步清理失效索引） */
    private fun cleanup() {
        val dir = cacheDir ?: return
        val files = dir.listFiles { f -> f.name.matches(Regex("search_\\d+\\.json")) }
            ?.sortedByDescending { f -> f.name.removePrefix("search_").removeSuffix(".json").toIntOrNull() ?: 0 }
            ?: return
        val kept = files.take(KEEP_ROUNDS).map { it.name }.toSet()
        files.drop(KEEP_ROUNDS).forEach { f ->
            f.delete()
            Log.d(TAG, "清理过期缓存: ${f.name}")
        }
        // 清理索引中已删除文件的轮次
        index.entries.removeAll { (_, round) -> kept.none { it == "search_$round.json" } }
    }

    /** 任务结束清理：清空文件与索引 */
    fun clearAll() {
        val dir = cacheDir ?: return
        dir.listFiles { f -> f.name.matches(Regex("search_\\d+\\.json")) }?.forEach { it.delete() }
        index.clear()
        Log.d(TAG, "任务结束，清空搜索缓存")
    }
}
