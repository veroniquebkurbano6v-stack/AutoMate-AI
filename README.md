# AutoMate AI — 一句话，帮不便操作手机的人完成手机上的事

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> Android 数字无障碍助手 · 完全端侧知识库（离线可用）· 多通道感知（无障碍树 + 视觉）
> 本项目采用 [Apache License 2.0](LICENSE) 开源协议。

## 📋 为什么做这个

中国有**数亿人**不会或不方便操作智能手机：视力下降的老年人、低视力/视障者、
行动不便者、以及不熟悉数字世界的用户。对他们而言，**点外卖、发消息、查路线**
这些"日常小事"，却是难以跨越的障碍。

AutoMate AI 用一句自然语言，帮他们完成整个手机操作流程：
**"帮我在淘宝点杯奶茶"、"用微信给家人发条消息"、"导航去最近的医院"** ——
AI 自主完成搜索、定位、输入、选择、下单等全部步骤，**无需看清屏幕、无需记住步骤、
无需精确点击**。

**多通道感知**同时融合三种"眼睛"，适配不同障碍人群：
- 🦯 **无障碍树**（文本通道）→ 读屏用户友好，App 未被屏蔽时优先
- 👁️ **屏幕视觉 + GUI-Plus**（坐标通道）→ 低视力用户友好，看不清时视觉兜底
- 🧠 **端侧知识库**（离线 SOP 指引）→ 无需联网，数据不出手机

## ✨ 核心特性

| 特性 | 技术实现 | 对用户的意义 |
|------|---------|------------|
| 完全端侧知识库 | bge-small-zh INT8 ONNX + SQLite 向量 + RRF 混合检索 | 离线可用、隐私不出手机 |
| 多通道感知 | 无障碍树 + OCR + VLM + GUI-Plus 四通道回退 | 视障/读屏总有一条"眼睛"可用 |
| 一句话任务路由 | 决策模型工具链（kb_read / list_apps / amap_* / web_search / 追问） | 用户只需"说"，无需"会" |
| 结构化 Plan | 决策模型生成分步 Plan（含完成标志、监督标记） | 复杂跨页流程拆解为可执行步骤 |
| 端到端执行 | 25 个动作工具（tap / auto_input / select_spec / open_app…） | 自动完成定位、输入、点击 |
| 敏感操作拦截 | `supervised` 标记 + 用户确认 | 支付/转账等不可逆操作不越权 |

## 📺 Demo

> 点击播放，GitHub 内嵌播放完整视频；每个场景的故事见 [docs/use-cases.md](docs/use-cases.md)

| 场景 | 演示 | 服务人群 | 亮点 |
|------|------|---------|------|
| 用淘宝点奶茶 | <video controls poster="demo/cover_03.jpg" src="demo/03_taobao_order_milk_tea.mp4" width="280"></video> | 老年人 | 跨页搜索→选规格→加购，全程 AI 代操作 |
| 用微信给联系人发消息 | <video controls poster="demo/cover_01.jpg" src="demo/01_wechat_send_message.mp4" width="280"></video> | 行动不便/低视力 | 一句话 + 自动定位输入/发送 |
| 用高德导航到目的地 | <video controls poster="demo/cover_02.jpg" src="demo/02_amap_navigate.mp4" width="280"></video> | 老年人/视障 | 附近检索→导航，语音触发 |

