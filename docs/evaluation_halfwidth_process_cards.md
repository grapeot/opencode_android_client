# Evaluation：thinking / tool call 卡片改半宽（只读评估）

- 日期：2026-09-27
- 状态：评估完成，未改任何代码
- 范围：Android 客户端聊天界面（`ChatMessageContent.kt`），iOS 客户端作为跨平台成本参照
- 问题契约：读完后能用普通语言回答——现状长什么样、半宽对用户有什么真实 benefit、实现要付什么 cost、ROI 结论是什么

## 首屏结论

**意图成立，机制不对。** "process 卡片（thinking / tool call）不该和回答正文抢视觉主角"这个诉求是对的，也是 chat UI 的通行做法。但行业通行的解法不是收窄宽度，而是降低视觉重量（去/减弱卡片底、单行、小字、全宽）。半宽两列是"紧凑自包含物件"（文件卡）的模式，用在"点开才有长内容"的 thinking / tool call 上属于模式错配：收起态放得下半宽，展开态放不下。

三句普通语言复述（供抽查）：

1. 现在聊天里回答文字占满整行，thinking 和 tool call 卡片也占满整行，只有文件卡是左右两列各占一半。
2. 把 thinking 和 tool call 也改成只占一半宽度，好处是让回答更突出、过程卡片更安静，坏处是点开后里面的长文本在窄列里换行翻倍、读起来更费劲，而且 iOS 端现在也是全宽，只改 Android 会造成两个客户端不一致。
3. 建议先花小成本把过程卡片"减重"（去掉卡片底、改成安静的单行），而不是改半宽；如果一定要半宽，只在 thinking 卡上做实验，tool call 和文件卡维持现状。

核心对比（三个选项）：

| 选项 | 做什么 | 用户 benefit | 实现 cost | 主要风险 |
|---|---|---|---|---|
| A 减重 | 全宽不变，去/减弱卡片底，单行小字 | 高（拿到 80% 感知收益） | 低（每端约 1 小时） | 低，纯视觉 QA |
| B 半宽 | 收起态半宽、展开态全宽，streaming 保持全宽 | 中（主要是 thinking 卡） | 中（每端 0.5–1 天 + 视觉 QA + 重跑 UI 测试层） | 中（展开时布局跳变、3 种宽度态、"半宽孤儿"观感、跨端不一致） |
| C 统一网格 | thinking + tool call + 文件全部进两列网格 | 低（相对 B 的边际收益很小） | 高（每端 2–3 天，MessageRow 缓冲逻辑重做） | 高（streaming 内容进静态网格、iOS LazyVGrid 重排、文件/工具/思考三种卡片被压平成同质瓦片） |

**推荐：A 双端一起做；B 作为 thinking 卡的后续小实验；C 不做。**

## 现状：三档卡片体系

聊天消息流里现在有三档视觉层级（Android，`app/src/main/java/com/yage/opencode_client/ui/chat/ChatMessageContent.kt`）：

| 档位 | 内容 | 宽度 | 证据 |
|---|---|---|---|
| 一档：回答正文 | assistant markdown 文本 | 全宽、无容器（主角） | `ChatMessageContent.kt:771-787` |
| 二档：文件瓦片 | 文件操作类工具（patch/edit/write/read）→ `FileCard` | 两列各半（`chunked(2)` + `Row` + `weight(1f)`） | `ChatMessageContent.kt:305-319` |
| 三档：过程卡片 | thinking（`ReasoningCard`）、"N tool calls"（`ToolCallsRow`）、单工具 `ToolCard` | 全宽、有 `surfaceVariant` 卡片底 | `ChatMessageContent.kt:828-891`、`:704-736`、`:930-1069` |

两个关键事实，直接决定本次评估的成本结构：

