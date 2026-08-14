package com.palmagent.app.agent

import com.palmagent.app.service.RapidOcrService.OcrTextBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展示 OCR 文本提取格式（按 Y 轴分行 + 每块带 x,y 坐标）
 *
 * 关键代码：[ScreenDescriptor.extractOcrText] + [ScreenDescriptor.formatOcrByRows]
 *
 * 输出格式：
 * ```
 * 【OCR 文本识别】
 * 文本1(x1，y1) 文本2(x2，y1) 文本3(x3，y1)  // 同一 Y 组
 * 文本4(x4，y2) 文本5(x5，y2)                  // 另一 Y 组
 * ```
 *
 * 优化点：
 * 1. Y 轴分组（yThreshold=15）：相邻文本块 y 差 < 15 视为同一行
 * 2. 同一行内按 X 排序：从左到右排列
 * 3. 每块带 (x,y) 坐标：LLM 可直接映射到 tap/locate
 * 4. 中文逗号分隔：与中国大陆坐标习惯一致
 * 5. Y 分组 bug 修复：之前用 currentRow[0].centerY 比较，现改为 sorted[i-1].centerY
 */
class OcrTextFormatTest {

    private val screenDescriptor = ScreenDescriptor()

    /**
     * 展示实机微信首页 OCR 输出格式
     */
    @Test
    fun `展示实机微信首页OCR输出格式`() {
        val blocks = listOf(
            // 顶部状态栏
            OcrTextBlock.fromBoundingBox("中国移动", 60, 50, 200, 90),
            OcrTextBlock.fromBoundingBox("4G", 950, 50, 1000, 90),
            OcrTextBlock.fromBoundingBox("100%", 1020, 50, 1080, 90),

            // 顶部标题栏
            OcrTextBlock.fromBoundingBox("微信", 510, 200, 600, 270),
            OcrTextBlock.fromBoundingBox("🔍", 950, 210, 990, 260),
            OcrTextBlock.fromBoundingBox("+", 1020, 210, 1060, 260),

            // 搜索框
            OcrTextBlock.fromBoundingBox("搜索", 100, 320, 980, 380),

            // 消息列表（每条消息含3个块：标题/内容/时间，y 差 < 15 视为同一行）
            OcrTextBlock.fromBoundingBox("微信团队", 130, 460, 280, 510),
            OcrTextBlock.fromBoundingBox("微信支付有红包待领", 130, 510, 600, 560),
            OcrTextBlock.fromBoundingBox("15:32", 980, 480, 1060, 520),

            OcrTextBlock.fromBoundingBox("文件传输助手", 130, 620, 350, 670),
            OcrTextBlock.fromBoundingBox("[文件]", 130, 670, 250, 720),
            OcrTextBlock.fromBoundingBox("14:28", 980, 640, 1060, 680),

            OcrTextBlock.fromBoundingBox("狗吠麟", 130, 940, 280, 990),
            OcrTextBlock.fromBoundingBox("在吗？", 130, 990, 400, 1040),
            OcrTextBlock.fromBoundingBox("12:05", 980, 960, 1060, 1000),

            // 底部 Tab
            OcrTextBlock.fromBoundingBox("微信", 100, 2200, 200, 2260),
            OcrTextBlock.fromBoundingBox("通讯录", 320, 2200, 460, 2260),
            OcrTextBlock.fromBoundingBox("发现", 580, 2200, 700, 2260),
            OcrTextBlock.fromBoundingBox("我", 840, 2200, 960, 2260)
        )

        val rows = screenDescriptor.formatOcrByRows(blocks)
        val output = buildString {
            appendLine("【OCR 文本识别】")
            rows.forEach { appendLine(it) }
        }

        println("=" .repeat(80))
        println("【实机微信首页 OCR 文本输出】（v3 优化：文本(x，y) 格式）")
        println("=" .repeat(80))
        println(output)
        println("=" .repeat(80))
        println("总字符数: ${output.length} (含标题行)")
        println("总行数: ${rows.size}")
        println("总文本块数: ${blocks.size}")
        println("=" .repeat(80))
    }

