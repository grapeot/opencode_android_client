# Plan: SSE 事件数据通路（SSE 作为消息状态的数据通路 + 移除 busy polling）

Status: draft — step 1 落盘（当前理解 + 设计方向），step 2 调研后大幅扩充再实现；无代码改动

姊妹文档：`opencode_ios_client/docs/features/sse_event_data_path/design.md`（同一设计，iOS 侧完整版，含服务端事件契约源码引用与 live 实测证据）。本文档聚焦 Android 移植差异。

## Bottom Line

Android 现在是同一套「SSE 只当门铃」架构：消息相关 SSE 事件不应用 payload，触发 400ms debounced 全量 REST 拉取；文本流式靠内存（`streamingPartTexts`）；另有 2s busy polling 兜底。本 plan 移植 iOS 侧已定的同一设计：

1. **SSE 作为数据通路**：`message.part.updated` / `message.updated` 的 payload 直接原地 upsert 到本地消息状态（零 RTT，事件到达即渲染）；REST 降级为对账（bootstrap / idle / 手动 / 看门狗）。
2. **移除 `launchBusyPolling`**（busy 期间每 2s `loadMessages`）：用户反馈差（REST 双拉 + UI 抖动），是 Android 上感知到的卡顿来源。静默死亡恢复改由心跳看门狗覆盖。
3. **心跳看门狗**：服务端每 10s 发 `server.heartbeat`；客户端静默 > 20s（错过 2 个周期）触发一次对账。不是轮询。
4. **保留**：现有 in-memory delta 机制（`streamingPartTexts`）、发送时乐观 busy（`MainViewModel.kt:564`，composer 行秒出的基础，正确行为）。

预期收益：per-event REST 归零（改为 per-turn 1–2 次对账）；busy 期间不再有 2s 双拉；SSE 静默死亡 ≤20s 自愈。

## 服务端事件契约（摘要）

与 iOS 文档同一契约（native OpenCode server，源码已验证），关键三条：

- `message.part.updated`：`properties.part` 是完整 part 对象（tool part 含 `state.status/input/output/time.start/end`，状态机 `pending→running→completed/error` 各推独立事件帧），`properties.time` 带时间戳。
- `message.part.delta`：`{sessionID, messageID, partID, field: "text", delta}`，流式增量（text 与 reasoning 均 `field: "text"`）。
- `server.heartbeat`：每 10s 一帧（`handlers/global.ts:32-35`）。

完整契约表、触发时机源码行号、live 实测时间线（tool `pending→running→completed` 各一帧）见 iOS 文档「服务端事件契约」节，不重复。

**payload 完整性门禁（多 host 兼容关键）**：dsh shim 的 `message.part.updated` payload 形状不同（`part` 瘦身为 `{messageID, id, type: "text"}`，增量在顶层 `delta` 字段，无 `state`/`time`/`text`）。in-place apply 必须带门禁：part 缺该类型所需字段时回退现行 debounced refetch（与今天行为一致）。门禁不通过 ≠ 丢数据，只是走老路。Android 连接哪些 host（是否含 shim / SSH tunnel）step 2 核对。

## 客户端现状盘点（Android，文件:行号）

| 位置 | 现状 |
|---|---|
| `MainViewModelSyncActions.kt:119-147` | `message.part.updated` handler：payload 顶层有 `delta` → 写 `streamingPartTexts`（in-memory）；无 `delta` → 清缓冲 + `onRefreshMessages`（400ms debounced 全量 REST） |
| `MainViewModelSupport.kt:151-164` | `parseMessagePartDeltaEvent`：读 payload **顶层** `delta`（shim 形状）。native server 的 `message.part.updated` 无顶层 delta → 在 native server 上本路径恒走 refetch 分支 |
| `MainViewModelSyncActions.kt:16-30` | `launchBusyPolling`：busy 期间每 2s `loadMessages` |
| `MainViewModel.kt:564` | 发送时乐观置 busy（保留） |
| `ChatMessageContent.kt:117-123, 193-205` | `streamingPartTexts` 渲染路径（保留） |
| SSE 事件分发 `when` | 无 `server.heartbeat` case → 落入 else 丢弃（看门狗需补） |
| `docs/working.md:467` | guardrail 测试「`message.part.updated` 缺 delta → re-fetch」——本 plan 后该行为由门禁显式保留，测试需改写为「门禁：字段不全 → 回退 refetch」 |

