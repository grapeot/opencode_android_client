# Plan: SSE 事件数据通路（SSE 作为消息状态的数据通路 + 移除 busy polling）

Status: implementation-ready（step 2 调研完成，所有file:line基于当前树`docs/sse-event-data-path`复核；可开工）

姊妹文档：`opencode_ios_client/docs/features/sse_event_data_path/design.md`（同一设计的服务器端契约完整版本与iOS实现规范）。本文档专门针对Android实现进行描述；服务端的详细契约仅列出与Android相关的部分，其余细节参考iOS文档。

## 1. Bottom Line

Android 的现状是："SSE 触发 REST 补刷"为主：`message.created/updated` 用 400ms debounce 全量 REST + `loadSessions`；`message.part.updated` 仅在带**顶层 `delta`**（dsh shim 形状）时用 in-memory 流式，**native server 形状一律走 REST 补刷**；有 2s busy polling（用户痛点：双拉+UI 抖动）；**SSE 重连后无任何显式对账**。本 plan：

1. **SSE 作为数据通道**：`message.part.updated`（完整 part）和 `message.updated`（完整 info）直接 upsert 到 `messages`；**新增 `message.part.delta` 事件分支**（native server 的流式文本，目前被无 else 的 `when` 静默丢弃）——在 native host 上 Android 也有了流式文本；payload 完整性门禁：shim 瘦形状回退现行路径。
2. **去掉 `launchBusyPolling`**（busy 期间每 2s `loadMessages`）。
3. **心跳看门狗 + 重连 bootstrap**：`server.heartbeat`（`/global/event` 保证 10s 一帧，源码验证）刷新时间戳，静默 > 20s 触发一次对账；`EventSourceListener.onOpen`（现状未覆写）回调触发重连后 `loadMessages + loadSessionStatus` 对账。这两项替代 busy polling 的兜底职能，且补齐 Android 缺失的重连回补。
4. **保留**：in-memory 流式文本机制（`streamingPartTexts`，扩展到 native server）、发送时乐观 busy（REST ack 后写 busy + 请求前 `sendingSessionIds`）、`session.status` idle 时的现有对账（Android 已有，保持）。

* 预期：per-event `getMessages` 归零（每 turn 1 次 idle 对账 + 重连 bootstrap）；busy 期间零 2s 双拉；流式文本覆盖 native host；静默死亡 ≤25s 自愈。

**UI 现状评估（UI 层缺口，与数据通路独立）**：Android 当前的 running 状态体现（composer 行计时 + tool 卡状态）可用（需求里观察到的基线），但数据源是 REST 补刷，running 状态展示有 debounce + 往返延迟；且 tool 行与 iOS 同为折叠行形态，行内没有显式的 per-tool running 指示，running 表达本身也有限。本 plan 把数据源换成 payload 后，running 状态展示变事件驱动、即时；视觉升级（行内显式 running 指示：tool 名 + 已运行时长）同属 P2，与 iOS 同步另开，本 plan 不做。

## 2. 服务端事件契约（Android 承重项，摘要）

所有细节（端点分析，removed payload，顺序保证，busy/idle 模式，subagent 归属）在 iOS 文档「服务端事件契约」节中详述，此处只列 Android 实现直接依赖的：

- 订阅端点 `{base}/global/event`（现状即此，`SSEClient.kt:52`）：**保证**每 ~10s 一帧 `server.heartbeat`（`{payload:{type:"server.heartbeat",properties:{}}}`，普通 data 帧），idle/长 tool 期间照发；无界队列不丢帧；同 session 事件 FIFO 保序。
- `message.part.updated`：`properties.part` 完整 part 对象（tool 含 `state.status/input/output/time`，状态机 pending→running→completed 各推独立帧）；**无顶层 `delta` 字段**（native server）。dsh shim 的形状不同：part 瘦身为 `{messageID,id,type}` + 顶层 `properties.delta`。
- `message.part.delta`：`{sessionID, messageID, partID, field:"text", delta}`，text 与 reasoning 均 `field:"text"`；start（空 text part）→ delta* → end（完整 text part）顺序保证；end 帧幂等收敛。
- `message.updated`：`{sessionID, info: <完整 message info>}`。
- `message.part.removed`：`{sessionID, messageID, partID}`（无 part 对象）；`message.removed`：`{sessionID, messageID}`。
- `session.status`：一个 turn 内 busy 多帧、**idle 只在 turn 结束发一次**。
- `server.connected`：连接建立第一帧。
- 兼容 host（shim）的 `server.heartbeat` 未验证：看门狗退化为 20s 低频对账，不劣化（见 4.4）。

