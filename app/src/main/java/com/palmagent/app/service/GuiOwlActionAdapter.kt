package com.palmagent.app.service

import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.ActionType
import com.palmagent.app.model.Coordinate

/**
 * GUI-Plus 动作适配器
 *
 * 将 GuiOwlService.DecideResult（action + coordinate）映射为 AgentAction。
 * action 已在 GuiOwlService.normalizeAction() 归一化到统一动作集：
 * - click          → CLICK       (coordinate)
 * - long_press     → LONG_PRESS  (coordinate)
 * - swipe          → SWIPE       (coordinate + coordinateEnd)
 * - type           → AUTO_INPUT  (text)
 * - system_button  → BACK / HOME (button name)
 * - open           → OPEN_APP    (text=应用名)
 * - wait           → WAIT
 * - answer         → VISUAL_DESCRIBE (text=回答内容)
 * - terminate      → FINISH
 *
 * 模型原生 computer_use 动作（left_click/scroll/mouse_move 等）已在服务层归一化，此处无需感知。
 */
object GuiOwlActionAdapter {

    fun adapt(result: GuiOwlService.DecideResult): AgentAction {
        val baseConfidence = if (result.success) 1.0f else 0.0f

        return when (result.action.lowercase().trim()) {
            "click" -> AgentAction(
                type = ActionType.CLICK,
                coordinate = result.coordinate,
                description = "点击",
                confidence = baseConfidence
            )

            "long_press" -> AgentAction(
                type = ActionType.LONG_PRESS,
                coordinate = result.coordinate,
                description = "长按",
                confidence = baseConfidence
            )

            "swipe" -> AgentAction(
                type = ActionType.SWIPE,
                coordinate = result.coordinate,
                coordinateEnd = result.coordinateEnd,
                description = "滑动",
                confidence = baseConfidence
            )

            "type" -> AgentAction(
                type = ActionType.AUTO_INPUT,
                text = result.text ?: "",
                description = "输入文本",
                confidence = baseConfidence
            )

            "system_button" -> {
                val button = result.text?.lowercase()?.trim() ?: "back"
                if (button == "home") {
                    AgentAction(
                        type = ActionType.HOME,
                        description = "主页键",
                        confidence = baseConfidence
                    )
                } else {
                    AgentAction(
                        type = ActionType.BACK,
                        description = "返回键",
                        confidence = baseConfidence
                    )
                }
            }

            "open" -> AgentAction(
                type = ActionType.OPEN_APP,
                text = result.text,
                description = "打开应用",
                confidence = baseConfidence
            )

            "wait" -> AgentAction(
                type = ActionType.WAIT,
                description = "等待",
                confidence = baseConfidence
            )

            "answer" -> AgentAction(
                type = ActionType.VISUAL_DESCRIBE,
                text = result.text,
                description = "视觉描述",
                confidence = baseConfidence
            )

            "terminate" -> AgentAction(
                type = ActionType.FINISH,
                description = "任务完成",
                confidence = baseConfidence
            )

            else -> AgentAction(
                type = ActionType.WAIT,
                description = "未知动作: ${result.action}",
                confidence = 0.0f
            )
        }
    }
}
