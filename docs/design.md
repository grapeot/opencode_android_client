# OpenCode Android Client 设计 Spec — 冷静科技感（Quiet Tech）

本文件是 OpenCode Android Client 的视觉设计语言规格。它和 iOS client 的 `docs/design.md` 是**同一套设计语言的两个平台实例**——Android 严格对齐 iOS 的 Quiet Tech，差异只在平台实现手段（Jetpack Compose / Material 3 vs SwiftUI），不在视觉决策。

方向一句话：像 Raycast、Vercel、Arc 那类现代开发者工具——深色为主、低饱和、靠精准间距和细描边而非装饰显贵。不喧哗，"贵"都来自克制、一致和恰到好处的留白。

## 设计原则

1. **深色是主场，浅色是平替。** 开发者默认深色审美。深色优先调到完美，浅色用同一套 token 映射。
2. **单一识别色：电蓝 `#3B82F6`。** 整个 app 只有一个色相承担"可交互/品牌"语义——发送、选中、链接、用户消息色条、可点的工具卡元素。金色 `#D9A621` 仅作唯一次级强调，只用在"AI 正在工作"瞬时态。除这两色外全部走中性灰阶；Material 的 `error`/绿/橙一律不作装饰用（红只保留给 stop / 删除等破坏性语义）。
3. **关掉 Material You 动态取色。** `OpenCodeTheme` 不使用 `dynamicColor`——品牌色在所有设备上固定为电蓝，不跟随用户壁纸，和 iOS"固定 primary blue、不跟系统 accent"的决策一致。
4. **语义靠形态和位置，不靠颜色堆叠。** 用**卡片形态**区分功能类别，颜色只在极少数可交互处点缀。
5. **颜色不建立层级，字号和留白建立层级。**
6. **无边框优先。** 信息类容器去描边、只留极淡底色；只有"请你操作"的卡片才用一条左侧色条作功能信号。
7. **稳定按钮不换位。** 用户会靠肌肉记忆点击 composer 的发送和麦克风。发送按钮和麦克风按钮永远占据各自竖排的底部槽位；stop / retry 这类临时按钮只能出现在它们上方，不能把主按钮顶到别的位置。

## 色板与 Material 映射

Quiet Tech 的 token 定义在 `ui/theme/Color.kt`，并在 `ui/theme/Theme.kt` 里映射到 Material 3 的 `ColorScheme`，这样 View 层优先用 `MaterialTheme.colorScheme.*`，深浅两套自动切换。

### 深色模式（主场）

| 用途 | Color.kt token | Hex | Material colorScheme 槽位 |
|---|---|---|---|
| 主背景（近黑冷调） | `BgDark` | `#0B0C0E` | `background` / `surface` |
| 信息卡 / surface 底 | `SurfaceDark` | `#1A1D21` | `surfaceVariant` / `surfaceContainer*` |
| composer 输入底 | `ComposerDark` | `#141619` | `surfaceContainerLow` |
| 主文字 | `OnSurfaceDark` | `#E6E8EB` | `onSurface` / `onBackground` |
| 次级文字（时间戳、状态、tool 名） | `OnSurfaceVariantDark` | `#9BA1A8` | `onSurfaceVariant` |
| 极细分隔线 | `OutlineDark` | `#2A2E33` | `outline` / `outlineVariant` |
| 唯一识别色 | `BrandPrimary` | `#3B82F6` | `primary` / `secondary` |
| AI 工作中 | `BrandGold` | `#D9A621` | `tertiary` |

### 浅色模式（平替）

| 用途 | token | Hex |
|---|---|---|
| 主背景 | `BgLight` | `#FFFFFF` |
| 信息卡 / surface 底 | `SurfaceLight` | `#F0F1F3` |
| composer 输入底 | `ComposerLight` | `#F4F5F6` |
| 主文字 | `OnSurfaceLight` | `#15171A` |
| 次级文字 | `OnSurfaceVariantLight` | `#60656B` |
| 分隔线 | `OutlineLight` | `#E2E4E7` |
| 识别色（与深色同值，鲜艳电蓝） | `BrandPrimaryLight` | `#3B82F6` |

