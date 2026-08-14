package com.palmagent.app.agent

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.AgentApplication
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.floating.FloatingProgressManager
import kotlinx.coroutines.delay
import com.palmagent.app.domain.usecase.ActionTrackingUseCase
import com.palmagent.app.domain.usecase.BuildEnhancedContextUseCase
import com.palmagent.app.domain.usecase.RequestAIDecisionUseCase
import com.palmagent.app.framework.config.AppConfig
import com.palmagent.app.framework.coroutine.AgentCoroutineScope
import com.palmagent.app.framework.event.EventBus
import com.palmagent.app.model.ActionRecord
import com.palmagent.app.model.ActionType
import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.service.AIService
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.GuiOwlActionAdapter
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.service.DecisionDialogService
import com.palmagent.app.service.PromptBuilder
import com.palmagent.app.service.ToolDecisionEngine
import com.palmagent.app.service.WebSearchService
import com.palmagent.app.tool.ToolRegistry
import com.palmagent.app.tool.ToolResult
import com.palmagent.app.utils.KVUtils
import com.palmagent.app.utils.recycleSafely
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class ScratchpadEntry(
    val id: String,
    val content: String,
    val source: String,
    val roundCreated: Int
)

private data class VisionDecisionResult(
    val action: AgentAction,
    val rawContent: String,
    val userPrompt: String
)

/**
 * 默认 Agent 服务实现
 *
 * 重构后职责清晰：
 * - 任务循环控制（轮次、熔断）
 * - 协调 ScreenDescriptor / ActionExecutor / TaskProgressTracker / SmartWaitStrategy
 * - AI 决策请求与重试
 * - 上下文组装
 */