## 3. 客户端现状盘点（当前树，file:line 已复核）

| 位置 | 现状 |
|---|---|
| `SSEClient.kt:32-43` | `retryWhen` 指数退避 1s→30s 无上限；**无 onOpen/重连成功回调**，ViewModel 对重连零感知 |
| `SSEClient.kt:52` | 订阅 `{base}/global/event`（与 iOS 同端点） |
| `SSEClient.kt:64-88` | `EventSourceListener` 只覆写 onEvent/onClosed/onFailure；**onOpen 未覆写**（看门狗/ bootstrap 的挂点） |
| `MainViewModelSyncActions.kt:52-217` | `handleIncomingSseEvent`：`when(payload.type)` 11 分支，**无 else**——`server.heartbeat`/`message.part.delta`/未知 type 静默丢弃 |
| `MainViewModelSyncActions.kt:101-117` | `message.created`/`message.updated` → `onRefreshMessages`（400ms debounce REST）+ `onRefreshSessions`（REST `GET session`，**非当前 session 也触发**） |
| `MainViewModelSyncActions.kt:119-147` | `message.part.updated`：顶层 `properties.delta` 存在 → 追加 `streamingPartTexts`（in-memory）；否则清 streaming + REST 补刷。**native server 形状恒走 REST** |
| `MainViewModelSupport.kt:151-164` | `parseMessagePartDeltaEvent`：读顶层 `delta`（shim 形状解析器） |
| `MainViewModelSyncActions.kt:79-99` | `session.status`：upsert `sessionStatuses`；当前 session 变非 busy → 清 streaming + `loadSessions` + `loadMessages`（**idle 对账已存在，保持**） |
| `MainViewModelSyncActions.kt:16-30` + `MainViewModel.kt:2024-2028, 1345` | `launchBusyPolling`（2s，三重 skip 守卫）；唯一启动点 = `testConnection` health 成功 |
| `MainViewModel.kt:1390-1392, 1416` + `MainViewModelSessionActions.kt:61` | `launchLoadSessionStatus`：one-shot 按需；成功时**整体替换** `sessionStatuses` |
| `MainViewModelSessionActions.kt:542-593` | 发送：REST `prompt_async` ack 后写乐观 busy（:564）；请求前 busy 由 `sendingSessionIds` 承担（`ChatScreen.kt:135-136`） |
| `MainViewModel.kt:93-94, 183-184` | `streamingPartTexts: Map<String,String>`（key `"$messageId:$partId"`）+ `streamingReasoningPart: Part?` |
| `MainViewModelSessionActions.kt:176-179` 等 | streaming 清理点 4 处（idle / 缺 delta / 切 session / host 切换） |
| `ChatMessageContent.kt:513` | 渲染：`displayedText = streamingTextOverride ?: part.text ?: ""`（流式覆盖 part 自带 text） |
| `MainViewModelSessionActions.kt:219` | `launchLoadMessages` 自带 `sessionId == currentSessionId` 竞态保护（对账入口可复用） |
| `MainViewModelTest.kt` | 测试基建：MockK relaxed fake repository + 反射喂 fake SSE 事件（:188-192）+ `coVerify(exactly = N)` REST 计数断言（现成） |
| `MainViewModelSupport.kt:16-21` | `MainViewModelTimings`：`busyPollingIntervalMs=2000`、`messageRetryDelayMs=400` 等常量区（看门狗常量落点） |

## 4. 设计

### 4.1 事件 → apply 规格（`handleIncomingSseEvent` 改造）

