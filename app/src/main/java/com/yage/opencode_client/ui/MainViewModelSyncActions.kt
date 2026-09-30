package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.Message
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

internal fun launchSseCollection(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onEvent: (SSEEvent) -> Unit,
    onConnected: (() -> Unit)? = null
): Job {
    return scope.launch {
        repository.connectSSE(onConnected)
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

/** Records a user-message SSE event for the round counter when the payload
 *  carries an `info` object with a user role. Dedup happens downstream
 *  (seen-ID sets), so calling on both created and updated is safe. */
private fun recordUserMessageForStats(
    state: MutableStateFlow<AppState>,
    event: SSEEvent,
    sessionId: String,
    onRecordUserMessage: (String, String) -> Unit
) {
    if (sessionId != state.value.currentSessionId) return
    val info = parseMessageInfo(event) ?: return
    if (info.role == "user") {
        onRecordUserMessage(sessionId, info.id)
    }
}

/**
 * Watchdog that replaces the old busy polling. Every 5s it checks whether any
 * SSE frame arrived within the last 20s (two heartbeat periods; the server
 * hard-codes a 10s heartbeat on `/global/event`). `lastFrameAtMs == 0` means
 * no frame ever arrived, in which case it never fires — reconnect bootstrap
 * (onConnected) owns that case. When silence is detected it runs exactly one
 * reconcile (loadMessages + loadSessionStatus) for the current session and
 * touches the timestamp so the next check is a no-op.
 */
internal fun launchSseWatchdog(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>,
    onLoadMessages: (String, Boolean) -> Unit,
    onLoadSessionStatus: () -> Unit,
    lastFrameAtMs: () -> Long,
    touchLastFrameAtMs: (Long) -> Unit,
    clock: () -> Long = { System.currentTimeMillis() }
): Job {
    return scope.launch {
        while (true) {
            delay(MainViewModelTimings.watchdogCheckMs)
            val last = lastFrameAtMs()
            if (last == 0L) continue
            val now = clock()
            if (now - last > MainViewModelTimings.watchdogSilenceMs) {
                touchLastFrameAtMs(now)
                state.value.currentSessionId?.let { sessionId ->
                    if (state.value.sessionStatuses[sessionId]?.isBusy != true) {
                        onLoadMessages(sessionId, false)
                    }
                    onLoadSessionStatus()
                }
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
    onNonFatalIssue: (String) -> Unit,
    onRecordUserMessage: (String, String) -> Unit = { _, _ -> },
    onRecordToolPart: (String, String) -> Unit = { _, _ -> }
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
                            streamingReasoningPart = null,
                            partTypeIndex = emptyMap()
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
                if (sessionId == state.value.currentSessionId) {
                    recordUserMessageForStats(state, event, sessionId, onRecordUserMessage)
                    val info = parseMessageInfoFromEvent(event)
                    if (info != null) {
                        upsertMessageInfo(state, info)
                    } else {
                        onRefreshSessions()
                        onRefreshMessages(sessionId, true)
                    }
                } else {
                    onRefreshSessions()
                }
            }
        }
        "message.updated" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null) {
                if (sessionId == state.value.currentSessionId) {
                    recordUserMessageForStats(state, event, sessionId, onRecordUserMessage)
                    val info = parseMessageInfoFromEvent(event)
                    if (info != null) {
                        upsertMessageInfo(state, info)
                    } else {
                        onRefreshSessions()
                        onRefreshMessages(sessionId, false)
                    }
                } else {
                    onRefreshSessions()
                }
            }
        }
        "message.part.delta" -> {
            // Native-server streaming text. text and reasoning both use
            // field:"text", so partTypeIndex (populated by message.part.updated
            // upserts) is what distinguishes them.
            val sessionId = event.payload.getString("sessionID") ?: return
            if (sessionId != state.value.currentSessionId) return
            if (event.payload.getString("field") != "text") return
            val messageId = event.payload.getString("messageID") ?: return
            val partId = event.payload.getString("partID") ?: return
            val delta = event.payload.getString("delta") ?: return
            if (delta.isBlank()) return
            val key = "$messageId:$partId"
            // Seed from the part already in the row. A part.updated frame can
            // land between deltas; starting the overlay from "" would flash
            // the UI down to just this token.
            val previous = state.value.streamingPartTexts[key]
                ?: existingPartText(state.value.messages, messageId, partId)
                ?: ""
            val isReasoning = state.value.partTypeIndex[partId] == "reasoning"
            state.update {
                it.copy(
                    streamingPartTexts = it.streamingPartTexts + (key to (previous + delta)),
                    streamingReasoningPart = if (isReasoning) {
                        reasoningPartOrNull("reasoning", partId, messageId, sessionId)
                    } else {
                        it.streamingReasoningPart
                    }
                )
            }
        }
        "message.part.updated" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                val deltaEvent = parseMessagePartDeltaEvent(event)
                if (deltaEvent != null && deltaEvent.partType == "tool" && deltaEvent.partId != null) {
                    onRecordToolPart(sessionId, deltaEvent.partId)
                }
                val part = parseMessagePartUpdatedFull(event)
                when (partUpdatedUpsertDecision(part, event.payload.getString("delta") != null)) {
                    PartUpdatedDecision.ShimDelta -> {
                        if (deltaEvent != null && deltaEvent.sessionId == state.value.currentSessionId) {
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
                                    it.copy(
                                        streamingPartTexts = emptyMap(),
                                        streamingReasoningPart = null,
                                        partTypeIndex = emptyMap()
                                    )
                                }
                                onRefreshMessages(deltaEvent.sessionId, false)
                            }
                        }
                    }
                    PartUpdatedDecision.Upsert -> {
                        val fullPart = part ?: return
                        state.update { current ->
                            val (nextMessages, nextPartTypeIndex) = upsertMessagePartInMessages(
                                current.messages,
                                current.partTypeIndex,
                                fullPart
                            )
                            current.copy(
                                messages = nextMessages,
                                partTypeIndex = nextPartTypeIndex
                                // Keep the streaming overlay. Clearing it here
                                // makes the next delta render as a single token
                                // until the following full frame, which is the
                                // single-vs-streaming flicker. Idle reconcile
                                // clears the overlay once the turn is done.
                            )
                        }
                    }
                    PartUpdatedDecision.RestFallback -> {
                        state.update {
                            it.copy(
                                streamingPartTexts = emptyMap(),
                                streamingReasoningPart = null,
                                partTypeIndex = emptyMap()
                            )
                        }
                        onRefreshMessages(sessionId, false)
                    }
                }
            }
        }
        "message.part.removed" -> {
            val sessionId = event.payload.getString("sessionID") ?: return
            if (sessionId != state.value.currentSessionId) return
            val messageId = event.payload.getString("messageID") ?: return
            val partId = event.payload.getString("partID") ?: return
            state.update {
                it.copy(
                    messages = removePartFromMessages(it.messages, messageId, partId),
                    partTypeIndex = it.partTypeIndex - partId,
                    streamingPartTexts = it.streamingPartTexts - "$messageId:$partId",
                    streamingReasoningPart = if (it.streamingReasoningPart?.id == partId) {
                        null
                    } else {
                        it.streamingReasoningPart
                    }
                )
            }
        }
        "message.removed" -> {
            val sessionId = event.payload.getString("sessionID") ?: return
            if (sessionId != state.value.currentSessionId) return
            val messageId = event.payload.getString("messageID") ?: return
            state.update {
                it.copy(
                    messages = removeMessageFromMessages(it.messages, messageId),
                    pendingOptimisticMessageIds = it.pendingOptimisticMessageIds - messageId
                )
            }
        }
        "server.heartbeat" -> {
            // No action needed: the watchdog's frame timestamp is stamped in
            // handleSSEEvent before dispatch, so every frame counts.
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

/** Info upsert by id (server info authoritative for metadata, local parts kept
 *  until the next REST reconcile). Prunes the optimistic set on hit, matching
 *  `mergePendingOptimisticMessages` semantics. */
private fun existingPartText(
    messages: List<com.yage.opencode_client.data.model.MessageWithParts>,
    messageId: String,
    partId: String
): String? {
    return messages.firstOrNull { it.info.id == messageId }
        ?.parts
        ?.firstOrNull { it.id == partId }
        ?.text
}

private fun upsertMessageInfo(state: MutableStateFlow<AppState>, info: Message) {
    state.update {
        it.copy(
            messages = upsertMessageInfoInMessages(it.messages, info),
            pendingOptimisticMessageIds = it.pendingOptimisticMessageIds - info.id
        )
    }
}
