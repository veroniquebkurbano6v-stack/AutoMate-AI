package com.palmagent.app.channel

import com.palmagent.app.AgentApplication
import com.palmagent.app.R
import com.palmagent.app.TaskOrchestrator
import com.palmagent.app.channel.wechat.WeChatChannelHandler
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.utils.KVUtils

class ChannelSetup(
    private val taskOrchestrator: TaskOrchestrator
) {

    fun setup() {
        val wechatHandler = WeChatChannelHandler(AgentApplication.instance)
        ChannelManager.registerHandler(Channel.WECHAT, wechatHandler)

        ChannelManager.init(
            wechatBotToken = KVUtils.getWechatBotToken().ifEmpty { null },
            wechatApiBaseUrl = KVUtils.getWechatApiBaseUrl().ifEmpty { null }
        )

        ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
            override fun onMessageReceived(channel: Channel, message: String, messageID: String) {
                val app = AgentApplication.instance
                if (!GUIAccessibilityService.isRunning) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_no_accessibility), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                if (!taskOrchestrator.tryAcquireTask(messageID, channel)) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_task_in_progress), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                taskOrchestrator.startNewTask(channel, message, messageID)
            }
        })

        ChannelManager.start(Channel.WECHAT)
    }
}