    /**
     * 展示过滤前 vs 过滤后（v3.1 仅保留含中文汉字）
     */
    @Test
    fun `OCR过滤前后效果对比`() {
        val allBlocks = listOf(
            OcrTextBlock.fromBoundingBox("中国移动", 60, 50, 200, 90),
            OcrTextBlock.fromBoundingBox("4G", 950, 50, 1000, 90),
            OcrTextBlock.fromBoundingBox("100%", 1020, 50, 1080, 90),
            OcrTextBlock.fromBoundingBox("微信", 510, 200, 600, 270),
            OcrTextBlock.fromBoundingBox("🔍", 950, 210, 990, 260),
            OcrTextBlock.fromBoundingBox("+", 1020, 210, 1060, 260),
            OcrTextBlock.fromBoundingBox("搜索", 100, 320, 980, 380),
            OcrTextBlock.fromBoundingBox("微信团队", 130, 460, 280, 510),
            OcrTextBlock.fromBoundingBox("微信支付有红包待领", 130, 510, 600, 560),
            OcrTextBlock.fromBoundingBox("15:32", 980, 480, 1060, 520),
            OcrTextBlock.fromBoundingBox("文件传输助手", 130, 620, 350, 670),
            OcrTextBlock.fromBoundingBox("[文件]", 130, 670, 250, 720),
            OcrTextBlock.fromBoundingBox("14:28", 980, 640, 1060, 680),
            OcrTextBlock.fromBoundingBox("狗吠麟", 130, 940, 280, 990),
            OcrTextBlock.fromBoundingBox("在吗？", 130, 990, 400, 1040),
            OcrTextBlock.fromBoundingBox("12:05", 980, 960, 1060, 1000),
            OcrTextBlock.fromBoundingBox("微信", 100, 2200, 200, 2260),
            OcrTextBlock.fromBoundingBox("通讯录", 320, 2200, 460, 2260),
            OcrTextBlock.fromBoundingBox("发现", 580, 2200, 700, 2260),
            OcrTextBlock.fromBoundingBox("我", 840, 2200, 960, 2260)
        )

        // 过滤前：所有块
        val beforeRows = screenDescriptor.formatOcrByRows(allBlocks)
        val beforeOutput = buildString {
            appendLine("【OCR 文本识别】")
            beforeRows.forEach { appendLine(it) }
        }

        // 过滤后：仅含中文汉字
        val filteredBlocks = allBlocks.filter { block ->
            com.palmagent.app.service.RapidOcrService.shouldFilterOcrText(block.text).not()
        }
        val afterRows = screenDescriptor.formatOcrByRows(filteredBlocks)
        val afterOutput = buildString {
            appendLine("【OCR 文本识别】")
            afterRows.forEach { appendLine(it) }
        }

        println("=" .repeat(80))
        println("【OCR 过滤前后对比】（v3.1：仅保留含中文汉字的文本）")
        println("=" .repeat(80))
        println("▼ 过滤前 (${allBlocks.size} 块, ${beforeRows.size} 行, ${beforeOutput.length} 字符):")
        println(beforeOutput)
        println()
        println("▼ 过滤后 (${filteredBlocks.size} 块, ${afterRows.size} 行, ${afterOutput.length} 字符):")
        println(afterOutput)
        println("-".repeat(80))
        println(String.format("过滤统计: 保留 %d/%d 块 (%.0f%%)，节省 %d 字符 (-%.1f%%)",
            filteredBlocks.size, allBlocks.size,
            filteredBlocks.size * 100.0 / allBlocks.size,
            beforeOutput.length - afterOutput.length,
            (beforeOutput.length - afterOutput.length) * 100.0 / beforeOutput.length))
        println("-".repeat(80))
        println("被过滤的内容:")
        allBlocks.filter { com.palmagent.app.service.RapidOcrService.shouldFilterOcrText(it.text) }
            .forEach { println("  - \"${it.text}\"") }
        println("=" .repeat(80))
    }

