package com.palmagent.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.utils.BitmapPool
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * VLM 视觉描述与决策服务
 *
 * 通过 OpenAI 兼容 API 调用视觉语言模型（默认阿里云百炼 Qwen3-VL-Flash），
 * 对屏幕截图进行描述/问答/决策。
 *
 * 支持任意 OpenAI 兼容的 VLM 提供商（百炼 / 智谱 / SiliconFlow 等），
 * 通过 local.properties 中的 VLM_* 配置切换。
 */
object VlmService {

    private const val TAG = "VlmService"
    // Qwen3-VL 系列图片配置（阿里云百炼）：
    // - 模型支持动态分辨率，原生处理高分辨率图片，无需过度缩放
    // - 1080×2400 手机截图 → 长边 1568（保留约 65% 像素，小字清晰）
    // - JPEG quality 85：平衡质量与传输大小（约 80-120KB）
    // - VLM_SCALE=1.0 时 scaleBitmap 跳过客户端缩放，不增加额外开销
    private const val JPEG_QUALITY = 85
    private const val VLM_SCALE = 1.0f
    private const val MAX_IMAGE_DIMENSION = 1568

    data class VlmResult(
        val success: Boolean,
        val answer: String = "",
        val durationMs: Long = 0,
        val error: String? = null
    )

    /** VL 模式决策结果 */
    data class DecideResult(
        val success: Boolean,
        val content: String = "",
        val durationMs: Long = 0,
        val error: String? = null
    )

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 简化提示词：一个通用系统提示覆盖所有场景
    private const val SYSTEM_PROMPT = "You are a mobile screen analysis assistant. Answer questions about the screenshot concisely in the same language as the question."

    /** 屏幕视觉描述提示词（无障碍不可用时自动调用）
     *  优化：分上/中/下三区域描述，在保持速度的同时获取更完整的屏幕信息
     *  对比旧版"一句话50字"：信息量提升 ~3x，耗时仅增加 ~20%（结构化输出减少模型决策时间） */
    private const val SCREEN_DESC_PROMPT = """你是一个移动端屏幕分析助手。请将屏幕垂直分为上、中、下三部分，描述各区域的关键UI元素。

【输出格式】
上：<顶部区域>
中：<中部区域>
下：<底部区域>

【要求】
- 只描述可见的UI元素（按钮、输入框、列表项、图标、文本标签等），不要判断页面类型
- 每个区域控制在25字以内
- 如果某区域无UI元素，写"无"
- 只输出上述三行，不要任何额外内容

【示例输出】
上：搜索栏、小程序标签、"全部"按钮
中：i莞家小程序入口、"企业有难题"推荐、服务号列表
下：底部导航栏"微信"等图标
"""

    fun init(): Boolean {
        val apiUrl = KVUtils.getVlmApiUrl()
        if (apiUrl.isBlank()) {
            lastError = "LLM API地址未配置"
            Log.w(TAG, lastError!!)
            return false
        }
        isReady = true
        lastError = null
        Log.d(TAG, "VlmService初始化成功, API=$apiUrl, Model=${KVUtils.getVlmModelName()}")
        LiveLogBuffer.append("✓ VLM服务就绪 (${KVUtils.getVlmModelName()})")
        return true
    }

    /**
     * 一句话屏幕语义分析（无障碍不可用时自动调用）
     * 极速版：用一句话概括当前屏幕的核心内容和界面类型（耗时 ~2s）
     *
     * @param question 可选：屏幕描述外想额外确认的问题（如"当前界面是美团App吗？"）；
     *                 为 null/空时回退固定结构描述（上/中/下三区域）
     */
    suspend fun describeScreen(bitmap: Bitmap, question: String? = null): VlmResult {
        val q = question?.takeIf { it.isNotBlank() }
        return query(q ?: SCREEN_DESC_PROMPT, bitmap)
    }

    /**
     * 向 VLM 模型提问关于屏幕截图的问题
     *
     * @param question 关于屏幕的问题（如"有没有搜索按钮？"、"当前是什么页面？"）
     * @param bitmap 屏幕截图
     * @return VlmResult
     */
    suspend fun query(
        question: String,
        bitmap: Bitmap
    ): VlmResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (!isReady) {
            val msg = "VLM服务未就绪: ${lastError ?: "未初始化"}"
            Log.e(TAG, msg)
            return@withContext VlmResult(success = false, error = msg)
        }

        // 缩放图片以减少传输和推理时间
        val scaledBitmap = scaleBitmap(bitmap)
        val base64Image = try {
            compressAndEncode(scaledBitmap)
        } finally {
            // 流式释放：编码完成后归还到复用池，避免与原始 Bitmap 叠加占用
            if (scaledBitmap !== bitmap && !scaledBitmap.isRecycled) {
                BitmapPool.release(scaledBitmap)
            }
        }

