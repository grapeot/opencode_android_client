# OpenCode Android 客户端工作日志

## 2026-02-23

- 项目初始化，Android Studio 创建项目，初始化 git，编写 PRD 和 RFC
- 添加全部依赖（OkHttp、Retrofit、Serialization、Hilt、Navigation、Security 等）
- 实现数据模型层：Session、Message、Part、AgentInfo、TodoItem、FileNode、Config、SSE、Permission
- 实现网络层：REST API 接口、OkHttp SSE 客户端、带认证的 Repository
- 搭建 Hilt DI 和 Application 类
- 实现 UI 主题（浅色/深色）、SettingsManager（EncryptedSharedPreferences）、ThemeMode
- 实现 AppState + MainViewModel（StateFlow + SSE）
- 实现三个主页面：ChatScreen、FilesScreen、SettingsScreen，MainActivity 添加底部导航
- 配置 network_security_config.xml 和 INTERNET 权限
- 创建 ModelTests 和 AppStateTest

---

## 2026-02-24

- 修复大量编译问题：kapt 迁移至 KSP、升级 KSP 版本、修复各文件导入和语法错误
- 创建 AGENTS.md（构建环境说明）
- 替换应用图标为 OpenCode logo（从 iOS 项目复制，脚本生成各尺寸 mipmap）
- 修复 Settings 页面：加载已保存设置、Test Connection 正常工作、Save 持久化
- 添加测试覆盖率（Kover）和集成测试，配置 .env 凭证加载
- 添加 Android Studio 运行配置

---

## 2026-03-02

- 代码审查发现 5 个 bug：Repository lazy re-init、network_security_config 过宽、主题切换未持久化、SSE 无重连、模型选择无 UI
- 修复全部 bug：Repository 改为 mutable + rebuildClients、Tailscale ts.net exception、主题全链路打通、SSE 指数退避重连
- 新增 Markdown 渲染（Chat 消息 + 文件预览）
- 新增模型选择下拉菜单
- 新增 Context Usage 环形进度条
- 新增平板三栏布局
- AppState 扩展：ModelOption、ContextUsage、availableModels、contextUsage
- AppStateTest 新增 14 个测试，更新 PRD/RFC 标记完成状态

---

## 2026-03-03

- 重新生成 Gradle wrapper，新增 gradle.properties 配置 JVM 内存，升级 AGP 和 Kotlin
- Chat TopBar 添加 Settings 齿轮入口，解决平板布局下底部导航不可见的问题
- 默认 Server URL 改为 Tailscale quantum 地址
- 平板布局多轮迭代：左栏 Session 列表 + Settings，中栏文件预览，右栏 Chat，比例 25/37.5/37.5
- App 启动时自动连接服务器，修复消息加载时机
- 修复消息解析：Part.files 兼容字符串数组和对象数组两种格式
- 修复 Files 后退按钮、Chat TopBar 下拉定位、手机导航返回
- 升级 Compose BOM 至 2025.12.00，compileSdk 升至 35，修复 markdown-renderer 兼容性
- Tool/Patch 卡片改进：类型标题、两列并排、Show in Files 跳转、蓝色背景、Todo 展示
- 全局字号缩小一号，Markdown 标题同步缩小
- 模型列表改为预设模式（与 iOS 一致），修复 ProvidersResponse default 字段解析
- 修复发送消息：错误处理、null 字段省略、type 序列化、消息更新及时性
- 修复状态栏 insets 重叠，修复 session 切换闪烁和消息顺序

---

## 2026-03-05

- InputBar 添加 imePadding，物理键盘 IME 栏不再遮挡输入框
- Session 列表左滑删除（AnchoredDraggable），SessionList 提取为共用组件
- 手机端用 ModalBottomSheet 展示 SessionList，左滑 reveal 删除
- Logo 缩小至 66dp 安全区
- 用户消息与 AI 回复均可长按选择复制
- Session 子 agent 树形折叠展开，新增 SessionTreeTest

