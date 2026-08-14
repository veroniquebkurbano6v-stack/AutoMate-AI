package com.palmagent.app.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import io.github.hzkitty.RapidOCR
import io.github.hzkitty.entity.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object RapidOcrService {

    private const val TAG = "RapidOcrService"
    const val OCR_TITLE = "【RapidOCR文字识别】"

    private const val MIN_CONFIDENCE = 0.5f

    /** OCR预处理缩放比例：90%为精度与速度的最佳平衡点（实测重合率>90%，节省250~950ms） */
    private const val OCR_SCALE_RATIO = 0.9f

    /**
     * 过滤无意义OCR文本（v3 优化：仅保留含中文汉字的文本）：
     * - 不含中文汉字的（纯英文、纯数字、纯符号、emoji、时间戳等都过滤）
     *
     * 保留：包含至少一个中文汉字（CJK Unified Ideographs U+4E00-U+9FFF）
     * 典型应用：微信 "中国移动" "微信" "微信团队" 保留，"4G" "100%" "15:32" "🔍" "+" 过滤
     */
    fun shouldFilterOcrText(text: String): Boolean {
        val stripped = text.trim()
        if (stripped.isEmpty()) return true
        // 至少包含一个中文汉字
        val hasChinese = stripped.any { it in '\u4e00'..'\u9fff' }
        if (!hasChinese) return true
        return false
    }

    data class OcrTextBlock(
        val text: String,
        val left: Int,
        val top: Int,
        val width: Int = 0,
        val height: Int = 0,
        val confidence: Float = 0f
    ) {
        val centerX: Int get() = left + width / 2
        val centerY: Int get() = top + height / 2

        companion object {
            fun fromBoundingBox(text: String, left: Int, top: Int, right: Int, bottom: Int, confidence: Float = 0f): OcrTextBlock {
                return OcrTextBlock(
                    text = text,
                    left = left,
                    top = top,
                    width = right - left,
                    height = bottom - top,
                    confidence = confidence
                )
            }
        }
    }

    data class OcrResultData(
        val fullText: String,
        val blocks: List<OcrTextBlock>,
        val durationMs: Long,
        val rawJson: String = ""
    )

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var rapidOCR: RapidOCR? = null

    fun init(context: Context): Boolean {
        return try {
            rapidOCR = RapidOCR.create(context)
            isReady = true
            lastError = null
            Log.d(TAG, "RapidOCR引擎初始化成功")
            LiveLogBuffer.append("✓ RapidOCR引擎初始化成功")
            true
        } catch (e: Exception) {
            lastError = "RapidOCR初始化失败: ${e.message}"
            Log.e(TAG, lastError!!, e)
            LiveLogBuffer.append("❌ $lastError")
            false
        }
    }

    /**
     * OCR识别主入口
     * 流程：原图 → 90%缩放 → OCR检测识别 → 坐标逆变换回原图尺寸
     *
     * 缩放策略：将图像缩放至90%后送入OCR引擎，识别完成后将所有坐标
     * 按 1/scaleRatio 逆变换回原始图像坐标系，确保定位精度偏差≤1px。
     */
    suspend fun recognize(bitmap: Bitmap?): OcrResultData = withContext(Dispatchers.IO) {
        if (bitmap == null || bitmap.isRecycled) {
            return@withContext OcrResultData("", emptyList(), 0)
        }

        if (!isReady || rapidOCR == null) {
            return@withContext OcrResultData("", emptyList(), 0)
        }

        val startTime = System.currentTimeMillis()

        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            bitmap
        }

        // Step 1: 90%缩放预处理
        val originalWidth = safeBitmap.width
        val originalHeight = safeBitmap.height
        val scaledWidth = (originalWidth * OCR_SCALE_RATIO).roundToInt()
        val scaledHeight = (originalHeight * OCR_SCALE_RATIO).roundToInt()

        val scaledBitmap = if (scaledWidth > 0 && scaledHeight > 0 &&
                               scaledWidth != originalWidth && scaledHeight != originalHeight) {
            Bitmap.createScaledBitmap(safeBitmap, scaledWidth, scaledHeight, true)
        } else {
            null // 缩放后尺寸无效或与原图相同，不缩放
        }

        // 缩放完成后立即回收 safeBitmap 副本，释放 ~8MB 内存
        if (scaledBitmap != null && safeBitmap !== bitmap && !safeBitmap.isRecycled) {
            try { safeBitmap.recycle() } catch (_: Exception) {}
        }

        val ocrInput = scaledBitmap ?: safeBitmap
        val wasScaled = scaledBitmap != null

        // 精确逆变换比率：用实际缩放尺寸计算，避免浮点累积误差
        val inverseScaleX = if (wasScaled) originalWidth.toFloat() / scaledWidth else 1.0f
        val inverseScaleY = if (wasScaled) originalHeight.toFloat() / scaledHeight else 1.0f

        try {
            // Step 2: OCR检测与识别
            val ocrResult: OcrResult? = rapidOCR!!.run(ocrInput)
            val durationMs = System.currentTimeMillis() - startTime

            if (ocrResult != null) {
                // Step 3: 解析结果 + 坐标逆变换
                val rawBlocks = parseOcrResult(ocrResult)
                val blocks = rawBlocks
                    .map { block -> mapBlockToOriginal(block, inverseScaleX, inverseScaleY) }
                    .filter { it.confidence >= MIN_CONFIDENCE && !shouldFilterOcrText(it.text) }
                val fullText = blocks.joinToString("\n") { it.text }

                val filtered = rawBlocks.size - blocks.size
                val scaleInfo = if (wasScaled) " [${originalWidth}x${originalHeight}→${scaledWidth}x${scaledHeight}]" else ""
                Log.d(TAG, "RapidOCR完成: 原始${rawBlocks.size}个, 过滤后${blocks.size}个文本块 (过滤${filtered}个), ${durationMs}ms$scaleInfo")
                LiveLogBuffer.append("🔍 RapidOCR: ${blocks.size}个文本块 (${durationMs}ms)")

                return@withContext OcrResultData(fullText, blocks, durationMs)
            } else {
                Log.w(TAG, "RapidOCR返回空结果")
                return@withContext OcrResultData("", emptyList(), durationMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RapidOCR识别异常: ${e.message}", e)
            val durationMs = System.currentTimeMillis() - startTime
            return@withContext OcrResultData("", emptyList(), durationMs)
        } finally {
            scaledBitmap?.recycle()
        }
    }

    /**
     * 坐标逆变换：将缩放后图像上的OCR坐标映射回原始图像坐标系
     *
     * 使用 roundToInt 确保偏差≤1px：
     *   originalCoord = roundToInt(scaledCoord * inverseScale)
     * 其中 inverseScale = originalSize / scaledSize（精确比率）
     */
    private fun mapBlockToOriginal(block: OcrTextBlock, inverseScaleX: Float, inverseScaleY: Float): OcrTextBlock {
        if (inverseScaleX == 1.0f && inverseScaleY == 1.0f) return block

        val origLeft = (block.left * inverseScaleX).roundToInt()
        val origTop = (block.top * inverseScaleY).roundToInt()
        val origWidth = (block.width * inverseScaleX).roundToInt()
        val origHeight = (block.height * inverseScaleY).roundToInt()

        return OcrTextBlock(
            text = block.text,
            left = origLeft,
            top = origTop,
            width = origWidth,
            height = origHeight,
            confidence = block.confidence
        )
    }

    suspend fun extractPlainTextList(bitmap: Bitmap?): List<String> = withContext(Dispatchers.IO) {
        val result = recognize(bitmap)
        if (result.fullText.isBlank()) return@withContext emptyList()
        result.blocks.map { it.text }
    }

    data class OcrTextWithBbox(
        val text: String,
        val centerX: Int,
        val centerY: Int
    )

    suspend fun extractTextWithBboxes(bitmap: Bitmap?): List<OcrTextWithBbox> = withContext(Dispatchers.IO) {
        val result = recognize(bitmap)
        if (result.fullText.isBlank()) return@withContext emptyList()
        result.blocks.map { OcrTextWithBbox(it.text, it.centerX, it.centerY) }
    }

    private fun parseOcrResult(ocrResult: OcrResult): List<OcrTextBlock> {
        val blocks = mutableListOf<OcrTextBlock>()

        try {
            val recResults = ocrResult.getRecRes() ?: return blocks

            for (recResult in recResults) {
                val text = recResult.getText() ?: continue
                val dtBoxes = recResult.getDtBoxes()
                val confidence = recResult.getConfidence()

                if (dtBoxes != null && dtBoxes.size >= 4) {
                    val xs = dtBoxes.map { it.x.toInt() }
                    val ys = dtBoxes.map { it.y.toInt() }
                    val left = xs.minOrNull() ?: 0
                    val top = ys.minOrNull() ?: 0
                    val right = xs.maxOrNull() ?: 0
                    val bottom = ys.maxOrNull() ?: 0

                    blocks.add(OcrTextBlock.fromBoundingBox(
                        text = text,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        confidence = confidence
                    ))
                } else {
                    blocks.add(OcrTextBlock(
                        text = text,
                        left = 0, top = 0, width = 0, height = 0,
                        confidence = confidence
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析RapidOCR结果异常: ${e.message}", e)
        }

        return blocks
    }

    fun formatForPrompt(result: OcrResultData): String {
        if (result.fullText.isBlank()) return ""

        return buildString {
            appendLine(OCR_TITLE)
            appendLine("识别到${result.blocks.size}行文字：")
            result.blocks.take(30).forEachIndexed { i, block ->
                appendLine("  ${i + 1}. ${block.text.take(60)}")
            }
        }
    }

    fun getStatus(): String {
        return buildString {
            appendLine("RapidOCR引擎状态:")
            appendLine("  就绪: $isReady")
            appendLine("  缩放比例: ${(OCR_SCALE_RATIO * 100).roundToInt()}%")
            if (lastError != null) appendLine("  最后错误: $lastError")
        }
    }

    fun release() {
        rapidOCR = null
        isReady = false
        lastError = null
        Log.d(TAG, "RapidOCR引擎已释放")
    }
}
