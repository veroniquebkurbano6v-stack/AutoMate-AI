package com.palmagent.app.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.agent.Plan
import com.palmagent.app.agent.PlanFormatter
import com.palmagent.app.agent.PlanStep
import com.palmagent.app.model.Question
import com.palmagent.app.model.QuestionOption
import com.palmagent.app.tool.impl.KbReadTool
import com.palmagent.app.ui.chat.ChatMessage
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 决策对话服务
 *
 * 管理用户与决策模型的多轮对话。决策模型判断用户描述的手机操作任务
 * 是否有足够信息制定执行计划：信息不足时追问，信息足够时返回规划方案。
 *
 * 决策模型配置读取 KVUtils（getPlannerApiKey 等，沿用"任务决策模型"设置项），
 * 使用 OpenAI 兼容 API 格式。
 *
 * 支持高德地图 function calling：模型根据用户问题自主决定是否调用
 * amap_nearby/amap_search/amap_weather/amap_directions 工具，服务层
 * 执行后把结果回传给模型生成最终回复。
 */
class DecisionDialogService {

    /** 对话结果：密封类，表示追问 / 就绪 / 出错三种状态 */
    sealed class DialogResult {
        /** 信息不足，需要追问用户。questions 非空时为结构化批量追问，message 为兜底纯文本 */
        data class NeedMoreInfo(val message: String, val questions: List<Question>? = null) : DialogResult()

        /** 信息足够，返回结构化任务计划 */
        data class Ready(
            val plan: Plan,
            val userSummary: String = ""
        ) : DialogResult()

        /** 调用出错 */
        data class Error(val message: String) : DialogResult()
    }

