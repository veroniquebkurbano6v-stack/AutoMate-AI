package com.palmagent.app.service

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * VLM 图片配置集成测试（使用真实 Qwen-VL API）
 *
 * 验证目标：
 * 1. VLM 能正确识别微信主界面截图内容
 * 2. 验证 VLM 对截图文字的识别能力（间接验证 50%+JPEG70 配置的合理性）
 *
 * 注意：VlmService 内部使用 VLM_SCALE=1.0f + JPEG_QUALITY=85 进行图片预处理，
 * 此处直接发送原始 JPEG 给 VLM API（服务端会自动 smart_resize），
 * 验证 VLM 模型本身对微信截图的识别能力。
 *
 * 前置条件（API 不可达时自动跳过）：
 * - local.properties 中配置 VLM_API_URL / VLM_API_KEY / VLM_MODEL
 * - 测试图片：d:/Android/project/1/PalmAgent/log/微信图片_20260724143038_260_2.jpg
 *
 * 运行示例：
 *   .\gradlew.bat :app:testDebugUnitTest --tests "com.palmagent.app.service.VlmImageConfigIntegrationTest"
 */
class VlmImageConfigIntegrationTest {

    private val apiUrl: String? by lazy {
        System.getenv("VLM_API_URL")?.takeIf { it.isNotBlank() }
            ?: readLocalProperty("VLM_API_URL")
    }
    private val apiKey: String? by lazy {
        System.getenv("VLM_API_KEY")?.takeIf { it.isNotBlank() }
            ?: readLocalProperty("VLM_API_KEY")
    }
    private val model: String by lazy {
        System.getenv("VLM_MODEL")?.takeIf { it.isNotBlank() }
            ?: readLocalProperty("VLM_MODEL")
            ?: "qwen3-vl-flash"
    }

    private val testImage: File? by lazy {
        listOfNotNull(
            System.getenv("VLM_TEST_IMAGE")?.let { File(it) },
            File("log/微信图片_20260724143038_260_2.jpg"),
            File("../log/微信图片_20260724143038_260_2.jpg"),
            File("D:/Android/project/1/PalmAgent/log/微信图片_20260724143038_260_2.jpg")
        ).firstOrNull { it.exists() && it.isFile }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        println("[setUp] 工作目录: ${File(".").absolutePath}")
        println("[setUp] apiUrl: ${apiUrl ?: "[未配置]"}")
        println("[setUp] model: $model")
        println("[setUp] testImage: ${testImage?.absolutePath ?: "[未找到]"}")

        assumeTrue(
            "VLM API 未配置（需在 local.properties 中设置 VLM_API_URL / VLM_API_KEY），跳过测试",
            !apiUrl.isNullOrBlank() && !apiKey.isNullOrBlank()
        )
        assumeTrue(
            "测试图片不存在（查找路径: log/微信图片_20260724143038_260_2.jpg 或 D:/Android/project/1/PalmAgent/log/...）",
            testImage != null && testImage!!.exists()
        )
    }

    /**
     * 核心测试：VLM 能正确识别微信主界面截图
     *
     * VlmService 使用 VLM_SCALE=1.0f + JPEG_QUALITY=85 配置（基于 Qwen3-VL
     * 动态分辨率的最优配置），1080×2400 手机截图 → 长边 1568（约 706×1568），
     * 汉字约 39-45 像素清晰可读，~2200 视觉 tokens。
     */
    @Test
    fun `vlm_correctly_identifies_wechat_home_screen`() {
        val imageBytes = testImage!!.readBytes()
        val imageKb = imageBytes.size / 1024
        val (width, height) = getJpegSize(testImage!!)

        println("[测试] 原图: ${width}x${height}, ${imageKb}KB")
        println("[测试] VlmService 配置: VLM_SCALE=1.0f, JPEG_QUALITY=85")
        println("[测试] 1568长边缩放后预期: ${(1568.0 / maxOf(width, height) * width).toInt()}x${(1568.0 / maxOf(width, height) * height).toInt()}, ~2200视觉tokens")

        // 调用 VLM（直接发送原图，服务端自动 smart_resize）
        val prompt = "请描述这张截图的内容。这是什么应用的界面？你看到了哪些文字和按钮？"
        val result = callVlm(prompt, imageBytes)

        println("[测试] VLM响应 (${result.durationMs}ms):")
        println(result.content)

        assertTrue(
            "VLM 调用应成功: ${result.error}",
            result.success
        )
        assertTrue(
            "VLM 应识别出这是微信界面（响应中应包含'微信'或'WeChat'）: \n${result.content}",
            result.content.contains("微信") || result.content.contains("WeChat", ignoreCase = true)
        )
    }

