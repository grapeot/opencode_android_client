# 测试策略

本文件定义 OpenCode Android 客户端的整体测试策略。重点是 UI（UX）测试体系，分四层：unit、component、integration-UI、LLM-driven-UI。后端/数据存储测试列出纲要，细节待补。

逐次改动的工作记录在 `docs/working.md`，不在本文件。本文件回答的是体系问题：每一层测什么、怎么跑、依赖什么前提，而非某次具体改动验证了什么。

## 为什么分这四层

四层不是按工具分，是按它们回答的问题分，以及各自的成本和触发频率分。

第一，unit 和 component 都是隔离测试：喂假数据、断言单个单元的输出，不连任何 server，零成本，每次 commit 都该跑。区别在 component 需要 Android runtime（Compose 渲染要在 emulator 或 Robolectric 上跑），unit 不需要。把这两层分开是因为现有的三个 `*InstrumentedTest`（`ChatInputBarInstrumentedTest`、`SettingsSectionsInstrumentedTest`、`SessionListInstrumentedTest`）虽然用了 UI 测试的 API，本质却是隔离组件测试——它们 `setContent { ... }` 手动构造一个 Composable、把参数写死传进去，不启动真实 app、不连 server、不加载真实 session。把它们叫 UI 测试会让人以为已经覆盖了真实链路，其实没有。

第二，integration-UI 才是连真实 app、真实 server、真实 session 的那一层。它回答的是「app 真的连上 server、加载一个真实 session、用户看到的整屏 UI 对不对」。这一层用定死的 testTag + 固定 expectation 来断言，守的是已知会变的契约（读图标、写图标、各 element 的存在与文案）。它的盲区是只能测预先想到要断言的东西。

第三，LLM-driven-UI 补 integration-UI 的盲区。它不依赖预先写好的 assertion，由一个 agent 拿着用户场景目标和验收标准，自己驱动真实 app、读 semantics tree、判断渲染是否合理。它能抓到没人预料到去断言的 regression。代价是慢、判断有非确定性、消耗 LLM token——这是唯一有 token 开销的一层，下面三层全部零成本。所以它按需或定期跑，不进每次 commit。

这四层从下到上，确定性递减、成本递增、覆盖的「未知」递增。下面三层守已知契约，最上面一层探未知问题。

## 第一层：unit

纯逻辑测试，不需要 Android runtime。位置 `app/src/test/`，框架 JUnit4 + Mockk + Turbine + OkHttp MockWebServer。

这一层已经成熟（19 个文件，约 220 个 case）。tool call 的读写分类逻辑就在这层测——`ToolCardClassifierTest` 覆盖 `ToolCardClassifier` 把 read/write/edit/patch 分流成文件操作、其余收进合并行的逻辑。ViewModel、工具函数、消息选择、session 树等纯逻辑也都在这层。NFC Quick Prompt 的触发逻辑（enable 检查、pending action 设置、auto-send vs fill-only）在 `NfcQuickPromptTest` 中覆盖。

