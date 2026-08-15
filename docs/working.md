# OpenCode Android 客户端工作日志

## 2026-08-14 — GLM 5.3 + Gemini 3.7 Flash model preset upgrade

- 模型列表顶部 `zai-coding-plan` 的 GLM 从 `GLM-5.2` / `glm-5.2` 升级为 `GLM-5.3` / `glm-5.3`，与 iOS 客户端对齐。`models.dev` 上 `zai-coding-plan` 已列出 `glm-5.3`。
- `ollama-cloud` 那行 `Ollama GLM 5.2` 保持不变，因为 `ollama-cloud` 上游暂未提供 `glm-5.3`。
- 默认模型 `Gemini 3.6 Flash` / `google/gemini-3.6-flash` 升级为 `Gemini 3.7 Flash` / `gemini-3.7-flash`（`models.dev` 已列出）。`shortName` 的 `Gemini` contains 分支不受影响。
- 模型列表末尾新增 `Grok 4.6` / `xai/grok-4.6`，与 iOS 的 Grok 4.6 preset 对齐（Android 此前没有 Grok preset）。`ModelOption.shortName` 的 `Grok` contains 分支自动生效。
- `GPT-5.6 Sol Fast` 本来就不在 Android preset 列表中（历史已移除），iOS 同步移除后两边一致。

## 2026-07-31 — v0.1.20260731 release prep

- Bumped the App to `versionName` `0.1.20260731` and `versionCode` 17 for the GPT Live Transcribe release. VoiceFlowKit remains pinned to exact `0.4.0`.

## 2026-07-31 — GPT Live default

- New installations now default to GPT Live Transcribe while preserving valid saved choices. VoiceFlowKit is pinned to exact JitPack release `0.4.0`, which includes recording-time accumulated transcript snapshots.
- Verification: all 309 unit tests and `assembleDebug` passed. The active in-flight strategy keeps its neutral pre-recording sentinel; Start always snapshots the persisted GPT Live default.
- Exact-pin verification: clean remote dependency resolution, all unit tests, and `assembleDebug` passed outside the local composite build.
- CI follow-up: the realtime session-switch test now waits for the observable recording state before requesting Stop, removing a scheduler-speed race seen on clean GitHub runners.

## 2026-07-30 — Dual recording strategies + Save auto-test (open PR, do not merge)

- 分支 `feat/dual-recording-strategies`：Settings 增加 OpenAI Realtime / Grok STT picker；Chat 录音 Start 时 snapshot strategy。
- Grok 路径不创建 realtime session，Stop 后 multipart 上传；OpenAI 路径保持现有 WS + heartbeat。
- **Save UX 重做**：去掉独立的 Test Connection 按钮；点 Save 即保存 + 现场测试，成功显示 connected、失败直接报错。`saveAIBuilderSettings` 不再无条件清掉已连接状态，避免改个 picker 就丢失连接绿勾。
- "Grok Batch" 改名 **"Grok STT"**。
- VoiceFlowKit 依赖 pin 到 dual-strategy commit 完整 SHA `0fbc2d5cdeeb2335671726044f7fb105314671f1`；本地存在 sibling monorepo 时 `includeBuild` 替换为源码。
- 验证：`./gradlew :app:testDebugUnitTest` 通过。PR 保持 Open，等 voiceflow-android merge/tag 后再改 exact `0.3.0` 并 merge。

## 2026-07-15 — Session Deep Link

- 分支：`feat/session-deep-links`，基于 `github/master`；本地 `master` 的独立 commit `18f4a30` 保持不动，不混入本 PR。
- 对齐 iOS `opencode://session/<session_id>` contract：Manifest system Intent、Chat Markdown、严格 JVM parser 和 ViewModel router 共用同一路径。
- 当前 Host 通过 `GET /session/:id` 验证后才切 session；断连时 pending，Host 切换/连续链接用 generation token 丢弃旧响应。
- by-ID 目标先 upsert 完整 Session，再复用现有 message/status hydration；session refresh 保留不在分页窗口中的当前目标。
- 根层提供 opening/error UI，手机成功后回到 Chat；平板保留三栏。
- 主产品与技术 contract 已并入 `docs/PRD.md` 和 `docs/RFC.md` §5.13。

## 2026-06-30 — NFC Quick Prompt (Experimental)

### 目标

在 Settings → Experimental 增加 NFC Quick Prompt 功能。用户输入一段 prompt，写入 NTAG215 NFC tag；亮屏靠近 tag 时系统自动拉起 App，新建 session，填入 prompt，按写入时的设置决定直接发送或等待确认。

### 分支

`feat/nfc-quick-prompt`（基于 `master`）

### 设计文档

NFC Quick Prompt 的 PRD 和 RFC 内容已合并进主 `docs/PRD.md` 和 `docs/RFC.md`（§5.12）。独立的 `NFC_Quick_Prompt_PRD.md`、`NFC_Quick_Prompt_RFC.md` 和 `nfc_feature_plan.md` 已删除。

### 实现

1. **AndroidManifest.xml**：新增 `NFC` permission + `<uses-feature nfc required=false>`；`MainActivity` 加 `launchMode="singleTop"` 和 `NDEF_DISCOVERED` intent-filter（scheme=`opencode`、host=`prompt`）；注册 `NfcWriterActivity`（透明、noHistory）。
2. **SettingsManager.kt**：新增 3 个属性 `nfcEnabled`/`nfcPrompt`/`nfcAutoSend`（EncryptedSharedPreferences），新增常量 `NFC_PROMPT_MAX_BYTES=480` 和 `NFC_TAG_MAX_BYTES=504`。
3. **AppState**：新增 `pendingNfcAction: NfcPendingAction?` 字段和 `NfcPendingAction(prompt, autoSend)` data class。
4. **MainViewModel.kt**：
   - `handleNfcPrompt(prompt, autoSend)`：检查 nfcEnabled → 设置 pendingNfcAction → createSession()
   - `consumePendingNfcAction()`：setInputText(prompt) + 条件 sendMessage()，清空 pending
   - `loadMessages` 的 `onMessagesLoaded` 回调触发 consumePendingNfcAction
   - NFC settings 读写辅助方法
