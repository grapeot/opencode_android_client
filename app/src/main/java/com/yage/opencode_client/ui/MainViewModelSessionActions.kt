package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.ProviderModel
import com.yage.opencode_client.data.model.ProvidersResponse
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        it.sessions,
                        it.currentSessionId
                    )
                    it.copy(
                        sessions = mergedSessions,
                        hasMoreSessions = sessions.size >= limit,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                val hasCurrentSession = currentId != null && refreshedSessions.any { it.id == currentId }
                when {
                    currentId == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    hasCurrentSession -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentId!!)
                    }
                    refreshedSessions.isNotEmpty() -> {
                        onSelectSession(refreshedSessions.first().id)
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
                    val mergedSessions = mergeRefreshedSessionsPreservingLocalActivity(
                        sessions,
                        it.sessions,
                        it.currentSessionId
                    )
                    it.copy(
                        sessions = mergedSessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = sessions.size >= nextLimit,
                        isLoadingMoreSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val refreshedSessions = state.value.sessions
                val hasCurrentSession = currentId != null && refreshedSessions.any { it.id == currentId }
                when {
                    currentId == null && refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
                    hasCurrentSession -> Unit
                    refreshedSessions.isNotEmpty() -> onSelectSession(refreshedSessions.first().id)
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
            pendingOptimisticMessageIds = emptySet(),
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            messageLimit = 30,
            inputText = restoredDraft
        )
    }
}

internal fun mergePendingOptimisticMessages(
    serverMessages: List<MessageWithParts>,
    currentState: AppState
): Pair<List<MessageWithParts>, Set<String>> {
    val loadedIds = serverMessages.map { it.info.id }.toSet()
    val pendingRows = currentState.messages.filter { m ->
        currentState.pendingOptimisticMessageIds.contains(m.info.id) && m.info.id !in loadedIds
    }
    val merged = serverMessages + pendingRows
    val prunedPending = currentState.pendingOptimisticMessageIds - loadedIds
    return merged to prunedPending
}

