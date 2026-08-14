package com.palmagent.app.domain.usecase

import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.service.ToolDecisionEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.pow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 决策请求 Use Case
 *
 * 封装带指数退避重试的 AI 决策请求逻辑。
 */
@Singleton
class RequestAIDecisionUseCase @Inject constructor(
    private val toolDecisionEngine: ToolDecisionEngine
) {
    companion object {
        private const val TAG = "RequestAIDecisionUC"
        const val MAX_API_RETRIES = 3
        const val BASE_RETRY_DELAY_MS = 1000L
    }

    data class Params(
        val userPrompt: String,
        val screenInfo: ScreenInfo?,
        val enhancedContext: String,
        val isCancelled: () -> Boolean,
        val round: Int = 0
    )

    suspend operator fun invoke(params: Params): ToolDecisionEngine.DecisionResult? {
        for (retry in 0 until MAX_API_RETRIES) {
            if (params.isCancelled()) break
            try {
                return toolDecisionEngine.executeWithTools(
                    userRequest = params.userPrompt,
                    screenInfo = params.screenInfo,
                    initialKnowledgeContext = params.enhancedContext,
                    isCancelled = params.isCancelled,
                    round = params.round
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "第${retry + 1}次决策请求失败: ${e.message}", e)
                if (retry < MAX_API_RETRIES - 1) {
                    val delayMs = (BASE_RETRY_DELAY_MS * 2.0.pow(retry)).toLong()
                    delay(delayMs)
                }
            }
        }
        return null
    }
}
