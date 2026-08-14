package com.palmagent.app.kb

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 端侧 SOP JSON 加载器：从 assets/kb/sop_raw/ 读取全部 SOP 文件并解析。
 * 完全端侧 RAG：无需 PC 预构建，App 首次启动时直接读 assets 建库。
 */
class SopJsonLoader(private val context: Context) {

    companion object { private const val TAG = "SopJsonLoader" }

    /** 从 assets/kb/sop_raw/ 加载全部 SOP（含 steps），返回待嵌入的文本单元 */
    fun loadAll(): List<SopChunk> {
        val chunks = ArrayList<SopChunk>()
        val files = context.assets.list("kb/sop_raw") ?: return chunks
        Log.i(TAG, "发现 ${files.size} 个 SOP JSON 文件")

        for (fname in files.sorted()) {
            if (!fname.endsWith(".json")) continue
            try {
                val text = context.assets.open("kb/sop_raw/$fname").use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                }
                parse(JSONObject(text))?.let { chunks.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "解析 $fname 失败: ${e.message}")
            }
        }
        Log.i(TAG, "解析完成：${chunks.size} 条 SOP")
        return chunks
    }

    private fun parse(raw: JSONObject): SopChunk? {
        val sopId = raw.optString("sop_id").trim()
        val taskName = raw.optString("task_name").trim()
        val appName = raw.optString("app_name").trim()
        if (sopId.isEmpty() || taskName.isEmpty()) return null

        val keywords = mutableListOf<String>()
        val kwArr = raw.optJSONArray("keywords")
        if (kwArr != null) {
            for (i in 0 until kwArr.length()) {
                val k = kwArr.optString(i).trim()
                if (k.isNotEmpty()) keywords.add(k)
            }
        }

        val steps = ArrayList<SopStep>()
        val stepsArr = raw.optJSONArray("steps")
        if (stepsArr != null) {
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                val order = s.optInt("step_order", i + 1)
                steps.add(SopStep(
                    stepRef = "$sopId:$order",
                    stepOrder = order,
                    goal = s.optString("goal").trim(),
                    expected = s.optString("expected").trim(),
                    actionType = s.optString("action_type").trim()
                ))
            }
        }

        // 两路向量化文本（对齐原 chunker.py）
        val taskText = taskName
        val keywordText = keywords.joinToString("，") { it }

        return SopChunk(
            sopId = sopId,
            originalTaskName = raw.optString("original_task_name").trim(),
            taskName = taskName,
            appName = appName,
            source = raw.optString("source").ifEmpty { null },
            difficulty = raw.optString("difficulty").ifEmpty { null },
            domain = raw.optString("domain").ifEmpty { null },
            keywords = keywords,
            taskText = taskText,
            keywordText = keywordText,
            steps = steps
        )
    }
}

/** 待嵌入的 SOP 检索单元 */
data class SopChunk(
    val sopId: String,
    val originalTaskName: String,
    val taskName: String,
    val appName: String,
    val source: String?,
    val difficulty: String?,
    val domain: String?,
    val keywords: List<String>,
    val taskText: String,
    val keywordText: String,
    val steps: List<SopStep>
)
