package com.palmagent.app.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.palmagent.app.AgentApplication
import com.palmagent.app.model.ToolCallResult
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class WebMCPService {

    companion object {
        private const val TAG = "WebMCPService"
        private const val PROTOCOL_VERSION = "2024-11-05"
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    // 应用 Context，用于 LocationService
    private val appContext get() = AgentApplication.instance

    private var amapSessionId: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ==================== 高德地图 MCP 工具 ====================

    /**
     * 高德地图：搜索地点（自动附带设备位置）
     * @param keywords 搜索关键词，如"星巴克"、"医院"
     * @param city 城市名称（可选），如"北京"
     */
    suspend fun amapSearch(keywords: String, city: String = ""): ToolCallResult = withContext(Dispatchers.IO) {
        if (!KVUtils.getAmapMcpEnabled()) {
            return@withContext ToolCallResult("amap_search", success = false, error = "高德地图 MCP 未启用，请在设置中开启")
        }

        val args = mutableMapOf<String, Any>(
            "keywords" to keywords
        )
        if (city.isNotBlank()) args["city"] = city

        // 自动附带设备位置
        val location = LocationService.getLocationString(appContext)
        if (location != null) {
            args["location"] = location
            Log.d(TAG, "高德搜索附带位置: $location")
        } else {
            Log.w(TAG, "高德搜索无位置信息（权限未授予或定位失败）")
        }

        callAmapTool("maps_text_search", args, "amap_search")
    }

    /**
     * 高德地图：搜索周边（自动附带设备位置）
     * @param keywords 搜索关键词
     * @param radius 搜索半径（米），默认 1000
     */
    suspend fun amapNearby(keywords: String, radius: Int = 1000): ToolCallResult = withContext(Dispatchers.IO) {
        if (!KVUtils.getAmapMcpEnabled()) {
            return@withContext ToolCallResult("amap_nearby", success = false, error = "高德地图 MCP 未启用")
        }

        val location = LocationService.getLocationString(appContext)
        if (location == null) {
            return@withContext ToolCallResult("amap_nearby", success = false, error = "无法获取设备位置，请检查位置权限")
        }

        val args = mapOf<String, Any>(
            "keywords" to keywords,
            "location" to location,
            "radius" to radius.toString()
        )

        callAmapTool("maps_around_search", args, "amap_nearby")
    }

    /**
     * 高德地图：路线规划（自动附带设备位置作为起点）
     * @param destination 目的地，如"北京站"或坐标"116.481028,39.989643"
     * @param mode 出行方式：drive/car（驾车）、walk（步行）、bus（公交）、bike（骑行）
     */
    suspend fun amapDirections(destination: String, mode: String = "drive"): ToolCallResult = withContext(Dispatchers.IO) {
        if (!KVUtils.getAmapMcpEnabled()) {
            return@withContext ToolCallResult("amap_directions", success = false, error = "高德地图 MCP 未启用")
        }

        val location = LocationService.getLocationString(appContext)
        if (location == null) {
            return@withContext ToolCallResult("amap_directions", success = false, error = "无法获取设备位置，请检查位置权限")
        }

        val args = mapOf<String, Any>(
            "origin" to location,
            "destination" to destination,
            "mode" to mode
        )

        callAmapTool("maps_direction_driving", args, "amap_directions")
    }

    /**
     * 高德地图：天气查询（自动附带设备位置）
     */
    suspend fun amapWeather(): ToolCallResult = withContext(Dispatchers.IO) {
        if (!KVUtils.getAmapMcpEnabled()) {
            return@withContext ToolCallResult("amap_weather", success = false, error = "高德地图 MCP 未启用")
        }

        val location = LocationService.getLocationString(appContext)
        val args = mutableMapOf<String, Any>()
        if (location != null) {
            args["location"] = location
        }

        callAmapTool("maps_weather", args, "amap_weather")
    }

    /**
     * 调用高德 MCP 工具（使用独立的高德 MCP 端点）
     */
    private suspend fun callAmapTool(name: String, arguments: Map<String, Any>, toolName: String): ToolCallResult {
        val amapUrl = KVUtils.getAmapMcpEndpointUrl()
        if (amapUrl.isBlank()) {
            return ToolCallResult(toolName, success = false, error = "高德地图 MCP 端点未配置（请检查 local.properties 中 AMAP_API_KEY）")
        }

        // 初始化高德 MCP session（首次调用时）
        if (amapSessionId == null) {
            val initResult = sendJsonRpcRequestToUrl(amapUrl, "initialize", mapOf(
                "protocolVersion" to PROTOCOL_VERSION,
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "PalmAgent", "version" to "1.0.0")
            ), null) { sessionId ->
                amapSessionId = sessionId
            }
            if (!initResult.success) {
                return ToolCallResult(toolName, success = false, error = "高德 MCP 初始化失败: ${initResult.error}")
            }
        }

        val result = sendJsonRpcRequestToUrl(amapUrl, "tools/call", mapOf(
            "name" to name,
            "arguments" to arguments
        ), amapSessionId) { sessionId ->
            amapSessionId = sessionId
        }

        return if (result.success && result.content.isNotEmpty()) {
            ToolCallResult(toolName, success = true, content = result.content)
        } else {
            ToolCallResult(toolName, success = false, error = result.error ?: "高德工具调用失败")
        }
    }

    /**
     * 发送 JSON-RPC 请求到指定 URL
     * @param url 目标 URL
     * @param method RPC 方法名
     * @param params 请求参数
     * @param sessionId 当前 session ID（可为 null）
     * @param onSessionId 收到新 session ID 时的回调
     */
    private suspend fun sendJsonRpcRequestToUrl(
        url: String,
        method: String,
        params: Map<String, Any>,
        sessionId: String?,
        onSessionId: (String) -> Unit = {}
    ): ToolCallResult = withContext(Dispatchers.IO) {
        try {
            val requestId = System.currentTimeMillis().toInt() and 0xFFFF

            val requestObj = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", requestId)
                addProperty("method", method)
                add("params", gson.toJsonTree(params))
            }

            val body = gson.toJson(requestObj).toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json, text/event-stream")
                .post(body)

            sessionId?.let {
                requestBuilder.addHeader("Mcp-Session-Id", it)
            }

            client.newCall(requestBuilder.build()).execute().use { httpResponse ->
                if (httpResponse.isSuccessful) {
                    val responseBody = httpResponse.body?.string() ?: ""

                    val newSessionId = httpResponse.header("Mcp-Session-Id")
                    if (!newSessionId.isNullOrBlank()) {
                        onSessionId(newSessionId)
                    }

                    try {
                        val json = gson.fromJson(responseBody, JsonObject::class.java)
                        val errorObj = json.getAsJsonObject("error")
                        if (errorObj != null) {
                            val errorMsg = errorObj.get("message")?.asString ?: "Unknown MCP error"
                            val errorCode = errorObj.get("code")?.asInt ?: 0
                            return@withContext ToolCallResult(method, success = false, error = "$errorMsg (code=$errorCode)")
                        }

                        val result = json.get("result")
                        if (result != null) {
                            val resultData = if (result.isJsonObject || result.isJsonArray) {
                                val content = result.getAsJsonObject()?.get("content")
                                if (content != null && content.isJsonArray) {
                                    val contentArr = content.asJsonArray
                                    if (contentArr.size() > 0) {
                                        val firstContent = contentArr[0]
                                        val text = firstContent.asJsonObject?.get("text")?.asString
                                        if (!text.isNullOrBlank()) {
                                            text
                                        } else {
                                            gson.toJson(contentArr)
                                        }
                                    } else {
                                        gson.toJson(result)
                                    }
                                } else {
                                    gson.toJson(result)
                                }
                            } else {
                                result.asString
                            }
                            return@withContext ToolCallResult(method, success = true, content = resultData)
                        }

                        return@withContext ToolCallResult(method, success = false, error = "MCP空结果")
                    } catch (e: Exception) {
                        Log.e(TAG, "解析MCP响应失败: ${e.message}, body=${responseBody.take(500)}")
                        return@withContext ToolCallResult(method, success = false, error = "MCP响应解析失败: ${e.message}")
                    }
                } else {
                    val errorBody = httpResponse.body?.string()?.take(200) ?: "HTTP ${httpResponse.code}"
                    Log.e(TAG, "MCP HTTP错误: $errorBody")
                    return@withContext ToolCallResult(method, success = false, error = "MCP HTTP ${httpResponse.code}")
                }
            }
        } catch (e: SocketTimeoutException) {
            return@withContext ToolCallResult(method, success = false, error = "MCP连接超时")
        } catch (e: IOException) {
            return@withContext ToolCallResult(method, success = false, error = "MCP网络异常: ${e.message}")
        } catch (e: Exception) {
            return@withContext ToolCallResult(method, success = false, error = "MCP请求异常: ${e.message}")
        }
    }
}