| 分支 | 现状 | 改后 |
|---|---|---|
| `message.part.updated` | 顶层 delta → in-memory；否则 REST | **门禁分流**（下）：完整 part → 原地 upsert `messages`（零 REST）+ 清对应 `streamingPartTexts` 条目（完整帧覆盖）；shim 形状（顶层 delta + 瘦 part）→ **保持现路径**（in-memory delta / REST 补刷），行为回归断言 |
| `message.part.delta` | 无分支，静默丢弃 | **新增**：`field=="text"` → 追加 `streamingPartTexts["$messageID:$partID"]`；`partTypeIndex[partID]=="reasoning"` 时同步 `streamingReasoningPart` |
| `message.updated` / `message.created` | REST + `loadSessions` | `properties.info` 可解析为完整 `Message` → 原地 upsert（按 id，保留本地 parts）；**移除 `onRefreshSessions` 触发**（session 列表已有 `session.created/updated` 本地 upsert 通路）；info 缺失 → 回退现 REST 路径 |
| `message.part.removed` / `message.removed` | 无分支 | **新增**：按 `{sessionID, messageID, partID}` / `{sessionID, messageID}` 原地删除 |
| `session.status` | upsert + idle 对账 | **保持不动**（idle 对账已存在） |
| `server.heartbeat` | 静默丢弃 | **新增 case**：无动作（时间戳在 `handleSSEEvent` 入口统一刷，见 4.4） |
| 其余（`session.created/updated`、`permission.asked`、`question.*`、`todo.updated`、`session.error`） | — | 不动 |

**门禁（payload 完整性）**，`message.part.updated` 判定顺序（自上而下短路）：

1. 如果顶层 `properties.delta` 存在（shim 形状）→ 走现有 streaming/REST 路径（行为不变）。
2. 如果 `part.type == "tool"` 且 `state` 字段存在且能解码（`PartState` 的 `PartStateSerializer` 已经支持字符串和对象双形态）→ upsert。
3. 如果 `part.type ∈ {"text", "reasoning"}` 且 `text` 字段存在 → upsert（end 帧幂等收敛 delta 拼接）。
4. 其他情况 → 回退到现有的 REST 补刷路径（和今天的一样）。

* 误判安全性：shim 瘦 part 同时缺 `state` 与 `text`，不会误入 2/3；完整 part 解析失败落 4 = 现状行为，是安全方向。

* in-place upsert 长这样：

```kotlin
// messages 在 AppState（StateFlow），upsert = state.update { it.copy(messages = ...) }
private fun upsertMessagePart(state: MutableStateFlow<AppState>, messageID: String, part: Part) {
    state.update { s ->
        val msgs = s.messages
        val idx = msgs.indexOfFirst { it.info.id == messageID }
        val next = if (idx >= 0) {
            val row = msgs[idx]
            val parts = row.parts.toMutableList()
            val pidx = parts.indexOfFirst { it.id == part.id }
            if (pidx >= 0) parts[pidx] = part else parts.add(part)
            msgs.toMutableList().also { it[idx] = row.copy(parts = parts) }
        } else {
            // 壳行：仅 assistant 专属 part 类型（tool/reasoning/step-finish/patch）建行；
            // text/file 忽略（等 message.updated 或对账收敛，避免角色不明）
            msgs + MessageWithParts(info = shellInfo(messageID, s.currentSessionId), parts = listOf(part))
        }
        s.copy(messages = next)
    }
}
```

