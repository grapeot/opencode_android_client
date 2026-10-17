# 会话树单灯：产品与实现合同

状态：本地实现与验证；发布由主线程负责。来源内容已读与父行蓝色提醒确认分开维护。

## 产品规则

列表父、子、孙行及 composer 使用同一个判断：归档无灯；待处理权限或提问为黄；未确认失败为红；自身或任意已知后代 busy/retry 为绿；已知子树均不活跃且有本行尚未确认的未读更新提醒为蓝；其余无灯。当前会话仅豁免蓝灯。绿色表示执行未结束，不承诺持续产出或任务成功。父行无蓝不代表子内容已读。

来源先确认，再聚合：`failedAt[source] > viewedAt[source]`；未读要求正数更新时间、来源执行不活跃且 `updatedAt[source] > viewedAt[source]`。每个有效来源贡献给自身及已知祖先，祖先自己的查看时间不参与子来源过滤。查看子会话后所有祖先减去该来源；查看父会话不会确认子会话。其他兄弟和父自身的提示独立保留。确认与失败存储继续按 host profile 隔离。

蓝色另有逐行确认映射 `SessionReadState.blueAckBySource[sourceID] = updated token`，默认空映射，兼容原 JSON。`SessionTree.blueReminderSourcesBySession` 先按上述来源未读谓词过滤，再防环上卷；若祖先行保存的 token 与当前正数 updated 相等，仅抑制这一行的贡献，继续向更高祖先传播。来源自身不受映射抑制。`AppState` 从同一份可信状态 presentation 派生来源映射与计数，整图计算 O(N×depth)，不逐行重算。

`MainViewModel.selectSession` 开始时，只有目标行实际显示 UNREAD 且非 unknown，才同步确认当时的来源快照；随后执行原 markViewed、current 切换及加载。没有异步间隙，不会将迟到数据写入另一 host。映射只保存非 self 的正数版本，不以 now 或子树最大时间作阈值。P 确认 C=100、D=200 后，C=101 或新补载 E=50 都能重新提醒；只确认 P，不确认祖父或子。看 C 仍按原 viewedAt 从所有祖先消除 C 的来源贡献。

显式选择入口与自动导航分开：用户点选（含主动打开子会话或会话链接）使用 `selectSession`，随后调用共享的 `navigateToSession`。列表刷新/分页缺少 current 时的自动选择、删除后的自动替补、创建/分叉后的导航及解除归档后的导航直接使用 `navigateToSession`，不写 `blueAckBySource`。自动导航仍保持原有自身 markViewed、草稿切换和消息/状态加载行为；冷启动恢复 current 不新增确认。独立审查发现的自动选择误确认已按此边界修复。

停留 P 时后来出现的子版本不会自动确认；切走、后台、刷新、冷启动恢复及 current 无蓝重选均不扩大快照。黄、红、绿、unknown 和归档行不会顺带确认潜在蓝色。分页移出保留映射，明确删除来源才从当前 host 的所有行清理引用（包括来源没有自身存储记录的情况）。

本轮与 iOS e2a1788 的有界实现同构：保留来源 `updated > lastViewedAt` 的时钟局限，相同 updated 的不同内容仍不可区分；没有引入 self 版本模型、消息 revision 或新同步协议。

后续 busy/retry → idle 不确认历史失败：尚未查看的失败继续保留，忙状态仍先黄/红后绿。成功轮次仅可清理 `failedAt <= viewedAt` 的已确认历史，不能代替用户查看来源。

列表上的自身 running 只由呼吸灯表达，不再写 Running。黄红遮盖绿色时不补回 Running 文字。后代数量用分支图标加数字，朗读仍用完整的「N 个子代理运行中」；不把这句话画在行上。Retrying 和「状态待同步」仍是文字。断线或执行事实不确定时禁止蓝灯，没有黄红或可信活动则无彩灯并显示“状态待同步”；黄红仍保留并附同步提示。存在一个可信活跃后代即可显示绿，但不能因没有发现活跃项便宣称未知后代停止。

## 状态与同步

保留现有 AppState 与同步层，仅新增观察元数据：runtime generation、连接 epoch、请求起始 revision、逐 session SSE revision、成功快照的 directory 集合及请求在途标志。SSE 每次状态事件都增加 revision，包括同值事件。状态 GET 单在途；结果只合并请求起点后没有新 SSE 的来源。旧 host/旧连接 epoch 的结果丢弃。断线、后台、刷新失败和确认过期仅使知识失效，不写入假 idle。

`null` 在成功、完整、同 host、同 directory 的状态快照确认缺项之后才解释为 inactive。针对已加载会话的 directory 逐个请求 `/session/status?directory=…`，这一轮全部成功后才发布完整确认；无会话时保留默认状态 GET 以供引导，但不将其缺项确认扩展到后来加载的项目。其他项目的既有状态不由本项目空快照清零。

缺项确认只覆盖请求开始时已加载的 session ID；同项目后来新增或补载的 session 仍须等待新 SSE 或下一轮快照，不能沿用旧目录快照直接判空闲。

