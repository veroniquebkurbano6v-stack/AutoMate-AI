package com.palmagent.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RapidOcrService.shouldFilterOcrText 单元测试
 *
 * v3.1 规则：仅保留含中文汉字的文本，过滤掉：
 * - 纯英文（"OK" "Cancel" "Hello"）
 * - 纯数字（"123" "100"）
 * - 纯符号（"+" "🔍" "..."）
 * - 时间戳（"15:32" "14:28"）
 * - 数字+符号（"100%" "+1"）
 *
 * 保留：
 * - 任意含至少一个 CJK 汉字的文本（"中国移动" "微信" "OK微信" "[文件]"）
 */
class RapidOcrServiceFilterTest {

    @Test
    fun `纯英文单字被过滤`() {
        listOf("a", "A", "Z", "k").forEach {
            assertTrue("应过滤纯英文单字: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `纯英文单词被过滤`() {
        listOf("OK", "Cancel", "Hello", "Search", "Settings").forEach {
            assertTrue("应过滤纯英文单词: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `纯数字被过滤`() {
        listOf("0", "123", "100", "9999").forEach {
            assertTrue("应过滤纯数字: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `数字加符号被过滤`() {
        listOf("100%", "+1", "1.5x", "12:34", "2024-01-01").forEach {
            assertTrue("应过滤数字+符号: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `时间戳被过滤`() {
        listOf("15:32", "14:28", "12:05", "23:59", "00:00").forEach {
            assertTrue("应过滤时间戳: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `纯符号被过滤`() {
        listOf("+", "-", "×", "…", "←", "→", "...", "🔍", "🔔", "❤️", ">>", "<<").forEach {
            assertTrue("应过滤纯符号/emoji: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `空字符串被过滤`() {
        assertTrue(RapidOcrService.shouldFilterOcrText(""))
        assertTrue(RapidOcrService.shouldFilterOcrText("   "))
        assertTrue(RapidOcrService.shouldFilterOcrText("\n\t"))
    }

    @Test
    fun `含中文的文本被保留`() {
        listOf(
            "中",
            "中国",
            "中国移动",
            "微信",
            "微信团队",
            "微信支付有红包待领",
            "文件传输助手",
            "狗吠麟",
            "在吗？",
            "通讯录",
            "我"
        ).forEach {
            assertFalse("应保留含中文: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `混合中英文被保留`() {
        listOf(
            "微信OK",
            "OK微信",
            "BLG战胜AL",
            "微信5.0",
            "红米k100",
            "[文件]",
            "【微信】",
            "「微信」"
        ).forEach {
            assertFalse("应保留含中文的混合文本: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `包含中文标点被保留`() {
        listOf("你好！", "微信？", "微信。", "微信，测试", "微信【】").forEach {
            assertFalse("应保留含中文标点: $it", RapidOcrService.shouldFilterOcrText(it))
        }
    }

    @Test
    fun `首尾空白不影响过滤判断`() {
        assertFalse("首尾空白应保留", RapidOcrService.shouldFilterOcrText("  微信  "))
        assertTrue("首尾空白但内容为纯英文应过滤", RapidOcrService.shouldFilterOcrText("  OK  "))
    }

    @Test
    fun `Unicode汉字范围识别准确`() {
        // 边界字符测试（filter 范围：CJK Unified Ideographs U+4E00-U+9FFF）
        assertFalse("U+4E00 一 应保留（基本汉字起始）", RapidOcrService.shouldFilterOcrText("一"))
        assertFalse("U+9FFF 鿿 应保留（基本汉字结束）", RapidOcrService.shouldFilterOcrText("鿿"))
        // CJK Extension A (U+3400-U+4DBF)：不在 filter 范围，会被过滤（已知限制）
        assertTrue("U+3400 㐀 应过滤（CJK Extension A 不在 filter 范围）",
            RapidOcrService.shouldFilterOcrText("㐀"))
        // 日文假名 U+3042 あ：不在 filter 范围，应过滤
        assertTrue("U+3042 あ 应过滤（非 CJK 汉字）",
            RapidOcrService.shouldFilterOcrText("あ"))
        // 韩文 U+AC00 가：不在 filter 范围，应过滤
        assertTrue("U+AC00 가 应过滤（非 CJK 汉字）",
            RapidOcrService.shouldFilterOcrText("가"))
    }
}