---

## 2026-03-12

- 设计文档：`docs/speech_recognition.md`（PRD + RFC，中文）
- 新增 AIBuildersAudioClient：通过 AI Builder WebSocket API 实现实时语音转写，支持部分结果回调和连接测试
- 新增 AudioRecorderManager：M4A 录音 + 解码 + 重采样至 24kHz PCM
- SettingsManager 新增 6 个 AI Builder 相关属性
- SettingsScreen 新增 Speech Recognition 设置区（Base URL、Token、Prompt、Terminology、连接测试）
- ChatScreen 输入栏添加麦克风按钮（录音中红色动画），speechError 弹窗提示
- MainViewModel 新增语音状态管理、录音/转写流程、连接测试逻辑
- AndroidManifest 添加 RECORD_AUDIO 权限
- 22 个单元测试 + 集成测试，全部通过
- 修复麦克风“点击无反应”：按钮在未配置时不再静默禁用，点击会进入 ViewModel 并给出明确错误提示；补充录音/转写关键日志
- 修复 AI Builder Token 隐藏字符问题：清洗零宽字符/BOM/空白后再组 Authorization header，解决 `unexpected char 0x200b`
- 修复录音启动失败 `setAudioSource failed`：点击麦克风前先检查/请求 `RECORD_AUDIO` 运行时权限，拒绝时给出明确提示
- 真机验证通过：AI Builder 连接成功后，首次点击麦克风会触发系统权限请求，授权后可正常开始录音
- Repo 公开到 GitHub（grapeot/opencode_android_client），添加 README
- 添加 GitHub Actions CI（unit test on push/PR）
- 版本号设为 0.1.20260312，首个 GitHub Release
- 将 `speech_recognition.md` 的核心设计合并进 `PRD.md` 与 `RFC.md`，删除单独文档
- 修复 Chat 输入栏：录音中允许继续发送已有文本；输入框变高时右侧操作按钮自动改为竖排
- 修复 CI：提交 `gradle-wrapper.jar`，避免 GitHub Actions 找不到 Gradle wrapper
- Files 新增图片预览：默认 fit-to-screen，支持双击缩放、拖动平移、系统分享
- Chat 自动跟随改为双模式：停留在底部时跟随新内容，离开底部时保持当前位置
- 修复手机端 Settings/Chat 状态错位：Settings 与 Chat 统一使用同一个 `MainViewModel`，避免 AI Builder 连接测试结果只留在 Settings 页面，导致麦克风仍提示未通过测试
- 修复手机端底部 Tab 导航：改为 top-level 导航写法，去掉对 `Screen.Chat.route` 的特殊 `inclusive popUpTo`，降低切换 Chat/Settings 后出现空白 Settings 页的风险
- 简化 Chat 顶栏：移除右上角重复的停止图标，busy 状态只保留输入栏右侧的停止按钮，减少重复入口
- 新建阶段一测试强化分支：`feature/test-hardening-phase1`
- 细化并落实“先补测试、再做拆分”的执行顺序：优先锁定 `MainViewModel` 状态迁移与 `OpenCodeRepository` 协议行为
- 新增 `MainDispatcherRule`，为 ViewModel 协程测试提供稳定的 Main dispatcher 控制
- 新增 `MainViewModelTest`，覆盖初始化模型索引钳制、AI Builder 连接状态恢复、发送消息成功/失败、消息加载后同步 agent/model、录音前置校验、停止录音失败、SSE streaming 增量、idle 状态补刷、权限请求刷新等关键状态机
- 扩充 `OpenCodeRepositoryTest`，覆盖 `sendMessage()` 请求体与 Basic Auth header、错误体透传、`getFileContent()` 查询参数、`getProviders()` 默认模型解析、`configure()` 重建 client 后切换 base URL/认证信息
- 根据 Oracle 审查补充 guard-rail：新增空输入/无 session 发送短路测试、`message.part.updated` 缺失 delta 的补刷测试、空 session SSE 防崩测试，并修复 `MainViewModel` 中该空 session 分支的 NPE
- 执行验证：`./gradlew testDebugUnitTest` 通过
- 生成覆盖率报告：`./gradlew koverHtmlReport`，报告位于 `app/build/reports/kover/html/index.html`
- 新建完整重构分支：`feature/full-refactor-phase2`
- 更新 `docs/dev_code_review.md`，将审查结论转换为本轮可执行 checklist
- 将 `SettingsScreen` 拆分为连接、外观、语音、About 四个 section 组件，主屏只保留状态编排
- 将 `FilesScreen` 拆分为浏览区、预览区与纯 helper（路径归一化、目录预览文案），并补充 `FileNavigationUtilsTest`
- 将 Chat UI 拆分为顶栏、消息内容、输入栏三个文件；同时把 `MessageSelectionTest` 从单文件断言改成 chat package 级行为断言，避免后续继续拆文件时产生伪失败
- 执行验证：`./gradlew testDebugUnitTest` 通过
- 继续收口 `MainViewModel`：新增 `MainViewModelConnectionActions.kt`、`MainViewModelSpeechActions.kt`、`MainViewModelSyncActions.kt`，把连接初始化、AI Builder 连接测试、录音转写编排、busy polling、SSE 收集与事件分发从主类中抽离出去
- 收紧 `MainViewModel` 相关错误处理：session 列表/状态、load more、create/update/delete session、abort session、agent/pending permission 加载都改为统一日志或状态可观测路径
- 根据 Oracle 复查补充收口：统一 AI Builder signature 的规范化计算，修复 saved session 丢失后的回退选择，避免 busy polling 与消息加载重叠，并把错误文案从 `null` 降级为明确 fallback
- 清理 `docs/dev_code_review.md` 中已经失效的 `MainViewModel.kt:<line>` 引用，改成按职责分组的文件级定位
- 执行验证：`./gradlew testDebugUnitTest assembleDebug koverHtmlReport` 通过
- 新建 phase 3 收口分支：`feature/phase3-constant-and-structure-followups`
- 新增 `ChatUiTuning.kt` 与 `AudioTranscriptionConfig.kt`，把 Chat 输入区 / top bar 阈值与音频转写参数从 UI、录音、WebSocket 实现中收口出来
- 将 `FilePreviewPane.kt` 的 preview 分流逻辑下沉到 `FilePreviewUtils.previewContentKind(...)`，减少 Composable 内部 `when` 分支复杂度
- 新增 `ChatUiTuningTest.kt`、扩充 `FilePreviewUtilsTest.kt` 与 `SpeechRecognitionTest.kt`，为阈值判断、preview 路由和音频参数对齐补充 JVM 护栏
- 新增 `ChatInputBarInstrumentedTest.kt` 与 `SettingsSectionsInstrumentedTest.kt`，覆盖 Chat 输入区动作按钮状态、Settings 语音区按钮可用性与成功态展示
- 调整 connected integration tests：仅在显式配置且服务可达时运行 OpenCode server smoke tests；未配置或服务不可达时自动 skip，避免把外部环境问题误报为回归
- 执行验证：`./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest koverHtmlReport connectedDebugAndroidTest` 通过
- 修复 session 标题不刷新：新增 `session.updated` SSE 事件处理，参考 iOS 实现，服务端更新 session 标题后通过 SSE 推送至客户端并即时替换本地 session 对象（含 title），无需轮询或延时刷新
- 修复工具调用折叠箭头方向：ToolCard 和 ReasoningCard 的折叠/展开箭头从 ExpandLess(↑)/ExpandMore(↓) 改为 ChevronRight(→)/KeyboardArrowDown(↓)，与 SessionList 树形折叠风格统一
- 新增 `parseSessionUpdatedEvent` 解析器，兼容 "info" 和 "session" 两种 payload key（对齐 iOS）
- 新增 2 个 MainViewModelTest：验证 session.updated 事件更新已有 session 标题、插入未知 session
- 修复新建 session 崩溃：`createSession()` 的 REST 成功回调与 `session.created` / `session.updated` SSE 事件统一改为按 `session.id` 去重 upsert，避免 SessionList 的 LazyColumn 因重复 key 崩溃
- 新增 `MainViewModelTest` 回归用例，覆盖 create response 与 `session.created` SSE 竞态下仍只保留一个 session 条目
- 执行验证：`./gradlew testDebugUnitTest` 通过
- 调整 SessionList 视觉语义：标题统一改为粗体；选中态改为背景色高亮；busy session 改用前景色表达，避免把“选中”和“正在运行”混在一个颜色信号里
- 修正 SessionList 选中态背景泄露 swipe 删除层的问题：选中背景改为不透明浅蓝白色，swipe reveal 背景从红色改成更 subtle 的浅蓝色，并进一步加强选中背景对比度
- 修复 SessionList 滚动 UX bug：移除前端对 session tree 的 20 条本地分页与伪 “Loading more...” 提示，左侧列表现在直接滚动浏览本次 `/session` 已返回的全部 session
- 新增 `SessionListInstrumentedTest`，验证列表可以滚动到更靠后的 session 项，防止再次引入前端裁剪
- 对齐 iOS 增加 session 分页加载：`GET /session?limit=N` 初始请求 100 条，滚动接近底部时按 100 递增 limit（100→200→300）重新拉取更老 session，解决 sub-agent 大量占位时主 session 看起来“没加载完”的问题
- 扩充 `SessionListInstrumentedTest` 与 `MainViewModelTest`：分别覆盖滚动到底触发 load-more、以及 ViewModel 的 session limit / hasMore 状态迁移
- 执行验证：`./gradlew testDebugUnitTest` 与 `./gradlew assembleDebugAndroidTest` 通过
- 删除 `docs/dev_code_review.md`，结束已完成的 code review / refactor 跟踪文档
- 执行验证：`./gradlew testDebugUnitTest` 通过
- 更新 PRD/RFC 标记相关功能完成

