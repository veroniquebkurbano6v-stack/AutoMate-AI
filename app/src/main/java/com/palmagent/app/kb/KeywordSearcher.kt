package com.palmagent.app.kb

/**
 * 内存关键词检索：对 task_name / keywords / app_name / original_task_name
 * 做子串匹配 + 命中次数打分。≥2 字词才参与（对齐原 FTS5 trigram 思路）。
 */
class KeywordSearcher(private val records: List<SopRecord>) {

    fun search(query: String, appFilter: String? = null, limit: Int = 50): List<Pair<String, Float>> {
        val terms = query.trim().split(Regex("[\\s，。、；：！？/]+"))
            .map { it.trim() }.filter { it.length >= 2 }
        if (terms.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<String, Float>>()
        for (r in records) {
            if (appFilter != null && !r.appName.contains(appFilter)) continue
            val text = buildString {
                append(r.taskName); append(' ')
                append(r.keywords.joinToString(" ")); append(' ')
                append(r.appName); append(' ')
                append(r.originalTaskName)
            }
            var score = 0f
            for (t in terms) {
                var idx = 0
                while (true) {
                    val pos = text.indexOf(t, idx, ignoreCase = true)
                    if (pos < 0) break
                    score += 1f
                    idx = pos + t.length
                }
            }
            if (score > 0) scored.add(r.sopId to score)
        }
        scored.sortByDescending { it.second }
        return scored.take(limit)
    }
}