    companion object {
        private const val TAG = "DecisionDialogService"
        // P0 修复演进：1024 → 4096 → 16384。
        // deepseek-v4-flash 的多步骤结构化 Plan（JSON 对象，复杂任务可超 10 步）+ user_summary
        // 可能超过 4096 token 导致 JSON 截断（MalformedJsonException: Unterminated object at $.plan）。
        // 不限制 plan 长度/步骤数（复杂任务需要），改为显式给足输出空间：16384 覆盖 10-20 步长 plan。
        private const val MAX_TOKENS = 16384
        private const val TEMPERATURE = 0.3
        // 工具循环上限：覆盖 "调 list_apps → 逐个候选 App 调 kb_read → amap/web_search → 追问 → 输出 ready" 路径。
        // 决策侧 kb_read 按 app_filter 一次一 App，多候选 App 场景轮次增加，5 轮易触顶导致硬失败，放宽到 9 轮。
        private const val MAX_TOOL_ROUNDS = 9
        // 工具结果保留轮数：框架固定只保留最近 N 轮（assistant.tool_calls + 其 role=tool 结果成对），
        // 更早的工具结果由框架自动清理，防止决策上下文随轮次线性膨胀（业界做法：Anthropic Context Editing /
        // OpenAI Responses context_management 均为框架确定性清理）。模型通过 workspace_update 把关键信息
        // 写入任务工作区，不依赖历史工具结果。
        private const val KEEP_TOOL_ROUNDS = 1
        // 工作区最大字符数（约 800 token 上限的兜底）：防止模型超长写入导致工作区自身膨胀
        private const val WORKSPACE_MAX_CHARS = 2000

        // 操作任务格式违规纠错提示：模型在操作任务中输出裸文本 need_more_info（无 questions）时
        // 追加此提示重试一次，强制其输出规范 ready JSON 或调用 ask_questions 工具
        private const val FORMAT_CORRECTION_PROMPT = """
你上一轮回复格式不符合规范：当前是【操作任务】，输出"need_more_info"文本且未携带结构化 questions 是不允许的。
请重新输出，必须二选一：
1. 若信息已充足，直接输出 ready JSON：{"status":"ready","intent":"operate","plan":{"requirement":"<需求复述>","goal":"<目标概括>","steps":[{"order":1,"goal":"<步骤目标>","success_criteria":"<完成标志>","supervised":false}]},"user_summary":"<一句话摘要>"}
2. 若仍有硬性未知需澄清，调用 ask_questions 工具（1-4个结构化问题，每问2-6个选项）。
禁止再次输出裸文本 need_more_info。
"""

        // 截断续写纠错提示：finish_reason=length 导致 JSON 未闭合时追加此提示重试，
        // 让模型基于已输出内容续写（不精简 plan，保留复杂任务长步骤完整性）
        private const val TRUNCATED_CONTINUE_PROMPT = """
你的上轮输出因超出长度限制被截断（JSON 未闭合）。请基于上轮已输出的内容继续，
把被截断的 plan.steps 数组补全，输出完整闭合的 JSON 对象；不要重复已输出的内容，
不要缩减步骤（复杂任务可以超过 10 步）。
⚠️ plan 必须是结构化 JSON 对象（含 requirement/goal/steps 字段），禁止输出字符串。
"""

        private const val SYSTEM_PROMPT = """
你是智能设备操作系统的"意图路由与规划层（Router & Planner）"。
你的职责：精准理解用户意图，通过工具获取必要上下文，输出供下游"执行模型"使用的结构化 Plan（JSON 对象）；查询/闲聊类任务直接回复用户。

### 核心红线
1. **禁止越界拒绝**：你是路由，不是执行者。无论任务多复杂、是否涉及敏感操作（人脸/密码/支付），都必须正常生成 Plan。执行模型会负责拦截敏感操作，你绝不能以"无法操作"、"能力有限"或"安全限制"为由拒绝用户。
2. **追问边界**：
   - 信息缺失可推断 → 直接输出 Plan，不追问
   - 意图或执行方式歧义（点外卖/导航过去/自提/在线购买/只是随口说说）→ 必须调用 ask_questions 澄清，禁止自行猜测一种方式直接生成 Plan。记住："想喝奶茶"不等于"导航去奶茶店"。
   - 已提供信息不得重复追问；主观偏好（语气/隐晦度/条数等）用默认值；同一问题最多追问 1 次。
3. **禁止凭空捏造**：严禁凭训练知识猜测设备上的 App 名称、包名或 UI 路径。操作类 Plan 的具体目标（医院名/餐厅名等）必须来自 amap_nearby 返回结果或用户明确指定，绝不使用知识库 SOP 中的示例名称；若无相关指引，使用通用操作常识生成 Plan。

### 任务工作区（scratchpad）与工具结果生命周期
你有一个跨轮持续的任务工作区（system 中"【任务工作区】"块）。规则：
1. 每轮工具调用后，必须调用 workspace_update，把关键结论用你自己的话精简写入工作区（覆盖式）：
   已确认的 App 与包名、kb_read 返回的 SOP 步骤要点（含 UI 变体与异常处理）、
   list_apps / amap 结果中必须记住的目标实体、待办事项。
2. 工具结果（assistant.tool_calls + role=tool）只会保留最近 1 轮，更早的会被框架自动清理。
   生成 Plan 时一律从工作区读取信息，不要依赖已被清理的工具结果。
3. 工作区必须精简（≤800 token）：只写结论与关键事实，不要复制工具原始输出。

### 工具调用与决策工作流（信息收集管道）
每次收到用户请求，严格按以下管道顺序执行，禁止跳步、禁止重复调用同一工具：
1. **意图分类（先于一切工具调用）**：
   - 闲聊/愿望表达（如"我想喝奶茶""今天好累啊"）：禁止调用任何工具、禁止生成 Plan，直接输出 {"status":"need_more_info","message":"<真正回复用户的自然语言文字，可顺带询问是否需要帮忙实现>"}。
   - 查询类（如"附近有什么奶茶店""今天天气如何"）：可调用 amap_*/web_search 获取答案，直接以 need_more_info + message 回答，不进入执行。
   - 操作任务-明确（如"导航到XX奶茶店""帮我发微信给张三"）：进入步骤 2-6 管道。
   - 操作任务-模糊（如"帮我买杯奶茶"但未说外卖/自提/导航）：必须先调用 ask_questions 澄清执行方式，拿到答案后再进入管道，禁止不澄清直接选一种方式执行。
2. **应用环境确认（list_apps，操作任务必调）**：先调 list_apps 确认设备已安装哪些相关 App（支持一次传入多个关键词，避免多次调用；关键词从用户请求中提取，如"点奶茶"→["美团","饿了么"]）。
   ⚠️ **指定 App 未安装的红线（绝对禁止自行替代）**：若用户在请求中**明确指定**了某个 App（如"用饿了么…"、"打开微信…"），而 list_apps 显示该 App 未安装——**禁止擅自改用其他同类型 App 执行**（如"饿了么未安装就改用美团外卖"），也禁止在 Plan 中声称"已确认/用户已同意替代"。此时**必须调用 ask_questions**，如实告知"XX 未安装"，并提供替代选项（如"改用美团外卖/饿了么网页版/换其他方式/放弃任务"）让用户选择；只有用户**泛化表述**（如"帮我点个外卖"未指定 App）时才允许从已安装列表中自行挑选合适的 App。
   - 若未安装，在 Plan 中如实描述"尝试打开XX，若未安装则提示用户"，绝不瞎编包名。
3. **知识库校验（kb_read，仅操作任务）**：基于 list_apps 确认已安装的候选 App，**逐个**调用 kb_read——一次只查一个 App：query 含"用户意图 + App名"，app_filter 传该 App 名。**候选 App 最多查 3 个**（按与意图相关度从高到低，超过 3 个就只查最相关的 3 个，避免轮次超限）。
   ⚠️ **kb_read 三禁止**：禁止跳过 list_apps 直接查库；禁止不带 app_filter 的全量检索；禁止对同一 App 重复调用。知识库只是"怎么做"的操作手册，不是"做什么"的意图证据，用户意图只能来自用户原话、对话历史或 ask_questions 澄清结果。闲聊/愿望表达/查询类不得调用 kb_read。
4. **补充信息（按需调用，非必调）**：
   - 地理/路线/天气：amap_*（仅当请求含"附近/周边/周围/就近"且意图为查询或导航到附近目标时，才用 amap_nearby 获取附近列表；关键词从用户请求提取）。
   - 实时信息（新闻/股价/天气/赛事/近期事件/人物动态/版本号/价格变动）：先调 web_search 再回答，不要凭训练知识回答实时性问题，避免幻觉。
5. **追问（操作任务必须执行一次）**：调用 ask_questions 前自问"还有什么问题没问？"，把所有硬性未知打包到一次调用（1-4问，每问2-6个选项，UI 自动追加"其他"勿生成）。依次自检以下四项，**只决定"问什么"，不决定"问不问"**：
   ① 该信息是否已在对话历史中由用户明确提供？ → 是则不再重复问
   ② 该信息是否为主观偏好（表达风格、隐晦程度、消息条数等可用默认值）？ → 是则并入"确认型问题"的默认项
   ③ 该信息是否可通过 kb_read / list_apps / amap_* 工具补全？ → 是则先调工具再问
   ④ 用户是否真的要求执行？执行方式是否唯一确定？ → 否/不确定则必须问
   - 有硬性未知（商品规格/品种/冷热/甜度/尺寸/颜色/数量/执行方式等）→ 问具体问题；无硬性未知 → 也必须调用一次 ask_questions 用"确认型问题"复述执行方案（含默认选项，用户可一键确认）。
   - 禁止跳过 ask_questions 直接输出 ready；除非用户本轮已明确表示"随便/你定/直接执行"。
6. **直接生成 Plan**：用户已回答/确认 ask_questions 的问题、或按例外跳过追问后，立即生成 Plan，停止调用工具。

### 工具速查表（何时用哪个）
- list_apps：想知道设备装了哪些 App / 该用哪个 App 执行 → 管道第 2 步必调
- kb_read：已安装 App 的操作步骤/SOP → 管道第 3 步，app_filter=该 App 名，一次一 App
- amap_nearby：附近/周边的地点列表（仅请求含"附近"类词）
- amap_search / amap_directions / amap_weather：特定地点/路线/天气
- web_search：实时信息（新闻/股价/赛事/价格变动等）
- ask_questions：需求模糊 / 硬性未知 / 执行方式歧义
- workspace_update：把本轮关键信息写入任务工作区（见上方"任务工作区"章节规则）

### Plan 生成规范
Plan 是传给执行模型消费的分步骤操作指引，输出为结构化 JSON 对象（非字符串），每个字段短小无嵌套引号风险。
- plan.requirement：完整复述用户原始需求（对象、地点、时间、数量、内容等关键要素逐条列出，不得遗漏、不得凭训练知识编造）
- plan.goal：一句话概括任务目标
- plan.steps：步骤数组，每个步骤对象含：
  - order：步骤序号（从1开始递增）
  - goal：步骤目标（一句话，≤15字，只写动作）
  - success_criteria：完成标志（该步完成时应看到的界面状态/元素变体/异常处理，执行模型据此判定本步完成）
  - supervised：是否需用户监督执行（默认false；仅资金支付/转账/删除/权限变更等不可逆操作设为true；用户明确要求的常规操作如发消息不设）
  - tool_hint：可选，建议执行模型优先使用的快捷工具（仅当该步骤可用下方"执行模型快捷工具"一步完成时填写，否则省略），格式见下方说明
- 如有 kb_read 结果，把知识库每步的"预期"字段对应写入 steps[].success_criteria；UI 元素变体名称（如菜单可能叫"就医服务"也可能叫"服务平台"）、异常处理策略（弹窗处理、备选路径）一并提炼写入 success_criteria
- ⚠️ **终止边界（必须遵守）**：Plan 的步骤范围严格以 kb_read 返回的 SOP 步骤为准——知识库 SOP 覆盖到哪里，Plan 就写到哪里，**禁止超出知识库覆盖范围自行续写后续操作步骤**。例如知识库 SOP 只到"将商品加入购物车"，Plan 不得自行续写"进入购物车结算→确认订单→支付"等知识库未覆盖的步骤。在知识库覆盖的最后一步之后，必须追加一个终止步骤：
  order: N+1, goal: "任务完成，终止行动", success_criteria: "执行模型输出FINISH，向用户报告已完成的范围（如已将商品加入购物车，后续结算/支付请手动确认），不再继续操作", supervised: false
  若 kb_read 未返回匹配 SOP，基于通用操作常识生成 Plan，最后一步同样必须追加上述终止步骤

### 执行模型快捷工具（tool_hint 说明）
执行模型内置两个可"一步完成多操作"的快捷工具，但因上下文注意力有限，**只有写进 Plan 的 tool_hint 才会被可靠触发**。规划时识别到以下场景，必须在对应步骤的 tool_hint 中标注：
- **auto_input**：一步完成"定位输入框 → 输入文本 → 自动点击搜索/确认按钮"。适用：任何"输入关键词/地址/内容后触发搜索或确认"的步骤（如在 App 内搜索商品/医院/联系人、在搜索框输入地点后确认）。
  tool_hint 写法："auto_input: <输入文本>；<按钮特征>"，如 "auto_input: 医院服务号；搜索按钮"。
- **select_spec**：自动遍历规格表单（份量/辣度/尺寸/颜色/口味等）逐项选取并点击确认。适用：外卖/购物/预约等需选择多个规格后确认的步骤。
  tool_hint 写法："select_spec"（具体规格内容由执行模型从屏幕读取，不在 tool_hint 中列举）。
⚠️ tool_hint 只标注"动作类型 + 关键参数"，具体界面元素仍由执行模型从屏幕识别；纯点击/滚动/导航等不适用快捷工具的步骤一律不填 tool_hint。

### 输出紧凑度要求（防截断，步骤数不限）
**步骤数不设上限**（复杂任务可超过 10 步），但每步必须紧凑：
- steps[].goal：不超过 15 字，只写动作，不写原因/背景
- steps[].success_criteria：只保留执行模型判断所需的最小信息（界面状态/元素变体/异常处理），禁止复述步骤目标、禁止客套语
- 禁止输出 JSON 之外的任何解释文字

Plan 示例（预约挂号）：
{"status":"ready","intent":"operate","plan":{"requirement":"用户需要为本人预约东莞市人民医院呼吸内科的挂号","goal":"通过微信服务号预约东莞市人民医院呼吸内科","steps":[{"order":1,"goal":"打开微信","success_criteria":"进入微信主页，底部有聊天/通讯录/发现/我四个Tab；如果微信未安装，提示用户","supervised":false},{"order":2,"goal":"搜索并进入医院服务号","success_criteria":"进入服务号主页，底部有菜单栏，常见叫法有就医服务/服务平台/智慧医院/诊疗服务；搜索无结果则提示用户确认医院名称","supervised":false,"tool_hint":"auto_input: 医院服务号；搜索按钮"}]},"user_summary":"通过微信服务号预约东莞市人民医院呼吸内科"}

### user_summary 规范
- 面向用户的一句话摘要，不超过 30 字，含目标 App + 核心操作（如"通过微信预约挂号"、"在淘宝搜索商品"）
- 不要包含步骤编号、技术细节、完成标志等内部信息

### 输出格式（严格 JSON / 工具调用）
- 操作任务-明确：**必须先调用一次 ask_questions**（有未知问具体问题，无未知用确认型问题），收到用户回答/确认后再输出 ready JSON：
  {"status":"ready","intent":"operate","plan":{"requirement":"<需求>","goal":"<目标>","steps":[{"order":1,"goal":"<步骤目标>","success_criteria":"<完成标志>","supervised":false}]},"user_summary":"<一句话摘要>"}
- 闲聊/愿望表达/查询类（不进入执行）：输出 JSON
  {"status":"need_more_info", "message":"<真正回复用户的自然语言文字>"}
- 操作任务-模糊 或 执行方式歧义：必须调用 ask_questions 工具（禁止输出 questions 文本字段）
⚠️ **操作任务红线**：操作任务禁止输出裸文本 need_more_info（无 questions 的 message 追问）。操作任务的出口只有两个：ready JSON 或 ask_questions 工具调用——**转发 ready 前必须至少调用一次 ask_questions**；仅当用户本轮已明确说"随便/你定/直接执行"时才允许直接 ready。

最后一行（必须遵守）：输出必须是严格的 JSON 对象或一次工具调用；禁止用 markdown 代码块包裹 JSON，禁止输出 JSON 之外的任何解释文字。
"""
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mcpService = WebMCPService()

    /**
     * 与决策模型进行一轮对话。
     *
     * @param userMessage 当前用户消息
     * @param history 之前的对话历史（按时间顺序，isUser=true 为用户消息，
     *                isUser=false 为决策模型回复）
     * @return 对话结果（NeedMoreInfo / Ready / Error）
     */
    suspend fun chat(
        userMessage: String,
        history: List<ChatMessage>
    ): DialogResult = withContext(Dispatchers.IO) {
        LiveLogBuffer.append("🤖 决策模型收到请求: ${userMessage.take(60)}")
        val apiKey = KVUtils.getPlannerApiKey()
        if (apiKey.isEmpty()) {
            LiveLogBuffer.append("❌ 决策模型 API Key 未配置")
            return@withContext DialogResult.Error(
                "决策模型 API Key 未配置，请在设置 → 任务决策模型中配置"
            )
        }

        val apiUrl = normalizeApiUrl(KVUtils.getPlannerApiUrl())
        if (apiUrl.isEmpty()) {
            LiveLogBuffer.append("❌ 决策模型 API 地址未配置")
            return@withContext DialogResult.Error("决策模型 API 地址未配置")
        }

        val model = KVUtils.getPlannerModel()
        Log.d(TAG, "决策请求: model=$model, url=$apiUrl, user=${userMessage.take(80)}")

        // 构建 messages: system + history + user(当前消息)
        val messages = mutableListOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT)
        )
        for (msg in history) {
            val role = if (msg.isUser) "user" else "assistant"
            messages.add(mapOf("role" to role, "content" to msg.content))
        }
        messages.add(mapOf("role" to "user", "content" to userMessage))