        if (base64Image == null) {
            return@withContext VlmResult(success = false, error = "图片编码失败")
        }

        val apiUrl = normalizeUrl(KVUtils.getVlmApiUrl())
        val apiKey = KVUtils.getVlmApiKey()
        val modelName = KVUtils.getVlmModelName()

        if (apiKey.isEmpty()) {
            return@withContext VlmResult(success = false, error = "API Key未配置")
        }

        val messages = listOf(
            mapOf(
                "role" to "system",
                "content" to listOf(
                    mapOf("type" to "text", "text" to SYSTEM_PROMPT)
                )
            ),
            mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf("type" to "text", "text" to question),
                    mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf(
                            "url" to "data:image/jpeg;base64,$base64Image"
                        )
                    )
                )
            )
        )

        val requestMap = mapOf(
            "model" to modelName,
            "messages" to messages,
            "max_tokens" to 512,
            "temperature" to 0.1
        )

        val requestBody = JSONObject(requestMap).toString()
            .toRequestBody("application/json".toMediaType())

        var lastErrorMsg: String? = null
        val maxRetries = 2

        for (attempt in 1..maxRetries) {
            try {
                val httpRequest = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                Log.d(TAG, "[VLM]请求 (尝试$attempt/$maxRetries): ${question.take(50)}")
                LiveLogBuffer.append("🔍 VLM查询: ${question.take(40)}")

                val responseBody = client.newCall(httpRequest).execute().use { it.body?.string() }

                if (!responseBody.isNullOrBlank()) {
                    val content = parseOpenAIResponse(responseBody)
                    if (content.isNotBlank()) {
                        val duration = System.currentTimeMillis() - startTime
                        Log.d(TAG, "[VLM]成功 (${duration}ms): ${content.take(100)}")
                        LiveLogBuffer.append("✓ VLM查询成功 (${duration}ms)")
                        return@withContext VlmResult(
                            success = true,
                            answer = content.trim(),
                            durationMs = duration
                        )
                    } else {
                        lastErrorMsg = "VLM返回内容为空"
                        Log.e(TAG, "[VLM]失败(尝试$attempt): $lastErrorMsg")
                        if (attempt < maxRetries) delay(1000)
                    }
                } else {
                    lastErrorMsg = try {
                        val errorResp = JSONObject(responseBody ?: "")
                        val errorObj = errorResp.optJSONObject("error")
                        errorObj?.optString("message", "") ?: "响应为空"
                    } catch (_: Exception) {
                        (responseBody ?: "无响应").take(200)
                    }
                    Log.e(TAG, "[VLM]失败(尝试$attempt): $lastErrorMsg")
                    if (attempt < maxRetries) delay(1000)
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastErrorMsg = "请求超时: ${e.message}"
                Log.e(TAG, "[VLM]超时(尝试$attempt)")
                if (attempt < maxRetries) delay(1000)
            } catch (e: Exception) {
                lastErrorMsg = "网络错误: ${e.message}"
                Log.e(TAG, "[VLM]异常(尝试$attempt): ${e.message}")
                if (attempt < maxRetries) delay(1000)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        LiveLogBuffer.append("❌ VLM查询失败: $lastErrorMsg")
        return@withContext VlmResult(
            success = false,
            error = lastErrorMsg ?: "未知错误",
            durationMs = duration
        )
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        // 硬件加速 Bitmap（如 MediaProjection 截图）无法直接用于软件 Canvas 绘制，
        // 需先转换为软件 Bitmap，否则抛出 "Software rendering doesn't support hardware bitmaps"
        // 如果调用方已传入非HARDWARE的Bitmap（如ScreenDescriptor裁剪后的ARGB_8888），跳过copy
        val srcBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }

        var width = srcBitmap.width
        var height = srcBitmap.height
        if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(width, height)
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }
        // 缩放图片以控制 visual tokens 数量，Qwen3-VL 使用动态分辨率
        width = (width * VLM_SCALE).toInt().coerceAtLeast(120)
        height = (height * VLM_SCALE).toInt().coerceAtLeast(120)
        if (width == srcBitmap.width && height == srcBitmap.height) {
            return srcBitmap
        }
        // 使用 RGB_565 配置，内存减半（截图不需要透明度通道）
        // 通过 BitmapPool 复用，减少 GC 压力
        val scaled = BitmapPool.acquire(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(scaled)
        canvas.drawBitmap(
            srcBitmap,
            Rect(0, 0, srcBitmap.width, srcBitmap.height),
            Rect(0, 0, width, height),
            null
        )
        // 如果从硬件 Bitmap 复制了临时软件副本，缩放完成后释放
        if (srcBitmap !== bitmap && !srcBitmap.isRecycled) {
            srcBitmap.recycle()
        }
        return scaled
    }

    private fun compressAndEncode(bitmap: Bitmap): String? {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val byteArray = outputStream.toByteArray()
            Log.v(TAG, "图片编码: ${bitmap.width}x${bitmap.height}, ${byteArray.size / 1024}KB")
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "图片编码失败: ${e.message}")
            null
        }
    }

    private fun parseOpenAIResponse(responseBody: String): String {
        return try {
            val responseMap = JSONObject(responseBody)

            // 优先检查 error 字段（OpenAI 兼容错误响应格式）
            val errorObj = responseMap.optJSONObject("error")
            if (errorObj != null) {
                val errMsg = errorObj.optString("message", "未知错误")
                val errCode = errorObj.optString("code", "")
                Log.e(TAG, "API错误响应: code=$errCode, message=$errMsg")
                return ""
            }

            val choices = responseMap.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.w(TAG, "响应无 choices 字段, body=${responseBody.take(300)}")
                return ""
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message")
            if (message == null) {
                Log.w(TAG, "响应无 message 字段, finish_reason=${firstChoice.optString("finish_reason", "N/A")}")
                return ""
            }

            val content = message.optString("content", "")
            if (content.isBlank()) {
                val finishReason = firstChoice.optString("finish_reason", "N/A")
                Log.w(TAG, "content为空, finish_reason=$finishReason, body=${responseBody.take(300)}")
            }
            content
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败: ${e.message}, body=${responseBody.take(300)}")
            ""
        }
    }

    /**
     * 预热云端 API，消除首次调用冷启动延迟
     *
     * 发送带 1x1 像素占位图的请求，图片走与 query()/decide() 完全相同的处理管线
     * （scaleBitmap + compressAndEncode JPEG），
     * 同时预热 VL 模型的文本管道和图片处理管道。
     * 冷启动首次请求约 8s，预热后后续请求约 1-2s。
     * 在 AgentApplication.onCreate() 中异步调用。
     */
    fun warmUp() {
        if (!isReady) {
            Log.d(TAG, "预热跳过: 服务未就绪")
            return
        }

        val apiUrl = normalizeUrl(KVUtils.getVlmApiUrl())
        val apiKey = KVUtils.getVlmApiKey()
        val modelName = KVUtils.getVlmModelName()

        if (apiKey.isEmpty() || apiUrl.isBlank()) {
            Log.d(TAG, "预热跳过: API配置不完整")
            return
        }

        Thread({
            var warmUpBitmap: Bitmap? = null
            var scaledBitmap: Bitmap? = null
            try {
                // 创建 1x1 像素占位图，走与 query()/decide() 完全相同的处理管线
                warmUpBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
                scaledBitmap = scaleBitmap(warmUpBitmap)
                val base64Image = compressAndEncode(scaledBitmap!!)
                if (base64Image == null) {
                    Log.w(TAG, "预热失败: 图片编码失败")
                    return@Thread
                }

                val messages = listOf(
                    mapOf(
                        "role" to "system",
                        "content" to listOf(
                            mapOf("type" to "text", "text" to SYSTEM_PROMPT)
                        )
                    ),
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "text", "text" to "warmup"),
                            mapOf(
                                "type" to "image_url",
                                "image_url" to mapOf(
                                    "url" to "data:image/jpeg;base64,$base64Image"
                                )
                            )
                        )
                    )
                )

                val warmUpRequest = mapOf(
                    "model" to modelName,
                    "messages" to messages,
                    "max_tokens" to 1,
                    "temperature" to 0.0
                )

                val requestBody = JSONObject(warmUpRequest).toString()
                    .toRequestBody("application/json".toMediaType())

                val httpRequest = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val start = System.currentTimeMillis()
                client.newCall(httpRequest).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start

                    if (response.isSuccessful) {
                        Log.d(TAG, "预热成功(含图片): ${elapsed}ms")
                    } else {
                        Log.w(TAG, "预热返回非200: HTTP ${response.code}, ${elapsed}ms")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "预热失败(不影响后续使用): ${e.message}")
            } finally {
                warmUpBitmap?.recycle()
                if (scaledBitmap != null && scaledBitmap !== warmUpBitmap && !scaledBitmap.isRecycled) {
                    BitmapPool.release(scaledBitmap)
                }
            }
        }, "vlm-warmup").start()
    }

    /**
     * VL 决策：发送截图 + system prompt + user prompt 给 VLM 模型，
     * 返回模型的决策内容（JSON 格式的 AgentAction）。
     * 通过 OpenAI 兼容 API 调用，支持任意 VLM 提供商。
     */
    suspend fun decide(
        systemPrompt: String,
        userPrompt: String,
        screenshot: Bitmap
    ): DecideResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (!isReady) {
            return@withContext DecideResult(success = false, error = "VLM服务未就绪: ${lastError ?: "未初始化"}")
        }

        // 缩放图片
        val scaledBitmap = scaleBitmap(screenshot)
        val base64Image = try {
            compressAndEncode(scaledBitmap)
        } finally {
            if (scaledBitmap !== screenshot && !scaledBitmap.isRecycled) {
                BitmapPool.release(scaledBitmap)
            }
        }

        if (base64Image == null) {
            return@withContext DecideResult(success = false, error = "图片编码失败")
        }

        val apiUrl = normalizeUrl(KVUtils.getVlmApiUrl())
        val apiKey = KVUtils.getVlmApiKey()
        val modelName = KVUtils.getVlmModelName()

        if (apiKey.isEmpty()) {
            return@withContext DecideResult(success = false, error = "API Key未配置")
        }

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to listOf(
                mapOf("type" to "text", "text" to userPrompt),
                mapOf("type" to "image_url", "image_url" to mapOf(
                    "url" to "data:image/jpeg;base64,$base64Image"
                ))
            ))
        )

        // 移除 max_tokens 参数（百炼无硬限制，使用模型默认值）
        // 避免类似智谱 [1,1024] 的兼容性问题
        val requestMap = mapOf(
            "model" to modelName,
            "messages" to messages,
            "temperature" to 0.0,
            // JSON Mode：从采样阶段强制保证 JSON 语法合法
            "response_format" to mapOf("type" to "json_object")
        )

        val requestBody = JSONObject(requestMap).toString()
            .toRequestBody("application/json".toMediaType())

        var lastErrorMsg: String? = null
        val maxRetries = 2

        for (attempt in 1..maxRetries) {
            try {
                val httpRequest = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                Log.d(TAG, "[VLM-decide]请求 (尝试$attempt/$maxRetries) model=$modelName")
                LiveLogBuffer.append("🔍 VL决策[$modelName]: ${userPrompt.take(50)}")

                val response = client.newCall(httpRequest).execute()
                val httpCode = response.code
                val responseBody = response.use { it.body?.string() }

                if (httpCode != 200) {
                    val errorPreview = responseBody?.take(500) ?: "无响应体"
                    lastErrorMsg = "HTTP $httpCode: $errorPreview"
                    Log.e(TAG, "[VLM-decide]失败(尝试$attempt): HTTP $httpCode, body=$errorPreview")
                    LiveLogBuffer.append("❌ VL决策失败: HTTP $httpCode, body=${responseBody?.take(200) ?: "无"}")
                    if (attempt < maxRetries) delay(1000)
                    continue
                }

                if (responseBody.isNullOrBlank()) {
                    lastErrorMsg = "HTTP 200 但响应体为空"
                    Log.e(TAG, "[VLM-decide]失败(尝试$attempt): $lastErrorMsg")
                    LiveLogBuffer.append("❌ VL决策失败: $lastErrorMsg")
                    if (attempt < maxRetries) delay(1000)
                    continue
                }

                val content = parseOpenAIResponse(responseBody)
                if (content.isNotBlank()) {
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[VLM-decide]成功 (${duration}ms): ${content.take(100)}")
                    LiveLogBuffer.append("✓ VL决策成功 (${duration}ms)")
                    return@withContext DecideResult(
                        success = true,
                        content = content.trim(),
                        durationMs = duration
                    )
                } else {
                    lastErrorMsg = "VLM返回内容为空"
                    Log.e(TAG, "[VLM-decide]失败(尝试$attempt): $lastErrorMsg, body=${responseBody.take(500)}")
                    LiveLogBuffer.append("❌ VL决策失败: content为空, body=${responseBody.take(200)}")
                    if (attempt < maxRetries) delay(1000)
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastErrorMsg = "请求超时: ${e.message}"
                Log.e(TAG, "[VLM-decide]超时(尝试$attempt)")
                LiveLogBuffer.append("❌ VL决策超时(尝试$attempt)")
                if (attempt < maxRetries) delay(1000)
            } catch (e: Exception) {
                lastErrorMsg = "网络错误: ${e.message}"
                Log.e(TAG, "[VLM-decide]异常(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ VL决策异常: ${e.message}")
                if (attempt < maxRetries) delay(1000)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        LiveLogBuffer.append("❌ VL决策失败: $lastErrorMsg")
        return@withContext DecideResult(
            success = false,
            error = lastErrorMsg ?: "未知错误",
            durationMs = duration
        )
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