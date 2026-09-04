# RFC-001: OpenCode Android Client 技术方案

> Request for Comments · Accepted · Mar 2026

## 元数据

| 字段 | 值 |
|------|------|
| **RFC 编号** | RFC-001 |
| **标题** | OpenCode Android Client 技术方案 |
| **状态** | Accepted + Phase 8 SSH Host Profiles Draft |
| **创建日期** | 2026-02 |
| **最后更新** | 2026-06-21 |
| **PRD 引用** | [PRD.md](PRD.md) |

---

## 摘要

本 RFC 提出 OpenCode Android Client 的技术实现方案。核心是：在 Android 8.0+ 上构建一个基于 Jetpack Compose 的原生客户端，通过 HTTP REST + SSE 与 OpenCode Server 通信，并通过 AI Builder WebSocket API 提供语音转写能力，实现远程监控、消息发送、文档审查等能力。

---

## 1. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    Android Client (Jetpack Compose)              │
├─────────────────────────────────────────────────────────────────┤
│  UI Layer              │  ViewModel Layer      │  Data Layer                 │
│  ─────────             │  ────────────         │  ──────────                 │
│  ChatScreen            │  MainViewModel        │  OpenCodeApi               │
│  FilesScreen           │                       │  SSEClient                 │
│  SettingsScreen        │                       │  OpenCodeRepository        │
│  HostProfilesScreen    │                       │  TunnelManager             │
│                        │                       │  SSHKeyManager             │
│  Components            │                       │  AIBuildersAudioClient     │
│                        │                       │  AudioRecorderManager      │
│                        │                       │  SettingsManager           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ OkHttp (REST + SSE + WebSocket)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│          OpenCode Server + AI Builder Speech Services            │
│  GET /global/event  │  POST /session/:id/prompt_async  │  ...     │
│  POST /v1/audio/realtime/sessions  │  WS /v1/audio/realtime/ws    │
└─────────────────────────────────────────────────────────────────┘
```

SSH Tunnel 模式下，OpenCode REST/SSE 仍然使用同一套 OkHttp/Retrofit/SSEClient。差异只发生在 repository 配置前：`TunnelManager` 先建立 `127.0.0.1:<localPort>` 到 SSH gateway 侧 `127.0.0.1:<remotePort>` 的 local forward，然后 `OpenCodeRepository.configure()` 使用本地 loopback URL。这样 Files、Chat、SSE、health check 不需要各自感知 SSH。

**分层说明**：
- **UI Layer**：Jetpack Compose 声明式 UI
- **ViewModel Layer**：持有 UI 状态，处理业务逻辑
- **Data Layer**：网络请求、数据持久化

---

## 2. 技术选型

| 层面 | 选择 | 理由 |
|------|------|------|
| 语言 | Kotlin | Android 官方推荐，协程支持好 |
| UI | Jetpack Compose | 声明式，与 SwiftUI 概念相似，未来方向 |
| 状态 | ViewModel + StateFlow | 官方推荐，生命周期感知 |
| 网络 | OkHttp + Retrofit | 业界标准，SSE 支持好 |
| 序列化 | Kotlinx Serialization | Kotlin 原生，性能好 |
| 依赖注入 | Hilt | 官方推荐，Dagger 封装 |
| Markdown | multiplatform-markdown-renderer-m3 | 已落地，Compose 兼容性好 |
| Markdown Web Preview | Android WebView + bundled markdown-it + DOMPurify | Phase 7 对齐 iOS PR #94，承载 HTML-in-Markdown / CSS cards / inline SVG |
| SSH Tunnel | mwiede/JSch | Java 实现、依赖面小，适合 app 内 local port forwarding；Apache Mina SSHD 作为替代方案 |
| 安全存储 | EncryptedSharedPreferences + Keystore | Android 官方方案 |

### 2.1 HTTP 连接配置

Android 9+ 默认禁止明文流量。`network_security_config.xml` 设置 base-config cleartextTrafficPermitted="false"，仅对 localhost、127.0.0.1、10.0.2.2、ts.net（Tailscale MagicDNS）开放 HTTP，其余强制 HTTPS。Android 不支持 IP 段匹配，局域网 IP 需使用 HTTPS 或 Tailscale。

### 2.2 SSH 库选型

| 库 | 语言 | 维护状态 | 推荐度 |
|----|------|----------|--------|
| **mwiede/JSch** | Java | 活跃 fork | ★★★★★ |
| Apache Mina SSHD | Java | 活跃 | ★★★★ |
| sshj | Java | 活跃 | ★★★★ |

**推荐 mwiede/JSch**：Android 端只需要 SSH client + local port forwarding，不需要完整 SSH server/subsystem。mwiede/JSch 是 JSch 的维护 fork，API 面小，接入成本低，适合 Phase 8 先完成 iOS feature parity。Apache Mina SSHD 功能完整，但体积、配置、线程池和 forwarding filter 复杂度更高，作为 JSch 在 key format 或 Android crypto 上遇到阻塞时的替代方案。sshj 保留为备选，不作为第一实现。

---

## 3. 网络层设计

### 3.1 REST API

```kotlin
interface OpenCodeApi {
    @GET("global/health")
    suspend fun getHealth(): HealthResponse

    @GET("session")
    suspend fun getSessions(@Query("limit") limit: Int? = null): List<Session>

    @POST("session")
    suspend fun createSession(@Body body: CreateSessionRequest = CreateSessionRequest()): Session

    @GET("session/{id}")
    suspend fun getSession(@Path("id") sessionId: String): Session

    @PATCH("session/{id}")
    suspend fun updateSession(@Path("id") sessionId: String, @Body body: UpdateSessionRequest): Session

    @DELETE("session/{id}")
    suspend fun deleteSession(@Path("id") sessionId: String): Response<Unit>

    @GET("session/status")
    suspend fun getSessionStatus(): Map<String, SessionStatus>

    @GET("session/{id}/message")
    suspend fun getMessages(
        @Path("id") sessionId: String,
        @Query("limit") limit: Int? = null
    ): List<MessageWithParts>

    @POST("session/{id}/prompt_async")
    suspend fun promptAsync(
        @Path("id") sessionId: String,
        @Body body: PromptRequest
    ): Response<Unit>

    @POST("session/{id}/abort")
    suspend fun abortSession(@Path("id") sessionId: String): Response<Unit>

    @POST("session/{id}/fork")
    suspend fun forkSession(
        @Path("id") sessionId: String,
        @Body body: ForkSessionRequest
    ): Session

    @POST("session/{id}/permissions/{permissionId}")
    suspend fun respondPermission(
        @Path("id") sessionId: String,
        @Path("permissionId") permissionId: String,
        @Body body: PermissionResponseRequest
    ): Response<Unit>

    @GET("permission")
    suspend fun getPendingPermissions(): List<PermissionRequest>

    @GET("question")
    suspend fun getPendingQuestions(): List<QuestionRequest>

    @POST("question/{requestId}/reply")
    suspend fun replyQuestion(
        @Path("requestId") requestId: String,
        @Body body: QuestionReplyRequest
    ): Response<Unit>

    @POST("question/{requestId}/reject")
    suspend fun rejectQuestion(@Path("requestId") requestId: String): Response<Unit>

    @GET("config/providers")
    suspend fun getProviders(): ProvidersResponse

    @GET("agent")
    suspend fun getAgents(): List<AgentInfo>

    @GET("session/{id}/diff")
    suspend fun getSessionDiff(@Path("id") sessionId: String): List<FileDiff>

    @GET("session/{id}/todo")
    suspend fun getSessionTodos(@Path("id") sessionId: String): List<TodoItem>

