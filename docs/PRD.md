# PRD: OpenCode Android Client

> Product Requirements Document · v1.1 · Mar 2026

## 元数据

| 字段 | 值 |
|------|------|
| **产品名称** | OpenCode Android Client |
| **状态** | v1.2 (UX Parity Phase 5b) |
| **创建日期** | 2026-02 |
| **最后更新** | 2026-05-25 |
| **参考** | iOS Client PRD |

---

## 摘要

OpenCode Android Client 是 OpenCode AI 编程助手的原生 Android 客户端，让开发者可以在手机/平板上远程监控 AI 工作进度、发送指令、审查代码变更。

---

## 1. 产品定位

OpenCode Android Client 是一个面向 OpenCode 服务端的原生 Android 远程控制应用。它不是一个独立的 AI 编程工具，而是运行在 Mac/Server 上的 OpenCode 实例的**移动端延伸**——让用户可以在沙发上、通勤中或任何远离电脑的场景下，发送指令、监控 AI 工作进度、浏览 Markdown 报告、切换模型。

核心设计原则：**轻量、快速、以阅读和交互为主**。所有繁重的配置（Provider 密钥、MCP 服务、workspace 设置）都在电脑端完成，Android 端只做必要的交互和消费。

但比技术架构更根本的，是这个 App 要解决的核心交互问题：**如何让 AI 把它做出的最关键决策浮出水面，供人类审查，并在必要时通过语音快速介入纠正**——我们把这个模式叫做 **Steer（统领）**。AI 负责执行和探索，人类负责判断和方向。App 的价值不只是在"看一眼 AI 在干嘛"的监控，更在于它是一个轻量的**决策审查与方向控制终端**——用户每次打开都在阅读 Markdown 报告、查看嵌入的截图和产物、通过语音下达新的方向性指令。它不是浏览工具，是统领工具。

围绕 Steer 这个范式，App 把重心放在了三个交互载体上：**Markdown 对话窗口**（AI 向人类汇报和展示产物的主要途径）、**文件卡片预览**（快速查看 AI 修改了什么，以文档而非代码为主）、**语音输入**（用户在远离键盘时仍能高效下达方向性指令）。代码语法高亮不是优先级——因为用户要审的不是代码本身，而是 AI 做决策的理由和结果。

### 1.1 它不是什么

这个 App 不试图做以下事情：在手机上编辑代码、在手机上运行 OpenCode server、替代完整的 Web UI。它的价值在于"AI 在干活的任何时刻掏出手机，阅读它刚刚完成的 Markdown 分析报告或者代码改动，觉得方向不对就立刻通过语音让它换一条路"这种场景。

### 1.2 核心交互范式

#### Steer 范式

App 围绕"统领"的认知闭环来设计交互：

1. **Surface**——AI 通过 Markdown 窗口向人类展示它正在做的决策、读到的代码、跑过的命令、分析和结论。文本是自然语言 Markdown，可以嵌入截图（如部署结果、图表、UI diff），而不是纯代码。这是 AI 与人之间的主要信息通道。
2. **Review**——人类阅读 Markdown，审查看法对不对、方向偏不偏。大多数内容是文档、分析、调研，天然适合 Markdown 呈现；偶尔需要看代码 diff 确认细节。人类在这个环节是在做判断，不是在写代码。
3. **Steer**——发现方向偏了，通过语音快速下达纠偏指令（"停，不要用继承，换成组合模式"），或者切换模型/Agent 重新开始。指令以自然语言下达，不需要打字。

这个循环在两端都是异步的：AI 在电脑上跑，人类在手机上审。两边的节奏不需要同步——这是移动端相对于桌面端的独特优势。但也因此引入了一个真实痛点：当 AI 停下来等人类决定时（如 `question` tool），人类目前收不到任何通知，双方都在空转。这个问题的解决方案不在 UX 设计层面，而在 Android 工程层面——需要前台服务（Foreground Service）配合通知（Notification）或持续通知来主动触达用户。

#### Markdown 作为交互窗口

Markdown 是这个 App 里 AI 与人类交互的**主要信息媒介**。这不是一个"顺便支持 Markdown"的代码查看器，而是一个把 Markdown 阅览体验做到极致的统领终端。具体体现：