@Singleton
class DefaultAgentService @Inject constructor(
    private val aiService: AIService,
    private val screenDescriptor: ScreenDescriptor,
    private val actionExecutor: ActionExecutor,
    private val smartWait: SmartWaitStrategy,
    private val toolDecisionEngine: ToolDecisionEngine,
    private val appConfig: AppConfig,
    private val eventBus: EventBus,
    private val coroutineScope: AgentCoroutineScope,
    private val requestAIDecisionUseCase: RequestAIDecisionUseCase,
    private val buildEnhancedContextUseCase: BuildEnhancedContextUseCase,
    private val actionTrackingUseCase: ActionTrackingUseCase
) : AgentService {

    private val progressTracker: TaskProgressTracker get() = actionExecutor.progressTracker

    companion object {
        private const val TAG = "DefaultAgentService"

        private const val WAIT_MAX_CONSECUTIVE = 5
        private const val MAX_REPLAN = 2

        /** 同时缓存的最大操作历史轮数（超出后删除最旧记录，控制内存）
         *  v9.2: 5→7，避免压缩前丢失早期历史（详见 P2 Running Summary 压缩可靠性提升方案 修复 2） */
        private const val MAX_ACTION_HISTORY = 7
    }

    private var config: AgentConfig = AgentConfig()
    @Volatile private var isTaskRunning = false
    @Volatile private var isTaskCancelled = false
    override val isRunning: Boolean get() = isTaskRunning

    private val actionHistory = mutableListOf<ActionRecord>()
    private var waitConsecutiveCount = 0
    /** 决策模型生成的结构化任务计划，注入到每轮决策上下文 */
    private var planContext: Plan? = null
    /** LLM 自管理的任务进度（上一轮输出，注入下一轮上下文） */
    private var llmProgress: com.palmagent.app.model.TaskProgress? = null
    /** 重规划次数（限流） */
    private var replanCount = 0
    

    /** Scratchpad 工作记忆：跨轮保留的搜索结果 */
    private val scratchpad = mutableListOf<ScratchpadEntry>()

    /** v3.2: ASK_USER 截图复用——缓存上一轮 capture，ASK_USER 后下一轮跳过截图 */
    @Volatile
    private var cachedCapture: ActionExecutor.CaptureResult? = null
    @Volatile
    private var lastActionWasAskUser = false

    override fun initialize(config: AgentConfig) {
        this.config = config
    }

    override fun updateConfig(config: AgentConfig) {
        this.config = config
    }

    override suspend fun executeTask(userPrompt: String, callback: AgentCallback, plan: Plan?) {
        isTaskRunning = true
        isTaskCancelled = false

        actionTrackingUseCase.reset()
        actionHistory.clear()
        waitConsecutiveCount = 0
        // v9: userPrompt 是 PlanFormatter 格式化后的 plan 文本，直接使用
        // 双注入修复：planContext 仅在 triggerReplan 重规划或显式传入 plan 时承载
        planContext = plan
        llmProgress = null
        replanCount = 0
        
        scratchpad.clear()
        screenDescriptor.reset()
        progressTracker.reset()
        ContextManager.reset()

        GUIAccessibilityService.instance?.markAgentAction()
        AgentLogger.beginTask(
            userPrompt
                .replace("\n", " ")
                .replace("\r", " ")
                .take(40)
                .replace(Regex("[\\\\/:*?\"<>|【】，。、；：！？（）…—]"), "_")
                .trim()
        )
        AgentLogger.log(AgentLogger.LogType.SYSTEM, "信息获取策略", "无障碍 > OCR+GUI-Plus > GUI-Plus Grounding")

        val maxIterations = if (config.maxIterations > 0) config.maxIterations else appConfig.maxIterations
        var round = 0
        var accumTokens = 0

        try {
            val deviceCtx = buildDeviceContext()
            Log.d(TAG, deviceCtx)
            callback.onContent(0, "任务启动: $userPrompt\n$deviceCtx")
            LiveLogBuffer.append("🚀 开始执行任务: ${userPrompt.take(80)}")

            // 决策模型已改造为执行模型按需调用的工具（PLAN_TASK），
            // 执行模型在自认为知识不足时可主动调用，无需在此固定前置规划。
            // 重规划（triggerReplan）仍保留作为 FATAL 错误的兜底机制。
            callback.onContent(0, "开始执行任务...")

            // 注入取消回调到 SmartWaitStrategy，使其能响应取消信号
            smartWait.isCancelled = { isTaskCancelled }
            // 注入取消回调到 ActionExecutor
            actionExecutor.isCancelled = { isTaskCancelled }
            // Scratchpad FORGET 回调
            actionExecutor.onScratchpadForget = { target -> forgetFromScratchpad(target) }

            while (round < maxIterations && !isTaskCancelled) {
                round++
                callback.onLoopStart(round)

                if (isTaskCancelled) {
                    callback.onComplete(round, "任务已被用户取消", accumTokens)
                    return
                }

                if (GUIAccessibilityService.instance?.consumeUserTouch() == true && !isTaskCancelled) {
                    handleUserInterruption()
                }

                if (isTaskCancelled) {
                    callback.onComplete(round, "任务已被用户取消", accumTokens)
                    return
                }

                // 首轮截屏前等待1秒，让输入法界面消失
                if (round == 1) {
                    delay(1000L)
                }

                // v3.2: ASK_USER 截图复用——上一轮是 ASK_USER 时复用缓存截图（屏幕未变化）
                val capture = if (lastActionWasAskUser && cachedCapture != null) {
                    Log.d(TAG, "ASK_USER 后复用上一轮截图，跳过 captureScreen")
                    LiveLogBuffer.append("📸 复用上一轮截图（ASK_USER 追问期间屏幕未变化）")
                    cachedCapture!!
                } else {
                    actionExecutor.captureScreen()
                }
                val screenInfo = capture.screenInfo
                val screenshotBmp = capture.screenshotBmp

                // 当无障碍数据质量过低时，放弃无障碍数据改用 OCR+VLM
                val dataQuality = capture.accessibilityCheck?.dataQuality ?: 0f
                val useAccessibility = capture.accessibilityCheck?.isAvailable == true && dataQuality >= 0.3f
                val effectiveTreeEmpty = !useAccessibility

                if (!useAccessibility && capture.accessibilityCheck?.isAvailable == true) {
                    Log.w(TAG, "无障碍数据质量过低(${(dataQuality * 100).toInt()}%)，放弃无障碍数据改用OCR+VLM")
                }

                if (isTaskCancelled) {
                    callback.onComplete(round, "任务已被用户取消", accumTokens)
                    return
                }

                // ============ VL 模式 vs 文本模式分流 ============
                var finalAction: AgentAction? = null
                // 统一日志变量（两种模式共用）
                var fullPromptForLog = ""
                var enhancedContextForLog = ""
                var screenOcrTextForLog = ""
                var textDecision: ToolDecisionEngine.DecisionResult? = null
                var vlDecisionResult: VisionDecisionResult? = null

                if (KVUtils.isVisionModeEnabled()) {
                    // VL 模式：跳过 OCR/无障碍树/VLM 描述/上下文组装，直接使用视觉模型决策
                    val vlDecision = decideViaVision(userPrompt, screenshotBmp, actionHistory.toList(), round, llmProgress, planContext)
                    vlDecisionResult = vlDecision

                    if (vlDecision == null) {
                        // VL 决策失败 → 记录 WAIT 并重试下一轮，不直接终止
                        Log.w(TAG, "VL决策失败，等待下一轮重试")
                        LiveLogBuffer.append("⚠ VL决策失败，等待下一轮重试")
                        actionHistory.add(ActionRecord(
                            round = round,
                            actionType = "WAIT",
                            params = emptyMap(),
                            description = "VL决策失败，等待重试",
                            screenPackage = screenInfo?.currentPackage,
                            success = true,
                            resultSummary = "VL API 调用失败，等待下一轮自动重试"
                        ))
                        while (actionHistory.size > MAX_ACTION_HISTORY) {
                            actionHistory.removeAt(0)
                        }
                        // 统一日志：VL 决策失败轮次也记录
                        AgentLogger.logRound(
                            round = round,
                            mode = "VL",
                            screenInfo = screenInfo,
                            screenshotJpegBytes = AgentLogger.compressScreenshot(screenshotBmp),
                            modelInput = "VL决策失败，未产生模型输入",
                            modelOutput = "VL API 调用失败",
                            action = AgentAction(
                                type = ActionType.WAIT,
                                description = "VL决策失败，等待重试",
                                confidence = 0.5f
                            ),
                            actionSuccess = false,
                            actionResultSummary = "VL API 调用失败，等待下一轮自动重试",
                            planContext = planContext
                        )
                        screenshotBmp.recycleSafely()
                        lastActionWasAskUser = false
                        cachedCapture = null
                        continue
                    }

                    finalAction = vlDecision.action

                    // 提取 LLM 自管理的任务进度，注入下一轮上下文
                    if (finalAction.progress != null) {
                        llmProgress = finalAction.progress
                    }

                    // VL 模式：WEB_SEARCH 处理（搜索结果写入 Scratchpad，跨轮保留）
                    if (finalAction.type == ActionType.WEB_SEARCH) {
                        val searchQuery = finalAction.text ?: finalAction.description
                        Log.d(TAG, "VL请求联网搜索: $searchQuery")
                        LiveLogBuffer.append("🔍 VL请求联网搜索: ${searchQuery.take(40)}")

                        val searchResult = WebSearchService.search(searchQuery)

                        addToScratchpad(listOf(ScratchpadEntry(
                            id = "sp-${round}-1",
                            content = (searchResult.content ?: "").take(300),
                            source = "web_search: $searchQuery",
                            roundCreated = round
                        )))

                        actionHistory.add(ActionRecord(
                            round = round,
                            actionType = "WEB_SEARCH",
                            params = mapOf("query" to searchQuery),
                            description = "联网搜索: $searchQuery",
                            screenPackage = screenInfo?.currentPackage,
                            success = true,
                            resultSummary = "搜索完成，结果已写入工作记忆（${searchResult.content?.take(50) ?: ""}...）"
                        ))
                        while (actionHistory.size > MAX_ACTION_HISTORY) {
                            actionHistory.removeAt(0)
                        }
                        // 统一日志：WEB_SEARCH 轮次也记录
                        AgentLogger.logRound(
                            round = round,
                            mode = "VL",
                            screenInfo = screenInfo,
                            screenshotJpegBytes = AgentLogger.compressScreenshot(screenshotBmp),
                            modelInput = "=== System Prompt ===\n${PromptBuilder.getSystemPrompt()}\n\n=== User Prompt ===\n${vlDecision.userPrompt}",
                            modelOutput = vlDecision.rawContent,
                            action = finalAction,
                            actionSuccess = true,
                            actionResultSummary = "搜索完成，结果已写入工作记忆",
                            planContext = planContext
                        )
                        screenshotBmp.recycleSafely()
                        lastActionWasAskUser = false
                        cachedCapture = null
                        continue  // 跳过本轮执行，进入下一轮
                    }

                    // VL 模式：UI 反馈（日志统一在 logRound 中保存）
                    callback.onContent(round, "VL决策: ${finalAction.type} - ${finalAction.description}")
                    FloatingProgressManager.updateProgress(round, "${finalAction.type.name} ${finalAction.description.take(40)}")
                } else {
                    // ============ 文本模式：保持现有流程 ============
                    screenOcrTextForLog = if (useAccessibility) {
                        screenDescriptor.extractScreenText(screenInfo)
                    } else {
                        screenDescriptor.extractOcrText(screenshotBmp)
                    }

                    if (isTaskCancelled) {
                        callback.onComplete(round, "任务已被用户取消", accumTokens)
                        return
                    }

                    val autoScreenDescription = screenDescriptor.generateScreenDescription(
                        effectiveTreeEmpty, screenshotBmp, screenInfo, round,
                        visualQuestion = actionHistory.lastOrNull()?.visualQuestion
                    )

                    val stateWarning = buildStateWarningFromTracking()
                    val screenshotFailedSignal = if (screenshotBmp == null) {
                        "⚠️ 截屏失败，OCR/VLM/GUI-Plus 视觉信息不可用。请基于无障碍节点和上下文谨慎决策；若需视觉确认请输出 WAIT 等待下一轮重试"
                    } else null
                    val effectiveStateWarning = listOfNotNull(stateWarning, screenshotFailedSignal)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    progressTracker.track(screenInfo, round)

                    if (isTaskCancelled) {
                        callback.onComplete(round, "任务已被用户取消", accumTokens)
                        return
                    }

                    enhancedContextForLog = buildEnhancedContextUseCase(
                        BuildEnhancedContextUseCase.Params(
                            deviceCtx = deviceCtx,
                            screenOcrText = screenOcrTextForLog,
                            autoScreenDescription = autoScreenDescription,
                            stateWarning = effectiveStateWarning,
                            isTreeEmpty = effectiveTreeEmpty,
                            actionHistory = actionHistory.toList(),
                            waitConsecutiveCount = waitConsecutiveCount,
                            config = config,
                            planContext = planContext,
                            
                            llmProgress = llmProgress,
                            scratchpad = scratchpad.toList()
                        )
                    )

                    fullPromptForLog = PromptBuilder.buildPrompt(
                        userRequest = userPrompt,
                        screenInfo = screenInfo,
                        knowledgeContext = enhancedContextForLog,
                        actionHistory = actionHistory.map {
                            AgentAction(
                                type = ActionType.valueOf(it.actionType),
                                description = it.description,
                                confidence = 0.8f
                            )
                        }
                    )

                    val decision = requestAIDecisionUseCase(
                        RequestAIDecisionUseCase.Params(
                            userPrompt = userPrompt,
                            screenInfo = screenInfo,
                            enhancedContext = enhancedContextForLog,
                            isCancelled = { isTaskCancelled },
                            round = round
                        )
                    ) ?: run {
                        callback.onError(round, Exception("API调用失败"), accumTokens)
                        return
                    }
                    textDecision = decision

                    finalAction = decision.finalAction
                    // 收集 Scratchpad 搜索结果
                    if (decision.scratchpadEntries.isNotEmpty()) {
                        addToScratchpad(decision.scratchpadEntries)
                        Log.d(TAG, "Scratchpad 写入 ${decision.scratchpadEntries.size} 条搜索结果")
                    }
                    // 提取 LLM 自管理的任务进度
                    if (finalAction.progress != null) {
                        llmProgress = finalAction.progress
                        Log.d(TAG, "LLM进度: ${finalAction.progress.currentStep} | 已完成${finalAction.progress.completedSteps.size}步 | 剩余${finalAction.progress.remainingSteps.size}步")
                    }
                    callback.onContent(round, "决策: ${finalAction.type} - ${finalAction.description}")
                    FloatingProgressManager.updateProgress(round, "${finalAction.type.name} ${finalAction.description.take(40)}")
                }
                // ============ 分流结束，finalAction 已就绪 ============

                val actionSig = actionTrackingUseCase.actionSignature(finalAction!!.type.name, buildActionParams(finalAction), screenInfo?.currentPackage)
                actionTrackingUseCase.track(actionSig)

                

                val result = actionExecutor.executeWithChangeDetection(finalAction, screenshotBmp, screenInfo, round, userPrompt)

                if (isTaskCancelled) {
                    callback.onComplete(round, "任务已被用户取消", accumTokens)
                    return
                }

                // 屏幕描述复用标记：仅当上一轮是只读屏幕描述（VISUAL_DESCRIBE）时才可复用，
                // 界面未变（只读操作不改变屏幕）；任何实际操作（LOCATE/TAP/SCROLL 等）后都必须重新描述
                // （LOCATE 已内置自动点击，界面必变，不能按"仅定位"复用——历史 bug 修复）
                screenDescriptor.lastRoundOnlyGrounding = finalAction.type == ActionType.VISUAL_DESCRIBE
                LiveLogBuffer.append("🎬 ${finalAction.description} → ${if (result.isSuccess) "✓" else "✗"} ${(if (result.isSuccess) result.data else result.error)?.take(60)}")
                callback.onToolCall(round, "action", finalAction.type.name,
                    finalAction.type.name, buildActionParams(finalAction).toString())

                // v3.2: ASK_USER 轮不 recycle 截图，保留给下一轮复用（屏幕未变化）
                // 在 recycle 前压缩截图字节，供后续 logRound 保存
                val screenshotJpegForLog = AgentLogger.compressScreenshot(screenshotBmp)
                if (finalAction.type == ActionType.ASK_USER) {
                    lastActionWasAskUser = true
                    cachedCapture = capture
                    Log.d(TAG, "ASK_USER 轮，保留截图供下一轮复用")
                } else {
                    lastActionWasAskUser = false
                    cachedCapture = null
                    screenshotBmp.recycleSafely()
                    // 清空 BitmapPool 缓存，释放本轮所有缩放副本
                    com.palmagent.app.utils.BitmapPool.clear()
                }

                actionExecutor.postActionDelayAndWait(finalAction.type)
                progressTracker.recordHomeAttempt(finalAction.type.name, screenInfo?.currentPackage)

                if (finalAction.type == ActionType.WAIT) {
                    waitConsecutiveCount++
                } else {
                    waitConsecutiveCount = 0
                }

                val toolResult = if (result.isSuccess) {
                    ToolResult.success(result.data ?: "")
                } else {
                    ToolResult.error(result.error ?: "操作失败")
                }
                callback.onToolResult(round, "action", finalAction.type.name,
                    finalAction.type.name, buildActionParams(finalAction).toString(), toolResult)

                actionHistory.add(ActionRecord(
                    round = round,
                    actionType = finalAction.type.name,
                    params = buildActionParams(finalAction),
                    description = finalAction.description,
                    screenPackage = screenInfo?.currentPackage,
                    success = result.isSuccess,
                    resultSummary = buildResultSummaryForHistory(finalAction, result),
                    screenChange = null,
                    executionTimeMs = if (result.isSuccess && result.data?.contains("ms") == true) {
                        result.data?.split("ms")?.firstOrNull()?.filter { it.isDigit() }?.toLongOrNull() ?: 0
                    } else 0,
                    visualQuestion = finalAction.visualQuestion
                ))
                // 限制历史记录数量，丢弃最旧条目释放内存
                while (actionHistory.size > MAX_ACTION_HISTORY) {
                    actionHistory.removeAt(0)
                }

                // ============ 统一轮次日志保存 ============
                val mode = if (KVUtils.isVisionModeEnabled()) "VL" else "TEXT"
                val modelInput = if (vlDecisionResult != null) {
                    "=== System Prompt ===\n${PromptBuilder.getVisionSystemPrompt()}\n\n=== User Prompt ===\n${vlDecisionResult!!.userPrompt}"
                } else {
                    fullPromptForLog
                }
                val modelOutput = if (vlDecisionResult != null) {
                    vlDecisionResult!!.rawContent
                } else {
                    // 文本模式：DecisionResult 不含 rawResponse，用 finalAction 的 JSON 表示
                    "actionType=${textDecision?.finalAction?.type}\ndescription=${textDecision?.finalAction?.description}"
                }
                AgentLogger.logRound(
                    round = round,
                    mode = mode,
                    screenInfo = screenInfo,
                    screenshotJpegBytes = screenshotJpegForLog,
                    modelInput = modelInput,
                    modelOutput = modelOutput,
                    action = finalAction,
                    actionSuccess = result.isSuccess,
                    actionResultSummary = buildResultSummaryForHistory(finalAction, result),
                    ocrText = if (vlDecisionResult != null) "" else screenOcrTextForLog,
                    enhancedContext = if (vlDecisionResult != null) "" else enhancedContextForLog,
                    planContext = planContext
                )

                if (finalAction.type == ActionType.FINISH) {
                    // 收尾兜底：模型 FINISH 时可能遗留 remaining_steps 未合并、status 未置 completed
                    // （实机日志曾出现：最后一轮 CLICK 已完成但 progress 仍写"剩余=[...]"，status=in_progress）
                    // 此处强制规范化终态，保证任何消费 progress 的地方拿到自洽的 completed 状态
                    finalAction.progress?.let { p ->
                        llmProgress = p.copy(
                            completedSteps = (p.completedSteps + p.remainingSteps).distinct(),
                            remainingSteps = emptyList(),
                            status = "completed"
                        )
                    }
                    callback.onComplete(round, result.data ?: "", accumTokens)
                    return
                }

                // 用户拒绝 REQUEST_USER_ACTION → 直接结束任务，不重规划
                if (finalAction.type == ActionType.REQUEST_USER_ACTION && !result.isSuccess) {
                    val finishMsg = "用户拒绝了操作，任务已终止。"
                    AgentLogger.log(AgentLogger.LogType.SYSTEM, "用户拒绝REQUEST_USER_ACTION，结束任务")
                    LiveLogBuffer.append("🚫 $finishMsg")
                    callback.onComplete(round, finishMsg, accumTokens)
                    return
                }

                // 永久失败检测与重规划触发
                if (!result.isSuccess && shouldTriggerReplan(result, finalAction)) {
                    LiveLogBuffer.append("🔄 检测到永久失败，触发重规划...")
                    val replanTriggered = triggerReplan(
                        failureReason = formatErrorForLLM(result, finalAction),
                        failedAction = finalAction,
                        completedSteps = llmProgress?.completedSteps ?: emptyList(),
                        currentScreen = screenInfo,
                        // 双注入修复：原计划以 userPrompt（决策模型输出的 plan）为准，
                        // 不依赖 planContext（其仅在重规划成功后承载新计划）
                        originalPlan = userPrompt,
                        callback = callback
                    )
                    if (replanTriggered) {
                        LiveLogBuffer.append("✓ 重规划成功，继续执行新计划")
                    } else {
                        LiveLogBuffer.append("⚠ 重规划失败，降级为执行模型自行处理")
                    }
                }
            }

            val maxRoundMsg = "任务已达到最大执行轮数（$maxIterations 轮）自动终止。" +
                    "如果您希望继续执行，请调整最大轮数设置后重新发起任务。"
            Log.w(TAG, maxRoundMsg)
            callback.onComplete(round, maxRoundMsg, accumTokens)

        } catch (e: CancellationException) {
            Log.d(TAG, "任务被取消")
            callback.onError(round, e, accumTokens)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "工具参数缺失: ${e.message}")
            LiveLogBuffer.append("⚠️ 工具参数缺失: ${e.message}")
            actionHistory.add(com.palmagent.app.model.ActionRecord(
                round = round,
                actionType = "WAIT",
                params = emptyMap(),
                description = "【执行错误】${e.message}，请重新输出含正确参数的动作",
                screenPackage = null,
                success = false,
                resultSummary = "【执行错误】${e.message}"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: ${e.message}", e)
            callback.onError(round, e, accumTokens)
        } finally {
            isTaskRunning = false
            isTaskCancelled = false
            // v3.2: 任务终止时清理 ASK_USER 缓存截图，防止内存泄漏
            cachedCapture?.screenshotBmp?.recycleSafely()
            cachedCapture = null
            lastActionWasAskUser = false
            scratchpad.clear()
            actionExecutor.onScratchpadForget = null
            FloatingProgressManager.setIdleState()
            AgentLogger.endTask("任务终止")
        }
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    private suspend fun handleUserInterruption() {
        Log.d(TAG, "检测到用户操作，暂停任务")
        LiveLogBuffer.append("⏸ 检测到用户操作，任务已暂停")
        suspendCancellableCoroutine { cont ->
            FloatingProgressManager.showPaused {
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
            cont.invokeOnCancellation {
                FloatingProgressManager.setIdleState()
            }
        }
        FloatingProgressManager.enterMinimizedMode()
        Log.d(TAG, "任务已恢复")
        LiveLogBuffer.append("▶ 任务已恢复")
    }

    private fun buildStateWarningFromTracking(): String {
        return actionTrackingUseCase.getCurrentWarning()
    }

    private fun buildDeviceContext(): String {
        val metrics = try {
            val wm = AgentApplication.instance.getSystemService(android.view.WindowManager::class.java)
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(dm)
            dm
        } catch (_: Exception) { null }

        val screenWidth = metrics?.widthPixels ?: 0
        val screenHeight = metrics?.heightPixels ?: 0
        return "屏幕尺寸：宽=${screenWidth}px, 高=${screenHeight}px"
    }

    private fun buildResultSummaryForHistory(action: AgentAction, result: ToolResult): String {
        // ASK_USER 结果截断：批量提问可能含多个问答，单问答案上限 200 字符
        // 注意：ActionExecutor.handleAskUser 返回值已含"用户回答："前缀，此处仅截断，不再加前缀
        if (action.type == ActionType.ASK_USER) {
            return if (result.isSuccess) (result.data ?: "").take(200) else "追问失败"
        }
        return if (result.isSuccess) (result.data ?: "") else (result.error ?: "失败")
    }

    /**
     * 判断是否应触发重规划
     * 复用 ErrorClassifier 分类，仅 Fatal 类错误且可重规划时触发
     */
    private fun shouldTriggerReplan(result: ToolResult, action: AgentAction): Boolean {
        if (result.isSuccess) return false

        // 从 metadata 获取错误类型
        val errorType = result.metadata[ToolResult.META_ERROR_TYPE] as? String
        val failureCategory = result.metadata[ToolResult.META_FAILURE_CATEGORY] as? String

        // 仅 FATAL 类错误触发重规划
        if (errorType != "FATAL") return false

        // 排除不可重规划的 Fatal 错误（服务不可用等无法通过换方案解决的）
        val nonReplannable = listOf("PERMISSION_DENIED", "SERVICE_UNAVAILABLE")
        if (failureCategory in nonReplannable) return false

        // 检查重规划限流
        if (replanCount >= MAX_REPLAN) {
            Log.w(TAG, "重规划次数超限($MAX_REPLAN)，不再触发重规划")
            return false
        }

        // 检查决策模型是否可用
        if (!KVUtils.hasPlannerConfig()) return false

        return true
    }

    /**
     * 将工具错误转为 LLM 可读的简明信息（不暴露技术细节）
     */
    private fun formatErrorForLLM(result: ToolResult, action: AgentAction): String {
        if (result.isSuccess) return result.data ?: ""

        val errorType = result.metadata[ToolResult.META_ERROR_TYPE] as? String ?: "UNKNOWN"
        val suggestion = result.metadata[ToolResult.META_ERROR_SUGGESTION] as? String ?: ""

        return buildString {
            append("❌ ${action.type} 执行失败")
            append("\n类型: $errorType")
            append("\n原因: ${result.error?.take(80)}")
            if (suggestion.isNotBlank()) {
                append("\n建议: $suggestion")
            }
        }
    }

    /**
     * 触发重规划
     * v9: 回调决策模型重新规划（替代旧的规划模型调用），
     * 决策模型有 kb_read 访问权限，能基于 SOP 知识生成替代方案
     */
    private suspend fun triggerReplan(
        failureReason: String,
        failedAction: AgentAction,
        completedSteps: List<String>,
        currentScreen: ScreenInfo?,
        originalPlan: String,
        callback: AgentCallback
    ): Boolean {
        replanCount++
        Log.d(TAG, "触发重规划（第${replanCount}次），失败原因: $failureReason")

        val replanPrompt = buildString {
            appendLine("原计划执行中遇到困难，需要调整后续步骤。")
            appendLine()
            appendLine("原计划：")
            appendLine(originalPlan)
            appendLine()
            appendLine("当前困难：$failureReason")
            appendLine("失败动作：${failedAction.type} - ${failedAction.description}")
            if (completedSteps.isNotEmpty()) {
                appendLine("已完成：${completedSteps.joinToString("、")}")
            }
            appendLine("当前应用：${currentScreen?.currentPackage ?: "未知"}")
            appendLine()
            appendLine("请基于当前困难重新规划后续步骤，输出新的分步骤操作指引。")
        }

        return try {
            callback.onContent(0, "检测到不可恢复的失败，正在重新规划任务...")
            val decisionService = DecisionDialogService()
            val result = decisionService.chat(replanPrompt, history = emptyList())

            if (result is DecisionDialogService.DialogResult.Ready && result.plan.steps.isNotEmpty()) {
                planContext = result.plan
                Log.d(TAG, "重规划成功，新 plan ${planContext?.steps?.size ?: 0}步")
                callback.onContent(0, "任务计划已更新，继续执行...")
                return true
            } else {
                val errorMsg = when (result) {
                    is DecisionDialogService.DialogResult.Error -> result.message
                    is DecisionDialogService.DialogResult.NeedMoreInfo -> "决策模型要求更多信息: ${result.message}"
                    else -> "未知错误"
                }
                Log.w(TAG, "重规划失败: $errorMsg")
                callback.onContent(0, "重规划失败，继续尝试其他方式...")
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "重规划异常: ${e.message}")
            return false
        }
    }

    private fun buildActionParams(action: AgentAction): Map<String, Any?> {
        val params = mutableMapOf<String, Any?>()
        val screenSize = getScreenSize()
        val screenW = screenSize[0]
        val screenH = screenSize[1]

        val hasValidCoord = action.coordinate != null &&
            action.coordinate.x in 0 until screenW &&
            action.coordinate.y in 0 until screenH

        if (hasValidCoord) {
            params["x"] = action.coordinate!!.x
            params["y"] = action.coordinate!!.y
        } else if (action.targetElement != null) {
            val bounds = action.targetElement.bounds
            params["x"] = (bounds.left + bounds.right) / 2
            params["y"] = (bounds.top + bounds.bottom) / 2
        } else if (action.coordinate != null) {
            params["x"] = action.coordinate.x.coerceIn(10, screenW - 10)
            params["y"] = action.coordinate.y.coerceIn(10, screenH - 10)
        }
        action.text?.let { params["text"] = it }
        action.targetId?.let { params["target_id"] = it }
        action.targetDesc?.let { params["target_desc"] = it }
        action.actionDesc?.let { params["action_desc"] = it }

        if (action.type == ActionType.AUTO_INPUT) {
            action.instruction?.let { params["instruction"] = it }
            action.searchIcon?.let { params["search_icon"] = it.toString() }
        }
        if (action.type == ActionType.OPEN_APP) {
            val appName = action.text?.takeIf { it.isNotBlank() } ?: action.description?.takeIf { it.isNotBlank() }
            appName?.let { params["app_name"] = it }
        }
        if (action.type == ActionType.LOCATE) {
            action.text?.let { params["text"] = it }
            val desc = action.description?.takeIf { it.isNotBlank() } ?: action.targetDesc?.takeIf { it.isNotBlank() }
            desc?.let { params["description"] = it }
        }
        if (action.type == ActionType.REQUEST_USER_ACTION) {
            val title = action.text?.takeIf { it.isNotBlank() } ?: action.description?.takeIf { it.isNotBlank() }
            title?.let { params["title"] = it }
            val steps = action.description?.takeIf { it.isNotBlank() } ?: action.text?.takeIf { it.isNotBlank() }
            steps?.let { params["steps"] = it }
        }
        if (action.type == ActionType.ASK_USER) {
            // 记录已问问题文本，供下一轮提示词注入"已问问题"区域防重追问
            action.questions?.let { qs ->
                params["asked_questions"] = qs.map { it.question }
            }
        }

        return params
    }

    private fun getScreenSize(): IntArray {
        val metrics = android.util.DisplayMetrics()
        val wm = AgentApplication.instance.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels)
    }

    override fun cancel() {
        isTaskCancelled = true
    }

    override fun shutdown() {
        cancel()
    }

    // ============ VL 模式辅助方法 ============

    /**
     * VL 模式决策：截图 → GUI-Plus 模型 → 适配为 AgentAction
     */
    private suspend fun decideViaVision(
        userPrompt: String,
        screenshot: Bitmap?,
        actionHistory: List<ActionRecord>,
        round: Int,
        llmProgress: com.palmagent.app.model.TaskProgress?,
        planContext: Plan?
    ): VisionDecisionResult? {
        if (screenshot == null) {
            Log.w(TAG, "VL决策跳过: 截图为空")
            return null
        }

        // 1. 构建 VL User Prompt
        val vlUserPrompt = buildVisionUserPrompt(userPrompt, actionHistory, llmProgress, scratchpad.toList(), planContext)

        // 2. 获取屏幕尺寸
        val screenSize = getScreenSize()
        val screenWidth = screenSize[0]
        val screenHeight = screenSize[1]

        // 3. 调用 GUI-Plus 模型
        val result = GuiOwlService.decide(vlUserPrompt, screenshot, screenWidth, screenHeight)
        if (!result.success) {
            Log.w(TAG, "VL决策失败: ${result.error}")
            return null
        }

        // 4. 将决策结果适配为 AgentAction
        return try {
            val action = GuiOwlActionAdapter.adapt(result)
            Log.d(TAG, "VL决策: ${action.type} - ${action.description}")
            VisionDecisionResult(action, result.rawResponse, vlUserPrompt)
        } catch (e: Exception) {
            Log.w(TAG, "VL决策解析失败: ${e.message}")
            LiveLogBuffer.append("⚠ VL决策解析失败: ${e.message}")
            null
        }
    }

    /**
     * 构建 VL 模式的 User Prompt
     * 仅包含：用户任务 + 操作历史 + Running Summary + 工作记忆 + 实时进度
     * 不含 OCR 文本、无障碍树、VLM 屏幕描述
     */
    private fun buildVisionUserPrompt(
        userPrompt: String,
        actionHistory: List<ActionRecord>,
        progress: com.palmagent.app.model.TaskProgress?,
        scratchpad: List<ScratchpadEntry> = emptyList(),
        planContext: Plan? = null
    ): String = buildString {
        appendLine("【用户任务】$userPrompt")
        appendLine()

        // 决策模型 Plan 已作为 userPrompt（【用户任务】区域）传递一次，此处仅在重规划后注入新计划
        // 双注入修复：正常执行时 planContext 为空，不重复注入同一份 plan
        if (planContext != null) {
            appendLine("【决策模型任务计划】（已经过用户确认，其中目标对象、参数等信息为用户明确提供，无需追问）")
            appendLine(PlanFormatter.format(planContext))
            appendLine()
        }

        // 工作记忆（Scratchpad — 跨轮保留的搜索结果）
        if (scratchpad.isNotEmpty()) {
            appendLine("【工作记忆】")
            scratchpad.forEach { entry ->
                appendLine("  [${entry.id}] ${entry.source}: ${entry.content.take(200)}")
            }
            appendLine()
        }

        

        // 最近操作历史
        if (actionHistory.isNotEmpty()) {
            appendLine("【最近操作回顾】")
            for (record in actionHistory.takeLast(5)) {
                val status = if (record.success) "✓" else "✗"
                val timeInfo = if (record.executionTimeMs > 0) " (${record.executionTimeMs}ms)" else ""
                appendLine("  第${record.round}轮: $status ${record.actionType} ${record.description} → ${record.resultSummary}$timeInfo")
            }
            appendLine()
        }

        // 已问问题（防重追问：提示模型不要重复追问已问过的问题）
        val askedQuestions = actionHistory
            .filter { it.actionType == ActionType.ASK_USER.name }
            .mapNotNull { it.params["asked_questions"] as? List<*> }
            .flatten()
            .filterIsInstance<String>()
        if (askedQuestions.isNotEmpty()) {
            appendLine("【已问问题（禁止重复追问）】")
            askedQuestions.forEachIndexed { idx, q ->
                appendLine("  ${idx + 1}. $q")
            }
            appendLine()
        }

        // 实时进度
        if (progress != null) {
            appendLine("【实时进度】")
            appendLine("当前步骤: ${progress.currentStep}")
            if (progress.completedSteps.isNotEmpty()) {
                appendLine("已完成: ${progress.completedSteps.joinToString(" ✅") { it }} ✅")
            }
            if (progress.remainingSteps.isNotEmpty()) {
                appendLine("剩余: ${progress.remainingSteps.joinToString(" ⏳") { it }} ⏳")
            }
            appendLine("状态: ${progress.status}")
            appendLine()
        }

        appendLine("请分析截图，决定下一步操作。")
    }

    fun addToScratchpad(entries: List<ScratchpadEntry>) {
        scratchpad.addAll(entries)
        while (scratchpad.size > 20) {
            scratchpad.removeAt(0)
        }
    }

    fun forgetFromScratchpad(target: String) {
        val byId = scratchpad.removeAll { it.id == target }
        if (byId) return
        scratchpad.removeAll { entry ->
            entry.source.contains(target, ignoreCase = true) ||
            entry.content.contains(target, ignoreCase = true)
        }
    }
}
