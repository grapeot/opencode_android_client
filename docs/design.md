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

## 卡片三态语言

按功能分三类形态，这是 Quiet Tech 的核心，取代"多色同形的彩虹卡片"：

1. **信息卡片**（`ToolCard` / `PatchCard` / `ReasoningCard`）：中性 `surfaceVariant` 底，12dp 圆角，无重描边。**但它们是可交互的**（点开看 input/output、跳文件预览），所以卡片**内部的可操作元素**——工具图标、工具名、可跳转的文件路径、展开 chevron、OpenInNew 图标——用 `colorScheme.primary` 电蓝着色作为"可点"暗示；卡身保持中性。纯展示文字（tool reason、output 预览）走 `onSurfaceVariant` 灰。**不要把整张卡灰掉**——那样读起来像禁用控件（iOS 上踩过这个坑）。
2. **操作卡片**（`ChatPermissionCard` / question card）：中性 surface + 左侧一条 3dp 电蓝色条（`colorScheme.primary`）作"请你操作"的功能信号，配纯文字按钮（TextButton）——Allow 类电蓝、Reject 灰，不用绿/蓝/红实底按钮。
3. **状态行**（turn activity、elapsed 计时）：不是卡片，纯 `onSurfaceVariant` 文字。

圆角统一：信息/操作卡片 12dp，sheet 16dp，inline tag 6dp。

## 消息区

- **用户消息**（`TextPart` when `isUser`）：3dp 电蓝左色条 + muted 蓝底（`primary.copy(alpha = 0.10f)`），12dp 圆角。和操作卡片、选中行同构——"左色条"语言贯穿全 app。
- **AI 回复**：无容器，全宽纯文本 / markdown。

工具/patch 卡片在手机上走 `run.chunked(2)` **两列网格**（信息密度优先；与 iPhone 一致，不改单列）。

## Composer（`ChatInputBar`）

- 单个圆角 pill（20dp），底色 `surfaceVariant`，从近黑背景上浮起。用 pill 内的无边框 `BasicTextField`（无可见描边/indicator）。
- 布局与 iOS `ChatTabView` **完全相同**：单行 `Row(verticalAlignment = Bottom)`，从左到右 **mic 图标**（框内左、无描边、`onSurfaceVariant` 灰；录音红 `StopRed`；转写中转圈）→ **文本框**（`weight(1f)`，无独立背景，共享 pill 底，`TopStart` 对齐）→ 右侧**主操作竖排 Column**。图标与文字同在一个 pill 内、底部对齐——iOS 本就如此。
- **文本框约三行高**：`heightIn(min = 66.dp, max = 132.dp)`（≈3 行起、≈6 行封顶后内部滚动）。
- **send 始终存在**——实底电蓝（`colorScheme.primary` = `#3B82F6`）圆角方块（36dp，12dp 圆角）+ 白色箭头；`!canSend` 时 alpha 降到 0.35。**stop 仅 busy 时出现**，作为**额外**的实底红方块（`StopRed #E5484D`）**堆叠在 send 下方**（不是替换 send、不是并排）。这对齐 iOS：send 在上 / stop 在下，运行中也能直接发新消息，不必先终止。
- `canSend = text.isNotBlank() && !isTranscribing`。

## Toolbar（`ChatTopBar`）

- **模型选择器**：从实底蓝胶囊改为**描边 chip**——透明底 + 1dp 电蓝边（primary @30% alpha）+ 电蓝文字。蓝作 accent 不作大块填充。
- 其余图标按钮（session 列表、rename、settings）：朴素 glyph，`onSurfaceVariant` 灰；突出的"新建"动作可用电蓝。去掉图标背后的填充圆。
- **Context ring**：track 用 `onSurfaceVariant` 低 alpha，进度用电蓝（不是紫），高占用阈值才切金/红（保留原阈值逻辑）。

## Session 列表（`SessionList` / `SwipeRevealRow`）

- **选中行**：3dp 电蓝左色条 baked 进 12dp 圆角的选中底（`primary.copy(alpha = 0.08f)`），整体 clip 在圆角内——色条不会戳出圆角、不与展开 chevron / 缩进冲突。
- 未选中行透明（去掉交替条纹底，取更干净的 Quiet Tech 观感）。
- 保留：swipe 删除、展开折叠、depth 缩进、busy 标题色、状态文字。

## Settings（`SettingsSections`）

- **Theme**（LIGHT / DARK / SYSTEM）：分段控件（`SingleChoiceSegmentedButtonRow`），选中段电蓝。
- Switch / toggle accent 用 `colorScheme.primary` 电蓝。

## 不做清单

- 不重新设计信息架构（手机 = Chat/Files/Settings 底部 tab；平板 = 三栏）。
- 不引入语法高亮（代码保持纯文本——和现状一致）。
- 不做 diff viewer / session 摘要预览（功能现状没有，设计不画）。
- 不引入 Material You 动态取色。

## 与 iOS 的对齐说明

本 spec 的每条视觉决策都来自 iOS client 已落地并验证的实现（见 iOS `docs/design.md`）。Android 侧通过 Material 3 `ColorScheme` 映射达到同样的观感；平台手段不同，视觉语言一致。两个 app 因此有家族感。
