# Session Status Line 设计（session 级状态行）

状态：已实现（`feat/session-status-line`）。创建于 2026-09-30。

## Bottom Line

在 `ChatInputBar` 上方加一行常驻状态行，为当前 session 显示四段：`⟳ rounds · 🔧 tool calls · 1.07M tok · 96% cache hit`（无数据段隐藏）。这是 iOS 同功能（`opencode_ios_client/docs/features/session_status_bar/design.md`，已上线）的 Android 移植，口径逐条对齐：

- **tokens / cache hit**：服务端 session 对象自带 `tokens`/`cost` 累计值（`/session` 列表与 `session.updated` SSE 都有，iOS 已在 live server 实测确认）。Android 侧只需给 `Session` 模型补解析（此前反序列化丢弃了这两个字段），数据经既有两条通路（REST `getSessions`、SSE `session.updated` upsert）自动流入 `AppState.sessions`，零新增网络请求。subagent 子 session 用量按 `parentID` 递归并入主数字（父聚合是 self-only，客户端求和即正确）。
- **rounds / tool calls**：服务端无现成聚合，照 iOS 方案本地持久化「计数 + 已见 ID 集合」（`SessionStatsStore`，SharedPreferences 后端），增量来自 SSE 事件，每次 `loadMessages` 用 message 窗口对账，revert 后重 seed。
- 挂载点：`ChatScreen.kt` 根 `Column` 内、`ChatInputBar` 之前；现有 `composerStatus`（Thinking/语音/耗时）保持原样作为下行。

## iOS 是怎么做的（口径基准）

1. token 段优先取 session 聚合（`session.updated` 每 step 推送全量累计值）；host 不返回聚合时回退到「完整 message 窗口内 assistant tokens 求和」，窗口不完整则隐藏（宁缺勿假）。
2. cache hit = `cache.read / (input + cache.read)`，跨整个 subagent 树求和后计算；`input` 是未命中输入（服务端投影层保证 total = input + output + reasoning + cache.read）；`input > 0` 且无 cache 字段 = 真实 0%，不是未知。
3. rounds = user message 数，tool calls = `type == "tool"` 的 part 数（单位是 part 不是 message，一步可并行多个 tool part）。
4. rounds/tools 保持主 session 视角（与消息列表口径一致）；tokens/cache hit 并入全部后代 subagent（`parentID` 递归）。
5. 主 session 自身 token 不可知时，整行隐藏（含 rounds/tools，与 iOS `sessionTotalTokens` 的 nil 口径一致）。
6. 紧凑数字：<1000 原样；≥1000 用 K/M/B/T，值 ≥10 一位小数、<10 两位小数、去尾零（`950` / `85.2K` / `1.11M` / `2B`）；四舍五入可进位（`9999 → 10K`）。

## Android 现状与缺口（实现前盘点）

已有（不用动）：

- `Message.tokens`（`TokenInfo` 全字段）、`isUser`、`Part.isTool` — `data/model/Message.kt`。
- `Session.parentId` 已解析 — `data/model/Session.kt`。
- SSE 分发已覆盖 `session.updated` / `message.created` / `message.updated` / `message.part.updated` — `ui/MainViewModelSyncActions.kt`；`session.updated` 直接 upsert 完整 session 对象（`parseSessionUpdatedEvent` 解码 `info`/`session`），所以模型加字段后 tokens 自动流入。
- 派生状态模式：`AppState` 上的计算 getter（`contextUsage` / `throughputStats`）— `ui/MainViewModel.kt`。
- i18n：`values/strings.xml` + `values-zh/strings.xml` 双语镜像。

缺口（本次补齐）：

1. `Session` 不解析 `tokens`/`cost` → 单点修复。
2. 无紧凑数字 formatter。
3. rounds/tools 无本地计数设施（`messages` 只持有当前 session 的 30 条分页窗口）。
4. 无「窗口是否完整」信号 → 用 `messages.size < messageLimit`（请求 limit 条返回不足，即翻到了头）。

## 实现

### 数据层

- `Session` 增加可空字段 `tokens: Message.TokenInfo?`、`cost: Double?`（复用 `Message.TokenInfo`；kotlinx.serialization 可空字段对旧 payload 安全）。
- `/session` 列表与 `session.updated` 两条通路自动携带新字段，repository / API 层零改动。
- 子 session 的 tokens 同样来自 `AppState.sessions`（同一列表已含子 session 对象及其聚合值），**零新增网络请求**；子 session 无本地窗口，tokens 段对其仅认聚合。