---

## 2026-03-14

- 从模型预设列表中移除 Gemini 3.1 Pro 和 Gemini 3 Flash 两个模型。

---

- 新建 busy-session 发送修复分支：`feature/allow-queued-send-while-busy`
- 对齐 iOS 的会话排队行为：移除 Android Chat 输入区对 `isBusy` 的发送禁用，仅在转写中继续阻止发送；busy 状态下仍保留 stop 按钮
- 新增 `MainViewModelTest` 回归用例，确认 current session 为 busy 时仍会调用 `repository.sendMessage(...)` 排队发送下一条 prompt
- 更新 `ChatInputBarInstrumentedTest`，确认 busy 状态下 stop 可见且 send 仍可点击

---

## 2026-03-13

**Question 功能实现（`feature/question-support`，参照 iOS 客户端）**

**数据层**
- `data/model/Question.kt` — `QuestionOption`、`QuestionInfo`、`QuestionRequest` 数据模型（镜像 iOS `QuestionModels.swift`）
- `data/api/OpenCodeApi.kt` — 3 个新端点：`GET /question`、`POST /question/{id}/reply`、`POST /question/{id}/reject`
- `data/repository/OpenCodeRepository.kt` — `getPendingQuestions()`、`replyQuestion()`、`rejectQuestion()`

