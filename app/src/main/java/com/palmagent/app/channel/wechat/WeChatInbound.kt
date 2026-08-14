package com.palmagent.app.channel.wechat

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*

class WeChatInbound(
    private val client: WeChatApiClient,
    private val onNewMessage: (WeChatMessage) -> Unit,
    private val onExpired: () -> Unit
) {

    companion object {
        private const val TAG = "WeChatInbound"
        private const val RECONNECT_DELAY_MS = 1_000L

        private val gson = Gson()

        fun parseMessage(json: JsonObject): WeChatMessage? {
            return try {
                WeChatMessage(
                    seq = json.get("seq")?.asInt,
                    messageId = json.get("message_id")?.asLong ?: json.get("messageId")?.asLong,
                    fromUserId = json.get("from_user_id")?.asString ?: "",
                    toUserId = json.get("to_user_id")?.asString ?: "",
                    clientId = json.get("client_id")?.asString,
                    createTimeMs = json.get("create_time_ms")?.asLong ?: json.get("createTimeMs")?.asLong,
                    updateTimeMs = json.get("update_time_ms")?.asLong ?: json.get("updateTimeMs")?.asLong,
                    sessionId = json.get("session_id")?.asString,
                    groupId = json.get("group_id")?.asString,
                    messageType = json.get("message_type")?.asInt ?: MessageType.USER,
                    messageState = json.get("message_state")?.asInt ?: MessageState.NEW,
                    itemList = parseItemList(json.getAsJsonArray("item_list")),
                    contextToken = json.get("context_token")?.asString
                )
            } catch (e: Exception) {
                Log.e(TAG, "parseMessage失败: ${e.message}")
                null
            }
        }

        private fun parseItemList(jsonArray: com.google.gson.JsonArray?): List<WeChatMessageItem>? {
            if (jsonArray == null) return null
            val items = mutableListOf<WeChatMessageItem>()
            for (i in 0 until jsonArray.size()) {
                val item = jsonArray.get(i).asJsonObject
                items.add(
                    WeChatMessageItem(
                        type = item.get("type")?.asInt ?: 0,
                        createTimeMs = item.get("create_time_ms")?.asLong,
                        updateTimeMs = item.get("update_time_ms")?.asLong,
                        isCompleted = item.get("is_completed")?.asBoolean,
                        msgId = item.get("msg_id")?.asString,
                        textItem = item.get("text_item")?.asJsonObject?.let { textJson ->
                            WeChatTextItem(text = textJson.get("text")?.asString)
                        }
                    )
                )
            }
            return items
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var running = false
    private var getUpdatesBuf: String = ""

    fun start() {
        if (running) return
        running = true
        scope.launch {
            Log.d(TAG, "WeChat入站监听启动")
            pollingLoop()
        }
    }

    fun stop() {
        running = false
        scope.cancel()
        Log.d(TAG, "WeChat入站监听停止")
    }

    private suspend fun pollingLoop() {
        while (running) {
            try {
                val resp = client.getUpdates(getUpdatesBuf)
                if (resp == null) {
                    delay(RECONNECT_DELAY_MS)
                    continue
                }

                if (!running) return

                when {
                    resp.errcode == SESSION_EXPIRED_ERRCODE -> {
                        Log.w(TAG, "session过期")
                        onExpired()
                        return
                    }
                    resp.ret != null && resp.ret != 0 -> {
                        Log.e(TAG, "getUpdates错误: ret=${resp.ret}")
                        delay(RECONNECT_DELAY_MS)
                        continue
                    }
                }

                resp.msgs?.forEach { msg ->
                    if (!running) return
                    try {
                        onNewMessage(msg)
                    } catch (e: Exception) {
                        Log.e(TAG, "消息处理异常: ${e.message}")
                    }
                }

                resp.getUpdatesBuf?.let { getUpdatesBuf = it }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "polling异常: ${e.message}")
                delay(RECONNECT_DELAY_MS)
            }
        }
    }
}