- **不强调代码语法高亮**，强调 Markdown 渲染质量（标题层级、列表、链接、代码块、图片嵌入）。用户要审的是 AI 的思考过程和产出物，不是代码的美学。
- **完善的图片嵌入支持**：AI 可以截取部署结果的截图、生成图表、拍摄 UI 对比图，嵌入 Markdown 报告中。App 有专门的 Skill 体系教 AI 如何使用这些能力。
- **内容形态不限于编程**：AI 产出的可以是代码 diff，也可以是调研文章、分析报告、系统设计文档。Markdown 天然兼容所有这些形态。

#### 文件卡片预览（非代码编辑器）

文件浏览的核心不是"在手机上写代码"，而是**快速确认 AI 的改动**。用户绝大部分情况下通过 Chat 窗口中的 tool/patch 卡片直接跳转到文件预览——Files Tab 是一个兜底入口，不是主工作流。当用户在 Chat 里看到 `edit_file` 卡片，点一下卡片上的文件路径就进入预览，不需要切到 Files Tab 去翻目录树。

文档 diff 的重要性远高于代码 diff：AI 能力已足够写出好代码，人类需要审查的主要是文档层面的逻辑和架构决策。

### 1.3 与 iOS Client 的关系

Android Client 与 iOS Client 共享同一套产品范式和视觉语言（Quiet Tech），两者在功能上追求对等——Steer 范式、Markdown 交互窗口、文件卡片预览、语音输入、模型/Agent 切换等核心体验在两个平台上保持一致。差异仅在于平台实现手段（Jetpack Compose / Material 3 vs SwiftUI）和少数受平台限制的功能（如 iOS 已实现 SSH Tunnel，Android 暂用 Tailscale/HTTPS 替代）。详细的平台差异对照见下文"与 iOS 版本的差异"章节。

### 1.4 核心能力总览

Android Client 提供以下核心能力：

| 能力 | 说明 |
|------|------|
| 远程监控与审阅 | 连接 OpenCode Server，实时查看 AI 工作进度和产出 |
| 方向控制（Steer） | 通过文本或语音下达指令，中止/排队/切换模型/切换 Agent |
| Markdown 阅览 | AI 生成的报告、分析、设计文档的完整 Markdown 渲染，支持嵌入图片 |
| 文件卡片预览 | 在 Chat 流中点击 tool/patch 卡片跳转文件预览，确认 AI 的改动 |
| Session 管理 | 查看、创建、切换、重命名、删除、Fork Session，追踪多线任务 |
| 权限审批 | 手动批准或拒绝 AI 请求的 shell 操作等权限 |

---

## 2. 目标用户与使用场景

### 2.1 目标用户

目标用户是日常使用 OpenCode 的开发者（初期就是作者自己）。需要理解的关键一点是：重度用户打开 App 并不是为了"瞄一眼状态"——每次打开都是重量级交互：阅读 Markdown 报告、通过语音与 AI 对话、在 Session 之间频繁切换来追踪不同任务。App 的价值在于让用户在离开电脑时保持对 AI 工作的判断力和控制力，不是一个 status checker。

### 2.2 典型场景

**场景 A — 远程监控与审阅**：在电脑上启动了一个耗时的重构任务，离开工位。掏出手机，不仅看 AI 处理了多少文件，更是阅读 AI 刚生成的 Markdown 分析报告——它在解释每个改动的理由，附带架构图截图。用户的实质操作是：仔细阅读报告、点开文件卡片看具体改动、确认方向正确。

**场景 B — 快速纠偏（语音驱动）**：手机上看到 AI 走偏了，正在用错误的方法实现某个功能。通过语音输入快速下达指令："停，不要用继承，改用组合模式"，然后放回口袋。语音输入在这个场景下的核心价值是避免手机打字的不便，让方向控制尽可能低摩擦。

**场景 C — 模型 A/B 测试**：想比较不同模型（如 GPT-5.3 Codex / DeepSeek / Opus / GLM）对同一个任务的表现。在手机上一键切到另一个模型，发送相同的指令，观察差异。这种场景也涉及 Fork Session——从同一个对话节点分叉出不同模型的尝试。

**场景 D — 文档审查**：AI 完成了一轮修改，在手机上浏览 Markdown 文档的 diff，以 Preview 模式为主查看变更，确认文档改动合理后让 AI 继续下一步。代码审查为辅——AI 能力已足够写出好代码，人类需要审查的主要是文档层面的逻辑和架构决策。内容不限于编程：AI 产出的可能是调研报告、系统设计文档、部署结果展示，这些天然是 Markdown 形态。

