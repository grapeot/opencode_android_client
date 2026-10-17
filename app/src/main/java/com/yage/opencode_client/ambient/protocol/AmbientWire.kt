package com.yage.opencode_client.ambient.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID

sealed interface AmbientClientEvent {
    class Audio(val pcm: ByteArray) : AmbientClientEvent
    data object Close : AmbientClientEvent
    data object Mute : AmbientClientEvent
    data object Unmute : AmbientClientEvent
    data class Commentary(val text: String) : AmbientClientEvent
    data class Thinking(val text: String) : AmbientClientEvent
    data class FunctionOutput(val callId: String, val output: String) : AmbientClientEvent
    data object ContinueResponse : AmbientClientEvent
}

object AmbientWire {
    val allowed: Set<String> = setOf(
        "session.input_audio.append",
        "session.close",
        "session.input_audio.mute",
        "session.input_audio.unmute",
        "session.commentary.append",
        "session.thinking.append",
        "session.instructions.append",
        "response.item.create",
        "response.create",
    )

    fun forbidden(type: String): Boolean = type == "session.start" || type == "session.update"

    fun encode(event: AmbientClientEvent, eventId: String = newEventId()): String? {
        val objectJson: JsonObject = when (event) {
            is AmbientClientEvent.Audio -> {
                val even = event.pcm.size - event.pcm.size % 2
                if (even < 2 || even > AmbientProtocol.MAX_AUDIO_BYTES) return null
                val frame = if (even == event.pcm.size) event.pcm else event.pcm.copyOf(even)
                buildJsonObject {
                    put("type", "session.input_audio.append")
                    put("event_id", eventId)
                    put("audio", Base64.getEncoder().encodeToString(frame))
                }
            }
            AmbientClientEvent.Close -> pair("session.close", eventId)
            AmbientClientEvent.Mute -> pair("session.input_audio.mute", eventId)
            AmbientClientEvent.Unmute -> pair("session.input_audio.unmute", eventId)
            is AmbientClientEvent.Commentary -> {
                val clipped = clip(event.text) ?: return null
                buildJsonObject {
                    put("type", "session.commentary.append")
                    put("event_id", eventId)
                    put("delegation_id", JsonNull)
                    put("content", clipped)
                }
            }
            is AmbientClientEvent.Thinking -> {
                val clipped = clip(event.text) ?: return null
                buildJsonObject {
                    put("type", "session.thinking.append")
                    put("event_id", eventId)
                    put("delegation_id", JsonNull)
                    put("content", clipped)
                }
            }
            is AmbientClientEvent.FunctionOutput -> {
                if (event.callId.isEmpty()) return null
                if (event.output.toByteArray(Charsets.UTF_8).size > AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES) {
                    return null
                }
                buildJsonObject {
                    put("type", "response.item.create")
                    put("event_id", eventId)
                    put("item", buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", event.callId)
                        put("output", event.output)
                    })
                }
            }
            AmbientClientEvent.ContinueResponse -> pair("response.create", eventId)
        }
        val type = jsonString(objectJson["type"]) ?: return null
        if (!allowed.contains(type) || forbidden(type)) return null
        return objectJson.toString()
    }

    fun eventType(text: String): String? = jsonObject(text)?.let { jsonString(it["type"]) }

    fun clip(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val out = StringBuilder()
        var used = 0
        var index = 0
        while (index < trimmed.length) {
            val codePoint = trimmed.codePointAt(index)
            val next = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + next > AmbientProtocol.MAX_APPEND_BYTES) break
            out.appendCodePoint(codePoint)
            used += next
            index += Character.charCount(codePoint)
        }
        return out.toString().ifEmpty { null }
    }

    fun newEventId(): String = "evt_" + UUID.randomUUID().toString().replace("-", "")

    fun errorOutput(code: String, message: String): String =
        buildJsonObject {
            put("ok", false)
            put("error", code)
            put("message", message)
        }.toString()

    private fun pair(type: String, eventId: String): JsonObject = buildJsonObject {
        put("type", type)
        put("event_id", eventId)
    }
}

data class AmbientTranscriptFragment(
    val text: String,
    val eventId: String = "",
    val startMs: Int? = null,
    val endMs: Int? = null,
)

