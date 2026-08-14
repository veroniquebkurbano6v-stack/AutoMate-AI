package com.palmagent.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ActionTrackingUseCase 单元测试
 *
 * 覆盖 P2-8 修复：
 * - track / getCurrentWarning / reset 加 @Synchronized 防止并发调用导致状态不一致
 * （@Synchronized 是 JVM 层面的方法级锁，单元测试通过单线程验证逻辑正确性即可）
 *
 * 覆盖 project_memory 约束：
 * - WAIT 操作必须免于重复检测（不更新签名、不累加计数）
 * - 阈值 MAX_IDENTICAL_ACTION_BEFORE_WARN = 2（连续 2 次相同操作触发警告）
 */
class ActionTrackingUseCaseTest {

    private lateinit var useCase: ActionTrackingUseCase

    @Before
    fun setUp() {
        useCase = ActionTrackingUseCase()
    }

    // ===== actionSignature 测试 =====

    @Test
    fun `actionSignature_includesActionType`() {
        val sig = useCase.actionSignature("CLICK", mapOf("x" to 100, "y" to 200))
        assertTrue("签名应包含动作类型", sig.startsWith("CLICK("))
    }

    @Test
    fun `actionSignature_includesKeyParams`() {
        val sig = useCase.actionSignature("CLICK", mapOf("x" to 100, "y" to 200, "ignored" to "xxx"))
        assertTrue("签名应包含 x 参数", sig.contains("x=100"))
        assertTrue("签名应包含 y 参数", sig.contains("y=200"))
        assertFalse("签名不应包含非关键参数", sig.contains("ignored"))
    }

    @Test
    fun `actionSignature_includesScreenPackage`() {
        val sig = useCase.actionSignature("CLICK", mapOf("x" to 100), screenPackage = "com.tencent.mm")
        assertTrue("签名应包含屏幕包名", sig.contains("@com.tencent.mm"))
    }

    @Test
    fun `actionSignature_nullScreenPackage`() {
        val sig = useCase.actionSignature("CLICK", mapOf("x" to 100), screenPackage = null)
        assertTrue("null 包名应以 @null 结尾", sig.endsWith("@null"))
    }

    // ===== track 测试 =====

    @Test
    fun `track_firstAction_noWarning`() {
        val result = useCase.track("CLICK(x=1)@pkg")
        assertFalse("首次操作不应触发警告", result.shouldWarn)
        assertEquals("", result.warningMessage)
    }

    @Test
    fun `track_sameActionSecondTime_triggersWarning`() {
        val sig = "CLICK(x=1,y=2)@pkg"
        useCase.track(sig)
        val result = useCase.track(sig)
        assertTrue("连续 2 次相同操作应触发警告", result.shouldWarn)
        assertTrue("警告消息应包含操作签名", result.warningMessage.contains(sig))
    }

    @Test
    fun `track_differentAction_resetsCount`() {
        useCase.track("CLICK(x=1)@pkg")
        val result = useCase.track("CLICK(x=2)@pkg")  // 不同签名
        assertFalse("不同操作不应触发警告", result.shouldWarn)
    }

    @Test
    fun `track_sameActionThreeTimes_continuesWarning`() {
        val sig = "CLICK(x=1)@pkg"
        useCase.track(sig)
        useCase.track(sig)
        val result = useCase.track(sig)
        assertTrue("连续 3 次相同操作应继续触发警告", result.shouldWarn)
        assertTrue("警告消息应显示 3 次", result.warningMessage.contains("3 次"))
    }

    /**
     * project_memory 约束：WAIT 操作免于重复检测
     */
    @Test
    fun `track_waitAction_doesNotUpdateState`() {
        val clickSig = "CLICK(x=1)@pkg"
        useCase.track(clickSig)

        // WAIT 不应影响 CLICK 的计数
        val waitResult = useCase.track("WAIT()@pkg")
        assertFalse("WAIT 不应触发警告", waitResult.shouldWarn)

        // 再次 track 相同 CLICK，应触发警告（说明 WAIT 没有重置计数）
        val clickResult = useCase.track(clickSig)
        assertTrue("WAIT 不应重置 CLICK 计数", clickResult.shouldWarn)
    }

