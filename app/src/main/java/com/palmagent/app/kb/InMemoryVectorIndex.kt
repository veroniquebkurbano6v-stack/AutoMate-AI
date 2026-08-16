package com.palmagent.app.kb

/**
 * 内存向量索引：全量加载后暴力余弦检索。
 * 514 条 × 512 维，单次检索 <5ms，无需 ANN 索引。
 * task 向量 0.7 + keyword 向量 0.3 加权融合。
 */
class InMemoryVectorIndex(
    private val records: List<SopRecord>,
    private val wTask: Float = 0.7f,
    private val wKw: Float = 0.3f
) {
    fun search(qvec: FloatArray, appFilter: String? = null, limit: Int = 50): List<Pair<String, Float>> {
        val scored = ArrayList<Pair<String, Float>>(records.size)
        for (r in records) {
            if (appFilter != null && !r.appName.contains(appFilter)) continue
            val ts = cosine(qvec, r.taskVec)
            val ks = r.kwVec?.let { cosine(qvec, it) } ?: 0f
            scored.add(r.sopId to (wTask * ts + wKw * ks))
        }
        scored.sortByDescending { it.second }
        return scored.take(limit)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum  // 向量已 L2 归一化，点积 = 余弦相似度
    }
}
