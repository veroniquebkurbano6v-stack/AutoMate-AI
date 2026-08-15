package com.palmagent.app.kb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 完全端侧知识库引擎（无 PC 依赖、无网络、无 HTTP 回退）。
 *
 * 初始化流程：
 * 1. KbAssetLoader 拷贝 ONNX 模型 + vocab 到 filesDir
 * 2. 若 kb.db 不存在 → SopJsonLoader 从 assets 读 586 条 SOP → OnnxEmbedder 逐条嵌入 → KbDbAccessor 写库
 * 3. 若 kb.db 已存在 → 直接读全量数据到内存
 * 4. 构建 InMemoryVectorIndex + KeywordSearcher
 *
 * 检索流程（search）：
 * embed(query) → 向量检索 + 关键词检索 → RRF 融合 → 阈值过滤 → top_k
 */
class LocalKbEngine private constructor(
    private val embedder: OnnxEmbedder,
    private val records: List<SopRecord>,
    private val vectorIndex: InMemoryVectorIndex,
    private val keywordSearcher: KeywordSearcher
) {
    companion object {
        private const val TAG = "LocalKbEngine"
        private const val RRF_K = 60
        private const val SCORE_THRESHOLD = 0.3

        @Volatile private var INSTANCE: LocalKbEngine? = null
        @Volatile private var initializing = false

        fun init(context: Context): LocalKbEngine {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                if (initializing) {
                    // 另一线程正在初始化，等待其完成
                    while (initializing) Thread.sleep(50)
                    return INSTANCE ?: error("初始化失败")
                }
                initializing = true
                try {
                    val engine = buildEngine(context)
                    INSTANCE = engine
                    return engine
                } finally {
                    initializing = false
                }
            }
        }

        /**
         * 强制重建知识库：删除 kb.db 并从 assets 重新嵌入建库。
         * 供设置界面"重新入库"按钮调用；必须在后台线程执行（约 30-60s）。
         * 成功后会替换 INSTANCE 并释放旧引擎的 ONNX 会话；失败则回滚到旧引擎。
         */
        fun rebuild(context: Context): LocalKbEngine {
            synchronized(this) {
                if (initializing) {
                    throw IllegalStateException("知识库正在初始化/重建中，请稍后再试")
                }
                initializing = true
                val old = INSTANCE
                try {
                    // 删除旧库，强制走嵌入建库分支
                    val paths = KbAssetLoader(context).ensureAssets()
                    java.io.File(paths.dbPath).delete()
                    INSTANCE = null
                    val engine = buildEngine(context)
                    INSTANCE = engine
                    // 释放旧引擎的 ONNX 会话，避免模型重复加载
                    old?.let { o ->
                        if (o !== engine) {
                            try { o.close() } catch (_: Exception) {}
                        }
                    }
                    Log.i(TAG, "知识库重建完成：${engine.records.size} 条 SOP")
                    return engine
                } catch (e: Exception) {
                    // 重建失败：回滚到旧引擎（内存数据仍可用），下次再触发重建
                    if (INSTANCE == null) INSTANCE = old
                    throw e
                } finally {
                    initializing = false
                }
            }
        }

        fun get(): LocalKbEngine? = INSTANCE

        /** 端侧嵌入建库或直接读库，构建引擎实例（不写 INSTANCE，由调用方管理）。 */
        private fun buildEngine(context: Context): LocalKbEngine {
            val t0 = System.currentTimeMillis()
            val loader = KbAssetLoader(context)
            val paths = loader.ensureAssets()

            // 加载嵌入器（ONNX 模型，约 1s）
            val embedder = OnnxEmbedder(paths.modelPath, paths.vocabPath, 512)

            val db = KbDbAccessor(paths.dbPath)
            val records: List<SopRecord>
            if (!loader.isDbBuilt()) {
                // 首次启动 / 重建：端侧嵌入建库
                Log.i(TAG, "kb.db 不存在，开始端侧建库...")
                try {
                    val chunks = SopJsonLoader(context).loadAll()
                    val taskVecs = ArrayList<FloatArray>(chunks.size)
                    val kwVecs = HashMap<String, FloatArray>()
                    for ((i, c) in chunks.withIndex()) {
                        taskVecs.add(embedder.embed(c.taskText))
                        if (c.keywordText.isNotEmpty()) {
                            kwVecs[c.sopId] = embedder.embed(c.keywordText)
                        }
                        if ((i + 1) % 100 == 0) {
                            Log.i(TAG, "嵌入进度 ${i + 1}/${chunks.size}")
                        }
                    }
                    db.buildIndex(chunks, taskVecs, kwVecs)
                    Log.i(TAG, "端侧建库完成，耗时 ${System.currentTimeMillis() - t0}ms")
                } catch (e: Exception) {
                    // 建库失败：删除可能残留的不完整 kb.db，并释放嵌入器，确保下次启动重建
                    Log.e(TAG, "端侧建库失败，清理 kb.db: ${e.message}", e)
                    java.io.File(paths.dbPath).delete()
                    embedder.close()
                    throw e
                }
            }
            records = db.loadAll()
            Log.i(TAG, "端侧知识库就绪：${records.size} 条 SOP，总耗时 ${System.currentTimeMillis() - t0}ms")

            return LocalKbEngine(
                embedder = embedder,
                records = records,
                vectorIndex = InMemoryVectorIndex(records),
                keywordSearcher = KeywordSearcher(records)
            )
        }
    }

    /** 释放 ONNX 会话（重建替换旧引擎时调用）。 */
    fun close() {
        embedder.close()
    }

    suspend fun search(
        query: String,
        appFilter: String? = null,
        topK: Int = 3
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val qvec = embedder.embed(query)

        val vecResults = vectorIndex.search(qvec, appFilter, 50)
        val kwResults = keywordSearcher.search(query, appFilter, 50)
        val fused = rrfFuse(vecResults, kwResults, 50)
        if (fused.isEmpty()) return@withContext emptyList()

        val byId = records.associateBy { it.sopId }
        val results = ArrayList<SearchResult>(topK)
        for ((sopId, _) in fused.take(topK)) {
            val r = byId[sopId] ?: continue
            val ts = cosine(qvec, r.taskVec)
            val ks = r.kwVec?.let { cosine(qvec, it) } ?: 0f
            val score = (0.7 * ts + 0.3 * ks).toDouble()
            val confidence = when {
                score >= 0.55 -> "high"
                score >= 0.45 -> "medium"
                else -> "low"
            }
            results.add(SearchResult(
                sopId = r.sopId,
                originalTaskName = r.originalTaskName,
                taskName = r.taskName,
                appName = r.appName,
                source = r.source,
                difficulty = r.difficulty,
                domain = r.domain,
                keywords = r.keywords,
                steps = r.steps,
                score = score,
                confidence = confidence
            ))
        }
        results
    }

    private fun rrfFuse(
        a: List<Pair<String, Float>>,
        b: List<Pair<String, Float>>,
        limit: Int
    ): List<Pair<String, Float>> {
        val rank = HashMap<String, Float>()
        for ((i, pair) in a.withIndex())
            rank[pair.first] = (rank[pair.first] ?: 0f) + 1f / (RRF_K + i + 1)
        for ((i, pair) in b.withIndex())
            rank[pair.first] = (rank[pair.first] ?: 0f) + 1f / (RRF_K + i + 1)
        return rank.entries.sortedByDescending { it.value }
            .take(limit).map { it.key to it.value }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