5. **MainViewModelSessionActions.kt**：`launchLoadMessages` 新增可选 `onMessagesLoaded` 回调，在成功路径末尾调用。
6. **MainActivity.kt**：override `onNewIntent`，解析 `NDEF_DISCOVERED` URI（`opencode://prompt?a=&p=`），调用 `viewModel.handleNfcPrompt`。
7. **NfcWriterActivity.kt**（新增）：透明 Activity，读 SettingsManager 生成 URI，foreground dispatch 写入 NDEF。字节校验 > 504 拒绝。
8. **SettingsSections.kt**：新增 `NfcExperimentalSection` Compose section（启用开关、多行 prompt 输入、字节计数、autoSend 开关、Write to tag 按钮）。
9. **SettingsScreen.kt**：在 SpeechRecognitionSection 之后、AboutSection 之前挂载 `NfcExperimentalSection`。
10. **strings.xml / values-zh**：NFC 相关文案（中英文）。
11. **themes.xml**：新增 `Theme.Transparent` 主题供 NfcWriterActivity 使用。

### 测试

`NfcQuickPromptTest.kt`（6 个测试）：
- `handleNfcPrompt ignored when NFC disabled` — nfcEnabled=false 时静默忽略
- `handleNfcPrompt sets pending action when enabled` — 启用后设置 pending action 并创建 session
- `consumePendingNfcAction fills input and sends when autoSend` — autoSend=true 时 sendMessage 被调用
- `consumePendingNfcAction fills input only when autoSend false` — autoSend=false 时只填入不发送
- `consumePendingNfcAction no-op when no pending action` — 无 pending 时不做任何操作
- `NfcPendingAction holds prompt and autoSend` — data class 字段验证

### 验证

- `./gradlew testDebugUnitTest` — 全部通过（含新增 6 个 NFC 测试 + 既有全部测试）
- `./gradlew assembleDebug` — BUILD SUCCESSFUL

### 当前状态

- 代码实现完成，测试通过，真机验证通过。
- PR #77 已开，准备 merge。

### 真机调试修复

1. **NfcWriterActivity 写入卡住**：旧代码用 `enableForegroundDispatch` + 错误的 PendingIntent（传了 Activity 自身的 intent 而非新构造的 `Intent(this, NfcWriterActivity::class.java).addFlags(FLAG_ACTIVITY_SINGLE_TOP)`），导致 `onNewIntent` 不触发。修复 PendingIntent 构造，移到 `onResume` 启用。
2. **"Empty Tag" 系统弹窗**：曾尝试 `enableReaderMode` + `FLAG_READER_SKIP_NDEF_CHECK`，但在 MIUI/HyperOS 上不生效。回到 `enableForegroundDispatch`（null filters 拦住所有 tag 类型）。
3. **Session storm（320+ session）**：root cause 是 `handleNfcIntent(intent)` 误放在 `setContent` 的 Composable lambda 中，每次 UI 重组都重新触发。修复：移到只在 `onCreate` 和 `onNewIntent` 调用。
4. **Debounce**：加 30 秒 cooldown 防止 tag 贴着天线时系统反复 dispatch。
5. **ViewModel 初始化竞态**：`onNewIntent` 可能在 `setContent` 赋值 `mainViewModel` 之前到达。暂存 `pendingNfcPrompt`，在 `setContent` 第一行消费。
6. **NFC 功能不响应**：修复竞态问题后 tag 触发正常拉起 App 并执行。

## 2026-06-14 — Phase 7：Markdown Web Preview + Tablet Sessions 折叠

### Compact 后恢复步骤

新的 agent 或 context compact 后先读本节，再读两份设计文档：`docs/Android_Markdown_Web_Preview_PRD.md` 和 `docs/Android_Markdown_Web_Preview_RFC.md`。当前分支是 `feature/android-markdown-preview-collapse`。本轮目标是对齐 iOS 最近两个 PR：`#94 feat: Markdown Web Preview (Phase 0/1/2)` 和 `#95 feat(ipad): collapse Sessions panel + remove dead SplitSidebarView`。

恢复时不要重新发散调研。除非代码已明显变化，按下面的执行计划继续推进：先实现 tablet Sessions pane collapse，再实现 Markdown Web Preview 的 resource shell 和 Files 接入，最后补测试和跑验证。每完成一个可独立回滚的阶段就 commit。全部测试通过后直接开 PR，不 merge。

### 已完成

