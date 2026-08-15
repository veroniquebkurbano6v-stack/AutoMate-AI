package com.palmagent.app

import com.palmagent.app.agent.Plan
import com.palmagent.app.channel.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * AppCoordinator 单元测试（v3.1 sendCommand 智能路由）
 *
 * 验证目标：
 * 1. sendCommand 成功时返回 true（无任务运行，tryAcquireTask 成功）
 * 2. sendCommand 失败时返回 false（有任务运行，tryAcquireTask 被拒绝）
 * 3. sendCommand 使用 Channel.LOCAL（非 WECHAT）—— 修复 v3.0 之前的 `?: Channel.WECHAT` 路由错误
 * 4. sendCommand 失败时不调用 startNewTask
 *
 * 技术要点（Kotlin + Mockito 无 mockito-kotlin 的 null 问题）：
 * - Mockito ArgumentMatchers 的所有方法（any/anyString/eq/any(Class)）对非原始类型均返回 null
 *   （eq 内部用 Primitives.defaultValueFor()，any/anyString 直接返回 null）
 * - null 传给 Kotlin non-null 参数时触发 NullPointerException
 *   （Kotlin 编译器对平台类型传给 non-null 参数插入 Intrinsics.checkExpressionValueIsNotNull）
 * - 即使 doReturn().when() 避免 mock 方法真实执行，参数 null 检查仍在调用点发生
 * - 解决方案：包装所有 matcher，用 `?: 默认值` 替换 null 返回值
 *   matcher 在 ArgumentMatchers 方法调用时注册到 Mockito 栈，与返回值无关
 * - verify 中混合原始值和 matcher 时，所有参数必须都用 matcher（用 eqXxx() 包裹原始值）
 */
class AppCoordinatorTest {

    private lateinit var mockOrchestrator: TaskOrchestrator
    private lateinit var coordinator: AppCoordinator

    @Before
    fun setUp() {
        mockOrchestrator = Mockito.mock(TaskOrchestrator::class.java)
        coordinator = AppCoordinator(mockOrchestrator)
    }

    @Test
    fun `sendCommand returns true when tryAcquireTask succeeds`() {
        Mockito.doReturn(true).`when`(mockOrchestrator)
            .tryAcquireTask(anyStr(), anyChannel())

        val result = coordinator.sendCommand("打开微信")

        assertTrue(result)
        // verify 混合原始值和 matcher 时所有参数必须都是 matcher
        // startNewTask 有 4 个参数（含默认 plan），必须补齐第 4 个 matcher
        verify(mockOrchestrator).startNewTask(eqChannel(Channel.LOCAL), eqStr("打开微信"), anyStr(), anyPlan())
    }

    @Test
    fun `sendCommand returns false when tryAcquireTask fails`() {
        Mockito.doReturn(false).`when`(mockOrchestrator)
            .tryAcquireTask(anyStr(), anyChannel())

        val result = coordinator.sendCommand("打开微信")

        assertFalse(result)
        verify(mockOrchestrator, never()).startNewTask(anyChannel(), anyStr(), anyStr(), anyPlan())
    }

    @Test
    fun `sendCommand uses Channel_LOCAL not WECHAT`() {
        Mockito.doReturn(true).`when`(mockOrchestrator)
            .tryAcquireTask(anyStr(), anyChannel())

        coordinator.sendCommand("测试任务")

        // 验证 tryAcquireTask 使用 Channel.LOCAL（修复前会使用 ?: Channel.WECHAT 导致路由错误）
        verify(mockOrchestrator).tryAcquireTask(anyStr(), eqChannel(Channel.LOCAL))
        // 验证 startNewTask 使用 Channel.LOCAL（含默认 plan 参数）
        verify(mockOrchestrator).startNewTask(eqChannel(Channel.LOCAL), anyStr(), anyStr(), anyPlan())
    }

    // ======================== Matcher 包装函数 ========================
    // 所有 ArgumentMatchers 方法对非原始类型返回 null，需用 `?: 默认值` 替换
    // matcher 在 ArgumentMatchers 方法调用时注册到 Mockito 栈，与返回值无关

    private fun anyStr(): String = ArgumentMatchers.anyString() ?: ""

    private fun anyChannel(): Channel =
        ArgumentMatchers.any(Channel::class.java) ?: Channel.LOCAL

    private fun eqStr(value: String): String = ArgumentMatchers.eq(value) ?: value

    private fun eqChannel(value: Channel): Channel = ArgumentMatchers.eq(value) ?: value

    private fun anyPlan(): Plan? = ArgumentMatchers.isNull(Plan::class.java) ?: null
}