* `parseMessagePartUpdatedFull` (new, `MainViewModelSupport.kt`): `properties.part` JsonObject -> `Part` (kotlinx `json.decodeFromJsonObject`)；`parseMessageInfo`: `properties.info` -> `Message`。解析失败 -> 门禁回退路径
* `shellInfo(messageID: String, sessionId: String?)` (new, `MainViewModelSupport.kt` 纯函数): `Message(id = messageID, sessionId = sessionId, role = "assistant")` 其余字段默认 null——assistant 专属 part 先于 message 到达时的壳行（见 upsert 代码注释）；后续 `message.updated` info upsert 覆盖，REST 对账全量替换是最终收敛
* removed 处理（同 `state.update` 模式）：`message.part.removed` -> 定位 messageID 行，`parts.filterNot { it.id == partID }`（行内 part 全删光则保留空 parts 行，等对账/`message.removed`）；`message.removed` -> `messages.filterNot { it.info.id == messageID }` + `pendingOptimisticMessageIds` 同步移除该 id
* **`partTypeIndex: Map<String, String>`**（新增 `AppState` 字段，partID -> type，默认 `emptyMap()`，与 `streamingPartTexts` 同声明区 `MainViewModel.kt:93-94` 旁）：`message.part.updated` upsert 时经 `state.update` 写入（first-write-wins）；`message.part.delta` 分支读取以决定 `streamingReasoningPart`（delta 事件无 type 字段，text/reasoning 都 `field:"text"`，必须靠索引区分）。生命周期与 `streamingPartTexts` 一致：切 session / host 切换 / idle 清理处（`MainViewModelSessionActions.kt:176-179` 等 4 处）同步清空。不进 `ChatState`（UI 不直接渲染它）
* **optimistic user row**：发送用确定性 `msg_<uuid>`（`makeServerId`，`MainViewModelSessionActions.kt:595-596`），`mergePendingOptimisticMessages`（:186-197）是纯 id membership——upsert 语义与之对齐：服务端 user message 的 info upsert 覆盖本地（parts 保留本地直到 `loadMessages` 对账，与 iOS 相同取舍）；**info upsert 命中 `id ∈ pendingOptimisticMessageIds`（user message）时同步从该集合移除**（对齐 `mergePendingOptimisticMessages` 的 prune 语义）；服务端 part（非 temp id）到达 upsert 时不清 optimistic temp part（Android 的 optimistic parts 是 temp id，REST 对账时由 merge 逻辑收敛，行为与现状一致）
* sessionID 门控：消息级状态仅当前 session。现有分支保持现状内联 gate 位置不动（`MainViewModelSyncActions.kt:105,114,121`）；**新增分支（delta / removed）加同款内联 gate** `properties.sessionID == state.value.currentSessionId`（与 :121 同模式）；`server.heartbeat` 无 gate（任何帧都刷时间戳）

### 4.2 REST 降级为对账

`getMessages` 触发点收敛为：

| 时机 | 现状 | 改后 |
|---|---|---|
| `session.status → idle`（当前 session） | 有（:87-96） | 保持 |
| SSE 重连 bootstrap（`onOpen`） | **无** | **新增**（4.3） |
| 看门狗触发 | 无 | **新增**（4.4） |
| 手动 / 切换 session / 发送后双补刷 / 分页 / abort / revert / edit / `loadSessions` 成功后 | 有 | 保留不动（发送后双补刷 :583-589 保留——发送是显式动作，保留现节奏） |
| per-event（`message.created/updated/part.updated`） | 有（400ms debounce） | **移除**（门禁回退路径除外） |

* `getSessions` (`onRefreshSessions`)：删除 `message.created/updated` 分支的触发（session 列表已有 `session.created/updated` 本地 upsert）；其他触发点不变。**注意**：非当前 session 的每个 message 事件都会额外打一个 `GET session`（gate 缺口，`MainViewModelSyncActions.kt:104,113`）——移除 per-event 触发后自然消失。

### 4.3 重连 bootstrap（新增，补 Android 缺失的回补）

现状：`retryWhen` 重连后 ViewModel 无感知，状态回补仅靠 busy polling（条件受限）/后续事件/前台 `testConnection`（30s 节流）。改造：

- `SSEClient.connect(baseUrl, username, password, onConnected: (() -> Unit)? = null)`：在 `EventSourceListener.onOpen` 中实现 `onConnected?.invoke()`。注意：OkHttp每次（重）建一个EventSource，收到任何响应头都会触发onOpen，所以语义上是每次新连接都会触发一次。
- `OpenCodeRepository.connectSSE()`（`OpenCodeRepository.kt:240`）：增加同名参数，并在`sseClient.connect`中透传——改动面就是这两处签名和透传（`connectSSE`的唯一调用方是`startSSE`）。
- `startSSE()`（`MainViewModel.kt:2030-2033`）：注入回调：`loadMessages(currentId, false)`（自带竞态保护，`MainViewModelSessionActions.kt:219`）+ `loadSessionStatus()`（one-shot）。
- 首次连接也走同一路径（onOpen 首连触发）——与现状 `testConnection → loadInitialData` 重叠一次，可接受（幂等），或实现时若嫌重可加「首连跳过」标记（`hasConnectedOnce`），默认实现先不做。