### ViewModel 层

新增 `AppState` 派生 getter `sessionStats: SessionStats?`（`ui/MainViewModel.kt`，与 `throughputStats` 同模式）：

```kotlin
data class SessionStats(rounds: Int?, toolCalls: Int?, totalTokens: Int?, cacheHitRate: Float?)
```

计算规则（对齐 iOS `sessionTotalTokens` / `sessionCacheHitRate`）：

1. **子树求集**：`sessionGroupIds(sessions, rootId)` — 按 `parentId` 建 child map，BFS（`seen` 预置根 + 入队前判重，防环），返回 `[main, ...descendants]`。不复用 `buildSessionTree`（展示用树，职责不同）。
2. **main 基线**：聚合优先（`tokenTotal`：total 缺失时分量求和）；聚合缺失时用完整窗口（`messages.size < messageLimit`）内 assistant tokens 求和；两者都不可知 → 返回 null（整行隐藏，含计数）。
3. **后代累加**：逐子 session 加聚合 total（>0 才加）及 input/cache.read。
4. **cacheHitRate**：整树 `cacheRead / (freshInput + cacheRead)`，分母 0 → null。
5. **rounds / toolCalls**：读 `currentSessionCounts`（store 的当前 session 计数投影），主 session 视角。
6. `totalTokens` 为 0 时该段隐藏（`takeIf { it > 0 }`）；`cacheHitRate == 0f` 是真实值（显示 0%），不隐藏。

新增 `SessionStatsStore`（`@Singleton`，Hilt 注入 `@ApplicationContext`，SharedPreferences 存 JSON，per-session 键 `session_stats_<id>`）：

| 字段 | 类型 | 作用 |
|---|---|---|
| `rounds` | Int | user message 计数 |
| `toolCalls` | Int | tool part 计数 |
| `seenUserMessageIds` | Set\<String\> | 跨重启去重 + 窗口对账 |
| `seenToolPartIds` | Set\<String\> | 同上，part 粒度 |

规则与 iOS 一致：`recordUserMessage` / `recordToolPart` 只对新 ID 增计（事件重放/重连不重复）；`seedOrReconcile` 无 entry 时按窗口 seed、有 entry 时只增未见的窗口 ID（只增不减）；`reset` 丢弃 entry（revert 截断历史后由重载窗口重 seed）。

接线（`MainViewModel` + `MainViewModelSyncActions`）：

- **SSE 钩子**（`handleIncomingSseEvent` 新增 `onRecordUserMessage` / `onRecordToolPart` 两个回调，限定 `sessionId == currentSessionId`）：
  - `message.created` / `message.updated`：解析 payload `info`（`parseMessageInfo`），`role == "user"` → record。两个事件都记，下游 seen 集合去重，payload 形态差异（哪个事件带 `info`）不影响正确性。
  - `message.part.updated`：`parseMessagePartDeltaEvent` 已有 `partType`/`partId`，`partType == "tool"` → record。
- **窗口对账**：`loadMessages` 与 `loadMoreMessages` 完成后调 `reconcileSessionCounts`（窗口 user message ID 集 + tool part ID 集 → `seedOrReconcile` → publish）。
- **revert**（`editFromMessage`）：`sessionStatsStore.reset(sessionId)` 后再 `loadMessages` 重 seed。
- **切 session**：`selectSessionState` 清 `currentSessionCounts`（防上一 session 计数残留），`selectSession` 调 `publishCurrentSessionCounts` 从 store 回填。
- host 切换（`resetRuntimeForHostSwitch`）一并清空 `currentSessionCounts`。

### UI 层

- `ui/chat/ComposerStatusBar.kt`（新文件）：`compactTokenCount(Long)`（iOS 口径逐字移植）+ `ComposerStatusBar(...)` composable，**单行两段**：左侧常驻计数段（`Refresh`/`Construction` 图标 + 数字、`1.11M tok`、`96% cache hit`，`·` 分隔），右侧临时活动段（busy 时 gold 点 + 活动文案 + `mm:ss` 计时 + `⋮` 中断菜单；语音状态与活动文案按旧规则 `·` 拼接，如 `Agent running · Transcribing`）。两侧都空时整行不组合。上边距 4dp / 下边距 2dp，样式 labelMedium / onSurfaceVariant。
- 挂载：`ChatScreen.kt` 内 `if (state.currentSessionId != null) { ComposerStatusBar(...) }`，位于 `ChatInputBar` 之前（messages 区是 `weight(1f)`，固定行自然钉在 composer 上方）。原嵌在 `ChatInputBar` 内部的 `QuietComposerStatus` 临时行已删除并并入此栏（Android 与 iOS 的两行布局有意不同：iOS 保持两行，Android 合并为一行——计数左、活动右，busy/语音行为不变）。
- tok 与 cache hit 为纯文本（对齐 iOS 放弃易误读图标的决定）；图标用 Material Icons（依赖已有 `material-icons-extended`）。