### 2.3 重度用户的时间分布

理解重度用户实际花时间的地方，对于把握产品方向至关重要：

- **约 60% 的时间在阅读 Markdown**——AI 的分析报告、调研文章、设计文档、带有嵌入式截图的部署结果。这是"审"的环节。
- **约 25% 的时间在与 AI 对话**——通过语音下达新指令、纠正方向、追问细节。这是"领"的环节。
- **约 10% 的时间在 Session 间切换**——追踪不同项目、不同方向的进展，判断哪个需要介入。这是"多线统领"。Session 辨识度（一眼区分哪些是活跃的、哪些是已完成的）因此成为一个真实的 UX 痛点。
- **约 5% 的时间在 Files Tab**——它本质上是一个兜底入口，绝大部分文件访问都是通过 Chat 窗口中的 tool/patch 卡片跳转完成的。

基于这个时间分布，产品优化的优先级应该是：**Markdown 渲染质量 > 语音输入流畅度 > Session 辨识度 > 文件树功能**。

---

## 3. 功能需求

### 3.1 核心功能

#### Chat Tab

- **Session 管理**：列出所有 Session，创建/切换/重命名，标题通过 SSE 实时更新 ✅
- **消息发送**：支持多行输入，busy 时服务端排队 ✅
- **消息渲染** ✅：
  - 用户消息：灰色背景 ✅
  - AI 消息：Markdown 渲染（multiplatform-markdown-renderer-m3） ✅
  - 思考过程（reasoning）：折叠展示 ✅
  - 工具调用（tool）：卡片形式，running 时展开、completed 时收起 ✅
  - Patch：显示文件路径，点击可跳转预览 ✅
  - Todo：在 tool 卡片内展示任务列表 ✅
- **流式显示**：SSE delta 增量累积，text/reasoning 均支持打字机效果 ✅
- **自动跟随**：当用户停留在底部时，新的消息 / tool call / 流式更新自动跟随；离开底部时保持当前位置 ✅
- **模型选择**：从 `/provider` API 动态获取，TopBar 下拉菜单 ✅
- **Agent 选择**：从 `/agent` API 动态获取 ✅
- **Context Usage**：环形进度显示上下文占用（绿/橙/红三色），AI 回答中也始终可见 ✅
- **权限审批**：手动批准/拒绝 permission 请求 ✅
- **Abort**：中止当前任务 ✅
- **语音输入**：通过 VoiceFlowKit realtime session 录制 PCM16 mono 24kHz 音频，partial transcript 实时回填输入框；Kit 内部写入本地 PCM cache，WebSocket 断线或发送失败时自动新建 session 并从 cache offset 0 replay；录音/转写卡住时可通过左侧辅助 stop 保留已录 PCM，随后 retry 重新识别，可在录音中继续发送已有文本 ✅

#### Chat Toolbar 布局（Phase 5 对齐 iOS）

当前 Android 的 Chat 顶栏与 iOS 差异较大，Phase 5 将统一布局：

**Session 标题**：从当前 TopAppBar 内的 `titleSmall` 改为独立的大标题行，位于 toolbar 按钮行上方，使用 `titleMedium` 字号，单行省略。显示当前 session 的 title，无 session 时显示 "OpenCode"。

**Toolbar 按钮行**（标题下方，对齐 iOS ChatToolbarView）：

```
┌─────────────────────────────────────────────────┐
│  Session Title (large)                          │
├─────────────────────────────────────────────────┤
│  [☰] [✏] [+]          [Model ▾] [Agent ▾] [◔]  │
│   左侧操作              右侧选择器               │
└─────────────────────────────────────────────────┘
```

- **左侧**（从左到右）：Session 列表、Rename、新建 Session
- **右侧**（从左到右）：Model 下拉、Agent 下拉、Context Usage 环
- Settings 齿轮仅在平板布局时出现在最右侧（手机通过底部 Tab 进入）
- Rename：点击弹出对话框，输入新标题后调用 `PATCH /session/{id}`

#### 草稿持久化（Phase 5）

输入框中未发送的文本按 sessionID 持久化。切换 session 时自动保存当前草稿、加载目标 session 的草稿。发送成功后清空对应 session 的草稿。持久化存储使用 EncryptedSharedPreferences（JSON 编码的 Map）。

