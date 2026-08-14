package com.palmagent.app

import android.os.Handler
import com.palmagent.app.agent.AgentServiceFactory
import com.palmagent.app.channel.Channel
import com.palmagent.app.channel.ChannelHandler
import com.palmagent.app.channel.ChannelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito

/**
 * TaskOrchestrator 单元测试（v3.1 智能微信路由）
 *
 * 验证目标：
 * 1. shouldReportToWeChat 路由矩阵（4 种组合）
 * 2. tryAcquireTask / releaseTask / isHoldingTask 状态管理
 * 3. onTaskFinished 智能路由（LOCAL 不发微信 / WECHAT+已连接发微信 / WECHAT+未连接不发）
 * 4. tryCancel 智能路由（LOCAL 不发微信 / WECHAT+已连接发微信）
 *
 * 技术要点：
 * - mockConstruction(Handler) 避免 RuntimeException("Stub!")（FloatingProgressManager / LiveLogBuffer 初始化）
 * - Dispatchers.setMain 避免 IllegalStateException（FloatingProgressManager scope 初始化）
 * - 真实 ChannelManager + FakeChannelHandler 控制 isConnected 返回值和 sendMessage 计数
 * - 反射清理 ChannelManager.handlers 实现测试间状态隔离
 */
class TaskOrchestratorTest {

    companion object {
        private var handlerMock: MockedConstruction<Handler>? = null

        @BeforeClass
        @JvmStatic
        fun setMainDispatcher() {
            Dispatchers.setMain(StandardTestDispatcher())
            handlerMock = Mockito.mockConstruction(Handler::class.java)
        }

        @AfterClass
        @JvmStatic
        fun resetMainDispatcher() {
            Dispatchers.resetMain()
            handlerMock?.close()
        }
    }

    private lateinit var orchestrator: TaskOrchestrator

    @Before
    fun setUp() {
        clearChannelManagerHandlers()
        orchestrator = TaskOrchestrator(Mockito.mock(AgentServiceFactory::class.java))
    }

    @After
    fun tearDown() {
        clearChannelManagerHandlers()
    }

    private fun clearChannelManagerHandlers() {
        val handlersField = ChannelManager::class.java.getDeclaredField("handlers")
        handlersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (handlersField.get(ChannelManager) as MutableMap<Channel, ChannelHandler>).clear()
    }

    /** 注册 FakeChannelHandler 到 ChannelManager，返回该 handler 供断言 */
    private fun registerWeChatHandler(connected: Boolean): FakeChannelHandler {
        val handler = FakeChannelHandler(connected)
        ChannelManager.registerHandler(Channel.WECHAT, handler)
        return handler
    }

    // ======================== shouldReportToWeChat 路由矩阵 ========================

    @Test
    fun `shouldReportToWeChat returns true when WECHAT channel and connected`() {
        registerWeChatHandler(connected = true)
        assertTrue(orchestrator.shouldReportToWeChat(Channel.WECHAT))
    }

    @Test
    fun `shouldReportToWeChat returns false when WECHAT channel but not connected`() {
        registerWeChatHandler(connected = false)
        assertFalse(orchestrator.shouldReportToWeChat(Channel.WECHAT))
    }

    @Test
    fun `shouldReportToWeChat returns false when LOCAL channel even if WeChat connected`() {
        registerWeChatHandler(connected = true)
        assertFalse(orchestrator.shouldReportToWeChat(Channel.LOCAL))
    }

    @Test
    fun `shouldReportToWeChat returns false when LOCAL channel and WeChat not connected`() {
        registerWeChatHandler(connected = false)
        assertFalse(orchestrator.shouldReportToWeChat(Channel.LOCAL))
    }

    @Test
    fun `shouldReportToWeChat returns false when WeChat handler not registered`() {
        // 不注册任何 handler，ChannelManager.isConnected 返回 false
        assertFalse(orchestrator.shouldReportToWeChat(Channel.WECHAT))
    }

    // ======================== tryAcquireTask / releaseTask / isHoldingTask ========================

    @Test
    fun `tryAcquireTask first call returns true and sets activeChannel`() {
        val result = orchestrator.tryAcquireTask("msg-1", Channel.LOCAL)
        assertTrue(result)
        assertEquals(Channel.LOCAL, orchestrator.activeChannel)
    }