    /**
     * 优化前 → v2 → v3 三版格式对比
     */
    @Test
    fun `OCR输出格式三版对比`() {
        val blocks = listOf(
            OcrTextBlock.fromBoundingBox("微信团队", 130, 460, 280, 510),
            OcrTextBlock.fromBoundingBox("微信支付有红包待领", 130, 510, 600, 560),
            OcrTextBlock.fromBoundingBox("15:32", 980, 480, 1060, 520)
        )

        val v3Rows = screenDescriptor.formatOcrByRows(blocks)
        val v3Output = buildString {
            appendLine("【OCR 文本识别】")
            v3Rows.forEach { appendLine(it) }
        }

        // 优化前：单行拼接，无坐标
        val v1Output = "微信团队 微信支付有红包待领 15:32"

        // v2：Y= 开头 + (x=) 坐标
        val v2Output = """
            【OCR 文本识别】
            Y=485  微信团队(x=205)
            Y=500  15:32(x=1020)
            Y=535  微信支付有红包待领(x=365)
        """.trimIndent()

        println("=" .repeat(80))
        println("【OCR 格式 v1 → v2 → v3 三版对比】")
        println("=" .repeat(80))
        println("▼ v1（无格式，单行拼接）:")
        println(v1Output)
        println("\n▼ v2（Y= 开头 + 英文 (x=) 坐标）:")
        println(v2Output)
        println("\n▼ v3（文本(x，y) 格式，每块自带坐标）:")
        println(v3Output)
        println("=" .repeat(80))
        println("v3 优势:")
        println("  1. 文本与坐标一一对应，LLM 可直接提取")
        println("  2. 无需解析 Y= 前缀，更符合人类阅读习惯")
        println("  3. 中文逗号分隔，贴合中国用户习惯")
        println("  4. 同一 Y 组合并一行，节省 tokens")
        println("=" .repeat(80))
    }

    /**
     * Y 分组 bug 修复前后对比
     */
    @Test
    fun `Y分组bug修复前后对比`() {
        // 场景：第一行 Y=70，第二行 Y=85（差 15），第三行 Y=200
        val blocks = listOf(
            OcrTextBlock.fromBoundingBox("A", 100, 70, 200, 100),
            OcrTextBlock.fromBoundingBox("B", 300, 75, 400, 105),  // 差 5
            OcrTextBlock.fromBoundingBox("C", 500, 85, 600, 115),  // 差 10（与B）
            OcrTextBlock.fromBoundingBox("D", 100, 200, 200, 230)  // 差 115
        )

        val rows = screenDescriptor.formatOcrByRows(blocks)
        val output = buildString {
            appendLine("【OCR 文本识别】")
            rows.forEach { appendLine(it) }
        }

        println("=" .repeat(80))
        println("【Y 分组 bug 修复演示】")
        println("=" .repeat(80))
        println("输入 4 个块：A(y=70), B(y=75), C(y=85), D(y=200)")
        println("yThreshold=15，期望：A/B/C 同组（差值均<15），D 单独一组")
        println("-".repeat(80))
        println("修复后输出:")
        println(output)
        println("-".repeat(80))
        println("旧 bug: 用 currentRow[0].centerY (A.y=70) 比较")
        println("  - B: |75-70|=5 <15 → 同组 ✓")
        println("  - C: |85-70|=15 NOT <15 → 新分组 ✗（实际应与B同组）")
        println("修复: 用 sorted[i-1].centerY 比较")
        println("  - B: |75-70|=5 <15 → 同组 ✓")
        println("  - C: |85-75|=10 <15 → 同组 ✓")
        println("  - D: |200-85|=115 ≥15 → 新分组 ✓")
        println("=" .repeat(80))
    }

