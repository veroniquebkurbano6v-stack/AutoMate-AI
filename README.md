# AutoMate AI

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Android GUI 智能助手，通过 AI 模型自动化操作手机界面。**完全端侧 RAG，无需 PC、无需服务端、离线可用。**

本项目采用 [Apache License 2.0](LICENSE) 开源协议，允许商用、修改与分发，使用时请保留版权声明。

## 核心特性

- **原生 Android Kotlin**：装在手机上的 App，无需电脑/ADB
- **双模式执行**：文本模式（无障碍树 + OCR + VLM 描述）+ 视觉模式（GUI-Plus 截图直出动作坐标）
- **完全端侧知识库**：bge-small-zh INT8 ONNX + SQLite 向量 + 内存检索，首次启动自动建库，离线可用
- **决策模型工具链**：OpenAI function calling，kb_read / list_apps / amap_* / web_search / ask_questions

## 核心架构

### 双模式任务编排
- **复杂模式**（默认）：用户请求 → 决策模型生成 Plan → 执行模型按 Plan 操作
- **简单模式**：用户请求 → 直接交执行模型操作（跳过决策层）

开关：`KVUtils.isComplexModeEnabled()`，设置界面可切换。

### 端侧知识库（完全本地 RAG）
首次启动从 `assets/kb/` 读取 600 条 SOP JSON → 端侧 bge-small-zh INT8 ONNX 嵌入 → 写入 SQLite BLOB → 内存检索。后续启动直接读库，无需重复嵌入。

检索管线（对齐原服务端四层混合检索，去 reranker）：
1. **关键词检索**：task_name / keywords / app_name 子串匹配
2. **向量检索**：task 向量(0.7) + keyword 向量(0.3) 加权融合，内存暴力余弦
3. **RRF 融合**：两路排名倒数融合，候选池 50
4. **阈值过滤**：score < 0.3 标记低相关

### 模型分工
| 角色 | 默认模型 | 用途 | 部署方式 |
|------|---------|------|---------|
| 文本执行模型 | DeepSeek | 接收屏幕信息，决策下一步操作 | 云端 API |
| 任务决策模型 | DeepSeek（可改 Qwen） | 意图路由 + Plan 生成 + 工具调用 | 云端 API（独立配置） |
| 视觉执行 + 定位 | GUI-Plus（阿里云百炼） | 截图+指令→动作+坐标 | 云端 API（百炼 DashScope） |
| VLM 屏幕描述 | qwen3-vl-flash | 屏幕视觉描述 | 云端阿里云百炼 |
| 键盘检测 VLM | GLM-4V-Flash | 检测键盘是否弹出 | 云端智谱（免费） |
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
- `VLM_API_KEY` / `VLM_API_URL` / `VLM_MODEL`：VLM 屏幕描述
- `KEYBOARD_VLM_*`：键盘检测 VLM
- `DASHSCOPE_API_KEY`：GUI-Plus 视觉执行
- `AMAP_API_KEY`：高德地图 MCP
- `BOCHA_API_KEY`：联网搜索（不配则降级 DuckDuckGo）

> 端侧知识库无需配置，App 内置 ONNX 模型 + 600 条 SOP，首次启动自动建库。

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
│   │   ├── agent/                # Agent 核心
│   │   ├── kb/                   # 端侧知识库引擎（完全本地 RAG）
│   │   │   ├── LocalKbEngine.kt        # 引擎入口（建库 + 检索编排）
│   │   │   ├── OnnxEmbedder.kt         # bge-small-zh INT8 ONNX 嵌入
│   │   │   ├── BertTokenizer.kt        # WordPiece 分词器
│   │   │   ├── KbDbAccessor.kt         # SQLite 读写（BLOB 向量）
│   │   │   ├── SopJsonLoader.kt        # 从 assets 读 SOP JSON
│   │   │   ├── InMemoryVectorIndex.kt  # 内存向量检索
│   │   │   ├── KeywordSearcher.kt      # 关键词检索
│   │   │   └── KbAssetLoader.kt        # 资源拷贝
│   │   ├── service/              # 服务层（AIService, DecisionDialogService, VlmService 等）
│   │   ├── tool/impl/            # 工具实现（KbReadTool, TapTool, LocateTool 等）
│   │   ├── ui/                   # 界面
│   │   └── utils/                # KVUtils（配置中心）
│   ├── assets/kb/
│   │   ├── onnx/model_quantized.onnx   # bge-small-zh INT8（23MB）
│   │   ├── vocab.txt                   # 分词器词表
│   │   └── sop_raw/                    # 600 条 SOP JSON（0.94MB）
│   └── res/                      # 资源
├── local.default.properties      # 配置模板
└── local.properties              # 实际配置（gitignore）
```

## 操作工具

### 执行模型 ActionType
- `TAP/CLICK`：坐标点击
- `LOCATE`：视觉定位并自动点击（GUI-Plus）
- `AUTO_INPUT`：定位输入框 + 输入文本 + 自动确认
- `LONG_PRESS`、`SWIPE`、`SCROLL_*`、`OPEN_APP`、`BACK`、`HOME`、`WAIT`
- `FINISH`、`REQUEST_USER_ACTION`、`ASK_USER`
- `VISUAL_DESCRIBE`：向视觉模型提问
- `WEB_SEARCH`：联网搜索

### 知识库检索（kb_read）
端侧本地检索，离线可用：
- `top_k`：1-5，默认 3
- 每条 SOP 含 `task_name`、`app_name`、`score`、`confidence`、全量 `steps`
- 知识库总规模：600 个 SOP

## 故障排查

| 现象 | 排查 |
|------|------|
| GUI-Plus 连接失败 | 检查百炼 API Key 是否正确 |
| 知识库不可用 | 查看 Logcat `LocalKbEngine` 标签，确认建库完成；或重启 App 触发重建 |
| TAP 解析失败 | 检查 AI 输出 JSON 是否有未转义引号 |
| 无障碍不可用 | 检查系统设置 → 无障碍 → AutoMate AI 是否开启 |
| 首次启动慢 | 端侧建库需 30-60s（600 条 SOP 嵌入），后续启动直接读库 |