    @Test
    fun `tryAcquireTask second call returns false when task running`() {
        assertTrue(orchestrator.tryAcquireTask("msg-1", Channel.LOCAL))
        assertFalse(orchestrator.tryAcquireTask("msg-2", Channel.WECHAT))
        assertEquals(Channel.LOCAL, orchestrator.activeChannel)
    }

    @Test
    fun `releaseTask allows new acquire`() {
        assertTrue(orchestrator.tryAcquireTask("msg-1", Channel.LOCAL))
        orchestrator.releaseTask()
        assertNull(orchestrator.activeChannel)
        assertTrue(orchestrator.tryAcquireTask("msg-2", Channel.WECHAT))
        assertEquals(Channel.WECHAT, orchestrator.activeChannel)
    }

    @Test
    fun `isHoldingTask returns true only for current messageID`() {
        orchestrator.tryAcquireTask("msg-1", Channel.LOCAL)
        assertTrue(orchestrator.isHoldingTask("msg-1"))
        assertFalse(orchestrator.isHoldingTask("msg-other"))
    }

    @Test
    fun `isHoldingTask returns false after releaseTask`() {
        orchestrator.tryAcquireTask("msg-1", Channel.LOCAL)
        orchestrator.releaseTask()
        assertFalse(orchestrator.isHoldingTask("msg-1"))
    }

    // ======================== onTaskFinished 智能路由 ========================

    @Test
    fun `onTaskFinished LOCAL channel does not send to WeChat even if connected`() {
        val handler = registerWeChatHandler(connected = true)
        val msgId = "finish-local"

        assertTrue(orchestrator.tryAcquireTask(msgId, Channel.LOCAL))
        orchestrator.onTaskFinished(Channel.LOCAL, "任务完成", msgId)

        assertEquals(0, handler.sendMessageCount)
        assertNull(orchestrator.activeChannel)
    }

    @Test
    fun `onTaskFinished WECHAT channel with connection sends to WeChat`() {
        val handler = registerWeChatHandler(connected = true)
        val msgId = "finish-wechat"

        assertTrue(orchestrator.tryAcquireTask(msgId, Channel.WECHAT))
        orchestrator.onTaskFinished(Channel.WECHAT, "任务完成", msgId)

        assertEquals(1, handler.sendMessageCount)
        assertEquals("任务完成", handler.lastSentMessage)
    }

    @Test
    fun `onTaskFinished WECHAT channel without connection does not send`() {
        val handler = registerWeChatHandler(connected = false)
        val msgId = "finish-wechat-disconnected"

        assertTrue(orchestrator.tryAcquireTask(msgId, Channel.WECHAT))
        orchestrator.onTaskFinished(Channel.WECHAT, "任务完成", msgId)

        assertEquals(0, handler.sendMessageCount)
    }

    @Test
    fun `onTaskFinished with empty message sends default text when WeChat connected`() {
        val handler = registerWeChatHandler(connected = true)
        val msgId = "finish-empty"

        orchestrator.tryAcquireTask(msgId, Channel.WECHAT)
        orchestrator.onTaskFinished(Channel.WECHAT, "   ", msgId)

        assertEquals(1, handler.sendMessageCount)
        assertEquals("任务已结束", handler.lastSentMessage)
    }

    @Test
    fun `onTaskFinished ignores if not holding task`() {
        val handler = registerWeChatHandler(connected = true)
        // 不 acquire，直接调用 onTaskFinished
        orchestrator.onTaskFinished(Channel.WECHAT, "任务完成", "non-existent-msg")

        assertEquals(0, handler.sendMessageCount)
    }

    // ======================== tryCancel 智能路由 ========================

    @Test
    fun `tryCancel LOCAL channel does not send to WeChat even if connected`() {
        val handler = registerWeChatHandler(connected = true)
        val msgId = "cancel-local"

        assertTrue(orchestrator.tryAcquireTask(msgId, Channel.LOCAL))
        assertTrue(orchestrator.tryCancel(msgId))

        Thread.sleep(200) // 等待 scope.launch 异步完成

        assertEquals(0, handler.sendMessageCount)
        assertNull(orchestrator.activeChannel)
    }