    @GET("file")
    suspend fun getFileTree(@Query("path") path: String? = ""): List<FileNode>

    @GET("file/content")
    suspend fun getFileContent(@Query("path") path: String): FileContent

    @GET("file/status")
    suspend fun getFileStatus(): List<FileStatusEntry>

    @GET("find/file")
    suspend fun findFile(
        @Query("query") query: String,
        @Query("limit") limit: Int = 50
    ): List<String>
}
```

### 3.2 SSE 连接

使用 OkHttp 的 `EventSource`，构造时只注入 `OkHttpClient`，连接时传入服务器参数，返回 `Flow<Result<SSEEvent>>`，内置指数退避重连：

```kotlin
// 构造器：只接受 okHttpClient，不持有 baseUrl
class SSEClient(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val RETRY_MULTIPLIER = 2.0
    }

    // connect() 接受 baseUrl/username/password，返回 Flow<Result<SSEEvent>>
    // 失败时自动指数退避重连（1s → 2s → 4s … 上限 30s）
    fun connect(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): Flow<Result<SSEEvent>> = connectOnce(baseUrl, username, password)
        .retryWhen { _, attempt ->
            val delayMs = (INITIAL_RETRY_DELAY_MS * Math.pow(RETRY_MULTIPLIER, attempt.toDouble()))
                .toLong().coerceAtMost(MAX_RETRY_DELAY_MS)
            delay(delayMs)
            true
        }

    private fun connectOnce(
        baseUrl: String,
        username: String? = null,
        password: String? = null
    ): Flow<Result<SSEEvent>> = callbackFlow {
        // 构造带 Basic Auth 的请求，连接 /global/event 端点
        // onEvent → trySend(Result.success(event))
        // onClosed → close()
        // onFailure → close(t)
        // awaitClose { eventSource.cancel() }
    }
}
```

### 3.3 错误处理与重连

- 网络错误：Toast 提示，不 crash
- SSE 断开：指数退避重连，上限 30s
- 服务器不可达：显示 Disconnected 状态

### 3.4 语音转写链路

- 录音端使用 `AudioRecorderManager.startRealtimeCapture()`：`AudioRecord` 直接采集 PCM16 mono 24kHz chunk，点击麦克风后立即开始写入本地 cache，不等待网络 session 创建完成
- 本地缓存使用 `RealtimeSpeechAudioCache`：每次录音创建一个临时 `.pcm` 文件，`append()` 追加 chunk，`readChunk(offset, maxBytes)` 支持从任意 offset 读取，stop/cancel 后删除
- 转写端使用 `RealtimeSpeechStreamer`：每个 chunk 先 append cache，再在 session 可用时发送；首次 session ready 后从 cache offset 0 replay，保证建连前录到的音频也进入 WebSocket
- session 创建由 `AIBuildersAudioClient.startRealtimeSession()` 完成：先 `POST /v1/audio/realtime/sessions`，再连接返回的 `ws_url`，等待 `session_ready` 后返回可发送 binary PCM 的 session wrapper。日志只能记录 redacted WebSocket URL，不能输出 ticket query string
- 恢复策略与 iOS 对齐：发送 chunk、heartbeat 或 commit 失败时，streamer 取消旧 session，创建新 session，从 cache offset 0 replay 全部 PCM，然后继续 live send。每次断线都从头 replay，优先保证语音不丢失
- stop 流程：停止 `AudioRecord` capture，停止 heartbeat，等待正在进行的 recovery 完成，发送 `{ "type": "commit" }`，接收 `transcript_delta` / `transcript_completed`，发送 `{ "type": "stop" }`，等待 `session_stopped` 后清理 cache
- abort/retry 流程：录音或转写中，左侧辅助 stop 调用 VoiceFlowKit `abortPreservingAudio()`，立即释放 `isRecording/isTranscribing` UI 门控并保留 Kit 内部 PCM cache；按钮随后变为 retry，调用 `VoiceFlowClient.transcribe(preservedAudio)` 重新识别上一段 PCM，成功后清理 preserved audio
- 输入框合并策略使用 `mergedSpeechInput(prefix, transcript)`：保留原输入，在转写结果前后只做必要空格拼接
- 连接测试使用 AI Builder API，成功状态按 `baseURL + token` 的签名缓存，避免每次进入页面都强制重测

关键常量：

| 常量 | 值 | 说明 |
|------|----|------|
| `AudioRecorderConfig.targetPcmSampleRate` | `24_000` | AI Builder realtime 输入采样率 |
| `AudioRecorderConfig.targetPcmChannelCount` | `1` | mono |
| `AudioRecorderConfig.targetPcmBytesPerSample` | `2` | PCM16 |
| `AudioTranscriptionConfig.sendChunkSizeBytes` | `240_000` | live send chunk 上限 |
| `AudioTranscriptionConfig.realtimeReplayChunkSizeBytes` | `240_000` | recovery replay chunk 上限 |
| `AudioTranscriptionConfig.realtimeHeartbeatIntervalSeconds` | `12` | heartbeat 间隔 |

### 3.5 Host Profiles 与 SSH Tunnel

Phase 8 把连接层从单一全局 `serverUrl` 升级为 Host Profiles。Profile 是用户可理解的 OpenCode 环境；transport 是访问路径。Direct transport 直接访问 OpenCode HTTP(S) URL；SSH Tunnel transport 通过 app 内 SSH local forwarding 访问 gateway 后面的 OpenCode instance。Repository、REST API、SSE 和 Files 不直接感知 SSH，只消费最终 resolved base URL。

核心数据模型：

```kotlin
@Serializable
enum class HostTransport {
    @SerialName("direct")
    DIRECT,
    @SerialName("sshTunnel")
    SSH_TUNNEL
}

@Serializable
data class BasicAuthConfig(
    val username: String,
    val passwordId: String
)

@Serializable
data class SshTunnelConfig(
    val host: String,
    val port: Int = 8006,
    val username: String = "opencode",
    val remotePort: Int = 19001
)

