package com.yage.opencode_client.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.data.api.AIUsageClient
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ssh.SSHKeyManager
import com.yage.opencode_client.ssh.TunnelManager
import com.yage.opencode_client.ssh.TunnelResult
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.LanguageMode
import com.yage.opencode_client.util.OpenCodeDeepLink
import com.yage.opencode_client.util.OpenCodeDeepLinkParseResult
import com.yage.opencode_client.util.OpenCodeDeepLinkParser
import com.yage.opencode_client.util.ThemeMode
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import com.yage.voiceflowkit.VoiceFlowMicrophone
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import javax.inject.Inject

data class ConnectionFormSettings(
    val serverUrl: String,
    val username: String,
    val password: String
)

data class AIBuilderSettings(
    val baseURL: String,
    val token: String,
    val customPrompt: String,
    val terminology: String,
    val recordingStrategy: String = "GPT_LIVE_TRANSCRIBE",
)

data class AIUsageSettings(val dashboardUrl: String)

enum class DeepLinkError {
    INVALID,
    SESSION_UNAVAILABLE,
    OPEN_FAILED
}

data class AppState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val sessions: List<Session> = emptyList(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val currentSessionId: String? = null,
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val messages: List<MessageWithParts> = emptyList(),
    val messageLimit: Int = 30,
    val isLoadingMessages: Boolean = false,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgentName: String = "build",
    val selectedModelIndex: Int = 2,
    val selectedModelId: String? = null,
    val modelShortlist: List<ModelShortlistItem> = emptyList(),
    val catalogModels: List<CatalogModel> = emptyList(),
    val providerDisplayNames: Map<String, String> = emptyMap(),
    val pendingModelShortlistFocus: Boolean = false,
    val providers: ProvidersResponse? = null,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val inputText: String = "",
    val error: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val languageMode: LanguageMode = LanguageMode.SYSTEM,
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val hasPreservedSpeechAudio: Boolean = false,
    val isRetryingSpeech: Boolean = false,
    val speechAudioLevel: Float = 0f,
    val speechError: String? = null,
    val aiBuilderConnectionOK: Boolean = false,
    val aiBuilderConnectionError: String? = null,
    val isTestingAIBuilderConnection: Boolean = false,
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap(),
    val sendingSessionIds: Set<String> = emptySet(),
    val sessionSendTimestamps: Map<String, Long> = emptyMap(),
    val pendingOptimisticMessageIds: Set<String> = emptySet(),
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null,
    val connectionPhase: String? = null,
    val pendingNfcAction: NfcPendingAction? = null,
    val pendingDeepLinkSessionId: String? = null,
    val isResolvingDeepLink: Boolean = false,
    val deepLinkError: DeepLinkError? = null,
    val deepLinkNavigationVersion: Long = 0,
    val aiUsageDashboardUrl: String = "",
    val aiUsageQuotaSnapshot: AIUsageQuotaSnapshot? = null,
    val isLoadingAIUsage: Boolean = false,
    val isRefreshingAIUsage: Boolean = false,
    val aiUsageError: String? = null
) {
    data class NfcPendingAction(val prompt: String, val autoSend: Boolean)
    data class ModelOption(val displayName: String, val providerId: String, val modelId: String, val customShortName: String? = null) {
        val shortName: String
            get() = customShortName?.trim()?.takeIf { it.isNotEmpty() } ?: suggestedShortName(displayName)
    }

    data class ContextUsage(
        val percentage: Float,
        val totalTokens: Int,
        val contextLimit: Int,
        val providerId: String? = null,
        val modelId: String? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val reasoningTokens: Int? = null,
        val cachedReadTokens: Int? = null,
        val cachedWriteTokens: Int? = null,
        val cost: Double? = null
    )

    data class ConnectionState(
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val serverVersion: String? = null
    )

    data class SessionState(
        val sessions: List<Session> = emptyList(),
        val currentSessionId: String? = null,
        val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
        val expandedSessionIds: Set<String> = emptySet(),
        val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
        val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
        val messageLimit: Int = 30,
        val pendingPermissions: List<PermissionRequest> = emptyList(),
        val pendingQuestions: List<QuestionRequest> = emptyList()
    ) {
        val currentSession: Session?
            get() = sessions.find { it.id == currentSessionId }

        val currentSessionStatus: SessionStatus?
            get() = currentSessionId?.let { sessionStatuses[it] }

        val isCurrentSessionBusy: Boolean
            get() = currentSessionStatus?.isBusy == true

        val canLoadMoreSessions: Boolean
            get() = hasMoreSessions && !isLoadingMoreSessions
    }

    data class ChatState(
        val messages: List<MessageWithParts> = emptyList(),
        val streamingPartTexts: Map<String, String> = emptyMap(),
        val streamingReasoningPart: Part? = null,
        val isLoadingMessages: Boolean = false,
        val inputText: String = "",
        val imageAttachments: List<ComposerImageAttachment> = emptyList()
    )

    data class SpeechState(
        val isRecording: Boolean = false,
        val isTranscribing: Boolean = false,
        val hasPreservedSpeechAudio: Boolean = false,
        val isRetryingSpeech: Boolean = false,
        val speechError: String? = null,
        val isTestingAIBuilderConnection: Boolean = false,
        val aiBuilderConnectionOK: Boolean = false,
        val aiBuilderConnectionError: String? = null
    )

    data class FileUiState(
        val filePathToShowInFiles: String? = null,
        val filePreviewOriginRoute: String? = null
    )

    data class SettingsState(
        val error: String? = null,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val languageMode: LanguageMode = LanguageMode.SYSTEM,
        val selectedModelIndex: Int = 2,
        val selectedModelId: String? = null,
        val selectedAgentName: String = "build",
        val availableModels: List<ModelOption> = emptyList(),
        val modelShortlist: List<ModelShortlistItem> = emptyList(),
        val catalogModels: List<CatalogModel> = emptyList(),
        val providerDisplayNames: Map<String, String> = emptyMap(),
        val contextUsage: ContextUsage? = null,
        val agents: List<AgentInfo> = emptyList(),
        val providers: ProvidersResponse? = null,
        val isRecording: Boolean = false
    )

    val connectionState: ConnectionState
        get() = ConnectionState(
            isConnected = isConnected,
            isConnecting = isConnecting,
            serverVersion = serverVersion
        )

    val sessionState: SessionState
        get() = SessionState(
            sessions = sessions,
            currentSessionId = currentSessionId,
            sessionStatuses = sessionStatuses,
            expandedSessionIds = expandedSessionIds,
            loadedSessionLimit = loadedSessionLimit,
            hasMoreSessions = hasMoreSessions,
            isLoadingMoreSessions = isLoadingMoreSessions,
            isRefreshingSessions = isRefreshingSessions,
            messageLimit = messageLimit,
            pendingPermissions = pendingPermissions,
            pendingQuestions = pendingQuestions
        )

    val chatState: ChatState
        get() = ChatState(
            messages = visibleMessages,
            streamingPartTexts = streamingPartTexts,
            streamingReasoningPart = streamingReasoningPart,
            isLoadingMessages = isLoadingMessages,
            inputText = inputText,
            imageAttachments = imageAttachments
        )

    val visibleMessages: List<MessageWithParts>
        get() {
            val revertMessageId = currentSession?.revert?.messageId ?: return messages
            return messages.filter { message -> message.info.id < revertMessageId }
        }

    val speechState: SpeechState
        get() = SpeechState(
            isRecording = isRecording,
            isTranscribing = isTranscribing,
            hasPreservedSpeechAudio = hasPreservedSpeechAudio,
            isRetryingSpeech = isRetryingSpeech,
            speechError = speechError,
            isTestingAIBuilderConnection = isTestingAIBuilderConnection,
            aiBuilderConnectionOK = aiBuilderConnectionOK,
            aiBuilderConnectionError = aiBuilderConnectionError
        )

    val fileUiState: FileUiState
        get() = FileUiState(
            filePathToShowInFiles = filePathToShowInFiles,
            filePreviewOriginRoute = filePreviewOriginRoute
        )

    val settingsState: SettingsState
        get() = SettingsState(
            error = error,
            themeMode = themeMode,
            languageMode = languageMode,
            selectedModelIndex = selectedModelIndex,
            selectedModelId = selectedModelId,
            selectedAgentName = selectedAgentName,
            availableModels = availableModels,
            modelShortlist = modelShortlist,
            catalogModels = catalogModels,
            providerDisplayNames = providerDisplayNames,
            contextUsage = contextUsage,
            agents = agents,
            providers = providers,
            isRecording = isRecording
        )

    val currentSession: Session?
        get() = sessions.find { it.id == currentSessionId }

    val currentSessionStatus: SessionStatus?
        get() = currentSessionId?.let { sessionStatuses[it] }

    val attentionSessionIds: List<String>
        get() = pendingPermissions.map { it.sessionId } + pendingQuestions.map { it.sessionId }

    val isCurrentSessionBusy: Boolean
        get() = currentSessionStatus?.isBusy == true

    val canLoadMoreSessions: Boolean
        get() = hasMoreSessions && !isLoadingMoreSessions

    val visibleAgents: List<AgentInfo>
        get() = agents.filter { it.isVisible }

    /** Curated model list (the user shortlist), not the full API response. */
    val availableModels: List<ModelOption>
        get() = modelShortlist.map { item ->
            ModelOption(
                displayName = item.displayName,
                providerId = item.providerId,
                modelId = item.modelId,
                customShortName = item.shortName
            )
        }

    val selectedAIUsageQuota: AIUsageQuota?
        get() {
            val provider = when (availableModels.getOrNull(selectedModelIndex)?.providerId) {
                "openai" -> "codex"
                "zai-coding-plan" -> "glm"
                "ollama-cloud" -> "ollama"
                else -> return null
            }
            return aiUsageQuotaSnapshot?.quotas?.firstOrNull {
                it.provider.equals(provider, ignoreCase = true) && it.label.equals("5h", ignoreCase = true)
            }
        }

    private val providerModelsIndex: Map<String, ProviderModel>
        get() = buildProviderModelsIndex(providers)

    val contextUsage: ContextUsage?
        get() {
            val lastAssistant = messages.lastOrNull { it.info.isAssistant && tokenTotal(it.info.tokens) != null }
                ?: return logContextUsageUnavailable("no assistant message with usable tokens; messages=${messages.size}")
            val tokens = lastAssistant.info.tokens
                ?: return logContextUsageUnavailable("latest assistant has no tokens; messages=${messages.size}")
            val total = tokenTotal(tokens)
                ?: return logContextUsageUnavailable("assistant tokens have no usable totals; tokens=$tokens")
            val model = lastAssistant.info.resolvedModel
                ?: return logContextUsageUnavailable("assistant message has no resolved model; message=${lastAssistant.info.id}")
            val key = "${model.providerId}/${model.modelId}"
            val index = providerModelsIndex
            val providerModel = index[key] ?: index.entries
                .filter { it.key.substringAfter('/') == model.modelId }
                .takeIf { it.size == 1 }
                ?.first()
                ?.value
            val limit = providerModel?.limit?.context
                ?: return logContextUsageUnavailable("no context limit for $key; providerModelKeys=${index.keys.take(12)}")
            if (limit <= 0) return logContextUsageUnavailable("non-positive context limit for $key: $limit")
            return ContextUsage(
                percentage = (total.toFloat() / limit.toFloat()).coerceIn(0f, 1f),
                totalTokens = total,
                contextLimit = limit,
                providerId = model.providerId,
                modelId = model.modelId,
                inputTokens = tokens.input,
                outputTokens = tokens.output,
                reasoningTokens = tokens.reasoning,
                cachedReadTokens = tokens.cache?.read,
                cachedWriteTokens = tokens.cache?.write,
                cost = lastAssistant.info.cost
            )
        }

    private fun logContextUsageUnavailable(reason: String): ContextUsage? {
        runCatching { Log.d("AppState", "contextUsage unavailable: $reason") }
        return null
    }

    private fun tokenTotal(tokens: Message.TokenInfo?): Int? {
        if (tokens == null) return null
        tokens.total?.takeIf { it > 0 }?.let { return it }
        return listOfNotNull(
            tokens.input,
            tokens.output,
            tokens.reasoning,
            tokens.cache?.read,
            tokens.cache?.write
        ).sum().takeIf { it > 0 }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    internal val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val voiceFlowClient: VoiceFlowClient,
    private val microphone: VoiceFlowMicrophone,
    private val hostProfileStore: HostProfileStore,
    private val tunnelManager: TunnelManager,
    private val sshKeyManager: SSHKeyManager,
    private val aiUsageClient: AIUsageClient = AIUsageClient()
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var sseJob: Job? = null
    private var pollJob: Job? = null
    private var speechHeartbeatJob: Job? = null
    private var speechAudioLevelJob: Job? = null
    private var speechTranscriptionJob: Job? = null
    private var speechSessionOwner: SpeechSessionOwner? = null
    private var speechPcmSender: OrderedSpeechPcmSender? = null
    private var speechTypewriter: AttemptScopedSpeechTypewriter? = null
    private var speechCleanupJob: Job? = null
    private var activeSpeechFile: File? = null
    private var activeSpeechFileOwner: SpeechRecordingFileOwner? = null
    private var speechExistingInput: String = ""
    private var speechSourceSessionId: String? = null
    private var activeSpeechStrategy: VoiceFlowRecordingStrategy =
        VoiceFlowRecordingStrategy.OPENAI_REALTIME
    private var speechAttemptId = 0L
    private var activeSpeechAttemptId = 0L
    private var preservedSpeechRecording: PreservedSpeechRecording? = null
    private var lastHealthCheckTime = 0L
    private var deepLinkRouteGeneration = 0L
    private var deepLinkJob: Job? = null
    private var hostRuntimeJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val hostRuntimeScope: CoroutineScope
        get() = CoroutineScope(viewModelScope.coroutineContext + hostRuntimeJob)

    init {
        loadSettings()
    }

    private fun loadSettings() {
        applySavedSettings(repository, settingsManager, hostProfileStore, _state)
        _state.update { it.copy(aiUsageDashboardUrl = settingsManager.aiUsageDashboardUrl) }
    }

    fun configureServer(url: String, username: String? = null, password: String? = null) {
        settingsManager.serverUrl = url
        settingsManager.username = username
        settingsManager.password = password
        repository.configure(url, username, password)
    }

    fun getHostProfiles(): List<HostProfile> = hostProfileStore.profiles()

    fun currentHostProfile(): HostProfile = hostProfileStore.currentProfile()

    fun saveHostProfile(profile: HostProfile, basicAuthPassword: String? = null) {
        val normalized = if (profile.basicAuth != null) {
            profile.copy(basicAuth = profile.basicAuth.copy(passwordId = profile.id))
        } else {
            profile
        }
        if (normalized.basicAuth != null) {
            settingsManager.setBasicAuthPassword(normalized.id, basicAuthPassword)
        }
        hostProfileStore.save(normalized)
        refreshHostProfileState()
    }

    fun selectHostProfile(profileId: String) {
        invalidateDeepLinkRoute(keepPending = true)
        resetRuntimeForHostSwitch()
        hostRuntimeScope.launch {
            val profile = hostProfileStore.select(profileId)
            configureRepositoryForProfileAsync(profile)
            refreshHostProfileState()
            testConnection(force = true)
        }
    }

    fun duplicateHostProfile(profileId: String) {
        hostProfileStore.duplicate(profileId)
        refreshHostProfileState()
    }

    fun deleteHostProfile(profileId: String) {
        val deletingCurrentProfile = profileId == _state.value.currentHostProfileId
        if (deletingCurrentProfile) {
            invalidateDeepLinkRoute(keepPending = true)
            resetRuntimeForHostSwitch()
        }
        hostProfileStore.delete(profileId)
        val current = hostProfileStore.currentProfile()
        configureRepositoryForProfile(current, startTunnel = false)
        refreshHostProfileState()
        if (deletingCurrentProfile) {
            testConnection(force = true)
        }
    }

    fun importHostProfile(payload: String): Result<HostProfile> = runCatching {
        hostProfileStore.importJson(payload).also { refreshHostProfileState() }
    }

    fun exportHostProfile(profile: HostProfile): String = hostProfileStore.exportJson(profile)

    fun ensureSshPublicKey(): String = sshKeyManager.ensureKeyPair()

    fun sshPublicKey(): String? = sshKeyManager.publicKey()

    fun rotateSshKey(): String = sshKeyManager.rotateKey()

    private fun refreshHostProfileState() {
        _state.update {
            it.copy(
                hostProfiles = hostProfileStore.profiles(),
                currentHostProfileId = hostProfileStore.currentProfile().id
            )
        }
    }

    private fun configureRepositoryForProfile(profile: HostProfile, startTunnel: Boolean) {
        val password = profile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
        if (profile.transport == HostTransport.SSH_TUNNEL && startTunnel) {
            hostRuntimeScope.launch { configureRepositoryForProfileAsync(profile) }
            return
        }
        repository.configure(profile.serverUrl, profile.basicAuth?.username, password)
    }

    private suspend fun configureRepositoryForProfileAsync(profile: HostProfile): Boolean {
        val password = profile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
        val baseUrl = when (profile.transport) {
            HostTransport.DIRECT -> profile.serverUrl
            HostTransport.SSH_TUNNEL -> {
                val ssh = profile.ssh ?: run {
                    _state.update { it.copy(error = "SSH profile is missing tunnel settings") }
                    return false
                }
                when (val result = tunnelManager.ensureStarted(ssh)) {
                    is TunnelResult.Success -> result.localUrl
                    is TunnelResult.Failure -> {
                        _state.update {
                            it.copy(
                                isConnected = false,
                                isConnecting = false,
                                connectionPhase = result.phase.name,
                                error = result.message
                            )
                        }
                        return false
                    }
                }
            }
        }
        repository.configure(baseUrl, profile.basicAuth?.username, password)
        return true
    }

    fun getSavedConnectionSettings(): ConnectionFormSettings = ConnectionFormSettings(
        serverUrl = settingsManager.serverUrl,
        username = settingsManager.username ?: "",
        password = settingsManager.password ?: ""
    )

    fun getAIBuilderSettings(): AIBuilderSettings = AIBuilderSettings(
        baseURL = settingsManager.aiBuilderBaseURL,
        token = settingsManager.aiBuilderToken,
        customPrompt = settingsManager.aiBuilderCustomPrompt,
        terminology = settingsManager.aiBuilderTerminology,
        recordingStrategy = settingsManager.aiBuilderRecordingStrategy,
    )

    fun saveAIBuilderSettings(settings: AIBuilderSettings) {
        settingsManager.aiBuilderBaseURL = settings.baseURL
        settingsManager.aiBuilderToken = settings.token
        settingsManager.aiBuilderCustomPrompt = settings.customPrompt
        settingsManager.aiBuilderTerminology = settings.terminology
        settingsManager.aiBuilderRecordingStrategy = settings.recordingStrategy
        // Do not wipe the connection-OK state here. Save now auto-tests, which
        // sets the OK/error state from the live probe. Wiping unconditionally
        // forced users to re-test after every Save even when credentials were
        // unchanged.
    }

    fun testAIBuilderConnection() {
        launchAIBuilderConnectionTest(viewModelScope, settingsManager, voiceFlowClient, _state)
    }

    fun getAIUsageSettings(): AIUsageSettings = AIUsageSettings(settingsManager.aiUsageDashboardUrl)

    fun saveAIUsageSettings(settings: AIUsageSettings) {
        settingsManager.aiUsageDashboardUrl = settings.dashboardUrl.trim()
        _state.update {
            it.copy(
                aiUsageDashboardUrl = settings.dashboardUrl.trim(),
                aiUsageQuotaSnapshot = null,
                aiUsageError = null
            )
        }
    }

    fun loadAIUsage() {
        val url = settingsManager.aiUsageDashboardUrl
        if (url.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingAIUsage = true, aiUsageError = null) }
            aiUsageClient.fetchQuotas(url)
                .onSuccess { snapshot ->
                    _state.update { it.copy(aiUsageQuotaSnapshot = snapshot, isLoadingAIUsage = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoadingAIUsage = false, aiUsageError = error.message) }
                }
        }
    }

    fun refreshAIUsage() {
        val url = settingsManager.aiUsageDashboardUrl
        if (url.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingAIUsage = true, isRefreshingAIUsage = true, aiUsageError = null) }
            aiUsageClient.refreshDashboard(url)
                .onFailure { error ->
                    _state.update {
                        it.copy(isLoadingAIUsage = false, isRefreshingAIUsage = false, aiUsageError = error.message)
                    }
                    return@launch
                }
            aiUsageClient.fetchQuotas(url)
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            aiUsageQuotaSnapshot = snapshot,
                            isLoadingAIUsage = false,
                            isRefreshingAIUsage = false
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isLoadingAIUsage = false, isRefreshingAIUsage = false, aiUsageError = error.message)
                    }
                }
        }
    }

    fun toggleRecording() {
        val currentState = _state.value
        val speechConfig = currentSpeechInputConfig(settingsManager)
        Log.d(
            TAG,
            "toggleRecording clicked: recording=${currentState.isRecording}, transcribing=${currentState.isTranscribing}, aiBuilderOK=${currentState.aiBuilderConnectionOK}, tokenSet=${speechConfig.token.isNotEmpty()}"
        )
        if (!currentState.isRecording && !currentState.isTranscribing && !currentState.isRetryingSpeech &&
            (speechTranscriptionJob?.isActive == true || speechCleanupJob?.isActive == true)
        ) {
            _state.update {
                it.copy(speechError = "Still finishing the previous recording, please wait.")
            }
            return
        }
        if (currentState.isTranscribing) {
            Log.w(TAG, "Ignoring toggle while transcription is in progress")
            _state.update {
                it.copy(speechError = "Still transcribing previous audio, please wait.")
            }
            return
        }
        if (currentState.isRetryingSpeech) {
            Log.w(TAG, "Ignoring toggle while preserved audio retry is in progress")
            return
        }
        if (currentState.isRecording) {
            val owner = speechSessionOwner
            val sender = speechPcmSender
            val strategy = activeSpeechStrategy
            val attemptId = activeSpeechAttemptId
            val target = SpeechDraftTarget(speechSourceSessionId, speechExistingInput)
            val fileOwner = activeSpeechFileOwner ?: SpeechRecordingFileOwner().also {
                activeSpeechFileOwner = it
            }
            val typewriter = speechTypewriter
            stopSpeechAudioLevelConsumer()
            speechHeartbeatJob?.cancel()
            speechHeartbeatJob = null
            _state.update { it.copy(isRecording = false, isTranscribing = true) }
            speechTranscriptionJob = viewModelScope.launch {
                val audioFile = fileOwner.record(runCatching { microphone.stop() }.getOrNull())
                activeSpeechFile = audioFile
                if (!isCurrentSpeechAttempt(attemptId)) {
                    sender?.cancel()
                    fileOwner.claimForAttempt(audioFile)?.let(::deleteSpeechFileUnlessPreserved)
                    return@launch
                }
                if (strategy.usesRealtimeTransport) {
                    if (owner == null) {
                        Log.e(TAG, "Realtime speech session is missing on stop")
                        preserveSpeechFile(
                            fileOwner.claimForAttempt(audioFile),
                            strategy,
                            target,
                            attemptId,
                        )
                        _state.update {
                            it.copy(
                                isTranscribing = false,
                                speechError = "Recording failed: realtime session missing",
                            )
                        }
                        speechTranscriptionJob = null
                        return@launch
                    }
                    try {
                        sender?.closeAndDrain()
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to drain realtime PCM before commit", error)
                        finishRealtimeSession(
                            owner,
                            true,
                            fileOwner.claimForAttempt(audioFile),
                            target,
                            attemptId,
                            preferFallbackFile = true,
                        )
                        if (isCurrentSpeechAttempt(attemptId)) {
                            _state.update {
                                it.copy(
                                    isTranscribing = false,
                                    speechError = errorMessageOrFallback(error, "Failed to send recorded audio"),
                                )
                            }
                            speechSessionOwner = null
                            speechTranscriptionJob = null
                        }
                        return@launch
                    } finally {
                        if (speechPcmSender === sender) speechPcmSender = null
                    }
                    speechTranscriptionJob = launchRealtimeSpeechStop(
                        scope = viewModelScope,
                        state = _state,
                        session = owner.session,
                        existingInput = target.existingInput,
                        tag = TAG,
                        shouldApply = {
                            isCurrentSpeechAttempt(attemptId) &&
                                speechSessionOwner === owner
                        },
                        shouldPreserve = { isCurrentSpeechAttempt(attemptId) },
                        onPartialTranscript = { partial ->
                            typewriter?.submit(partial)
                        },
                        onFinalTranscript = { transcript ->
                            typewriter?.cancel()
                            if (speechTypewriter === typewriter) speechTypewriter = null
                            acceptSpeechDraft(target, transcript)
                        },
                        onFailure = { error ->
                            typewriter?.cancel()
                            if (speechTypewriter === typewriter) speechTypewriter = null
                            reportSpeechFailure(target, error)
                        },
                        onCommitted = {
                            owner.markCommitted()
                            val ownedFile = fileOwner.claimForAttempt(audioFile)
                            if (isCurrentSpeechAttempt(attemptId) && speechSessionOwner === owner) {
                                deleteSpeechFileUnlessPreserved(ownedFile)
                            } else {
                                preserveSpeechFile(ownedFile, owner.session.strategy, target, attemptId)
                            }
                            if (activeSpeechFile === audioFile) activeSpeechFile = null
                        },
                        terminateSession = { preserve ->
                            finishRealtimeSession(
                                owner,
                                preserve,
                                fileOwner.claimForAttempt(audioFile),
                                target,
                                attemptId,
                            )
                        },
                    ) {
                        if (speechSessionOwner === owner) speechSessionOwner = null
                        if (activeSpeechFileOwner === fileOwner && fileOwner.current() == null) {
                            activeSpeechFileOwner = null
                        }
                        speechTranscriptionJob = null
                    }
                } else {
                    var succeeded = false
                    try {
                        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
                            throw IllegalStateException("Empty Grok recording")
                        }
                        val result = voiceFlowClient.transcribe(
                            audioFile = audioFile,
                            strategy = strategy,
                        )
                        val cleaned = result.text.trim()
                        if (!isCurrentSpeechAttempt(attemptId)) return@launch
                        succeeded = true
                        Log.d(TAG, "Grok batch transcription success: chars=${cleaned.length}")
                        acceptSpeechDraft(target, cleaned)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (!isCurrentSpeechAttempt(attemptId)) return@launch
                        Log.e(TAG, "Grok batch speech processing failed", error)
                        reportSpeechFailure(target, error)
                    } finally {
                        withContext(NonCancellable) {
                            val ownedFile = fileOwner.claimForAttempt(audioFile)
                            if (ownedFile != null) {
                                if (succeeded) {
                                    deleteSpeechFileUnlessPreserved(ownedFile)
                                } else if (isCurrentSpeechAttempt(attemptId)) {
                                    preserveSpeechFile(ownedFile, strategy, target, attemptId)
                                } else {
                                    deleteSpeechFileUnlessPreserved(ownedFile)
                                }
                            }
                            if (activeSpeechFile === audioFile) activeSpeechFile = null
                            if (activeSpeechFileOwner === fileOwner && fileOwner.current() == null) {
                                activeSpeechFileOwner = null
                            }
                            if (isCurrentSpeechAttempt(attemptId)) {
                                _state.update { it.copy(isTranscribing = false) }
                                speechTranscriptionJob = null
                            }
                        }
                    }
                }
            }
        } else {
            if (speechConfig.token.isEmpty()) {
                Log.w(TAG, "Speech start blocked: missing AI Builder token")
                _state.update {
                    it.copy(speechError = "Speech recognition requires an AI Builder token. Configure it in Settings.")
                }
                return
            }
            if (!currentState.aiBuilderConnectionOK) {
                Log.w(TAG, "Speech start blocked: AI Builder connection test has not passed")
                _state.update {
                    it.copy(speechError = "AI Builder connection test has not passed. Please test in Settings first.")
                }
                return
            }
            val strategy = speechConfig.recordingStrategy
            val target = SpeechDraftTarget(currentState.currentSessionId, currentState.inputText)
            val attemptId = beginSpeechAttempt()
            val fileOwner = SpeechRecordingFileOwner()
            speechExistingInput = target.existingInput
            speechSourceSessionId = target.sessionId
            activeSpeechStrategy = strategy
            activeSpeechAttemptId = attemptId
            activeSpeechFile = null
            activeSpeechFileOwner = fileOwner
            speechTranscriptionJob = viewModelScope.launch {
                var owner: SpeechSessionOwner? = null
                var sender: OrderedSpeechPcmSender? = null
                try {
                    // Refresh the library config with the latest endpoint/token/prompt/
                    // terms before opening the session.
                    voiceFlowClient.updateConfig(
                        VoiceFlowConfig(
                            endpoint = speechConfig.baseURL.ifEmpty { VoiceFlowConfig.DEFAULT_ENDPOINT },
                            tokenProvider = { speechConfig.token },
                            prompt = speechConfig.prompt.ifEmpty { null },
                            terms = speechConfig.terms,
                        )
                    )
                    if (!isCurrentSpeechAttempt(attemptId)) return@launch
                    clearPreservedSpeechAudio()
                    if (strategy.usesRealtimeTransport) {
                        val session = voiceFlowClient.startSession(strategy)
                        owner = SpeechSessionOwner(session)
                        speechSessionOwner = owner
                        if (!isCurrentSpeechAttempt(attemptId)) {
                            owner.discard()
                            return@launch
                        }
                        sender = OrderedSpeechPcmSender(viewModelScope) { chunk ->
                            session.sendAudioChunk(chunk)
                        }
                        speechPcmSender = sender
                        startSpeechAudioLevelConsumer()
                        microphone.start(strategy = strategy) { chunk ->
                            if (isCurrentSpeechAttempt(attemptId) && speechSessionOwner === owner) {
                                if (!sender.trySend(chunk) && _state.value.currentSessionId == target.sessionId) {
                                    _state.update {
                                        it.copy(speechError = OrderedSpeechPcmSender.BUFFER_FULL_MESSAGE)
                                    }
                                }
                            }
                        }
                        speechHeartbeatJob?.cancel()
                        speechHeartbeatJob = viewModelScope.launch {
                            while (true) {
                                delay(SPEECH_HEARTBEAT_INTERVAL_SECONDS * 1000L)
                                if (isCurrentSpeechAttempt(attemptId) && speechSessionOwner === owner) {
                                    session.ping()
                                }
                            }
                        }
                        Log.d(TAG, "Realtime recording started")
                    } else {
                        speechSessionOwner = null
                        startSpeechAudioLevelConsumer()
                        microphone.start(strategy = strategy, onPCMChunk = null)
                        Log.d(TAG, "Grok batch recording started")
                    }
                    if (isCurrentSpeechAttempt(attemptId)) {
                        _state.update { it.copy(isRecording = true, speechError = null) }
                        speechTypewriter = AttemptScopedSpeechTypewriter(
                            scope = viewModelScope,
                            shouldApply = {
                                isCurrentSpeechAttempt(attemptId) &&
                                    _state.value.currentSessionId == target.sessionId
                            },
                        ) { partial ->
                            _state.update {
                                it.copy(inputText = mergedSpeechInput(target.existingInput, partial))
                            }
                        }
                        speechTranscriptionJob = null
                    }
                } catch (cancelled: CancellationException) {
                    val audioFile = fileOwner.record(runCatching { microphone.stop() }.getOrNull())
                    activeSpeechFile = audioFile
                    sender?.cancel()
                    if (!fileOwner.isCleanupOwner()) {
                        runCatching { owner?.discard() }
                        fileOwner.claimForAttempt(audioFile)?.let(::deleteSpeechFileUnlessPreserved)
                    }
                    throw cancelled
                } catch (e: Exception) {
                    val audioFile = fileOwner.record(runCatching { microphone.stop() }.getOrNull())
                    activeSpeechFile = audioFile
                    runCatching { sender?.closeAndDrain() }
                    if (!isCurrentSpeechAttempt(attemptId)) {
                        owner?.discard()
                        fileOwner.claimForAttempt(audioFile)?.let(::deleteSpeechFileUnlessPreserved)
                        return@launch
                    }
                    Log.e(TAG, "Failed to start recording", e)
                    stopSpeechAudioLevelConsumer()
                    if (owner != null) {
                        finishRealtimeSession(
                            owner,
                            true,
                            fileOwner.claimForAttempt(audioFile),
                            target,
                            attemptId,
                        )
                    } else {
                        preserveSpeechFile(
                            fileOwner.claimForAttempt(audioFile),
                            strategy,
                            target,
                            attemptId,
                        )
                    }
                    speechSessionOwner = null
                    speechPcmSender = null
                    activeSpeechFile = null
                    if (activeSpeechFileOwner === fileOwner) activeSpeechFileOwner = null
                    speechHeartbeatJob?.cancel()
                    speechHeartbeatJob = null
                    _state.update {
                        it.copy(
                            isRecording = false,
                            speechError = "Failed to start recording: ${errorMessageOrFallback(e, "unknown error")}"
                        )
                    }
                }
            }
        }
    }

    private fun beginSpeechAttempt(): Long {
        speechTranscriptionJob?.cancel()
        speechTranscriptionJob = null
        speechPcmSender?.cancel()
        speechPcmSender = null
        speechTypewriter?.cancel()
        speechTypewriter = null
        speechAttemptId += 1
        return speechAttemptId
    }

    private fun isCurrentSpeechAttempt(attemptId: Long): Boolean = speechAttemptId == attemptId

    private fun acceptSpeechDraft(target: SpeechDraftTarget, transcript: String) {
        val merged = mergedSpeechInput(target.existingInput, transcript.trim())
        if (_state.value.currentSessionId == target.sessionId) {
            _state.update {
                it.copy(
                    inputText = merged,
                    isTranscribing = false,
                    isRetryingSpeech = false,
                    speechError = null,
                )
            }
        }
        target.sessionId?.let { settingsManager.setDraftText(it, merged) }
    }

    private fun reportSpeechFailure(target: SpeechDraftTarget, error: Throwable) {
        if (_state.value.currentSessionId != target.sessionId) return
        _state.update {
            it.copy(
                inputText = speechFailureInput(target.existingInput, it.inputText),
                isTranscribing = false,
                isRetryingSpeech = false,
                speechError = errorMessageOrFallback(error, "Transcription failed"),
            )
        }
    }

    private suspend fun finishRealtimeSession(
        owner: SpeechSessionOwner,
        preserveAudio: Boolean,
        fallbackFile: File?,
        target: SpeechDraftTarget,
        attemptId: Long,
        preferFallbackFile: Boolean = false,
    ) {
        if (!preserveAudio) {
            runCatching { owner.discard() }
                .onFailure { Log.e(TAG, "Failed to discard speech session", it) }
            deleteSpeechFileUnlessPreserved(fallbackFile)
            return
        }

        val preserved = try {
            owner.preserve()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to preserve speech session", error)
            null
        }
        val recording = when {
            preferFallbackFile && fallbackFile.isUsableSpeechFile() ->
                PreservedSpeechRecording.FileRecording(fallbackFile!!, owner.session.strategy, target)
            preserved != null -> PreservedSpeechRecording.Realtime(preserved, target)
            fallbackFile.isUsableSpeechFile() ->
                PreservedSpeechRecording.FileRecording(fallbackFile!!, owner.session.strategy, target)
            else -> null
        }
        if (recording is PreservedSpeechRecording.Realtime) {
            deleteSpeechFileUnlessPreserved(fallbackFile)
        } else if (preserved != null) {
            voiceFlowClient.discardPreservedAudio(preserved)
        }

        if (recording != null && isCurrentSpeechAttempt(attemptId)) {
            installPreservedSpeechRecording(recording)
        } else if (recording != null) {
            discardSpeechRecordingUnlessCurrent(recording)
        } else {
            deleteSpeechFileUnlessPreserved(fallbackFile)
        }
        if (activeSpeechFile === fallbackFile) activeSpeechFile = null
    }

    private fun preserveSpeechFile(
        file: File?,
        strategy: VoiceFlowRecordingStrategy,
        target: SpeechDraftTarget,
        attemptId: Long,
    ) {
        if (!file.isUsableSpeechFile()) return
        val recording = PreservedSpeechRecording.FileRecording(file!!, strategy, target)
        if (isCurrentSpeechAttempt(attemptId)) {
            installPreservedSpeechRecording(recording)
        } else {
            discardSpeechRecordingUnlessCurrent(recording)
        }
    }

    private fun installPreservedSpeechRecording(
        recording: PreservedSpeechRecording,
    ) {
        val previous = preservedSpeechRecording
        if (previous != null && !previous.hasSameBacking(recording)) {
            discardSpeechRecording(previous)
        }
        preservedSpeechRecording = recording
        _state.update { it.copy(hasPreservedSpeechAudio = true) }
    }

    private fun discardSpeechRecordingUnlessCurrent(recording: PreservedSpeechRecording) {
        if (preservedSpeechRecording?.hasSameBacking(recording) != true) {
            discardSpeechRecording(recording)
        }
    }

    private fun discardSpeechRecording(recording: PreservedSpeechRecording) {
        when (recording) {
            is PreservedSpeechRecording.Realtime -> voiceFlowClient.discardPreservedAudio(recording.audio)
            is PreservedSpeechRecording.FileRecording -> recording.file.delete()
        }
    }

    private fun PreservedSpeechRecording.hasSameBacking(other: PreservedSpeechRecording): Boolean =
        when {
            this is PreservedSpeechRecording.Realtime && other is PreservedSpeechRecording.Realtime ->
                audio === other.audio || audio.id == other.audio.id
            this is PreservedSpeechRecording.FileRecording && other is PreservedSpeechRecording.FileRecording ->
                file.absolutePath == other.file.absolutePath
            else -> false
        }

    private fun File?.isUsableSpeechFile(): Boolean =
        this != null && exists() && length() > 0L

    private fun deleteSpeechFileUnlessPreserved(file: File?) {
        if (file == null) return
        val preservedFile = (preservedSpeechRecording as? PreservedSpeechRecording.FileRecording)?.file
        if (preservedFile?.absolutePath != file.absolutePath) file.delete()
    }

    fun stopSpeechForBackground() {
        scheduleSpeechCleanup()
    }

    private fun scheduleSpeechCleanup() {
        if (speechCleanupJob?.isActive == true) return
        val owner = speechSessionOwner
        val sender = speechPcmSender
        val fileOwner = activeSpeechFileOwner
        val file = activeSpeechFile
        val strategy = activeSpeechStrategy
        val target = SpeechDraftTarget(speechSourceSessionId, speechExistingInput)
        val originalJob = speechTranscriptionJob
        val shouldStopMicrophone = _state.value.isRecording || fileOwner != null || owner != null || sender != null
        fileOwner?.handoffToCleanup()
        val cleanupAttemptId = if (owner == null) {
            speechAttemptId += 1
            speechAttemptId
        } else {
            activeSpeechAttemptId
        }
        speechTranscriptionJob = null
        speechHeartbeatJob?.cancel()
        speechHeartbeatJob = null
        speechTypewriter?.cancel()
        speechTypewriter = null
        stopSpeechAudioLevelConsumer()
        speechSessionOwner = null
        speechPcmSender = null
        _state.update {
            it.copy(
                isRecording = false,
                isTranscribing = false,
                isRetryingSpeech = false,
                speechAudioLevel = 0f,
            )
        }
        speechCleanupJob = viewModelScope.launch {
            originalJob?.cancelAndJoin()
            val stoppedFile = if (shouldStopMicrophone) {
                fileOwner?.record(runCatching { microphone.stop() }.getOrNull())
                    ?: runCatching { microphone.stop() }.getOrNull()
                    ?: file
            } else {
                file
            }
            val ownedFile = fileOwner?.claimForCleanup(stoppedFile) ?: stoppedFile
            val senderFailure = runCatching { sender?.closeAndDrain() }.exceptionOrNull()
            if (owner != null) {
                finishRealtimeSession(
                    owner,
                    true,
                    ownedFile,
                    target,
                    cleanupAttemptId,
                    preferFallbackFile = senderFailure != null,
                )
            } else if (fileOwner != null) {
                preserveSpeechFile(ownedFile, strategy, target, cleanupAttemptId)
            }
            if (activeSpeechFile === stoppedFile || activeSpeechFile === file) activeSpeechFile = null
            if (activeSpeechFileOwner === fileOwner) activeSpeechFileOwner = null
            speechCleanupJob = null
        }
    }

    fun clearSpeechError() {
        _state.update { it.copy(speechError = null) }
    }

    fun abortSpeechRecognition() {
        if (speechSessionOwner == null && activeSpeechFileOwner == null &&
            !_state.value.isRecording && !_state.value.isTranscribing && !_state.value.isRetryingSpeech
        ) return
        scheduleSpeechCleanup()
    }

    fun retryPreservedSpeechAudio() {
        val preserved = preservedSpeechRecording ?: return
        val target = preserved.target
        val attemptId = beginSpeechAttempt()
        val typewriter = AttemptScopedSpeechTypewriter(
            scope = viewModelScope,
            shouldApply = {
                isCurrentSpeechAttempt(attemptId) &&
                    preservedSpeechRecording === preserved &&
                    _state.value.currentSessionId == target.sessionId
            },
        ) { partial ->
            _state.update {
                it.copy(inputText = mergedSpeechInput(target.existingInput, partial))
            }
        }
        speechTypewriter = typewriter
        _state.update { it.copy(isRetryingSpeech = true) }
        speechTranscriptionJob = viewModelScope.launch {
            try {
                val onPartial: (String) -> Unit = { partial ->
                    if (isCurrentSpeechAttempt(attemptId) && preservedSpeechRecording === preserved) {
                        typewriter.submit(partial)
                    }
                }
                val result = when (preserved) {
                    is PreservedSpeechRecording.Realtime ->
                        voiceFlowClient.transcribe(preserved.audio, onPartial)
                    is PreservedSpeechRecording.FileRecording ->
                        voiceFlowClient.transcribe(preserved.file, preserved.strategy, onPartial)
                }
                if (!isCurrentSpeechAttempt(attemptId) || preservedSpeechRecording !== preserved) return@launch
                typewriter.cancel()
                acceptSpeechDraft(target, result.text)
                clearPreservedSpeechAudio()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrentSpeechAttempt(attemptId)) return@launch
                Log.e(TAG, "Failed to retry preserved speech audio", error)
                typewriter.cancel()
                reportSpeechFailure(target, error)
            } finally {
                typewriter.cancel()
                if (speechTypewriter === typewriter) speechTypewriter = null
                if (isCurrentSpeechAttempt(attemptId)) {
                    _state.update { it.copy(isRetryingSpeech = false) }
                    speechTranscriptionJob = null
                }
            }
        }
    }

    private fun clearPreservedSpeechAudio() {
        preservedSpeechRecording?.let(::discardSpeechRecording)
        preservedSpeechRecording = null
        _state.update { it.copy(hasPreservedSpeechAudio = false) }
    }

    fun discardPreservedSpeechAudio() {
        val retryJob = speechTranscriptionJob
        val preserved = preservedSpeechRecording
        beginSpeechAttempt()
        _state.update { it.copy(isRetryingSpeech = false) }
        if (retryJob != null && !retryJob.isCompleted) {
            speechCleanupJob = viewModelScope.launch {
                retryJob.cancelAndJoin()
                if (preservedSpeechRecording === preserved) clearPreservedSpeechAudio()
                speechCleanupJob = null
            }
        } else {
            clearPreservedSpeechAudio()
        }
    }

    private fun startSpeechAudioLevelConsumer() {
        speechAudioLevelJob?.cancel()
        _state.update { it.copy(speechAudioLevel = 0f) }
        speechAudioLevelJob = viewModelScope.launch {
            microphone.audioLevel.collect { level ->
                _state.update { it.copy(speechAudioLevel = level.coerceIn(0f, 1f)) }
            }
        }
    }

    private fun stopSpeechAudioLevelConsumer() {
        speechAudioLevelJob?.cancel()
        speechAudioLevelJob = null
        _state.update { it.copy(speechAudioLevel = 0f) }
    }

    fun setSpeechError(message: String) {
        _state.update { it.copy(speechError = message) }
    }

    fun testConnection(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHealthCheckTime < 30_000) return
        lastHealthCheckTime = now
        hostRuntimeScope.launch {
            _state.update { it.copy(isConnecting = true, error = null, connectionPhase = null) }
            val profile = hostProfileStore.currentProfile()
            if (!configureRepositoryForProfileAsync(profile)) return@launch
            repository.checkHealth()
                .onSuccess { health ->
                    _state.update {
                        it.copy(
                            isConnected = health.healthy,
                            serverVersion = health.version,
                            isConnecting = false,
                            connectionPhase = if (health.healthy) "connected" else "health"
                        )
                    }
                    if (health.healthy) {
                        loadInitialData()
                        startSSE()
                        startBusyPolling()
                        processPendingDeepLinkIfPossible()
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            connectionPhase = "health",
                            error = errorMessageOrFallback(error, "Connection failed")
                        )
                    }
                }
        }
    }

    private fun loadInitialData() {
        loadSessions()
        loadAgents()
        loadProviders()
        loadPendingPermissions()
        loadPendingQuestions()
    }

    fun loadSessions() {
        launchLoadSessions(
            scope = hostRuntimeScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession,
            onLoadSessionStatus = ::loadSessionStatus,
            onLoadMessages = { sessionId -> loadMessages(sessionId) }
        )
    }

    fun loadMoreSessions() {
        launchLoadMoreSessions(
            scope = hostRuntimeScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession
        )
    }

    private fun loadSessionStatus() {
        launchLoadSessionStatus(hostRuntimeScope, repository, _state)
    }

    fun selectSession(sessionId: String) {
        if (_state.value.currentSessionId != sessionId) {
            if (_state.value.isRecording ||
                (activeSpeechFileOwner != null && !_state.value.isTranscribing && !_state.value.isRetryingSpeech)
            ) {
                scheduleSpeechCleanup()
            } else {
                speechTypewriter?.cancel()
                speechTypewriter = null
            }
        }
        selectSessionState(_state, settingsManager, sessionId)
        _state.update {
            it.copy(
                isRecording = false,
                isTranscribing = false,
                isRetryingSpeech = false,
                speechAudioLevel = 0f,
                speechError = null,
            )
        }
        loadMessages(sessionId)
        loadSessionStatus()
    }

    fun receiveDeepLink(rawUrl: String) {
        when (val parsed = OpenCodeDeepLinkParser.parse(rawUrl)) {
            is OpenCodeDeepLinkParseResult.Success -> {
                val sessionId = (parsed.deepLink as OpenCodeDeepLink.Session).id
                deepLinkRouteGeneration += 1
                deepLinkJob?.cancel()
                _state.update {
                    it.copy(
                        pendingDeepLinkSessionId = sessionId,
                        isResolvingDeepLink = false,
                        deepLinkError = null
                    )
                }
                processPendingDeepLinkIfPossible()
            }
            OpenCodeDeepLinkParseResult.InvalidSessionLink,
            OpenCodeDeepLinkParseResult.UnsupportedScheme -> {
                invalidateDeepLinkRoute(keepPending = false)
                _state.update { it.copy(deepLinkError = DeepLinkError.INVALID) }
            }
        }
    }

    internal fun processPendingDeepLinkIfPossible() {
        val snapshot = _state.value
        val sessionId = snapshot.pendingDeepLinkSessionId ?: return
        if (!snapshot.isConnected) return

        deepLinkRouteGeneration += 1
        val generation = deepLinkRouteGeneration
        val hostProfileId = snapshot.currentHostProfileId
        deepLinkJob?.cancel()
        _state.update { it.copy(isResolvingDeepLink = true, deepLinkError = null) }
        deepLinkJob = hostRuntimeScope.launch {
            repository.getSession(sessionId)
                .onSuccess { session ->
                    if (!isCurrentDeepLinkRoute(generation, hostProfileId, sessionId)) return@onSuccess
                    _state.update {
                        it.copy(
                            sessions = upsertSession(it.sessions, session),
                            pendingDeepLinkSessionId = null,
                            isResolvingDeepLink = false,
                            deepLinkError = null,
                            deepLinkNavigationVersion = it.deepLinkNavigationVersion + 1
                        )
                    }
                    if (_state.value.currentSessionId != session.id) {
                        selectSession(session.id)
                    }
                }
                .onFailure { error ->
                    if (!isCurrentDeepLinkRoute(generation, hostProfileId, sessionId)) return@onFailure
                    _state.update {
                        it.copy(
                            pendingDeepLinkSessionId = null,
                            isResolvingDeepLink = false,
                            deepLinkError = if (error is HttpException && error.code() == 404) {
                                DeepLinkError.SESSION_UNAVAILABLE
                            } else {
                                DeepLinkError.OPEN_FAILED
                            }
                        )
                    }
                }
        }
    }

    private fun isCurrentDeepLinkRoute(generation: Long, hostProfileId: String?, sessionId: String): Boolean {
        val current = _state.value
        return generation == deepLinkRouteGeneration &&
            hostProfileId == current.currentHostProfileId &&
            sessionId == current.pendingDeepLinkSessionId
    }

    private fun invalidateDeepLinkRoute(keepPending: Boolean) {
        deepLinkRouteGeneration += 1
        deepLinkJob?.cancel()
        deepLinkJob = null
        _state.update {
            it.copy(
                pendingDeepLinkSessionId = if (keepPending) it.pendingDeepLinkSessionId else null,
                isResolvingDeepLink = false
            )
        }
    }

    private fun resetRuntimeForHostSwitch() {
        val current = _state.value
        current.currentSessionId?.let { sessionId ->
            settingsManager.setDraftText(sessionId, current.inputText)
        }
        settingsManager.currentSessionId = null
        tunnelManager.disconnect()
        hostRuntimeJob.cancel()
        hostRuntimeJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        sseJob = null
        pollJob = null
        _state.update {
            it.copy(
                isConnected = false,
                isConnecting = true,
                serverVersion = null,
                sessions = emptyList(),
                loadedSessionLimit = MainViewModelTimings.sessionPageSize,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = false,
                expandedSessionIds = emptySet(),
                currentSessionId = null,
                sessionStatuses = emptyMap(),
                messages = emptyList(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                isLoadingMessages = false,
                inputText = "",
                imageAttachments = emptyList(),
                pendingPermissions = emptyList(),
                pendingQuestions = emptyList(),
                sessionTodos = emptyMap(),
                sendingSessionIds = emptySet(),
                sessionSendTimestamps = emptyMap(),
                pendingOptimisticMessageIds = emptySet(),
                agents = emptyList(),
                providers = null,
                filePathToShowInFiles = null,
                filePreviewOriginRoute = null,
                pendingNfcAction = null,
                connectionPhase = null
            )
        }
    }

    fun clearDeepLinkError() {
        _state.update { it.copy(deepLinkError = null) }
    }

    fun loadMessages(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessages(hostRuntimeScope, repository, _state, sessionId, resetLimit, settingsManager) {
            if (_state.value.pendingNfcAction != null) {
                consumePendingNfcAction()
            }
        }
    }

    /** Load messages with delay when triggered by SSE/send (server may need time to persist). */
    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(hostRuntimeScope, sessionId, _state, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = _state.value.currentSessionId ?: return
        launchLoadMoreMessages(hostRuntimeScope, repository, _state, sessionId)
    }

    private fun loadAgents() {
        hostRuntimeScope.launch {
            repository.getAgents()
                .onSuccess { agents ->
                    _state.update { it.copy(agents = agents) }
                }
                .onFailure { error ->
                    reportNonFatalIssue(TAG, "Failed to load agents", error)
                }
        }
    }

    private fun loadProviders() {
        launchLoadProviders(hostRuntimeScope, repository, _state, settingsManager) { message, error ->
            reportNonFatalIssue(TAG, message, error)
        }
    }

    fun createSession(title: String? = null) {
        launchCreateSession(hostRuntimeScope, repository, _state, title, ::selectSession)
    }

    fun forkSession(sessionId: String, messageId: String?) {
        launchForkSession(hostRuntimeScope, repository, _state, sessionId, messageId, ::selectSession)
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        launchUpdateSessionTitle(hostRuntimeScope, repository, _state, sessionId, title)
    }

    fun archiveSession(sessionId: String) {
        launchSetSessionArchived(hostRuntimeScope, repository, _state, sessionId, archived = true)
    }

    fun restoreSession(sessionId: String) {
        launchSetSessionArchived(hostRuntimeScope, repository, _state, sessionId, archived = false)
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(hostRuntimeScope, repository, _state, sessionId, ::selectSession)
    }

    fun sendMessage() {
        val sessionId = _state.value.currentSessionId ?: return
        if (_state.value.isRecording) return
        if (_state.value.sendingSessionIds.contains(sessionId)) return
        val text = _state.value.inputText.trim()
        val attachments = _state.value.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        // Insert the optimistic user row immediately so the send feels instant, then
        // clear the composer. The row carries the same deterministic msg_ id we send
        // to the server, so reconciliation in loadMessages is pure id membership.
        val messageId = makeServerId("msg")
        val optimistic = buildOptimisticMessage(
            sessionId = sessionId,
            text = text,
            attachments = attachments,
            messageId = messageId,
            parentMessageId = _state.value.messages.lastOrNull()?.info?.id
        )

        _state.update { state ->
            state.copy(
                messages = state.messages + optimistic,
                pendingOptimisticMessageIds = state.pendingOptimisticMessageIds + messageId,
                inputText = "",
                imageAttachments = emptyList(),
                sendingSessionIds = state.sendingSessionIds + sessionId,
                sessionSendTimestamps = state.sessionSendTimestamps + (sessionId to System.currentTimeMillis())
            )
        }

        val agent = _state.value.selectedAgentName
        val model = buildSelectedModel(_state.value)
        val currentSession = _state.value.currentSession

        fun dispatchSend() {
            launchSendMessage(
                scope = hostRuntimeScope,
                repository = repository,
                state = _state,
                sessionId = sessionId,
                text = text,
                attachments = attachments,
                agent = agent,
                model = model,
                messageId = messageId,
                onRefreshMessages = ::loadMessagesWithRetry,
                onRefreshSessions = ::loadSessions,
                onSuccess = {
                    settingsManager.setDraftText(sessionId, "")
                },
                onComplete = {
                    _state.update { state ->
                        state.copy(
                            sendingSessionIds = state.sendingSessionIds - sessionId,
                            sessionSendTimestamps = state.sessionSendTimestamps - sessionId
                        )
                    }
                }
            )
        }

        if (currentSession?.isArchived == true) {
            hostRuntimeScope.launch {
                repository.updateSessionArchived(sessionId, -1L)
                    .onSuccess { updated ->
                        _state.update { state ->
                            state.copy(sessions = state.sessions.map { session -> if (session.id == sessionId) updated else session })
                        }
                        dispatchSend()
                    }
                    .onFailure { error ->
                        // Restoring the archived session failed; roll back the optimistic row.
                        _state.update { state ->
                            state.copy(
                                messages = state.messages.filter { m -> m.info.id != messageId },
                                pendingOptimisticMessageIds = state.pendingOptimisticMessageIds - messageId,
                                inputText = text,
                                imageAttachments = attachments,
                                sendingSessionIds = state.sendingSessionIds - sessionId,
                                sessionSendTimestamps = state.sessionSendTimestamps - sessionId,
                                error = "Failed to restore session: ${errorMessageOrFallback(error, "unknown error")}"
                            )
                        }
                    }
            }
            return
        }

        dispatchSend()
    }

    fun abortSession() {
        val sessionId = _state.value.currentSessionId ?: return
        hostRuntimeScope.launch {
            repository.abortSession(sessionId)
                .onFailure { error ->
                    _state.update { it.copy(error = errorMessageOrFallback(error, "Failed to abort session")) }
                }
        }
    }

    fun setInputText(text: String) {
        _state.update { it.copy(inputText = text) }
        _state.value.currentSessionId?.let { settingsManager.setDraftText(it, text) }
    }

    fun handleNfcPrompt(prompt: String, autoSend: Boolean) {
        if (!settingsManager.nfcEnabled) return
        _state.update { it.copy(pendingNfcAction = AppState.NfcPendingAction(prompt, autoSend)) }
        createSession()
    }

    fun consumePendingNfcAction() {
        val action = _state.value.pendingNfcAction ?: return
        _state.update { it.copy(pendingNfcAction = null) }
        setInputText(action.prompt)
        if (action.autoSend) {
            sendMessage()
        }
    }

    fun getNfcEnabled(): Boolean = settingsManager.nfcEnabled
    fun saveNfcEnabled(value: Boolean) { settingsManager.nfcEnabled = value }
    fun getNfcPrompt(): String = settingsManager.nfcPrompt
    fun saveNfcPrompt(value: String) { settingsManager.nfcPrompt = value }
    fun getNfcAutoSend(): Boolean = settingsManager.nfcAutoSend
    fun saveNfcAutoSend(value: Boolean) { settingsManager.nfcAutoSend = value }

    fun addImageAttachments(attachments: List<ComposerImageAttachment>) {
        if (attachments.isEmpty()) return
        _state.update { state ->
            state.copy(imageAttachments = (state.imageAttachments + attachments).take(4))
        }
    }

    fun removeImageAttachment(id: String) {
        _state.update { state ->
            state.copy(imageAttachments = state.imageAttachments.filterNot { it.id == id })
        }
    }

    fun editFromMessage(messageId: String) {
        val sessionId = _state.value.currentSessionId ?: return
        val message = _state.value.messages.firstOrNull { it.info.id == messageId && it.info.isUser } ?: return
        val draft = message.parts.firstOrNull { it.isText }?.text?.trim().orEmpty()
        if (draft.isBlank()) return

        hostRuntimeScope.launch {
            repository.revertSession(sessionId, messageId)
                .onSuccess { updatedSession ->
                    _state.update { state ->
                        state.copy(
                            sessions = state.sessions.map { session -> if (session.id == sessionId) updatedSession else session },
                            inputText = draft,
                            imageAttachments = emptyList(),
                            error = null
                        )
                    }
                    settingsManager.setDraftText(sessionId, draft)
                    loadMessages(sessionId)
                    loadSessions()
                }
                .onFailure { error ->
                    _state.update { it.copy(error = "Failed to edit message: ${errorMessageOrFallback(error, "unknown error")}") }
                }
        }
    }

    fun selectAgent(agentName: String) {
        settingsManager.selectedAgentName = agentName
        _state.update { it.copy(selectedAgentName = agentName) }
        _state.value.currentSessionId?.let { settingsManager.setAgentForSession(it, agentName) }
    }

    fun toggleSessionExpanded(sessionId: String) {
        _state.update { state ->
            val next = if (state.expandedSessionIds.contains(sessionId)) {
                state.expandedSessionIds - sessionId
            } else {
                state.expandedSessionIds + sessionId
            }
            state.copy(expandedSessionIds = next)
        }
    }

    fun selectModel(index: Int) {
        val list = _state.value.modelShortlist
        if (list.isEmpty()) return
        val clamped = index.coerceIn(0, list.size - 1)
        val id = list[clamped].id
        settingsManager.selectedModelId = id
        _state.update { it.copy(selectedModelIndex = clamped, selectedModelId = id) }
        _state.value.currentSessionId?.let { settingsManager.setModelIdForSession(it, id) }
    }

    fun moveModelShortlist(from: Int, to: Int) {
        val current = _state.value.modelShortlist
        val next = moveShortlistItem(current, from, to)
        if (next == current) return
        settingsManager.modelShortlistJson = encodeShortlist(next)
        _state.update {
            it.copy(modelShortlist = next, selectedModelIndex = reanchorSelectedModelIndex(next, it.selectedModelId))
        }
    }

    fun removeModelShortlistItem(id: String) {
        val current = _state.value.modelShortlist
        val next = removeShortlistItem(current, id)
        if (next == current) return
        settingsManager.modelShortlistJson = encodeShortlist(next)
        // If the removed model was the current selection, fall back to the first
        // remaining model (or none) and persist that to both the global selection
        // and the current session, so a restart doesn't re-read the deleted ID.
        val wasSelected = _state.value.selectedModelId == id
        val selectedId = if (wasSelected) next.firstOrNull()?.id else _state.value.selectedModelId
        if (wasSelected) {
            settingsManager.selectedModelId = next.firstOrNull()?.id
            _state.value.currentSessionId?.let { sessionId ->
                val fallbackId = next.firstOrNull()?.id
                if (fallbackId != null) settingsManager.setModelIdForSession(sessionId, fallbackId)
                else settingsManager.removeModelIdForSession(sessionId)
            }
        }
        _state.update {
            it.copy(
                modelShortlist = next,
                selectedModelId = selectedId,
                selectedModelIndex = reanchorSelectedModelIndex(next, selectedId)
            )
        }
    }

    fun updateModelShortlistShortName(id: String, shortName: String) {
        val current = _state.value.modelShortlist
        val next = updateShortlistShortName(current, id, shortName)
        if (next == current) return
        settingsManager.modelShortlistJson = encodeShortlist(next)
        _state.update { it.copy(modelShortlist = next) }
    }

    fun addModelsToShortlist(items: List<ModelShortlistItem>) {
        var next = _state.value.modelShortlist
        var changed = false
        for (item in items) {
            val (added, c) = addModelToShortlist(next, item.providerId, item.modelId, item.displayName)
            next = added
            changed = changed || c
        }
        if (!changed) return
        settingsManager.modelShortlistJson = encodeShortlist(next)
        // Ensure the ID invariant: a non-empty shortlist always has a valid
        // selectedModelId. When the shortlist was empty (selectedModelId null)
        // and we just added items, anchor to the first item and persist.
        val currentId = _state.value.selectedModelId
        val resolvedId = if (currentId != null && next.any { it.id == currentId }) {
            currentId
        } else {
            next.firstOrNull()?.id
        }
        if (resolvedId != currentId) {
            settingsManager.selectedModelId = resolvedId
            _state.value.currentSessionId?.let { sessionId ->
                if (resolvedId != null) settingsManager.setModelIdForSession(sessionId, resolvedId)
                else settingsManager.removeModelIdForSession(sessionId)
            }
        }
        _state.update {
            it.copy(
                modelShortlist = next,
                selectedModelId = resolvedId,
                selectedModelIndex = reanchorSelectedModelIndex(next, resolvedId)
            )
        }
    }

    /** Asks the Settings screen to open the model shortlist when it appears. */
    fun requestModelShortlistFocus() {
        _state.update { it.copy(pendingModelShortlistFocus = true) }
    }

    fun clearModelShortlistFocus() {
        _state.update { it.copy(pendingModelShortlistFocus = false) }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsManager.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setLanguageMode(mode: LanguageMode) {
        settingsManager.languageMode = mode
        _state.update { it.copy(languageMode = mode) }
    }

    fun respondPermission(sessionId: String, permissionId: String, response: PermissionResponse) {
        hostRuntimeScope.launch {
            repository.respondPermission(sessionId, permissionId, response)
                .onSuccess {
                    _state.update { it.copy(
                        pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }
                    )}
                }
                .onFailure { error ->
                    _state.update { it.copy(error = errorMessageOrFallback(error, "Failed to respond to permission")) }
                }
        }
    }

    fun loadPendingPermissions() {
        hostRuntimeScope.launch {
            repository.getPendingPermissions()
                .onSuccess { permissions ->
                    _state.update { it.copy(pendingPermissions = permissions) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load permissions: ${error.message}")
                }
        }
    }

    fun loadPendingQuestions() {
        hostRuntimeScope.launch {
            repository.getPendingQuestions()
                .onSuccess { questions ->
                    _state.update { it.copy(pendingQuestions = questions) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load questions: ${error.message}")
                }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>, onError: () -> Unit = {}) {
        hostRuntimeScope.launch {
            repository.replyQuestion(requestId, answers)
                .onSuccess {
                    _state.update { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reply question: ${error.message}")
                    onError()
                }
        }
    }

    fun rejectQuestion(requestId: String) {
        hostRuntimeScope.launch {
            repository.rejectQuestion(requestId)
                .onSuccess {
                    _state.update { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reject question: ${error.message}")
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun showFileInFiles(path: String, originRoute: String? = null) {
        _state.update { it.copy(filePathToShowInFiles = path, filePreviewOriginRoute = originRoute) }
    }

    fun clearFileToShow() {
        _state.update { it.copy(filePathToShowInFiles = null, filePreviewOriginRoute = null) }
    }

    /** Poll loadMessages every 2s when session is busy, as SSE fallback. */
    private fun startBusyPolling() {
        pollJob?.cancel()
        pollJob = launchBusyPolling(hostRuntimeScope, _state, ::loadMessages)
    }

    private fun startSSE() {
        sseJob?.cancel()
        sseJob = launchSseCollection(hostRuntimeScope, repository, _state, ::handleSSEEvent)
    }

    private fun handleSSEEvent(event: SSEEvent) {
        handleIncomingSseEvent(
            state = _state,
            event = event,
            onRefreshMessages = ::loadMessagesWithRetry,
            onRefreshSessions = ::loadSessions,
            onLoadPendingPermissions = ::loadPendingPermissions,
            onNonFatalIssue = { message -> reportNonFatalIssue(TAG, message) }
        )
    }

    override fun onCleared() {
        sseJob?.cancel()
        pollJob?.cancel()
        speechHeartbeatJob?.cancel()
        speechTranscriptionJob?.cancel()
        speechCleanupJob?.cancel()
        speechPcmSender?.cancel()
        speechTypewriter?.cancel()
        microphone.discard()
        runBlocking {
            runCatching { speechSessionOwner?.discard() }
        }
        speechSessionOwner = null
        speechPcmSender = null
        val ownedFile = activeSpeechFileOwner?.let {
            it.claimForCleanup() ?: it.claimForAttempt() ?: it.current()
        } ?: activeSpeechFile
        deleteSpeechFileUnlessPreserved(ownedFile)
        activeSpeechFile = null
        activeSpeechFileOwner = null
        preservedSpeechRecording?.let(::discardSpeechRecording)
        preservedSpeechRecording = null
        super.onCleared()
    }

    private companion object {
        private const val TAG = "MainViewModel"

        /** Mirrors VoiceFlowKit's internal heartbeat cadence (12s ping). */
        private const val SPEECH_HEARTBEAT_INTERVAL_SECONDS = 12L
    }
}
