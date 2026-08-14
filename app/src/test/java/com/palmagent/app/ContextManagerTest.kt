package com.palmagent.app

import com.palmagent.app.agent.ContextManager
import com.palmagent.app.agent.DefaultAgentService
import org.junit.Assert.*
import org.junit.Test

class ContextManagerTest {

    @Test
    fun estimateTokens_emptyString_returnsZero() {
        assertEquals(0, ContextManager.estimateTokens(""))
    }

    @Test
    fun estimateTokens_englishText() {
        val tokens = ContextManager.estimateTokens("Hello World")
        assertTrue("英文Token估算应大于2", tokens >= 3)
        assertTrue("英文Token估算应合理", tokens <= 10)
    }

    @Test
    fun estimateTokens_chineseText() {
        val tokens = ContextManager.estimateTokens("你好世界")
        assertTrue("中文每个字约1.6 tokens", tokens >= 6)
        assertTrue("中文Token估算应合理", tokens <= 12)
    }

    @Test
    fun estimateTokens_mixedText() {
        val tokens = ContextManager.estimateTokens("你好 Hello 123")
        assertTrue("混合文本Token应>0", tokens > 0)
    }

    @Test
    fun estimateTokens_longText() {
        val longText = "测试".repeat(100)
        val tokens = ContextManager.estimateTokens(longText)
        assertTrue("长文本Token估算", tokens > 100)
    }

    @Test
    fun estimateTokens_punctuationAndSymbols() {
        val text = "!!!===///"
        val tokens = ContextManager.estimateTokens(text)
        assertTrue("符号Token估算应>0", tokens > 0)
    }

    @Test
    fun estimateTokens_safetyMarginIncluded() {
        val baseTokens = "Hello".length * 0.25
        val estimated = ContextManager.estimateTokens("Hello")
        assertTrue("应包含1.2倍安全边际: estimated=$estimated, base=$baseTokens",
            estimated >= (baseTokens * 1.15).toInt())
    }

    @Test
    fun estimateTokensSafe_largeText_notOverflow() {
        val hugeText = "a".repeat(10000)
        val result = ContextManager.estimateTokensSafe(hugeText)
        assertTrue("应不溢出: $result", result < Int.MAX_VALUE / 2)
    }

    @Test
    fun reset_clearsState() {
        ContextManager.reset()
    }

    @Test
    fun assembledContext_dataClass() {
        val ctx = ContextManager.AssembledContext(
            text = "test context",
            estimatedTokens = 100
        )
        assertEquals("test context", ctx.text)
        assertEquals(100, ctx.estimatedTokens)
    }

    @Test
    fun estimateTokens_typicalScreenContext() {
        val typical = buildString {
            appendLine("【当前屏幕】")
            appendLine("包名: com.android.settings")
            appendLine("Activity: com.android.settings.Settings")
            appendLine("UI元素(30):")
            repeat(30) {
                appendLine("  [TEXT] \"设置项${it}\" (100,${it * 50}) 可点击")
            }
        }
        val tokens = ContextManager.estimateTokens(typical)
        assertTrue("典型屏幕上下文应在200-1000 tokens: actual=$tokens", tokens in 200..1000)
    }

    @Test
    fun estimateTokens_chinesePoem() {
        val poem = "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。"
        val tokens = ContextManager.estimateTokens(poem)
        assertTrue("古诗Token估算应>15: actual=$tokens", tokens >= 15)
    }

    @Test
    fun estimateTokens_specialCharacters() {
        val special = "用户名: user@example.com, 密码: ****, URL: https://test.com/api?key=val&type=1"
        val tokens = ContextManager.estimateTokens(special)
        assertTrue("特殊字符Token估算应>0", tokens > 0)
    }

    @Test
    fun estimateTokens_emoji() {
        val emojiText = "完成 ✅ 失败 ❌  进度 🔄"
        val tokens = ContextManager.estimateTokens(emojiText)
        assertTrue("Emoji Token估算应>0", tokens > 0)
    }
}