#### 模型/Agent 选择按 Session 记忆（Phase 5）

模型和 Agent 的选择按 sessionID 存储。切换 session 时，优先从持久化存储恢复该 session 上次的选择；若无记录则从最后一条 assistant message 推断（当前已有此逻辑）；推断不到时保持全局默认值。

#### 消息历史分页（Phase 5b — Bug 修复）

当前滚动到消息列表顶部（最早的消息）时无法加载更多历史消息。`loadMoreMessages()` 后端已实现（增大 limit 参数重新拉取），但 UI 滚动检测逻辑方向反转：`reverseLayout = true` 下，列表视觉顶部对应高索引，而当前检测逻辑在低索引处触发，实际效果是在最新消息处触发加载而非最旧消息处。需修复检测方向并在列表顶部增加 loading 指示器。

#### Model/Agent 选择器文本化（Phase 5b — 对齐 iOS）

当前 Model 和 Agent 选择器仅显示图标（Tune / SmartToy），用户无法直接看到选中了哪个模型和 Agent。iOS 端使用 Capsule 样式按钮显示文本（模型名 + chevron.down），Android 需对齐：

```
┌─────────────────────────────────────────────────┐
│  Session Title                                  │
├─────────────────────────────────────────────────┤
│  [☰] [✏] [+]    [Opus 4 ▾] [build ▾] [◔]      │
└─────────────────────────────────────────────────┘
```

- Model 按钮：Capsule 形状，accent 渐变背景，白色文字显示 `shortName`（如 "Opus"），下拉 chevron
- Agent 按钮：Capsule 形状，灰色背景，secondary 文字显示 `shortName`（如 "build"），下拉 chevron
- 需要给 ModelOption 新增 `shortName` 计算属性（对齐 iOS ModelPreset.shortName）

#### 平板 Chat Toolbar 适配（Phase 5b）

平板三栏布局下 Chat 面板的 toolbar 在 Phase 5 中已使用新两行布局，但由于 `showSessionListInTopBar = false` 和 `showNewSessionInTopBar = false`，左侧只剩一个 Rename 按钮，视觉上不平衡。需要调整平板布局下的 toolbar 样式，使左右分布更合理。

#### 消息模型标注（Phase 5b — 对齐 iOS）

iOS 在每条 assistant 消息旁显示回复该消息的模型名称（如 `anthropic/claude-opus-4-20250514`），Android 虽然 `MessageWithParts.info.resolvedModel` 数据已存在但未在 UI 中渲染。需要在消息行中添加一个小标签显示模型信息，格式为 `providerID/modelID`，使用 caption 字号、tertiary 颜色。

#### Fork Session（会话分叉）✅

用户可以从任意消息处 fork 当前对话，创建一个新 session，包含该消息之前的全部历史。典型场景：AI 回复不满意，想从某个节点重新开始；或者想从同一个起点尝试不同的提问方向。

**API**：`POST /session/{sessionID}/fork`，body 为 `{ "messageID": "..." }`（可选）。返回新的 `Session` 对象。服务端创建新 session 并复制指定消息之前的全部消息历史，标题自动变为 "{原标题} (fork #N)"。

**已实现方案**：在 assistant 消息底部 model label 旁添加 '...' (MoreVert) 按钮，点击弹出 DropdownMenu，包含 'Fork from here' 选项。点击后调用 API 创建新 session 并自动切换。

**实现参考**：`ChatTopBar.kt` 的 `DropdownMenu` + `IconButton` 模式可直接复用。

#### Files Tab

> Files Tab 是兜底入口——主工作流中文件访问通过 Chat 窗口的 tool/patch 卡片跳转完成。

- **文件树**：递归展示工作目录，支持 git 状态颜色标记 ✅
- **文件预览**：文本文件等宽字体显示，Markdown 文件渲染 ✅
- **图片预览**：图片默认 fit-to-screen，支持双击放大、拖动平移、系统分享 ✅
- **Session 变更**：🔲 暂不实现

#### Settings Tab

- **服务器连接**：配置 URL（HTTP/HTTPS）、Basic Auth ✅
- **连接测试**：验证服务器可达性 ✅
- **SSH Tunnel**：🔲 暂不实现
- **主题**：Light / Dark / System ✅
- **语音识别配置**：AI Builder Base URL、Token、Prompt、Terminology、连接测试 ✅

### 3.2 平板适配

