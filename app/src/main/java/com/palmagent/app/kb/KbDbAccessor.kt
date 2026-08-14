package com.palmagent.app.kb

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * kb.db 访问层（纯 Android SQLite，无任何 native 扩展依赖）。
 * - buildIndex(): 首次启动端侧建库（createTables + 批量插入 SOP/steps/向量）
 * - loadAll(): 读取全量数据到内存供检索
 *
 * 向量以 float32 BLOB 存储，读取时解包为 FloatArray。
 */
class KbDbAccessor(private val dbPath: String) {

    companion object {
        private const val TAG = "KbDbAccessor"
        private const val EMBED_DIM = 512
        private const val BUILD_VERSION = "ondevice-bge-small-zh-v1.5"
    }

    private fun open(writable: Boolean): SQLiteDatabase {
        val flags = if (writable) SQLiteDatabase.CREATE_IF_NECESSARY
                    else SQLiteDatabase.OPEN_READONLY
        return SQLiteDatabase.openDatabase(dbPath, null, flags)
    }

    // ==================== 建库（首次启动，端侧嵌入后写入） ====================

    fun buildIndex(chunks: List<SopChunk>, taskVecs: List<FloatArray>,
                   kwVecs: Map<String, FloatArray>) {
        // 删除旧库重建
        File(dbPath).delete()
        val db = open(true)
        db.beginTransaction()
        try {
            createTables(db)
            val now = System.currentTimeMillis() / 1000

            for ((i, c) in chunks.withIndex()) {
                // kb_sop
                val sopCv = ContentValues().apply {
                    put("sop_id", c.sopId)
                    put("original_task_name", c.originalTaskName)
                    put("task_name", c.taskName)
                    put("app_name", c.appName)
                    put("source", c.source)
                    put("difficulty", c.difficulty)
                    put("domain", c.domain)
                    put("keywords", c.keywords.joinToString(","))
                    put("updated_at", now)
                }
                db.insertWithOnConflict("kb_sop", null, sopCv,
                    SQLiteDatabase.CONFLICT_REPLACE)

                // kb_vectors_task
                val taskCv = ContentValues().apply {
                    put("sop_id", c.sopId)
                    put("embedding", vecToBlob(taskVecs[i]))
                }
                db.insertWithOnConflict("kb_vectors_task", null, taskCv,
                    SQLiteDatabase.CONFLICT_REPLACE)

                // kb_vectors_keyword（可能为空）
                kwVecs[c.sopId]?.let { kv ->
                    val kwCv = ContentValues().apply {
                        put("sop_id", c.sopId)
                        put("embedding", vecToBlob(kv))
                    }
                    db.insertWithOnConflict("kb_vectors_keyword", null, kwCv,
                        SQLiteDatabase.CONFLICT_REPLACE)
                }

                // kb_steps
                for (st in c.steps) {
                    val stepCv = ContentValues().apply {
                        put("step_ref", st.stepRef)
                        put("sop_id", c.sopId)
                        put("step_order", st.stepOrder)
                        put("goal", st.goal)
                        put("expected", st.expected)
                        put("action_type", st.actionType)
                    }
                    db.insertWithOnConflict("kb_steps", null, stepCv,
                        SQLiteDatabase.CONFLICT_REPLACE)
                }
            }

            // 元信息
            val metaCv = ContentValues().apply {
                put("key", "embed_dim"); put("value", EMBED_DIM.toString())
            }
            db.insertWithOnConflict("kb_meta", null, metaCv, SQLiteDatabase.CONFLICT_REPLACE)
            val verCv = ContentValues().apply {
                put("key", "build_version"); put("value", BUILD_VERSION)
            }
            db.insertWithOnConflict("kb_meta", null, verCv, SQLiteDatabase.CONFLICT_REPLACE)
            val cntCv = ContentValues().apply {
                put("key", "sop_count"); put("value", chunks.size.toString())
            }
            db.insertWithOnConflict("kb_meta", null, cntCv, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
            Log.i(TAG, "端侧建库完成：${chunks.size} 条 SOP")
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kb_meta (
                key TEXT PRIMARY KEY, value TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kb_sop (
                sop_id TEXT PRIMARY KEY,
                original_task_name TEXT NOT NULL DEFAULT '',
                task_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                source TEXT, difficulty TEXT, domain TEXT,
                keywords TEXT NOT NULL DEFAULT ',',
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sop_app ON kb_sop(app_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sop_domain ON kb_sop(domain)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kb_steps (
                step_ref TEXT PRIMARY KEY,
                sop_id TEXT NOT NULL,
                step_order INTEGER NOT NULL,
                goal TEXT NOT NULL DEFAULT '',
                expected TEXT NOT NULL DEFAULT '',
                action_type TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_steps_sop ON kb_steps(sop_id)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kb_vectors_task (
                sop_id TEXT PRIMARY KEY, embedding BLOB NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kb_vectors_keyword (
                sop_id TEXT PRIMARY KEY, embedding BLOB NOT NULL
            )
        """.trimIndent())
    }

    // ==================== 读取（检索时加载到内存） ====================

    fun loadAll(): List<SopRecord> {
        val db = open(false)
        try {
            val sopRows = mutableListOf<SopMeta>()
            db.rawQuery(
                "SELECT sop_id, original_task_name, task_name, app_name, source, " +
                "difficulty, domain, keywords FROM kb_sop", null
            ).use { c ->
                while (c.moveToNext()) {
                    sopRows.add(SopMeta(
                        c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getString(6), c.getString(7)
                    ))
                }
            }
            Log.i(TAG, "加载 ${sopRows.size} 条 SOP 元数据")

            val stepsMap = HashMap<String, MutableList<SopStep>>()
            db.rawQuery(
                "SELECT sop_id, step_ref, step_order, goal, expected, action_type " +
                "FROM kb_steps ORDER BY sop_id, step_order", null
            ).use { c ->
                while (c.moveToNext()) {
                    val sopId = c.getString(0)
                    val list = stepsMap.getOrPut(sopId) { mutableListOf() }
                    list.add(SopStep(c.getString(1), c.getInt(2), c.getString(3),
                                     c.getString(4), c.getString(5)))
                }
            }

            val taskVecs = HashMap<String, FloatArray>()
            db.rawQuery("SELECT sop_id, embedding FROM kb_vectors_task", null).use { c ->
                while (c.moveToNext()) taskVecs[c.getString(0)] = blobToVec(c.getBlob(1))
            }
            val kwVecs = HashMap<String, FloatArray>()
            db.rawQuery("SELECT sop_id, embedding FROM kb_vectors_keyword", null).use { c ->
                while (c.moveToNext()) kwVecs[c.getString(0)] = blobToVec(c.getBlob(1))
            }

            val records = ArrayList<SopRecord>(sopRows.size)
            for (m in sopRows) {
                val tv = taskVecs[m.sopId] ?: continue
                records.add(SopRecord(
                    sopId = m.sopId,
                    originalTaskName = m.originalTaskName,
                    taskName = m.taskName,
                    appName = m.appName,
                    source = m.source,
                    difficulty = m.difficulty,
                    domain = m.domain,
                    keywords = m.keywords.split(",").filter { it.isNotEmpty() },
                    steps = stepsMap[m.sopId] ?: emptyList(),
                    taskVec = tv,
                    kwVec = kwVecs[m.sopId]
                ))
            }
            Log.i(TAG, "加载完成：${records.size} 条（含向量）")
            return records
        } finally {
            db.close()
        }
    }

    private fun blobToVec(blob: ByteArray): FloatArray {
        // 动态维度：从 BLOB 长度推断（float32 = 4 字节/维）
        val dim = blob.size / 4
        val vec = FloatArray(dim)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vec)
        return vec
    }

    private fun vecToBlob(vec: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(vec)
        return bb.array()
    }

    private data class SopMeta(
        val sopId: String, val originalTaskName: String, val taskName: String,
        val appName: String, val source: String?, val difficulty: String?,
        val domain: String?, val keywords: String
    )
}
