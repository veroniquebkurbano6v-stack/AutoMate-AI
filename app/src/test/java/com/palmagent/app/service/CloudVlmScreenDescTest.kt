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
 * 云端视觉模型 屏幕描述 集成测试（纯 JVM）
 *
 * **目的**：验证 1.log 里的 SCREEN_DESC_PROMPT 在云端 VLM（Qwen-VL-Plus 等）上的
 *          实际输出效果和 HTTP 耗时。
 *
 * **测试方式**：
 *   - 真实 HTTP 调用云端 VLM（OpenAI 兼容 chat/completions 协议）
 *   - 输入：image（base64）+ SCREEN_DESC_PROMPT
 *   - 输出：模型返回的 JSON 描述（应包含上/中/下三区域 UI 元素）
 *   - 测量：HTTP 总耗时 + token 数
 *
 * **SCREEN_DESC_PROMPT 设计要求**（新版：上中下三区域）：
 *   1. 将屏幕垂直分为上、中、下三部分，分别描述各区域关键UI元素
 *   2. 只描述可见UI元素，不判断页面类型（减少模型推理开销）
 *   3. 每个区域控制在25字以内，总输出 ~75字（保持速度）
 *   4. 输出纯文本三行，不包含额外解释
 *
 * **前置条件**（服务不可达时自动跳过，不报失败）：
 *   - 环境变量 CLOUD_VLM_API_URL  - 云端 VLM 地址（OpenAI 兼容）
 *   - 环境变量 CLOUD_VLM_API_KEY - API 密钥
 *   - 环境变量 CLOUD_VLM_MODEL   - 模型名（如 qwen-vl-plus）
 *   - 测试图片目录：默认 VLM_test_input，可用 VLM_TEST_INPUT_DIR 覆盖
 *
 * **运行示例**：
 *   ```bash
 *   $env:CLOUD_VLM_API_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
 *   $env:CLOUD_VLM_API_KEY="sk-xxx"
 *   $env:CLOUD_VLM_MODEL="qwen-vl-plus"
 *   .\gradlew.bat :app:testDebugUnitTest --tests "com.palmagent.app.service.CloudVlmScreenDescTest"
 *   ```
 */
class CloudVlmScreenDescTest {