**ViewModel 层**
- `AppState` 添加 `pendingQuestions: List<QuestionRequest>` 字段
- `MainViewModelSupport.kt` — `parseQuestionAskedEvent()` 从 `payload.properties: JsonObject` 反序列化 `QuestionRequest`
- `MainViewModelSyncActions.kt` — 内联处理 `question.asked`（去重 upsert）、`question.replied`、`question.rejected` SSE 事件
- `MainViewModel.kt` — `loadPendingQuestions()`、`replyQuestion()`、`rejectQuestion()`

**UI 层**
- `ui/chat/QuestionCardView.kt` — 完整 Composable：单选/多选/自定义文本输入、多题分步导航、进度指示点、Dismiss/Back/Next/Submit 按钮
- `ui/chat/ChatScreen.kt` — 集成 `QuestionCardView`，按 `currentSessionId` 过滤

**测试**
- `QuestionTest.kt` — 5 个单元测试，全部通过

**构建验证**
- `./gradlew assembleDebug` — BUILD SUCCESSFUL
- `./gradlew testDebugUnitTest` — 5/5 QuestionTest pass

**版本**：0.1.20260313，versionCode 2，GitHub Release + tag `v0.1.20260313`

---

## 2026-03-14

**Phase 5：UX 对齐 iOS（`feature/ux-parity-phase5`）**

