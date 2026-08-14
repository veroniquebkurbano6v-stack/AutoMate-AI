package com.palmagent.app.agent

import com.palmagent.app.domain.usecase.BuildEnhancedContextUseCase
import com.palmagent.app.model.ActionRecord
import com.palmagent.app.model.TaskProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展示执行模型完整上下文的构成（除 planContext 之外的所有部分）
 *
 * v9 结构化 Plan 改造后：planContext 由决策模型输出结构化 Plan 对象，
 * 经 PlanFormatter.format() 转为执行模型可读文本注入【决策模型任务计划】区域。
 * 上下文其余部分（设备信息、屏幕描述、OCR文本、动作历史、策略提示）由代码组装。
 *
 * 上下文各组件（按 BuildEnhancedContextUseCase 组装顺序，从前到后）：
 * 1. planContext（决策模型输出结构化 Plan，经 PlanFormatter 格式化）
 * 2. autoScreenDescription（VLM 屏幕描述）
 * 3. deviceCtx（设备信息）
 * 4. screenOcrText（OCR 提取的屏幕文本）
 * 5. 最近操作回顾（ContextManager.assemble 组装）
 * 6. 信息获取策略（isTreeEmpty=true 时追加）
 * 7. WAIT 连续警告（waitConsecutiveCount>=5 时追加）
 * 8. stateWarning（HOME 键无效等系统状态警告）
 * 9. llmProgress（v8：始终注入，独立于 planContext）
 * 10. progressTracker 系统级状态（v8：始终注入，独立于 planContext）
 */
class ExecutionModelContextCompositionTest {

