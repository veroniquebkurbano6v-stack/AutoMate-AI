package com.palmagent.app.kb

/** 端侧知识库数据模型 */

data class SopStep(
    val stepRef: String,
    val stepOrder: Int,
    val goal: String,
    val expected: String,
    val actionType: String
)

data class SopRecord(
    val sopId: String,
    val originalTaskName: String,
    val taskName: String,
    val appName: String,
    val source: String?,
    val difficulty: String?,
    val domain: String?,
    val keywords: List<String>,
    val steps: List<SopStep>,
    val taskVec: FloatArray,
    val kwVec: FloatArray?
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = sopId.hashCode()
}

data class SearchResult(
    val sopId: String,
    val originalTaskName: String,
    val taskName: String,
    val appName: String,
    val source: String?,
    val difficulty: String?,
    val domain: String?,
    val keywords: List<String>,
    val steps: List<SopStep>,
    val score: Double,
    val confidence: String   // high / medium / low
)