### 4.4 心跳看门狗（替代 busy polling 的静默死亡恢复）

- **时间戳**：在 `handleSSEEvent` 入口统一设置 `sseLastFrameMs = System.currentTimeMillis()`（任何帧，包括 heartbeat）。`sseLastFrameMs` 是 `MainViewModel` 的一个 private 字段（在原 `pollJob` 旁边，`MainViewModel.kt:439`），初始值是 0（0 表示没有帧，watchdog 不触发）。
- **检查与生命周期**：watchdog 循环与原 busy polling job 一样有相同的生命周期（`hostRuntimeScope`）——`testConnection` health 成功分支（`MainViewModel.kt:1344-1347`，原 `startBusyPolling()` 调用处）随 `startSSE()` 一起 launch。取消点与原 `pollJob` 完全一致（host 切换 `MainViewModel.kt:1545`，`onCleared` `:2048`）。5s 周期：

```kotlin
scope.launch {
    while (true) {
        delay(MainViewModelTimings.watchdogCheckMs)        // 5000
        val last = sseLastFrameMs
        if (last > 0L && System.currentTimeMillis() - last > MainViewModelTimings.watchdogSilenceMs) { // 20000
            sseLastFrameMs = System.currentTimeMillis()
            _state.value.currentSessionId?.let { sid ->
                loadMessages(sid, false)                    // 竞态保护内置
                loadSessionStatus()                          // status 一并纠正
            }
        }
    }
}
```

- **阈值 20s** = 2 个心跳周期（服务端 10s 硬编码）；最坏恢复 25s。常量进 `MainViewModelTimings`（`MainViewModelSupport.kt:16-21`）。
- **不是轮询**：正常时检查恒 no-op；无心跳兼容 host 上退化为静默期 20–25s 一次对账，频率远低于现状 busy polling（2s），无回归。
- **与 4.3 分工**：onOpen 管「连接断了又连上」（立即对账）；看门狗管「连接活着但没帧」（代理缓冲丢帧等）。
- **busy 清除的连带改善**：现状 idle 事件丢失时 busy 无超时兜底（靠 polling/loadSessionStatus 间接纠正）；看门狗对账含 `loadSessionStatus`，≤25s 纠正。
- 单测：反射预置 `sseLastFrameMs` + `runTest { advanceTimeBy(25_000) }` → `coVerify(exactly = 1) { repository.getMessages(any(), any()) }`；fresh 时间戳 → 0 次。

### 4.5 移除 busy polling

删除（4 处，全清单已核）：

1. `launchBusyPolling` 函数（`MainViewModelSyncActions.kt:16-30`）
2. `startBusyPolling`（`MainViewModel.kt:2024-2028`）
3. 唯一调用点（`MainViewModel.kt:1345`，`testConnection` health 成功分支）
4. `pollJob` 字段及生命周期引用（`MainViewModel.kt:439, 1545, 2048`），以及间隔常量 `busyPollingIntervalMs`（`MainViewModelSupport.kt:20`）——删除后以编译 + 全量测试确认无残留引用（调研已核无测试引用该常量）

- **无测试依赖**：全测试目录无断言轮询行为的用例（grep `busyPolling|busy.*poll` 无命中，已核）。
- `docs/working.md:465-478` 是历史工作记录（archive 性质，只进不改），不改写；本 plan 文档即为移除记录。
- 兜底职能转移：数据及时性 → 4.1 payload 直供；静默死亡 → 4.4 看门狗；重连回补 → 4.3 onOpen。

### 4.6 保留项与明确不做

