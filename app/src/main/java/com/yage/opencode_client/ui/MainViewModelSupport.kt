package com.yage.opencode_client.ui

import android.util.Log
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

private val lenientJson = Json { ignoreUnknownKeys = true }

internal object MainViewModelTimings {
    const val sessionPageSize = 400
    const val messageRetryDelayMs = 400L
    const val messageRefreshDelayMs = 1200L
    const val watchdogCheckMs = 5000L
    const val watchdogSilenceMs = 20000L
}

internal data class SessionCreatedEvent(
    val session: Session
)

internal data class SessionStatusEvent(
    val sessionId: String,
    val status: SessionStatus
)

internal data class ParsedMessageInfo(
    val id: String,
    val role: String
)

internal fun parseMessageInfo(event: SSEEvent): ParsedMessageInfo? {
    val info = event.payload.getJsonObject("info") ?: return null
    val id = (info["id"] as? JsonPrimitive)?.content ?: return null
    val role = (info["role"] as? JsonPrimitive)?.content ?: return null
    return ParsedMessageInfo(id = id, role = role)
}

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

internal fun bumpSessionUpdated(sessions: List<Session>, sessionId: String, updated: Long): List<Session> {
    return sessions.map { session ->
        if (session.id == sessionId) {
            session.copy(time = session.time.withUpdatedAtLeast(updated))
        } else {
            session
        }
    }
}

internal fun mergeRefreshedSessionsPreservingLocalActivity(
    refreshed: List<Session>,
    local: List<Session>,
    currentSessionId: String? = null
): List<Session> {
    val localById = local.associateBy { it.id }
    val merged = refreshed.map { remote ->
        val localSession = localById[remote.id]
        val localUpdated = localSession?.time?.updated
        val remoteUpdated = remote.time?.updated
        if (localUpdated != null && (remoteUpdated == null || localUpdated > remoteUpdated)) {
            // The local copy is strictly newer than this refresh response (e.g. it was just
            // upserted from a session.updated SSE event that carries the server-authoritative
            // title). A concurrently-issued full refresh can return a stale snapshot that
            // predates the title generation, so prefer the local title here to avoid clobbering
            // it. The full refresh remains authoritative whenever it is at least as fresh.
            remote.copy(
                title = localSession.title ?: remote.title,
                time = remote.time.withUpdatedAtLeast(localUpdated)
            )
        } else {
            remote
        }
    }
    val selectedSession = currentSessionId?.let(localById::get)
    return if (selectedSession != null && merged.none { it.id == selectedSession.id }) {
        listOf(selectedSession) + merged
    } else {
        merged
    }
}