    /**
     * 验证 VLM 能识别截图中的具体文字内容
     */
    @Test
    fun `vlm_can_read_text_from_screenshot`() {
        val imageBytes = testImage!!.readBytes()

        val prompt = "请列出这张截图中你看到的所有文字内容，逐行列出。"
        val result = callVlm(prompt, imageBytes)

        println("[测试] VLM文字识别 (${result.durationMs}ms):")
        println(result.content)

        assertTrue("VLM 调用应成功: ${result.error}", result.success)
        // VLM 应返回非空内容，且长度至少 20 字符（微信界面至少有"微信"等文字）
        assertTrue(
            "VLM 应识别出截图中的文字（响应长度应 > 20字符）: \n${result.content}",
            result.content.length > 20
        )
    }

    // ============ 辅助方法 ============

    private data class VlmResult(
        val success: Boolean,
        val content: String,
        val durationMs: Long,
        val error: String?
    )

    private fun callVlm(prompt: String, jpegBytes: ByteArray): VlmResult {
        val base64 = Base64.getEncoder().encodeToString(jpegBytes)
        val dataUrl = "data:image/jpeg;base64,$base64"

        // 使用 JSONObject 构建请求体，避免手动拼接 JSON 的转义问题
        val payload = org.json.JSONObject().apply {
            put("model", model)
            put("max_tokens", 500)
            put("temperature", 0.1)
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(org.json.JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", org.json.JSONObject().apply {
                                put("url", dataUrl)
                            })
                        })
                    })
                })
            })
        }.toString()

        val t0 = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url(if (apiUrl!!.endsWith("/chat/completions")) apiUrl!! else "$apiUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val httpMs = System.currentTimeMillis() - t0

            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(300) ?: ""
                return VlmResult(false, "", httpMs, "HTTP ${response.code}: $errBody")
            }

            val body = response.body?.string() ?: ""
            val content = extractContent(body)
            VlmResult(true, content, httpMs, null)
        } catch (e: Exception) {
            VlmResult(false, "", System.currentTimeMillis() - t0, "异常: ${e.message}")
        }
    }

    private fun extractContent(body: String): String {
        return try {
            val json = org.json.JSONObject(body)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                message?.optString("content", "")?.trim() ?: ""
            } else ""
        } catch (e: Exception) {
            body.take(500)
        }
    }

    /**
     * 从 JPEG 字节流解析图片尺寸（解析 SOF0/SOF2 标记）
     */
    private fun getJpegSize(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        require(bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            "非 JPEG 文件: ${file.name}"
        }
        var i = 2
        while (i < bytes.size - 1) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xC3) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return width to height
            }
            if (marker == 0xD8 || marker == 0xD9) { i += 2; continue }
            val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            i += 2 + segLen
        }
        throw IllegalStateException("无法解析 JPEG 尺寸: ${file.absolutePath}")
    }

    private fun readLocalProperty(key: String): String? {
        val candidates = listOf(
            File("local.properties"),
            File("../local.properties"),
            File("../../local.properties"),
            File("../../../local.properties"),
            File("D:/Android/project/1/PalmAgent/local.properties")
        )
        for (f in candidates) {
            if (!f.exists()) continue
            try {
                f.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx <= 0) continue
                        val k = trimmed.substring(0, eqIdx).trim()
                        if (k == key) {
                            return trimmed.substring(eqIdx + 1).trim()
                        }
                    }
                }
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }
}