- **手机**：底部 Tab 导航（Chat / Files / Settings） ✅
- **平板**：三栏布局（WindowSizeClass.Expanded） ✅
  - 左：Workspace（Files / Settings 切换）
  - 中：文件预览
  - 右：Chat

---

## 技术约束

| 约束 | 说明 |
|------|------|
| 最低版本 | Android 8.0 (API 26) |
| 网络协议 | HTTP REST + SSE（Server-Sent Events） |
| 安全 | HTTPS 默认；HTTP 仅允许 localhost 与 Tailscale (*.ts.net)，局域网 IP 需 HTTPS |
| 无本地 AI | 不引入本地推理、文件系统操作、shell 能力 |
| 语音音频 | 麦克风音频以 PCM16 mono 24kHz 进入 VoiceFlowKit realtime session；本地 cache 只存临时 `.pcm`，停止或取消后清理；显式 abort 会保留 cache 供 retry 后再清理 |

---

## 已知限制与风险

**网络依赖**：App 完全依赖与 OpenCode Server 的网络连接。如果 Server 不可达（网络不通、Server 未启动），App 无法使用。当前支持局域网直连与公网 HTTPS 访问；弱网下通过"最近 3 轮 + 下拉扩展历史"降低首屏延迟。Android 的 `network_security_config` 默认禁止明文流量，仅对 localhost 和 Tailscale MagicDNS（`*.ts.net`）开放 HTTP。

**SSE 在 Android 上的行为**：Android 系统可能在 App 进入后台后限制网络连接。需要实现可靠的前后台切换重连和状态恢复机制。不建议在后台保持 SSE 长连接。

**屏幕尺寸**：代码和 diff 在手机窄屏上的可读性是一个持续挑战。需要仔细设计横向滚动、字号调节等交互。平板上的体验会显著更好。

**Server API 稳定性**：OpenCode 的 HTTP API 目前没有正式的版本承诺（没有 `/v1/` 前缀）。Server 更新可能引入 breaking changes。建议 Android 端对 API 响应做防御性解析，对未知字段忽略而非 crash。

**安全**：初期 App 仅用于本地局域网，安全风险较低。如果后续支持公网访问，需要考虑 TLS、token-based auth 等增强方案。当前的 Basic Auth over HTTP 在局域网环境下可接受，但不适合公网暴露。Android 通过 `network_security_config.xml` 管理明文流量策略。

**人机异步空转（通知缺失）**：当 AI 因 `question` tool、permission 请求或需要人类审查而暂停等待时，人类如果离开了 App（Android 进入后台或锁屏），目前收不到任何通知。这意味着两端都在空转——AI 等着人类的决定，人类不知道需要做决定。这个痛点的实质是 Android 工程问题，而非 UX 设计问题：需要通过 Foreground Service 配合持续通知（Ongoing Notification），或利用 Android 的后台通知机制来主动触达用户。这是 Steer 闭环的关键工程增强项，应在后续版本中作为高优先级实现。

**首次启动配置复杂度**：OpenCode Server 本身的配置较为复杂（需要配 Tailscale、Provider API key 等），首次连接对新用户存在一定门槛。这是一个真实痛点，但优先级较低——当前用户群主要是已有 OpenCode 使用经验的开发者。

---

## 非功能需求

| 指标 | 要求 |
|------|------|
| 首屏加载 | < 3 秒（弱网） |
| 消息延迟 | SSE 事件 < 500ms 渲染 |
| 电池消耗 | 后台不保持连接，前台正常耗电 |
| 离线支持 | 断线时显示缓存内容，不崩溃 |

---

## 与 iOS 版本的差异