1. 已创建分支：`feature/android-markdown-preview-collapse`。
2. 已读 workspace 与项目规则：根目录 `AGENTS.md`、Android `AGENTS.md`、internal writing skill、parallel subagent skill。
3. 已用 subagent 并行调研三块：iOS Markdown Web Preview、iOS tablet Sessions pane collapse、Android 架构/测试层级。
4. 已更新主文档：`docs/PRD.md` 和 `docs/RFC.md` 增加 Phase 7、Web Preview、tablet Sessions pane collapse。
5. 已新增 Android 专属设计文档：`docs/Android_Markdown_Web_Preview_PRD.md` 和 `docs/Android_Markdown_Web_Preview_RFC.md`。
6. 已更新 `.gitignore`，忽略 `docs/ios_markdown_preview_fixtures/`。这个目录只用于把 iOS 的 Markdown Preview PRD/RFC 复制进 Android repo 手工渲染，不提交。
7. 已实现 tablet Sessions pane collapse：`MainActivity.TabletLayout` 使用 `rememberSaveable` 保存 `sessionsPaneCollapsed`，折叠时左栏不渲染，Files/Chat 各占 50%；`SessionList` header 增加 `Hide sessions`，Files pane 左上角增加 `Show sessions` 恢复入口。
8. 已实现 Android Markdown Web Preview MVP：新增 `app/src/main/assets/web_preview/` 本地 shell；`FilePreviewPane` 增加 `Web Preview` / `Native Preview` / `Markdown Source` 三态，默认 Web；新增 `MarkdownWebPreviewPane`，复用 `MarkdownImageResolver` 生成 data URI，WebView 加载 `file:///android_asset/web_preview/preview.html` 并通过 JSON payload 注入 Markdown。
9. 已补低层测试：`MarkdownImageResolverTest` 增加 query/fragment stripping case；`SessionListInstrumentedTest` 增加 collapse callback component test。
10. 已修复既有 androidTest 编译漂移：`ChatInputBarInstrumentedTest` 补齐 `agentActivityText` / `agentStartedAtMillis` 参数。
11. 已修复 Web Preview 切文件黑屏：之前切文件时 `ResolvedMarkdownWebPreview` 先把 `resolvedContent` 置空，Compose 临时移除 WebView；新文件的 WebView shell 加载到 JS ready 前显示空深色页，视觉像全黑闪一下。现在先立即渲染当前 Markdown 文本，再异步补 data URI 图片，切文件不会进入 blank WebView 状态。
12. 已修复 `.md` 一直黑屏的更底层问题：WebView 通过 `file:///android_asset/web_preview/preview.html` 加载 shell，必须允许 file asset 子资源读取，否则相邻的 `preview.js` / `markdown-it.min.js` / `purify.min.js` / `preview.css` 可能不加载，只剩黑色 HTML 背景。现在 `allowFileAccess=true` 仅用于 app asset shell；workspace 文件仍不走 WebView file URL，图片继续预解析成 data URI。`renderMarkdown()` 也增加了 renderer-ready 重试，避免注入早于 JS ready。
13. 真机反馈 Web Preview 首次打开仍有黑色首帧。当前诊断分两层：第一，WebView 先显示空的深色 `preview.html` shell，随后 `preview.js` 才完成 renderer 初始化和 Markdown 注入；第二，WebView 创建时的硬件合成层可能抢到窗口首帧，导致整个 Activity 黑闪，而不只是 Markdown pane 变黑。默认模式保持 Web Preview；修复方式是在 JS 发出 `rendered` bridge 事件前，用 Compose Native Markdown 覆盖 WebView，并用 `onPageCommitVisible` + `postVisualStateCallback` 控制 WebView 可见时机。bridge 回调统一切回 main thread 更新 Compose state。
14. 用户进一步确认黑闪只发生在进程内第一次切到 Markdown，之后不再闪。这个现象基本指向 WebView/Chromium 首次初始化，而不是每次 Markdown render。已在 `OpenCodeApp` 启动后预热一个不 attach 到窗口的 `about:blank` WebView，把首次初始化从用户点击 MD 的交互路径挪走。此前尝试过的 `LAYER_TYPE_SOFTWARE` 已撤回；Chromium 官方不建议对 WebView 使用 software layer，它也可能放大全窗口合成黑闪。

### 关键调研结论

iOS PR #94 的产品语义是 Files-only Web Preview。Swift 侧复用 `MarkdownImageResolver.resolveImages(...)` 把相对图片转 data URI，再把 Markdown payload 注入本地 `preview.html`。WebView shell 使用 `markdown-it` + `DOMPurify`，允许 HTML/CSS card、inline SVG、`details/summary` 和 table，禁止 `script/iframe/form/object/embed/on*/javascript:`。Files 预览模式是 `native / web / source`，当前默认 `web`。测试包括 path unit test、Web shell verification、XCUITest fixture。

iOS PR #95 折叠的是 iPad / regular width 的左侧 Sessions pane，不是 session row tree。状态是 view-local `sessionsCollapsed`，展开时三栏 `Sessions / Files / Chat`，折叠时 Sessions 宽度为 0，Files / Chat 平分。左栏有 Hide sessions 按钮，折叠后 Files 栏左上有 Show sessions 按钮，另有左缘 swipe 展开手势。Android 第一版先做按钮，手势可作为后续 polish。

Android 当前基线：`MainActivity.TabletLayout` 固定三栏，左栏 `SessionList/Settings` 25%，中栏 `FilesScreen` 37.5%，右栏 `ChatScreen` 37.5%。Files Markdown 入口在 `FilePreviewPane.kt`，当前只有 Compose Native Markdown。Android 已有 `MarkdownImageResolver.resolveImages(...)` 和 data URI 图片链路，可直接复用。项目没有 WebView / assets shell。

### 执行计划

1. 文档与 fixture checkpoint
   - 复制 iOS `Markdown_Web_Preview_PRD.md` 和 `Markdown_Web_Preview_RFC.md` 到 Android `docs/ios_markdown_preview_fixtures/`。
   - 确认该目录被 git ignore，不进入 commit。
   - commit 文档和计划：`.gitignore`、`docs/PRD.md`、`docs/RFC.md`、`docs/Android_Markdown_Web_Preview_PRD.md`、`docs/Android_Markdown_Web_Preview_RFC.md`、`docs/working.md`。

2. Tablet Sessions pane collapse
   - 修改 `MainActivity.TabletLayout`。
   - 新增 `sessionsPaneCollapsed`，建议 `rememberSaveable`。
   - 展开时保持当前三栏比例；折叠时左栏不渲染，Files/Chat 各占 `0.5f`。
   - 左栏顶部增加 `Hide sessions` 按钮；折叠后 Files 顶部左侧增加 `Show sessions` 按钮。
   - 不复用 `expandedSessionIds`，不改变 phone layout。
   - 补 component/instrumented test 或至少为按钮加 stable content description，后续 UI test 可定位。
   - 验证 `./gradlew testDebugUnitTest` 和 `./gradlew assembleDebug`，可独立 commit。