回到前台刷新状态与 pending；现有 watchdog 在前台每 30 秒对状态做低频确认，即使心跳持续也能修复漏 idle。快照确认与 SSE 确认分开保存：60 秒快照过期或 GET 失败，仅撤销依赖快照的事实；同连接的 SSE（包括同值事件）保持有效。成功快照若改变了某来源状态，该来源改为依赖新的快照；未改变则保留其 SSE 证据。断线、切 host 或后台仍撤销两类实时知识。无剩余证据的来源显示待同步，后续轮询恢复。轮询不按行创建，也不轮询消息来模拟执行。

权限 `permission.replied` 使用 session ID 与 request/permission ID 精确删除；字段不完整则刷新权限快照。每次重连刷新 permissions 和 questions，即使没有 current session。两类 pending 独立使用事件 revision 与请求版本：新事件使旧 GET 失效并补刷，较早 GET 不能覆盖较晚请求。成功回复才删除；HTTP 失败、关闭卡片、idle 均不删除 pending。

权限回复显式检查 HTTP 2xx；403/500 等转为失败，保留 pending，错误只含状态码，不包含响应正文。Repository 的 REST/SSE 客户端均移除完整 URL 网络日志，debug 也不记录 directory、会话路径、认证信息或响应正文。

abort 只请求中断，之后刷新权威状态；不预写 idle、不把 HTTP 成功当成任务成功，也不终止后代的显示。

## 展示与动效

共享 `SessionIndicatorDot`，保持 chevron、层级、点击和滑动。列表灯列与完整文字 Column（标题及时间/状态副文字）并列，整体垂直居中；无副文字时对标题居中。通过 Row 的布局对齐，不使用垂直 offset 修正。保留 48dp chevron/Spacer、树缩进和原内容横向位置，metadata 无需额外灯列 padding，无灯也保留稳定的 16dp 灯列。

Android 相对较小的标题字体采用更小的点：绿蓝基础 5dp、上限 7.5dp；黄红基础 6dp、上限 9dp，跟随 fontScale 并 clamp。列表与 composer 共用尺寸，语义及轻重分级与 iOS 一致，像素尺寸按平台字体比例调整。running 使用独立 token：浅色 `#167C45`，深色 `#4CBF82`，不复用 success。绿灯 2.6 秒透明度 0.40↔1.0，最低值由 `SessionIndicatorTuning.runningMinOpacity` 定义；无缩放、位移、光晕或闪灭。系统关闭动画、后台、离屏时常亮或停止动画。动画状态只在绘制层读取，不触发行图、排序或确认逻辑逐帧重算。

历史：初版透明度范围为 0.75↔1.0。2026-10-14 根据真机反馈增强呼吸幅度，最低值改为 0.40；周期、尺寸、绿色 tone 和状态规则保持原规格。

后续视觉反馈确认呼吸幅度后，Android 将原绿蓝 6→9dp、黄红 8→12dp 缩小为上述当前尺寸，并把列表的标题行居中改为完整文字块居中；保留已确认的呼吸和状态规则。

列表与 composer 各保留单灯；文字采用中性色。composer 的后台任务导航入口保留，执行摘要仍明确区分自身与后代。状态图按已加载会话 O(N×depth) 生成，逐行读取结果。

## 边界与取舍

树范围仅限已加载 session 和已知 parent 链。折叠不缩小范围，分页未加载或缺失中间祖先会截断聚合。归档只隐藏该行灯，不取消真实 pending，也不排除其对活跃祖先的贡献。兼容服务端必须遵守 directory 状态快照完整性合同；不支持该端点的服务保持待同步，不伪造已结束。

失败仍以收到的 `session.error` 和随后 idle 为来源；离线期间错过的 error 不保证恢复。消息错误及任务回执重建失败不是本次范围。查看表示打开确认，不证明用户读完。时间戳沿用已有客户端机制；没有新增事件历史或同步已读协议。

## 验证

单测覆盖来源确认、多级和兄弟独立性、父自身事件、未知与已确认缺项、优先级、最后子结束、动画开关，以及真实同步入口的 replied、旧 GET、新事件、同值 SSE、重连和 host generation。构建顺序为 `testDebugUnitTest`、`assembleDebug`、`assembleDebugAndroidTest`。UI 测试仅指定 emulator；证据留在 ignored build scratch。具体执行结果见 `working.md`。

父级蓝点回归通过真实 selectSession 覆盖打开 P→X、子仍未读、祖先独立、兄弟乱序 token、新补载、current 期间新版本、非蓝不确认、分页与删除、冷恢复和跨 host；存储测试覆盖旧 JSON、有效 token 过滤和重启，纯 helper 测试覆盖防环及来源已读优先。

自动导航回归驱动真实 loadSessions/loadMoreSessions（current 为空或指向不存在的 ID）、deleteSession、restoreSession 及冷恢复后的刷新；断言自动进入蓝色父行不确认子来源，切走仍蓝，随后显式点选才写入来源快照。
