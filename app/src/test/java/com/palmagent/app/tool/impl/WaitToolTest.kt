package com.palmagent.app.tool.impl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WaitTool 单元测试
 *
 * 覆盖 P1-3 修复：
 * - duration_ms 通过 optionalLong 解析后 clamp 到 [100, 10000]ms
 * - 默认值 1000ms（key 缺失时）
 * - 非数字字符串回退到默认值 1000ms（P1-2 修复的间接验证）
 * - 返回格式包含目标时长和实际时长
 *
 * project_memory 约束：
 * - WAIT tool duration_ms 必须通过 coerceIn(100L, 10_000L) 限制
 * - WAIT tool 返回格式必须为 "✓ 等待完成，累计等待 Xms（目标Xms）"
 */
class WaitToolTest {

    private lateinit var waitTool: WaitTool

    @Before
    fun setUp() {
        waitTool = WaitTool()
    }

    @Test
    fun `defaultDuration_is1000ms`() = runBlocking {
        val result = waitTool.execute(emptyMap())
        assertTrue("应使用默认值 1000ms", result.data!!.contains("目标 1000ms"))
    }

    @Test
    fun `explicitDuration_withinRange_isRespected`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to 200L))
        assertTrue("200ms 在范围内应被尊重", result.data!!.contains("目标 200ms"))
    }

    /**
     * P1-3 核心修复点：低于下限 100ms 的值被 clamp 到 100ms
     */
    @Test
    fun `durationBelow100ms_clampedTo100`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to 50L))
        assertTrue("50ms 应被 clamp 到 100ms", result.data!!.contains("目标 100ms"))
    }

    /**
     * P1-3 核心修复点：高于上限 10000ms 的值被 clamp 到 10000ms
     */
    @Test
    fun `durationAbove10000ms_clampedTo10000`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to 99999L))
        assertTrue("99999ms 应被 clamp 到 10000ms", result.data!!.contains("目标 10000ms"))
    }

    /**
     * 边界值：100ms（下限）应被尊重
     */
    @Test
    fun `durationExactly100ms_isRespected`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to 100L))
        assertTrue("100ms 边界值应被尊重", result.data!!.contains("目标 100ms"))
    }

    /**
     * 边界值：10000ms（上限）应被尊重
     */
    @Test
    fun `durationExactly10000ms_isRespected`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to 10000L))
        assertTrue("10000ms 边界值应被尊重", result.data!!.contains("目标 10000ms"))
    }

    /**
     * P1-2 间接验证：非数字字符串回退到默认值 1000ms
     */
    @Test
    fun `nonNumericDuration_fallsBackToDefault`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to "invalid"))
        assertTrue("非数字应回退到默认 1000ms", result.data!!.contains("目标 1000ms"))
    }

    /**
     * 数字字符串应被正确解析
     */
    @Test
    fun `numericStringDuration_isParsed`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to "500"))
        assertTrue("数字字符串 '500' 应被解析为 500ms", result.data!!.contains("目标 500ms"))
    }

    /**
     * project_memory 约束：返回格式必须为 "✓ 等待完成，累计等待 Xms（目标Xms）"
     */
    @Test
    fun `returnFormat_matchesProjectConstraint`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to 300L))
        val expectedPattern = Regex("""✓ 等待完成，累计等待 \d+ms（目标 300ms）""")
        assertTrue(
            "返回格式应匹配 '✓ 等待完成，累计等待 Xms（目标 300ms）'，实际: ${result.data}",
            expectedPattern.matches(result.data!!)
        )
    }

    /**
     * 负数 duration 被 clamp 到 100ms
     */
    @Test
    fun `negativeDuration_clampedTo100`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to -500L))
        assertTrue("-500ms 应被 clamp 到 100ms", result.data!!.contains("目标 100ms"))
    }

    @Test
    fun `result_isAlwaysSuccess`() = runBlocking {
        val result = waitTool.execute(mapOf("duration_ms" to 100L))
        assertTrue("WAIT 应总是返回成功", result.isSuccess)
    }
}