internal fun launchLoadMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    resetLimit: Boolean = true,
    settingsManager: SettingsManager? = null,
    onMessagesLoaded: (() -> Unit)? = null
) {
    scope.launch {
        state.update { it.copy(isLoadingMessages = true) }
        val limit = if (resetLimit) 30 else state.value.messageLimit
        repository.getMessages(sessionId, limit)
            .onSuccess { messages ->
                if (sessionId == state.value.currentSessionId) {
                    val lastAssistant = messages.lastOrNull { it.info.isAssistant }
                    val inferredModel = lastAssistant?.info?.resolvedModel
                    val inferredAgentName = lastAssistant?.info?.agent
                    val savedModelId = settingsManager?.getModelIdForSession(sessionId)
                    val inferredModelId = inferredModel?.let { "${it.providerId}/${it.modelId}" }
                    val sessionModelId = savedModelId ?: inferredModelId
                    val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName

                    // Ensure the session's effective model (saved, or inferred from
                    // history) is present in the shortlist. Only auto-add when
                    // the provider is known (present in the loaded providers
                    // list) to avoid resurrecting stale/retired models.
                    var nextShortlist = state.value.modelShortlist
                    if (sessionModelId != null) {
                        val slash = sessionModelId.indexOf('/')
                        if (slash > 0) {
                            val providerId = sessionModelId.substring(0, slash)
                            val modelId = sessionModelId.substring(slash + 1)
                            val alreadyInShortlist = nextShortlist.any { it.id == sessionModelId }
                            val providerKnown = state.value.providers?.providers
                                ?.any { it.id == providerId } == true
                            if (!alreadyInShortlist && providerKnown) {
                                val displayName = buildProviderModelsIndex(state.value.providers)[sessionModelId]?.name
                                    ?: modelId
                                val (added, changed) = addModelToShortlist(
                                    nextShortlist, providerId, modelId, displayName
                                )
                                if (changed) {
                                    nextShortlist = added
                                    settingsManager?.modelShortlistJson = encodeShortlist(nextShortlist)
                                }
                            }
                        }
                    }
                    val effectiveModelId = sessionModelId ?: state.value.selectedModelId
                    val modelIndex = reanchorSelectedModelIndex(nextShortlist, effectiveModelId)

                    state.update {
                        val (mergedMessages, prunedPending) = mergePendingOptimisticMessages(messages, it)
                        it.copy(
                            messages = mergedMessages,
                            pendingOptimisticMessageIds = prunedPending,
                            messageLimit = limit,
                            isLoadingMessages = false,
                            modelShortlist = nextShortlist,
                            selectedModelId = effectiveModelId,
                            selectedModelIndex = modelIndex,
                            selectedAgentName = agentName ?: it.selectedAgentName
                        )
                    }
                    onMessagesLoaded?.invoke()
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

        // Best-effort: load session todos after messages (matches iOS behavior).
        // Fails silently in test mocks where the endpoint isn't set up.
        try {
            repository.getSessionTodos(sessionId)
                .onSuccess { todos ->
                    state.update { it.copy(sessionTodos = it.sessionTodos + (sessionId to todos)) }
                }
        } catch (_: Exception) {}
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
                        val (mergedMessages, prunedPending) = mergePendingOptimisticMessages(messages, it)
                        it.copy(
                            messages = mergedMessages,
                            pendingOptimisticMessageIds = prunedPending,
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
    settingsManager: SettingsManager?,
    onNonFatalError: (String, Throwable?) -> Unit
) {
    scope.launch {
        val providersResult = repository.getProviders()
        providersResult
            .onSuccess { providers -> state.update { it.copy(providers = providers) } }
            .onFailure { error -> onNonFatalError("Failed to load providers", error) }

        // Build the model catalog from /provider (connected-scoped), falling back
        // to config/providers (unscoped) when the registry is unavailable (D4).
        // When both endpoints fail, keep the previously loaded catalog (D4).
        val resolvedCatalog = resolveModelCatalog(repository, providersResult)
        if (resolvedCatalog != null) {
            // Refresh shortlist display names from the catalog; short names are kept (D6).
            val refreshed = refreshShortlistDisplayNames(state.value.modelShortlist, resolvedCatalog.models)
            if (refreshed != state.value.modelShortlist) {
                settingsManager?.modelShortlistJson = encodeShortlist(refreshed)
            }
            state.update {
                it.copy(
                    catalogModels = resolvedCatalog.models,
                    providerDisplayNames = resolvedCatalog.providerDisplayNames,
                    modelShortlist = refreshed,
                    selectedModelIndex = reanchorSelectedModelIndex(refreshed, it.selectedModelId)
                )
            }
        }
    }
}

private suspend fun resolveModelCatalog(
    repository: OpenCodeRepository,
    providersResult: Result<ProvidersResponse>
): CatalogBuildResult? {
    val registryResult = repository.getProviderRegistry()
    if (registryResult.isSuccess) {
        val registry = registryResult.getOrThrow()
        return buildCatalog(registry.all, registry.connectedProviderIds)
    }
    val providers = providersResult.getOrNull()
    if (providers != null) {
        return buildCatalog(providers.providers, null)
    }
    // Both endpoints failed: fall back to the hardcoded presets so the
    // "Add Model" catalog is never empty (users can still add known models
    // while offline or against an incompatible server).
    val presetCatalog = ModelPresets.list.map { preset ->
        CatalogModel(
            providerId = preset.providerId,
            modelId = preset.modelId,
            displayName = preset.displayName,
            shortName = preset.customShortName ?: preset.displayName
        )
    }
    return CatalogBuildResult(models = presetCatalog, providerDisplayNames = emptyMap())
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

internal fun launchSetSessionArchived(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    archived: Boolean
) {
    scope.launch {
        val archivedValue = if (archived) System.currentTimeMillis() else -1L
        val ids = sessionSubtreeIds(state.value.sessions, sessionId, parentFirst = !archived)
        for (id in ids) {
            repository.updateSessionArchived(id, archivedValue)
                .onSuccess { updated ->
                    state.update { current ->
                        current.copy(sessions = current.sessions.map { session -> if (session.id == id) updated else session })
                    }
                }
                .onFailure { error ->
                    state.update {
                        it.copy(error = "Failed to ${if (archived) "archive" else "restore"} session: ${errorMessageOrFallback(error, "unknown error")}")
                    }
                    return@launch
                }
        }
    }
}

private fun sessionSubtreeIds(sessions: List<com.yage.opencode_client.data.model.Session>, rootId: String, parentFirst: Boolean): List<String> {
    val childrenByParent = sessions.groupBy { it.parentId }
    fun collect(id: String): List<String> {
        val children = childrenByParent[id].orEmpty().flatMap { collect(it.id) }
        return if (parentFirst) listOf(id) + children else children + id
    }
    return collect(rootId)
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
    attachments: List<ComposerImageAttachment> = emptyList(),
    agent: String,
    model: Message.ModelInfo?,
    messageId: String,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onSuccess: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null
) {
    scope.launch {
        repository.sendMessage(sessionId, text, agent, model, attachments = attachments, messageId = messageId)
            .onSuccess {
                state.update {
                    it.copy(
                        error = null,
                        sessions = bumpSessionUpdated(it.sessions, sessionId, System.currentTimeMillis()),
                        sessionStatuses = it.sessionStatuses + (sessionId to com.yage.opencode_client.data.model.SessionStatus(type = "busy"))
                    )
                }
                onSuccess?.invoke()
                onRefreshSessions()
                onRefreshMessages(sessionId, true)
                launch {
                    delay(MainViewModelTimings.messageRefreshDelayMs)
                    onRefreshSessions()
                    onRefreshMessages(sessionId, false)
                }
            }
            .onFailure { error ->
                // The optimistic row was inserted before dispatch. On failure, drop it
                // and hand the text/attachments back to the composer so the user can retry.
                // Only restore the composer if the user is still on the session that sent
                // this message; otherwise we'd clobber another session's draft.
                state.update {
                    it.copy(
                        messages = it.messages.filter { m -> m.info.id != messageId },
                        pendingOptimisticMessageIds = it.pendingOptimisticMessageIds - messageId,
                        inputText = if (it.currentSessionId == sessionId) text else it.inputText,
                        imageAttachments = if (it.currentSessionId == sessionId) attachments else it.imageAttachments,
                        error = errorMessageOrFallback(error, "Failed to send message")
                    )
                }
            }
        onComplete?.invoke()
    }
}

internal fun makeServerId(prefix: String): String =
    "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

internal fun buildOptimisticMessage(
    sessionId: String,
    text: String,
    attachments: List<ComposerImageAttachment>,
    messageId: String,
    parentMessageId: String?
): MessageWithParts {
    val now = System.currentTimeMillis()
    val message = Message(
        id = messageId,
        sessionId = sessionId,
        role = "user",
        parentId = parentMessageId,
        time = Message.TimeInfo(created = now, completed = now)
    )
    val parts = buildList {
        if (text.isNotBlank()) {
            add(
                Part(
                    id = "temp-part-$messageId",
                    messageId = messageId,
                    sessionId = sessionId,
                    type = "text",
                    text = text
                )
            )
        }
        attachments.forEach { attachment ->
            add(
                Part(
                    id = "temp-file-${attachment.id}",
                    messageId = messageId,
                    sessionId = sessionId,
                    type = "file",
                    mime = attachment.mime,
                    filename = attachment.filename,
                    url = attachment.dataUrl
                )
            )
        }
    }
    return MessageWithParts(info = message, parts = parts)
}