- **保留**：乐观 busy（`MainViewModelSessionActions.kt:564` + `sendingSessionIds`）、`streamingPartTexts` 机制与渲染 override（`ChatMessageContent.kt:513`）、idle 对账、`session.status` 全分支。
- **不做**：
  - tile / tool 行视觉升级（per-tool spinner 等）：非体验润色，而是把 tool running 状态体现出来的必要补充（UI 缺口：折叠 tool 行无显式 per-tool running 指示）。P2 与 iOS 同步另开。
  - 渲染粒度优化：`streamingPartTexts` 作为参数传给所有可见行 → 每 delta 所有可见行重入组（`ChatMessageContent.kt:213-223`）。现状 shim host 已有此行为，native host 接入 delta 后同。Compose 层缓解（拆参数/派生 Flow）另开，不阻塞。
  - `sessionTodos`/`pendingQuestions` 的 sessionID gate（现状无 gate，全局 map）：现状行为，不动。
  - `launchLoadSessionStatus` 整体替换覆盖乐观 busy 的 flicker（现状已有副作用，`MainViewModelSessionActions.kt:144-158` 整体替换 vs :564 乐观写入）：已知问题，不扩大改动面。
  - 服务端改动：零。

## 5. 实现顺序（按步验证）

1. **Support 层**（`MainViewModelSupport.kt`）：`parseMessagePartUpdatedFull` / `parseMessageInfo` / `partTypeIndex` 管理 / 门禁判定函数（纯函数，可独立单测）。
   - 验证：纯函数单测（native 完整 part / shim 瘦 part / 顶层 delta / 畸形 payload 四象限）。
2. **SyncActions handler 改造**（`MainViewModelSyncActions.kt`）：按 4.1 表逐分支改；`upsertMessagePart`/`upsertMessageInfo`/removed 处理；`partTypeIndex` 生命周期接入 4 个清理点。
   - 验证：SSE 单测（见测试规格），全量 `MainViewModelTest` 回归。
3. **SSEClient onConnected 回调 + startSSE 接线**（4.3）+ **watchdog 循环**（4.4）。
   - 验证：onOpen 单测（fake EventSource 或用例级 stub）+ watchdog 时间注入单测。
4. **移除 busy polling**（4.5，4 处）。
   - 验证：编译 + 全量测试（无依赖用例，预期零改动通过）。
5. **live 验收**（见下）。

## 6. 测试规格

**现有用例改写**（`MainViewModelTest.kt`）：

| 用例（行号） | 现断言 | 改后 |
|---|---|---|
| `handleSSEEvent missing delta clears streaming state and refreshes messages`（:2403） | 清 streaming + `getMessages` 进 state | 门禁化：瘦 part + 无顶层 delta（native 完整 part 形状的反例构造需注意）→ 回退 REST；**另加**完整 tool part（带 state）→ 0 REST + 本地 upsert 断言 |
| `handleSSEEvent idle status clears streaming state and refreshes messages`（:2458） | idle → REST | 保持（idle 对账不动） |
| `handleSSEEvent message created refreshes messages for current session`（:2936） | REST | info upsert → 0 REST（`coVerify(exactly = 0) { repository.getMessages(...) }`） |

**新添用例**（同上文件，反射喂事件 + `coVerify(exactly=N)`）：