private fun Session.TimeInfo?.withUpdatedAtLeast(updated: Long): Session.TimeInfo {
    val currentUpdated = this?.updated
    return (this ?: Session.TimeInfo()).copy(
        updated = if (currentUpdated == null || updated > currentUpdated) updated else currentUpdated
    )
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

/** Part types that mark a message as assistant-owned; these may create a shell
 *  row before the message info arrives. text/file parts never do (role unknown). */
internal val assistantShellPartTypes = setOf("tool", "reasoning", "step-finish", "patch")

internal enum class PartUpdatedDecision {
    /** Shim shape (top-level `delta`): keep the existing streaming/REST path. */
    ShimDelta,
    /** Complete native part: upsert into local messages without REST. */
    Upsert,
    /** Unrecognized/malformed: fall back to the existing REST refresh. */
    RestFallback
}

/**
 * Payload-completeness gate for `message.part.updated`. Decisions short-circuit
 * top to bottom: a top-level `delta` is the dsh-shim shape (unchanged behavior);
 * a tool part carrying `state` or a text/reasoning part carrying `text` is
 * complete enough to upsert in place; anything else falls back to REST, which
 * is exactly today's behavior (safe direction).
 */
internal fun partUpdatedUpsertDecision(part: Part?, hasTopLevelDelta: Boolean): PartUpdatedDecision {
    if (hasTopLevelDelta) return PartUpdatedDecision.ShimDelta
    val p = part ?: return PartUpdatedDecision.RestFallback
    if (p.messageId == null) return PartUpdatedDecision.RestFallback
    return when {
        p.type == "tool" && p.state != null -> PartUpdatedDecision.Upsert
        (p.type == "text" || p.type == "reasoning") && p.text != null -> PartUpdatedDecision.Upsert
        else -> PartUpdatedDecision.RestFallback
    }
}

internal fun parseMessagePartUpdatedFull(event: SSEEvent): Part? {
    val partObj = event.payload.getJsonObject("part") ?: return null
    return runCatching {
        lenientJson.decodeFromString<Part>(partObj.toString())
    }.getOrNull()
}

internal fun parseMessageInfoFromEvent(event: SSEEvent): Message? {
    val infoObj = event.payload.getJsonObject("info") ?: return null
    return runCatching {
        lenientJson.decodeFromString<Message>(infoObj.toString())
    }.getOrNull()
}

/** Shell row info for an assistant part that arrives before its message info.
 *  Overwritten by the later `message.updated` info upsert; the REST reconcile
 * is the final convergence. */
internal fun shellInfo(messageId: String, sessionId: String?): Message =
    Message(id = messageId, sessionId = sessionId, role = "assistant")

internal fun upsertMessagePartInMessages(
    messages: List<MessageWithParts>,
    partTypeIndex: Map<String, String>,
    part: Part
): Pair<List<MessageWithParts>, Map<String, String>> {
    val messageId = part.messageId ?: return messages to partTypeIndex
    val index = messages.indexOfFirst { it.info.id == messageId }
    val nextMessages = if (index >= 0) {
        val row = messages[index]
        val parts = row.parts.toMutableList()
        // A real part supersedes the optimistic temp twin of the same type
        // (the user text part that just landed for a message sent moments
        // ago), so the row never renders the text twice before the REST
        // reconcile.
        if (!part.id.startsWith("temp-")) {
            parts.removeAll { it.id.startsWith("temp-") && it.type == part.type }
        }
        val partIndex = parts.indexOfFirst { it.id == part.id }
        if (partIndex >= 0) parts[partIndex] = part else parts.add(part)
        messages.toMutableList().also { it[index] = row.copy(parts = parts) }
    } else if (part.type in assistantShellPartTypes) {
        messages + MessageWithParts(info = shellInfo(messageId, part.sessionId), parts = listOf(part))
    } else {
        messages
    }
    // First-write-wins: the type index only feeds the delta branch's
    // text-vs-reasoning distinction, and a part's type never changes.
    val nextPartTypeIndex = if (partTypeIndex.containsKey(part.id)) {
        partTypeIndex
    } else {
        partTypeIndex + (part.id to part.type)
    }
    return nextMessages to nextPartTypeIndex
}

/** Info upsert by id: replace the row's info, keep local parts (server info is
 *  authoritative for metadata; parts converge on the next REST reconcile). */
internal fun upsertMessageInfoInMessages(
    messages: List<MessageWithParts>,
    info: Message
): List<MessageWithParts> {
    val index = messages.indexOfFirst { it.info.id == info.id }
    return if (index >= 0) {
        messages.toMutableList().also { it[index] = it[index].copy(info = info) }
    } else {
        messages + MessageWithParts(info = info, parts = emptyList())
    }
}

internal fun removePartFromMessages(
    messages: List<MessageWithParts>,
    messageId: String,
    partId: String
): List<MessageWithParts> {
    val index = messages.indexOfFirst { it.info.id == messageId }
    if (index < 0) return messages
    val row = messages[index]
    return messages.toMutableList().also {
        it[index] = row.copy(parts = row.parts.filterNot { p -> p.id == partId })
    }
}

internal fun removeMessageFromMessages(
    messages: List<MessageWithParts>,
    messageId: String
): List<MessageWithParts> = messages.filterNot { it.info.id == messageId }

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

/**
 * Builds a short display reason from a `session.error` payload
 * (`error: {name, data: {message}}`). Server causes can be long multi-line
 * dumps; keep the first meaningful line, bounded.
 */
internal fun parseSessionErrorReason(event: SSEEvent): String {
    val errorObj = event.payload.getJsonObject("error") ?: return "unknown error"
    val name = (errorObj["name"] as? JsonPrimitive)?.content
    val data = errorObj["data"] as? JsonObject
    val message = (data?.get("message") as? JsonPrimitive)?.content
        ?: (data?.get("error") as? JsonPrimitive)?.content
    val text = when {
        !message.isNullOrBlank() -> {
            val firstLine = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: message
            if (!name.isNullOrBlank()) "$name: $firstLine" else firstLine
        }
        !name.isNullOrBlank() -> name
        else -> "unknown error"
    }
    return text.take(300)
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
