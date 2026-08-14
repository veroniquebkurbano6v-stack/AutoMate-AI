package com.palmagent.app.floating

import android.os.Handler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito

/**
 * AskUserManager 单元测试（批量提问版）
 *
 * 验证目标：
 * 1. requestAnswer 设置 currentRequest 并触发回调链路就绪
 * 2. onUserAnswer(answers) 触发回调，answers 原样回传，cancelled=false
 * 3. onUserCancel 触发回调，cancelled=true，answers 为空
 * 4. cancel 触发回调，cancelled=true（P0：通知前一个调用方）
 * 5. 新 requestAnswer 抢占前一个请求时，前一个回调被触发（cancelled=true）
 * 6. 回调触发后 currentRequest/currentCallback 清空（避免重复触发）
 * 7. 空答案列表（用户提交但未作答）cancelled=false，区别于 cancel
 *
 * 技术要点：
 * - mockConstruction(Handler) 避免 RuntimeException("Stub!")（FloatingProgressManager 依赖 Handler）
 * - Dispatchers.setMain 避免 IllegalStateException（FloatingProgressManager scope 初始化）
 * - 反射清理 AskUserManager 内部状态实现测试间隔离
 * - AskUserManager 是 object 单例，每个测试后必须重置 currentCallback/currentRequest
 */
class AskUserManagerTest {

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

    @Before
    fun setUp() {
        resetAskUserManagerState()
    }

    @After
    fun tearDown() {
        resetAskUserManagerState()
    }

    /** 反射清空 AskUserManager 的 currentCallback 和 currentRequest，避免测试间状态污染 */
    private fun resetAskUserManagerState() {
        val cbField = AskUserManager::class.java.getDeclaredField("currentCallback")
        cbField.isAccessible = true
        cbField.set(AskUserManager, null)

        val reqField = AskUserManager::class.java.getDeclaredField("currentRequest")
        reqField.isAccessible = true
        reqField.set(AskUserManager, null)
    }

    /** 构造一个单问题 AskRequest，减少样板代码 */
    private fun singleQuestionRequest(questionText: String): AskUserManager.AskRequest {
        return AskUserManager.AskRequest(
            questions = listOf(
                com.palmagent.app.model.Question(
                    question = questionText,
                    header = "测试",
                    options = listOf(
                        com.palmagent.app.model.QuestionOption(label = "选项A"),
                        com.palmagent.app.model.QuestionOption(label = "选项B")
                    )
                )
            )
        )
    }

    @Test
    fun `requestAnswer sets currentRequest`() {
        val req = singleQuestionRequest("需要发短信给哪个联系人？")

        AskUserManager.requestAnswer(req) { /* no-op */ }

        assertEquals(req, AskUserManager.currentRequest)
    }

    @Test
    fun `onUserAnswer triggers callback with answers and cancelled false`() {
        var received: AskUserManager.AskResponse? = null
        val req = singleQuestionRequest("请输入验证码")
        AskUserManager.requestAnswer(req) { response -> received = response }

        val answers = listOf(
            com.palmagent.app.model.QuestionAnswer(
                question = "请输入验证码",
                answer = listOf("123456")
            )
        )
        AskUserManager.onUserAnswer(answers)

        assertNotNull(received)
        assertEquals(answers, received!!.answers)
        assertFalse(received!!.cancelled)
        // 触发后状态清空
        assertNull(AskUserManager.currentRequest)
    }

    @Test
    fun `onUserCancel triggers callback with cancelled true and empty answers`() {
        var received: AskUserManager.AskResponse? = null
        AskUserManager.requestAnswer(singleQuestionRequest("测试")) { response ->
            received = response
        }

        AskUserManager.onUserCancel()

        assertNotNull(received)
        assertTrue(received!!.cancelled)
        assertTrue(received!!.answers.isEmpty())
        assertNull(AskUserManager.currentRequest)
    }

    @Test
    fun `cancel triggers callback with cancelled true`() {
        var received: AskUserManager.AskResponse? = null
        AskUserManager.requestAnswer(singleQuestionRequest("测试")) { response ->
            received = response
        }

        AskUserManager.cancel()

        assertNotNull(received)
        assertTrue(received!!.cancelled)
        assertNull(AskUserManager.currentRequest)
    }