@Serializable
data class HostProfile(
    val id: String,
    val name: String,
    val transport: HostTransport,
    @SerialName("serverURL")
    val serverUrl: String,
    val basicAuth: BasicAuthConfig? = null,
    val ssh: SshTunnelConfig? = null,
    val lastUsedAt: Long? = null
)
```

存储策略：

1. `SettingsManager` 保存 `host_profiles_json` 和 `current_host_profile_id`。旧的 `server_url / username / password` 作为 migration source，首次加载时自动生成一个 Direct profile。
2. Basic Auth password 继续存在 EncryptedSharedPreferences，但 profile 只保存 `passwordId`。Export 不输出 password。
3. SSH private key 是设备级 secret，存 app-private encrypted storage 或 EncryptedSharedPreferences；多个 SSH profiles 复用同一把 key。第一版由 BouncyCastle 生成 Ed25519 key，写出 OpenSSH private key，再由 JSch 读取执行 SSH auth；导出的 public key 为 `ssh-ed25519 ... opencode-android`，与 iOS 和 private-host gateway 的 `authorized_keys` 约束保持一致。Non-exportable Android Keystore key 留作后续增强。Rotate key 是显式恢复动作，UI 必须先确认并提示用户更新服务器授权；不要对开发期旧 RSA key 做自动迁移。
4. Known hosts 以 SSH gateway `host:port` 为 key 存 fingerprint。多个 profile 指向同一 gateway 时共享 trust state。

跨端 import/export JSON 与 iOS 对齐。Transport JSON 使用 iOS raw value：`direct` / `sshTunnel`。Direct export 包含 `version/name/transport/serverURL`；SSH export 包含 `version/name/transport/ssh{host,port,username,remotePort}`。Export 不包含 private key、Basic Auth password、known host fingerprint、local port、last used time。Import SSH profile 时强制 `transport = SSH_TUNNEL`，并由 app 管理 resolved local URL。

```json
{
  "version": 1,
  "name": "VPS OpenCode",
  "transport": "sshTunnel",
  "ssh": {
    "host": "gateway.example.com",
    "port": 8006,
    "username": "opencode",
    "remotePort": 19001
  }
}
```

连接解析流程：

```kotlin
suspend fun resolveProfile(profile: HostProfile): ResolvedConnection {
    return when (profile.transport) {
        HostTransport.DIRECT -> ResolvedConnection(profile.serverUrl, profile.basicAuth)
        HostTransport.SSH_TUNNEL -> {
            val localUrl = tunnelManager.ensureStarted(profile.ssh!!)
            ResolvedConnection(localUrl, profile.basicAuth)
        }
    }
}
```

`TunnelManager.ensureStarted()` 必须幂等：同一 SSH config 已连接时直接返回当前 local URL；config 变化时先关闭旧 tunnel 再启动新 tunnel。Local port 优先用稳定端口 `4096`；如果端口被占用，回退到随机可用端口，并把实际 local URL 只保存在 runtime state，不写入 export JSON。

SSH Tunnel 状态机：

| Phase | 说明 | 失败提示 |
|------|------|----------|
| `sshGateway` | TCP 连接 SSH gateway | gateway 不可达、端口错误、网络断开 |
| `sshHostKey` | TOFU / fingerprint 校验 | host key changed，需要 reset trusted host |
| `sshAuth` | private key auth | public key 未授权、私钥损坏、用户名错误 |
| `localTunnel` | 绑定 loopback local port 并建立 forwarding | 本地端口占用、forwarding 被服务端拒绝 |
| `health` | 通过 local URL 请求 `/global/health` | OpenCode server 未启动、remotePort 错误 |
| `connected` | REST/SSE 可用 | - |

生命周期边界：

1. App 前台使用时自动建立 tunnel；切换 profile 时关闭旧 tunnel 并重建。
2. App 进入后台时可以断开 SSE 与 tunnel；回前台先 `ensureStarted()`，再 REST 全量同步和重建 SSE。
3. Phase 8 不实现后台永久 tunnel，不添加 Foreground Service，也不把 SSH tunnel 作为 notification transport。
4. `testConnection()` 不能只复用 30 秒 health debounce。用户保存或修改 SSH config 后，必须重新跑 tunnel phase；health check 的防抖只作用于未变化的 resolved connection。

---

## 4. 状态管理

### 4.1 AppState

```kotlin
data class AppState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val sessions: List<Session> = emptyList(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val currentSessionId: String? = null,
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val messages: List<MessageWithParts> = emptyList(),
    val messageLimit: Int = 30,
    val isLoadingMessages: Boolean = false,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgentName: String = "build",
    val selectedModelIndex: Int = 0,
    val providers: ProvidersResponse? = null,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val inputText: String = "",
    val error: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val filePathToShowInFiles: String? = null,
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val speechError: String? = null,
    val aiBuilderConnectionOK: Boolean = false,
    val aiBuilderConnectionError: String? = null,
    val isTestingAIBuilderConnection: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val audioRecorderManager: AudioRecorderManager
) : ViewModel() {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    // SSE 事件处理逻辑拆分到 MainViewModelSyncActions.kt 中的顶层函数
    // MainViewModel 通过私有包装调用：
    private fun handleSSEEvent(event: SSEEvent) {
        handleIncomingSseEvent(   // 定义于 MainViewModelSyncActions.kt
            state = _state,
            event = event,
            onRefreshMessages = ::loadMessagesWithRetry,
            onLoadPendingPermissions = ::loadPendingPermissions,
            onNonFatalIssue = { message -> reportNonFatalIssue(TAG, message) }
        )
    }
}
```

### 4.2 数据模型

```kotlin
@Serializable
data class Session(
    val id: String,
    val slug: String? = null,
    @SerialName("projectID") val projectId: String? = null,
    val directory: String,
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null,
    val version: String? = null,
    val time: TimeInfo? = null,
    val share: ShareInfo? = null,
    val summary: SummaryInfo? = null
)

// API 返回 MessageWithParts（info + parts 分离），不是裸 Message
@Serializable
data class MessageWithParts(
    val info: Message,
    val parts: List<Part> = emptyList()
)

@Serializable
data class Message(
    val id: String,
    @SerialName("sessionID") val sessionId: String? = null,
    val role: String,
    @SerialName("parentID") val parentId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    @SerialName("modelID") val modelId: String? = null,
    val model: ModelInfo? = null,
    val agent: String? = null,
    val error: MessageError? = null,
    val time: TimeInfo? = null,
    val finish: String? = null,
    val tokens: TokenInfo? = null,
    val cost: Double? = null
)

@Serializable
data class Part(
    val id: String,
    @SerialName("messageID") val messageId: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val type: String,          // "text" | "reasoning" | "tool" | "patch" | "step-start" | "step-finish"
    val text: String? = null,
    val tool: String? = null,  // tool name (was toolName in RFC draft)
    @SerialName("callID") val callId: String? = null,
    val state: PartState? = null,
    val metadata: PartMetadata? = null,
    val files: List<FileChange>? = null
)

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean? = null
)
```

### 4.3 草稿持久化（Phase 5，对齐 iOS）

**背景**：当前 `AppState.inputText` 是全局 String，切换 session 时不保存/不恢复，发送后清空。iOS 端用 `draftInputsBySessionID: [String: String]` 字典按 session 存储草稿，持久化到 UserDefaults。

**数据存储**：

```kotlin
// SettingsManager 新增
private val draftKey = "draft_inputs_by_session"

fun getDraftText(sessionId: String): String {
    val json = prefs.getString(draftKey, "{}") ?: "{}"
    val map = Json.decodeFromString<Map<String, String>>(json)
    return map[sessionId] ?: ""
}

fun setDraftText(sessionId: String, text: String) {
    val json = prefs.getString(draftKey, "{}") ?: "{}"
    val map = Json.decodeFromString<Map<String, String>>(json).toMutableMap()
    if (text.isBlank()) map.remove(sessionId) else map[sessionId] = text
    prefs.edit { putString(draftKey, Json.encodeToString(map)) }
}
```

**状态流转**：
1. `selectSession(newId)` 时：先调 `setDraftText(oldId, currentInputText)` 保存旧草稿，再调 `getDraftText(newId)` 加载新草稿到 `inputText`
2. `setInputText(text)` 时：同步调 `setDraftText(currentSessionId, text)` 持久化（每次击键都写，利用 EncryptedSharedPreferences 的内存缓存，实际 I/O 开销可控）
3. `sendMessage()` 成功后：调 `setDraftText(sessionId, "")` 清空

**性能考虑**：EncryptedSharedPreferences 在内存中维护缓存，`getString`/`putString` 的 hot path 不涉及磁盘 I/O。JSON 编解码 Map 规模通常 < 50 条，开销可忽略。如果未来 session 数过多，可按 LRU 淘汰旧草稿。

### 4.4 Model/Agent 按 Session 记忆（Phase 5，对齐 iOS）

**背景**：当前 `selectedModelIndex` 和 `selectedAgentName` 全局存储在 SettingsManager。切换 session 时通过 last assistant message 推断，但用户手动切了模型还没发消息就切走的场景会丢失选择。iOS 用 `selectedModelIDBySessionID` 字典做显式 per-session 持久化；Android 实现用 Int 索引（对应 `ModelPresets.list` 下标）而非 modelID 字符串。

**数据存储**：

```kotlin
// SettingsManager 新增
// 注意：模型存储的是 Int 索引（对应 ModelPresets.list 下标），不是 "{providerID}/{modelID}" 字符串
// 底层用 Map<String, String> 持久化，取出时再 toIntOrNull()