> 高清原片：可在 [GitHub Releases](https://github.com/veroniquebkurbano6v-stack/AutoMate-AI/releases) 下载。

## 🗺️ 端到端流程

```
用户一句话（语音/文本/远程通道）
  → 决策模型：意图路由 + 工具链（list_apps→kb_read→amap_*→追问）+ 生成 Plan
  → 执行模型：多通道感知（无障碍树/视觉/GUI-Plus）→ 25 个动作工具逐步执行
  → 端侧知识库：离线 SOP 指引
  → 完成 / 需确认 / 敏感操作拦截后向用户汇报
```

[完整架构见 docs/architecture.md](docs/architecture.md)

## 📊 评估结果（摘要）

| 指标 | 结果 |
|------|------|
| 知识库检索命中率（10 场景） | **100%**（top-3）/ 80%（top-1） |
| 平均端到端检索延迟 | <80ms（端侧） |
| 实际任务演示 | 3/3 成功（见 Demo） |

[完整评估数据见 docs/evaluation.md](docs/evaluation.md)

## 🚀 快速开始

### 1. 环境准备
- Android Studio + JDK 17
- Android SDK（路径在 `local.properties` 中配置）

### 2. 配置
```bash
cp local.default.properties local.properties
# 编辑 local.properties，填入 API Key
```
关键配置项（详见 `local.default.properties`）：
- `LLM_API_KEY` / `LLM_API_URL` / `LLM_MODEL`：执行模型
- `PLANNER_API_KEY` / `PLANNER_API_URL` / `PLANNER_MODEL`：决策模型
- `DASHSCOPE_API_KEY`：GUI-Plus 视觉执行（为空自动回退 `LLM_API_KEY`）
- `VLM_API_KEY` / `VLM_MODEL`：VLM 屏幕描述
- `AMAP_API_KEY` / `AMAP_MCP_BASE_URL`：高德地图 MCP
- `BOCHA_API_KEY`：联网搜索（不配则降级 DuckDuckGo）

> 端侧知识库无需配置：App 内置 ONNX 模型 + 586 条 SOP，首次启动自动建库，离线可用。

### 3. 编译安装
```bash
./gradlew.bat assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
首次启动端侧建库约 30-60s，完成后即可离线检索。

## 🏗️ 项目结构 & 🧠 技术架构

```
app/src/main/java/com/palmagent/app/
├── agent/     # 执行编排（DefaultAgentService / ActionExecutor / FailureCompactor / ContextManager）
├── kb/        # 端侧知识库（完全本地 RAG：ONNX 嵌入 + SQLite 向量 + 内存检索）
├── service/   # 服务层（无障碍 / OCR / VLM / GUI-Plus / 决策 / 搜索 / 保活）
├── tool/impl/ # 25 个动作工具（TapTool / AutoInputTool / SelectSpecTool…）
├── channel/   # 消息通道（微信机器人，可远程下发任务）
├── floating/  # 悬浮窗（追问 / 进度展示）
├── framework/ # DI(Hilt) / EventBus / 协程调度
├── ui/        # 界面（guide/home/settings/log/chat）
└── utils/     # KVUtils 配置中心
```

### 模型分工
| 角色 | 默认模型 | 部署方式 |
|------|---------|---------|
| 文本执行模型 | deepseek-v4-flash | 云端 API |
| 任务决策模型 | deepseek-v4-flash | 云端 API（独立配置） |
| 视觉执行 + 定位 | gui-plus-2026-02-26（百炼） | 云端 API |
| VLM 屏幕描述 | qwen3-vl-flash（百炼） | 云端 API |
| 知识库嵌入 | bge-small-zh-v1.5 INT8 | **端侧 ONNX Runtime** |

### 双模式任务编排
- **复杂模式**（默认）：用户请求 → 决策模型生成 Plan → 执行模型按 Plan 操作
- **简单模式**：用户请求 → 直接交执行模型（跳过决策层）

### 端侧知识库（完全本地 RAG）
586 条 SOP JSON → 端侧 bge-small-zh INT8 嵌入 → SQLite BLOB 向量 → 内存检索。
检索管线：关键词 + 向量（task 0.7 + keyword 0.3）→ RRF 融合（RRF_K=60）→ 阈值过滤 0.3。
**无服务端、无网络依赖**，首次启动自动建库。

## 🗓️ Roadmap（无障碍专项）

- [ ] **TalkBack/读屏兼容**：无障碍树通道适配系统读屏，读屏用户可直接使用
- [ ] **语音输入交互**：端侧 ASR，一句话直达（进一步降低门槛）
- [ ] **大字/高对比模式**：界面适配低视力
- [ ] **家人远程协助**：消息通道一键接管，子女远程帮老人操作
- [ ] **适老化场景包**：常用 App 高频任务一键预置

## 📢 News

- **决策模型上下文控制**：新增任务工作区（workspace_update），工具结果由框架自动清理，
  上下文有界，防膨胀（对齐 Anthropic Context Editing 做法）
- **执行引擎增强**：FailureCompactor 失败跨轮记忆 + 工具熔断，防重试风暴烧 token
- **视觉流程修复**：修复 OCR HARDWARE 位图崩溃、输入降级 instruction 丢失
- **完全端侧知识库**：无服务端依赖，隐私不出手机

## 📄 License

[Apache License 2.0](LICENSE)

---

**相关文档**：[使用场景](docs/use-cases.md) · [架构说明](docs/architecture.md) · [评估结果](docs/evaluation.md)