sealed interface AmbientServerEvent {
    data class Started(val sessionId: String) : AmbientServerEvent
    class OutputAudio(val pcm: ByteArray) : AmbientServerEvent {
        override fun equals(other: Any?): Boolean = other is OutputAudio && pcm.contentEquals(other.pcm)
        override fun hashCode(): Int = pcm.contentHashCode()
    }
    data class InputTranscript(val fragment: AmbientTranscriptFragment) : AmbientServerEvent
    data class OutputTranscript(val fragment: AmbientTranscriptFragment) : AmbientServerEvent
    data class Hosted(val inbound: AmbientHostedInbound) : AmbientServerEvent
    data class VoiceUsage(val seconds: Double) : AmbientServerEvent
    data class Closed(val seconds: Double?) : AmbientServerEvent
    data class PortalUsage(
        val requestId: String,
        val estimatedUsd: Double,
        val confirmed: Boolean,
        val status: String,
    ) : AmbientServerEvent
    data object UsageUnconfirmed : AmbientServerEvent
    data object InvalidAudio : AmbientServerEvent
    data class Failed(val code: String) : AmbientServerEvent
    data class Ignored(val type: String) : AmbientServerEvent
}

sealed interface AmbientHostedInbound {
    data class FunctionCall(val callId: String, val name: String, val arguments: String) : AmbientHostedInbound
    data class Lifecycle(val lifecycle: AmbientLifecycle) : AmbientHostedInbound
    data class Ignored(val type: String) : AmbientHostedInbound
}

data class AmbientLifecycle(
    val type: String,
    val responseId: String,
    val model: String,
    val outputCount: Int,
    val usage: AmbientParsedUsage?,
)

data class AmbientParsedUsage(
    val inputTokens: Int,
    val cachedRead: Int,
    val cacheWrite: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val model: String,
    val responseId: String,
    val serviceTier: String,
    val fieldsLegal: Boolean,
)

object AmbientEventDecoder {
    fun decode(text: String): AmbientServerEvent {
        val json = jsonObject(text) ?: return AmbientServerEvent.Ignored("bad")
        val type = jsonString(json["type"]).orEmpty()
        return when (type) {
            "usage" -> portal(json)
            "error", "session.error" -> error(json)
            "session.started" -> {
                val session = asObject(json["session"])
                val id = jsonString(json["session_id"]) ?: jsonString(session?.get("id")).orEmpty()
                AmbientServerEvent.Started(id)
            }
            "session.output_audio.delta" -> AmbientServerEvent.OutputAudio(pcm(json))
            "session.input_transcript.delta" -> AmbientServerEvent.InputTranscript(fragment(json))
            "session.output_transcript.delta" -> AmbientServerEvent.OutputTranscript(fragment(json))
            "response.event" -> AmbientServerEvent.Hosted(hosted(json))
            "session.usage.updated" -> {
                val seconds = seconds(json) ?: return AmbientServerEvent.Ignored(type)
                AmbientServerEvent.VoiceUsage(seconds)
            }
            "session.closed" -> AmbientServerEvent.Closed(optionalSeconds(json))
            else -> AmbientServerEvent.Ignored(type)
        }
    }

    fun errorCode(json: JsonObject): String {
        jsonString(json["code"])?.takeIf { it.isNotEmpty() }?.let { return it }
        val nested = asObject(json["error"])
        jsonString(nested?.get("code"))?.takeIf { it.isNotEmpty() }?.let { return it }
        return "error"
    }

    fun portalStatus(value: kotlinx.serialization.json.JsonElement?, confirmed: Boolean): String {
        val status = jsonString(value)
        if (status == "confirmed" || status == "provisional" || status == "anomaly") {
            return if (confirmed) "confirmed" else status
        }
        return if (confirmed) "confirmed" else "provisional"
    }

    /** Closing or an already-seen close does not fail the run. invalid_audio is not a failure. */
    fun errorDisposition(code: String, closing: Boolean, closeSeen: Boolean): AmbientErrorDisposition {
        if (code == "invalid_audio" || code == "usage_unconfirmed") return AmbientErrorDisposition.IGNORE
        if (closing || closeSeen) return AmbientErrorDisposition.IGNORE
        if (code == "upstream_slow") return AmbientErrorDisposition.SOFT
        return AmbientErrorDisposition.FAIL
    }