Android 与 iOS 的关键差异：Android 已有 in-memory 流式文本与乐观 busy，视觉差主要体现在 **tool running 状态**——tool part 的 `running` 帧到达后，Android 也要等 debounced REST（400ms + 往返）才渲染，busy polling 只是兜底放大问题而非解决。in-place apply 后 tool running 帧到达即渲染。

## 设计

### 6.1 SSE 作为数据通路

与 iOS 同一事件 → apply 规格表（`message.part.updated` → 按 partID upsert；`message.updated` → 按 messageID upsert info 保留本地 parts；`message.part.delta` → 追加 `streamingPartTexts`（现状保持）；`message.part.removed` / `message.removed` → 删除；`session.status → idle` → 触发一次对账）。Android 实现形态：

- messages 在 `AppState`（`StateFlow`）内：upsert = `state.update { copy(messages = upsert(...)) }`（精确结构 step 2 细读 `MessageStore` / `AppState` 后钉死）。
- 保留 `streamingPartTexts` 与完整帧覆盖语义（完整 `part.updated` 到达后以完整帧为准、清缓冲）。
- payload 完整性门禁：同 iOS（tool part 需 `state`；text part 需 `text` 或顶层 `delta`），不满足 → 现行 400ms debounced refetch。
- sessionID 门控：只处理当前 session 事件（现状语义保持，step 2 核对现有 gate 位置）。
- 幂等：按 ID upsert，重复/乱序安全。

### 6.2 REST 降级为对账 + 移除 busy polling

- `loadMessages` 触发点收敛为：SSE (re)connect bootstrap、`session.status → idle`、手动刷新 / session 切换、看门狗触发、`session.error` 恢复。
- **删除 `launchBusyPolling`**：函数本体、busy 分支里的 launch 点、相关单测与 guardrail。删除后 busy 期间的数据及时性由 in-place apply 提供，静默死亡由看门狗覆盖。
- 400ms debounced refetch：仅作为门禁不通过时的回退路径保留（见 6.1）。

### 6.3 心跳看门狗

- OkHttp `EventSource` 的 `onEvent` 收到 `server.heartbeat` 帧（data-only JSON `{type: "server.heartbeat"}`）→ 补 `when` case → 刷新 `lastFrameAt`（monotonic clock）。任意 SSE 帧到达都刷新。
- 实现：SSE 连接建立时 launch 一个 `watchdogLoop` 协程（每 5s 检查一次 `now - lastFrameAt > 20s`），SSE 断开时 cancel。触发时执行一次对账（`loadMessages` + 必要的 status 同步）并重置 `lastFrameAt`。
- 不是轮询：无固定周期拉取；正常时（heartbeat 10s 一帧 + turn 中业务事件）零触发；静默死亡（代理缓冲丢帧）≤20s 恢复。
- 与现有 `/session/status` 拉取（`MainViewModel.kt:25, 61, 144-150` 的 `launchLoadSessionStatus`）的关系：step 2 核对是 one-shot 还是周期，避免与看门狗功能重叠。

### 6.4 明确不做

- tile / tool 行视觉升级（per-tool spinner、计时展示细节）：P2，与 iOS 同步另开。
- 乐观 busy 的改动：保持现状。
- NFC 分支等无关路径：不碰。
- 服务端改动：零。

## 测试