> `ToolWritePatchBackground` / `...Dark` 是历史上 write/patch 卡的特殊蓝底，Quiet Tech 下工具卡统一用中性 surface，这两个色已不再被 UI 引用，保留只为向后兼容，后续可清理。

整屏在任何时刻最多出现一处彩色（accent 或 gold），其余全灰阶——这是"冷静"的硬约束。

## 卡片形态语言

按功能分四类形态，这是 Quiet Tech 的核心，取代"多色同形的彩虹卡片"：

1. **信息卡片**（`ToolCard` / `PatchCard`）：中性 `surfaceVariant` 底，12dp 圆角，无重描边。**但它们是可交互的**（点开看 input/output、跳文件预览），所以卡片**内部的可操作元素**——工具图标、工具名、可跳转的文件路径、展开 chevron、OpenInNew 图标——用 `colorScheme.primary` 电蓝着色作为"可点"暗示；卡身保持中性。纯展示文字（tool reason、output 预览）走 `onSurfaceVariant` 灰。**不要把整张卡灰掉**——那样读起来像禁用控件（iOS 上踩过这个坑）。
2. **过程行**（`ReasoningCard` / `ToolCallsRow`）：无底色的轻量行——单行 `labelMedium` header（icon + 文本 + chevron，12dp 水平 inset 对齐正文），整行可点切换展开，**展开内容留在同一个半宽 tile 内**（tile 变高、网格 reflow），不撑到全宽。它们是**永远半宽 tile**，与 file card 同进一个两列网格（见「消息区」）。
3. **操作卡片**（`ChatPermissionCard` / question card）：中性 surface + 左侧一条 3dp 电蓝色条（`colorScheme.primary`）作"请你操作"的功能信号，配纯文字按钮（TextButton）——Allow 类电蓝、Reject 灰，不用绿/蓝/红实底按钮。
4. **状态行**（turn activity、elapsed 计时）：不是卡片，纯 `onSurfaceVariant` 文字。

圆角统一：信息/操作卡片 12dp，sheet 16dp，inline tag 6dp。

## 消息区

- **用户消息**（`TextPart` when `isUser`）：3dp 电蓝左色条 + muted 蓝底（`primary.copy(alpha = 0.10f)`），12dp 圆角。和操作卡片、选中行同构——"左色条"语言贯穿全 app。
- **AI 回复**：无容器，全宽纯文本 / markdown。

**卡片两列网格**：thinking（`ReasoningCard`）、合并的非文件工具（`ToolCallsRow`）、文件操作（`FileCard`）都是**永远半宽 tile**，进**同一个** `chunked(2) + Row(fillMaxWidth, spacedBy(8.dp))` 两列网格（tile `weight(1f)`，单 tile 补 `Spacer(weight(1f))`），按 part 顺序混排 2-up；Android 不能在 `LazyColumn` 里嵌 `LazyVGrid`，所以用手动两列手法（与 iPhone 2-up 一致，信息密度优先，不改单列）。奇数个 tile 时末行右列留空。tile 无论收起还是展开都是半宽：展开内容留在 tile 内（tile 变高、网格 reflow），不撑到全宽。text / 附件块**不进网格**，全宽渲染在网格之后（卡片先、正文后的读序是既定取舍）。列表级的 live streaming reasoning item 保持全宽、不进网格。

## Composer（`ChatInputBar`）

- F3 后与 iOS 一致采用 **voice rail + text review field** 两行结构，而不是把 mic 塞在单个输入 pill 内。语音是手机 steer 的主输入模态；文本框承担转写审阅、轻量修正和 fallback typing。
- **Voice rail** 位于文本框上方：左侧 transport、中央 waveform/status、右侧轻量恢复动作。录音中 waveform 消费 `VoiceFlowMicrophone.audioLevel` 的真实 0..1 smoothed mic level；转写和 retry 中显示 generating waveform。
- 左侧 transport：空闲为 `Tap to speak` mic；录音中变 stop，点击是正常结束采集并进入转写；preserved-audio 状态变 `Retry this segment`，点击重新识别同一段已保存 PCM。转写中 transport disabled，避免把等待恢复误读为重新录音。
- 右侧轻量动作：转写等待显示 `Stop transcription wait`，调用 `abortPreservingAudio()` 并保留音频；preserved-audio 状态显示 `Discard audio`，用于放弃缓存并退出恢复状态。retry 是唯一主恢复动作，discard 是退出动作，不能出现两个 retry 入口。
- **Text review field**：下方单个圆角 pill，底色 `surfaceVariant`，无边框 `BasicTextField`，`heightIn(min = 66.dp, max = 132.dp)`。语音转写和 retry partial transcript 写入文本框，用户可审阅和修正；普通打字保持系统默认编辑行为。
- **send 始终存在**：实底电蓝 36dp 圆角方块固定在 text review field 右侧。session busy 时仍可发送，因为服务端 `prompt_async` 支持排队；speech transcribing/retrying 时禁用，避免发送半成品转写。
- **agent interrupt 降权**：agent running 用 composer 附近 quiet status row 表达，例如 `Agent running · Transcribing`。`Interrupt agent` 放入 `⋯` overflow menu，作为低频 escape hatch，不再占据主输入区红色 stop 按钮，也不和语音 stop-wait 共用同一 glyph。

