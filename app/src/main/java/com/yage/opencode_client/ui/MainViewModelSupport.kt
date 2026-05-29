package com.yage.opencode_client.ui

import android.util.Log
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

private val lenientJson = Json { ignoreUnknownKeys = true }

internal object MainViewModelTimings {
    const val sessionPageSize = 100
    const val messageRetryDelayMs = 400L
    const val messageRefreshDelayMs = 1200L
    const val busyPollingIntervalMs = 2000L
}

internal data class SessionCreatedEvent(
    val session: Session
)

internal data class SessionStatusEvent(
    val sessionId: String,
    val status: SessionStatus
)

internal data class MessagePartDeltaEvent(
    val sessionId: String,
    val messageId: String?,
    val partId: String?,
    val partType: String,
    val delta: String?
)

/**
 * Strip surrounding/hidden whitespace (including zero-width and BOM) from a bearer
 * token. Previously lived on `AIBuildersAudioClient`; kept here as a UI-layer helper
 * after the audio pipeline moved into the VoiceFlowKit library.
 */
internal fun sanitizeBearerToken(rawToken: String): String {
    return rawToken
        .trim()
        .filterNot { ch ->
            ch.isWhitespace() ||
                Character.getType(ch) == Character.FORMAT.toInt() ||
                ch == '﻿'
        }
}

internal fun aiBuilderSignature(baseURL: String, token: String): String {
    val input = "$baseURL|$token"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun errorMessageOrFallback(throwable: Throwable?, fallback: String): String {
    val message = throwable?.message?.trim().orEmpty()
    return if (message.isEmpty()) fallback else message
}

internal fun parseSessionCreatedEvent(event: SSEEvent): SessionCreatedEvent? {
    val sessionJson = event.payload.getJsonObject("session") ?: return null
    return runCatching {
        SessionCreatedEvent(Json.decodeFromString<Session>(sessionJson.toString()))
    }.getOrNull()
}

internal fun parseSessionUpdatedEvent(event: SSEEvent): Session? {
    val sessionJson = event.payload.getJsonObject("info")
        ?: event.payload.getJsonObject("session")
        ?: return null
    return runCatching {
        Json.decodeFromString<Session>(sessionJson.toString())
    }.getOrNull()
}

internal fun upsertSession(sessions: List<Session>, session: Session): List<Session> {
    return listOf(session) + sessions.filter { it.id != session.id }
}

internal fun nextSessionFetchLimit(current: Int, pageSize: Int = MainViewModelTimings.sessionPageSize): Int {
    return maxOf(current, pageSize) + maxOf(pageSize, 1)
}

internal fun parseSessionStatusEvent(event: SSEEvent): SessionStatusEvent? {
    val sessionId = event.payload.getString("sessionID") ?: return null
    val statusJson = event.payload.getJsonObject("status") ?: return null
    return runCatching {
        SessionStatusEvent(
            sessionId = sessionId,
            status = Json.decodeFromString<SessionStatus>(statusJson.toString())
        )
    }.getOrNull()
}

internal fun parseMessagePartDeltaEvent(event: SSEEvent): MessagePartDeltaEvent? {
    val sessionId = event.payload.getString("sessionID") ?: return null
    val partObj = event.payload.getJsonObject("part")
    val messageId = (partObj?.get("messageID") as? JsonPrimitive)?.content
    val partId = (partObj?.get("id") as? JsonPrimitive)?.content
    val partType = (partObj?.get("type") as? JsonPrimitive)?.content ?: "text"
    return MessagePartDeltaEvent(
        sessionId = sessionId,
        messageId = messageId,
        partId = partId,
        partType = partType,
        delta = event.payload.getString("delta")
    )
}

internal fun parseQuestionAskedEvent(event: SSEEvent): QuestionRequest? {
    val properties = event.payload.properties ?: return null
    return runCatching {
        lenientJson.decodeFromString<QuestionRequest>(properties.toString())
    }.getOrNull()
}

internal fun reasoningPartOrNull(partType: String, partId: String, messageId: String, sessionId: String): Part? {
    return if (partType == "reasoning") {
        Part(id = partId, messageId = messageId, sessionId = sessionId, type = "reasoning")
    } else {
        null
    }
}

internal fun reportNonFatalIssue(tag: String, message: String, throwable: Throwable? = null) {
    if (throwable != null) {
        Log.w(tag, message, throwable)
    } else {
        Log.w(tag, message)
    }
}

internal fun mergedSpeechInput(prefix: String, transcript: String): String {
    val cleaned = transcript.trim()
    if (cleaned.isEmpty()) return prefix
    if (prefix.isEmpty()) return cleaned
    return "$prefix $cleaned"
}

internal fun speechFailureInput(existingInput: String, currentInput: String): String {
    return currentInput.ifBlank { existingInput }
}
