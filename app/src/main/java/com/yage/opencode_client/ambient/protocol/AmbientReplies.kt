package com.yage.opencode_client.ambient.protocol

import com.yage.opencode_client.data.model.SSEEvent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AmbientReplyReservation(
    val sessionId: String,
    val directory: String,
    val userMessageId: String,
    val title: String,
    val isNew: Boolean,
)

/** Admission is independent of whether function_call_output can be sent to the voice socket. */
interface AmbientReplyObserver {
    fun reserve(reservation: AmbientReplyReservation, history: JsonArray?): String?
    fun accept(token: String)
    fun rollback(token: String)
}

enum class AmbientReplySend { SUBMITTED, REJECTED, UNKNOWN }

internal data class AmbientReplyRow(
    val id: String,
    val role: String,
    val text: String,
    val complete: Boolean,
    val toolCall: Boolean,
    val agent: String?,
)

internal data class AmbientReplyPage(
    val rows: List<AmbientReplyRow>,
    val before: String? = null,
    val gaps: Boolean = false,
)

internal object AmbientReplyParser {
    fun page(http: AmbientHttpResult, sessionId: String): AmbientReplyPage {
        check(http.code == 200)
        val element = http.jsonOrNull()
        val array = element as? JsonArray ?: (element as? JsonObject)?.get("messages") as? JsonArray
            ?: error("Invalid reply page")
        return rows(array, sessionId).copy(before = cursor(http))
    }

    fun rows(array: JsonArray, sessionId: String): AmbientReplyPage {
        var gaps = false
        val rows = array.mapNotNull { element ->
            val row = element as? JsonObject
            val info = row?.get("info") as? JsonObject
            val id = jsonString(info?.get("id"))
            val role = jsonString(info?.get("role"))
            val parts = row?.get("parts") as? JsonArray
            val source = jsonString(info?.get("sessionID"))
            if (id.isNullOrBlank() || role.isNullOrBlank() || parts == null || (source != null && source != sessionId)) {
                gaps = true
                return@mapNotNull null
            }
            val text = parts.mapNotNull { part ->
                val objectPart = part as? JsonObject
                if (objectPart == null || jsonString(objectPart["type"]) == null) {
                    gaps = true
                    null
                } else if (jsonString(objectPart["type"]) == "text" && jsonBoolean(objectPart["synthetic"]) != true) {
                    jsonString(objectPart["text"])?.trim()?.takeIf { it.isNotEmpty() }
                } else null
            }.joinToString("\n\n")
            val finish = jsonString(info?.get("finish"))
            val completed = (info?.get("time") as? JsonObject)?.get("completed")
            AmbientReplyRow(
                id, role, text,
                (completed != null && completed !is JsonNull) || terminal(finish),
                finish == "tool-calls" || finish == "tool_calls",
                jsonString(info?.get("agent")),
            )
        }
        return AmbientReplyPage(rows, gaps = gaps)
    }

    fun terminal(finish: String?): Boolean = !finish.isNullOrBlank() &&
        finish != "unknown" && finish != "tool-calls" && finish != "tool_calls"

    fun terminalUpdate(event: SSEEvent): String? {
        val info = event.payload.getJsonObject("info") ?: return null
        val completed = (info["time"] as? JsonObject)?.get("completed")
        if ((completed == null || completed is JsonNull) && !terminal(jsonString(info["finish"]))) return null
        return event.payload.getString("sessionID")?.takeIf { it.isNotBlank() }
            ?: jsonString(info["sessionID"])?.takeIf { it.isNotBlank() }
    }

    private fun cursor(http: AmbientHttpResult): String? {
        http.header("X-Next-Cursor")?.let { return it }
        val link = http.header("Link") ?: return null
        val entries = Regex("<([^>]*)>([^<]*)").findAll(link)
        for (entry in entries) {
            val rel = Regex("(?:^|;)\\s*rel\\s*=\\s*(?:\"([^\"]*)\"|([^;,\\s]+))", RegexOption.IGNORE_CASE)
                .find(entry.groupValues[2]) ?: continue
            if ((rel.groups[1]?.value ?: rel.groups[2]?.value.orEmpty()).split(Regex("\\s+")).none { it.equals("next", true) }) continue
            // Only extract a parameter, never dispatch to the Link's origin.
            val target = entry.groupValues[1]
            val url = target.toHttpUrlOrNull() ?: ("https://example.invalid/" + target.trimStart('/')).toHttpUrlOrNull()
            url?.queryParameter("before")?.let { return it }
        }
        return null
    }
}

internal class AmbientReplyReader(
    private val gateway: AmbientAuthorizingGateway,
    private val token: Long,
) {
    suspend fun page(sessionId: String, directory: String, before: String?): AmbientReplyPage {
        val query = mutableMapOf("directory" to directory, "limit" to "100")
        if (before != null) query["before"] = before
        return AmbientReplyParser.page(exchange(AmbientGatewayCall("GET", "/session/$sessionId/message", query)), sessionId)
    }

    suspend fun idle(sessionId: String, directory: String): Boolean? {
        val http = exchange(AmbientGatewayCall("GET", "/session/status", mapOf("directory" to directory)))
        if (http.code != 200) return null
        val map = http.jsonOrNull() as? JsonObject ?: return null
        val row = map[sessionId] ?: return true // Successful status maps contain running sessions only.
        val type = jsonString((row as? JsonObject)?.get("type")) ?: return null
        return when (type) { "idle" -> true; "busy", "retry" -> false; else -> null }
    }

    private suspend fun exchange(call: AmbientGatewayCall): AmbientHttpResult = when (val result = gateway.exchange(token, call)) {
        is AmbientGatewayExchange.Completed -> result.http
        AmbientGatewayExchange.StaleLease -> throw CancellationException("Retired voice owner")
        is AmbientGatewayExchange.TransportFailed -> error("Reply transport unavailable")
    }
}

internal object AmbientReplyCopy {
    fun render(title: String, sessionId: String, agent: String?, body: String): String {
        val heading = buildString {
            append("来源会话：").append(clip(title.ifBlank { sessionId }, 80)).append('\n')
            append("来源编号：").append(clip(sessionId, 64)).append('\n')
            agent?.takeIf { it.isNotBlank() }?.let { append("来源 agent：").append(clip(it, 40)).append('\n') }
        }
        val full = heading + "新回复：" + body
        if (full.toByteArray(Charsets.UTF_8).size <= AmbientProtocol.MAX_APPEND_BYTES) return full
        val prefix = heading + "正文已截断，详情见原会话\n新回复："
        return prefix + clip(body, AmbientProtocol.MAX_APPEND_BYTES - prefix.toByteArray(Charsets.UTF_8).size)
    }

    private fun clip(text: String, bytes: Int): String {
        var used = 0
        var end = 0
        while (end < text.length) {
            val point = text.codePointAt(end)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (used + size > bytes) break
            used += size
            end += Character.charCount(point)
        }
        return text.substring(0, end)
    }
}
