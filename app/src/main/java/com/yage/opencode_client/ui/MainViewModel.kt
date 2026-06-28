package com.yage.opencode_client.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ssh.SSHKeyManager
import com.yage.opencode_client.ssh.TunnelManager
import com.yage.opencode_client.ssh.TunnelResult
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.LanguageMode
import com.yage.opencode_client.util.ThemeMode
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import com.yage.voiceflowkit.VoiceFlowMicrophone
import com.yage.voiceflowkit.VoiceFlowPreservedAudio
import com.yage.voiceflowkit.VoiceFlowSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val terminology: String
)

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
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null,
    val connectionPhase: String? = null
) {
    data class ModelOption(val displayName: String, val providerId: String, val modelId: String) {
        val shortName: String
            get() = when {
                displayName == "DeepSeek V4 Flash" -> "DS-Flash"
                displayName == "DeepSeek Local" -> "DS-L"
                displayName == "DeepSeek V4 Pro" -> "DS-Pro"
                displayName == "Ollama GLM 5.2" -> "OGLM-5.2"
                "Haiku" in displayName -> "Haiku"
                "Gemini" in displayName -> "Gemini"
                "GPT" in displayName -> "GPT"
                "Grok" in displayName -> "Grok"
                else -> displayName.split(" ").firstOrNull() ?: displayName
            }
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
        val selectedAgentName: String = "build",
        val availableModels: List<ModelOption> = ModelPresets.list,
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
            selectedAgentName = selectedAgentName,
            availableModels = availableModels,
            contextUsage = contextUsage,
            agents = agents,
            providers = providers,
            isRecording = isRecording
        )

    val currentSession: Session?
        get() = sessions.find { it.id == currentSessionId }

    val currentSessionStatus: SessionStatus?
        get() = currentSessionId?.let { sessionStatuses[it] }

    val isCurrentSessionBusy: Boolean
        get() = currentSessionStatus?.isBusy == true

    val canLoadMoreSessions: Boolean
        get() = hasMoreSessions && !isLoadingMoreSessions

    val visibleAgents: List<AgentInfo>
        get() = agents.filter { it.isVisible }

    /** Curated model list (filtered like iOS), not the full API response. */
    val availableModels: List<ModelOption>
        get() = ModelPresets.list

    private val providerModelsIndex: Map<String, ProviderModel>
        get() = providers?.providers?.flatMap { provider ->
            provider.models.flatMap { (modelKey, model) ->
                listOfNotNull(
                    "${provider.id}/$modelKey" to model,
                    model.id.takeIf { it.isNotEmpty() }?.let { "${provider.id}/$it" to model },
                    model.resolvedProviderId?.let { resolvedProvider ->
                        model.id.takeIf { it.isNotEmpty() }?.let { modelId -> "$resolvedProvider/$modelId" to model }
                    }
                )
            }
        }?.toMap() ?: emptyMap()

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
    private val sshKeyManager: SSHKeyManager
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var sseJob: Job? = null
    private var pollJob: Job? = null
    private var speechHeartbeatJob: Job? = null
    private var speechAudioLevelJob: Job? = null
    private var speechSession: VoiceFlowSession? = null
    private var speechExistingInput: String = ""
    private var preservedSpeechAudio: VoiceFlowPreservedAudio? = null
    private var preservedSpeechExistingInput: String = ""
    private var lastHealthCheckTime = 0L

    init {
        loadSettings()
    }

    private fun loadSettings() {
        applySavedSettings(repository, settingsManager, hostProfileStore, _state)
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
        viewModelScope.launch {
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
        hostProfileStore.delete(profileId)
        val current = hostProfileStore.currentProfile()
        configureRepositoryForProfile(current, startTunnel = false)
        refreshHostProfileState()
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
            viewModelScope.launch { configureRepositoryForProfileAsync(profile) }
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
        terminology = settingsManager.aiBuilderTerminology
    )

    fun saveAIBuilderSettings(settings: AIBuilderSettings) {
        settingsManager.aiBuilderBaseURL = settings.baseURL
        settingsManager.aiBuilderToken = settings.token
        settingsManager.aiBuilderCustomPrompt = settings.customPrompt
        settingsManager.aiBuilderTerminology = settings.terminology
        _state.update { it.copy(aiBuilderConnectionOK = false, aiBuilderConnectionError = null) }
        settingsManager.aiBuilderLastOKSignature = null
    }

    fun testAIBuilderConnection() {
        launchAIBuilderConnectionTest(viewModelScope, settingsManager, voiceFlowClient, _state)
    }

    fun toggleRecording() {
        val currentState = _state.value
        val speechConfig = currentSpeechInputConfig(settingsManager)
        Log.d(
            TAG,
            "toggleRecording clicked: recording=${currentState.isRecording}, transcribing=${currentState.isTranscribing}, aiBuilderOK=${currentState.aiBuilderConnectionOK}, tokenSet=${speechConfig.token.isNotEmpty()}"
        )
        if (currentState.isTranscribing) {
            Log.w(TAG, "Ignoring toggle while transcription is in progress")
            _state.update {
                it.copy(speechError = "Still transcribing previous audio, please wait.")
            }
            return
        }
        if (currentState.isRecording) {
            val session = speechSession
            // Stop PCM capture first so no further chunks race the commit.
            viewModelScope.launch { microphone.stop() }
            stopSpeechAudioLevelConsumer()
            speechHeartbeatJob?.cancel()
            speechHeartbeatJob = null
            _state.update { it.copy(isRecording = false, isTranscribing = true) }
            if (session == null) {
                Log.e(TAG, "Realtime speech session is missing on stop")
                _state.update { it.copy(isTranscribing = false, speechError = "Recording failed: realtime session missing") }
                return
            }
            launchRealtimeSpeechStop(
                scope = viewModelScope,
                state = _state,
                session = session,
                existingInput = speechExistingInput,
                tag = TAG,
                shouldApply = { speechSession === session },
                terminateSession = ::terminateSpeechSession,
            ) {
                speechSession = null
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
            speechExistingInput = currentState.inputText
            viewModelScope.launch {
                try {
                    // Refresh the library config with the latest endpoint/token/prompt/
                    // terms before opening the session, then start the realtime session.
                    voiceFlowClient.updateConfig(
                        VoiceFlowConfig(
                            endpoint = speechConfig.baseURL.ifEmpty { VoiceFlowConfig.DEFAULT_ENDPOINT },
                            tokenProvider = { speechConfig.token },
                            prompt = speechConfig.prompt.ifEmpty { null },
                            terms = speechConfig.terms,
                        )
                    )
                    clearPreservedSpeechAudio()
                    val session = voiceFlowClient.startSession()
                    speechSession = session
                    startSpeechAudioLevelConsumer()

                    // Stream PCM16/24kHz/mono chunks from the mic into the session. The
                    // library owns cache replay + WS recovery internally.
                    microphone.start { chunk ->
                        viewModelScope.launch { session.sendAudioChunk(chunk) }
                    }

                    speechHeartbeatJob?.cancel()
                    speechHeartbeatJob = viewModelScope.launch {
                        while (true) {
                            delay(SPEECH_HEARTBEAT_INTERVAL_SECONDS * 1000L)
                            session.ping()
                        }
                    }
                    Log.d(TAG, "Realtime recording started")
                    _state.update { it.copy(isRecording = true) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start recording", e)
                    runCatching { microphone.stop() }
                    stopSpeechAudioLevelConsumer()
                    speechSession?.let { session ->
                        runCatching { terminateSpeechSession(session) }
                    }
                    speechSession = null
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

    private suspend fun terminateSpeechSession(session: VoiceFlowSession) {
        try {
            session.abortPreservingAudio()?.let { voiceFlowClient.discardPreservedAudio(it) }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to terminate speech session", error)
        }
    }

    fun stopSpeechForBackground() {
        val session = speechSession
        speechHeartbeatJob?.cancel()
        speechHeartbeatJob = null
        stopSpeechAudioLevelConsumer()
        speechSession = null
        _state.update { it.copy(isRecording = false, isTranscribing = false, speechAudioLevel = 0f) }
        viewModelScope.launch {
            runCatching { microphone.stop() }
            if (session != null) {
                terminateSpeechSession(session)
            }
        }
    }

    fun clearSpeechError() {
        _state.update { it.copy(speechError = null) }
    }

    fun abortSpeechRecognition() {
        val session = speechSession ?: return
        val prefix = speechExistingInput
        speechHeartbeatJob?.cancel()
        speechHeartbeatJob = null
        stopSpeechAudioLevelConsumer()
        speechSession = null
        _state.update { it.copy(isRecording = false, isTranscribing = false, speechAudioLevel = 0f) }
        viewModelScope.launch {
            runCatching { microphone.stop() }
            try {
                val preserved = session.abortPreservingAudio()
                clearPreservedSpeechAudio()
                if (preserved != null) {
                    preservedSpeechAudio = preserved
                    preservedSpeechExistingInput = prefix
                    _state.update { it.copy(hasPreservedSpeechAudio = true) }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to abort speech recognition", error)
                _state.update { it.copy(speechError = errorMessageOrFallback(error, "Failed to abort speech recognition")) }
            }
        }
    }

    fun retryPreservedSpeechAudio() {
        val preserved = preservedSpeechAudio ?: return
        val prefix = preservedSpeechExistingInput
        _state.update { it.copy(isRetryingSpeech = true) }
        viewModelScope.launch {
            try {
                val result = voiceFlowClient.transcribe(preserved) { partial ->
                    _state.update { it.copy(inputText = mergedSpeechInput(prefix, partial)) }
                }
                _state.update {
                    it.copy(
                        inputText = mergedSpeechInput(prefix, result.text),
                        isRetryingSpeech = false,
                    )
                }
                clearPreservedSpeechAudio()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to retry preserved speech audio", error)
                _state.update {
                    it.copy(
                        isRetryingSpeech = false,
                        speechError = errorMessageOrFallback(error, "Transcription failed"),
                    )
                }
            }
        }
    }

    private fun clearPreservedSpeechAudio() {
        preservedSpeechAudio?.let { voiceFlowClient.discardPreservedAudio(it) }
        preservedSpeechAudio = null
        preservedSpeechExistingInput = ""
        _state.update { it.copy(hasPreservedSpeechAudio = false) }
    }

    fun discardPreservedSpeechAudio() {
        clearPreservedSpeechAudio()
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
        viewModelScope.launch {
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
        loadPendingQuestions()
    }

    fun loadSessions() {
        launchLoadSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession,
            onLoadSessionStatus = ::loadSessionStatus,
            onLoadMessages = { sessionId -> loadMessages(sessionId) }
        )
    }

    fun loadMoreSessions() {
        launchLoadMoreSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession
        )
    }

    private fun loadSessionStatus() {
        launchLoadSessionStatus(viewModelScope, repository, _state)
    }

    fun selectSession(sessionId: String) {
        selectSessionState(_state, settingsManager, sessionId)
        loadMessages(sessionId)
        loadSessionStatus()
    }

    fun loadMessages(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessages(viewModelScope, repository, _state, sessionId, resetLimit, settingsManager)
    }

    /** Load messages with delay when triggered by SSE/send (server may need time to persist). */
    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(viewModelScope, sessionId, _state, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = _state.value.currentSessionId ?: return
        launchLoadMoreMessages(viewModelScope, repository, _state, sessionId)
    }

    private fun loadAgents() {
        viewModelScope.launch {
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
        launchLoadProviders(viewModelScope, repository, _state) { message, error ->
            reportNonFatalIssue(TAG, message, error)
        }
    }

    fun createSession(title: String? = null) {
        launchCreateSession(viewModelScope, repository, _state, title, ::selectSession)
    }

    fun forkSession(sessionId: String, messageId: String?) {
        launchForkSession(viewModelScope, repository, _state, sessionId, messageId, ::selectSession)
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        launchUpdateSessionTitle(viewModelScope, repository, _state, sessionId, title)
    }

    fun archiveSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, sessionId, archived = true)
    }

    fun restoreSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, sessionId, archived = false)
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(viewModelScope, repository, _state, sessionId, ::selectSession)
    }

    fun sendMessage() {
        val sessionId = _state.value.currentSessionId ?: return
        if (_state.value.sendingSessionIds.contains(sessionId)) return
        val text = _state.value.inputText.trim()
        val attachments = _state.value.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        _state.update { state ->
            state.copy(
                sendingSessionIds = state.sendingSessionIds + sessionId,
                sessionSendTimestamps = state.sessionSendTimestamps + (sessionId to System.currentTimeMillis())
            )
        }

        val agent = _state.value.selectedAgentName
        val model = buildSelectedModel(_state.value)
        val currentSession = _state.value.currentSession

        fun dispatchSend() {
            launchSendMessage(
                scope = viewModelScope,
                repository = repository,
                state = _state,
                sessionId = sessionId,
                text = text,
                attachments = attachments,
                agent = agent,
                model = model,
                onRefreshMessages = ::loadMessagesWithRetry,
                onRefreshSessions = ::loadSessions,
                onSuccess = {
                    settingsManager.setDraftText(sessionId, "")
                    _state.update { it.copy(imageAttachments = emptyList()) }
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
            viewModelScope.launch {
                repository.updateSessionArchived(sessionId, -1L)
                    .onSuccess { updated ->
                        _state.update { state ->
                            state.copy(sessions = state.sessions.map { session -> if (session.id == sessionId) updated else session })
                        }
                        dispatchSend()
                    }
                    .onFailure { error ->
                        _state.update { it.copy(error = "Failed to restore session: ${errorMessageOrFallback(error, "unknown error")}") }
                    }
            }
            return
        }

        dispatchSend()
    }

    fun abortSession() {
        val sessionId = _state.value.currentSessionId ?: return
        viewModelScope.launch {
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

        viewModelScope.launch {
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
        val clamped = index.coerceIn(0, ModelPresets.list.size - 1)
        settingsManager.selectedModelIndex = clamped
        _state.update { it.copy(selectedModelIndex = clamped) }
        _state.value.currentSessionId?.let { settingsManager.setModelForSession(it, clamped) }
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        pollJob = launchBusyPolling(viewModelScope, _state, ::loadMessages)
    }

    private fun startSSE() {
        sseJob?.cancel()
        sseJob = launchSseCollection(viewModelScope, repository, _state, ::handleSSEEvent)
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
        microphone.discard()
        runBlocking { speechSession?.let { terminateSpeechSession(it) } }
        speechSession = null
        super.onCleared()
    }

    private companion object {
        private const val TAG = "MainViewModel"

        /** Mirrors VoiceFlowKit's internal heartbeat cadence (12s ping). */
        private const val SPEECH_HEARTBEAT_INTERVAL_SECONDS = 12L
    }
}