fun getModelForSession(sessionId: String): Int? {
    val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null) ?: return null
    return try {
        Json.decodeFromString<Map<String, String>>(json)[sessionId]?.toIntOrNull()
    } catch (e: Exception) {
        null
    }
}

fun setModelForSession(sessionId: String, modelIndex: Int) {
    val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null)
    val map: MutableMap<String, String> = try {
        json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
    } catch (e: Exception) {
        mutableMapOf()
    }
    map[sessionId] = modelIndex.toString()
    encryptedPrefs.edit().putString(KEY_SESSION_MODELS, Json.encodeToString(map)).apply()
}

// Agent 同理（存字符串 agentName）
fun getAgentForSession(sessionId: String): String? { /* 同上模式 */ }
fun setAgentForSession(sessionId: String, agentName: String) { /* 同上模式 */ }
```

**恢复优先级**（切换到 session X 时）：
1. 查 `getModelForSession(X)` → 有值则直接恢复
2. 无值 → 从 X 的最后一条 assistant message 推断（当前已有此逻辑）
3. 推断不到 → 保持当前全局 selectedModelIndex 不变

**写入时机**：
- `selectModel(index)` 时：若 `currentSessionId != null`，同时调 `setModelForSession(sessionId, index)`（存 Int 索引）
- `selectAgent(name)` 时：同理，调 `setAgentForSession(sessionId, agentName)`

**全局默认值保留**：SettingsManager 中原有的全局 `selectedModelIndex` 和 `selectedAgentName` 继续保留，作为新 session 或无 per-session 记录时的 fallback。

### 4.5 Model Shortlist（模型短名单，对齐 iOS）

**背景**：此前模型列表硬编码在 `ui/ModelPresets.kt`（9 个模型），`availableModels` 直接返回该列表，增删模型需改代码重编译；服务器 `config/providers` 全量列表只用于 context/AI usage，不喂给模型下拉框。本节把 iOS 的"模型短名单"机制移植到 Android：聊天下拉框只显示用户维护的短名单，设置里可增删/排序/改短名，候选目录从服务器 `/provider` 注册表动态生成。同时把 §4.4 的**模型**选择持久化从 index 升级为 model ID（Agent 记忆不变）。

**两个列表的分工（核心概念）**：

| 列表 | 来源 | 用途 |
|---|---|---|
| `modelShortlist`（持久化） | 用户维护 + 自动添加 | 聊天模型下拉框**只读这个**（`availableModels = modelShortlist.map { it.toModelOption() }`） |
| `catalogModels`（运行时） | `GET /provider` → 只取 `connected` 的 provider、只取 chat-capable 的 model，按 provider+name 排序 | 设置里"添加模型"的候选目录 |

**数据模型**（`data/model/`）：

```kotlin
// 持久化单元（对应 iOS ModelShortlistItem）
@Serializable
data class ModelShortlistItem(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val shortName: String
) {
    val id: String get() = "$providerId/$modelId"
    fun toModelOption() = AppState.ModelOption(shortName, providerId, modelId, customShortName = shortName)
}

// /provider 响应（对应 iOS ProviderRegistryResponse）
@Serializable
data class ProviderRegistryResponse(
    val all: List<ConfigProvider> = emptyList(),
    @SerialName("default") val defaultByProvider: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList()
)

// ProviderModel 新增可选 capabilities（chat-capable 过滤）
@Serializable
data class ProviderModelCapabilities(val output: ProviderModelOutput? = null)
@Serializable
data class ProviderModelOutput(val text: Boolean? = null)
// ProviderModel 加字段：val capabilities: ProviderModelCapabilities? = null
```

**持久化**（`SettingsManager`，全走 `EncryptedSharedPreferences`）：

| Key | 类型 | 说明 |
|---|---|---|
| `model_shortlist.v1` | JSON `List<ModelShortlistItem>` | 短名单本体 |
| `selected_model_id` | String? | 全局当前选择（model ID，替代 `model_index` 语义） |
| `session_model_ids` | JSON `Map<sessionId, modelId>` | 替代 `session_models`（index 版） |
| `model_shortlist_schema_version` | Int | bump 到 `2` |

**一次性迁移**（`migrateModelSelectionToIds`，`applySavedSettings` 调用，schema version 保护、幂等）：
1. 读旧 `model_index` → 用**当前硬编码** `ModelPresets.list[index]` 解析出 model ID → 写 `selected_model_id`。
2. 读旧 `session_models`（index 版）→ 逐条同样解析 → 写 `session_model_ids`。
3. **短名单播种**：`model_shortlist.v1` 不存在时，用当前 9 个 `ModelPresets` 初始化（决策 D1）。
4. bump `model_shortlist_schema_version = 2`。

> 与 iOS 差异：iOS 首启短名单为空（从零建）；Android 是存量迁移，播种 9 个默认项保证现有用户无感（已存 index 选择正好解析到播种进短名单的同一批 ID）。

**API**（`OpenCodeApi` / `OpenCodeRepository`）：

```kotlin
@GET("provider")
suspend fun getProviderRegistry(): ProviderRegistryResponse
```

- Repository `getProviderRegistry(): Result<ProviderRegistryResponse>`，走 REST client。
- **降级**：`/provider` 失败（老服务器无该路由）时，用现有 `getProviders()`（`config/providers`）构建 catalog，且**不做 connected 过滤**（视为全部 connected）。catalog 构建失败则保持旧 catalog 不动（对齐 iOS"注册表不可用则 catalog 不变"）。

**Catalog 构建**（纯函数 `buildCatalog`，`ui/ModelShortlist.kt`）：只取 `connected` 集合内的 provider；跳过 `capabilities.output.text == false` 的 model（null/缺省视为 chat-capable）；`displayName = model.name ?: modelId`；`shortName` 复用 `suggestedShortName` 推断；按 providerId、再按 displayName 排序。同时产出 `providerDisplayNames`（providerId → 人类可读名）。

**AppState / ViewModel 行为**：
- `AppState` 新增 `modelShortlist`、`catalogModels`、`providerDisplayNames`、`selectedModelId`、`pendingModelShortlistFocus`；`ModelOption` 新增 `customShortName`（`shortName` getter 委托 `suggestedShortName`）。
- `availableModels` getter 改为 `modelShortlist.map { it.toModelOption() }`（不再读 `ModelPresets.list`；`ModelPresets` 降级为播种 + 迁移解析的唯一用途，保留文件）。
- `reanchorSelectedModelIndex()`：任何短名单变动（增/删/移/改）后调用——在 `availableModels` 里找当前选中 model ID 的下标，找不到回落 0。
- `selectModel(index)`：持久化 **model ID**（`selected_model_id` + `session_model_ids[currentSession]`），不再存 index。
- `selectSession`：按 ID 恢复；saved ID 不在短名单但在 catalog → 自动加入短名单再选中。
- `launchLoadMessages` 推断：按 `providerId/modelId` 在短名单里 `firstIndex`；找不到且 session 有 assistant 消息 → ad-hoc 加入短名单（displayName 从 `providerModelsIndex` 取）。
- catalog 刷新时机：`loadProviders()`（连接成功 / host 切换后）拉 `getProviderRegistry()` → `buildCatalog` → 更新 `catalogModels`/`providerDisplayNames` → `refreshShortlistDisplayNames`（短名单 displayName 跟随 catalog，shortName 不动）→ `reanchorSelectedModelIndex`。
- 短名单操作（ViewModel 方法，全部 = 改 state + 写 prefs + reanchor）：`addModelsToShortlist`（去重 by id）、`removeModelShortlistItem`、`moveModelShortlist`、`updateModelShortlistShortName`（空值回落 `suggestedShortName`）。

**决策（已确认）**：
- **D1**：首启播种当前 9 个 `ModelPresets`（存量迁移，非 iOS 的空短名单）。
- **D2**：按 model ID 持久化选择态（短名单可变后 index 必然错位；一次性幂等迁移 + schema version 保护）。
- **D3**：重排用"上移/下移"，不做拖拽（iOS 拖拽 bug 是独立低优先级 follow-up）。
- **D4**：`/provider` 端点 + `config/providers` 降级（不做 connected 过滤）。
- **D5**：不做 ongoing canonical ID 老化映射；退役模型直接从短名单消失。
- **D6**：displayName 跟随 catalog 刷新；用户自定义 shortName 不动。

**不受影响**：`selectedAIUsageQuota` 的 provider 映射（走 `availableModels.getOrNull(selectedModelIndex)?.providerId`，reanchor 正确则自动正确）；乐观发送 / SSE / 网络层 / NFC / deep link / 语音。

---

## 5. UI 设计

### 5.1 导航结构

```kotlin
@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    
    NavHost(navController, startDestination = "chat") {
        composable("chat") { ChatScreen() }
        composable("files") { FilesScreen() }
        composable("settings") { SettingsScreen() }
    }
}