iOS/Android feature parity 调研完成，确认以下体验层差异需要对齐：

**5.1 Chat Toolbar 重排**
- 当前问题：session 标题是 `titleSmall` 塞在 TopAppBar 左侧，所有按钮（Context ring、Model、Agent、Session list、Settings）挤在右侧
- 目标：对齐 iOS ChatToolbarView 布局，分两行
  - 第一行：大标题（`titleMedium` bold）
  - 第二行：左侧 [List] [Rename] [Add]，右侧 [Model ▾] [Agent ▾] [◔ Context]
- 改动文件：`ChatTopBar.kt`（主改动）、`ChatScreen.kt`（新增 rename 回调）

**5.2 Session Rename UI**
- 当前问题：`updateSessionTitle()` 后端已实现，但 UI 无入口
- 目标：Toolbar 左侧加 Rename 按钮，点击弹 AlertDialog 输入新标题
- 改动文件：`ChatTopBar.kt`

**5.3 草稿按 Session 持久化**
- 当前问题：`inputText` 全局，切换 session 丢失草稿
- 目标：按 sessionID 存储草稿到 EncryptedSharedPreferences（JSON Map）
- 改动文件：`SettingsManager.kt`（新增 get/setDraftText）、`MainViewModel.kt`（selectSession 时保存/恢复）、`MainViewModelSessionActions.kt`

**5.4 Model/Agent 按 Session 记忆**
- 当前问题：全局 `selectedModelIndex` + 从 last message 推断，手动切模型后切走再切回会丢失
- 目标：按 sessionID 存储选择到 EncryptedSharedPreferences（JSON Map），恢复优先级 per-session > 推断 > 全局默认
- 改动文件：`SettingsManager.kt`（新增 get/setModelForSession）、`MainViewModel.kt`（selectModel/selectAgent 时写入）、`MainViewModelSessionActions.kt`（selectSession 时恢复）

**文档更新**：PRD v1.1、RFC §4.3/§4.4/§5.4 已更新

**实现完成**：

- `ChatTopBar.kt`：替换 `TopAppBar` 为 `Surface + Column` 自定义布局
  - Row 1：Session 标题（`titleMedium` bold，单行省略）
  - Row 2：左侧 [List] [Edit/Rename] [Add]，`Spacer(weight(1f))`，右侧 [Model] [Agent] [Context ring] [Settings]
  - 所有 icon 按钮统一 36.dp / 20.dp 尺寸
  - Rename 弹出 AlertDialog + OutlinedTextField，确认后调用 `onRenameSession(title)`
  - `Surface(tonalElevation=2.dp)` + `HorizontalDivider` 与消息区分隔
- `ChatScreen.kt`：新增 `onRenameSession` 回调，接入 `viewModel.updateSessionTitle()`
- `SettingsManager.kt`：新增 6 个方法 + 3 个 key 常量
  - `getDraftText/setDraftText`：JSON Map 存储，空白文本自动移除
  - `getModelForSession/setModelForSession`：JSON Map 存储 Int 索引
  - `getAgentForSession/setAgentForSession`：JSON Map 存储 agent name
