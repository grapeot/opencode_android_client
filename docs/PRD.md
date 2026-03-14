# PRD: OpenCode Android Client

> Product Requirements Document · v1.1 · Mar 2026

## 元数据

| 字段 | 值 |
|------|------|
| **产品名称** | OpenCode Android Client |
| **状态** | v1.2 (UX Parity Phase 5b) |
| **创建日期** | 2026-02 |
| **最后更新** | 2026-03-14b |
| **参考** | iOS Client PRD |

---

## 摘要

OpenCode Android Client 是 OpenCode AI 编程助手的原生 Android 客户端，让开发者可以在手机/平板上远程监控 AI 工作进度、发送指令、审查代码变更。

---

## 背景

### 问题

开发者使用 OpenCode 时，常需在电脑前等待 AI 完成耗时任务。离开工位后无法及时了解进度、无法快速纠偏。现有 Web 客户端移动端体验不佳，TUI 无法在手机上使用。

### 目标

提供原生 Android 客户端，让用户可在手机/平板上：
- 监控 AI 工作进度
- 发送消息、切换模型、选择 Agent
- 查看 Markdown diff、代码变更
- 必要时中止或排队新指令

### 目标用户

- 使用 OpenCode 进行日常开发的程序员
- 需要远程监控长时间运行任务的场景
- Android 手机/平板用户

---

## 功能需求

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
- **语音输入**：通过 AI Builder WebSocket API 录音转写，partial transcript 实时回填输入框，可在录音中继续发送已有文本 ✅

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

#### Files Tab

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
| 语音输入 | AI Builder WebSocket 实时转写 | AI Builder WebSocket 实时转写 | 功能已对齐 |
| 图片预览 | fit / zoom / pan / share | fit / zoom / pan / Android share sheet | 功能已对齐 |
| Chat Toolbar 布局 | 左 Session 操作 / 右 Model+Agent+Context | ✅ Phase 5 完成 | 两行布局已对齐 |
| 草稿持久化 | 按 Session 存储 | ✅ Phase 5 完成 | JSON Map in EncryptedSharedPreferences |
| Model/Agent 按 Session 记忆 | 按 Session 存储 | ✅ Phase 5 完成 | per-session > 推断 > 全局 |
| Session Rename UI | Toolbar pencil 按钮 | ✅ Phase 5 完成 | AlertDialog 已实现 |
| Model/Agent 文本显示 | Capsule 按钮含模型名 | Phase 5b 实现 | 当前 Android 仅图标 |
| 消息历史分页 | pull-to-refresh | Phase 5b 修复 | 当前 Android 滚动检测方向反转 |
| 消息模型标注 | caption 显示 provider/model | Phase 5b 实现 | Android 数据已有但未渲染 |

---

## 实现规划

| Phase | 范围 | 状态 |
|-------|------|------|
| 1 | 项目搭建、网络层、SSE、Session、消息发送 | ✅ 完成 (2026-02-23) |
| 2 | Part 渲染、权限审批、构建修复、集成测试 | ✅ 完成 (2026-02-24) |
| 3 | Bug 修复、Markdown 渲染、模型选择、Context Usage、主题、平板布局 | ✅ 完成 (2026-03-02) |
| 5 | UX 对齐 iOS：Chat toolbar 重排、Session Rename UI、草稿持久化、Model/Agent per-session 记忆 | ✅ 完成 (2026-03-14) |
| 5b | 消息历史分页修复、Model/Agent 文本化 Capsule、平板 toolbar 适配、消息模型标注 | 🔲 进行中 |
| 4 | SSH Tunnel、Session 变更文件列表 | 🔲 未来可选 |

---

## 成功指标

1. 能够稳定连接 OpenCode Server（局域网/公网）
2. 消息发送、接收、流式显示正常
3. 权限审批流程完整
4. 文件预览、Markdown / 图片渲染可用
5. 平板三栏布局体验流畅
6. Chat 在监控模式下自动跟随，在历史查看模式下不强制跳到底部

---

## 参考

- [OpenCode Web API](../../../adhoc_jobs/opencode_ios_client/docs/OpenCode_Web_API.md)
- [iOS Client RFC](../../../adhoc_jobs/opencode_ios_client/docs/OpenCode_iOS_Client_RFC.md)