// 手机：底部 Tab
@Composable
fun PhoneLayout() {
    Scaffold(
        bottomBar = { BottomNavigationBar() }
    ) { padding ->
        NavHost(/* ... */, modifier = Modifier.padding(padding))
    }
}

// 平板：三栏布局
@Composable
fun TabletLayout() {
    Row {
        WorkspacePanel(Modifier.weight(1f))
        PreviewPanel(Modifier.weight(1.5f))
        ChatPanel(Modifier.weight(1.5f))
    }
}
```

当前实现里，`MainViewModel` 仍然是状态入口，但连接初始化、session/message 同步、SSE/polling、语音转写编排已经拆到同包 helper 文件；这样保留统一状态入口，同时把副作用逻辑按职责分散到更小的单元。

### 5.2 消息渲染

```kotlin
@Composable
fun MessageRow(message: Message) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (message.role == "user") 
                    MaterialTheme.colors.surface.copy(alpha = 0.5f)
                else 
                    MaterialTheme.colors.background
            )
            .padding(16.dp)
    ) {
        message.parts.forEach { part ->
            when (part.type) {
                "text" -> MarkdownText(part.text ?: "")
                "reasoning" -> ReasoningCard(part)
                "tool" -> ToolCard(part)
                "patch" -> PatchCard(part)
            }
        }
    }
}
```

### 5.3 流式显示

```kotlin
@Composable
fun StreamingText(text: String, isStreaming: Boolean) {
    var displayedText by remember(text) { mutableStateOf("") }
    
    LaunchedEffect(text) {
        displayedText = text
    }
    
    Text(displayedText)
}
```

### 5.4 Chat Toolbar 重排（Phase 5，对齐 iOS）

**背景**：当前 Android 的 ChatTopBar 使用 Material 3 `TopAppBar`，session 标题以 `titleSmall` 放在左侧，所有操作按钮（Context ring、Model、Agent、Session list、Settings）堆在右侧 `actions` 区域。iOS 端采用自定义 HStack，左右分区清晰。Phase 5 将 Android 布局对齐 iOS。

**目标布局**：

```
┌─────────────────────────────────────────────────┐
│  Session Title (titleMedium, bold)              │  ← 大标题行
├─────────────────────────────────────────────────┤
│  [☰] [✏] [+]          [Model ▾] [Agent ▾] [◔]  │  ← 按钮行
└─────────────────────────────────────────────────┘
```

**实现方案**：将 `TopAppBar` 替换为自定义 `Column`，分两行：

**第一行：Session 标题**
- 使用 `MaterialTheme.typography.titleMedium`（替代原 `titleSmall`）
- `fontWeight = FontWeight.Bold`
- 单行省略（`maxLines = 1, overflow = TextOverflow.Ellipsis`）
- 左对齐，水平 padding 16.dp

**第二行：操作按钮 HStack**
- 左侧 `Row`（spacing 8.dp）：
  1. Session List（`Icons.Default.List`）：点击打开 ModalBottomSheet
  2. Rename（`Icons.Default.Edit`）：点击弹出 `AlertDialog`，TextField 输入新标题，确认后调用 `updateSessionTitle()`
  3. New Session（`Icons.Default.Add`）：点击创建新 session
- `Spacer(Modifier.weight(1f))`
- 右侧 `Row`（spacing 4.dp）：
  1. Model 下拉（保持现有 `DropdownMenu` 实现）
  2. Agent 下拉（保持现有 `DropdownMenu` 实现）
  3. Context Usage ring（保持现有实现）
  4. Settings 齿轮（仅平板，通过 `showSettingsButton` 控制）

**Rename 对话框**：
```kotlin
@Composable
fun RenameSessionDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
```

**影响范围**：
- `ChatTopBar.kt`：主要改动文件，替换 TopAppBar 为 Column + 两行 Row
- `ChatScreen.kt`：新增 `onRenameSession` 回调参数
- `MainViewModel.kt`：`updateSessionTitle()` 已存在，无需改动
- `ChatUiTuning.kt`：可能新增标题字号、按钮间距等常量

### 5.5 消息历史分页修复（Phase 5b）

**Bug 根因**：Chat 列表使用 `reverseLayout = true`，视觉上最新消息在底部（索引 0），最旧消息在顶部（最大索引）。`ChatMessageContent.kt` 中的 `shouldLoadMore` 检测 `lastVisible >= total - 3`，这在反转布局下实际是在最新消息处触发，而非最旧消息处。

**修复方案**：

```kotlin
// 当前（错误）：在列表"底部"（最新消息）触发
val lastVisible = visible.maxOfOrNull { it.index } ?: return@derivedStateOf false
lastVisible >= total - 3

