package com.palmagent.app.service

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.agent.AgentLogger
import com.palmagent.app.agent.ScratchpadEntry
import com.palmagent.app.model.ActionType
import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.model.ToolCallResult
import com.palmagent.app.utils.recycleSafely
import com.palmagent.app.LiveLogBuffer

class ToolDecisionEngine(
    private val aiService: AIService,
    private val log: (String) -> Unit
) {
    companion object {
        private const val TAG = "ToolDecision"
    }

    suspend fun executeWithTools(
        userRequest: String,
        screenInfo: ScreenInfo?,
        initialKnowledgeContext: String = "",
        isCancelled: () -> Boolean = { false },
        round: Int = 0
    ): DecisionResult {
        log("正在请求AI决策...")

        // 收集 Scratchpad 条目（web_search 动作触发时写入）
        val scratchpadEntries = mutableListOf<ScratchpadEntry>()
        var seq = 0

        var action = aiService.generateAction(
            userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = initialKnowledgeContext,
        )

        log("AI决策：${action.type} - ${action.description}")

        val toolResults = mutableListOf<ToolCallResult>()
        var contextFromTools = initialKnowledgeContext
        var loopCount = 0
        // P2-6 修复：工具循环加 30s 整体 deadline，防止 5×15s=75s 超时
        val loopStartTime = System.currentTimeMillis()

        while (loopCount < 5 && !isCancelled() &&
            System.currentTimeMillis() - loopStartTime < 30_000L) {
            loopCount++

            when (action.type) {
                ActionType.WEB_SEARCH -> {
                    val query = action.text?.takeIf { it.isNotBlank() } ?: action.description
                    if (query.isBlank()) {
                        log("WEB_SEARCH 缺少 query 参数，跳过")
                        toolResults.add(ToolCallResult(
                            toolName = "web_search",
                            success = false,
                            error = "query 参数为空"
                        ))
                        return DecisionResult(
                            finalAction = action,
                            toolResults = toolResults,
                            combinedContext = contextFromTools,
                            scratchpadEntries = scratchpadEntries
                        )
                    }
                    log("AI请求联网搜索: ${query.take(80)}")
                    LiveLogBuffer.append("🔍 模型请求联网搜索: ${query.take(80)}")

                    val searchResult = WebSearchService.search(query, count = 5)
                    seq++
                    val entryId = "sp-${round}-${seq}"
                    val entryContent = (searchResult.content ?: "").take(300)
                    scratchpadEntries.add(ScratchpadEntry(
                        id = entryId,
                        content = entryContent,
                        source = "web_search: $query",
                        roundCreated = round
                    ))

                    toolResults.add(ToolCallResult(
                        toolName = "web_search",
                        success = searchResult.success,
                        content = searchResult.content ?: "",
                        error = searchResult.error,
                        durationMs = searchResult.durationMs
                    ))

                    if (searchResult.success) {
                        log("搜索成功: ${entryContent.take(80)}，重新请求AI决策...")
                        LiveLogBuffer.append("✓ 搜索成功，结果已写入工作记忆")
                    } else {
                        log("搜索失败: ${searchResult.error}")
                        LiveLogBuffer.append("❌ 搜索失败: ${searchResult.error}")
                    }

                    val combined = buildString {
                        if (contextFromTools.isNotBlank()) {
                            appendLine(contextFromTools)
                            appendLine()
                        }
                        appendLine("【联网搜索结果 ID=$entryId】query: $query")
                        if (searchResult.success) {
                            appendLine(entryContent)
                        } else {
                            appendLine("搜索失败：${searchResult.error}")
                            appendLine("请根据当前屏幕信息自行判断下一步操作。")
                        }
                    }
                    contextFromTools = combined

                    logToolLoopModelInput(userRequest, combined, round, loopCount)
                    action = aiService.generateAction(
                        userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = combined,
                    )
                }

                ActionType.VISUAL_DESCRIBE -> {
                    val question = action.text?.takeIf { it.isNotBlank() } ?: action.description
                    log("AI请求视觉描述: ${question.take(80)}")
                    LiveLogBuffer.append("👁 模型请求视觉描述: ${question.take(80)}")

                    val a11y = GUIAccessibilityService.instance
                    val screenshotBmp: Bitmap? = a11y?.takeScreenshot()

                    if (screenshotBmp != null) {
                        try {
                            val vlmResult = VlmService.query(question, screenshotBmp)

                            AgentLogger.log(AgentLogger.LogType.GUI_PLUS_GROUNDING,
                                "VLM视觉描述: ${question.take(100)}",
                                vlmResult.answer.take(500), 0)

                            if (vlmResult.success) {
                                val resultContent = buildString {
                                    appendLine("视觉描述结果:")
                                    appendLine("  问题: $question")
                                    appendLine("  回答: ${vlmResult.answer}")
                                    appendLine("  耗时: ${vlmResult.durationMs}ms")
                                }

                                toolResults.add(ToolCallResult(
                                    toolName = "visual_describe",
                                    success = true,
                                    content = resultContent,
                                    durationMs = vlmResult.durationMs
                                ))

                                val combined = buildString {
                                    if (contextFromTools.isNotBlank()) {
                                        appendLine(contextFromTools)
                                        appendLine()
                                    }
                                    appendLine(resultContent)
                                }

                                log("视觉描述成功: ${vlmResult.answer.take(100)}，重新请求AI决策...")
                                LiveLogBuffer.append("✓ 视觉描述成功: ${vlmResult.answer.take(80)}")

                                logToolLoopModelInput(userRequest, combined, round, loopCount)
                                action = aiService.generateAction(
                                    userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = combined,
                                )
                                contextFromTools = combined
                            } else {
                                val errorMsg = vlmResult.error ?: "描述失败"
                                log("视觉描述失败: $errorMsg")
                                LiveLogBuffer.append("❌ 视觉描述失败: $errorMsg")

                                toolResults.add(ToolCallResult(
                                    toolName = "visual_describe",
                                    success = false,
                                    error = errorMsg,
                                    durationMs = vlmResult.durationMs
                                ))

                                val fallbackContext = buildString {
                                    if (contextFromTools.isNotBlank()) {
                                        appendLine(contextFromTools)
                                        appendLine()
                                    }
                                    appendLine("视觉描述失败（$errorMsg），请根据当前屏幕信息自行判断下一步操作。")
                                }
                                logToolLoopModelInput(userRequest, fallbackContext, round, loopCount)
                                action = aiService.generateAction(
                                    userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = fallbackContext,
                                )
                                contextFromTools = fallbackContext
                            }
                        } finally {
                            screenshotBmp.recycleSafely()
                        }
                    } else {
                        log("无法截图，视觉描述不可用")
                        LiveLogBuffer.append("❌ 视觉描述不可用: 无法获取截图")

                        toolResults.add(ToolCallResult(
                            toolName = "visual_describe",
                            success = false,
                            error = "无法获取屏幕截图"
                        ))

                        val fallbackContext = buildString {
                            if (contextFromTools.isNotBlank()) {
                                appendLine(contextFromTools)
                                appendLine()
                            }
                            appendLine("视觉描述不可用（无法截图），请根据当前屏幕信息自行判断。")
                        }
                        logToolLoopModelInput(userRequest, fallbackContext, round, loopCount)
                        action = aiService.generateAction(
                            userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = fallbackContext,
                        )
                        contextFromTools = fallbackContext
                    }
                }

                ActionType.PLAN_TASK -> {
                    val taskDescription = action.text ?: userRequest
                    log("执行模型遇到困难，使用 PLAN_TASK 自反思: ${taskDescription.take(80)}")
                    LiveLogBuffer.append("🤔 执行模型遇到困难，重新评估策略")

                    // v9: PLAN_TASK 作为执行模型的自反思机制
                    // 执行模型描述遇到的困难，系统将其作为额外上下文注入，让执行模型重新决策
                    // 不再调用规划模型（已砍掉），执行模型根据当前屏幕+困难描述自行调整策略
                    contextFromTools = buildString {
                        if (contextFromTools.isNotBlank()) {
                            appendLine(contextFromTools)
                            appendLine()
                        }
                        appendLine("【执行困难】${taskDescription}")
                        appendLine("请根据当前屏幕信息和上述困难，重新评估操作策略。")
                    }

                    toolResults.add(ToolCallResult(
                        toolName = "plan_task",
                        success = true,
                        content = "已记录困难，重新决策"
                    ))

                    logToolLoopModelInput(userRequest, contextFromTools, round, loopCount)
                    action = aiService.generateAction(
                        userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = contextFromTools,
                    )
                }

                else -> return DecisionResult(
                    finalAction = action,
                    toolResults = toolResults,
                    combinedContext = contextFromTools,
                    scratchpadEntries = scratchpadEntries
                )
            }

            log("AI最新决策：${action.type} - ${action.description}")
        }

        return DecisionResult(
            finalAction = action,
            toolResults = toolResults,
            combinedContext = contextFromTools,
            scratchpadEntries = scratchpadEntries
        )
    }

    data class DecisionResult(
        val finalAction: AgentAction,
        val toolResults: List<ToolCallResult>,
        val combinedContext: String,
        val scratchpadEntries: List<ScratchpadEntry> = emptyList()
    )

    /**
     * 记录工具循环中的 LLM 输入到独立文件
     * 文件名: round_N_model_input_tool_M.txt（M 是工具循环序号，从 1 开始）
     * 触发场景: VISUAL_DESCRIBE / PLAN_TASK 等工具调用后重新请求 AI 决策
     */
    private fun logToolLoopModelInput(
        userRequest: String,
        knowledgeContext: String,
        round: Int,
        attempt: Int
    ) {
        if (round <= 0) return  // round 未传时不记录
        val fullPrompt = PromptBuilder.buildPrompt(userRequest, knowledgeContext = knowledgeContext)
        AgentLogger.logToolLoopModelInput(fullPrompt, round, attempt)
    }
}
