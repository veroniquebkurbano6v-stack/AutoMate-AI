# AutoMate AI

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Android GUI 智能助手，通过 AI 模型自动化操作手机界面。**完全端侧 RAG，无需 PC、无需服务端、离线可用。**

本项目采用 [Apache License 2.0](LICENSE) 开源协议，允许商用、修改与分发，使用时请保留版权声明。

## 核心特性

- **原生 Android Kotlin**：装在手机上的 App，无需电脑/ADB
- **双模式执行**：文本模式（无障碍树 + OCR + VLM 描述）+ 视觉模式（GUI-Plus 截图直出动作坐标）
- **完全端侧知识库**：bge-small-zh INT8 ONNX + SQLite 向量 + 内存检索，首次启动自动建库，离线可用
- **决策模型工具链**：OpenAI function calling，kb_read / list_apps / amap_* / web_search / ask_questions
- **消息通道**：微信机器人等通道可远程下发任务、接收执行结果

## 核心架构

### 双模式任务编排
- **复杂模式**（默认）：用户请求 → 决策模型生成 Plan → 执行模型按 Plan 操作
- **简单模式**：用户请求 → 直接交执行模型操作（跳过决策层）

开关：`KVUtils.isComplexModeEnabled()`，设置界面可切换。

### 端侧知识库（完全本地 RAG）
首次启动从 `assets/kb/` 读取 586 条 SOP JSON → 端侧 bge-small-zh INT8 ONNX 嵌入 → 写入 SQLite BLOB → 内存检索。后续启动直接读库，无需重复嵌入。

检索管线（对齐原服务端四层混合检索，去 reranker）：
1. **关键词检索**：task_name / keywords / app_name 子串匹配
2. **向量检索**：task 向量(0.7) + keyword 向量(0.3) 加权融合，内存暴力余弦
3. **RRF 融合**：两路排名倒数融合（RRF_K=60），候选池 50
4. **阈值过滤**：score < 0.3 标记低相关；置信度 high ≥0.55 / medium ≥0.45 / 其余 low

### 模型分工
| 角色 | 默认模型 | 用途 | 部署方式 |
|------|---------|------|---------|
| 文本执行模型 | deepseek-v4-flash | 接收屏幕信息，决策下一步操作 | 云端 API |
| 任务决策模型 | deepseek-v4-flash | 意图路由 + Plan 生成 + 工具调用 | 云端 API（独立配置） |
| 上下文压缩模型 | glm-4.5-flash（智谱） | 长对话历史压缩（Running Summary / FailureCompactor） | 云端 API（未配置回退 Planner） |
| 视觉执行 + 定位 | gui-plus-2026-02-26（阿里云百炼） | 截图+指令→动作+坐标 | 云端 API（百炼 DashScope） |
| VLM 屏幕描述 | qwen3-vl-flash | 屏幕视觉描述 | 云端阿里云百炼 |
| 键盘检测 VLM | glm-4v-flash | 检测键盘是否弹出 | 云端智谱（免费，未配置回退主 VLM） |
| 知识库嵌入 | bge-small-zh-v1.5 INT8 | SOP 向量化（512 维） | **端侧 ONNX Runtime** |

## 快速开始

### 1. 环境准备
- Android Studio + JDK 17
- Android SDK（路径在 `local.properties` 中配置）

### 2. 配置
```bash
cp local.default.properties local.properties
# 编辑 local.properties，填入 API Key
```

关键配置项：
- `LLM_API_KEY` / `LLM_API_URL` / `LLM_MODEL`：执行模型
- `PLANNER_API_KEY` / `PLANNER_API_URL` / `PLANNER_MODEL`：决策模型
- `COMPACT_API_KEY` / `COMPACT_API_URL` / `COMPACT_MODEL`：上下文压缩模型（未配置回退 Planner）
- `VLM_API_KEY` / `VLM_API_URL` / `VLM_MODEL`：VLM 屏幕描述
- `KEYBOARD_VLM_*`：键盘检测 VLM
- `DASHSCOPE_API_KEY`：GUI-Plus 视觉执行（为空自动回退 LLM_API_KEY，共用百炼额度）
- `AMAP_API_KEY` / `AMAP_MCP_BASE_URL`：高德地图 MCP
- `BOCHA_API_KEY`：联网搜索（不配则降级 DuckDuckGo）
- `EXECUTION_ENABLE_SEARCH`：执行模型联网搜索总开关（默认 true）

> 端侧知识库无需配置，App 内置 ONNX 模型 + 586 条 SOP，首次启动自动建库。