// 修复后：在列表"顶部"（最旧消息）触发
// reverseLayout = true 时，firstVisibleItemIndex 接近 total - 1 表示用户滚到了视觉顶部
val firstVisible = visible.minOfOrNull { it.index } ?: return@derivedStateOf false
firstVisible >= total - 3  // 用户滚到了最旧消息附近
```

**Loading 指示器**：在消息列表顶部（`reverseLayout` 下即 `lastItem`）增加 `CircularProgressIndicator`，当 `isLoading && messages.size >= messageLimit` 时显示。

**影响范围**：
- `ChatMessageContent.kt`：修复 `shouldLoadMore` 方向，新增顶部 loading 指示器
- 其他文件无需改动，`loadMoreMessages()` 后端逻辑（增大 limit 重新拉取）已正确

### 5.6 Model/Agent Capsule 文本化（Phase 5b，对齐 iOS）

**背景**：当前 Model 和 Agent 选择器仅显示 icon（`Icons.Default.Tune` / `Icons.Default.SmartToy`），用户无法一眼看到当前选择。iOS 使用 Capsule 样式按钮显示文本名称。

**实现方案**：将 `IconButton` 替换为自定义 Capsule Composable：

```kotlin
// Model Capsule — accent gradient 背景，白色文字
@Composable
fun ModelCapsule(
    modelName: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = modelName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

// Agent Capsule — 灰色背景，secondary 文字
@Composable
fun AgentCapsule(
    agentName: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = agentName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

**shortName 计算属性**：给 `AppState.ModelOption` 新增 `shortName`，逻辑对齐 iOS `ModelPreset.shortName`：

```kotlin
data class ModelOption(val displayName: String, val providerId: String, val modelId: String) {
    val shortName: String
        get() = when {
            "DeepSeek" in displayName -> "DeepSeek"
            "Haiku" in displayName -> "Haiku"
            "Gemini" in displayName -> "Gemini"
            "GPT" in displayName -> "GPT"
            "Grok" in displayName -> "Grok"
            else -> displayName.split(" ").firstOrNull() ?: displayName
        }
}
```

**ChatTopBar 中的接线**：
- Model Capsule 显示 `availableModels.getOrNull(selectedModelIndex)?.shortName ?: "Model"`
- Agent Capsule 显示 `selectedAgent`（agent name 本身通常已经足够简短）
- DropdownMenu 逻辑保持不变，只是触发按钮从 IconButton 变为 Capsule

**影响范围**：
- `ChatTopBar.kt`：替换 Model/Agent 的 IconButton 为 Capsule
- `MainViewModel.kt`：`ModelOption` data class 新增 `shortName`
- `ModelPresets.kt`：无需改动（`shortName` 是计算属性）

### 5.7 平板 Toolbar 适配（Phase 5b）

**背景**：平板布局下 `showSessionListInTopBar = false`、`showNewSessionInTopBar = false`，左侧只剩 Rename 一个按钮，视觉不平衡。

**方案**：平板模式下左侧增加 session title 的内联显示（因为平板左侧已有 session list panel），使 Rename 按钮旁有足够的视觉元素。或者将 Rename 移到右侧和其他控件并排，左侧只保留大标题。具体方案在实现时根据视觉效果决定。

**影响范围**：
- `ChatTopBar.kt`：根据 `showSessionListInTopBar` 和 `showNewSessionInTopBar` 的组合调整布局
- `MainActivity.kt`：可能调整传给 ChatScreen 的参数

### 5.8 消息模型标注（Phase 5b，对齐 iOS）

**背景**：iOS 在用户消息下方显示回复该消息的模型信息（`MessageRowView.swift` lines 124-129），格式为 `providerID/modelID`，使用 `.caption2` 字号和 `.tertiary` 颜色。Android 的 `MessageWithParts.info.resolvedModel` 已包含同样的数据（且在 `MainViewModel` 的 context usage 计算中已使用），但消息 UI 渲染中未展示。

**实现方案**：

在 `ChatMessageContent.kt` 的 `MessageRow` composable 中，对 assistant 消息添加模型标签：

```kotlin
// 在 MessageRow 中，message parts 渲染之后（或之前）
message.info.resolvedModel?.let { model ->
    Text(
        text = "${model.providerId}/${model.modelId}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}
```

**位置**：放在 assistant 消息 content 的顶部（模型名在消息内容上方），与 iOS 的位置对应。仅对 `role == "assistant"` 的消息显示。

**影响范围**：
- `ChatMessageContent.kt`：`MessageRow` 函数内新增条件渲染

### 5.9 Session List 副标题（对齐 iOS）

**背景**：iOS 的 `SessionRowView` 在标题下方显示相对时间（"5 min ago"）和状态标签（Running/Retrying/Idle）。Android 的 `SwipeRevealRow` 只显示标题，用户无法快速判断 session 的活跃程度。

**实现**：

- `SessionList.kt` 新增 `formatRelativeTime(updatedMs)` 使用 `DateUtils.getRelativeTimeSpanString` 做本地化相对时间格式化
- 新增 `sessionStatusLabel(status)` 和 `sessionStatusColor(status)` 将 `SessionStatus` 映射为显示文本和颜色
- `SwipeRevealRow` 新增 `updatedTime: Long?` 和 `status: SessionStatus?` 参数，标题下方增加 `Column > Row` 副标题行
- 时间使用 `bodySmall` + `onSurfaceVariant`，状态标签使用 `bodySmall` + `FontWeight.Medium` + 对应主题色

**数据来源**：`session.time.updated`（毫秒时间戳）和 `sessionStatuses`（SSE 实时推送），均为已有数据，无需额外 API 请求。

**影响范围**：
- `SessionList.kt`：唯一改动文件

### 5.10 Chat 自动跟随策略

- Chat 列表使用 `reverseLayout = true`，底部为索引 0
- 当列表当前停留在底部时，新消息、tool call、streaming delta 到来后自动滚动到索引 0，适合 monitor session
- 当用户主动滚离底部查看历史内容时，自动跟随暂停，避免打断阅读
- 输入栏右侧操作按钮根据输入框高度在横排 / 竖排之间切换；该阈值已收口到 `ChatUiTuning`
- 录音中允许继续发送已有文本，转写中仍阻止重复录音

### 5.9 文件预览模式

- Markdown：直接渲染为视觉化预览
- Text：使用等宽字体原样显示
- Image：按扩展名识别，服务端返回的 base64 内容解码为位图后显示
- preview 路由判断已经下沉到纯 helper，便于独立测试 Markdown / Image / Binary / Text 四类分支
- 图片预览默认 fit-to-screen，支持双击缩放、拖动平移、系统分享
- Android 分享通过 `FileProvider + ACTION_SEND` 实现，对外仅暴露 cache 中的临时文件 URI

### 5.10 Phase 7 Markdown Web Preview（对齐 iOS PR #94）

实现路径：Files 中 Markdown 默认进入 Web Preview；Kotlin 侧复用 `MarkdownImageResolver` 把相对图片转 data URI；WebView 加载 `app/src/main/assets/web_preview/preview.html`；本地 `markdown-it` 将 Markdown 转 HTML；`DOMPurify` 过滤危险 HTML；Compose toolbar 提供 Web / Native / Source 三态回退。

集成边界：

1. `FilePreviewPane` 对 Markdown 文件提供 `Web Preview`、`Native Preview`、`Markdown Source` 三态。
2. 默认模式为 `Web Preview`；Native Compose Markdown renderer 保留为回退路径。
3. WebView 只加载 app assets 里的 renderer shell，不从网络加载 JS，不直接读取 workspace 文件。`WebSettings.allowFileAccess = true` 仅用于 app asset shell；workspace 文件仍走 data URI。
4. 相对图片复用 `MarkdownImageResolver.resolveImages(...)` 转 data URI，保证 Web / Native / Chat 的路径语义一致。
5. WebView navigation 默认拦截；外链交给系统，workspace 相对链接回 Files。
6. 大文件先显示确认 gate（总长度 `60_000`、单行 `5_000`），避免直接注入超大 Markdown。
7. DOMPurify allowlist 允许基础 Markdown 标签、`details/summary`、`div/span`、`img`、table、inline SVG、局部 `style`；移除 `script`/`iframe`/`form`/`on*`/`javascript:`。
8. Markdown payload 用 JSON serializer 生成，不手写字符串拼接。
9. 深浅色主题通过 CSS 变量传递（`--bg`、`--fg`、`--fg-muted`、`--border`、`--card-bg`、`--ok-*`、`--bad-*`、`--warn-*`、`--block-*`）。
10. App 启动后预热 WebView（加载 `about:blank`）以消除首次切到 Markdown 时的 Chromium 初始化黑闪。Web Preview 首帧用 Native Markdown overlay 覆盖直到 JS bridge 发出 `rendered` 事件。

### 5.11 Phase 7 Tablet Sessions Pane 折叠（对齐 iOS PR #95）

当前 `MainActivity.TabletLayout` 是固定三栏：Sessions/Settings 25%，Files 37.5%，Chat 37.5%。Phase 7 增加一个 transient UI state：

```kotlin
var sessionsPaneCollapsed by rememberSaveable { mutableStateOf(false) }
```

展开状态保持现有权重。折叠状态不渲染左侧 Sessions/Settings pane，Files 与 Chat 各占 `0.5f`。折叠按钮放在左侧 Sessions pane 顶部；展开按钮放在 Files pane 顶部左侧，避免用户折叠后失去恢复入口。

实现边界：

1. 只作用于 `WindowWidthSizeClass.Expanded`。
2. 不改变手机 `PhoneLayout` 的底部 Tab、Chat session sheet 或 edge gesture。
3. 不复用 `expandedSessionIds`，避免 pane collapse 与 session tree row expansion 混淆。
4. 第一版不持久化到 settings；`rememberSaveable` 足够覆盖旋转和配置变化。
5. 需要给 hide/show 按钮稳定 content description：`Hide sessions` / `Show sessions`，供 accessibility 与 UI test 使用。

### 5.12 NFC Quick Prompt（Experimental）

#### Manifest

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="false" />
```

MainActivity 新增 `android:launchMode="singleTop"` 和 NDEF intent-filter：

```xml
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="opencode" android:host="prompt" />
</intent-filter>
```

NfcWriterActivity 注册为单独 Activity（透明 `Theme.Transparent`，`noHistory`）。

#### NDEF tag 格式

URI scheme：`opencode://prompt`，query params：`a`（autoSend `0`/`1`）、`p`（prompt URL-encoded UTF-8）。写入使用 `NdefRecord.createUri(uri)` → `NdefMessage` → `Ndef.writeNdefMessage`。

#### 字节预算

NTAG215 用户可用 504 字节。NDEF TLV wrapper ≈ 3 字节，NDEF Record header ≈ 5 字节。保守取 **480 字节** prompt 上限，生成 URI 后校验总字节数 ≤ 504。

#### Settings 持久化

`SettingsManager` 新增 `nfcEnabled`/`nfcPrompt`/`nfcAutoSend`（EncryptedSharedPreferences），常量 `NFC_PROMPT_MAX_BYTES=480`、`NFC_TAG_MAX_BYTES=504`。

#### Intent 接收

`MainActivity` 在两个路径处理 NFC intent：

1. **`onCreate`**（冷启动）：app 被 tag 唤起时 intent 通过 `getIntent()` 到达，`onCreate` 末尾调用 `handleNfcIntent(intent)`。
2. **`onNewIntent`**（app 已在运行）：`singleTop` 下 tag dispatch 走 `onNewIntent`。

**关键**：`handleNfcIntent` 只在这两处调用，**不放在 Composable body 里**——之前误放在 `setContent` lambda 中导致每次 UI 重组都重复触发，产生数百个垃圾 session。

**ViewModel 初始化竞态**：`onNewIntent` 可能在 `setContent` 给 `mainViewModel` 赋值之前到达。暂存 `pendingNfcPrompt: Pair<String, Boolean>?`，在 `setContent` 第一行消费。

**Debounce**：30 秒 cooldown。`lastNfcTriggerTimeMs` 在 `MainActivity` 实例上，不重置（不依赖 `onResume`）。

#### ViewModel 编排

`MainViewModel.handleNfcPrompt(prompt, autoSend)`：
1. 若 `!settingsManager.nfcEnabled` → 静默 return
2. 设置 `pendingNfcAction = NfcPendingAction(prompt, autoSend)` → `createSession()`
3. `selectSession` → `loadMessages` 的 `onMessagesLoaded` 回调 → `consumePendingNfcAction()`：`setInputText(prompt)` + 条件 `sendMessage()`

`launchLoadMessages` 新增可选 `onMessagesLoaded` 回调，在成功路径末尾调用。

#### NfcWriterActivity

- `onCreate`：取 SettingsManager 的 nfcPrompt/nfcAutoSend，生成 URI，校验字节 ≤ 504
- `onResume`：`enableForegroundDispatch`，使用 `Intent(this, NfcWriterActivity::class.java).addFlags(FLAG_ACTIVITY_SINGLE_TOP)` 构造 PendingIntent，使 tag 到达时走 `onNewIntent`
- `onNewIntent`：`Ndef.get(tag).writeNdefMessage(msg)` → toast → finish
- `onPause`：`disableForegroundDispatch`

设计教训：
- `enableReaderMode` + `FLAG_READER_SKIP_NDEF_CHECK` 在 MIUI/HyperOS 上不抑制系统 "Empty Tag" 弹窗，改用 `enableForegroundDispatch`
- PendingIntent 必须用新构造的 Intent，不能传 Activity 自身的 `intent`（否则 `onNewIntent` 不触发）

#### 风险

- **误触发**：nfcEnabled 开关 + 30s debounce 缓解
- **安全**：prompt 明文写在 tag 上，任何人可读
- **ROM 兼容**：`enableReaderMode` 在 MIUI/HyperOS 不抑制弹窗
- **Composable 重组**：`handleNfcIntent` 绝不能放在 `setContent` lambda 中

### 5.13 Session Deep Link

V1 严格接受 `opencode://session/<session_id>`。`OpenCodeDeepLinkParser` 使用 `java.net.URI`，供 JVM unit test、系统 Intent 和 Chat Markdown 共用；要求 scheme/host 为 `opencode`/`session`，path 只有一个 segment，ID 以 `ses_` 开头且仅含 ASCII 字母、数字、下划线和连字符。parser 拒绝 userinfo、port、query、fragment、encoded slash、重复 percent encoding 和过长 ID。

Manifest 为 `MainActivity` 增加独立的 `ACTION_VIEW + DEFAULT + BROWSABLE` filter，不与 NFC 的 `NDEF_DISCOVERED` filter 合并。`handleIncomingIntent()` 统一分派 cold-start `intent` 与 warm `onNewIntent()`；NFC debounce 和 feature flag 不作用于 session link。手机成功解析后通过 `deepLinkNavigationVersion` 回到 Chat 顶层 route，平板三栏中的 Chat 始终可见。

ViewModel 状态机为：

```text
receive URL
  -> strict parse
  -> store latest pending session ID
  -> wait while disconnected
  -> GET /session/:id on current Host
  -> upsert complete Session
  -> selectSession + existing message/status hydration
```

`deepLinkRouteGeneration` 和当前 Host Profile ID 共同校验异步结果。新链接覆盖旧链接；Host 切换时取消旧 job、递增 generation、保留 pending ID，待新 Host 连接成功后重新 resolve。GET 成功前不修改 `currentSessionId` 或 messages。Session 列表刷新使用 `mergeRefreshedSessionsPreservingLocalActivity(..., currentSessionId)` 保留不在当前分页窗口内的已验证目标，同时用原始 server response 数量计算 `hasMoreSessions`。

Chat Markdown 在 `WorkspaceMarkdownLinkResolver` 之前拦截 `opencode` scheme：合法链接进入同一 ViewModel router，非法链接显示全局 deep-link error；普通 HTTP、file 和 workspace relative link 保持原路径。Activity 根层显示 `deep-link-opening` / `deep-link-error`，因此从 Chat、Files 或 Settings 唤起都可见。

安全边界与 iOS 一致：当前 Host only，不轮询其他 Host，不恢复离线 archive DB，不接受 server/凭证/prompt/tool action，不自动执行 Markdown link。测试覆盖 parser contract、repository by-ID path、断连 pending、成功 hydration、失败保留上下文和 session-window preservation；系统 cold/warm Intent 的 emulator E2E 作为后续可选 Tier 3，不在物理设备执行。

### 5.14 Model Shortlist 管理 UI（对齐 iOS）

- **设置页入口**（`SettingsScreen.kt`）：`AppearanceSection` 之后新增一行 `ModelShortlistEntry`（带 `modelShortlist.size` 数量角标 + `ChevronRight`），复用 `HostProfilesManagerScreen` 的子页面模式（`showModelShortlist` state 切换，渲染 `ModelShortlistScreen(viewModel, onBack)`）。
- **短名单页**（新 `ui/settings/ModelShortlistScreen.kt`）：TopAppBar 标题 "模型列表"，actions 里 "+" → catalog picker。行对齐 `HostProfileRow` 视觉语言（卡片 + 右侧单个 MoreVert 溢出菜单）：主行 displayName + 简称 badge（电蓝小 chip，呼应聊天胶囊里显示的标签），副行 `providerDisplayNames[providerId] ?: providerId / modelId`。所有操作收进 MoreVert 菜单：编辑短名 / 上移 / 下移（决策 D3，不做拖拽）/ 删除（error 色，直接执行，空短名单是合法状态）；上移/下移在首/尾位禁用。点行主体 → 编辑短名 dialog（`AlertDialog` + `OutlinedTextField`）。空态：提示文案 + 醒目"添加模型"按钮。
- **Catalog picker**（同文件 `AddModelCatalogDialog`）：搜索框（displayName/modelId/providerId 三字段过滤）+ 多选 checkbox（排除已在短名单的）+ 底部"添加所选 (n)"（0 选禁用）。catalog 为空时显示"未获取到模型目录"。
- **聊天 picker**（`ChatTopBar.kt`）：DropdownMenu 底部加 "Manage models" 跳转行（`onManageModels` → `requestModelShortlistFocus()` + 跳设置）；`availableModels.isEmpty()` 时显示"去设置添加模型"跳转行。
- **深链**：`pendingModelShortlistFocus` state + `SettingsScreen` 的 `LaunchedEffect` 一次性打开短名单子页（跳转即直接落到目标页，无需额外高亮；消费后清除，不重复触发）。tablet 折叠 Sessions 左栏时，设置跳转同时展开左栏，否则 `SettingsScreen` 不会 composition、pending 无法消费。
- **文案**：`values/strings.xml` + `values-zh/strings.xml` 各新增 17 条（`settings_model_shortlist`、`model_shortlist_*` 等）。

---

## 6. 安全设计

### 6.1 凭证存储

```kotlin
class CredentialManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun saveServerCredentials(url: String, username: String, password: String) {
        sharedPreferences.edit {
            putString("server_url", url)
            putString("auth_username", username)
            putString("auth_password", password)
            putString("ai_builder_base_url", "https://space.ai-builders.com/backend")
            putString("ai_builder_token", "")
        }
    }
}
```

语音相关配置也走 `EncryptedSharedPreferences`：AI Builder Base URL、Token、Custom Prompt、Terminology、上次成功连接签名与时间戳都保存在本地加密存储中。

### 6.2 语音权限与输入行为

- `RECORD_AUDIO` 采用运行时权限请求，未授权时在 Chat 页直接提示
- 录音中允许继续发送当前已输入文本，避免语音输入阻塞文字输入流；WebSocket 卡住时可先 abort 释放发送门控，再按需 retry 上一段 preserved audio
- 转写中保留 loading 态，不允许重复点麦克风，避免并发转写状态冲突

### 6.3 SSH 密钥管理（可选）

```kotlin
class SSHKeyManager(context: Context) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    
    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )
        keyPairGenerator.initialize(
            KeyGenParameterSpec.Builder("ssh_key", KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return keyPairGenerator.generateKeyPair()
    }
}
```

---

## 7. 项目结构

```
app/
├── src/main/
│   ├── java/com/yage/opencode_client/
│   │   ├── OpenCodeApp.kt            # Application 类
│   │   ├── MainActivity.kt           # 入口 Activity
│   │   ├── data/
│   │   │   ├── api/
│   │   │   │   ├── OpenCodeApi.kt    # Retrofit 接口
│   │   │   │   └── SSEClient.kt      # SSE 客户端
│   │   │   ├── model/                # 数据模型
│   │   │   └── repository/           # 数据仓库
│   │   ├── ui/
│   │   │   ├── chat/
│   │   │   │   └── ChatScreen.kt
│   │   │   ├── files/
│   │   │   │   ├── FilesScreen.kt
│   │   │   │   └── FilePreviewUtils.kt
│   │   │   ├── settings/
│   │   │   └── theme/                # 颜色、字体、Markdown typography
│   │   ├── di/                       # Hilt 模块
│   │   └── util/                     # 工具类
│   ├── res/
│   │   ├── xml/network_security_config.xml
│   │   ├── xml/file_paths.xml
│   │   └── ...
│   └── AndroidManifest.xml
├── build.gradle.kts
└── proguard-rules.pro
```

---

## 8. 依赖配置

当前实现以 version catalog 管理依赖，核心依赖如下：

| 类别 | 当前依赖 |
|------|----------|
| Compose | Compose BOM + Material 3 + activity-compose |
| Lifecycle | lifecycle-runtime-compose + viewmodel-compose |
| Network | OkHttp + OkHttp SSE + Retrofit |
| Serialization | kotlinx-serialization-json |
| DI | Hilt + KSP |
| Security | EncryptedSharedPreferences |
| Markdown | multiplatform-markdown-renderer + m3 adapter |

图片预览与分享暂未引入第三方图片库，直接使用 Android 平台位图解码、Compose 手势系统与 `FileProvider`。

测试方面，当前同时保留两层护栏：

- JVM 单元测试：覆盖 ViewModel 状态机、repository 协议行为、preview/helper 纯函数、音频参数与转写辅助逻辑
- connected Android tests：覆盖关键 Compose 交互，同时对依赖真实服务的 smoke integration 采用“未配置或不可达即 skip”的策略，避免环境噪音污染回归结果

---

## 9. 实现规划

| Phase | 范围 | 预计周期 |
|-------|------|----------|
| 1 | 项目搭建、网络层、SSE、Session、消息发送、流式渲染 | 已完成 |
| 2 | Part 渲染、权限审批、主题、语音输入 | 已完成 |
| 3 | 文件树、Markdown / 图片预览、Diff、平板布局 | 已完成 |
| 5 | UX 对齐 iOS：Chat toolbar 重排（§5.4）、Session Rename UI、草稿持久化（§4.3）、Model/Agent per-session（§4.4） | ✅ 完成 |
| 5b | 消息历史分页修复（§5.5）、Model/Agent Capsule 文本化（§5.6）、平板 toolbar 适配（§5.7）、消息模型标注（§5.8） | 1-2 天 |
| 5c | Model Shortlist（§4.5、§5.14）：模型短名单 + 动态 catalog + ID 持久化迁移 + 管理 UI | ✅ 完成 |
| 7 | Markdown Web Preview（§5.10）、Tablet Sessions pane 折叠（§5.11） | 2-4 天 |
| 4 | SSH Tunnel（可选） | 1 周 |

---

## 10. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| Compose 学习曲线 | 团队培训，参考 iOS SwiftUI 经验 |
| SSE 兼容性 | 使用成熟的 OkHttp SSE 库 |
| 平板适配复杂度 | 先完成手机版，平板作为 Phase 3 |
| SSH 库稳定性 | 充分测试，提供降级方案（公网 HTTPS） |
| WebView 安全面扩大 | 本地固定 JS、DOMPurify allowlist、禁 workspace file access、禁任意 navigation |
| Web Preview 大文档性能 | oversize gate、Native/Source 回退，后续再引入 asset loader / custom scheme 优化大图 |

---

## 参考

- [OpenCode Web API](../../../adhoc_jobs/opencode_ios_client/docs/OpenCode_Web_API.md)
- [Android Network Security Config](https://developer.android.com/training/articles/security-config)
- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
