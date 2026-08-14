package com.palmagent.app.service

import com.palmagent.app.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ActionParser search_icon 字段解析单元测试
 *
 * 覆盖 P3-11 修复：
 * - search_icon?.toBoolean() → search_icon?.let { parseBooleanLoose(it) }
 * - toBoolean() 仅识别 "true"（大小写敏感），parseBooleanLoose 识别 "true"/"1"/"yes"（大小写不敏感）
 *
 * 测试策略：通过 parseActionFromResponse 传入含不同 search_icon 值的 JSON 间接验证 parseBooleanLoose。
 * 注意：避开 ASK_USER 分支（type 非 ASK_USER），否则会触发 KVUtils/Android 依赖。
 */
class ActionParserSearchIconTest {

    private fun buildResponse(searchIcon: String?): String {
        val searchIconField = searchIcon?.let { "\"search_icon\":\"$it\"" } ?: ""
        val fields = listOf(
            "\"type\":\"CLICK\"",
            "\"target\":\"搜索按钮\"",
            searchIconField
        ).filter { it.isNotEmpty() }
        return "{${fields.joinToString(",")}}"
    }

    private fun parseSearchIcon(searchIcon: String?): Boolean? {
        val response = buildResponse(searchIcon)
        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)
        return action.searchIcon
    }

    // ===== P3-11 核心修复点：parseBooleanLoose 识别 "true"/"1"/"yes" =====

    @Test
    fun `searchIcon_true_returnsTrue`() {
        assertEquals(true, parseSearchIcon("true"))
    }

    /**
     * toBoolean() 也能识别 "True"（大小写不敏感），但这里验证 parseBooleanLoose 的 ignoreCase
     */
    @Test
    fun `searchIcon_True_caseInsensitive_returnsTrue`() {
        assertEquals(true, parseSearchIcon("True"))
    }

    @Test
    fun `searchIcon_TRUE_uppercase_returnsTrue`() {
        assertEquals(true, parseSearchIcon("TRUE"))
    }

    /**
     * P3-11 新增：toBoolean() 不识别 "1"，parseBooleanLoose 识别
     */
    @Test
    fun `searchIcon_one_returnsTrue`() {
        assertEquals(true, parseSearchIcon("1"))
    }

    /**
     * P3-11 新增：toBoolean() 不识别 "yes"，parseBooleanLoose 识别
     */
    @Test
    fun `searchIcon_yes_returnsTrue`() {
        assertEquals(true, parseSearchIcon("yes"))
    }

    @Test
    fun `searchIcon_Yes_caseInsensitive_returnsTrue`() {
        assertEquals(true, parseSearchIcon("Yes"))
    }

    @Test
    fun `searchIcon_YES_uppercase_returnsTrue`() {
        assertEquals(true, parseSearchIcon("YES"))
    }

    // ===== false / 其他值 =====

    @Test
    fun `searchIcon_false_returnsFalse`() {
        assertEquals(false, parseSearchIcon("false"))
    }

    @Test
    fun `searchIcon_zero_returnsFalse`() {
        assertEquals(false, parseSearchIcon("0"))
    }

    @Test
    fun `searchIcon_no_returnsFalse`() {
        assertEquals(false, parseSearchIcon("no"))
    }

    @Test
    fun `searchIcon_emptyString_returnsFalse`() {
        // parseBooleanLoose 对空字符串返回 false
        assertEquals(false, parseSearchIcon(""))
    }

    @Test
    fun `searchIcon_randomString_returnsFalse`() {
        assertEquals(false, parseSearchIcon("maybe"))
    }

    // ===== 字段缺失 =====

    /**
     * search_icon 字段缺失时，searchIcon 应为 null（不调用 parseBooleanLoose）
     */
    @Test
    fun `searchIcon_missingField_returnsNull`() {
        assertNull(parseSearchIcon(null))
    }

    // ===== 完整解析验证 =====

    /**
     * 验证 parseActionFromResponse 同时正确解析其他字段和 search_icon
     */
    @Test
    fun `parseActionWithSearchIcon_otherFieldsAlsoCorrect`() {
        val response = """
            {
                "type": "CLICK",
                "target": "搜索按钮",
                "search_icon": "true",
                "confidence": 0.9
            }
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ActionType.CLICK, action.type)
        assertEquals("搜索按钮", action.targetId)
        assertEquals(true, action.searchIcon)
        assertEquals(0.9f, action.confidence, 0.001f)
    }

    /**
     * 验证 type 非 ASK_USER 时不触发 KVUtils 依赖
     */
    @Test
    fun `parseActionWithClickType_doesNotTriggerKVUtils`() {
        val response = """{"type":"CLICK","search_icon":"1"}"""
        // 如果触发 KVUtils，会抛 RuntimeException("not mocked")
        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)
        assertEquals(ActionType.CLICK, action.type)
        assertEquals(true, action.searchIcon)
    }
}
