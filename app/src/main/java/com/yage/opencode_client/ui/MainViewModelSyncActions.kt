package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal fun launchBusyPolling(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>,
    onLoadMessages: (String, Boolean) -> Unit
): Job {
    return scope.launch {
        while (true) {
            delay(MainViewModelTimings.busyPollingIntervalMs)
            val sessionId = state.value.currentSessionId ?: continue
            if (state.value.isLoadingMessages) continue
            if (!state.value.isCurrentSessionBusy) continue
            onLoadMessages(sessionId, false)
        }
    }
}

internal fun launchSseCollection(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onEvent: (SSEEvent) -> Unit
): Job {
    return scope.launch {
        repository.connectSSE()
            .catch { error ->
                state.update { it.copy(error = "SSE Error: ${error.message}") }
            }
            .collect { result ->
                result.onSuccess { event -> onEvent(event) }
                    .onFailure { error ->
                        state.update { it.copy(error = "SSE Error: ${error.message}") }
                    }
            }
    }
}

internal fun handleIncomingSseEvent(
    state: MutableStateFlow<AppState>,
    event: SSEEvent,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onLoadPendingPermissions: () -> Unit,
    onNonFatalIssue: (String) -> Unit
) {
    when (event.payload.type) {
        "session.created" -> {
            val created = parseSessionCreatedEvent(event)
            if (created != null) {
                state.update { it.copy(sessions = upsertSession(it.sessions, created.session)) }
                onRefreshSessions()
            } else {
                onNonFatalIssue("Ignoring invalid session.created payload")
            }
        }
        "session.updated" -> {
            val updated = parseSessionUpdatedEvent(event)
            if (updated != null) {
                state.update { it.copy(sessions = upsertSession(it.sessions, updated)) }
                onRefreshSessions()
            } else {
                onNonFatalIssue("Ignoring invalid session.updated payload")
            }
        }
        "session.status" -> {
            val statusEvent = parseSessionStatusEvent(event)
            if (statusEvent != null) {
                state.update {
                    it.copy(
                        sessionStatuses = it.sessionStatuses + (statusEvent.sessionId to statusEvent.status)
                    )
                }
                if (statusEvent.sessionId == state.value.currentSessionId && !statusEvent.status.isBusy) {
                    state.update {
                        it.copy(
                            streamingPartTexts = emptyMap(),
                            streamingReasoningPart = null
                        )
                    }
                    onRefreshSessions()
                    onRefreshMessages(statusEvent.sessionId, false)
                }
            } else {
                onNonFatalIssue("Ignoring invalid session.status payload")
            }
        }
        "message.created" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null) {
                onRefreshSessions()
                if (sessionId == state.value.currentSessionId) {
                    onRefreshMessages(sessionId, true)
                }
            }
        }
        "message.updated" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null) {
                onRefreshSessions()
                if (sessionId == state.value.currentSessionId) {
                    onRefreshMessages(sessionId, false)
                }
            }
        }
        "message.part.updated" -> {
            val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
            if (deltaEvent.sessionId == state.value.currentSessionId) {
                if (
                    deltaEvent.messageId != null &&
                    deltaEvent.partId != null &&
                    !deltaEvent.delta.isNullOrBlank()
                ) {
                    val key = "${deltaEvent.messageId}:${deltaEvent.partId}"
                    val previousValue = state.value.streamingPartTexts[key] ?: ""
                    state.update {
                        it.copy(
                            streamingPartTexts = it.streamingPartTexts + (key to (previousValue + deltaEvent.delta)),
                            streamingReasoningPart = reasoningPartOrNull(
                                partType = deltaEvent.partType,
                                partId = deltaEvent.partId,
                                messageId = deltaEvent.messageId,
                                sessionId = deltaEvent.sessionId
                            ) ?: it.streamingReasoningPart
                        )
                    }
                } else {
                    state.update {
                        it.copy(streamingPartTexts = emptyMap(), streamingReasoningPart = null)
                    }
                    onRefreshMessages(deltaEvent.sessionId, false)
                }
            }
        }
        "permission.asked" -> {
            onLoadPendingPermissions()
        }
        "question.asked" -> {
            val question = parseQuestionAskedEvent(event)
            if (question != null) {
                state.update { currentState ->
                    val existing = currentState.pendingQuestions.any { it.id == question.id }
                    if (!existing) {
                        currentState.copy(pendingQuestions = currentState.pendingQuestions + question)
                    } else {
                        currentState
                    }
                }
            } else {
                onNonFatalIssue("Ignoring invalid question.asked payload")
            }
        }
        "question.replied", "question.rejected" -> {
            val requestId = event.payload.getString("requestID") 
                ?: event.payload.getString("id")
            if (requestId != null) {
                state.update { currentState ->
                    currentState.copy(
                        pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId }
                    )
                }
            }
        }
        "todo.updated" -> {
            val sessionId = event.payload.getString("sessionID") ?: return
            val todosArray = event.payload.properties?.get("todos") as? kotlinx.serialization.json.JsonArray ?: return
            val todos = try {
                Json.decodeFromJsonElement<List<TodoItem>>(todosArray)
            } catch (_: Exception) {
                return
            }
            state.update { it.copy(sessionTodos = it.sessionTodos + (sessionId to todos)) }
        }
        "session.error" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                // prompt_async acknowledges with 204 before the turn runs, so a failure
                // that happens before the user message is persisted only surfaces here.
                // The server will never echo our id, so drop the pending optimistic rows
                // and hand their text back to the composer so the user can retry.
                val reason = parseSessionErrorReason(event)
                state.update { state ->
                    if (state.pendingOptimisticMessageIds.isEmpty()) {
                        state.copy(error = "Send failed: $reason")
                    } else {
                        val pendingIds = state.pendingOptimisticMessageIds
                        val recoveredText = state.messages
                            .filter { m -> m.info.id in pendingIds }
                            .mapNotNull { row -> row.parts.firstOrNull { p -> p.isText }?.text }
                            .filter { text -> text.isNotBlank() }
                            .joinToString("\n")
                        state.copy(
                            messages = state.messages.filter { m -> m.info.id !in pendingIds },
                            pendingOptimisticMessageIds = emptySet(),
                            inputText = recoveredText.ifBlank { state.inputText },
                            error = "Send failed: $reason"
                        )
                    }
                }
                onRefreshMessages(sessionId, false)
            }
        }
    }
}