3. Web Preview resource shell
   - 新增 `app/src/main/assets/web_preview/preview.html`、`preview.css`、`preview.js`、`markdown-it.min.js`、`purify.min.js`。
   - 资源可以从 iOS `Resources/WebPreview/` 迁移，但 Android 需要检查路径和 bridge 名称。
   - JS 暴露 `window.renderMarkdown(payload)`，payload 至少包含 `markdown` 和 `theme`。
   - CSS 保留主题变量：`--bg`、`--fg`、`--fg-muted`、`--border`、`--card-bg`、`--ok-*`、`--bad-*`、`--warn-*`、`--block-*`。
   - commit resource shell。

4. Files Web Preview 接入
   - 在 `FilePreviewPane.kt` 增加 `MarkdownPreviewMode`：`Web` / `Native` / `Source`，默认 `Web`。
   - Toolbar 增加 mode menu，保留 Refresh / Close；图片 Share 逻辑不受影响。
   - 新增 `MarkdownWebPreviewPane.kt`：负责 resolver、loading、error、oversize gate。
   - 新增 WebView wrapper：`AndroidView(factory = { WebView(...) })`，加载 `file:///android_asset/web_preview/preview.html`。
   - WebView settings：`javaScriptEnabled = true`，`allowFileAccess = false`，`allowContentAccess = false`，`domStorageEnabled = false`。
   - JSON 注入使用 Kotlinx Serialization 或 `JSONObject.quote` 级别的安全编码，不手写拼接 Markdown 字符串。
   - commit MVP。

5. 安全、链接与测试
   - WebViewClient 默认拦截 navigation；`http/https` 外链走系统浏览器；fragment anchor 留在页面内；其它 scheme 阻断。
   - JS bridge 接收 error/link/image 事件，第一版 image click 可 no-op。
   - 补 `MarkdownImageResolverTest` 的 Web Preview path case。
   - 补 `MarkdownWebPreviewInstrumentedTest`：至少覆盖 WebView 存在、fixture sentinel、mode switch 到 Source/Native、安全 fixture 不执行脚本。
   - 验证命令：先 `./gradlew testDebugUnitTest`，再 `./gradlew assembleDebug`；有 emulator 时跑 targeted `connectedDebugAndroidTest` 或相关 instrumented class。不要在物理 Android 设备上跑 install / connected tests。

### 当前待处理

- [x] 复制 iOS Markdown Preview PRD/RFC 到 ignored fixture 目录。
- [x] 提交文档计划 checkpoint：`a772863 docs: plan Android markdown web preview parity`。
- [x] 实现 tablet Sessions pane collapse，验证 `./gradlew assembleDebug` 与 `./gradlew testDebugUnitTest` 均通过。
- [x] 实现 Web Preview resource shell。
- [x] 接入 Files mode menu 和 WebView renderer。
- [x] 补 unit/component tests。
- [x] 跑 connected/component 验证。
- [x] 开 PR：`https://github.com/grapeot/opencode_android_client/pull/61`。
- [x] 修复 Web Preview 首次打开黑色首帧：WebView 后台加载，Native Markdown overlay 持续到 JS 发出 `rendered`；WebView 使用 visual-state callback 后显示，并在 app 启动后预热 Chromium/WebView 首次初始化。

### 当前验证状态

