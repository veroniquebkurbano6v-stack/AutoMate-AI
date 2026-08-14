package com.palmagent.app.channel

import android.util.Log

object ChannelManager {
    private const val TAG = "ChannelManager"

    interface OnMessageReceivedListener {
        fun onMessageReceived(channel: Channel, message: String, messageID: String)
    }

    private val handlers = LinkedHashMap<Channel, ChannelHandler>()
    private var messageListener: OnMessageReceivedListener? = null

    private var wechatBotToken: String? = null
    private var wechatApiBaseUrl: String? = null

    fun init(
        wechatBotToken: String? = null,
        wechatApiBaseUrl: String? = null
    ) {
        this.wechatBotToken = wechatBotToken
        this.wechatApiBaseUrl = wechatApiBaseUrl
    }

    fun setOnMessageReceivedListener(listener: OnMessageReceivedListener?) {
        this.messageListener = listener
    }

    fun registerHandler(channel: Channel, handler: ChannelHandler) {
        handlers[channel] = handler
        handler.setMessageReceivedListener { message, messageId ->
            messageListener?.onMessageReceived(channel, message, messageId)
        }
        Log.d(TAG, "已注册Channel: ${channel.displayName}")
    }

    fun getHandler(channel: Channel): ChannelHandler? = handlers[channel]

    fun start(channel: Channel) {
        handlers[channel]?.start()
        Log.d(TAG, "启动Channel: ${channel.displayName}")
    }

    fun stop(channel: Channel) {
        handlers[channel]?.stop()
        Log.d(TAG, "停止Channel: ${channel.displayName}")
    }

    fun sendMessage(channel: Channel, text: String, replyToMessageId: String? = null): Boolean {
        return handlers[channel]?.sendMessage(text, replyToMessageId) ?: false
    }

    fun sendImage(channel: Channel, imageBytes: ByteArray, replyToMessageId: String? = null): Boolean {
        return handlers[channel]?.sendImage(imageBytes, replyToMessageId) ?: false
    }

    fun flushMessages(channel: Channel) {
        handlers[channel]?.setTypingStatus(false)
    }

    fun reconnectIfNeeded() {
        for ((channel, handler) in handlers) {
            if (!handler.isConnected()) {
                Log.i(TAG, "Reconnecting channel: ${channel.displayName}")
                handler.start()
            }
        }
    }

    fun isConnected(channel: Channel): Boolean = handlers[channel]?.isConnected() ?: false

    fun getAllConnectedChannels(): List<Channel> {
        return handlers.filter { it.value.isConnected() }.keys.toList()
    }

    fun getWechatBotToken(): String? = wechatBotToken
    fun getWechatApiBaseUrl(): String? = wechatApiBaseUrl
}