- **两列是有意决策，且明确以 iPhone 为基准**。`docs/design.md:65`："工具/patch 卡片在手机上走 `run.chunked(2)` 两列网格（信息密度优先；与 iPhone 一致，不改单列）"。iOS 端 `MessageRowView.swift:554-583` 用 `LazyVGrid` 2-up（iPhone）/ 3-up（iPad）。所以"文件卡半宽"不是历史遗留，是两端共同的设计语言。
- **thinking 和 tool call 在两端都是全宽**。iOS：`MessageRowView.swift:619-648`（`ThinkingCard`，`.frame(maxWidth: .infinity)`）和 `:585-610`（`toolCallsRow`，同样全宽 + 背景 + `DisclosureGroup`）。Android 与 iOS 代码注释多处标注 "matches iPhone" / "Mirrors iOS"。任何一端单独改宽度都会造成两端不一致——这本身就是 UX cost（同一产品两个壳两种语言），双端一起改则成本翻倍。

另外两点影响体验细节的现状：

- **streaming thinking 是独立的列表级 item，自动展开、全宽**（`ChatMessageContent.kt:192-204`，展开逻辑 `:834-838`）。用户"看它想"的时候，内容在持续流式刷新。
- **tool call 已经按连续 run 合并成一张卡**。`MessageRow` 把连续 tool/patch 段缓冲后用 `ToolCardClassifier.split` 拆分，非文件工具合并成单个 "N tool calls" 卡（`ChatMessageContent.kt:285-328`）。所以哪怕一个 turn 有 20 个工具调用，屏幕上往往只有 1–3 张 tool call 卡；而 thinking 卡是每个 reasoning part 一张，agentic 长 turn 里数量才真正多。

## Benefit 分析（UX best practice 视角）

先还原诉求：鸭哥的观察是"过程卡片占一整行，和回答正文一样重"。这个观察背后的真实问题是**层级**——过程元数据（thinking、tool call）是次要内容，不应该和工作产物（回答）竞争视觉地盘。这一点是成立的，design skill 里的原则也是同一方向："不要让反馈性 UI 挤占实际工作产物"。

但"收窄宽度"是否实现了这个意图，要分开看。

**第一，半宽不省纵向空间。** 收起态的卡片就是一行标题（约 44–56dp 高）。半宽只减少横向视觉质量，滚动长度一点没变。chat 流里用户感知"重不重"主要来自纵向堆叠和卡片底色，不是宽度。所以半宽的 benefit 是真实但有限的：它改变的是"横向铺满"带来的压迫感，不是信息密度。

**第二，两列瓦片模式适合自包含物件，不适合披露式长内容。** 文件卡是"图标 + 名字 + chevron"，点开的动作是导航（跳文件 / 打开目录 sheet），不在卡片内展开长文本——所以它放进两列网格是模式匹配。thinking 和 tool call 是"标题 + 点开后在原地读长内容"：`ToolCard` 展开体是 monospace 的 input/output 和文件路径（`ChatMessageContent.kt:1019-1064`），`ReasoningCard` 展开体是整段 markdown（`:876-888`）。把这种内容的容器收窄到半列，收起态没问题，展开态就崩了。具体数字（以 360dp 中等屏估算）：全宽时卡片内文本约 312dp，monospace 每行约 43 字符；半宽后约 140dp，每行约 19 字符。一条 120 字符的命令，全宽 3 行，半宽 6–7 行——展开卡反而变高，"更紧凑"的收益在用户真正要看内容的状态里被抵消。

**第三，行业通行做法是全宽减重，不是半宽。** ChatGPT 的 "Thinking…"、Gemini 的 "Thought for N seconds"、Claude web 的工具行，都是全宽、单行、低视觉重量的文字行，靠字号和无/轻底色表达"这是次要内容"。（此处为基于通用认知的方向性判断，本次评估未附竞品截图证据，按 directional 处理。）共同点：**宽度这个轴留给文字阅读，用重量表达层级**。

**第四，半宽对 tool call 的边际收益比直觉小，对 thinking 的收益最大。** tool call 已按 run 合并（见现状），屏幕上卡片数量本来就少；thinking 卡在长 turn 里数量多、重复度高，是"卡片墙"观感的主要来源。如果做半宽，收益排序是 thinking ≫ tool call。

**第五，一致性收益是半宽最实的一条。** 把过程卡片都变成半宽瓦片，"凡不是回答的都是瓦片"，形成统一的过程层语言——这正是文件卡已经建立的语言。代价是三种卡片（文件 / 工具 / 思考）被压成同质瓦片，靠图标形状（doc/folder vs wrench vs brain）区分，扫读时"哪些文件被改了"和"agent 干了什么"的边界变模糊。