- `MainViewModel.kt`：
  - `setInputText()`：同步保存草稿到 SettingsManager
  - `selectModel()`：同步保存 per-session model
  - `selectAgent()`：同步保存 per-session agent
  - `sendMessage()`：成功后清空草稿（onSuccess callback）
  - `loadMessages()`：传递 settingsManager 给 launchLoadMessages
- `MainViewModelSessionActions.kt`：
  - `selectSessionState()`：切换前保存旧草稿，切换后恢复新草稿到 inputText
  - `launchLoadMessages()`：per-session 保存的 model/agent 优先于 message 推断
  - `launchSendMessage()`：新增 onSuccess 回调参数
- `MainViewModelTest.kt`：新增 per-session draft/model/agent 相关测试

**Phase 5b 调研与计划**（`feature/ux-parity-phase5b`）

三个新问题调研完成：

**5b.1 消息历史分页 Bug**
- 根因：`ChatMessageContent.kt` 的 `shouldLoadMore` 检测 `lastVisible >= total - 3`，在 `reverseLayout = true` 下实际在最新消息处触发，而非最旧消息处
- 修复：改为检测 `firstVisible >= total - 3`（用户滚到视觉顶部/最旧消息附近时触发）
- 改动文件：`ChatMessageContent.kt`

**5b.2 Model/Agent Capsule 文本化**
- 现状：Android Model/Agent 选择器仅显示 icon（Tune / SmartToy），iOS 显示 Capsule 文本按钮
- 方案：替换 IconButton 为 Capsule Composable（Model: primary 背景白色文字；Agent: surfaceVariant 背景），显示 shortName + chevron
- 需要给 `AppState.ModelOption` 新增 `shortName` 计算属性
- 改动文件：`ChatTopBar.kt`、`MainViewModel.kt`

**5b.3 平板 Toolbar 适配**
- 现状：平板下 `showSessionListInTopBar = false` + `showNewSessionInTopBar = false`，左侧只剩 Rename
- 方案：调整平板模式下的按钮布局使左右更平衡
- 改动文件：`ChatTopBar.kt`、可能 `MainActivity.kt`

**文档更新**：PRD v1.2、RFC §5.5/§5.6/§5.7/§5.8 已更新

**实现完成**：

- `ChatMessageContent.kt`：
  - 修复分页 stale closure bug：`remember` 添加 `(isLoading, messages.size, messageLimit)` 作为 keys
  - 分页 loading indicator 仅在 `messages.size >= messageLimit` 时显示（区分初始加载和分页）
  - assistant 消息顶部新增模型标注：`labelSmall` + 60% alpha `onSurfaceVariant`，显示 `providerId/modelId`
- `ChatTopBar.kt`：
  - Model 选择器：IconButton(Tune) → Capsule(primary 背景 + white text shortName + chevron)
  - Agent 选择器：IconButton(SmartToy) → Capsule(surfaceVariant 背景 + secondary text + chevron)
  - 平板模式（`showSessionListInTopBar = false && showNewSessionInTopBar = false`）：隐藏 Rename 按钮，左侧只保留标题
- `MainViewModel.kt`：`ModelOption` 新增 `shortName` 计算属性（Opus/Sonnet/Haiku/Gemini/GPT/Grok + fallback）

**Bug 修复**：
- Model badge 移到 assistant 消息下方（原在上方）
- 平板模式 Rename 按钮恢复显示（去掉 `showSessionListInTopBar || showNewSessionInTopBar` 条件）
- 平板模式 Context ring 恢复显示：右侧 Row 改用 `spacedBy(4.dp)` + Capsule Box 加 `weight(1f, fill = false)` 防溢出
- Context ring streaming 期间保持可见：ChatScreen 缓存最后非 null contextUsage，避免 streaming 时新 assistant 消息无 tokens 导致 ring 消失
