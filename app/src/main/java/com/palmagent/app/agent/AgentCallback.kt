package com.palmagent.app.agent

import com.palmagent.app.tool.ToolResult

interface AgentCallback {
    fun onLoopStart(round: Int)
    fun onContent(round: Int, content: String)
    fun onToolCall(round: Int, toolId: String, toolName: String, displayName: String, parameters: String)
    fun onToolResult(round: Int, toolId: String, toolName: String, displayName: String, parameters: String, result: ToolResult)
    fun onComplete(round: Int, finalAnswer: String, totalTokens: Int)
    fun onError(round: Int, error: Exception, totalTokens: Int)
}