- `./gradlew testDebugUnitTest`：通过。
- `./gradlew assembleDebug`：通过。
- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`：通过，25 tests，2 skipped（AI Builder live credential 类），0 failed。
- 切文件黑屏修复后重复执行以上三条验证，均通过；connected test 明确指定 `ANDROID_SERIAL=emulator-5554`，避免安装到已连接真机。
- `.md` shell asset 加载修复后再次执行以上三条验证，均通过。
- Web Preview 首次打开黑色首帧修复后重复执行以上三条验证，均通过。默认 Markdown mode 保持 Web Preview；Native Preview 作为 Web renderer 完成前的临时覆盖层和菜单中的手动 fallback。用户确认黑闪只在第一次切 MD 出现后，新增 app 启动后 WebView warm-up，并再次验证 `./gradlew testDebugUnitTest assembleDebug` 与 `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` 通过。

### 提交节奏

建议 commit 切分：

1. `docs: plan Android markdown web preview parity`
2. `feat(tablet): collapse sessions pane`
3. `feat(markdown): add web preview shell`
4. `feat(files): add markdown web preview mode`
5. `test: cover markdown web preview`

如果某个阶段测试失败，先修复再 commit。不要把 broken checkpoint commit 到分支，除非明确标记为 WIP 且用户要求保留。

## 2026-06-20

- 模型列表中将 `Ollama Kimi K2.6`（`ollama-cloud/kimi-k2.6`）替换为 `Ollama GLM 5.2`（`ollama-cloud/glm-5.2`）。OpenCode server 的 `ollama-cloud` provider registry 已通过 `scripts/regen_models_local.py` 的 `INJECTIONS` 注入 `glm-5.2` 条目，model key 是 `glm-5.2`。
- `ModelOption.shortName` 从 `Ollama Kimi K2.6 -> Kimi` 改为 `Ollama GLM 5.2 -> OGLM-5.2`（加 O 前缀和版本号，与顶部 `GLM-5.2` preset 的 shortName `GLM` 区分），对齐 iOS model chip 显示。
- Android 用 index 持久化 session model selection，GLM 5.2 占据原 Kimi 的 index 6 槽位，无需 canonical mapping 即可平滑迁移。

## 2026-06-10

- 模型列表中将 `MiniMax M3`（`ollama-cloud/minimax-m3`）替换为 `Ollama Kimi K2.6`（`ollama-cloud/kimi-k2.6`）。OpenCode server 的 `ollama-cloud` provider registry 暴露的 model key 是 `kimi-k2.6`；直接传 Ollama library tag `kimi-k2.6:cloud` 会让 `prompt_async` 返回 204 但后台不生成 assistant message。
- `ModelOption.shortName` 新增 `Ollama Kimi K2.6 -> Kimi`，对齐 iOS model chip 显示。

## 2026-06-08

- 对齐 iOS PR #84/#86/#87，实现 F3 voice rail composer：`ChatInputBar` 从旧单 pill 横排改为上方 voice rail + 下方 text review field。Voice rail 包含 transport、真实 `VoiceFlowMicrophone.audioLevel` waveform、转写等待 `Stop transcription wait`、preserved-audio `Retry this segment` 和 `Discard audio`。
- Agent running 从右侧红色 stop 主按钮降为 composer 附近 quiet status row；`Interrupt agent` 放入 overflow menu，和语音 stop-wait / retry 语义分离。send 固定在 text review field 右侧，busy 时仍可发送排队，transcribing/retrying 时禁用。
- `MainViewModel` 新增 `speechAudioLevel` state 并在录音生命周期内收集 microphone audio level；preserved audio discard 暴露为明确 action。
- 更新 `ChatInputBarInstrumentedTest` 覆盖 busy quiet status、transcribing + agent running、preserved-audio retry/discard 三个关键状态。
- 对齐 iOS PR #85，实现 Session Archive workflow：`SessionList` 分 Active / Archived 两个分区，Archived 默认折叠；Active leading swipe 为 Archive，Archived leading swipe 为 Restore，trailing swipe 保持 Delete。
- `PATCH /session/{id}` request body 扩展为可写 `time.archived`；Archive 写当前毫秒时间戳，Restore 写 `-1`。Archive subtree 按 children-first，Restore subtree 按 parent-first，避免父子 session 在分区间短暂游离。
- Session 分页从滚到底自动触发改为顶部全局 `Load older` action，避免 Archived 默认折叠时为不可见历史连续分页，也避免把加载更多误读成 Archived 专属动作。直接打开 archived session 并发送消息时会先 restore 当前 session，再发送。
- 补充 `OpenCodeRepositoryTest`、`MainViewModelTest`、`SessionListInstrumentedTest` 覆盖 archived PATCH body、递归顺序、Active/Archived 分区和全局 load-older。

## 2026-06-03

- 模型预设对齐 iOS，新增三个：`Ollama DeepSeek V4 Pro`（ollama-cloud/deepseek-v4-pro，shortName ODS-Pro）、`MiniMax M3`（ollama-cloud/minimax-m3）、`DeepSeek Local`（ds4/deepseek-v4-flash，shortName DS-L）。
- Task/Todo 列表支持：
  - `AppState` 新增 `sessionTodos: Map<String, List<TodoItem>>`，SSE `todo.updated` 事件实时更新。
  - session 切换时在 `launchLoadMessages` 内 fire-and-forget 加载 `GET /session/{id}/todo`（对齐 iOS）。
  - Toolbar 右侧新增 Checklist 图标按钮，始终可见；无 todo 时仅显示图标，有 todo 时显示完成数。
  - 点击弹出 `AlertDialog` → `TodoListPanel` composable（进度条 + checkbox 列表 + 完成划线 + 空态）。
  - `ChatMessageContent` 中 `todowrite` ToolCard 改为紧凑徽章 `"Todo updated · X/Y"`，替代之前的行内 TodoListInline。
- 新增文件 `ui/chat/TodoListPanel.kt`。
- 修改文件：`MainViewModel.kt`、`ModelPresets.kt`、`MainViewModelSyncActions.kt`、`MainViewModelSessionActions.kt`、`ChatTopBar.kt`、`ChatScreen.kt`、`ChatMessageContent.kt`。
- 验证：`./gradlew testDebugUnitTest` - 214 tests pass。

## 2026-05-30

- VoiceFlowKit dependency 更新到 merged revision `cc49c8fa272846852970f8df938766af6e7576ea`，使用 preserved audio retry facade。Chat input 左侧新增语音辅助按钮：录音/转写中显示 stop，调用 `abortPreservingAudio()` 立即释放 UI；保留音频后显示 retry，调用 `transcribe(preservedAudio)` 重识别上一段 PCM。`cancel()` 语义保持不变，只有显式 abort 路径保留音频。
- 工具卡渲染重做，同步 iOS 最新工具卡/对话/文件夹/todo 设计。全部落在 `ui/chat/ChatMessageContent.kt`，分类逻辑已先期抽到 `ui/chat/ToolCardClassifier.kt`（纯逻辑、可单测，对应 iOS `ToolCardClassifier.swift`）。
- 说话区分：assistant 回复顶部加 "OpenCode" 文字标题（`primary` 电蓝 + SemiBold，`testTag("assistant.header")`）；用户蓝左竖条、assistant 末尾模型小字保持不动。
- 工具卡两形态：`MessageRow` 攒连续 tool/patch run 后用 `ToolCardClassifier.split(run)` 拆成 fileParts + otherParts。fileParts 渲染成新 `FileCard`（doc 图标 + monospace basename + chevron），用 `chunked(2)+Row` 手动两列网格（Android 不能在 LazyColumn 嵌 LazyVGrid）；otherParts 合并成 `ToolCallsRow`（"N tool calls" 可展开行，展开复用 `ToolCard` 主体）。
- 文件夹卡：`FileCard` 在 `isDirectoryRead` 时切 folder 图标，点击弹 `ModalBottomSheet` 列出 `parseDirectoryEntries(toolOutput)` 的 entries（子目录排前），不调 API。
- todo 抽离：新增 `TodoListInline` composable（对齐 iOS `TodoListInlineView`）；`todowrite` 展开时只显示 todo、隐藏 input/output。
- testTag 覆盖关键元素：`assistant.header`、`toolcard.file.<basename>`、`toolcard.folder.<basename>`、`toolcard.folder.sheet.<basename>`、`toolcard.folder.entry.<name>`、`toolcard.toolcalls`。本仓库暂无 iOS 那套 UITEST fixture 机制，UI 靠 testTag + 代码审查，分类逻辑由块 A 的 `ToolCardClassifierTest` 单测覆盖。
- 文档：`docs/design.md` 增补「工具卡渲染重做（已实现 — 对齐 iOS）」一节（说话区分、2 列文件卡、N tool calls 合并行、文件夹卡、todo 抽离四类改动）。
- 验证：`./gradlew :app:assembleDebug` 与 `:app:testDebugUnitTest` 均通过（仅余既有 `OpenInNew` AutoMirrored 弃用 warning，非本次引入）。未做 git，未上真机。

## 2026-05-25

- 对齐 iOS 最终 realtime speech 方案：Android 语音输入从停止后 M4A 解码上传，改为点击麦克风后立即使用 `AudioRecord` 采集 PCM16 mono 24kHz chunk。
- 新增 `RealtimeSpeechAudioCache`，每个 chunk 先写入临时 `.pcm` cache；新增 `RealtimeSpeechStreamer`，负责首次 session attach replay、heartbeat、send/commit 失败恢复，以及 stop 前等待 recovery 完成。
- `AIBuildersAudioClient` 新增 live realtime session path：创建 session 后连接 WebSocket、等待 `session_ready`、发送 binary PCM、commit/stop 收 transcript；WebSocket ticket query string 在日志中 redacted。
- `MainViewModel` 的录音流程改为立即开始本地 PCM capture，后台异步创建 AI Builder session 并从 cache offset 0 replay；停止录音时停止 capture 和 heartbeat，再 commit/stop 更新输入框。
- 测试补充 PCM cache append/read/remove、realtime 常量、WebSocket URL redaction。
- 验证：先修复本轮开始前已有的 `DataUriImageTransformer.kt` 注解拼写错误（`@Composableg` → `@Composable`），随后 `./gradlew testDebugUnitTest` 与 `./gradlew koverHtmlReport` 均通过。覆盖率报告位于 `app/build/reports/kover/html/index.html`。
- Android 真机验证通过后合并 PR #36，并准备 GitHub Release `v0.1.20260526`：`versionCode` 升至 8，`versionName` 升至 `0.1.20260526`。

## 2026-05-03

- 模型预设里的 GLM 选项从 `GLM-5-turbo` / `glm-5-turbo` 更新为 `GLM-5.1` / `glm-5.1`，对齐 iOS 客户端。
- 修复 Markdown 图片后紧跟 caption 时的渲染问题：`MarkdownImageResolver.normalizeStandaloneImageBlocks()` 会在渲染前把单独一行图片与下一行非空文本分开，避免 renderer 把图片当成 paragraph 内 inline image，导致图片尺寸异常。File Preview、Chat 消息和 Reasoning 卡片渲染前统一应用该 normalizer，并新增 3 个单元测试覆盖 caption、已有空行和真正 inline image 三种情况。验证：`./gradlew testDebugUnitTest` 通过。

## 2026-05-02

- 修复 speech recognition 失败时丢失 partial transcript：`launchSpeechTranscription()` 原来在 WebSocket 转写失败或异常时把 `inputText` 回滚到录音前的 `existingInput`，用户已经看到的流式识别文本会被覆盖掉，空草稿场景表现为输入框被清空。现在失败路径通过 `speechFailureInput()` 优先保留当前输入框里的 partial transcript，只有没有 partial 时才回退到原始输入。
- 新增 `SpeechRecognitionTest` 覆盖失败恢复逻辑：有 partial 时保留 partial，没有 partial 时保留原草稿。验证命令：`./gradlew testDebugUnitTest` 与 `./gradlew koverHtmlReport`。

## 2026-04-28

- 默认模型切换到 GPT：将运行时默认模型索引从 DeepSeek fallback 改为 GPT 预设，新安装和未保存过模型选择的 session 默认发送 `openai/gpt-5.5`。
- GPT 预设升级：`ModelPresets.list` 中的 `GPT-5.4` / `gpt-5.4` 更新为 `GPT-5.5` / `gpt-5.5`，并同步更新 AppState 测试。

## 2026-04-23

- Model 列表更新：删除 Opus 4.6 和 Sonnet 4.6，添加 DeepSeek (`deepseek/deepseek-v4-pro`)。`ModelPresets.list`、`ModelOption.shortName`、对应测试同步更新。对齐 iOS 客户端改动。

## 2026-04-16

- Session list 增加副标题：在标题下方显示相对时间（如 "5 min ago"）和 session 状态标签（Running/Retrying/Idle），对齐 iOS 客户端行为。使用 `DateUtils.getRelativeTimeSpanString` 做本地化相对时间格式化。数据复用已有的 `session.time.updated` 和 `sessionStatuses`，无需额外 API 请求。
- 新增 3 个 `SessionListInstrumentedTest`：验证有 `time.updated` 的 session 显示相对时间副标题，有 busy/idle 状态的 session 显示对应状态标签。

## 2026-03-30

- 模型预设里的 GLM 选项从 `GLM-5.1` / `glm-5.1` 切换回 `GLM-5-turbo` / `glm-5-turbo`，保持 iOS 与 Android selector 一致。

## 2026-03-27

- 模型预设里的 GLM 选项从 `GLM-5-Turbo` / `glm-5-turbo` 更新为 `GLM-5.1` / `glm-5.1`。

## 2026-03-19

- 默认发送模型从 `zai-coding-plan/glm-5` 切换为预设里的 `openai/gpt-5.4`；新安装和未保存过模型选择的 session 会直接使用 GPT-5.4。
- 模型预设里的 GLM 选项已同步改为显示 `GLM-5-Turbo`，并将底层 model ID 从 `glm-5` 更新为 `glm-5-turbo`。

## 2026-03-22

- 修复 Markdown 内嵌图片渲染：新增 `MarkdownImageResolver`，在渲染前把相对路径图片读取为 base64 data URI，Files 预览与 Chat 消息均可显示嵌入图片。
- 修复 HTTPS Markdown 图片在 Android 端仅显示 placeholder 的问题：`DataUriImageTransformer` 现在支持远程 URL，并通过按 URL 的可观察缓存触发 Compose 重组；`Cursor Bench Benchmark Analysis` 已实机确认恢复显示。
- 修复文件预览与聊天 Markdown 的图片预取路径，移除无效的 `imageVersion` 状态和排障临时日志，重新验证 `./gradlew assembleDebug` 与 `./gradlew testDebugUnitTest` 均通过。
- 修复 Files 预览中的本地 Markdown 图片路径：当预览文件路径是缺少前导 `/` 的 Unix 绝对路径时，`MarkdownImageResolver` 现在会恢复正确的绝对路径，避免把 `Users/...` 这类路径误当成 workspace 相对路径传给 `/file/content`，导致图片节点只有占位没有内容。新增 `MarkdownImageResolverTest` 回归用例覆盖该场景，并验证 `./gradlew :app:testDebugUnitTest --tests com.yage.opencode_client.MarkdownImageResolverTest` 与 `./gradlew :app:compileDebugKotlin` 通过。
- 补上 Files 预览的 workspace 上下文：`FilesScreen` 现在会把 `sessionDirectory` 传给 `FilePreviewPane`，并在解析 Markdown 内嵌图片时先把当前文件路径转成 workspace 相对路径，再交给 `MarkdownImageResolver`。这修复了文件预览场景里图片解析脱离 session 根目录的问题。新增 `FileNavigationUtilsTest` 回归用例，并验证 `./gradlew :app:testDebugUnitTest --tests com.yage.opencode_client.MarkdownImageResolverTest --tests com.yage.opencode_client.FileNavigationUtilsTest` 与 `./gradlew :app:compileDebugKotlin` 通过。
- 新增 `docs/code_review.md`，完成一轮中文系统性代码审查，覆盖架构、安全与测试问题，并整理后续修复优先级。

### Code Review 修复（Sprint A/B/C）

**Sprint A — 数据层加固**
- 新增 `FilesViewModel`（Hilt 注入），将 FilesScreen 的 6 次直接 repository 调用移入 ViewModel，状态通过 StateFlow 暴露，FilesScreen 不再接收 OpenCodeRepository 参数
- 移除 Composable 参数链中的 repository 透传：ChatScreen、PhoneLayout、TabletLayout 不再传递 repository，改用 `viewModel.repository`（MainViewModel.repository 从 private 改为 internal）
- MainActivity 不再持有 `@Inject lateinit var repository` 字段
- Repository 单元测试从 7/26（27%）提升到 43 个测试覆盖全部 26 个方法
- 新增 `FilesViewModelTest`（6 个测试）

**Sprint B — 架构优化**
- AppState 新增 6 个逻辑子状态 data class（ConnectionState、SessionState、ChatState、SpeechState、FileUiState、SettingsState），通过派生属性暴露；原有 flat API 不变，纯增量改动
- MainViewModel 补充 10 个测试：abortSession、deleteSession、updateSessionTitle、respondPermission、loadPendingPermissions、replyQuestion、rejectQuestion、testConnection 30s 防抖、SSE 事件（message.created、question.asked、question.rejected）

**Sprint C — 体验优化**
- testConnection 添加 30 秒防抖，避免屏幕旋转、后台切换时触发 5+ 次冗余网络请求和 SSE 重连
- ChatTopBar 23 个参数归并为 `ChatTopBarState`（14 字段）+ `ChatTopBarActions`（9 回调），调用端从 22 行参数传递简化为结构化构造

## 2026-03-17

- 全局 oh-my-opencode.json 默认 agent 从 GLM-5 切换为 sisyphus ultraworker（Claude Opus 4.6）。
- Gemini model ID 修正：`google/gemini-3-flash` → `google/gemini-3-flash-preview`，`google/gemini-3-pro` → `google/gemini-3.1-pro-preview`（Gemini 3 Pro 已于 3/9 下线）。

---

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
- 实现 Fork Session 功能：在 assistant 消息末尾 model 标签旁添加 "..." 菜单，支持从指定消息处 fork 对话为新 session。

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

---

## Quiet Tech 视觉设计语言落地（2026-05-29）

把 iOS client 已落地并验证的 "Quiet Tech 冷静科技感" 设计语言移植到 Android，与 iOS 视觉严格对齐（详见新建的 `docs/design.md`）。

**地基（theme）**
- `ui/theme/Color.kt`：新增 Quiet Tech token——电蓝 `BrandPrimary #3B82F6`、`BrandPrimaryLight #2563EB`、`BrandGold #D9A621`（仅"AI 工作中"）、近黑 `BgDark #0B0C0E` / `SurfaceDark #1A1D21` / `ComposerDark #141619` 及对应浅色 token、中性文字/分隔线。保留 diff/git 状态色。
- `ui/theme/Theme.kt`：**关闭 Material You 动态取色**（移除 `dynamicColor`），改用写死的电蓝 light/dark `ColorScheme`，并把 `surfaceVariant`/`surfaceContainer*`/`primary`/`tertiary`/`outline` 等槽位映射到 Quiet Tech token——品牌色在所有设备固定为电蓝，不跟壁纸，和 iOS 决策一致。