跑：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest
```

成本零，每次 commit 跑。新增纯逻辑时优先在这层补测。

## 第二层：component

隔离单个 Composable + 假数据，跑在 Android runtime 上。位置 `app/src/androidTest/`，框架 `androidx.compose.ui.test`（`createComposeRule()`）。

现有三个 `*InstrumentedTest` 属于这层。它们的模式是 `composeRule.setContent { MaterialTheme { SomeComposable(写死的参数) } }`，然后用 `onNodeWithTag` / `onNodeWithText` / `onNodeWithContentDescription` 断言。`SessionListInstrumentedTest` 喂 40 个 hardcode 的假 `Session` 测滚动和分页触发，是这层的典型样本。

**读写卡片的 UI 在这层测。** 包括 write 卡片——write 不能在连真实 server 的层测，因为真实 write tool 会污染工作目录（见「边界与安全」）。所以 write 卡片的渲染验证放在这层：构造一个假的 write part 喂给 `FileCard`，断言它渲染出写的标记和颜色；同样构造 read part 断言读的标记。这样读写两种 UI 都被覆盖，且零风险。

这一层的前提是读写卡片在 semantics tree 里可区分——已落地（`FileCardInstrumentedTest` 即断言读写卡片各自的 testTag 和 content-desc），见「贯穿全局的前提」。

跑：

```bash
./gradlew connectedDebugAndroidTest
```

需要一个连接的 emulator 或真机。成本零（不连 server、不调 LLM），可每次 commit 跑。注意 CI 当前只跑 unit（`testDebugUnitTest`），component 层未进 CI——若要进 CI 需要在 runner 上配 emulator。

## 第三层：integration-UI

连真实 server、加载真实 session、定死 testTag 断言整屏。已落地：`ReadToolCardIntegrationTest` 是第一个——连 4097、建 session、发读 prompt、等真实 read tool call、把真实消息喂进真实的 `ChatMessageList` 渲染路径、断言读卡片渲染。

### 连什么 server、怎么填 credential

连本地 OpenCode server。`start_opencode.sh` 起两个：4096（`opencode.db`，main）和 4097（`opencode_batch.db`，batch）。credential 是本地临时密码，记在项目 `.env`（已 gitignore，不进仓库）和 opencode_skill 的 `.env`——用值时从 `.env` 读，不要把它写进任何被提交的文件。

credential 注入复用现有的成熟链路，不要发明新机制：`.env` → `build.gradle.kts` 读取 → `testInstrumentationRunnerArguments` → 测试里 `InstrumentationRegistry.getArguments()` 取值 → `repository.configure(baseUrl, username, password)`。`OpenCodeIntegrationTest` 的 `@Before` 已经是这个模式。credential 只活在测试进程里，不落 app 磁盘、不进编译常量、不进仓库——靠 `.gitignore` 这个机制保证不泄露，而非靠纪律。

### 怎么准备 session 数据

server 没有「直接塞一条构造好的 tool call 消息」的 API。端点只有 `POST /session`（建空 session）和 `POST /session/{id}/prompt_async`（发文本 prompt）。`PromptRequest.parts` 的 type 写死是 `text`。

所以造数据走「发一个几乎必然触发某个 tool 的文本 prompt，让 LLM 自己去 call」这条路。对 read，用「读取文件 X 并复述第一行」这类 prompt，触发率接近 100%，且 read 不写文件系统、安全。这一层只跑 read 类场景。write 类不在这层造数据，原因见「边界与安全」。

这种做法把确定性从「数据固定」换成了「行为高概率」。对 read 足够稳。

### 模型可配置，无默认

agent/模型通过 `.env` 配（`OPENCODE_AGENT` + 可选 `OPENCODE_MODEL_PROVIDER`/`OPENCODE_MODEL_ID`）→ `build.gradle.kts` 读 → instrumentation args → 测试读取。**未配 `OPENCODE_AGENT` 时测试 skip + warning，不默认**（默认到一个 server 跑不了的 agent 只会假失败）。

迭代速度上，选官方 DeepSeek flash 而非本地 ds4：保留一个能跑的 agent（如 `build`），把模型 override 成 `deepseek` / `deepseek-v4-flash`。本地 ds4 免费但慢（~12s 出 read tool call），官方 flash 几乎免费且快（~2s）。Anthropic 系 agent（build/general 的默认模型）需要 server 上有有效 API key，缺了会报 `invalid x-api-key`，所以用 model override 绕开。

### 怎么加载 session 并断言

不需要 app 侧的「测试模式」入口。`ReadToolCardIntegrationTest` 的做法是：通过 `repository`（public 的 `configure`/`createSession`/`sendMessage`/`getMessages`）走完真实 server 往返拿到真实消息，再把这些消息喂进真实的内部 composable `ChatMessageList`（`createComposeRule().setContent`），断言渲染结果。这样既走了真实 server、真实 session、真实 LLM 产出的 tool call、真实渲染链路，又不必为了测试在 app 里加一段生产代码。

断言用定死 testTag + 固定 expectation：读卡片断言 `toolcard.read.*` 存在（前缀匹配，不依赖模型读了哪个文件）。非确定性用 `assumeTrue` 吸收——server 不可达、或模型超时没产出 read part，则 skip 而非 fail，且 skip 原因写进 logcat（test APK 跑完会被卸载，stdout 会丢，logcat 留得住）。

成本零（本地 server，不调 LLM），可每次 commit 或每日跑。CI 当前只跑 unit；要把这层进 CI 需要在 runner 上配 emulator + server。

## 第四层：LLM-driven-UI

由 agent 驱动真实 app、读 UI tree、按验收标准判断，靠 agentic loop 自我纠错。设计范式来自博客《从过程确定性到结果确定性》。已落地：一个 Python CLI（`ui_driver/`）+ 两个 skill + 第一个 test prompt。

### 核心范式：结果确定性，不是过程确定性

下面三层是过程确定性——预先把每个断言写死，系统只能检查想到要检查的东西，天花板是设计者的想象力。这一层是结果确定性——不规定 agent 每一步怎么走，只规定终点长什么样、怎么验证到了终点。

这是这一层存在的全部意义。如果在 prompt 里规定 agent 先点哪、再点哪、点什么坐标，就退回了过程确定性，那不如直接写 integration-UI 的 instrumented test。这一层的价值在于：给一个用户场景目标和验收标准，让 agent 用工具自己想办法走通、自己判断对错。

### 结构：CLI 是 skeleton，agent 做判断，test 是 prompt

固化的动作序列（配 server、发 prompt、导航、读屏）下沉到一个确定性 Python CLI，agent 只保留需要判断的部分。这把过程确定性的部分用 CLI 几秒钟跑完，避免 agent 每个动作都现场推理（实测：让 agent 一步步点着配 server 要几分钟，CLI 的 `configure-server` 十几秒）。

- **`ui_driver/` CLI = 运行时层（skeleton）。** 把 adb/uiautomator 的重复动作序列绑成命令，每个会改 UI 的命令返回操作后的 UI tree（JSON）；高层命令 `configure-server`、`send-prompt`、`select-model` 把固化流程绑死。CLI 本身有 unit/integration test。用法见 skill `docs/skill_operate_emulator.md`，命令参考见 `ui_driver/README.md`。
- **agent = 判断层。** 只做需要灵活性的部分：读返回的 tree、决定等多久、判断 UI 对不对。
- **每个 test 是一个 prompt**（`docs/ui_test_prompts/`）：用户场景目标 + 验收标准，写到「健忘症实习生」粒度——一个没有上下文、只读这一个 prompt 的 agent 据此能判断自己验证完了没有。
- **跑 test 是开 sub-agent**：把 prompt + 两个 skill 交给它，它用 CLI 跑 act → 观测返回 tree → 判断 → 下一步 的循环，最后给 PASS / FAIL / BLOCKED + 证据。

两个 skill 分层：`skill_operate_emulator.md`（怎么用 CLI 操作 client）和 `skill_ui_test_tasks.md`（怎么用前者完成测试任务）。

test prompt 该长这样：「验证用户能进入一个 session 并看到读操作被标成读。」而不是：「点 tab、点第一个 session、检查第三个卡片的 testTag。」前者把怎么走交给 agent，后者把 agent 降级成一个慢速的 instrumented test。

### tree 里靠 content-desc 而非 testTag

uiautomator dump 出的 tree **不含 Compose 的 testTag**（app 没设 `testTagsAsResourceId`）。所以这一层 agent 读的是 content-description，不是 testTag。这就是为什么读写卡片除了 testTag 还带了 content-description（"Read file X" / "Write file X"）——testTag 服务第二、三层，content-description 服务这一层。新增需要这层识别的 UI 时，给它一个 content-description。

### coverage 不是目标

不追求穷举覆盖。目标是走通基本用户流程（进 session、看读写区分、切 tab、看连接状态）。它补下面三层的盲区，不替代它们。尤其适合异步、时序相关、确定性断言写不出来的行为（如「发消息后标题该自动刷新」），用 agent 真去操作、等一段时间或某个事件、再判断。

### Tier 4 探明 → Tier 3 固化

Tier 4 探明一个（尤其异步/时序相关的）行为对不对；探明且修复后，把能固化的部分沉淀成 Tier 3 确定性测试进每次 commit 守住。这是两层的协作方式。

### 成本与频率

唯一有 token 开销的一层。每开一个 sub-agent 跑一个 prompt 都烧 LLM token；基础设施（emulator、CLI、adb、dump tree）本身免费。所以按需或定期跑，不进每次 commit。

### 只跑安全流程

同 integration-UI，这一层连真实 app、可能触发真实 tool。只跑 read 类安全场景，不让 agent 让 app 触发真实 write（会污染工作目录）。prompt 里明确写「不要创建/编辑/写文件」。

## 贯穿全局的前提：读写卡片在 semantics tree 里可区分（已落地）

这是 component、integration-UI、LLM-driven 三层共同依赖的前提。曾经不满足，现已落地。

曾经的问题（`ui/chat/ChatMessageContent.kt`）：read 和 write 文件卡片用同一个 testTag `toolcard.file.<basename>`，唯一区别是图标颜色——read 用 `onSurfaceVariant`（灰），write 用 `primary`（蓝）。但 Compose 默认不把颜色暴露进 semantics tree，图标 `contentDescription` 是 `null`，任何 testTag 断言或 tree 读取都分不出读写。

现在的做法（两者都加，因为服务不同层）：

1. **testTag 编码读写**：`toolcard.read.<basename>` / `toolcard.write.<basename>`（目录读仍是 `toolcard.folder.<basename>`）。服务 component 层和 integration-UI 层的 `onNodeWithTag` 断言。
2. **图标 contentDescription 标读写**："Read file X" / "Write file X" / "Read directory X"。服务 LLM-driven 层（uiautomator dump 不含 testTag，只能读 content-desc），顺带补全无障碍。

读写判定沿用 `ToolCardClassifier`：目录读为 folder；文件按 tool 前缀，`readToolPrefixes` 为读，其余（write/edit/patch）为写。

## 边界与安全

### 工作目录共享，write 操作会污染真实文件

`start_opencode.sh` 起的 4096 和 4097 共享同一个工作目录 `/Users/grapeot/co/knowledge_working`（即本 workspace）。隔离只在数据库层面（`opencode.db` vs `opencode_batch.db`），文件系统层面完全共享。

后果：让 agent 真的去触发一个 write tool（连任一端口），写的是这个真实 workspace 的文件。所以连真实 server 的两层（integration-UI、LLM-driven）只跑 read 类场景。write 卡片的 UI 验证放在 component 层用假数据做。

若以后要在连真实 server 的层跑真 write，先给 server 配一个隔离的工作目录（cwd 指向 `/tmp` 下的零价目录或专用 sandbox），再放开。

### LLM-driven agent 的权限

LLM-driven 层的 agent 能驱动真实 app、可能触发真实工具。按博客《从过程确定性到结果确定性》的建议收紧：限制可调用的工具，必要时配合轻量 sandbox，让 agent 即使出错也只影响隔离环境。这块是开放问题，随这一层落地再细化。

## 后端 / 数据存储测试（纲要，待补）

本仓库除 UI 外还有后端 / 数据存储相关测试（如 `OpenCodeRepositoryTest`、`SettingsManager` 的加密存储等）。这部分的体系化整理尚未做，先占位：

- 数据存储：`SettingsManager` 的 EncryptedSharedPreferences 读写、`currentSessionId` 等持久化字段。
- Repository / API：`OpenCodeRepository` 的请求构造、错误处理；`OpenCodeApi` 端点的契约（可用 MockWebServer）。
- 现有 `OpenCodeIntegrationTest` 做的是 HTTP 层健康检查（health、getSessions、getAgents），不碰 UI，归在这一类。

## Host Profiles + SSH Tunnel 测试策略（Phase 8）

Host Profiles 和 SSH Tunnel 的测试目标不是证明某个真实 VPS 永远可达，而是证明 Android 与 iOS 的连接 contract 稳定：profile JSON 兼容、secret 不泄露、transport resolve 正确、tunnel 生命周期可恢复、UI 能让用户完成配置和诊断。真实 SSH 环境只作为 smoke test，不能成为默认回归的单点依赖。

### Unit：profile contract 和连接状态机

位置仍在 `app/src/test/`。这些测试每次 commit 都要跑。

需要新增：

1. `HostProfileImportExportTest`：覆盖 Direct 和 SSH Tunnel 的 iOS 兼容 JSON。断言 `transport` 使用 `direct` / `sshTunnel`，Direct export 输出 `serverURL`，SSH export 输出 `ssh.host/port/username/remotePort`，并且不输出 private key、Basic Auth password、known host fingerprint、local port、lastUsedAt。
2. `HostProfileMigrationTest`：从旧 `server_url / username / password` 迁移出默认 Direct profile，保留当前 server URL 和 Basic Auth，设置 `current_host_profile_id`。重复迁移必须幂等。
3. `HostProfileStoreTest`：覆盖新增、编辑、复制、删除、切换 current profile、禁止删除最后一个 profile、lastUsedAt 更新。
4. `KnownHostStoreTest`：覆盖 `host:port` 归一化、首次保存、fingerprint match、fingerprint mismatch、reset trusted host。同 gateway 多 profile 应共享同一 fingerprint。
5. `SSHKeyManagerTest`：覆盖 device-level key 的生成/读取/轮换、OpenSSH public key 字符串格式、JSch 可读取 private key、export 不包含 private key。public key 必须是 `ssh-ed25519 ... opencode-android`，与 private-host 的 `authorized_keys` 约束一致。无法在 JVM 上稳定跑 Android Keystore 时，用接口隔离 crypto provider，并对格式化逻辑做 JVM 单测。
6. `ConnectionResolverTest`：Direct profile 返回原始 URL；SSH profile 调用 fake `TunnelManager.ensureStarted()` 并返回 local URL；SSH config 变化时触发 tunnel restart；Basic Auth 透传到 repository。
7. `ConnectionDebounceTest`：验证 SSH config 修改后不会被旧的 30 秒 health debounce 拦住。Debounce 只作用于同一 resolved connection 的重复 health check，不作用于新 tunnel readiness。

`TunnelManager` 本体需要用接口隔离 JSch：生产代码依赖 `SshPortForwarder`，测试用 fake forwarder 模拟 success、auth failure、host key mismatch、local port occupied、remote health failure。JVM 单测不连接真实 SSH。

### Component：Host Profiles UI 和错误状态

位置 `app/src/androidTest/`，使用 `createComposeRule()` + 假数据，不启动真实 app、不连 server。

需要新增 component tests：

1. `HostProfilesScreenInstrumentedTest`：空列表不应出现；至少有一个 default profile；current profile 有明确 selected 状态；Direct 和 SSH rows 显示不同 summary。点击 row 打开 detail，不直接切换 current profile。
2. `HostProfileEditorInstrumentedTest`：Direct mode 展示 Server URL 和 Basic Auth；SSH mode 展示 gateway host、SSH port、username、remotePort，并隐藏用户不可编辑的 local tunnel URL。SSH editor 可以提供 `Copy Device Public Key` 快捷动作，但不展示或编辑 key 内容。
3. `HostProfileValidationInstrumentedTest`：空 host、非正 port、空 username、非正 remotePort、空 Direct URL 都给 inline error，Save disabled 或保存失败提示明确。
4. `HostProfileImportExportInstrumentedTest`：import JSON dialog 能粘贴 iOS export；invalid JSON 显示错误；export sheet 显示 JSON，并提供 Copy action。
5. `SSHKeyActionsInstrumentedTest`：Host Profiles 列表页展示全局 Device Key section；Copy device public key 入口存在；copy 成功有 snackbar 或短暂 copied 状态。Rotate key 必须先显示确认弹窗，确认文案说明需要更新服务器授权，用户确认后才生成新 key 并复制新 public key。
6. `HostProfileDetailInstrumentedTest`：SSH profile detail 展示 gateway 字段、`Use This Host`、`Test Connection`、`Edit`、`Copy Config JSON`、`Copy Device Public Key`；Direct profile detail 不显示 public key action。
6. `KnownHostMismatchInstrumentedTest`：host key mismatch state 显示 expected/got fingerprint，默认阻断连接，提供 `Reset trusted host`，不提供继续忽略校验的按钮。

所有可由 LLM-driven 层识别的关键控件必须有 content description；所有 component 层要断言的结构必须有 stable testTag。命名建议：`host.profile.row.<id>`、`host.profile.current`、`host.editor.transport.direct`、`host.editor.transport.ssh`、`ssh.publicKey.copy`、`ssh.knownHost.reset`。

### Integration-UI：不默认依赖真实 SSH

默认 integration-UI 不连接真实 SSH gateway。原因是真实 SSH 环境需要私钥授权、host key、网络和 gateway 进程，失败原因太多，会让回归结果不可解释。

默认 integration 覆盖两条路径：

1. Direct profile + MockWebServer 或本地 OpenCode server：验证切换 profile 后 repository 使用对应 base URL，health 成功后加载 sessions/SSE。
2. SSH profile + fake TunnelManager：fake tunnel 返回 `http://127.0.0.1:<port>`，后面接 MockWebServer health。这样能验证 ViewModel、repository 和 UI 的连接编排，不依赖 SSH。

