package com.yage.opencode_client.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yage.opencode_client.data.audio.AudioRecorderManager
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
) {
    data class ModelOption(val displayName: String, val providerId: String, val modelId: String) {
        val shortName: String
            get() = when {
                "Opus" in displayName -> "Opus"
                "Sonnet" in displayName -> "Sonnet"
                "Haiku" in displayName -> "Haiku"
                "Gemini" in displayName -> "Gemini"
                "GPT" in displayName -> "GPT"
                "Grok" in displayName -> "Grok"
                else -> displayName.split(" ").firstOrNull() ?: displayName
            }
    }

    data class ContextUsage(val percentage: Float, val totalTokens: Int, val contextLimit: Int)

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
            provider.models.map { (_, model) ->
                "${provider.id}/${model.id}" to model
            }
        }?.toMap() ?: emptyMap()

    val contextUsage: ContextUsage?
        get() {
            val lastAssistant = messages.lastOrNull { it.info.isAssistant && it.info.tokens != null }
                ?: return null
            val tokens = lastAssistant.info.tokens ?: return null
            val total = tokens.total ?: return null
            val model = lastAssistant.info.resolvedModel ?: return null
            val key = "${model.providerId}/${model.modelId}"
            val limit = providerModelsIndex[key]?.limit?.context ?: return null
            if (limit <= 0) return null
            return ContextUsage(
                percentage = (total.toFloat() / limit.toFloat()).coerceIn(0f, 1f),
                totalTokens = total,
                contextLimit = limit
            )
        }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val audioRecorderManager: AudioRecorderManager
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var sseJob: Job? = null
    private var pollJob: Job? = null

    init {
        loadSettings()
    }

    private fun loadSettings() {
        applySavedSettings(repository, settingsManager, _state)
    }

    fun configureServer(url: String, username: String? = null, password: String? = null) {
        settingsManager.serverUrl = url
        settingsManager.username = username
        settingsManager.password = password
        repository.configure(url, username, password)
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
        launchAIBuilderConnectionTest(viewModelScope, settingsManager, _state)
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
            val file = audioRecorderManager.stop()
            _state.update { it.copy(isRecording = false, isTranscribing = true) }
            if (file == null) {
                Log.e(TAG, "Recording stop returned null file")
                _state.update { it.copy(isTranscribing = false, speechError = "Recording failed: no file") }
                return
            }
            launchSpeechTranscription(
                scope = viewModelScope,
                state = _state,
                audioRecorderManager = audioRecorderManager,
                config = speechConfig,
                recordingFile = file,
                existingInput = currentState.inputText,
                tag = TAG
            )
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
            try {
                audioRecorderManager.start()
                Log.d(TAG, "Recording started")
                _state.update { it.copy(isRecording = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _state.update { it.copy(speechError = "Failed to start recording: ${errorMessageOrFallback(e, "unknown error")}") }
            }
        }
    }

    fun clearSpeechError() {
        _state.update { it.copy(speechError = null) }
    }

    fun setSpeechError(message: String) {
        _state.update { it.copy(speechError = message) }
    }

    fun testConnection() {
        launchConnectionTest(viewModelScope, repository, _state) {
            loadInitialData()
            startSSE()
            startBusyPolling()
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

    fun updateSessionTitle(sessionId: String, title: String) {
        launchUpdateSessionTitle(viewModelScope, repository, _state, sessionId, title)
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(viewModelScope, repository, _state, sessionId, ::selectSession)
    }

    fun sendMessage() {
        val sessionId = _state.value.currentSessionId ?: return
        val text = _state.value.inputText.trim()
        if (text.isEmpty()) return

        val agent = _state.value.selectedAgentName
        val model = buildSelectedModel(_state.value)

        launchSendMessage(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            sessionId = sessionId,
            text = text,
            agent = agent,
            model = model,
            onRefreshMessages = ::loadMessagesWithRetry,
            onSuccess = { settingsManager.setDraftText(sessionId, "") }
        )
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

    fun showFileInFiles(path: String) {
        _state.update { it.copy(filePathToShowInFiles = path) }
    }

    fun clearFileToShow() {
        _state.update { it.copy(filePathToShowInFiles = null) }
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
            onLoadPendingPermissions = ::loadPendingPermissions,
            onNonFatalIssue = { message -> reportNonFatalIssue(TAG, message) }
        )
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        pollJob?.cancel()
    }

    private companion object {
        private const val TAG = "MainViewModel"
    }
}