    @Test
    fun `展示执行模型完整上下文构成 各组件字符数与占比`() = runBlocking {
        // v9: planContext 改为结构化 Plan 对象，不再读取旧文本文件
        val planFixture = Plan(
            requirement = "用户需要打开微信给联系人狗吠麟发送消息",
            goal = "在微信中打开与狗吠麟的聊天并发送消息",
            steps = listOf(
                PlanStep(order = 1, goal = "打开微信", successCriteria = "进入微信主页，底部有四个Tab", supervised = false),
                PlanStep(order = 2, goal = "搜索联系人", successCriteria = "搜索结果显示狗吠麟的聊天项", supervised = false),
                PlanStep(order = 3, goal = "点击聊天项", successCriteria = "进入与狗吠麟的聊天界面", supervised = false),
                PlanStep(order = 4, goal = "发送消息", successCriteria = "消息发送成功，输入框清空", supervised = false)
            )
        )
        val planFixtureText = PlanFormatter.format(planFixture)

        // 模拟实机场景：微信首页、无障碍树为空、第3轮
        val deviceCtx = "屏幕尺寸：宽=1080px, 高=2340px"
        val screenOcrText = """
            [搜索框] 微信 通讯录
            微信 (123) 消息
            订阅号消息
            微信团队
            文件传输助手
            订阅号
            公众号
            狗吠麟 [进入聊天]
            [底部Tab] 微信 通讯录 发现 我
        """.trimIndent()
        val autoScreenDescription = """
            【屏幕视觉描述】
            上方：微信顶部"微信"标题 + 搜索图标 + +号
            中部：消息列表，包含订阅号、文件传输助手、联系人"狗吠麟"等
            下方：底部Tab栏4个图标（微信/通讯录/发现/我）
        """.trimIndent()
        val stateWarning = ""

        // 最近2步操作历史
        val actionHistory = listOf(
            ActionRecord(
                round = 1,
                actionType = "OPEN_APP",
                params = emptyMap(),
                description = "打开微信",
                screenPackage = "com.tencent.mm",
                success = true,
                resultSummary = "应用已打开",
                screenChange = null,
                executionTimeMs = 0
            ),
            ActionRecord(
                round = 2,
                actionType = "AUTO_INPUT",
                params = emptyMap(),
                description = "搜索联系人",
                screenPackage = "com.tencent.mm",
                success = false,
                resultSummary = "未找到匹配项",
                screenChange = null,
                executionTimeMs = 0
            )
        )

        val useCase = BuildEnhancedContextUseCase(
            progressTracker = TaskProgressTracker()
        )

        val params = BuildEnhancedContextUseCase.Params(
            deviceCtx = deviceCtx,
            screenOcrText = screenOcrText,
            autoScreenDescription = autoScreenDescription,
            stateWarning = stateWarning,
            isTreeEmpty = true,
            actionHistory = actionHistory,
            waitConsecutiveCount = 0,
            config = AgentConfig(),
            planContext = planFixture,
            // v8 验证：planContext 非空时 llmProgress 也应注入（修复前会被跳过）
            llmProgress = TaskProgress(
                currentStep = "搜索联系人",
                completedSteps = listOf("打开微信"),
                remainingSteps = listOf("点击聊天项", "发送消息"),
                status = "in_progress"
            )
        )

        val enhancedContext = useCase(params)

        // 各组件字符数
        val planLen = planFixtureText.length
        val autoScreenLen = autoScreenDescription.length
        val deviceLen = deviceCtx.length
        val ocrLen = screenOcrText.length
        val totalLen = enhancedContext.length
        val otherLen = totalLen - planLen - autoScreenLen - deviceLen - ocrLen

        println("=" .repeat(80))
        println("【执行模型完整上下文构成】（v9 结构化 Plan 改造后实机场景：微信发消息第3轮）")
        println("=" .repeat(80))
        println(String.format("%-30s %10s %8s", "组件", "字符数", "占比"))
        println("-".repeat(80))
        println(String.format("%-30s %10d %7.1f%%", "1. planContext (决策模型Plan)", planLen, planLen * 100.0 / totalLen))
        println(String.format("%-30s %10d %7.1f%%", "2. autoScreenDescription (VLM)", autoScreenLen, autoScreenLen * 100.0 / totalLen))
        println(String.format("%-30s %10d %7.1f%%", "3. deviceCtx (设备信息)", deviceLen, deviceLen * 100.0 / totalLen))
        println(String.format("%-30s %10d %7.1f%%", "4. screenOcrText (OCR文本)", ocrLen, ocrLen * 100.0 / totalLen))
        println(String.format("%-30s %10d %7.1f%%", "5. 其他(操作历史/策略/警告/进度)", otherLen, otherLen * 100.0 / totalLen))
        println("-".repeat(80))
        println(String.format("%-30s %10d %7.1f%%", "总 enhancedContext", totalLen, 100.0))
        println("=" .repeat(80))

        // Token 估算
        val totalTokens = ContextManager.estimateTokens(enhancedContext)
        println("Token 估算: $totalTokens (按中英混合规则)")
        println("=" .repeat(80))

        // 断言
        assertNotNull(enhancedContext)
        // v9: planContext 经 PlanFormatter.format 输出包含"需求："和"步骤1："
        assertTrue("enhancedContext 应包含 planContext 的需求标题", enhancedContext.contains("需求："))
        assertTrue("enhancedContext 应包含 planContext 的步骤1", enhancedContext.contains("步骤1："))
        assertTrue("enhancedContext 应包含 planContext 的完成标志", enhancedContext.contains("完成标志："))
        assertTrue("enhancedContext 应包含 VLM 描述", enhancedContext.contains("【屏幕视觉描述】"))
        assertTrue("enhancedContext 应包含设备信息", enhancedContext.contains("屏幕尺寸"))
        assertTrue("enhancedContext 应包含 OCR 文本", enhancedScreenContainsOcr(enhancedContext, "狗吠麟"))
        assertTrue("enhancedContext 应包含操作历史", enhancedContext.contains("打开微信"))
        assertTrue("enhancedContext 应包含信息获取策略(无障碍为空)",
            enhancedContext.contains("无障碍树为空") || enhancedContext.contains("无障碍服务不可用"))
        // v8 核心断言：llmProgress 也应注入（修复前 planContext 非空时被跳过）
        assertTrue("enhancedContext 应包含 llmProgress 的【实时进度】标题（v8 修复后始终注入）",
            enhancedContext.contains("【实时进度】"))
        assertTrue("enhancedContext 应包含 llmProgress.currentStep", enhancedContext.contains("搜索联系人"))
    }

    private fun enhancedScreenContainsOcr(context: String, keyword: String): Boolean {
        return context.contains(keyword)
    }
}