    /**
     * 不同场景字符数对比
     */
    @Test
    fun `不同场景OCR输出字符数对比`() {
        val wechatHome = listOf(
            OcrTextBlock.fromBoundingBox("微信", 510, 200, 600, 270),
            OcrTextBlock.fromBoundingBox("微信团队", 130, 460, 280, 510),
            OcrTextBlock.fromBoundingBox("微信支付有红包待领", 130, 510, 600, 560),
            OcrTextBlock.fromBoundingBox("15:32", 980, 480, 1060, 520),
            OcrTextBlock.fromBoundingBox("文件传输助手", 130, 620, 350, 670),
            OcrTextBlock.fromBoundingBox("14:28", 980, 640, 1060, 680),
            OcrTextBlock.fromBoundingBox("狗吠麟", 130, 940, 280, 990),
            OcrTextBlock.fromBoundingBox("在吗？", 130, 990, 400, 1040)
        )

        val simpleDialog = listOf(
            OcrTextBlock.fromBoundingBox("确认删除？", 200, 800, 880, 880),
            OcrTextBlock.fromBoundingBox("此操作不可恢复", 200, 900, 880, 960),
            OcrTextBlock.fromBoundingBox("取消", 400, 1000, 520, 1080),
            OcrTextBlock.fromBoundingBox("确定", 600, 1000, 720, 1080)
        )

        val searchResult = mutableListOf<OcrTextBlock>().apply {
            for (i in 1..10) {
                add(OcrTextBlock.fromBoundingBox("联系人$i", 130, 300 + i * 100, 280, 340 + i * 100))
                add(OcrTextBlock.fromBoundingBox("消息内容$i", 130, 340 + i * 100, 600, 380 + i * 100))
                add(OcrTextBlock.fromBoundingBox("${10 - i}:00", 980, 320 + i * 100, 1060, 360 + i * 100))
            }
        }

        println("=" .repeat(80))
        println("【不同场景 OCR 字符数对比】（v3 格式）")
        println("=" .repeat(80))
        val scenarios = listOf(
            Triple("微信首页", wechatHome, "复杂消息列表"),
            Triple("简单弹窗", simpleDialog, "短文本+按钮"),
            Triple("搜索结果页(10条)", searchResult, "密集文本")
        )

        for ((name, blocks, desc) in scenarios) {
            val rows = screenDescriptor.formatOcrByRows(blocks)
            val output = buildString {
                appendLine("【OCR 文本识别】")
                rows.forEach { appendLine(it) }
            }
            println(String.format("%-20s 文本块=%d 行数=%d 字符数=%d (%s)",
                name, blocks.size, rows.size, output.length, desc))
        }
        println("=" .repeat(80))
        println("说明：屏幕分辨率 1080x2340，yThreshold=15 决定是否同行")
        println("ContextManager.assemble 按 30% 预算裁剪（避免单组件过大）")
        println("=" .repeat(80))
    }