**UI（5 个组件，与 iOS 一一对应）**
- `ChatInputBar.kt`：composer 收成单圆角 pill（`surfaceContainerLow` 底、无描边文本框）；mic 框内左（灰/录音红/转写转圈）；send 实底电蓝圆角方块**始终在**（`!canSend` 时 alpha 0.35），stop 仅 busy 时**堆叠在 send 下方**的实底红方块（不替换 send）。`ChatPermissionCard` 改中性 surface + 3dp 电蓝左色条 + 纯文字按钮。
- `ChatMessageContent.kt`：用户消息 3dp 电蓝左色条 + muted 蓝底；工具卡/patch 卡/reasoning 卡中性 `surfaceVariant` 底，但**可交互元素（图标/工具名/文件路径/chevron/OpenInNew）电蓝**作可点暗示（不把整卡灰掉）；去掉 write/patch 特殊蓝底和橙色；保留 `run.chunked(2)` 两列网格。
- `SessionList.kt`：选中行 3dp 电蓝左色条 baked 进 12dp 圆角选中底（`primary` @8% alpha）并 clip 在圆角内，不戳出、不与展开 chevron/缩进冲突；去掉交替条纹。
- `ChatTopBar.kt`：model 选择器从实底蓝胶囊改描边 chip（透明底 + 电蓝边 + 电蓝字）；toolbar 图标中性灰、新建动作电蓝；context ring 进度电蓝。
- `SettingsSections.kt`：Theme 改分段控件（`SingleChoiceSegmentedButtonRow`），选中段电蓝。