    @Test
    fun `tryCancel WECHAT channel with connection sends to WeChat`() {
        val handler = registerWeChatHandler(connected = true)
        val msgId = "cancel-wechat"

        assertTrue(orchestrator.tryAcquireTask(msgId, Channel.WECHAT))
        assertTrue(orchestrator.tryCancel(msgId))

        Thread.sleep(200)

        assertEquals(1, handler.sendMessageCount)
        assertTrue(handler.lastSentMessage?.contains("取消") == true)
    }

    @Test
    fun `tryCancel WECHAT channel without connection does not send`() {
        val handler = registerWeChatHandler(connected = false)
        val msgId = "cancel-wechat-disconnected"

        assertTrue(orchestrator.tryAcquireTask(msgId, Channel.WECHAT))
        assertTrue(orchestrator.tryCancel(msgId))

        Thread.sleep(200)

        assertEquals(0, handler.sendMessageCount)
    }

    @Test
    fun `tryCancel returns false for non-existent messageID`() {
        registerWeChatHandler(connected = true)
        orchestrator.tryAcquireTask("msg-1", Channel.LOCAL)

        assertFalse(orchestrator.tryCancel("wrong-msg-id"))
        assertEquals(Channel.LOCAL, orchestrator.activeChannel)
    }

    // ======================== cancelCurrentTask 智能路由 ========================

    @Test
    fun `cancelCurrentTask LOCAL channel does not send to WeChat`() {
        val handler = registerWeChatHandler(connected = true)
        val msgId = "cancel-current-local"

        orchestrator.tryAcquireTask(msgId, Channel.LOCAL)
        orchestrator.cancelCurrentTask()

        Thread.sleep(200)

        assertEquals(0, handler.sendMessageCount)
        assertNull(orchestrator.activeChannel)
    }

    @Test
    fun `cancelCurrentTask WECHAT channel with connection sends to WeChat`() {
        val handler = registerWeChatHandler(connected = true)
        val msgId = "cancel-current-wechat"

        orchestrator.tryAcquireTask(msgId, Channel.WECHAT)
        orchestrator.cancelCurrentTask()

        Thread.sleep(200)

        assertEquals(1, handler.sendMessageCount)
    }

    // ======================== taskStateListener 回调 ========================

    @Test
    fun `onTaskFinished notifies taskStateListener with false`() {
        registerWeChatHandler(connected = false)
        val stateChanges = mutableListOf<Boolean>()
        orchestrator.taskStateListener = object : TaskOrchestrator.TaskStateListener {
            override fun onTaskStateChanged(running: Boolean) {
                stateChanges.add(running)
            }
        }

        orchestrator.tryAcquireTask("msg-listener", Channel.LOCAL)
        orchestrator.onTaskFinished(Channel.LOCAL, "完成", "msg-listener")

        assertEquals(listOf(false), stateChanges)
    }

    @Test
    fun `tryCancel notifies taskStateListener with false`() {
        registerWeChatHandler(connected = false)
        val stateChanges = mutableListOf<Boolean>()
        orchestrator.taskStateListener = object : TaskOrchestrator.TaskStateListener {
            override fun onTaskStateChanged(running: Boolean) {
                stateChanges.add(running)
            }
        }

        orchestrator.tryAcquireTask("msg-cancel-listener", Channel.LOCAL)
        orchestrator.tryCancel("msg-cancel-listener")

        assertEquals(listOf(false), stateChanges)
    }

    // ======================== 辅助类 ========================

    /**
     * 假 ChannelHandler：控制 isConnected 返回值，记录 sendMessage 调用次数和内容
     */
    private class FakeChannelHandler(
        private val connected: Boolean
    ) : ChannelHandler {
        override val channel = Channel.WECHAT
        var sendMessageCount = 0
        var lastSentMessage: String? = null

        override fun start() {}
        override fun stop() {}
        override fun isConnected(): Boolean = connected
        override fun sendMessage(text: String, replyToMessageId: String?): Boolean {
            sendMessageCount++
            lastSentMessage = text
            return true
        }
        override fun sendImage(imageBytes: ByteArray, replyToMessageId: String?) = false
        override fun setTypingStatus(isTyping: Boolean) = false
        override fun setMessageReceivedListener(listener: ((String, String) -> Unit)?) {}
    }
}
