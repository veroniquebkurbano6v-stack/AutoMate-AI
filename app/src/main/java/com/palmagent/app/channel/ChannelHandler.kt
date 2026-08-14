package com.palmagent.app.channel

interface ChannelHandler {
    val channel: Channel
    fun start()
    fun stop()
    fun isConnected(): Boolean
    fun sendMessage(text: String, replyToMessageId: String? = null): Boolean
    fun sendImage(imageBytes: ByteArray, replyToMessageId: String? = null): Boolean
    fun setTypingStatus(isTyping: Boolean): Boolean
    fun setMessageReceivedListener(listener: ((String, String) -> Unit)?) {}
}