## Toolbar（`ChatTopBar`）

- **模型选择器**：从实底蓝胶囊改为**描边 chip**——透明底 + 1dp 电蓝边（primary @30% alpha）+ 电蓝文字。蓝作 accent 不作大块填充。
- 其余图标按钮（session 列表、rename、settings）：朴素 glyph，`onSurfaceVariant` 灰；突出的"新建"动作可用电蓝。去掉图标背后的填充圆。
- **Context ring**：track 用 `onSurfaceVariant` 低 alpha，进度用电蓝（不是紫），高占用阈值才切金/红（保留原阈值逻辑）。

## Session 列表（`SessionList` / `SwipeRevealRow`）

- Session List 按 **Active / Archived** 两个分区展示。Active 是当前工作集；Archived 是同屏折叠抽屉，不跳到 Settings 或另一个页面。
- **选中行**：3dp 电蓝左色条 baked 进 12dp 圆角的选中底（`primary.copy(alpha = 0.08f)`），整体 clip 在圆角内——色条不会戳出圆角、不与展开 chevron / 缩进冲突。
- 未选中行透明（去掉交替条纹底，取更干净的 Quiet Tech 观感）。
- Active 行 leading swipe 为 Archive；Archived 行 leading swipe 为 Restore；trailing swipe 始终为 Delete。所有 action 使用图标在上、文字在下的克制按钮语言。
- Archived rows 仍可点击查看，但标题和状态使用弱一级中性色，不画当前选中 accent，避免读起来像当前工作项。
- Session 分页使用顶部全局 `Load older` action，不再用底部自动 sentinel；这样 Archived 默认折叠时不会为了不可见历史自动连续分页，也不会把加载更多误读成 Archived 专属动作。
- 保留：展开折叠、depth 缩进、busy 标题色、状态文字。

## Settings（`SettingsSections`）

- **Theme**（LIGHT / DARK / SYSTEM）：分段控件（`SingleChoiceSegmentedButtonRow`），选中段电蓝。
- Switch / toggle accent 用 `colorScheme.primary` 电蓝。

## Host Profiles 与 SSH Tunnel（Phase 8）

### 设计意图

Host Profiles 的主任务是让用户在 30 秒内判断“我现在连的是哪个 OpenCode 环境、它通过什么路径访问、失败时卡在哪一步”。SSH Tunnel 是高级连接 transport，但 UI 不能把高级性转嫁成猜谜。用户应该只需要理解三件事：选哪个 profile、服务器是否授权了这台 Android 设备的 public key、连接失败属于 gateway/auth/tunnel/health 哪一段。

### 用户不确定性清单

第一次配置 SSH 的用户会自然卡在几件事上。第一，SSH gateway 和 OpenCode server 不是同一个字段；gateway 是入口，remotePort 是 gateway 侧分配给 OpenCode instance 的端口。第二，Android 设备的 SSH public key 需要复制到服务器授权，而不是填写 OpenCode Basic Auth。第三，本地 `127.0.0.1:<port>` 是 app 内部细节，用户不应该编辑。第四，host key mismatch 是安全事件，不是普通连接失败。

设计上要把这些不确定性显式放在界面里，而不是靠文档解释。

### Settings 入口

Settings 顶部的 Server Connection 改为 **Connection Profile** 卡片，不再直接展示一个全局 Server URL 表单。

