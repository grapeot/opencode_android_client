package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.ImageAttachment
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun launchLoadSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onSelectSession: (String) -> Unit,
    onLoadSessionStatus: () -> Unit,
    onLoadMessages: (String) -> Unit
) {
    scope.launch {
        val limit = MainViewModelTimings.sessionPageSize
        state.update {
            it.copy(
                loadedSessionLimit = limit,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = true
            )
        }
        repository.getSessions(limit)
            .onSuccess { sessions ->
                state.update {
                    it.copy(
                        sessions = sessions,
                        hasMoreSessions = sessions.size >= limit,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val hasCurrentSession = currentId != null && sessions.any { it.id == currentId }
                when {
                    currentId == null && sessions.isNotEmpty() -> onSelectSession(sessions.first().id)
                    hasCurrentSession -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentId!!)
                    }
                    sessions.isNotEmpty() -> {
                        onSelectSession(sessions.first().id)
                    }
                    else -> {
                        state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                    }
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false,
                        error = "Failed to load sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadMoreSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onSelectSession: (String) -> Unit
) {
    var nextLimit = 0
    var shouldLaunch = false
    state.update { current ->
        if (!current.hasMoreSessions || current.isLoadingMoreSessions) {
            current
        } else {
            nextLimit = nextSessionFetchLimit(current.loadedSessionLimit)
            shouldLaunch = true
            current.copy(isLoadingMoreSessions = true)
        }
    }
    if (!shouldLaunch) return
    scope.launch {
        repository.getSessions(nextLimit)
            .onSuccess { sessions ->
                if (state.value.loadedSessionLimit > nextLimit) {
                    state.update { it.copy(isLoadingMoreSessions = false) }
                    return@onSuccess
                }
                state.update {
                    it.copy(
                        sessions = sessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = sessions.size >= nextLimit,
                        isLoadingMoreSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val hasCurrentSession = currentId != null && sessions.any { it.id == currentId }
                when {
                    currentId == null && sessions.isNotEmpty() -> onSelectSession(sessions.first().id)
                    hasCurrentSession -> Unit
                    sessions.isNotEmpty() -> onSelectSession(sessions.first().id)
                    else -> state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isLoadingMoreSessions = false,
                        error = "Failed to load more sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>
) {
    scope.launch {
        repository.getSessionStatus()
            .onSuccess { statuses ->
                state.update { it.copy(sessionStatuses = statuses) }
            }
            .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
            }
    }
}

internal fun selectSessionState(
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    sessionId: String
) {
    val oldSessionId = state.value.currentSessionId
    val currentInputText = state.value.inputText
    if (oldSessionId != null) {
        settingsManager.setDraftText(oldSessionId, currentInputText)
    }

    settingsManager.currentSessionId = sessionId
    val restoredDraft = settingsManager.getDraftText(sessionId)
    state.update {
        it.copy(
            currentSessionId = sessionId,
            messages = emptyList(),
            messageLimit = 30,
            inputText = restoredDraft
        )
    }
}

internal fun launchLoadMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    resetLimit: Boolean = true,
    settingsManager: SettingsManager? = null
) {
    scope.launch {
        state.update { it.copy(isLoadingMessages = true) }
        val limit = if (resetLimit) 30 else state.value.messageLimit
        repository.getMessages(sessionId, limit)
            .onSuccess { messages ->
                if (sessionId == state.value.currentSessionId) {
                    val lastAssistant = messages.lastOrNull { it.info.isAssistant }
                    val inferredModelIndex = lastAssistant?.info?.resolvedModel?.let { model ->
                        state.value.availableModels.indexOfFirst {
                            it.providerId == model.providerId && it.modelId == model.modelId
                        }.takeIf { it >= 0 }
                    }
                    val inferredAgentName = lastAssistant?.info?.agent
                    val modelIndex = settingsManager?.getModelForSession(sessionId) ?: inferredModelIndex
                    val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName
                    state.update {
                        it.copy(
                            messages = messages,
                            messageLimit = limit,
                            isLoadingMessages = false,
                            selectedModelIndex = modelIndex ?: it.selectedModelIndex,
                            selectedAgentName = agentName ?: it.selectedAgentName
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure { error ->
                if (sessionId == state.value.currentSessionId) {
                    state.update {
                        it.copy(
                            isLoadingMessages = false,
                            error = "Failed to load messages: ${errorMessageOrFallback(error, "unknown error")}"
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
    }
}

internal fun launchLoadMessagesWithRetry(
    scope: CoroutineScope,
    sessionId: String,
    state: MutableStateFlow<AppState>,
    resetLimit: Boolean = true,
    onLoadMessages: (String, Boolean) -> Unit
) {
    scope.launch {
        delay(MainViewModelTimings.messageRetryDelayMs)
        if (sessionId == state.value.currentSessionId) {
            onLoadMessages(sessionId, resetLimit)
        }
    }
}

internal fun launchLoadMoreMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String
) {
    if (state.value.isLoadingMessages) return
    val newLimit = state.value.messageLimit + 30
    scope.launch {
        state.update { it.copy(isLoadingMessages = true) }
        repository.getMessages(sessionId, newLimit)
            .onSuccess { messages ->
                if (sessionId == state.value.currentSessionId) {
                    state.update {
                        it.copy(
                            messages = messages,
                            messageLimit = newLimit,
                            isLoadingMessages = false
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure {
                if (sessionId == state.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Failed to load more messages")
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
    }
}

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onNonFatalError: (String, Throwable?) -> Unit
) {
    scope.launch {
        repository.getProviders()
            .onSuccess { providers ->
                state.update { it.copy(providers = providers) }
            }
            .onFailure { error ->
                onNonFatalError("Failed to load providers", error)
            }
    }
}

internal fun launchCreateSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    title: String?,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.createSession(title)
            .onSuccess { session ->
                state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to create session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchForkSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    messageId: String?,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to fork session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchUpdateSessionTitle(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    title: String
) {
    scope.launch {
        repository.updateSession(sessionId, title)
            .onSuccess { updated ->
                state.update {
                    it.copy(sessions = it.sessions.map { session -> if (session.id == sessionId) updated else session })
                }
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to update session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchDeleteSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.deleteSession(sessionId)
            .onSuccess {
                val newSessions = state.value.sessions.filter { it.id != sessionId }
                state.update { it.copy(sessions = newSessions) }
                if (state.value.currentSessionId == sessionId) {
                    val newCurrent = newSessions.firstOrNull()?.id
                    if (newCurrent != null) {
                        onSelectSession(newCurrent)
                    } else {
                        state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                    }
                }
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to delete session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun buildSelectedModel(state: AppState): Message.ModelInfo? {
    val selectedModel = state.availableModels.getOrNull(state.selectedModelIndex)
    return selectedModel?.let {
        Message.ModelInfo(it.providerId, it.modelId)
    } ?: state.providers?.default?.let {
        Message.ModelInfo(it.providerId, it.modelId)
    }
}

internal fun launchSendMessage(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    text: String,
    images: List<ImageAttachment> = emptyList(),
    agent: String,
    model: Message.ModelInfo?,
    onRefreshMessages: (String, Boolean) -> Unit,
    onSuccess: (() -> Unit)? = null
) {
    scope.launch {
        repository.sendMessage(sessionId, text, images, agent, model)
            .onSuccess {
                state.update {
                    it.copy(
                        inputText = "",
                        error = null,
                        sessionStatuses = it.sessionStatuses + (sessionId to com.yage.opencode_client.data.model.SessionStatus(type = "busy"))
                    )
                }
                onSuccess?.invoke()
                onRefreshMessages(sessionId, true)
                launch {
                    delay(MainViewModelTimings.messageRefreshDelayMs)
                    onRefreshMessages(sessionId, false)
                }
            }
            .onFailure { error ->
                state.update { it.copy(error = errorMessageOrFallback(error, "Failed to send message")) }
            }
    }
}