**验证**
- `./gradlew compileDebugKotlin`、`testDebugUnitTest` 通过。
- 3 个 UI instrumented test 类（`ChatInputBarInstrumentedTest` / `SessionListInstrumentedTest` / `SettingsSectionsInstrumentedTest`，共 9 个 test）在真机上通过——确认 restyle 没破坏 send/stop/speech contentDescription、session 行文字/状态、settings 文字/enabled 逻辑。
- 模拟器深色截图确认：电蓝品牌色生效（无 Material You 紫）、toolbar 描边 model chip、底部 tab 电蓝选中、Settings 分段 Theme 控件、实底电蓝按钮。
- 注：真机连 `127.0.0.1` 连不到跑在宿主机的 OpenCode server；模拟器走 `10.0.2.2:4096` 映射宿主 localhost。有内容的 Chat（消息/卡片）截图未强验，靠 instrumented test 对 composer 的覆盖 + 编译 + 代码审查。

**视觉走查后的迭代修正（同日，模拟器逐项验收）**
- **品牌蓝定版**：低饱和实验（`#5E8BCC`/`#4574B5`）显得 send 按钮太低调，最终回到鲜艳电蓝 `#3B82F6`，**浅色深色用同一个值**（与 iOS 一致，按钮不退到背景里）。`Color.kt` 的 `BrandPrimary` 与 `BrandPrimaryLight` 都为 `#3B82F6`。
- **composer 布局对齐 iOS**：曾误改成"文本框在上、图标行在下"的上下两段式，反而割裂。改回与 iOS `ChatTabView` 完全相同的**单行横排**：`Row(verticalAlignment = Bottom)` → mic（左）| 文本框（`weight 1f`，无独立背景，共享 pill 底）| send/stop 列（右），整体一个圆角 pill。图标与文字同在 pill 内、底部对齐——iOS 本就如此，不是 bug。
- **文本框约三行高**：`heightIn(min = 66.dp, max = 132.dp)`（≈3 行起、≈6 行封顶后内部滚动），Box `TopStart` 对齐让多行从顶部起排。
- **send/stop 顺序**：send 始终在**上**、stop 仅 busy 时叠在**下**（修掉了曾经 busy 时渲染两个 stop 的 bug）。send 始终可发——无需先终止再发送。
- **stop 红定版**：Material 默认 error 红 `#B3261E` 又深又闷，改 `StopRed #E5484D`（提亮、纯度略降，近 iOS 系统红），录音态 mic 也用此红。
- **测试环境**：API 36 模拟器上 espresso `3.6.1` 触发 `InputManager.getInstance` `NoSuchMethodException`（旧版 espresso 与 Android 16 注入不兼容）。升级 `espressoCore 3.6.1 → 3.7.0` 修复。**全部 15 个 instrumented test 在 emulator-5554 通过**（`am instrument` 限定设备，不装真机以免破坏 credential）；单元测试 + `assembleDebug` 通过。