    private fun portal(json: JsonObject): AmbientServerEvent {
        val cost = jsonFinite(json["estimated_cost_usd"]) ?: return AmbientServerEvent.UsageUnconfirmed
        val requestId = jsonString(json["request_id"])?.takeIf { it.isNotEmpty() }
            ?: return AmbientServerEvent.UsageUnconfirmed
        val confirmed = jsonBoolean(json["confirmed"]) == true
        return AmbientServerEvent.PortalUsage(
            requestId = requestId,
            estimatedUsd = cost,
            confirmed = confirmed,
            status = portalStatus(json["status"], confirmed),
        )
    }

    private fun error(json: JsonObject): AmbientServerEvent {
        val code = errorCode(json)
        return when (code) {
            "usage_unconfirmed" -> AmbientServerEvent.UsageUnconfirmed
            "invalid_audio" -> AmbientServerEvent.InvalidAudio
            else -> AmbientServerEvent.Failed(code)
        }
    }

    private fun hosted(envelope: JsonObject): AmbientHostedInbound {
        val inner = asObject(envelope["event"]) ?: return AmbientHostedInbound.Ignored("missing_event")
        val type = jsonString(inner["type"]).orEmpty()
        return when (type) {
            "response.output_item.done" -> {
                val call = functionCall(inner) ?: return AmbientHostedInbound.Ignored(type)
                AmbientHostedInbound.FunctionCall(call.first, call.second, call.third)
            }
            "response.completed", "response.failed", "response.incomplete" ->
                AmbientHostedInbound.Lifecycle(lifecycle(type, inner))
            else -> AmbientHostedInbound.Ignored(type)
        }
    }

    private fun functionCall(inner: JsonObject): Triple<String, String, String>? {
        val item = asObject(inner["item"]) ?: inner
        if (jsonString(item["type"]) != "function_call") return null
        val callId = jsonString(item["call_id"]).orEmpty()
        val name = jsonString(item["name"]).orEmpty()
        if (callId.isEmpty() || name.isEmpty()) return null
        return Triple(callId, name, argumentsText(item["arguments"]))
    }

    private fun argumentsText(value: kotlinx.serialization.json.JsonElement?): String {
        if (value is kotlinx.serialization.json.JsonPrimitive && value.isString) return value.content
        if (value is JsonObject || value is JsonArray) return value.toString()
        return ""
    }

    private fun lifecycle(type: String, inner: JsonObject): AmbientLifecycle {
        val response = asObject(inner["response"]) ?: JsonObject(emptyMap())
        val output = asArray(response["output"])
        val payload = if (response.isEmpty()) inner else response
        return AmbientLifecycle(
            type = type,
            responseId = jsonString(response["id"]) ?: jsonString(inner["response_id"]).orEmpty(),
            model = jsonString(response["model"]).orEmpty(),
            outputCount = output?.size ?: 0,
            usage = AmbientUsageParser.parse(payload),
        )
    }

    private fun pcm(json: JsonObject): ByteArray {
        val raw = jsonString(json["delta"]) ?: jsonString(json["audio"]).orEmpty()
        if (raw.isEmpty()) return ByteArray(0)
        return try {
            Base64.getDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }
    }

    private fun fragment(json: JsonObject): AmbientTranscriptFragment {
        val text = jsonString(json["delta"]) ?: jsonString(json["text"]).orEmpty()
        return AmbientTranscriptFragment(
            text = text,
            eventId = jsonString(json["event_id"]).orEmpty(),
            startMs = jsonMillis(json["start_ms"]),
            endMs = jsonMillis(json["end_ms"]),
        )
    }

    private fun seconds(json: JsonObject): Double? {
        jsonFinite(json["seconds"])?.let { return it }
        val usage = asObject(json["usage"]) ?: return null
        return jsonFinite(usage["seconds"])
    }

    private fun optionalSeconds(json: JsonObject): Double? {
        val nested = asObject(json["usage"])
        if (json["seconds"] == null && nested?.get("seconds") == null) return null
        return seconds(json)
    }
}

enum class AmbientErrorDisposition {
    IGNORE,
    SOFT,
    FAIL,
}