    @Test
    fun `track_waitAction_doesNotChangeLastSignature`() {
        val clickSig = "CLICK(x=1)@pkg"
        // track 2 次 CLICK 达到警告阈值
        useCase.track(clickSig)
        useCase.track(clickSig)
        // 此时 consecutiveSameActionCount=2, lastActionSignature=clickSig

        // WAIT 不应改变 lastActionSignature
        useCase.track("WAIT()@pkg")

        // 当前警告应仍基于 CLICK，不是 WAIT
        val warning = useCase.getCurrentWarning()
        assertTrue("警告应基于 CLICK 签名", warning.contains("CLICK"))
    }

    @Test
    fun `track_emptySignature_doesNotWarn`() {
        val result = useCase.track("")
        assertFalse("空签名不应触发警告", result.shouldWarn)
    }

    // ===== getCurrentWarning 测试 =====

    @Test
    fun `getCurrentWarning_belowThreshold_returnsEmpty`() {
        useCase.track("CLICK(x=1)@pkg")  // 只 1 次
        assertEquals("", useCase.getCurrentWarning())
    }

    @Test
    fun `getCurrentWarning_atThreshold_returnsWarning`() {
        val sig = "CLICK(x=1)@pkg"
        useCase.track(sig)
        useCase.track(sig)
        val warning = useCase.getCurrentWarning()
        assertTrue("达到阈值应返回警告", warning.isNotEmpty())
        assertTrue("警告应包含 REQUEST_USER_ACTION 建议", warning.contains("REQUEST_USER_ACTION"))
    }

    /**
     * getCurrentWarning 不应更新状态
     */
    @Test
    fun `getCurrentWarning_doesNotUpdateState`() {
        val sig = "CLICK(x=1)@pkg"
        useCase.track(sig)  // 1 次
        useCase.getCurrentWarning()  // 调用 getCurrentWarning
        val result = useCase.track(sig)  // 应该是第 2 次，触发警告
        assertTrue("getCurrentWarning 不应影响 track 计数", result.shouldWarn)
    }

    // ===== reset 测试 =====

    @Test
    fun `reset_clearsCount`() {
        val sig = "CLICK(x=1)@pkg"
        useCase.track(sig)
        useCase.track(sig)
        assertTrue(useCase.getCurrentWarning().isNotEmpty())

        useCase.reset()

        assertEquals("", useCase.getCurrentWarning())
    }

    @Test
    fun `reset_allowsRestartTracking`() {
        val sig = "CLICK(x=1)@pkg"
        useCase.track(sig)
        useCase.track(sig)
        useCase.reset()

        // reset 后重新 track 相同签名，第一次不应触发警告
        val result = useCase.track(sig)
        assertFalse("reset 后首次操作不应触发警告", result.shouldWarn)
    }

    // ===== 集成场景测试 =====

    @Test
    fun `scenario_alternatingActions_neverWarns`() {
        useCase.track("CLICK(x=1)@pkg")
        useCase.track("CLICK(x=2)@pkg")
        useCase.track("CLICK(x=1)@pkg")
        useCase.track("CLICK(x=2)@pkg")
        assertEquals("交替操作不应触发警告", "", useCase.getCurrentWarning())
    }

    @Test
    fun `scenario_waitBetweenSameActions_stillWarns`() {
        val sig = "CLICK(x=1)@pkg"
        useCase.track(sig)
        useCase.track("WAIT()@pkg")  // WAIT 不重置
        useCase.track(sig)
        assertTrue("WAIT 中断不应清除重复计数", useCase.getCurrentWarning().isNotEmpty())
    }
}