| 功能 | iOS | Android | 说明 |
|------|-----|---------|------|
| HTTP 连接 | 需配置 ATS | 需配置 network_security_config | 两者都允许 HTTP |
| SSH Tunnel | Citadel (SwiftNIO) | 🔲 暂不实现 | iOS 已实现，Android 用 Tailscale/HTTPS 替代 |
| UI 框架 | SwiftUI | Jetpack Compose | 声明式，概念相似 |
| 状态管理 | @Observable | ViewModel + StateFlow | 架构相似 |
| 安全存储 | Keychain | Keystore + EncryptedSharedPreferences | 功能等价 |
| 语音输入 | AI Builder realtime WebSocket + PCM cache/replay recovery | AI Builder realtime WebSocket + PCM cache/replay recovery | 功能已对齐 |
| 图片预览 | fit / zoom / pan / share | fit / zoom / pan / Android share sheet | 功能已对齐 |
| Chat Toolbar 布局 | 左 Session 操作 / 右 Model+Agent+Context | ✅ Phase 5 完成 | 两行布局已对齐 |
| 草稿持久化 | 按 Session 存储 | ✅ Phase 5 完成 | JSON Map in EncryptedSharedPreferences |
| Model/Agent 按 Session 记忆 | 按 Session 存储 | ✅ Phase 5 完成 | per-session > 推断 > 全局 |
| Session Rename UI | Toolbar pencil 按钮 | ✅ Phase 5 完成 | AlertDialog 已实现 |
| Model/Agent 文本显示 | Capsule 按钮含模型名 | ✅ Phase 5b 完成 | 模型名 + Agent 名文本化显示 |
| 消息历史分页 | pull-to-refresh | Phase 5b 修复 | 当前 Android 滚动检测方向反转 |
| 消息模型标注 | caption 显示 provider/model | ✅ Phase 5b 完成 | 消息行顶部显示 provider/model |
| Fork Session | 消息节点 fork | ✅ Phase 5b 完成 | API + DropdownMenu 已实现 |

---

## 实现规划

| Phase | 范围 | 状态 |
|-------|------|------|
| 1 | 项目搭建、网络层、SSE、Session、消息发送 | ✅ 完成 (2026-02-23) |
| 2 | Part 渲染、权限审批、构建修复、集成测试 | ✅ 完成 (2026-02-24) |
| 3 | Bug 修复、Markdown 渲染、模型选择、Context Usage、主题、平板布局 | ✅ 完成 (2026-03-02) |
| 5 | UX 对齐 iOS：Chat toolbar 重排、Session Rename UI、草稿持久化、Model/Agent per-session 记忆 | ✅ 完成 (2026-03-14) |
| 5b | 消息历史分页修复、Model/Agent 文本化 Capsule、平板 toolbar 适配、消息模型标注 | 🔲 进行中 |
| 6 | 语音输入 realtime recovery：立即 PCM capture、本地 cache、session attach/replay、断线恢复 | ✅ 完成 (2026-05-25) |
| 4 | SSH Tunnel、Session 变更文件列表 | 🔲 未来可选 |

---

## 成功指标

1. 能够稳定连接 OpenCode Server（局域网/公网）
2. 消息发送、接收、流式显示正常
3. 权限审批流程完整
4. 文件预览、Markdown / 图片渲染可用
5. 平板三栏布局体验流畅
6. Chat 在监控模式下自动跟随，在历史查看模式下不强制跳到底部
7. 语音输入在 WebSocket 建连慢、发送失败或心跳发现连接关闭时仍能通过本地 PCM cache 恢复并完成转写

---

## 已决事项

1. **文件内容渲染**：不引入代码语法高亮。Android 端与 iOS 端一致，文件预览保持等宽字体纯文本 + 行号。语法高亮列为 future enhancement，涉及 tokenizer 选型与性能考量。
2. **Files Tab 定位**：确认为兜底入口。主工作流中文件访问通过 Chat 流中的 tool/patch 卡片点击跳转完成。Files Tab 不投入额外资源做体验优化，保持稳定可靠即可。
3. **Session 列表交互**：不引入摘要预览（如生成了哪些文件），因主工作流在 Chat 中查看产出。Session 列表专注解决辨识度问题（活跃 vs 死亡）。
4. **推送通知**：暂不实现，但已识别为高优先级工程增强项——AI 等待人类决策时，需要通过 Android 前台通知机制主动触达用户，消除人机异步空转。
5. **大型 Session**：暂不考虑性能优化，不预期 session 超过百条消息。
6. **后台 SSE 连接**：不保持。App 进入后台时断开 SSE，回到前台时通过 REST 全量同步 + 重建 SSE 恢复。
7. **SSH Tunnel**：Android 端暂不实现（iOS 已通过 Citadel 实现）。Android 用户可通过 Tailscale 直接组网，或使用 HTTPS 公网访问替代。

---

## 参考

- [OpenCode Web API](../../../adhoc_jobs/opencode_ios_client/docs/OpenCode_Web_API.md)
- [iOS Client RFC](../../../adhoc_jobs/opencode_ios_client/docs/OpenCode_iOS_Client_RFC.md)