**benefit 小结**：真实存在，集中在"长 agentic turn 的回答更突出、thinking 卡片墙更安静"；但大部分收益可以用"全宽减重"拿到，半宽独有的增量主要是瓦片语言一致性。本 app 无埋点、唯一用户是自己，以上 benefit 均为体感判断，不是测量值。

## Cost 分析

**实现 cost（Android 视角）：**

- 选项 A 几乎零结构改动：改 `ReasoningCard` / `ToolCallsRow` 的 `colors`、字号、内边距即可，`ChatMessageContent.kt:704-736` 和 `:828-891` 两处。无新状态、无布局逻辑变化。
- 选项 B 是 modifier 级改动但引入**第三种宽度态**：streaming 全宽 / 收起半宽 / 展开全宽。展开瞬间宽度从 50% 跳到 100%，不带动画是可见的"pop"，带动画在 reverse-layout 的 `LazyColumn` 里（`ChatMessageContent.kt:186-189`）会触发整列重排；自动滚动逻辑（`:113-135`，以 `firstVisibleItemScrollOffset <= 24` 判定贴底）对 item 尺寸变化敏感，展开导致的行高变化可能造成滚动位置跳动。
- 选项 C 要重做 `MessageRow` 的 run 缓冲（现在只缓冲 tool/patch 段，reasoning part 是独立 part 逐个渲染），并且 streaming reasoning 目前是列表级独立 item（`:192-204`），要并进消息内网格。iOS 侧 `LazyVGrid` 里瓦片高度增长会触发网格重排，Android 侧 `chunked(2) + Row` 也会重折行。

**UX cost：**

- **展开态拥挤**（如上，半宽 monospace 换行翻倍）——这是半宽方案最硬的 UX 伤，只能通过"展开时回全宽"绕开，而绕开就引入了宽度态切换。
- **streaming 态**：流式 thinking 在半宽下持续换行刷新，短行高频 reflow，"看它想"的体验明显变差。必须保持全宽，又多一个宽度态。
- **tap target**：`ReasoningCard` 的展开入口只有一个 24dp 的 `IconButton`（`ChatMessageContent.kt:866`，本身已低于 Material 48dp 下限），半宽后它落在屏幕横向中点。单手够得着中点（比右缘好），但 24dp 的精度问题没解决；`ToolCallsRow` 整行可点（`:711`）则不受影响。
- **"半宽孤儿"观感**：半宽卡后面紧跟全宽正文，左右宽度跳变，处理不好像布局错乱而不是刻意层级。这需要视觉 QA 把关，是 A 方案没有的成本。
- **跨端不一致**：只改 Android，两个客户端同一产品两种卡片语言；双端改，工时翻倍。

**测试 cost：** 语义层 testTag 挂在卡上（如 `"toolcard.toolcalls"`、`"toolcard.read.$basename"`），宽度变化不影响 semantics 树；但项目还有 LLM 驱动的视觉 QA 层（`docs/ui_test_prompts/`），布局变化需要重跑并可能更新预期。两端都要过模拟器截图 QA。

## 结论与建议

**ROI 排序：A > B（仅 thinking）≫ C。**

1. **先做 A（双端同步）**：thinking 和 tool call 卡去/减弱 `surfaceVariant` 底，收起态压成单行小字（对齐 iOS 已有观感），全宽保留。这拿到"过程不抢主角"的大部分收益，约每端 1 小时，无布局风险，且与行业通行做法一致。
2. **如果瓦片语言一致性是明确的设计偏好，再做 B 的 thinking-only 实验**：streaming 全宽、收起半宽、展开回全宽（宽度切换不做动画，先接受 pop），只在 `ReasoningCard` 上改，模拟器截图对比后再决定是否推广到 `ToolCallsRow`。tool call 因已合并成少量卡片，收益最低，放最后。
3. **C 不做**：边际收益小、两端各 2–3 天、streaming 进静态网格和网格重排是真实工程风险，且压平文件/工具/思考的层级区分。
4. **无论选哪个，双端同步定案**。文件卡两列已经是以 iPhone 为基准的有意决策（`docs/design.md:65`），过程卡片不应成为两端分叉点。