### 3. 编译安装
```bash
./gradlew.bat assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

首次启动时，端侧知识库引擎会在后台自动建库（约 30-60s），完成后即可离线检索。

## 项目结构

```
AutoMate-AI/
├── app/src/main/
│   ├── java/com/palmagent/app/
│   │   ├── agent/                # Agent 执行编排（DefaultAgentService, ActionExecutor, FailureCompactor…）
│   │   ├── kb/                   # 端侧知识库引擎（完全本地 RAG）
│   │   │   ├── LocalKbEngine.kt        # 引擎入口（建库 + 检索编排 + 重建）
│   │   │   ├── OnnxEmbedder.kt         # bge-small-zh INT8 ONNX 嵌入
│   │   │   ├── BertTokenizer.kt        # WordPiece 分词器
│   │   │   ├── KbDbAccessor.kt         # SQLite 读写（BLOB 向量）
│   │   │   ├── SopJsonLoader.kt        # 从 assets 读 SOP JSON
│   │   │   ├── InMemoryVectorIndex.kt  # 内存向量检索
│   │   │   ├── KeywordSearcher.kt      # 关键词检索
│   │   │   └── KbAssetLoader.kt        # 资源拷贝
│   │   ├── service/              # 服务层（无障碍/OCR/VLM/GUI-Plus/决策/搜索/MCP/保活）
│   │   ├── tool/impl/            # 25 个动作工具（TapTool, AutoInputTool, SelectSpecTool…）
│   │   ├── channel/              # 消息通道（微信机器人 WeChatChannelHandler 等）
│   │   ├── floating/             # 悬浮窗（AskUserManager, FloatingProgressManager）
│   │   ├── framework/            # DI（Hilt）/ EventBus / 协程调度
│   │   ├── domain/ + data/       # UseCase / Repository 分层
│   │   ├── model/                # 数据模型
│   │   ├── ui/                   # 界面（经典 View：guide/home/settings/log/chat）
│   │   └── utils/                # KVUtils（配置中心）
│   ├── assets/kb/
│   │   ├── onnx/model_quantized.onnx   # bge-small-zh INT8（23MB）
│   │   ├── vocab.txt                   # 分词器词表
│   │   └── sop_raw/                    # 586 条 SOP JSON
│   └── res/                      # 资源（XML 布局）
├── local.default.properties      # 配置模板
└── local.properties              # 实际配置（gitignore）
```

## 操作工具

### 执行模型 ActionType
- `TAP`/`CLICK`：坐标点击
- `LOCATE`：视觉定位并自动点击（GUI-Plus）
- `AUTO_INPUT`：定位输入框 + 输入文本 + 自动确认
- `LONG_PRESS`、`SWIPE`、`SCROLL_UP/DOWN/LEFT/RIGHT`、`SCROLL_UNTIL`、`OPEN_APP`、`BACK`、`HOME`、`WAIT`
- `SELECT_SPEC`：规格自动选取（无障碍树驱动：查选中态 → 节点直点 → 表单过长小步下滑 → 点确认）
- `FINISH`、`REQUEST_USER_ACTION`、`ASK_USER`（批量提问，questions 数组）、`FORGET`
- `VISUAL_DESCRIBE`：向视觉模型提问
- `WEB_SEARCH`：联网搜索

### 知识库检索（kb_read）
端侧本地检索，离线可用（端到端 <80ms）：
- `query`：检索词（必填）
- `top_k`：1-5，默认 3
- `app_filter`：可选，按 App 过滤检索范围（如"微信"）
- 每条 SOP 含 `task_name`、`app_name`、`score`、`confidence`、全量 `steps`
- 知识库总规模：586 个 SOP

### 决策模型工具链
- `kb_read` / `list_apps` / `amap_search` / `amap_nearby` / `amap_directions` / `amap_weather` / `web_search` / 追问（ask questions）

## 故障排查

| 现象 | 排查 |
|------|------|
| GUI-Plus 连接失败 | 检查百炼 API Key 是否正确（DASHSCOPE_API_KEY，为空回退 LLM_API_KEY） |
| 知识库不可用 | 查看 Logcat `LocalKbEngine` 标签，确认建库完成；或设置页触发"重新入库" |
| TAP 解析失败 | 检查 AI 输出 JSON 是否有未转义引号 |
| 无障碍不可用 | 检查系统设置 → 无障碍 → AutoMate AI 是否开启；可用通知栏快捷开关恢复 |
| 首次启动慢 | 端侧建库需 30-60s（586 条 SOP 嵌入），后续启动直接读库 |
| 联网搜索无结果 | 检查 `EXECUTION_ENABLE_SEARCH` 开关与 `BOCHA_API_KEY`（未配置降级 DuckDuckGo） |
| 长任务中途异常 | 看 `FailureCompactor` / `SmartWaitStrategy` 日志；确认前台服务与保活（WRITE_SECURE_SETTINGS）生效 |
