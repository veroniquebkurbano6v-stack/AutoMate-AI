package com.palmagent.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.model.Coordinate
import com.palmagent.app.utils.BitmapPool
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * GUI-Plus（阿里云百炼）界面交互模型服务
 *
 * 已废弃本地 GUI-Plus 服务（palm-pulse/serve_gui_owl.py），全部改为直接调用云端百炼 GUI-Plus：
 * - decide(): VL 模式执行决策（截图 + 任务提示 → 动作 + 坐标）
 * - ground(): 定位（instruction + 截图 → 屏幕像素坐标）
 * - exists(): 元素甄别（target + 截图 → exists 布尔）
 *
 * 接口：POST {base}/chat/completions（OpenAI 兼容，Bearer 鉴权）
 * 请求：{ model, messages:[system, user(image_url data URL + text)], vl_high_resolution_images }
 * 响应：choices[0].message.content 内含
 *       <tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":[x,y]}}</tool_call>
 *
 * 坐标换算：模型输出在 [0,1000] 归一化空间，需缩放至真实屏幕尺寸：
 *   pixelX = coordinate[0] × screenWidth / 1000
 *   pixelY = coordinate[1] × screenHeight / 1000
 */
object GuiOwlService {

    private const val TAG = "GuiOwl"
    private const val JPEG_QUALITY = 40
    private const val MAX_IMAGE_DIMENSION = 4096
    private const val MAX_PIXELS = 1500000
    private const val WRITE_TIMEOUT_MS = 10_000L

    data class ScreenSize(val width: Int, val height: Int)

    data class GroundingResult(
        val success: Boolean,
        val coordinate: Coordinate? = null,
        val pixelCoordinate: Coordinate? = null,
        val action: String = "",
        val thinking: String = "",
        val answer: String = "",
        val rawResponse: String = "",
        val error: String? = null,
        val durationMs: Long = 0
    )

    data class DecideResult(
        val success: Boolean,
        val action: String = "",
        val coordinate: Coordinate? = null,
        val coordinateEnd: Coordinate? = null,
        val text: String? = null,
        val rawResponse: String = "",
        val error: String? = null,
        val durationMs: Long = 0
    )

    /** 元素甄别结果：只判断目标元素是否存在，不返回坐标（防止模型幻觉坐标） */
    data class ExistsResult(
        val success: Boolean,
        val exists: Boolean = false,
        val reason: String = "",
        val rawResponse: String = "",
        val error: String? = null,
        val durationMs: Long = 0
    )

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    // ============ 指令识别工具 ============

    private val SEARCH_ICON_KEYWORDS = listOf(
        "搜索图标", "search icon", "magnifying glass", "放大镜", "lens icon"
    )

    fun isSearchIconInstruction(instruction: String): Boolean {
        val lower = instruction.lowercase()
        return SEARCH_ICON_KEYWORDS.any { lower.contains(it) }
    }