- part upsert：tool `pending→running→completed` 三帧各自改变 `state.messages`，全程 `getMessages` 0 次
- `message.part.delta`：连续 3 帧追加 `streamingPartTexts`；`partTypeIndex` 标 reasoning 时 `streamingReasoningPart` 更新；非当前 session 忽略
- 完整 end 帧到达 → 清对应 `streamingPartTexts` 条目
- removed：`message.part.removed`（`{sessionID,messageID,partID}`）删 part；`message.removed` 删行
- 壳行：tool part 先到未知 messageID → 壳行；text part 先到 → 不建
- 门禁：shim 形状（顶层 delta + 瘦 part）→ 走现 streaming 路径（回归）；完全畸形 → REST 回退
- `message.updated` info upsert：已知行 info 替换、parts 保留；未知行壳行
- watchdog：`sseLastFrameMs` 预置 30s 前 + `advanceTimeBy(5_000)` → 1 次对账（getMessages + getSessionStatus）；fresh → 0
- `sseLastFrameMs` 声明为 private，测试按现有反射模式预置（同 `_state`/`handleSSEEvent` 的反射用法，`MainViewModelTest.kt:165-171, 188-192`）
- onOpen：SSEClient 单测实现 OkHttp `EventSource`（abstract class）的最小 fake，直接调 `onOpen`，断言 `onConnected` 回调被调（SSEClient 是 plain class 可直接构造，不经 Hilt）；ViewModel 侧接线（回调 → `loadMessages + loadSessionStatus` 各 1 次）由 fake repository + 直接调 startSSE 注入路径的回调 lambda 覆盖，端到端走 live 验收点 4
- polling 移除后回归：busy + 无事件场景，`advanceTimeBy(10_000)` → `getMessages` 0 次（watchdog 阈值内）

## 7. Live 验收

- 环境：真机/模拟器连 4097 测试 server 或临时 scratch server；prompt 用 `bash sleep 6` 类长 tool。
- 验收点：
  1. 流式文本：native host 上 assistant 文本逐字出现（现状 native host 没有，本 plan 后要有）；shim host 行为不变。
  2. tool running：`message.part.updated`（running 帧）到达后 tool 卡状态即时更新（不等 400ms debounce + REST）。看到的是折叠行状态更新 + composer 行；行内显式 running 指示由 P2 补齐，验收时不以此为失败项。
  3. 网络面板：turn 期间 `getMessages` 调用 = 1（idle 对账）+ 发送后双补刷；busy 期间无 2s 周期拉取。
  4. 静默死亡模拟（断网/代理缓冲）：重连后 onOpen 立即对账；或静默 ≤25s 看门狗对账。
- 对照现状：busy 期间 2s 一次 `GET session/{id}/message` 的日志消失。

## 8. 风险与回滚

- **去掉 polling 后的兜底**：仅 watchdog + onOpen 可恢复静默死亡，二者需测试覆盖（上节验收）。漏触发 = 比现状更差的静默死亡；测试不过不 merge。
- **`it.copy(messages = ...)` 的 CAS 重试**：高频 part 下 `update` 竞争（CAS 失败自动重试，语义安全）。每次事件一份 `copy` 新 list（O(n)，n=已加载行数≤页大小），与现状 `loadMessages` 全量替换同阶，无新增量级。
- **渲染重入组**（4.6）：delta 频率 = token 级，所有可见行重入组。live 观察滚动/输入卡顿，若卡则 P2 渲染优化（delta 合帧 50–100ms，end 帧收敛兜底——机制同 iOS）。
- **门禁误判**：完整 part 解析失败误走 REST 回退 = 现状安全；shim 误判为完整（shim part 恰好含 state 字段？）→ upsert 瘦 part 覆盖本地完整 part 的风险：门禁 text part 有 text 字段、tool part 有 state 字段，shim 瘦 part 无 → 不致误判。
- **回滚**：无数据迁移、无协议改动。revert commit 回现状（polling 恢复、无流式文本 native 支持）。

## 9. 工作量估计

| 项 | 估计 |
|---|---|
| Support 解析/门禁/纯函数单测 | 0.5 天 |
| SyncActions handler 改造 + upsert + 测试改写/新增 | 1 天 |
| onOpen bootstrap + watchdog + 测试 | 0.5 天 |
| 移除 polling + live 验收 | 0.5 天 |

## 10. 遗留开放项（不阻塞开工）

1. **turn 级 final completion `message.updated` 帧是否存在**：与 iOS 共享 open item（live 待测，不影响设计）。
2. **dsh shim host 的 `server.heartbeat` 是否存在**：未验证；退化行为已设计（4.4）。
3. **服务端是否为 user message 的 parts 发 `part.updated` 事件**：未验证；两种情况都被 optimistic 收敛逻辑覆盖（4.1）。
4. **onOpen 首连与 `testConnection → loadInitialData` 的重叠是否加跳过标记**：实现时按网络面板观察定。
