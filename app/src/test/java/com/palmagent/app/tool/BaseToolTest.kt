package com.palmagent.app.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BaseTool 数值解析单元测试
 *
 * 覆盖 P1-2 修复：
 * - optionalInt/optionalLong 的 toInt()/toLong() → toIntOrNull()/toLongOrNull() ?: default
 * - requireInt/requireLong 的 toInt()/toLong() → toIntOrNull() ?: throw IllegalArgumentException
 *
 * 测试策略：通过反射调用 protected 方法（Kotlin protected 方法默认 final，无法子类化 override）。
 */
class BaseToolTest {

    /** 测试专用子类，仅用于实例化 BaseTool（abstract class 不能直接实例化） */
    private class TestableBaseTool : BaseTool() {
        override fun getName() = "test_tool"
        override fun getParameters() = emptyList<ToolParameter>()
        override suspend fun execute(params: Map<String, Any>) = ToolResult.success("ok")
        override fun getDescriptionEN() = "test"
        override fun getDescriptionCN() = "测试"
    }

    private val tool = TestableBaseTool()

    /** 反射调用 protected optionalInt */
    private fun optionalInt(params: Map<String, Any>, key: String, default: Int): Int {
        val method = BaseTool::class.java.getDeclaredMethod("optionalInt", Map::class.java, String::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(tool, params, key, default) as Int
    }

    /** 反射调用 protected optionalLong */
    private fun optionalLong(params: Map<String, Any>, key: String, default: Long): Long {
        val method = BaseTool::class.java.getDeclaredMethod("optionalLong", Map::class.java, String::class.java, Long::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(tool, params, key, default) as Long
    }

    /** 反射调用 protected requireInt */
    private fun requireInt(params: Map<String, Any>, key: String): Int {
        val method = BaseTool::class.java.getDeclaredMethod("requireInt", Map::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(tool, params, key) as Int
    }

    /** 反射调用 protected requireLong */
    private fun requireLong(params: Map<String, Any>, key: String): Long {
        val method = BaseTool::class.java.getDeclaredMethod("requireLong", Map::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(tool, params, key) as Long
    }

    // ===== optionalInt 测试 =====

    @Test
    fun `optionalInt_missingKey_returnsDefault`() {
        val result = optionalInt(emptyMap(), "count", 5)
        assertEquals(5, result)
    }

    @Test
    fun `optionalInt_numberValue_returnsValue`() {
        // 注意：optionalInt 的 coerceAtLeast(default.coerceAtLeast(1)) 使 default 兼作最小值
        // 所以 default=1 时传入 3 返回 3（3 >= 1 不被 clamp）
        val result = optionalInt(mapOf("count" to 3), "count", 1)
        assertEquals(3, result)
    }

    @Test
    fun `optionalInt_numericString_returnsParsed`() {
        val result = optionalInt(mapOf("count" to "7"), "count", 5)
        assertEquals(7, result)
    }

    /**
     * P1-2 核心修复点：LLM 传入非数字字符串时回退到 default，而非抛 NumberFormatException 崩溃
     */
    @Test
    fun `optionalInt_nonNumericString_returnsDefault`() {
        val result = optionalInt(mapOf("count" to "abc"), "count", 5)
        assertEquals(5, result)
    }

    @Test
    fun `optionalInt_emptyString_returnsDefault`() {
        val result = optionalInt(mapOf("count" to ""), "count", 5)
        assertEquals(5, result)
    }

    /**
     * 防御逻辑：LLM 可能传入 0 或负数，coerceAtLeast 保证至少返回 1（当 default >= 1 时）
     */
    @Test
    fun `optionalInt_zeroValue_clampedToDefault`() {
        val result = optionalInt(mapOf("count" to 0), "count", 5)
        assertEquals(5, result) // 0 被 coerceAtLeast(5) 提升到 5
    }

    @Test
    fun `optionalInt_negativeValue_clampedToDefault`() {
        val result = optionalInt(mapOf("count" to -1), "count", 3)
        assertEquals(3, result) // -1 被 coerceAtLeast(3) 提升到 3
    }

    // ===== optionalLong 测试 =====

    @Test
    fun `optionalLong_missingKey_returnsDefault`() {
        val result = optionalLong(emptyMap(), "delay", 1000L)
        assertEquals(1000L, result)
    }

    @Test
    fun `optionalLong_numberValue_returnsValue`() {
        val result = optionalLong(mapOf("delay" to 500L), "delay", 1000L)
        assertEquals(500L, result)
    }

    @Test
    fun `optionalLong_numericString_returnsParsed`() {
        val result = optionalLong(mapOf("delay" to "2000"), "delay", 1000L)
        assertEquals(2000L, result)
    }

    /**
     * P1-2 核心修复点：LLM 传入非数字字符串时回退到 default
     */
    @Test
    fun `optionalLong_nonNumericString_returnsDefault`() {
        val result = optionalLong(mapOf("delay" to "invalid"), "delay", 1000L)
        assertEquals(1000L, result)
    }

    /**
     * optionalLong 不做 clamp（与 optionalInt 不同），保留原值
     */
    @Test
    fun `optionalLong_zeroValue_notClamped`() {
        val result = optionalLong(mapOf("delay" to 0L), "delay", 1000L)
        assertEquals(0L, result)
    }

    @Test
    fun `optionalLong_negativeValue_notClamped`() {
        val result = optionalLong(mapOf("delay" to -100L), "delay", 1000L)
        assertEquals(-100L, result)
    }

    // ===== requireInt 测试 =====

    @Test
    fun `requireInt_missingKey_throwsIllegalArgument`() {
        val ex = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            requireInt(emptyMap(), "count")
        }
        val cause = ex.cause
        assert(cause is IllegalArgumentException)
        assert(cause!!.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `requireInt_numberValue_returnsValue`() {
        val result = requireInt(mapOf("count" to 42), "count")
        assertEquals(42, result)
    }

    @Test
    fun `requireInt_numericString_returnsParsed`() {
        val result = requireInt(mapOf("count" to "99"), "count")
        assertEquals(99, result)
    }

    /**
     * P1-2 核心修复点：非数字字符串抛带 key+value 信息的 IllegalArgumentException
     * 反射调用时，InvocationTargetException 会包装底层异常，需解包
     */
    @Test
    fun `requireInt_nonNumericString_throwsWithKeyAndValue`() {
        val ex = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            requireInt(mapOf("count" to "not_a_number"), "count")
        }
        val cause = ex.cause
        assert(cause is IllegalArgumentException)
        assert(cause!!.message!!.contains("count"))
        assert(cause.message!!.contains("not_a_number"))
    }

    // ===== requireLong 测试 =====

    @Test
    fun `requireLong_missingKey_throwsIllegalArgument`() {
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            requireLong(emptyMap(), "delay")
        }
    }

    @Test
    fun `requireLong_numberValue_returnsValue`() {
        val result = requireLong(mapOf("delay" to 999L), "delay")
        assertEquals(999L, result)
    }

    @Test
    fun `requireLong_numericString_returnsParsed`() {
        val result = requireLong(mapOf("delay" to "1234"), "delay")
        assertEquals(1234L, result)
    }

    @Test
    fun `requireLong_nonNumericString_throwsWithKeyAndValue`() {
        val ex = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            requireLong(mapOf("delay" to "NaN"), "delay")
        }
        val cause = ex.cause
        assert(cause is IllegalArgumentException)
        assert(cause!!.message!!.contains("delay"))
    }
}