卡片默认状态：

```text
Connection Profile
VPS OpenCode                         SSH Tunnel
gateway.example.com:8006 -> :19001
[Test Connection] [Manage Profiles]
```

Direct profile 的 summary 显示 server URL；SSH profile 的 summary 显示 `gateway:port -> :remotePort`。卡片内只放当前 profile 的摘要和两个动作：`Test Connection`、`Manage Profiles`。编辑不在卡片内展开，避免 Settings 首屏变成复杂表单。

### Host Profiles 列表

`Manage Profiles` 进入独立 screen 或 modal sheet。手机使用全屏 screen；平板 Settings pane 足够宽时可以使用右侧 detail pane，但第一版可先全屏保持简单。

列表结构：

```text
Host Profiles
[Import]                         [+]

Current
▌ VPS OpenCode            SSH Tunnel
  gateway.example.com:8006 -> :19001

Other Profiles
  Localhost               Direct
  http://localhost:4096
```

选中 profile 使用 Quiet Tech 统一的 3dp 电蓝左色条 + muted selected background。Transport 用小 chip 表示：Direct 为中性 chip，SSH Tunnel 为电蓝描边 chip。Row 点击打开 Host Profile detail，而不是立刻切换 host；切换 host 必须在 detail 里点 `Use This Host`。这样和 iOS 的交互一致，也避免用户只是想查看配置却意外切换连接。

Detail 负责承载对象级动作：`Use This Host`、`Test Connection`、`Edit`、`Copy Config JSON`。SSH profile 额外显示 `Copy Device Public Key`，因为 iOS 在 SSH detail 里也提供这个快捷入口。列表页仍保留全局 Device Key section，强调 key 属于设备；detail/editor 里的 copy 只是针对 SSH profile 的快捷动作，不改变 key 的归属语义。

### Profile Editor

Editor 顶部是 profile name，其下是 Direct / SSH Tunnel segmented control。切换 transport 时保留当前已输入字段，但保存时按当前 transport 校验。

Direct mode 字段：

```text
Profile name
Server URL
Basic Auth username (optional)
Basic Auth password (optional)
[Test Connection] [Save]
```

SSH mode 字段：

```text
Profile name
SSH gateway host
SSH port
SSH username
Assigned remote port

[Copy Device Public Key]

OpenCode Basic Auth (optional)
[Test Connection] [Save]
```

不要展示可编辑的 local URL。SSH editor 可以提供 `Copy Device Public Key` 快捷动作，但不展示 key 内容、不提供 per-profile key 字段。可以在辅助文字里解释：“Android creates a local tunnel automatically when this profile is active.” 这句话只解释机制，不要求用户操作。

### Device Key 与 Host Key 交互

Device public key 属于 Android 设备，不属于某个 Host Profile。它放在 Host Profiles 列表页的全局 `Device Key` section，语义与 iOS 对齐：用户复制一次 public key，把这台设备授权到 SSH gateway；多个 SSH Tunnel profiles 复用同一把 key。不要把 public key 放进每个 profile editor，否则用户会误以为每个 host 有独立 key。

```text
Device Key
This Android device uses one SSH public key for all SSH Tunnel profiles.
[Copy device public key] [Rotate key]
```

Android 生成 Ed25519 device key，public key 格式为 `ssh-ed25519 ... opencode-android`，与 iOS 和 private-host 的 `authorized_keys` 约束保持一致。Rotate key 是破坏现有服务器授权的恢复动作，必须先弹确认：确认文案说明会生成新的 device key，并且用户需要把新的 public key 更新到服务器；确认后才 rotate，并把新 public key 复制到剪贴板。

Host key mismatch 是阻断状态，不给“continue anyway”。错误卡片文案结构：发生了什么、为什么重要、怎么恢复。

```text
SSH host key changed
The gateway fingerprint no longer matches the trusted key saved on this device. This can happen after a server rebuild, but it can also indicate a man-in-the-middle attack.
Expected: SHA256:...
Got:      SHA256:...
[Reset trusted host] [Cancel]
```

`Reset trusted host` 之后下次连接重新走 TOFU；不要在同一弹窗里直接 trust 新 key。

### Test Connection 反馈