- 单测（现有 `handleIncomingSseEvent` fake event 模式）：
  - part upsert：tool `pending→running→completed` 帧各自改变 state，**且零 REST 调用**（refetch 计数断言）。
  - removed 事件正确删除。
  - 幂等与乱序。
  - 完整性门禁：shim 形状 payload（瘦 part + 顶层 delta）→ 走 debounced refetch（回归断言）。
  - 非当前 session 事件被忽略。
- **refetch 计数回归**：一个 turn 的 fixture（N 个 part 事件）改前 N 次 refetch、改后 0 次（idle 对账 1 次除外）。这是本 plan 的核心行为断言。
- 看门狗：fake clock / fake events。静默 20s → 一次对账；帧到达重置不误触发；触发后不连发。
- polling 移除：busy + 无事件场景下无 `loadMessages`（看门狗对账除外）。
- `docs/working.md:467` guardrail 测试改写为门禁语义。

## 风险

- **移除 polling 后唯一安全网是看门狗**：看门狗可靠性必须有测试覆盖（上节）；其失败模式（漏触发）的后果 = 回到「SSE 静默死亡无恢复」，比现状（有 polling）差——所以看门狗测试是本 plan 的验收硬门槛。
- **payload 完整性依赖服务端版本**：门禁保证老版本 / shim host 行为不劣化（走老路），但意味着这些 host 上本 plan 的收益不生效。可接受：收益主要在 native server host。
- **StateFlow 整体 copy 的重渲染**：`copy(messages = ...)` 触发 collector 重入。与 iOS 的 @Observable 问题同类，step 2 评估是否需要更细粒度（如 per-message 派生 flow）。
- **回滚**：无数据迁移、无协议改动。revert commit 即回到现状（polling 恢复）。

## 工作量估计

| 项 | 估计 |
|---|---|
| 数据通路：SSE handler 重写 + upsert + 门禁 + refetch 计数回归测试 | 1–1.5 天 |
| 移除 `launchBusyPolling` + 相关测试/guardrail 更新 | 0.5 天 |
| 心跳看门狗（EventSource heartbeat case + watchdogLoop + 测试） | 0.5 天 |
| live 验收（真机/模拟器对 4097 或 scratch server） | 0.5 天 |

## Step 2 调研待办（扩充本文档前完成）

- [ ] `MessageStore` / `AppState` / messages 数据结构全量细读：`MessageWithParts` 形状、upsert 插入点、`state.update` 的精确用法与现有测试工具。
- [ ] `streamingPartTexts` 全链路：写入点（`message.part.delta` handler 位置）、渲染点、清理时机（现有代码里 `message.part.delta` 事件的 handler 在哪，与 `parseMessagePartDeltaEvent` 的分工）。
- [ ] `launchBusyPolling` 全部调用点 + 依赖它的单测/guardrail 清单（删除范围钉死）。
- [ ] OkHttp EventSource 对 data-only 帧（`server.heartbeat`）的 `onEvent` 行为验证（帧是否带 event name；`when` 分发的输入是 type 字段还是 SSE event 名——iOS 是自定义解析，Android 用 OkHttp 库，两者路径不同，需按 Android 实际解析路径钉死）。
- [ ] `launchLoadSessionStatus`（`/session/status` 拉取）的触发模式（one-shot / 周期），与看门狗的重叠与分工。
- [ ] Android 的 host 配置：连接哪些 host（native / shim / SSH tunnel），门禁的适用范围确认。
- [ ] SSE 重连/bootstrap 路径（OkHttp EventSource 的 `onClosed` / `onFailure` → 重连 → `loadMessages`）现状盘点。
- [ ] 非当前 session 事件的现有 gate 位置（upsert 守卫对齐）。
- [ ] 渲染粒度：StateFlow `copy` 重渲染范围评估，是否需要派生 flow / 细粒度 observable。
- [ ] iOS 文档 step 2 的共享项（removed 事件完整 payload、乐观行交互等）同步结论。
