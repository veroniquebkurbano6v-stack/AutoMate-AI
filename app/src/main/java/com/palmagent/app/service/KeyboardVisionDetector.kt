package com.palmagent.app.service

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 键盘弹出视觉检测服务
 *
 * 基于智谱 GLM-4V-Flash 视觉模型检测键盘是否弹出。
 * 优化策略：截取屏幕下半50% + 75%缩放 + 极简提示词，推理约2s。
 *
 * 使用场景：AutoInputTool 点击输入框后验证键盘是否弹出
 */
object KeyboardVisionDetector {

    private const val TAG = "KeyboardVision"

    // 下半屏裁剪比例
    private const val CROP_RATIO = 0.5f
    // 缩放比例
    private const val SCALE_FACTOR = 0.75f
    // JPEG 压缩质量
    private const val JPEG_QUALITY = 30

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(15000, TimeUnit.MILLISECONDS)
        .writeTimeout(10000, TimeUnit.MILLISECONDS)
        .build()

    data class KeyboardDetectionResult(
        val keyboardVisible: Boolean,
        val keyboardType: String = "none",
        val confidence: Float = 0f,
        val durationMs: Long = 0,
        val error: String? = null
    )

    /** 极简提示词：只问是否为键盘 */
    private const val KEYBOARD_PROMPT = "这是手机键盘吗？回答是或否"

    /**
     * 检测键盘是否弹出
     *
     * @param screenshot 全屏截图
     * @return KeyboardDetectionResult
     */
    suspend fun detectKeyboard(screenshot: Bitmap): KeyboardDetectionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val apiUrl = KVUtils.getKeyboardVlmApiUrl()
        val apiKey = KVUtils.getKeyboardVlmApiKey()
        val modelName = KVUtils.getKeyboardVlmModelName()

        if (apiUrl.isBlank() || apiKey.isBlank()) {
            return@withContext KeyboardDetectionResult(
                keyboardVisible = false,
                error = "VLM未配置(API地址或Key为空)",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // 裁剪下半屏 + 缩放
        val processedBitmap = cropAndScale(screenshot)
        val base64Image = compressAndEncode(processedBitmap)

        if (processedBitmap !== screenshot) {
            try { processedBitmap.recycle() } catch (_: Exception) {}
        }

        if (base64Image == null) {
            return@withContext KeyboardDetectionResult(
                keyboardVisible = false,
                error = "图片编码失败",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // 构造 VLM 请求（极简提示词）
        val messages = listOf(
            mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf("type" to "text", "text" to KEYBOARD_PROMPT),
                    mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64Image")
                    )
                )
            )
        )

        val requestMap = mapOf(
            "model" to modelName,
            "messages" to messages,
            "max_tokens" to 16,
            "temperature" to 0.0
        )

        val requestBody = JSONObject(requestMap).toString()
            .toRequestBody("application/json".toMediaType())

        val chatUrl = normalizeUrl(apiUrl)

        val httpRequest = Request.Builder()
            .url(chatUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            val responseBody = client.newCall(httpRequest).execute().use { it.body?.string() }

            if (responseBody.isNullOrBlank()) {
                val duration = System.currentTimeMillis() - startTime
                Log.w(TAG, "键盘检测API失败: 响应为空")
                return@withContext KeyboardDetectionResult(
                    keyboardVisible = false,
                    error = "响应为空",
                    durationMs = duration
                )
            }

            val content = parseOpenAIResponse(responseBody)
            val duration = System.currentTimeMillis() - startTime

            if (content.isBlank()) {
                return@withContext KeyboardDetectionResult(
                    keyboardVisible = false,
                    error = "模型返回空内容",
                    durationMs = duration
                )
            }

            // 解析极简回答：是/否
            val visible = parseSimpleAnswer(content)
            Log.d(TAG, "键盘检测(VLM): visible=$visible, 回答='${content.take(50)}', ${duration}ms")
            LiveLogBuffer.append("⌨️ 键盘检测(VLM): ${if (visible) "已弹出" else "未弹出"} (${duration}ms)")

            return@withContext KeyboardDetectionResult(
                keyboardVisible = visible,
                keyboardType = if (visible) "qwerty" else "none",
                confidence = if (visible) 0.9f else 0.9f,
                durationMs = duration
            )

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "键盘检测异常: ${e.message}")
            return@withContext KeyboardDetectionResult(
                keyboardVisible = false,
                error = e.message,
                durationMs = duration
            )
        }
    }

    /**
     * 解析极简回答：是/否
     * 支持中英文回答
     */
    private fun parseSimpleAnswer(content: String): Boolean {
        val lower = content.lowercase().trim()
        // 中文"是"
        if (lower.contains("是") && !lower.contains("不是") && !lower.contains("否")) return true
        // 英文 yes
        if (lower.contains("yes") && !lower.contains("no")) return true
        // true
        if (lower.contains("true")) return true
        // 中文"否"/"不是"/"没有"
        if (lower.contains("否") || lower.contains("不是") || lower.contains("没有") || lower.contains("no") || lower.contains("false")) return false
        // 默认否
        return false
    }

    /**
     * 裁剪下半屏 + 缩放
     */
    private fun cropAndScale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        // 裁剪下半50%
        val cropTop = (h * CROP_RATIO).toInt()
        val cropped = if (cropTop < h) {
            Bitmap.createBitmap(bitmap, 0, cropTop, w, h - cropTop)
        } else {
            bitmap
        }

        // 缩放75%
        val newW = (cropped.width * SCALE_FACTOR).toInt()
        val newH = (cropped.height * SCALE_FACTOR).toInt()

        return if (newW > 0 && newH > 0 && (newW != cropped.width || newH != cropped.height)) {
            Bitmap.createScaledBitmap(cropped, newW, newH, true)
        } else {
            cropped
        }
    }

    /**
     * 压缩并编码为 Base64
     */
    private fun compressAndEncode(bitmap: Bitmap): String? {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val byteArray = outputStream.toByteArray()
            Log.d(TAG, "键盘检测图片: ${bitmap.width}x${bitmap.height}, ${byteArray.size / 1024}KB")
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "图片编码失败: ${e.message}")
            null
        }
    }

    private fun parseOpenAIResponse(responseBody: String): String {
        return try {
            val responseMap = JSONObject(responseBody)
            val choices = responseMap.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                message?.optString("content", "") ?: ""
            } else ""
        } catch (e: Exception) {
            Log.e(TAG, "解析OpenAI响应失败: ${e.message}")
            ""
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }
}