    /**
     * 端到端测试：模拟 RapidOcrService.recognize 完整流程（缩放逆变换 + 过滤 + Y 分组 + 输出）
     * 验证：过滤被正确应用到最终输出
     */
    @Test
    fun `端到端模拟OCR主流程 验证过滤应用`() {
        // 模拟 RapidOCR 引擎原始输出（含噪声）
        val rawBlocks = listOf(
            OcrTextBlock.fromBoundingBox("中国移动", 60, 50, 200, 90, confidence = 0.95f),
            OcrTextBlock.fromBoundingBox("4G", 950, 50, 1000, 90, confidence = 0.92f),
            OcrTextBlock.fromBoundingBox("100%", 1020, 50, 1080, 90, confidence = 0.88f),
            OcrTextBlock.fromBoundingBox("微信", 510, 200, 600, 270, confidence = 0.97f),
            OcrTextBlock.fromBoundingBox("🔍", 950, 210, 990, 260, confidence = 0.75f),
            OcrTextBlock.fromBoundingBox("+", 1020, 210, 1060, 260, confidence = 0.70f),
            OcrTextBlock.fromBoundingBox("搜索", 100, 320, 980, 380, confidence = 0.93f),
            OcrTextBlock.fromBoundingBox("微信团队", 130, 460, 280, 510, confidence = 0.96f),
            OcrTextBlock.fromBoundingBox("微信支付有红包待领", 130, 510, 600, 560, confidence = 0.94f),
            OcrTextBlock.fromBoundingBox("15:32", 980, 480, 1060, 520, confidence = 0.90f),
            OcrTextBlock.fromBoundingBox("文件传输助手", 130, 620, 350, 670, confidence = 0.95f),
            OcrTextBlock.fromBoundingBox("[文件]", 130, 670, 250, 720, confidence = 0.85f),
            OcrTextBlock.fromBoundingBox("14:28", 980, 640, 1060, 680, confidence = 0.89f),
            OcrTextBlock.fromBoundingBox("狗吠麟", 130, 940, 280, 990, confidence = 0.97f),
            OcrTextBlock.fromBoundingBox("在吗？", 130, 990, 400, 1040, confidence = 0.92f),
            OcrTextBlock.fromBoundingBox("12:05", 980, 960, 1060, 1000, confidence = 0.88f),
            OcrTextBlock.fromBoundingBox("微信", 100, 2200, 200, 2260, confidence = 0.96f),
            OcrTextBlock.fromBoundingBox("通讯录", 320, 2200, 460, 2260, confidence = 0.95f),
            OcrTextBlock.fromBoundingBox("发现", 580, 2200, 700, 2260, confidence = 0.94f),
            OcrTextBlock.fromBoundingBox("我", 840, 2200, 960, 2260, confidence = 0.93f),
            // 模拟低置信度块
            OcrTextBlock.fromBoundingBox("a", 50, 30, 80, 70, confidence = 0.45f),  // 低置信度
            // 模拟纯符号
            OcrTextBlock.fromBoundingBox("…", 200, 100, 240, 140, confidence = 0.80f)
        )

        // 模拟 RapidOcrService.recognize 的过滤管线
        val filteredBlocks = rawBlocks
            .filter { it.confidence >= 0.5f }
            .filter { !com.palmagent.app.service.RapidOcrService.shouldFilterOcrText(it.text) }

        val rows = screenDescriptor.formatOcrByRows(filteredBlocks)
        val finalOutput = buildString {
            appendLine("【OCR 文本识别】")
            rows.forEach { appendLine(it) }
        }

        println("=" .repeat(80))
        println("【端到端模拟 RapidOcrService.recognize 主流程】")
        println("=" .repeat(80))
        println("原始块数: ${rawBlocks.size}（含低置信度 + 无意义文本）")
        println("过滤后块数: ${filteredBlocks.size}（confidence>=0.5 且含中文汉字）")
        println("输出行数: ${rows.size}")
        println("最终字符数: ${finalOutput.length}")
        println("-".repeat(80))
        println("最终输出:")
        println(finalOutput)
        println("-".repeat(80))
        println("被过滤的块（含原因）:")
        rawBlocks.forEach { block ->
            val reasons = mutableListOf<String>()
            if (block.confidence < 0.5f) reasons.add("置信度低(${block.confidence})")
            if (com.palmagent.app.service.RapidOcrService.shouldFilterOcrText(block.text)) {
                reasons.add("无中文")
            }
            if (reasons.isNotEmpty()) {
                println("  - \"${block.text}\" (${reasons.joinToString(", ")})")
            }
        }
        println("=" .repeat(80))

        // 断言：低置信度被过滤
        assertTrue("低置信度 a 应被过滤",
            filteredBlocks.none { it.text == "a" })

        // 断言：无中文的块被过滤
        listOf("4G", "100%", "🔍", "+", "15:32", "14:28", "12:05", "…").forEach {
            assertTrue("应被过滤: $it", filteredBlocks.none { block -> block.text == it })
        }

        // 断言：含中文的块被保留
        listOf("中国移动", "微信", "搜索", "微信团队", "狗吠麟").forEach {
            assertTrue("应被保留: $it", filteredBlocks.any { block -> block.text == it })
        }

        // 断言：输出格式正确
        assertTrue("输出应包含【OCR 文本识别】", finalOutput.contains("【OCR 文本识别】"))
        assertTrue("输出应含坐标格式", Regex("""[\u4e00-\u9fff]+\(\d+，\d+\)""").containsMatchIn(finalOutput))
        assertFalse("输出不应含 4G", finalOutput.contains("4G"))
        assertFalse("输出不应含 15:32", finalOutput.contains("15:32"))
    }
}
