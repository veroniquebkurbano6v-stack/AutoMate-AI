package com.palmagent.app.kb

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 端侧资源加载器：首次启动从 assets 拷贝 ONNX 模型 + vocab 到 filesDir/kb/。
 * sop_raw 不拷贝，直接由 SopJsonLoader 从 assets 读取（节省内部存储）。
 */
class KbAssetLoader(private val context: Context) {
    companion object { private const val TAG = "KbAssetLoader" }

    private val destDir = File(context.filesDir, "kb")

    /** 确保模型与分词器文件就位，返回路径 */
    fun ensureAssets(): KbPaths {
        if (!destDir.exists()) destDir.mkdirs()
        // 仅拷贝模型与分词器（约 24MB），kb.db 由 LocalKbEngine 建库时生成
        val files = listOf("onnx/model_quantized.onnx", "vocab.txt")
        for (f in files) {
            val dest = File(destDir, f)
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                context.assets.open("kb/$f").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "拷贝 $f (${dest.length()} bytes)")
            }
        }
        return KbPaths(
            modelPath = File(destDir, "onnx/model_quantized.onnx").absolutePath,
            vocabPath = File(destDir, "vocab.txt").absolutePath,
            dbPath = File(destDir, "kb.db").absolutePath
        )
    }

    /** kb.db 是否已存在（已建过库） */
    fun isDbBuilt(): Boolean = File(destDir, "kb.db").exists()

    data class KbPaths(val modelPath: String, val vocabPath: String, val dbPath: String)
}