真实 SSH smoke test 只在显式配置环境变量时运行，未配置则 skip：

```text
OPENCODE_ANDROID_SSH_HOST
OPENCODE_ANDROID_SSH_PORT
OPENCODE_ANDROID_SSH_USERNAME
OPENCODE_ANDROID_SSH_PRIVATE_KEY
OPENCODE_ANDROID_SSH_REMOTE_PORT
```

真实 smoke 只验证 `/global/health` 和 SSE 建连，不发送会写文件的 prompt。它用于发布前人工验证，不进入每次 commit 的默认门槛。

### LLM-driven-UI：验证用户能不能自己走通

新增 prompt 放在 `docs/ui_test_prompts/`，目标是用户任务而不是固定点击路径：

1. `host_profiles_create_direct.md`：从 Settings 进入 Host Profiles，创建 Direct profile，设为 current，返回 Chat 后看到连接状态。
2. `host_profiles_import_ssh.md`：导入一段 iOS SSH profile JSON，确认列表中出现 SSH profile，详情页显示 gateway summary 和 public key copy action。
3. `host_profiles_ssh_error_recovery.md`：面对 host key mismatch 或 auth failure，用户能读懂问题、找到 reset trusted host 或 copy public key 的恢复动作。

这层不触发真实 write prompt。它主要检查信息架构和文案是否让第一次配置 SSH 的用户知道下一步该做什么。