### i18n

`values/strings.xml` + `values-zh/strings.xml` 各加四个 key（对齐 `chat_context_*` 命名）：`chat_status_rounds`（rounds/轮数）、`chat_status_tool_calls`（tool calls/工具调用）、`chat_status_tokens`（tok/token）、`chat_status_cache_hit`（cache hit/缓存命中）。rounds/tools 段只显示图标+数字，label 走 contentDescription；tok / cache hit 段 label 参与显示。

### 测试（`./gradlew testDebugUnitTest`，449 全绿）

- `SessionStatusFormatterTest`：`compactTokenCount` 边界（0/950/999/1K/1.01K/9.54K/进位 10K/85.2K/1.11M/9.99M/10M/2.1B/1.23T）。
- `SessionStatsStoreTest`（`FakeSharedPreferences` 内存 fake，`testSessionStatsStore()` 共享 helper）：seed 幂等、record 去重、对账只增未见、reset 重 seed、per-session 隔离、跨 store 重建存活（重启模拟）。
- `AppStateSessionStatsTest`：子树求和、整树 cache 分母、聚合缺失+窗口不完整整行隐藏、完整窗口回退、fresh session 零聚合只显示计数、input 无 cache = 0%、`parentID` 成环终止、无关 session 不并入、current session 不在列表/无 current session 隐藏、`hasVisibleSegments`。
- `SseSessionStatsHooksTest`：`handleIncomingSseEvent` 直测——user 事件（created/updated）记录、assistant 不记录、非当前 session 忽略、tool part 记录而 text/reasoning 不记录、缺 `info` payload 静默忽略。
- 既有 `MainViewModelTest` / `NfcQuickPromptTest` / `ForkSessionTest` 的 `MainViewModel` 构造补 `testSessionStatsStore()` 参数。

## 与 iOS 的口径差异（有意保留）

- 持久化后端：SharedPreferences(JSON) vs UserDefaults，语义一致。
- 图标：Material Icons vs SF Symbols；tok / cache hit 同样是纯文本。
- 行数：iOS 是两行（常驻计数行 + 临时活动行），Android 合并为一行（计数左、活动右）；内容口径一致。
- Context 弹窗（`ContextUsageDialog`）保持 `%,d` 全量数字，与 iOS 弹窗行为一致。
- master 分支的 SSE 数据通路是「事件触发 REST 刷新」（SSE payload 直写是另一条 feature 分支的工作），所以 rounds/tools 的增量记录挂在 `handleIncomingSseEvent` 的既有事件分支上，不依赖 payload 直写；两分支合流后口径不变（seen 集合去重）。
- 无 stepTimings 持久化（iOS 侧的独立附带项，不属于本 feature）。

## 不做

- 服务端加聚合列（user_messages / tool_calls）——保持客户端方案，对所有 OpenCode 兼容 host 一致。
- cost 段的 UI 显示（`cost` 字段已解析，留给后续）。
- 子 session 窗口加载（子 session tokens 只认聚合；出分页窗口少算是已声明的接受项）。
- 消息 footer 改动（保持 `model | t/s` 原样）。
- `buildSessionTree` 重构（展示用树与求集逻辑职责不同，不合并）。

## iOS 源码参照索引

- `OpenCodeClient/AppState.swift` — `sessionGroup(including:)`、`sessionTotalTokens`、`sessionCacheHitRate`、`cacheHitRate`（求和与隐藏口径的唯一事实源）。
- `OpenCodeClient/Views/Chat/ChatTabView.swift` — `sessionStats` 组装、`cacheHitRateText`、状态行挂载与段拼接。
- `OpenCodeClient/Views/Chat/MessageRowView.swift` — `compactTokenCount` 紧凑格式化。
- `OpenCodeClient/Stores/SessionStatsStore.swift` — 持久化计数字段与规则。
- `OpenCodeClientTests/SessionStatusStatsTests.swift` — 格式化与求和/隐藏口径的全部边界用例（移植测试时逐条对照）。
- `docs/features/session_status_bar/design.md` — iOS 完整设计（含 live server 实测事实与时间线）。