        // 插入任务工作区占位（index=1，紧跟主 system）：每轮由 callDecisionWithTools 刷新内容，
        // 模型通过 workspace_update 写入关键信息。content 必须非空（部分 OpenAI 兼容 API 拒绝空 system）
        messages.add(
            1,
            mapOf(
                "role" to "system",
                "content" to "【任务工作区（scratchpad）】\n（暂无内容，等待工具调用后写入）"
            )
        )

        // 统一走 function calling：模型自主决定是否调用高德工具
        // 透传 userMessage 作为降级兜底的原始用户请求（避免被纠错提示覆盖）
        return@withContext callDecisionWithTools(apiUrl, apiKey, model, messages, userMessage)
    }

    /**
     * 对话模型调用：启用 function calling 循环，模型可主动调 list_apps / kb_read / amap_*
     * 工具，工具结果以 role=tool 回传模型继续推理。最长 MAX_TOOL_ROUNDS 轮。
     */
    private suspend fun callDecisionWithTools(
        apiUrl: String,
        apiKey: String,
        model: String,
        messages: MutableList<Map<String, Any>>,
        originalUserMessage: String
    ): DialogResult {
        var round = 0
        // 是否已调用过操作类工具（kb_read/list_apps）：用于判定当前为操作任务（查询/闲聊不调这些工具）
        var usedOperationalTool = false
        // 是否已对"操作任务返回无问题 need_more_info"做过一次纠错重试
        var formatRetried = false
        // 是否已对"输出被截断（finish_reason=length）"做过一次续写重试
        var truncateRetried = false
        // 任务工作区（scratchpad）：单任务内状态，由 workspace_update 工具覆盖式更新。
        // 注意：DecisionDialogService 是长生命周期实例，故不可用类字段承载（多任务会互相污染）
        var workspace = ""
        while (round < MAX_TOOL_ROUNDS) {
            round++

            // 框架自动清理更早的工具结果轮次，只保留最近 KEEP_TOOL_ROUNDS 轮
            // （assistant.tool_calls 与其 role=tool 结果成对保留），控制决策上下文有界
            trimToolMessages(messages, keepLastRounds = KEEP_TOOL_ROUNDS)
            Log.d(TAG, "决策上下文: workspace=${workspace.length}字符, 保留最近${KEEP_TOOL_ROUNDS}轮工具结果, messages=${messages.size}条")

            // 刷新任务工作区（index=1 占位 system 消息）：模型只依赖工作区信息生成 Plan；
            // take 截断兜底，防止模型超长写入导致工作区自身膨胀
            messages[1] = mapOf(
                "role" to "system",
                "content" to "【任务工作区（scratchpad）】\n${workspace.take(WORKSPACE_MAX_CHARS)}\n（工具结果可能被框架清理，请只依赖工作区中的信息生成 Plan）"
            )

            // 工具选择策略：始终 auto，让 LLM 按触发式 prompt 自主决定
            // kb_read 工具仅在 KB 启用时由 buildToolsJson 注入到 tools 列表
            val toolChoiceJson = "\"auto\""

            LiveLogBuffer.append("🔁 决策模型推理（第${round}轮/${MAX_TOOL_ROUNDS}）")
            Log.d(TAG, "决策推理 第${round}轮/${MAX_TOOL_ROUNDS}")
            val (content, toolCalls, truncated) = callApiWithTools(apiUrl, apiKey, model, messages, toolChoiceJson)
                ?: run {
                    LiveLogBuffer.append("❌ 决策模型调用失败")
                    return DialogResult.Error("决策模型调用失败，请查看日志")
                }

            if (toolCalls.isEmpty()) {
                // 模型直接给出最终回复（ready / need_more_info / 普通回答）
                if (content.isBlank()) return DialogResult.Error("决策模型返回空内容")
                val result = parseDialogResult(content)
                // 截断续写：三路截断信号或 JSON 解析失败（Malformed/Unterminated）任一命中 →
                // 追加续写提示重试一次，让模型基于已输出内容补全（不精简 plan，保留长步骤完整性）
                val truncationError = result is DialogResult.Error &&
                    (result.message.contains("Malformed") || result.message.contains("Unterminated"))
                if ((truncated || truncationError) && !truncateRetried) {
                    LiveLogBuffer.append("⚠️ [决策] 输出疑似截断，追加续写重试")
                    messages.add(mapOf("role" to "assistant", "content" to content))
                    messages.add(mapOf("role" to "user", "content" to TRUNCATED_CONTINUE_PROMPT))
                    truncateRetried = true
                    continue
                }
                // 格式违规拦截：操作任务返回"无结构化问题"的裸文本 need_more_info
                // （模型违反输出契约，把复述需求的长文本当追问）不允许直接上屏——
                // 追加纠错提示重试一次，让模型输出规范 ready JSON 或调用 ask_questions
                if (usedOperationalTool && !formatRetried &&
                    result is DialogResult.NeedMoreInfo && result.questions == null
                ) {
                    LiveLogBuffer.append("⚠️ [决策] 操作任务返回无问题追问（格式违规），追加纠错重试")
                    messages.add(mapOf("role" to "assistant", "content" to content))
                    messages.add(mapOf("role" to "user", "content" to FORMAT_CORRECTION_PROMPT))
                    formatRetried = true
                    continue
                }
                // 纠错重试后仍返回无问题 need_more_info：降级为 ready，直接用用户原始请求执行，
                // 绝不把模型长文本发给用户（userSummary 留空，UI 只显示简短确认语）
                // ⚠️ 必须使用 originalUserMessage 而非 messages 反查：此时 messages 最后一个 role=user
                // 是 FORMAT_CORRECTION_PROMPT，反查会拿到纠错提示全文而非用户真实请求
                if (usedOperationalTool && result is DialogResult.NeedMoreInfo && result.questions == null) {
                    LiveLogBuffer.append("⚠️ [决策] 纠错重试后仍返回无问题追问，降级为按用户请求直接执行")
                    val userReq = originalUserMessage.takeIf { it.isNotBlank() } ?: "执行用户请求"
                    return DialogResult.Ready(
                        plan = Plan(
                            requirement = userReq,
                            goal = userReq.take(40),
                            steps = listOf(PlanStep(order = 1, goal = "执行用户请求", successCriteria = "任务完成", supervised = false))
                        ),
                        userSummary = ""
                    )
                }
                LiveLogBuffer.append("🤖 决策模型回复: ${resultSummary(result)}")
                Log.d(TAG, "决策回复: ${resultSummary(result)}")
                return result
            }

            // 记录本轮工具调用链（决策模型请求调用的工具及参数）
            toolCalls.forEach { tc ->
                val name = tc["name"] as? String ?: "?"
                val args = tc["arguments"] as? Map<String, Any> ?: emptyMap()
                LiveLogBuffer.append("🔧 [决策] 调用工具: $name ${args.toList().take(3).toMap()}")
                Log.d(TAG, "决策工具调用: $name ${args.toList().take(3).toMap()}")
            }

            // 检测 ask_questions 工具调用（追问信号）：拦截后追加自检对话，确认完整后再展示
            val askQuestionsCall = toolCalls.firstOrNull { it["name"] == "ask_questions" }
            if (askQuestionsCall != null) {
                val args = askQuestionsCall["arguments"] as? Map<String, Any> ?: emptyMap()
                val questions = parseQuestionsFromToolArgs(args)
                if (questions.isEmpty()) {
                    LiveLogBuffer.append("❌ [决策] ask_questions 工具参数解析失败")
                    return DialogResult.Error("ask_questions 工具参数解析失败，模型未按规范输出 questions 数组")
                }
                LiveLogBuffer.append("❓ [决策] 模型追问 ${questions.size} 个问题，进行自检")
                Log.d(TAG, "决策模型调用 ask_questions 工具：${questions.size} 个问题，即将自检是否有遗漏")

                // 把 assistant 的 tool_calls 消息加入历史
                messages.add(
                    mapOf(
                        "role" to "assistant",
                        "content" to content,
                        "tool_calls" to listOf(
                            mapOf(
                                "id" to (askQuestionsCall["id"] ?: "call_ask"),
                                "type" to "function",
                                "function" to mapOf(
                                    "name" to "ask_questions",
                                    "arguments" to gson.toJson(args)
                                )
                            )
                        )
                    )
                )
                // 追加 tool 结果（空结果，表示"已拦截，待自检"）
                messages.add(
                    mapOf(
                        "role" to "tool",
                        "tool_call_id" to (askQuestionsCall["id"] ?: "call_ask"),
                        "content" to "问题已拦截，等待自检确认。"
                    )
                )
                // 追加自检追问
                messages.add(
                    mapOf(
                        "role" to "user",
                        "content" to "在向用户展示这些问题之前，请先自检：还有什么问题需要确认吗？" +
                            "如果有遗漏，请调用 ask_questions 工具把所有问题（含之前的）汇总到一次调用中；" +
                            "如果确认没有遗漏，请输出 {\"status\":\"ready\"}。"
                    )
                )

                // 再调一次模型，让模型自检
                val (_, selfCheckToolCalls, _) = callApiWithTools(
                    apiUrl, apiKey, model, messages, "\"auto\""
                ) ?: return DialogResult.NeedMoreInfo(
                    message = "请回答以下问题",
                    questions = questions  // 自检失败，降级使用原始问题
                )

                // 自检结果：模型可能再次调用 ask_questions（补充/合并问题）
                val selfCheckAskCall = selfCheckToolCalls.firstOrNull { it["name"] == "ask_questions" }
                if (selfCheckAskCall != null) {
                    val selfCheckArgs = selfCheckAskCall["arguments"] as? Map<String, Any> ?: emptyMap()
                    val mergedQuestions = parseQuestionsFromToolArgs(selfCheckArgs)
                    if (mergedQuestions.isNotEmpty()) {
                        Log.d(TAG, "自检后模型补充问题，合并后共 ${mergedQuestions.size} 个问题")
                        return DialogResult.NeedMoreInfo(
                            message = "请回答以下问题",
                            questions = mergedQuestions
                        )
                    }
                }

                // 模型输出 ready（确认问题完整）或其它情况：使用原始问题
                Log.d(TAG, "自检完成，模型确认问题完整，共 ${questions.size} 个问题")
                return DialogResult.NeedMoreInfo(
                    message = "请回答以下问题",
                    questions = questions
                )
            }

            // 工具循环：把 assistant 的 tool_calls 消息加入历史
            messages.add(
                mapOf(
                    "role" to "assistant",
                    "content" to content,
                    "tool_calls" to toolCalls.map { tc ->
                        mapOf(
                            "id" to tc["id"]!!,
                            "type" to "function",
                            "function" to mapOf(
                                "name" to tc["name"]!!,
                                "arguments" to gson.toJson(tc["arguments"])
                            )
                        )
                    }
                )
            )

            // 逐个执行工具，结果以 role=tool 追加到 messages
            for (tc in toolCalls) {
                val id = tc["id"] as? String ?: continue
                val name = tc["name"] as? String ?: continue
                // 操作类工具调用过 → 判定为操作任务（查询类/闲聊不调 kb_read/list_apps）
                if (name == "kb_read" || name == "list_apps") usedOperationalTool = true
                val args = (tc["arguments"] as? Map<String, Any>) ?: emptyMap()

                // workspace_update：工作区写入（Memory 模式）。workspace 是 callDecisionWithTools 局部状态，
                // 无法经 executeAnyTool 访问，故在此单独处理；工具结果只写简短确认，不占上下文。
                if (name == "workspace_update") {
                    workspace = args["content"]?.toString() ?: ""
                    LiveLogBuffer.append("📝 [决策] 工作区更新: ${workspace.take(60)}...")
                    messages.add(
                        mapOf(
                            "role" to "tool",
                            "tool_call_id" to id,
                            "content" to "工作区已更新（${workspace.length} 字符）"
                        )
                    )
                    continue
                }

                val result = executeAnyTool(name, args)
                LiveLogBuffer.append("📦 [决策] 工具结果: $name → ${result.take(80)}")
                messages.add(
                    mapOf(
                        "role" to "tool",
                        "tool_call_id" to id,
                        "content" to result
                    )
                )
            }
            // 继续循环：模型基于工具结果继续推理
        }
        LiveLogBuffer.append("❌ 决策模型工具循环达到上限（$MAX_TOOL_ROUNDS 轮）")
        return DialogResult.Error("决策模型工具循环达到上限（$MAX_TOOL_ROUNDS 轮）仍未给出最终回复")
    }

    /**
     * 按轮清理工具消息：固定保留最近 keepLastRounds 轮，删除更早的。
     *
     * 每"轮"指一条 assistant.tool_calls 消息及其后连续的所有 role=tool 结果消息，
     * 必须成对保留/删除以满足 OpenAI tool_calls/tool 严格配对校验（缺失配对会 400）。
     * 截断/纠错/自检追加的 user 提示等非工具消息不受影响。
     */
    private fun trimToolMessages(messages: MutableList<Map<String, Any>>, keepLastRounds: Int) {
        if (keepLastRounds <= 0) return
        // 1) 定位每轮 [assistantIdx, toolEndIdx) 区间
        val rounds = mutableListOf<IntRange>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m["role"] == "assistant" && m["tool_calls"] != null) {
                var j = i + 1
                while (j < messages.size && messages[j]["role"] == "tool") j++
                rounds.add(i until j)
                i = j
            } else {
                i++
            }
        }
        // 2) 删除最早超出保留量的轮（从后往前删，避免下标错乱）
        val toDelete = rounds.take(maxOf(0, rounds.size - keepLastRounds))
        toDelete.reversed().forEach { range ->
            repeat(range.count()) { messages.removeAt(range.first) }
        }
        if (toDelete.isNotEmpty()) {
            LiveLogBuffer.append("🧹 [决策] 框架清理工具结果到最近${keepLastRounds}轮")
        }
    }

    /** 决策模型回复摘要（供日志界面展示） */
    private fun resultSummary(result: DialogResult): String {
        return when (result) {
            is DialogResult.Ready -> "已生成计划（${result.plan.steps.size}步）"
            is DialogResult.NeedMoreInfo -> "需要追问: ${result.message.take(40)}（${result.questions?.size ?: 0}个问题）"
            is DialogResult.Error -> "出错: ${result.message.take(40)}"
        }
    }

    /**
     * 按工具名分派到具体执行器（list_apps / kb_read / amap_* / web_search）
     */
    private suspend fun executeAnyTool(name: String, args: Map<String, Any>): String {
        return when {
            name == "list_apps" -> executeListAppsTool(args)
            name == "kb_read" -> executeKbTool(name, args)
            name.startsWith("amap_") -> executeAmapTool(name, args)
            name == "web_search" -> executeWebSearchTool(args)
            else -> "未知工具：$name（仅支持 list_apps / kb_read / amap_* / web_search）"
        }
    }

    /** 执行 web_search 工具调用，复用 WebSearchService 后端 */
    private suspend fun executeWebSearchTool(args: Map<String, Any>): String {
        return try {
            val query = args["query"]?.toString() ?: ""
            if (query.isBlank()) return "错误：query 参数不能为空"
            val count = (args["count"] as? Number)?.toInt() ?: 5
            val result = WebSearchService.search(query, count)
            if (result.success) result.content else "搜索失败：${result.error}"
        } catch (e: Exception) {
            Log.e(TAG, "执行 web_search 工具异常", e)
            "web_search 工具执行异常：${e.message}"
        }
    }

    /** 执行 list_apps 工具调用 */
    private suspend fun executeListAppsTool(args: Map<String, Any>): String {
        return try {
            val result = com.palmagent.app.tool.ToolRegistry.executeTool("list_apps", args)
            if (result.isSuccess) result.data ?: "" else "list_apps 执行失败：${result.error}"
        } catch (e: Exception) {
            Log.e(TAG, "执行 list_apps 工具异常", e)
            "list_apps 工具执行异常：${e.message}"
        }
    }

    /**
     * 调用 API 并返回 (content, toolCalls)。
     * - 请求体含 tools 字段（OpenAI function calling 格式）
     * - toolChoiceJson 控制工具选择策略：auto 让模型按触发式 prompt 自主决定
     * - 解析响应 message.content 和 message.tool_calls
     */
    private fun callApiWithTools(
        apiUrl: String,
        apiKey: String,
        model: String,
        messages: List<Map<String, Any>>,
        toolChoiceJson: String = "\"auto\""
    ): Triple<String, List<Map<String, Any>>, Boolean>? {
        return try {
            // 先尝试启用 JSON 模式（response_format: json_object，由 API 层保证输出合法 JSON，
            // 避免模型输出裸文本/代码块导致解析失败）；若 API 不支持则回退为普通请求重试
            var requestBody = buildDecisionRequestBody(model, messages, toolChoiceJson, useJsonFormat = true)
            var (responseCode, body) = executeDecisionRequest(apiUrl, apiKey, requestBody)
            if (responseCode !in 200..299) {
                val firstError = parseErrorMessage(body, responseCode)
                Log.w(TAG, "决策对话 JSON 模式请求失败: HTTP $responseCode, $firstError，回退为普通请求重试")
                requestBody = buildDecisionRequestBody(model, messages, toolChoiceJson, useJsonFormat = false)
                val retry = executeDecisionRequest(apiUrl, apiKey, requestBody)
                responseCode = retry.first
                body = retry.second
            }

            if (responseCode !in 200..299) {
                val errorMsg = parseErrorMessage(body, responseCode)
                Log.e(TAG, "决策对话 API 错误: HTTP $responseCode, $errorMsg")
                return null
            }

            val responseJson = gson.fromJson(body, JsonObject::class.java)
            val choices = responseJson.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return null

            val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
            val content = messageObj.get("content")?.asString ?: ""
            // 截断检测（三路信号，不依赖单一 finish_reason）：
            // ① finish_reason="length"（API 报告的截断）
            // ② usage.completion_tokens 达到 max_tokens（部分网关不报 length，但 usage 仍会顶格）
            // ③ content 疑似截断（以 { 开头但 JSON 括号不平衡/字符串未闭合——JSON 模式下的早断兜底）
            val finishReason = choices[0].asJsonObject.get("finish_reason")?.asString ?: ""
            // 防御性读取 usage：字段缺失/非对象时按 0 处理，避免异常导致整次调用失败
            val usageObj = responseJson.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            val completionTokens = usageObj?.get("completion_tokens")?.asInt ?: 0
            val truncated = finishReason == "length" ||
                (completionTokens > 0 && completionTokens >= MAX_TOKENS) ||
                isLikelyTruncated(content)
            if (truncated) {
                Log.w(TAG, "决策对话输出疑似截断（finish_reason='$finishReason', completion_tokens=$completionTokens/${MAX_TOKENS}, content 长度=${content.length}）")
            }

            // 解析 tool_calls（OpenAI 兼容格式）
            val toolCalls = mutableListOf<Map<String, Any>>()
            val toolCallsElem = messageObj.get("tool_calls")
            if (toolCallsElem != null && !toolCallsElem.isJsonNull) {
                val toolCallsArr = toolCallsElem.asJsonArray
                for (i in 0 until toolCallsArr.size()) {
                    val tc = toolCallsArr[i].asJsonObject
                    val id = tc.get("id")?.asString ?: "call_$i"
                    val function = tc.getAsJsonObject("function")
                    val name = function.get("name")?.asString ?: ""
                    val argsStr = function.get("arguments")?.asString ?: "{}"
                    val args = try {
                        @Suppress("UNCHECKED_CAST")
                        (gson.fromJson(argsStr, Map::class.java) as? Map<String, Any>)
                    } catch (_: Exception) {
                        null
                    }
                    toolCalls.add(buildMap {
                        put("id", id)
                        put("name", name)
                        put("arguments", args ?: emptyMap<String, Any>())
                    })
                }
            }

            Triple(content, toolCalls, truncated)
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "决策对话(带工具)请求超时", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "决策对话(带工具)调用异常", e)
            null
        }
    }

    /**
     * 构建决策模型请求体。useJsonFormat=true 时启用 response_format=json_object，
     * 由 API 层强制模型输出合法 JSON，避免裸文本/代码块包裹导致解析失败
     */
    private fun buildDecisionRequestBody(
        model: String,
        messages: List<Map<String, Any>>,
        toolChoiceJson: String,
        useJsonFormat: Boolean
    ): String = buildString {
        append("{")
        append("\"model\":\"$model\",")
        append("\"messages\":${gson.toJson(messages)},")
        // 显式设置足够的输出上限（16384），避免长 plan（复杂任务可超 10 步）被截断；
        // 不传该字段时 API 使用默认上限（约 4096）仍会截断，必须显式给足
        append("\"max_tokens\":$MAX_TOKENS,")
        append("\"temperature\":$TEMPERATURE,")
        if (useJsonFormat) {
            // API 层结构化输出约束（OpenAI 兼容格式，与 function calling 可共存）
            append("\"response_format\":{\"type\":\"json_object\"},")
        }
        // 注入 tools 字段，让模型能主动调 list_apps / kb_read / amap_* / web_search
        // enable_search 已删除 — 联网搜索由 web_search 工具提供（与执行模型统一）
        append("\"tools\":${buildToolsJson()},")
        append("\"tool_choice\":$toolChoiceJson")
        append("}")
    }

    /**
     * 疑似截断检测（不依赖 finish_reason）：content 以 { 开头但 JSON 结构未闭合
     * （括号不平衡或字符串未终结）——JSON 模式下响应早断时的兜底信号。
     */
    private fun isLikelyTruncated(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (c in trimmed) {
            when {
                inString -> {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                }
                c == '"' -> inString = true
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> depth--
            }
        }
        return inString || depth != 0
    }

    /** 执行决策模型 HTTP 请求，返回 (响应码, 响应体) */
    private fun executeDecisionRequest(
        apiUrl: String,
        apiKey: String,
        requestBody: String
    ): Pair<Int, String> {
        Log.d(TAG, "调用决策对话模型(带工具): url=$apiUrl, body 长度=${requestBody.length}")
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()
        return client.newCall(request).execute().use { resp ->
            Pair(resp.code, resp.body?.string() ?: "")
        }
    }

    /**
     * 执行高德工具调用，返回结果字符串（成功为结果内容，失败为错误说明）
     */
    private suspend fun executeAmapTool(name: String, args: Map<String, Any>): String {
        return try {
            when (name) {
                "amap_nearby" -> {
                    val keywords = args["keywords"]?.toString() ?: ""
                    val radius = (args["radius"] as? Number)?.toInt() ?: 1000
                    if (keywords.isBlank()) return "错误：keywords 参数不能为空"
                    val result = mcpService.amapNearby(keywords, radius)
                    if (result.success) result.content else "搜索失败：${result.error}"
                }
                "amap_search" -> {
                    val keywords = args["keywords"]?.toString() ?: ""
                    val city = args["city"]?.toString() ?: ""
                    if (keywords.isBlank()) return "错误：keywords 参数不能为空"
                    val result = mcpService.amapSearch(keywords, city)
                    if (result.success) result.content else "搜索失败：${result.error}"
                }
                "amap_weather" -> {
                    val result = mcpService.amapWeather()
                    if (result.success) result.content else "天气查询失败：${result.error}"
                }
                "amap_directions" -> {
                    val destination = args["destination"]?.toString() ?: ""
                    val mode = args["mode"]?.toString() ?: "drive"
                    if (destination.isBlank()) return "错误：destination 参数不能为空"
                    val result = mcpService.amapDirections(destination, mode)
                    if (result.success) result.content else "路线规划失败：${result.error}"
                }
                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行工具 $name 异常", e)
            "工具执行异常：${e.message}"
        }
    }

    /**
     * 执行知识库工具调用，返回结果字符串（成功为检索内容，失败为错误说明）
     */
    private suspend fun executeKbTool(name: String, args: Map<String, Any>): String {
        return try {
            when (name) {
                "kb_read" -> {
                    val query = args["query"]?.toString() ?: ""
                    val topK = (args["top_k"] as? Number)?.toInt() ?: 3
                    val appFilter = args["app_filter"]?.toString()
                    // 三禁止之一：禁止无 app_filter 的全量检索（schema 已要求必填，这里兜底强制）
                    if (appFilter.isNullOrBlank()) {
                        return "错误：kb_read 必须传 app_filter（指定单个App名，一次只查一个App）。请先调用 list_apps 确认已安装的候选App，再对该App单独查询。"
                    }
                    val params = mutableMapOf<String, Any>("query" to query, "top_k" to topK)
                    params["app_filter"] = appFilter
                    val result = KbReadTool().execute(params)
                    if (result.isSuccess) result.data ?: "" else "知识库查询失败：${result.error}"
                }
                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行知识库工具 $name 异常", e)
            "工具执行异常：${e.message}"
        }
    }

    /** 构建 OpenAI function calling 的 tools JSON 字符串 */
    private fun buildAmapToolsJson(): String {
        val tools = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "amap_nearby",
                    "description" to "搜索当前位置周边的地点。用于回答'附近医院'、'周边餐厅'等问题。会自动使用设备当前位置。",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "keywords" to mapOf(
                                "type" to "string",
                                "description" to "搜索关键词，如'医院'、'餐厅'、'药店'、'ATM'"
                            ),
                            "radius" to mapOf(
                                "type" to "integer",
                                "description" to "搜索半径(米)，默认1000，最大5000",
                                "default" to 1000
                            )
                        ),
                        "required" to listOf("keywords")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "amap_search",
                    "description" to "按关键词搜索地点(可指定城市)。用于查找特定地点如'协和医院'。",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "keywords" to mapOf("type" to "string", "description" to "搜索关键词"),
                            "city" to mapOf("type" to "string", "description" to "城市名(可选)")
                        ),
                        "required" to listOf("keywords")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "amap_weather",
                    "description" to "查询当前位置的天气情况。用于回答'今天天气怎么样'等问题。",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to emptyMap<String, Any>(),
                        "required" to emptyList<String>()
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "amap_directions",
                    "description" to "规划从当前位置到目的地的路线。用于回答'怎么去XX'、'到XX多远'等问题。",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "destination" to mapOf("type" to "string", "description" to "目的地名称或地址"),
                            "mode" to mapOf(
                                "type" to "string",
                                "description" to "出行方式：drive(驾车)/walk(步行)/transit(公交)",
                                "enum" to listOf("drive", "walk", "transit"),
                                "default" to "drive"
                            )
                        ),
                        "required" to listOf("destination")
                    )
                )
            )
        )
        return gson.toJson(tools)
    }

    /**
     * 组装传给模型的 tools JSON 数组：始终包含 amap 工具和 list_apps 工具，
     * 当本地知识库启用时追加 kb_read 工具，当执行模型联网搜索启用时追加 web_search 工具。
     * LOCAL_KB_ENABLED=false 时模型完全无感知 kb_read。
     * EXECUTION_ENABLE_SEARCH=false 时模型完全无感知 web_search。
     */
    private fun buildToolsJson(): String {
        val amapArray = buildAmapToolsJson()
        val listAppsTool = buildListAppsToolJson()
        // amapArray 形如 "[{...},{...}]"，去掉首尾方括号取内部内容
        val amapInner = amapArray.removeSurrounding("[", "]")
        return buildString {
            append("[")
            append(amapInner)
            // list_apps 工具（始终可用，对话层查设备已装应用）
            append(",")
            append(listAppsTool)
            // ask_questions 工具（始终注入，用于结构化批量追问）
            append(",")
            append(buildAskQuestionsToolJson())
            // workspace_update 工具（始终注入）：模型把关键信息写入任务工作区（Memory 模式）
            append(",")
            append(buildWorkspaceToolJson())
            if (KVUtils.isLocalKbEnabled()) {
                append(",")
                append(buildKbToolsJson())
            }
            // 追加 web_search 工具（与执行模型共用开关与 WebSearchService 后端）
            if (KVUtils.isExecutionSearchEnabled()) {
                append(",")
                append(buildWebSearchToolJson())
            }
            append("]")
        }
    }

    /** 构建 workspace_update 工具的 OpenAI function calling schema（任务工作区写入，Memory 模式） */
    private fun buildWorkspaceToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "workspace_update",
                "description" to "把本轮获取的关键信息（已确认App+包名、SOP步骤要点、用户确认项、待办）用你自己的话精简写入任务工作区（覆盖式更新）。" +
                    "工具结果会被框架自动清理，只有写进工作区的信息才会保留，生成Plan时从工作区读取。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "content" to mapOf(
                            "type" to "string",
                            "description" to "工作区新内容（覆盖旧内容，精简，不超过800字）"
                        )
                    ),
                    "required" to listOf("content")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建 ask_questions 工具的 OpenAI function calling schema（参考 GitHub Copilot） */
    private fun buildAskQuestionsToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "ask_questions",
                "description" to "向用户批量提问以澄清必要信息。一次性收集所有硬性未知，禁止分多轮追问。" +
                    "使用场景：澄清模糊需求/获取用户偏好/确认影响结果的决策。" +
                    "禁用场景：答案可从对话历史/工具结果推断/主观偏好可用默认值/仅确认你可自行决定的事。" +
                    "规则：① Batch related questions into a single call（1-4问）② 每问2-6选项 ③ UI自动追加'其他'勿生成 ④ 收到答案后继续不得重复追问",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "questions" to mapOf(
                            "type" to "array",
                            "minItems" to 1,
                            "maxItems" to 4,
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "question" to mapOf("type" to "string", "description" to "问题文本(≤30字)"),
                                    "header" to mapOf("type" to "string", "maxLength" to 12, "description" to "短标签(≤12字)"),
                                    "multiSelect" to mapOf("type" to "boolean", "default" to false),
                                    "allowFreeInput" to mapOf("type" to "boolean", "default" to true),
                                    "options" to mapOf(
                                        "type" to "array",
                                        "minItems" to 2,
                                        "maxItems" to 6,
                                        "items" to mapOf(
                                            "type" to "object",
                                            "properties" to mapOf(
                                                "label" to mapOf("type" to "string"),
                                                "description" to mapOf("type" to "string"),
                                                "recommended" to mapOf("type" to "boolean")
                                            ),
                                            "required" to listOf("label")
                                        )
                                    )
                                ),
                                "required" to listOf("question", "header", "options")
                            )
                        )
                    ),
                    "required" to listOf("questions")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建 web_search 工具的 OpenAI function calling schema */
    private fun buildWebSearchToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "web_search",
                "description" to "联网搜索互联网最新信息、新闻、实时数据、价格、天气等。当用户问题涉及实时信息、近期事件、价格变动、人物动态时必须调用此工具。不要凭训练知识回答实时性问题，必须先搜后答。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "搜索关键词"),
                        "count" to mapOf("type" to "integer", "description" to "返回结果数，默认5", "default" to 5)
                    ),
                    "required" to listOf("query")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建 list_apps 工具的 OpenAI function calling schema（单个工具对象） */
    private fun buildListAppsToolJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "list_apps",
                "description" to "查询设备上已安装应用的应用名和包名映射。" +
                    "可选 keywords（关键词数组）按应用名模糊过滤（如['支付宝']或['支付宝','交管12123']），不传则返回全量已装应用列表。" +
                    "用于解决对话模型不知道设备装了哪些 App、瞎猜 App 名/包名的问题。" +
                    "本工具的返回结果将用于后续 kb_read 的 app_filter 参数，请记录已安装的候选App名。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "keywords" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "可选关键词数组，按应用名模糊过滤（如['支付宝']或['支付宝','交管12123']）。不传则返回全量列表。"
                        ),
                        "max_results" to mapOf(
                            "type" to "integer",
                            "description" to "最多返回结果数（1-200），默认50",
                            "default" to 50
                        )
                    ),
                    "required" to emptyList<String>()
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 构建知识库查询工具的 OpenAI function calling schema（单个工具对象） */
    private fun buildKbToolsJson(): String {
        val tool = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "kb_read",
                "description" to "查询指定App的操作手册/SOP。必须先调用 list_apps 确认设备已安装的App，再对每个候选App单独调用本工具（app_filter 必填，一次只查一个App）。禁止无 app_filter 的全量检索、禁止对同一App重复调用、禁止跳过 list_apps 直接查库。",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "检索关键词或问题，如'微信发消息步骤'、'高德地图查路线'"
                        ),
                        "top_k" to mapOf(
                            "type" to "integer",
                            "description" to "返回结果数（1-5），默认3",
                            "default" to 3
                        ),
                        "app_filter" to mapOf(
                            "type" to "string",
                            "description" to "必填，按App过滤检索范围，如\"微信\"、\"高德地图\"，一次只传一个App名"
                        )
                    ),
                    "required" to listOf("query", "app_filter")
                )
            )
        )
        return gson.toJson(tool)
    }

    /** 解析模型返回内容为 DialogResult */
    private fun parseDialogResult(content: String): DialogResult {
        val jsonStr = extractJson(content)
        if (jsonStr.isEmpty()) {
            // 工具调用后模型可能返回自然语言（非 JSON），作为直接回复处理
            return if (content.isNotBlank()) {
                DialogResult.NeedMoreInfo(content)
            } else {
                DialogResult.Error("决策模型返回空内容")
            }
        }
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val status = obj.get("status")?.asString ?: ""
            when (status) {
                "need_more_info" -> {
                    // need_more_info 文本输出仅用于查询类任务直接回答（message 字段）
                    // 追问必须通过 ask_questions 工具调用，文本 questions 不再支持
                    val message = obj.get("message")?.asString ?: ""
                    if (message.isBlank()) {
                        DialogResult.Error("决策模型返回 need_more_info 但缺少 message 字段（追问须调用 ask_questions 工具）")
                    } else {
                        DialogResult.NeedMoreInfo(message)
                    }
                }
                "ready" -> {
                    val planObj = obj.getAsJsonObject("plan")
                    if (planObj == null) {
                        DialogResult.Error("决策模型返回 ready 但缺少 plan 对象")
                    } else {
                        val plan = parsePlan(planObj)
                        if (plan.steps.isEmpty()) {
                            DialogResult.Error("决策模型返回 ready 但 steps 为空")
                        } else {
                            val userSummary = obj.get("user_summary")?.asString?.takeIf { it.isNotBlank() }
                                ?: PlanFormatter.extractSummary(plan)
                            DialogResult.Ready(plan, userSummary)
                        }
                    }
                }
                else -> {
                    DialogResult.Error("决策模型返回未知的 status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析决策对话响应失败: ${e.message}")
            DialogResult.Error("解析决策模型响应失败: ${e.message ?: "未知错误"}")
        }
    }

    /** 从 JSON 对象解析结构化 Plan */
    private fun parsePlan(planObj: JsonObject): Plan {
        val requirement = planObj.get("requirement")?.asString ?: ""
        val goal = planObj.get("goal")?.asString ?: ""
        val stepsArr = planObj.getAsJsonArray("steps")
        val steps = if (stepsArr != null) {
            stepsArr.mapIndexed { idx, elem ->
                val s = elem.asJsonObject
                PlanStep(
                    order = s.get("order")?.asInt ?: (idx + 1),
                    goal = s.get("goal")?.asString ?: "",
                    successCriteria = s.get("success_criteria")?.asString ?: "",
                    supervised = s.get("supervised")?.asBoolean ?: false,
                    toolHint = s.get("tool_hint")?.asString ?: ""
                )
            }.filter { it.goal.isNotBlank() }
        } else {
            emptyList()
        }
        return Plan(requirement, goal, steps)
    }

    /**
     * 从 ask_questions 工具参数解析 questions 数组（结构化批量追问）
     * 工具参数已是结构化 Map，无需 JSON 解析。校验：每问必须有 question 文本和 ≥2 个合法选项
     * @return 合法问题列表（≥1 个）；整体非法返回空列表
     */
    private fun parseQuestionsFromToolArgs(args: Map<String, Any>): List<Question> {
        val rawList = args["questions"] as? List<*> ?: return emptyList()
        return rawList.mapNotNull { item ->
            val qMap = item as? Map<*, *> ?: return@mapNotNull null
            val questionText = qMap["question"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val header = qMap["header"]?.toString()?.takeIf { it.isNotBlank() } ?: questionText.take(12)
            val options = (qMap["options"] as? List<*>)?.mapNotNull { o ->
                val oMap = o as? Map<*, *> ?: return@mapNotNull null
                val label = oMap["label"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QuestionOption(
                    label = label,
                    description = oMap["description"]?.toString(),
                    recommended = oMap["recommended"] as? Boolean ?: false
                )
            } ?: emptyList()
            if (options.size < 2) return@mapNotNull null
            Question(
                question = questionText,
                header = header,
                options = options,
                multiSelect = qMap["multiSelect"] as? Boolean ?: false,
                allowFreeInput = qMap["allowFreeInput"] as? Boolean ?: true
            )
        }
    }

    /** 从模型返回内容中提取 JSON（处理 markdown 代码块包裹） */
    private fun extractJson(content: String): String {
        if (content.trim().startsWith("{")) {
            return content.trim()
        }
        val codeBlockRegex = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""")
        val match = codeBlockRegex.find(content)
        if (match != null) {
            return match.groupValues[1]
        }
        val firstBrace = content.indexOf('{')
        val lastBrace = content.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return content.substring(firstBrace, lastBrace + 1)
        }
        return ""
    }

    private fun normalizeApiUrl(baseUrl: String): String {
        if (baseUrl.isBlank()) return ""
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val errorMap = gson.fromJson(body, Map::class.java)
            (errorMap["error"] as? Map<*, *>)?.get("message")?.toString()
                ?: (errorMap["message"]?.toString())
                ?: "HTTP $code"
        } catch (e: Exception) {
            "HTTP $code: ${body.take(200)}"
        }
    }
}
