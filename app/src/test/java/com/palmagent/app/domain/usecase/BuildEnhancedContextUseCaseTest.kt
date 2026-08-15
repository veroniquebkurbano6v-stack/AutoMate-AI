package com.palmagent.app.domain.usecase

import com.palmagent.app.agent.AgentConfig
import com.palmagent.app.agent.Plan
import com.palmagent.app.agent.PlanStep
import com.palmagent.app.agent.TaskProgressTracker
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.model.TaskProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BuildEnhancedContextUseCase 单元测试
 *
 * v8 修复后验证目标：
 * 1. llmProgress 始终注入（独立于 planContext 是否为空）
 * 2. progressTracker 系统级状态始终注入（独立于 planContext 是否为空）
 * 3. planContext 非空时三者共存（planContext + llmProgress + progressTracker）
 *
 * 修复前 bug：planContext 非空时 llmProgress 和 progressTracker 都被跳过（第 81 行互斥条件）
 */
class BuildEnhancedContextUseCaseTest {

    private fun buildUseCase(round: Int = 2): BuildEnhancedContextUseCase {
        val tracker = TaskProgressTracker()
        // 第 1 轮 buildProgressContext 返回空，需模拟进入第 2 轮才能输出系统级状态
        if (round >= 2) {
            tracker.track(ScreenInfo(currentPackage = "com.tencent.mm", currentActivity = "LauncherUI"), round)
        }
        return BuildEnhancedContextUseCase(progressTracker = tracker)
    }

    private fun buildParams(
        planContext: Plan? = null,
        llmProgress: TaskProgress? = null,
        round: Int = 2
    ): BuildEnhancedContextUseCase.Params {
        return BuildEnhancedContextUseCase.Params(
            deviceCtx = "屏幕尺寸：宽=1080px, 高=2340px",
            screenText = "屏幕文本",
            autoScreenDescription = "VLM 描述",
            stateWarning = "",
            isTreeEmpty = false,
            actionHistory = emptyList(),
            waitConsecutiveCount = 0,
            config = AgentConfig(),
            planContext = planContext,
            llmProgress = llmProgress
        )
    }

    private fun buildTestPlan(goal: String = "打开微信发消息"): Plan = Plan(
        requirement = "用户需要打开微信发消息",
        goal = goal,
        steps = listOf(
            PlanStep(order = 1, goal = "打开微信", successCriteria = "进入微信主页", supervised = false),
            PlanStep(order = 2, goal = "发送消息", successCriteria = "消息发送成功", supervised = false)
        )
    )

    // 场景 1: planContext 非空 + llmProgress 非空 → enhancedContext 同时包含两者
    @Test
    fun `planContext非空且llmProgress非空时 enhancedContext同时包含两者`() = runBlocking {
        val planContext = buildTestPlan()

        val llmProgress = TaskProgress(
            currentStep = "点击发送",
            completedSteps = listOf("打开微信", "搜索联系人"),
            remainingSteps = emptyList(),
            status = "in_progress"
        )

        val useCase = buildUseCase(round = 2)
        val params = buildParams(planContext = planContext, llmProgress = llmProgress, round = 2)

        val enhancedContext = useCase(params)

        assertTrue("enhancedContext 应包含 planContext 步骤", enhancedContext.contains("打开微信"))
        assertTrue("enhancedContext 应包含 llmProgress 的【实时进度】标题", enhancedContext.contains("【实时进度】"))
        assertTrue("enhancedContext 应包含 llmProgress.currentStep", enhancedContext.contains("点击发送"))
        assertTrue("enhancedContext 应包含 llmProgress.completedSteps", enhancedContext.contains("搜索联系人"))
    }

    // 场景 2: planContext 非空 + llmProgress 为空 → enhancedContext 仅含 planContext（无【实时进度】）
    @Test
    fun `planContext非空且llmProgress为空时 enhancedContext仅含planContext`() = runBlocking {
        val planContext = buildTestPlan(goal = "测试任务")

        val useCase = buildUseCase(round = 2)
        val params = buildParams(planContext = planContext, llmProgress = null, round = 2)

        val enhancedContext = useCase(params)

        assertTrue("enhancedContext 应包含 planContext 步骤", enhancedContext.contains("打开微信"))
        assertFalse("enhancedContext 不应包含【实时进度】（llmProgress 为空）", enhancedContext.contains("【实时进度】"))
    }

    // 场景 3: planContext 为空 + llmProgress 非空 → enhancedContext 含 llmProgress
    @Test
    fun `planContext为空且llmProgress非空时 enhancedContext含llmProgress`() = runBlocking {
        val llmProgress = TaskProgress(
            currentStep = "搜索联系人",
            completedSteps = listOf("打开微信"),
            remainingSteps = listOf("发送消息"),
            status = "in_progress"
        )

        val useCase = buildUseCase(round = 2)
        val params = buildParams(planContext = null, llmProgress = llmProgress, round = 2)

        val enhancedContext = useCase(params)

        assertTrue("enhancedContext 应包含 llmProgress 的【实时进度】标题", enhancedContext.contains("【实时进度】"))
        assertTrue("enhancedContext 应包含 currentStep", enhancedContext.contains("搜索联系人"))
        assertTrue("enhancedContext 应包含 completedSteps", enhancedContext.contains("打开微信"))
        assertTrue("enhancedContext 应包含 remainingSteps", enhancedContext.contains("发送消息"))
    }

    // 场景 4: planContext 为空 + llmProgress 为空 + progressTracker 非空 → enhancedContext 含 progressTracker 系统级状态
    @Test
    fun `planContext为空且llmProgress为空时 enhancedContext含progressTracker系统级状态`() = runBlocking {
        val useCase = buildUseCase(round = 2)
        val params = buildParams(planContext = null, llmProgress = null, round = 2)

        val enhancedContext = useCase(params)

        // progressTracker.buildProgressContext() 第 2 轮起返回【进度】3轮 | 📱com.tencent.mm
        assertTrue("enhancedContext 应包含 progressTracker 系统级状态", enhancedContext.contains("【进度】"))
        assertTrue("enhancedContext 应包含当前应用包名", enhancedContext.contains("com.tencent.mm"))
    }

    // 场景 5: planContext 非空 + llmProgress 非空 + progressTracker 非空 → enhancedContext 同时包含三者
    @Test
    fun `planContext和llmProgress和progressTracker都非空时 enhancedContext同时包含三者`() = runBlocking {
        val planContext = buildTestPlan()

        val llmProgress = TaskProgress(
            currentStep = "发送消息",
            completedSteps = listOf("打开微信"),
            remainingSteps = emptyList(),
            status = "in_progress"
        )

        val useCase = buildUseCase(round = 2)
        val params = buildParams(planContext = planContext, llmProgress = llmProgress, round = 2)

        val enhancedContext = useCase(params)

        // v8 修复核心断言：三者共存
        assertTrue("enhancedContext 应包含 planContext 步骤", enhancedContext.contains("打开微信"))
        assertTrue("enhancedContext 应包含 llmProgress", enhancedContext.contains("【实时进度】"))
        assertTrue("enhancedContext 应包含 progressTracker 系统级状态", enhancedContext.contains("【进度】"))

        // v8 修复前 bug 验证：旧代码 planContext 非空时 progressTracker 也会被跳过
        // 修复后应能看到系统级状态（应用包名）
        assertTrue("enhancedContext 应包含当前应用包名（progressTracker 不再被跳过）",
            enhancedContext.contains("com.tencent.mm"))
    }
}