    /** SCREEN_DESC_PROMPT 完整内容（新版：上中下三区域描述，信息量提升 ~3x） */
    private val screenDescPrompt = """你是一个移动端屏幕分析助手。请将屏幕垂直分为上、中、下三部分，描述各区域的关键UI元素。

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

    private data class TestCase(
        val image: String,
        val description: String
    )

    private val apiUrl: String? by lazy {
        System.getenv("CLOUD_VLM_API_URL")?.takeIf { it.isNotBlank() }
            ?: readLocalProperty("VLM_API_URL")
    }
    private val apiKey: String? by lazy {
        System.getenv("CLOUD_VLM_API_KEY")?.takeIf { it.isNotBlank() }
            ?: readLocalProperty("VLM_API_KEY")
    }
    private val model: String by lazy {
        System.getenv("CLOUD_VLM_MODEL")?.takeIf { it.isNotBlank() }
            ?: readLocalProperty("VLM_MODEL")
            ?: "qwen3-vl-flash"
    }

    private val inputDir: File = listOfNotNull(
        System.getenv("VLM_TEST_INPUT_DIR")?.let { File(it) },
        File("../VLM_test_input/input"),
        File("../../VLM_test_input/input"),
        File("../../../VLM_test_input/input"),
        File("D:/Android/project/VLM_test_input/input")
    ).firstOrNull { it.exists() && it.isDirectory }
        ?: File("../VLM_test_input/input")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 多图测试：覆盖微信主界面、小程序搜索、聊天详情等场景 */
    private val testCases = listOf(
        TestCase("2.jpg", "微信主界面（聊天列表）"),
        TestCase("3.jpg", "微信小程序搜索结果页"),
        TestCase("4.jpg", "微信聊天详情页"),
        TestCase("5.jpg", "微信搜索页"),
    )

    @Before
    fun setUp() {
        println("[setUp] 工作目录: ${File(".").absolutePath}")
        println("[setUp] inputDir: ${inputDir.absolutePath} (exists=${inputDir.exists()})")
        println("[setUp] apiUrl: ${apiUrl ?: "[未配置，将跳过]"}")
        println("[setUp] apiKey: ${if (apiKey.isNullOrBlank()) "[未配置]" else "***(${apiKey!!.length}字符)"}")
        println("[setUp] model: $model")

        // 云端 VLM 未配置 → 优雅跳过
        assumeTrue(
            "云端 VLM API 未配置（需设置环境变量 CLOUD_VLM_API_URL / CLOUD_VLM_API_KEY），跳过测试",
            !apiUrl.isNullOrBlank() && !apiKey.isNullOrBlank()
        )
        assumeTrue(
            "测试图片目录不存在: ${inputDir.absolutePath}",
            inputDir.exists() && inputDir.isDirectory
        )
    }

    @Test
    fun `云端VLM 屏幕描述 多图测试 耗时与效果`() {
        println("=" .repeat(79))
        println("云端 VLM 屏幕描述集成测试（多图：上中下三区域描述）")
        println("API: $apiUrl | Model: $model")
        println("图片目录: ${inputDir.absolutePath}")
        println("=" .repeat(79))

        val results = mutableListOf<TestResult>()

        for (tc in testCases) {
            val imgFile = File(inputDir, tc.image)
            if (!imgFile.exists()) {
                println("[SKIP] ${tc.image} 不存在")
                continue
            }

            val result = runSingleTestCase(tc, imgFile)
            results.add(result)
            printResult(result)
        }

        // 汇总
        println("\n" + "=".repeat(79))
        val successResults = results.filter { it.success }
        if (successResults.isNotEmpty()) {
            val avgMs = successResults.map { it.httpMs }.average().toLong()
            val avgTokens = successResults.mapNotNull { it.outputTokens }.average()
            println("【汇总】成功: ${successResults.size}/${results.size} | 平均耗时: ${avgMs}ms | 平均 output_tokens: ${"%.1f".format(avgTokens)}")
            println()
            for (r in successResults) {
                println("── ${r.image} (${r.httpMs}ms, ${r.outputTokens}tok) ──")
                println(r.rawResponse)
            }
        } else {
            val first = results.firstOrNull()
            println("【失败】${first?.error ?: "无结果"}")
        }
        println("=" .repeat(79))

        assertTrue(
            "用例失败，请检查 API URL/KEY/Model 配置: ${results.firstOrNull()?.error}",
            results.any { it.success }
        )
    }

    private data class TestResult(
        val image: String,
        val description: String,
        val success: Boolean,
        val httpMs: Long,
        val imageKb: Int,
        val width: Int,
        val height: Int,
        val outputTokens: Int?,
        val rawResponse: String,   // 完整响应（三区域输出较短）
        val error: String?
    )

    private fun runSingleTestCase(tc: TestCase, imgFile: File, label: String = ""): TestResult {
        val labelSuffix = if (label.isNotEmpty()) " [$label]" else ""
        val imageBytes = imgFile.readBytes()
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        val imageKb = imageBytes.size / 1024
        val (width, height) = getJpegSize(imgFile)

        // 构造 OpenAI 兼容请求体（messages + image_url）
        val payload = buildString {
            append("{")
            append("\"model\":\"$model\",")
            append("\"max_tokens\":300,")  // 三区域输出，300 token 足够
            append("\"temperature\":0.0,")
            append("\"messages\":[")
            append("{")
            append("\"role\":\"user\",")
            append("\"content\":[")
            append("{\"type\":\"text\",\"text\":${jsonString(screenDescPrompt)}},")
            append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,$base64\"}}")
            append("]")
            append("}")
            append("]")
            append("}")
        }

        val t0 = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("$apiUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val httpMs = System.currentTimeMillis() - t0

            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(300) ?: ""
                return TestResult(
                    tc.image + labelSuffix, tc.description, false, httpMs, imageKb,
                    width, height, null, "", "HTTP ${response.code}: $errBody"
                )
            }

            val body = response.body?.string() ?: ""
            val content = extractContent(body)

            // 提取 output_tokens
            val tokensMatch = """"completion_tokens"\s*:\s*(\d+)""".toRegex().find(body)
            val tokens = tokensMatch?.groupValues?.get(1)?.toInt()

            TestResult(
                tc.image + labelSuffix, tc.description, true, httpMs, imageKb,
                width, height, tokens, content, null
            )
        } catch (e: Exception) {
            val httpMs = System.currentTimeMillis() - t0
            TestResult(
                tc.image + labelSuffix, tc.description, false, httpMs, imageKb,
                width, height, null, "", "异常: ${e.message}"
            )
        }
    }

    private fun printResult(r: TestResult) {
        val status = if (r.success) "✓" else "✗"
        println("\n[$status] ${r.image} (${r.description})")
        println("  尺寸: ${r.width}x${r.height} | 大小: ${r.imageKb}KB")
        println("  HTTP 耗时: ${r.httpMs}ms")
        if (r.outputTokens != null) {
            println("  output_tokens: ${r.outputTokens}")
        }
        if (r.error != null) {
            println("  错误: ${r.error}")
        } else if (r.rawResponse.isNotBlank()) {
            println("  响应:")
            r.rawResponse.lines().forEach { println("    $it") }
        }
    }

    /**
     * 从 OpenAI 响应中提取 message.content
     */
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
     * 从 local.properties 读取配置（支持 VLM_API_URL / VLM_API_KEY / VLM_MODEL）
     * 搜索路径：工作目录上溯 1-4 层
     */
    private fun readLocalProperty(key: String): String? {
        val candidates = listOf(
            File("local.properties"),
            File("../local.properties"),
            File("../../local.properties"),
            File("../../../local.properties"),
            File("D:/Android/project/PalmAgent/local.properties")
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
                return null  // 文件存在但找不到 key
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    /**
     * 简单 JSON 字符串转义（处理 ", \, 控制符）
     */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u${ch.code.toString(16).padStart(4, '0')}")
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * 从 JPEG 字节流解析图片尺寸（解析 SOF0/SOF2 标记，不依赖 javax.imageio）
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
}