## 2026-07-31: GPT Live host lifecycle race closure

- 语音 attempt 现在同时 snapshot Chat session ID、原 composer prefix 和 strategy。Realtime final、Grok upload final、preserved retry final 只更新来源 session；若用户已切到另一 Chat，则把完整结果写回来源 session draft，不覆盖当前 composer。切换 session 时会停止仍在录音/启动中的 attempt，并让 preserved artifact 保留来源 session ownership。
- 新增 attempt-scoped speech typewriter。partial snapshot 在实际写入时再次检查 attempt 与来源 session；切 session、新 attempt、final/error 都会关闭旧 writer，已排队的旧 partial 不能盖掉新 partial/final。
- Grok background cleanup 先把 WAV ownership 交给 cleanup，再 `cancelAndJoin` 原 upload job，最后才 preserve/delete。`SpeechRecordingFileOwner` 保证 attempt 与 cleanup 只有一方能取得文件，避免 cancellation `finally` 先删 WAV。
- PCM sender callback 改为 bounded nonblocking `trySend`，不再在 AudioRecord callback 上等待 Kit。buffer full 或 drain timeout 会停止 sender、保留完整 WAV，并显示明确的 saved-for-retry 错误；正常 Stop 仍在 commit 前按序 drain。
- Host `SpeechSessionOwner` 继续用 mutex memoize 单次 terminal result，与当前 Kit 的 atomic `AudioDisposition` terminate 行为叠加后，background/finally/abort 的重复 termination 不会重复 cancel/preserve。
- 验证：`testDebugUnitTest` 308 tests 全部通过；新增 session switch、stale typewriter、Grok cancel-and-join、active retry discard join、PCM saturation tests。VoiceFlowKit JitPack 已 pin 到 feature commit `54141fbb46ae495c6787a5de9800a30cae085f3d`，Kit 发布后、合并前替换为 exact `0.4.0`。
- `compileDebugKotlin`、`compileDebugUnitTestKotlin`、`assembleDebug` 通过。`compileDebugAndroidTestKotlin` 仍被既有 `ReadToolCardIntegrationTest.kt:151` 阻塞：调用缺少 `completedTurnActivities` 和 `onMarkdownLinkClick`；该错误与 speech 改动无关，本轮未扩大 scope 修复。
- 本地 composite sibling `:voiceflowkit:testDebugUnitTest` 全部通过，确认 host mutex memoization 与 Kit 当前 atomic `AudioDisposition` termination contract 兼容。