    /**
     * P0 教训验证：新请求必须 cancel 前一个，避免回调丢失
     * 场景：第一个请求等待中，第二个 requestAnswer 抢占，第一个回调必须被触发（cancelled=true）
     */
    @Test
    fun `new requestAnswer cancels previous request and triggers its callback`() {
        var firstReceived: AskUserManager.AskResponse? = null
        var secondReceived: AskUserManager.AskResponse? = null

        AskUserManager.requestAnswer(singleQuestionRequest("第一个问题")) { response ->
            firstReceived = response
        }

        // 抢占前一个
        AskUserManager.requestAnswer(singleQuestionRequest("第二个问题")) { response ->
            secondReceived = response
        }

        // 第一个回调被触发，cancelled=true
        assertNotNull(firstReceived)
        assertTrue(firstReceived!!.cancelled)

        // 第二个请求的 currentRequest 已就绪
        assertEquals(
            "第二个问题",
            AskUserManager.currentRequest?.questions?.firstOrNull()?.question
        )

        // 第二个请求尚未收到回调
        assertNull(secondReceived)

        // 用户回答第二个问题
        val answers = listOf(
            com.palmagent.app.model.QuestionAnswer(
                question = "第二个问题",
                answer = listOf("回答2")
            )
        )
        AskUserManager.onUserAnswer(answers)
        assertNotNull(secondReceived)
        assertEquals(answers, secondReceived!!.answers)
        assertFalse(secondReceived!!.cancelled)
    }

    @Test
    fun `callback is cleared after trigger to prevent duplicate invocation`() {
        var callCount = 0
        AskUserManager.requestAnswer(singleQuestionRequest("测试")) { callCount++ }

        AskUserManager.onUserAnswer(emptyList())
        AskUserManager.cancel()  // 应该不会再次触发回调

        assertEquals(1, callCount)
    }

    /**
     * 空答案列表（用户点提交但未作答）不算 cancel，cancelled=false
     * 区别于 onUserCancel/cancel（cancelled=true）
     */
    @Test
    fun `onUserAnswer with empty answers list still triggers callback with cancelled false`() {
        var received: AskUserManager.AskResponse? = null
        AskUserManager.requestAnswer(singleQuestionRequest("测试")) { response ->
            received = response
        }

        AskUserManager.onUserAnswer(emptyList())

        assertNotNull(received)
        assertTrue(received!!.answers.isEmpty())
        assertFalse(received!!.cancelled)
    }

    /**
     * 多问题批量提交：answers 数量与 questions 数量无需强制一致（UI 层负责校验）
     * Manager 仅负责透传
     */
    @Test
    fun `onUserAnswer with multiple answers passes through all answers`() {
        var received: AskUserManager.AskResponse? = null
        val req = AskUserManager.AskRequest(
            questions = listOf(
                com.palmagent.app.model.Question(
                    question = "问题1",
                    header = "h1",
                    options = listOf(
                        com.palmagent.app.model.QuestionOption(label = "A"),
                        com.palmagent.app.model.QuestionOption(label = "B")
                    )
                ),
                com.palmagent.app.model.Question(
                    question = "问题2",
                    header = "h2",
                    options = listOf(
                        com.palmagent.app.model.QuestionOption(label = "X"),
                        com.palmagent.app.model.QuestionOption(label = "Y")
                    ),
                    multiSelect = true
                )
            )
        )
        AskUserManager.requestAnswer(req) { response -> received = response }

        val answers = listOf(
            com.palmagent.app.model.QuestionAnswer(question = "问题1", answer = listOf("A")),
            com.palmagent.app.model.QuestionAnswer(question = "问题2", answer = listOf("X", "Y"))
        )
        AskUserManager.onUserAnswer(answers)

        assertNotNull(received)
        assertEquals(2, received!!.answers.size)
        assertEquals(listOf("A"), received!!.answers[0].answer)
        assertEquals(listOf("X", "Y"), received!!.answers[1].answer)
        assertFalse(received!!.cancelled)
    }
}