SSH test 需要分阶段显示，不要只给一个 spinner。阶段顺序与 RFC 一致：SSH gateway、Host key、SSH auth、Local tunnel、OpenCode health。已完成阶段显示 check；当前阶段显示 progress；失败阶段显示错误和恢复动作。

```text
Testing SSH Tunnel
✓ SSH gateway reachable
✓ Host key trusted
✓ SSH key accepted
✓ Local tunnel ready
… Checking OpenCode health
```

Direct test 仍然只显示 server health，但复用同一个 result card 样式，避免 Settings 里出现两套反馈语言。

### Import / Export

Import 使用 paste dialog，不做文件 picker 第一版。Toolbar 图标必须使用 download/import 方向（Material `FileDownload` 或等价向下箭头），不要用 upload/export 方向。Dialog 标题是 `Import Host Profile JSON`，输入框支持多行，主按钮 `Import Profile`。成功后进入 imported profile 的 detail/editor，并显示 snackbar：`Imported profile "VPS OpenCode"`。

Export 使用 bottom sheet 展示只读 JSON 和 `Copy JSON`。Sheet 顶部明确写 `Secrets are not included`，避免用户误以为导出的 profile 可以在另一台设备上直接完成 SSH auth。

### 状态、无障碍和测试合同

所有主要动作使用明确 content description：`Manage host profiles`、`Add host profile`、`Import host profile JSON`、`Copy SSH public key`、`Reset trusted SSH host`。不要只依赖 icon。

测试 tag 使用稳定语义，不使用可变标题：`host.profile.list`、`host.profile.row.<id>`、`host.profile.current`、`host.editor.transport.direct`、`host.editor.transport.ssh`、`host.editor.ssh.publicKey`、`host.test.phase.<phase>`。

### 不做清单

- 不做系统 VPN UI。
- 不展示 OpenSSH config 编辑器。
- 不支持 jump host chain、ssh-agent、FIDO/U2F key 第一版。
- 不把 Basic Auth 和 SSH auth 混在一个“Username/Password”区块里。
- 不在后台保活说明里暗示永久在线；Phase 8 的承诺是前台连接和回前台恢复。

## 不做清单

- 不重新设计信息架构（手机 = Chat/Files/Settings 底部 tab；平板 = 三栏）。
- 不引入语法高亮（代码保持纯文本——和现状一致）。
- 不做 diff viewer / session 摘要预览（功能现状没有，设计不画）。
- 不引入 Material You 动态取色。

## 与 iOS 的对齐说明

本 spec 的每条视觉决策都来自 iOS client 已落地并验证的实现（见 iOS `docs/design.md`）。Android 侧通过 Material 3 `ColorScheme` 映射达到同样的观感；平台手段不同，视觉语言一致。两个 app 因此有家族感。

---

# 工具卡渲染重做（已实现 — 对齐 iOS）

这一节就在上面 Quiet Tech 语言内，不引入任何新视觉质感——保持现有干净图标、细分隔、中性卡身。要改的是**信息组织方式**，不是皮肤：谁在说话怎么区分、工具结果怎么呈现。落点全在 `ui/chat/ChatMessageContent.kt`，分类逻辑抽到 `ui/chat/ToolCardClassifier.kt`（纯逻辑、可单测）。

之前的现状问题：(a) AI 回复和用户消息区分太弱，看不出谁在说话；(b) 所有工具一律走"通用展开式 ToolCard + 扳手图标"，patch 单独做成导航卡，工具卡彼此没有语义区分，读起来是一堆同质灰卡。

四个具体改动：(1) 说话区分；(2) 文件操作渲染成 2 列文件卡网格；(3) 其余工具合并成可展开的 "N tool calls" 行；(4) 文件夹读取展开成内容卡。

## 一、说话区分（不要头像，但要标题）

- **用户消息**：保留现有的**蓝色左竖条**（3dp accent 左 bar + `primary.copy(alpha=0.10f)` muted 底，12dp 圆角），由 `TextPart(isUser=true)` 渲染。
- **OpenCode 回复**：**不要头像/圆形图标**，在回复顶部加一个 **"OpenCode" 文字标题**（`labelMedium` + SemiBold + `primary` 电蓝，`testTag("assistant.header")`），让人一眼知道这是 AI 在说话；并**保留现有的"模型小字"**——每条 assistant 回复末尾那一行 `providerId/modelId` 的小灰字，位置和现状一样、不动。回复正文无容器、无左 bar。
- iOS 在 user 和 assistant 两侧末尾都放模型小字；Android 现状只 assistant 末尾有——本次保持 assistant 有即可，user 那行（fork 菜单已带 model 小字）不动。

