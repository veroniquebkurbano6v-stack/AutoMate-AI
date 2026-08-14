package com.palmagent.app.tool.impl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FinishToolTest {

    private lateinit var finishTool: FinishTool

    @Before
    fun setUp() {
        finishTool = FinishTool()
    }

    @Test
    fun `finishWithSummaryAndNextAction_returnsFormattedMessage`() = runBlocking {
        val result = finishTool.execute(mapOf(
            "summary" to "已为您打开呼吸内科预约挂号页面",
            "next_action" to "请您自行选择医生和就诊时间段完成预约"
        ))

        assertTrue(result.isSuccess)
        assertEquals("✅ 已为您打开呼吸内科预约挂号页面\n请您自行选择医生和就诊时间段完成预约", result.data)
    }

    @Test
    fun `finishWithEmptySummary_usesDefault`() = runBlocking {
        val result = finishTool.execute(mapOf("next_action" to "请检查网络连接"))

        assertTrue(result.isSuccess)
        assertEquals("✅ 任务已完成\n请检查网络连接", result.data)
    }

    @Test
    fun `finishWithEmptyNextAction_usesDefault`() = runBlocking {
        val result = finishTool.execute(mapOf("summary" to "已成功发送消息"))

        assertTrue(result.isSuccess)
        assertEquals("✅ 已成功发送消息\n任务已完成", result.data)
    }

    @Test
    fun `finishWithBlankSummary_usesDefault`() = runBlocking {
        val result = finishTool.execute(mapOf(
            "summary" to "   ",
            "next_action" to "请手动确认"
        ))

        assertTrue(result.isSuccess)
        assertEquals("✅ 任务已完成\n请手动确认", result.data)
    }

    @Test
    fun `finishWithBothEmpty_usesDefaults`() = runBlocking {
        val result = finishTool.execute(emptyMap())

        assertTrue(result.isSuccess)
        assertEquals("✅ 任务已完成\n任务已完成", result.data)
    }

    @Test
    fun `getParameters_containsSummaryAndNextAction_notMessage`() {
        val params = finishTool.getParameters()
        val names = params.map { it.name }

        assertTrue("应包含 summary 参数", "summary" in names)
        assertTrue("应包含 next_action 参数", "next_action" in names)
        assertFalse("不应包含旧的 message 参数", "message" in names)
        assertEquals("应只有 2 个参数", 2, params.size)
    }

    @Test
    fun `getName_returnsFinish`() {
        assertEquals("finish", finishTool.getName())
    }

    @Test
    fun `getDescriptionCN_mentionsBothParams`() {
        val desc = finishTool.getDescriptionCN()
        assertTrue("中文描述应包含 summary", desc.contains("summary"))
        assertTrue("中文描述应包含 next_action", desc.contains("next_action"))
    }
}