    // ============ HTTP 客户端 ============

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(KVUtils.getGuiOwlConnectTimeout(), TimeUnit.MILLISECONDS)
            .readTimeout(KVUtils.getGuiOwlReadTimeout(), TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    // ============ 初始化 ============

    fun init(): Boolean {
        val apiKey = KVUtils.getGuiOwlApiKey()
        if (apiKey.isBlank()) {
            lastError = "百炼 API Key 未配置"
            Log.w(TAG, lastError!!)
            return false
        }
        isReady = true
        lastError = null
        Log.d(TAG, "GuiOwlService(GUI-Plus) 初始化成功, 模型=${KVUtils.getGuiOwlModel()}")
        LiveLogBuffer.append("✓ GUI-Plus(百炼) 服务就绪")
        return true
    }

    // ============ 定位入口（供 tap/auto_input/locate 等工具调用） ============

    suspend fun ground(
        instruction: String,
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val truncatedInstruction = instruction.take(500)

        val payload = compressAndEncodeImage(bitmap)
        if (payload == null) {
            return@withContext GroundingResult(
                success = false,
                error = "图片编码失败",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        var lastErrorMsg: String? = null

        for (attempt in 1..KVUtils.getGuiOwlMaxRetries()) {
            try {
                Log.d(TAG, "[GROUND]请求 (尝试$attempt/${KVUtils.getGuiOwlMaxRetries()})")
                LiveLogBuffer.append("🔍 GUI-Plus[GROUND]: (尝试$attempt)")

                val content = requestChat(
                    text = "定位指令：$truncatedInstruction",
                    payload = payload,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    mode = PromptMode.GROUND
                )

                val result = parseGroundingResponse(
                    content, screenWidth, screenHeight, System.currentTimeMillis() - startTime
                )
                if (result.success) {
                    LiveLogBuffer.append(
                        "🎯 GUI-Plus[GROUND]成功: (${result.coordinate?.x},${result.coordinate?.y}) ${result.durationMs}ms"
                    )
                    return@withContext result
                }
                lastErrorMsg = result.error ?: "解析失败"
                Log.w(TAG, "[GROUND]失败(尝试$attempt): $lastErrorMsg")
                LiveLogBuffer.append("❌ GUI-Plus[GROUND]失败: $lastErrorMsg")
            } catch (e: java.net.SocketTimeoutException) {
                lastErrorMsg = "请求超时: ${e.message}"
                Log.e(TAG, "[GROUND]超时(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("⚠ GUI-Plus超时(尝试$attempt)")
            } catch (e: java.net.ConnectException) {
                lastErrorMsg = "连接失败: ${e.message}"
                Log.e(TAG, "[GROUND]连接失败(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus连接失败(尝试$attempt)")
            } catch (e: Exception) {
                lastErrorMsg = "网络错误: ${e.message}"
                Log.e(TAG, "[GROUND]网络错误(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus网络错误(尝试$attempt)")
            }

            if (attempt < KVUtils.getGuiOwlMaxRetries()) {
                delay(KVUtils.getGuiOwlRetryDelayMs())
            }
        }

        return@withContext GroundingResult(
            success = false,
            error = lastErrorMsg ?: "未知错误",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 元素甄别：判断目标元素在当前屏幕是否可见
     * 只返回 exists=true/false，不返回坐标（防止模型幻觉坐标）
     *
     * @param target 目标元素的视觉可辨识描述（如"心相印金装经典抽纸"、"底部导航栏的购物车图标"）
     * @param bitmap 屏幕截图
     */
    suspend fun exists(
        target: String,
        bitmap: Bitmap
    ): ExistsResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val truncatedTarget = target.take(200)

        val payload = compressAndEncodeImage(bitmap)
        if (payload == null) {
            return@withContext ExistsResult(
                success = false,
                error = "图片编码失败",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        var lastErrorMsg: String? = null

        for (attempt in 1..KVUtils.getGuiOwlMaxRetries()) {
            try {
                Log.d(TAG, "[EXISTS]请求 (尝试$attempt/${KVUtils.getGuiOwlMaxRetries()})")
                LiveLogBuffer.append("🔍 GUI-Plus[EXISTS]: ${truncatedTarget.take(40)} (尝试$attempt)")

                val content = requestChat(
                    text = "目标元素：$truncatedTarget",
                    payload = payload,
                    screenWidth = payload.width,
                    screenHeight = payload.height,
                    mode = PromptMode.EXISTS
                )

                val result = parseExistsResponse(content, System.currentTimeMillis() - startTime)
                if (result.success) {
                    Log.d(TAG, "[EXISTS]成功: exists=${result.exists} reason=${result.reason} (${result.durationMs}ms)")
                    LiveLogBuffer.append("✓ GUI-Plus[EXISTS]: exists=${result.exists} ${result.reason.take(40)}")
                    return@withContext result
                }
                lastErrorMsg = result.error ?: "解析失败"
                Log.w(TAG, "[EXISTS]失败(尝试$attempt): $lastErrorMsg")
            } catch (e: java.net.SocketTimeoutException) {
                lastErrorMsg = "请求超时: ${e.message}"
                Log.e(TAG, "[EXISTS]超时(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("⚠ GUI-Plus超时(尝试$attempt)")
            } catch (e: java.net.ConnectException) {
                lastErrorMsg = "连接失败: ${e.message}"
                Log.e(TAG, "[EXISTS]连接失败(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus连接失败(尝试$attempt)")
            } catch (e: Exception) {
                lastErrorMsg = "网络错误: ${e.message}"
                Log.e(TAG, "[EXISTS]网络错误(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus网络错误(尝试$attempt)")
            }

            if (attempt < KVUtils.getGuiOwlMaxRetries()) {
                delay(KVUtils.getGuiOwlRetryDelayMs())
            }
        }

        return@withContext ExistsResult(
            success = false,
            error = lastErrorMsg ?: "未知错误",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ============ VL 模式执行决策入口 ============

    suspend fun decide(
        userPrompt: String,
        screenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): DecideResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val payload = compressAndEncodeImage(screenshot)
        if (payload == null) {
            return@withContext DecideResult(
                success = false,
                error = "图片编码失败",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        var lastErrorMsg: String? = null

        for (attempt in 1..KVUtils.getGuiOwlMaxRetries()) {
            try {
                Log.d(TAG, "[DECIDE]请求 (尝试$attempt/${KVUtils.getGuiOwlMaxRetries()})")
                LiveLogBuffer.append("🔍 GUI-Plus[DECIDE]: (尝试$attempt)")

                val content = requestChat(
                    text = userPrompt.take(4000),
                    payload = payload,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    mode = PromptMode.DECIDE
                )

                val result = parseDecideResponse(
                    content, screenWidth, screenHeight, System.currentTimeMillis() - startTime
                )
                if (result.success) {
                    LiveLogBuffer.append(
                        "🎯 GUI-Plus[DECIDE]成功: ${result.action} " +
                            "(${result.coordinate?.x},${result.coordinate?.y}) ${result.durationMs}ms"
                    )
                    return@withContext result
                }
                lastErrorMsg = result.error ?: "解析失败"
                Log.w(TAG, "[DECIDE]失败(尝试$attempt): $lastErrorMsg")
                LiveLogBuffer.append("❌ GUI-Plus[DECIDE]失败: $lastErrorMsg")
            } catch (e: java.net.SocketTimeoutException) {
                lastErrorMsg = "请求超时: ${e.message}"
                Log.e(TAG, "[DECIDE]超时(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("⚠ GUI-Plus[DECIDE]超时(尝试$attempt)")
            } catch (e: java.net.ConnectException) {
                lastErrorMsg = "连接失败: ${e.message}"
                Log.e(TAG, "[DECIDE]连接失败(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus[DECIDE]连接失败(尝试$attempt)")
            } catch (e: Exception) {
                lastErrorMsg = "网络错误: ${e.message}"
                Log.e(TAG, "[DECIDE]网络错误(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus[DECIDE]网络错误(尝试$attempt)")
            }

            if (attempt < KVUtils.getGuiOwlMaxRetries()) {
                delay(KVUtils.getGuiOwlRetryDelayMs())
            }
        }

        return@withContext DecideResult(
            success = false,
            error = lastErrorMsg ?: "未知错误",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ============ 云端请求 ============

    private enum class PromptMode { GROUND, DECIDE, EXISTS }

    /**
     * 发送一次 GUI-Plus chat/completions 请求，返回 assistant 的 content 文本。
     * 失败时抛出异常（由调用方捕获重试）。
     */
    private fun requestChat(
        text: String,
        payload: ImagePayload,
        screenWidth: Int,
        screenHeight: Int,
        mode: PromptMode
    ): String {
        val apiKey = KVUtils.getGuiOwlApiKey()
        require(apiKey.isNotBlank()) { "百炼 API Key 未配置" }

        val baseUrl = KVUtils.getGuiOwlApiUrl().trimEnd('/')
        val chatUrl = "$baseUrl/chat/completions"

        val requestBody = JSONObject().apply {
            put("model", KVUtils.getGuiOwlModel())
            put("messages", buildMessages(text, payload, screenWidth, screenHeight, mode))
            put("vl_high_resolution_images", true)
            if (mode == PromptMode.DECIDE) {
                put("enable_thinking", true)
            }
        }.toString().toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(chatUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val apiError = runCatching { JSONObject(body).optJSONObject("error")?.optString("message", "") }
                    .getOrNull().orEmpty()
                throw IllegalStateException("HTTP ${response.code}: ${apiError.ifBlank { body.take(200) }}")
            }
            return extractAssistantContent(body)
        }
    }

    /** 从 chat/completions 响应中提取 assistant content；无 choices 时抛异常 */
    private fun extractAssistantContent(responseBody: String): String {
        val json = JSONObject(responseBody)
        val apiError = json.optJSONObject("error")
        if (apiError != null) {
            throw IllegalStateException(apiError.optString("message", "服务端错误"))
        }
        val choices = json.optJSONArray("choices")
        val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
        if (content.isNullOrBlank()) {
            throw IllegalStateException("响应为空")
        }
        return content
    }

    private fun buildMessages(
        text: String,
        payload: ImagePayload,
        screenWidth: Int,
        screenHeight: Int,
        mode: PromptMode
    ): JSONArray {
        val systemPrompt = when (mode) {
            PromptMode.GROUND -> buildGroundSystemPrompt()
            PromptMode.DECIDE -> buildDecideSystemPrompt()
            PromptMode.EXISTS -> buildExistsSystemPrompt()
        }
        return JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${payload.base64}"))
                    })
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    })
                })
            })
        }
    }

    // ============ 官方手机端 System Prompt（阿里云百炼 GUI-Plus 推荐） ============

    private val MOBILE_SYSTEM_PROMPT = """# Tools

You may call one or more functions to assist with the user query.

You are provided with function signatures within <tools></tools> XML tags:
<tools>
{"type": "function", "function": {"name": "mobile_use", "description": "Use a touchscreen to interact with a mobile device, and take screenshots.\\n* This is an interface to a mobile device with touchscreen. You can perform actions like clicking, typing, swiping, etc.\\n* Some applications may take time to start or process actions, so you may need to wait and take successive screenshots to see the results of your actions.\\n* The screen's resolution is 1000x1000.\\n* Make sure to click any buttons, links, icons, etc with the cursor tip in the center of the element. Don't click boxes on their edges unless asked.", "parameters": {"properties": {"action": {"description": "The action to perform. The available actions are:\\n* `key`: Perform a key event on the mobile device.\\n    - This supports adb's `keyevent` syntax.\\n    - Examples: \"volume_up\", \"volume_down\", \"power\", \"camera\", \"clear\".\\n* `click`: Click the point on the screen with coordinate (x, y).\\n* `long_press`: Press the point on the screen with coordinate (x, y) for specified seconds.\\n* `swipe`: Swipe from the starting point with coordinate (x, y) to the end point with coordinates2 (x2, y2).\\n* `type`: Input the specified text into the activated input box.\\n* `system_button`: Press the system button.\\n* `open`: Open an app on the device.\\n* `wait`: Wait specified seconds for the change to happen.\\n* `answer`: Terminate the current task and output the answer.\\n* `interact`: Resolve the blocking window by interacting with the user.\\n* `terminate`: Terminate the current task and report its completion status.", "enum": ["key", "click", "long_press", "swipe", "type", "system_button", "open", "wait", "answer", "interact", "terminate"], "type": "string"}, "coordinate": {"description": "(x, y): The x (pixels from the left edge) and y (pixels from the top edge) coordinates to move the mouse to. Required only by `action=click`, `action=long_press`, and `action=swipe`.", "type": "array"}, "coordinate2": {"description": "(x, y): The x (pixels from the left edge) and y (pixels from the top edge) coordinates to move the mouse to. Required only by `action=swipe`.", "type": "array"}, "text": {"description": "Required only by `action=key`, `action=type`, `action=open`, `action=answer`,and `action=interact`.", "type": "string"}, "time": {"description": "The seconds to wait. Required only by `action=long_press` and `action=wait`.", "type": "number"}, "button": {"description": "Back means returning to the previous interface, Home means returning to the desktop, Menu means opening the application background menu, and Enter means pressing the enter. Required only by `action=system_button`", "enum": ["Back", "Home", "Menu", "Enter"], "type": "string"}, "status": {"description": "The status of the task. Required only by `action=terminate`.", "type": "string", "enum": ["success", "failure"]}}, "required": ["action"], "type": "object"}, "args_format": "Format the arguments as a JSON object."}}
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{"name": <function-name>, "arguments": <args-json-object>}
</tool_call>

# Response format

Response format for every step:
1) Action: a short imperative describing what to do in the UI.
2) A single <tool_call>...</tool_call> block containing only the JSON: {"name": <function-name>, "arguments": <args-json-object>}.

Rules:
- Output exactly in the order: Action, <tool_call>.
- Be brief: one for Action.
- Do not output anything else outside those two parts.
- If finishing, use action=terminate in the tool call."""

    /** 定位模式：使用官方手机端 System Prompt，用户指令含定位目标 */
    private fun buildGroundSystemPrompt(): String = MOBILE_SYSTEM_PROMPT

    /** 决策模式：使用官方手机端 System Prompt，用户指令含完整任务 */
    private fun buildDecideSystemPrompt(): String = MOBILE_SYSTEM_PROMPT

    /** 元素甄别模式：只输出 JSON，不输出 tool_call */
    private fun buildExistsSystemPrompt(): String = """
        你是手机屏幕元素甄别助手。根据屏幕截图与目标元素描述，判断该目标元素当前是否可见。
        只输出一个 JSON 对象，不要输出任何其他内容，格式：
        {"exists": true 或 false, "reason": "简短原因"}
    """.trimIndent()

    // ============ 响应解析 ============

    /** 发送图的尺寸信息（用于坐标缩放还原） */
    private data class ImagePayload(val base64: String, val width: Int, val height: Int)

    /** 从 assistant content 中提取 <tool_call> JSON 的 arguments */
    private fun extractToolCall(content: String): JSONObject? {
        val match = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
            .find(content)
            ?: return null
        return runCatching {
            val toolJson = JSONObject(match.groupValues[1])
            toolJson.optJSONObject("arguments") ?: toolJson
        }.getOrNull()
    }

    private fun parseGroundingResponse(
        content: String,
        screenWidth: Int,
        screenHeight: Int,
        durationMs: Long
    ): GroundingResult {
        return try {
            val args = extractToolCall(content)
            val coordinateArr = args?.optJSONArray("coordinate")
            if (coordinateArr != null && coordinateArr.length() >= 2) {
                val pixel = scaleCoordinate(
                    coordinateArr.optDouble(0, 0.0), coordinateArr.optDouble(1, 0.0),
                    screenWidth, screenHeight
                )
                val coordinate = Coordinate(pixel.x, pixel.y)
                return GroundingResult(
                    success = true,
                    coordinate = coordinate,
                    pixelCoordinate = coordinate,
                    action = normalizeAction(args?.optString("action", "") ?: ""),
                    answer = "坐标: (${pixel.x}, ${pixel.y})",
                    rawResponse = content,
                    durationMs = durationMs
                )
            }
            GroundingResult(
                success = false,
                error = "响应中未找到有效坐标",
                rawResponse = content.take(300),
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析GROUND响应失败: ${e.message}")
            GroundingResult(
                success = false,
                error = "解析失败: ${e.message}",
                rawResponse = content.take(300),
                durationMs = durationMs
            )
        }
    }

    private fun parseDecideResponse(
        content: String,
        screenWidth: Int,
        screenHeight: Int,
        durationMs: Long
    ): DecideResult {
        return try {
            val args = extractToolCall(content)
            val action = normalizeAction(args?.optString("action", "") ?: "")
            if (action.isBlank()) {
                return DecideResult(
                    success = false,
                    error = "响应中未找到有效动作",
                    rawResponse = content.take(300),
                    durationMs = durationMs
                )
            }

            var coordinate: Coordinate? = null
            val coordinateArr = args?.optJSONArray("coordinate")
            if (coordinateArr != null && coordinateArr.length() >= 2) {
                val pixel = scaleCoordinate(
                    coordinateArr.optDouble(0, 0.0), coordinateArr.optDouble(1, 0.0),
                    screenWidth, screenHeight
                )
                coordinate = Coordinate(pixel.x, pixel.y)
            }

            var coordinateEnd: Coordinate? = null
            // 官方手机端 System Prompt 使用 coordinate2 而非 coordinate_end
            val endArr = args?.optJSONArray("coordinate2") ?: args?.optJSONArray("coordinate_end")
            if (endArr != null && endArr.length() >= 2) {
                val pixel = scaleCoordinate(
                    endArr.optDouble(0, 0.0), endArr.optDouble(1, 0.0),
                    screenWidth, screenHeight
                )
                coordinateEnd = Coordinate(pixel.x, pixel.y)
            }

            var text: String? = null
            if (action == "type" || action == "open" || action == "answer") {
                text = args?.optString("text", null)
            } else if (action == "system_button") {
                text = args?.optString("button", null) ?: args?.optString("name", null) ?: "back"
            }

            DecideResult(
                success = true,
                action = action,
                coordinate = coordinate,
                coordinateEnd = coordinateEnd,
                text = text,
                rawResponse = content,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析DECIDE响应失败: ${e.message}")
            DecideResult(
                success = false,
                error = "解析失败: ${e.message}",
                rawResponse = content.take(300),
                durationMs = durationMs
            )
        }
    }

    private fun parseExistsResponse(content: String, durationMs: Long): ExistsResult {
        return try {
            // 允许模型输出被 ```json 代码块包裹，先剥掉
            val stripped = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JSONObject(stripped)
            ExistsResult(
                success = true,
                exists = json.optBoolean("exists", false),
                reason = json.optString("reason", ""),
                rawResponse = content,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "[EXISTS]解析失败: ${e.message}")
            ExistsResult(
                success = false,
                error = "解析失败: ${e.message}",
                rawResponse = content.take(300),
                durationMs = durationMs
            )
        }
    }

    /** 归一化动作名：兼容模型原生 computer_use 动作与自有动作集 */
    private fun normalizeAction(action: String): String = when (action.lowercase().trim()) {
        "click", "left_click", "right_click", "double_click", "middle_click", "mouse_move", "triple_click" -> "click"
        "long_press" -> "long_press"
        "swipe", "scroll", "hscroll", "left_click_drag", "drag" -> "swipe"
        "type", "key" -> "type"
        "system_button", "back", "home" -> "system_button"
        "open" -> "open"
        "wait" -> "wait"
        "answer", "interact" -> "answer"
        "terminate" -> "terminate"
        else -> action.lowercase().trim()
    }

    /** 坐标缩放还原：模型输出在 [0,1000] 归一化空间 → 真实屏幕像素（方案C 复用，故 internal） */
    internal fun scaleCoordinate(
        x: Double, y: Double,
        screenWidth: Int, screenHeight: Int
    ): Coordinate {
        val pixelX = (x * screenWidth / 1000.0).toInt().coerceIn(0, screenWidth)
        val pixelY = (y * screenHeight / 1000.0).toInt().coerceIn(0, screenHeight)
        return Coordinate(pixelX, pixelY)
    }

    fun getStatus(): String = buildString {
        appendLine("GUI-Plus(百炼) 状态:")
        appendLine("  就绪: $isReady")
        appendLine("  API: ${KVUtils.getGuiOwlApiUrl()}")
        appendLine("  模型: ${KVUtils.getGuiOwlModel()}")
        appendLine("  Key: ${if (KVUtils.getGuiOwlApiKey().isBlank()) "未配置" else "已配置"}")
        if (lastError != null) appendLine("  最后错误: $lastError")
    }

    // ============ 图片编码 ============

    private fun compressAndEncodeImage(bitmap: Bitmap): ImagePayload? {
        return try {
            val srcBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                try {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    Log.w(TAG, "HARDWARE→ARGB_8888 转换失败，回退原 bitmap: ${e.message}")
                    bitmap
                }
            } else {
                bitmap
            }

            var width = srcBitmap.width
            var height = srcBitmap.height

            val pixels = width * height
            if (pixels > MAX_PIXELS) {
                val scale = Math.sqrt(MAX_PIXELS.toDouble() / pixels.toDouble())
                width = (width * scale).toInt().coerceAtLeast(1)
                height = (height * scale).toInt().coerceAtLeast(1)
                Log.d(TAG, "缩放图片: ${srcBitmap.width}x${srcBitmap.height} -> ${width}x${height}")
            }

            if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(width, height)
                width = (width * scale).toInt()
                height = (height * scale).toInt()
            }

            val scaledBitmap = if (width != srcBitmap.width || height != srcBitmap.height) {
                val scaled = BitmapPool.acquire(width, height, Bitmap.Config.RGB_565)
                val canvas = Canvas(scaled)
                canvas.drawBitmap(srcBitmap, null, Rect(0, 0, width, height), null)
                scaled
            } else {
                srcBitmap
            }

            val estimatedSize = (width * height * 3 / 14).coerceAtLeast(8192)
            val outputStream = ByteArrayOutputStream(estimatedSize)

            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

            if (scaledBitmap !== srcBitmap && !scaledBitmap.isRecycled) {
                BitmapPool.release(scaledBitmap)
            }
            if (srcBitmap !== bitmap && !srcBitmap.isRecycled) {
                srcBitmap.recycle()
            }

            ImagePayload(base64, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "图片编码失败: ${e.message}")
            null
        }
    }
}