核心是用**不同的 visual style**（标题 + 模型小字 vs 蓝左竖条）把两边分开，不靠头像。

## 二、文件卡（2 列网格）

**文件操作工具**渲染成新的 `FileCard` composable：左侧 `Icons.Default.Description`（doc 图标，电蓝 accent），中间该文件的 monospace basename，右侧 `ChevronRight`。一个工具一张卡，按 **2 列网格**排列——Android **不能**在 `LazyColumn` 里嵌 `LazyVGrid`，所以沿用现有的 `chunked(2) + Row` 手动两列手法（与 iPhone 2-up 一致）。这个两列网格不是文件卡专属：thinking tile 与合并的 "N tool calls" tile 也进同一个网格（见「消息区」）。

判断依据封装在 `ToolCardClassifier.isFileOperation(part)`：patch（**Android 特有要求**：须带可导航文件路径 `filePathsForNavigationFiltered.isNotEmpty()`，否则无路径的 patch 会掉出网格落到无处），或 `tool ∈ {apply_patch, edit_file, write_file, read_file}`（含 `patch/edit/write/read` 历史别名，lowercase 前缀匹配）。

basename/displayPath 优先级照 iOS：`metadata.path` → `state.pathFromInput` → `filePathsForNavigation.first`，取最后一段。点击复用现有打开文件逻辑（`onFileClick`）。`testTag("toolcard.file.<basename>")`。

## 三、合并成 "N tool calls"

**其余所有工具（bash / 测试 / grep / glob / list / webfetch / task …）合并成一个永远半宽的 tile** `ToolCallsRow`，文案 **"N tool calls"**（N = `otherParts.size`）——刻意抽象、不暴露具体类型。点 chevron 展开后逐条复用 `ToolCard` 的展开主体（tool 名 + reason + input/output），**展开内容留在半宽 tile 内**（tile 变高、网格 reflow），不撑到全宽。收起是默认态。`testTag("toolcard.toolcalls")` 挂在 tile 最外层。

一个 run 产出"N 个文件卡 tile（fileParts，一个文件操作一个 tile）+ 一个 N tool calls tile（若有 otherParts，放在 run 末尾）"，与 thinking tile 一起进同一个两列网格（见「消息区」）。分类用 `ToolCardClassifier.split(run) -> Pair<fileParts, otherParts>`。tile 按 **part 顺序**排：文件操作 tile 在前、合并的非文件 tile 随后，不分组、不加分区标题。

## 四、文件夹卡

当 `ToolCardClassifier.isDirectoryRead(part)`（服务端在 read 输出里嵌 `<type>directory</type>`）时，`FileCard` 切换成 folder 分支：图标改 `Icons.Default.Folder`（电蓝），点击弹一个 `ModalBottomSheet` 列出 `parseDirectoryEntries(part.toolOutput)` 的 entries（子目录排前，folder/file 图标区分）。**不调 API**——output 里已有 `<entries>…</entries>` 内容，直接解析渲染（在文件预览里打开文件夹本来就打不开）。`testTag("toolcard.folder.<basename>"、"toolcard.folder.entry.<name>"、"toolcard.folder.sheet.<basename>")`。

## todo 抽离

`todowrite` 工具展开时**只显示 todo、隐藏 input/output**（对齐 iOS）。todo 渲染抽成独立的 `TodoListInline` composable（对齐 iOS `TodoListInlineView`），其余工具若也带 todos 同样复用它。

## 不做 / 边界

- 不引入像素风、不分组、不加分区标题。
- 文件卡保留 doc 图标、2 列网格；AI 回复不要头像但要 "OpenCode" 标题；模型小字保留不动。
- 模型选择器不动，不要右上角用户头像。
- 两形态都用现有 Quiet Tech 中性卡身（`surfaceVariant` 底、无描边、12dp 圆角），彩色只落在图标和 chevron 上——全屏至多一处蓝。
