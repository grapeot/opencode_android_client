package com.yage.opencode_client.ambient.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.charset.StandardCharsets

object AmbientEndpoint {
    fun sessionUrl(base: String): String? {
        val portal = parseAbsolute(base) ?: return null
        val path = portal.path.trimEndOnce() + AmbientProtocol.SESSIONS_PATH
        return build(portal.scheme, portal.host, portal.explicitPort, path, query = null)
    }

    /**
     * Resolves [wsUrl] against the portal origin. The portal path, including `/backend`,
     * is not prefixed onto a relative socket path. The decoded `ticket` query must equal
     * [ticket] exactly.
     */
    fun resolveWebSocket(portalBase: String, wsUrl: String, ticket: String): String? {
        val resolved = resolveWebSocket(portalBase, wsUrl) ?: return null
        return if (ticketMatches(resolved, ticket)) resolved else null
    }

    fun resolveWebSocket(portalBase: String, wsUrl: String): String? {
        val raw = wsUrl.trim()
        if (raw.isEmpty() || raw.contains('\\') || raw.contains('@')) return null
        val portal = parseAbsolute(portalBase) ?: return null
        val lowered = raw.lowercase()
        val absolute = lowered.startsWith("wss://") || lowered.startsWith("ws://") ||
            lowered.startsWith("https://") || lowered.startsWith("http://")
        val socket = if (absolute) {
            val parsed = parseAbsolute(raw) ?: return null
            if (!queryIsTicketOnly(parsed.rawQuery, parsed.fragment, parsed.userInfo)) return null
            if (!allowsSocketScheme(portal.scheme, parsed.scheme)) return null
            if (!sameOrigin(portal, parsed)) return null
            parsed
        } else {
            if (!raw.startsWith("/") || raw.startsWith("//")) return null
            val relative = parseRelative(raw) ?: return null
            if (!queryIsTicketOnly(relative.rawQuery, relative.fragment, userInfo = null)) return null
            relative.copy(
                scheme = portal.scheme,
                host = portal.host,
                explicitPort = portal.explicitPort,
            )
        }
        val wireScheme = if (secure(socket.scheme)) "wss" else "ws"
        if (wireScheme == "ws" && secure(portal.scheme)) return null
        return build(wireScheme, socket.host, socket.explicitPort, socket.path, socket.rawQuery)
    }

    fun ticketMatches(url: String, ticket: String): Boolean {
        if (ticket.isEmpty()) return false
        val parsed = parseLoose(url) ?: return false
        if (!queryIsTicketOnly(parsed.rawQuery, parsed.fragment, parsed.userInfo)) return false
        return decodedTicket(parsed.rawQuery) == ticket
    }

    fun acceptsCreateStatus(status: Int): Boolean = status == 200 || status == 201

    private fun allowsSocketScheme(portalScheme: String, socketScheme: String): Boolean {
        if (secure(portalScheme)) return secure(socketScheme)
        if (portalScheme != "http" && portalScheme != "ws") return false
        return socketScheme == "http" || socketScheme == "ws"
    }

    private fun secure(scheme: String): Boolean {
        val lowered = scheme.lowercase()
        return lowered == "https" || lowered == "wss"
    }

    private fun sameOrigin(portal: Parts, socket: Parts): Boolean {
        if (!portal.host.equals(socket.host, ignoreCase = true)) return false
        return originPort(portal) == originPort(socket)
    }

    private fun originPort(parts: Parts): Int? {
        if (parts.explicitPort != null) return parts.explicitPort
        return when (parts.scheme.lowercase()) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> null
        }
    }

    private fun queryIsTicketOnly(rawQuery: String?, fragment: String?, userInfo: String?): Boolean {
        if (fragment != null || userInfo != null) return false
        if (rawQuery.isNullOrEmpty()) return false
        val items = rawQuery.split('&')
        if (items.size != 1) return false
        val item = items[0]
        val eq = item.indexOf('=')
        if (eq <= 0) return false
        if (item.substring(0, eq) != "ticket") return false
        return item.substring(eq + 1).isNotEmpty()
    }

    private fun decodedTicket(rawQuery: String?): String? {
        if (rawQuery.isNullOrEmpty()) return null
        val item = rawQuery.substringAfter('=')
        return decodeQuery(item)
    }

    private fun parseAbsolute(raw: String): Parts? {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        val explicit = if (uri.port == -1) null else uri.port
        val path = uri.rawPath ?: ""
        return Parts(scheme, host, explicit, if (path.isEmpty()) "/" else path, uri.rawQuery, uri.fragment, uri.userInfo)
    }

    private fun parseRelative(raw: String): Parts? {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != null || uri.host != null || uri.userInfo != null) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/")) return null
        return Parts("https", "", null, path, uri.rawQuery, uri.fragment, null)
    }

    private fun parseLoose(raw: String): Parts? {
        return if (raw.startsWith("/")) parseRelative(raw) else parseAbsolute(raw)
    }

    private fun build(scheme: String, host: String, explicitPort: Int?, path: String, query: String?): String? {
        if (host.isEmpty()) return null
        val port = if (explicitPort != null) ":$explicitPort" else ""
        val queryPart = if (query.isNullOrEmpty()) "" else "?$query"
        val pathPart = if (path.isEmpty()) "/" else path
        return "${scheme.lowercase()}://$host$port$pathPart$queryPart"
    }

    private fun decodeQuery(raw: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val hex = raw.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value == null) {
                    out.write(c.code)
                    i += 1
                } else {
                    out.write(value)
                    i += 3
                }
            } else {
                val bytes = c.toString().toByteArray(StandardCharsets.UTF_8)
                out.write(bytes)
                i += 1
            }
        }
        return out.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun String.trimEndOnce(): String = if (endsWith("/")) dropLast(1) else this

    private data class Parts(
        val scheme: String,
        val host: String,
        val explicitPort: Int?,
        val path: String,
        val rawQuery: String?,
        val fragment: String?,
        val userInfo: String?,
    )
}

data class AmbientTicket(
    val portalSessionId: String,
    val ticket: String,
    val wsUrl: String,
    val expiry: String,
)

object AmbientTicketParser {
    fun parse(json: String): AmbientTicket? {
        val objectJson = try {
            ambientJson.parseToJsonElement(json) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val portal = text(objectJson, "portal_session_id") ?: return null
        val ticket = text(objectJson, "ticket") ?: return null
        val wsUrl = text(objectJson, "ws_url") ?: return null
        val expiry = text(objectJson, "expiry") ?: return null
        return AmbientTicket(portal, ticket, wsUrl, expiry)
    }

    private fun text(json: JsonObject, key: String): String? {
        val value = json[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (!value.isString) return null
        val text = value.content
        return text.ifEmpty { null }
    }
}

object AmbientCreateBody {
    val keys: Set<String> = setOf(
        "frontend_instructions",
        "backend_instructions",
        "history",
        "voice",
        "tools",
    )

    fun json(configuration: AmbientConfiguration): String {
        val history = historyPayload(configuration.history)
        val tools = toolsPayload(configuration.toolsJson)
        return buildJsonObject {
            put("frontend_instructions", configuration.instructions)
            put("backend_instructions", configuration.backendInstructions)
            put("history", history)
            put("voice", configuration.voice)
            put("tools", tools)
        }.toString()
    }

    fun historyPayload(turns: List<AmbientHistoryTurn>): JsonArray {
        if (turns.size > AmbientProtocol.MAX_HISTORY_MESSAGES) {
            throw AmbientRejected("history_messages")
        }
        var bytes = 0
        val rows = ArrayList<JsonObject>(turns.size)
        for (turn in turns) {
            if (rows.isNotEmpty()) bytes += 1
            bytes += turn.text.toByteArray(StandardCharsets.UTF_8).size
            if (bytes > AmbientProtocol.MAX_HISTORY_BYTES) throw AmbientRejected("history_bytes")
            val role = when (turn.role) {
                "assistant" -> "assistant"
                "developer" -> "developer"
                else -> "user"
            }
            val part = if (role == "assistant") "output_text" else "input_text"
            rows.add(buildJsonObject {
                put("type", "message")
                put("role", role)
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", part)
                        put("text", turn.text)
                    })
                })
            })
        }
        return JsonArray(rows)
    }

    fun toolsPayload(json: String): JsonArray {
        val parsed = try {
            ambientJson.parseToJsonElement(json)
        } catch (_: Exception) {
            throw AmbientRejected("tools")
        }
        return parsed as? JsonArray ?: throw AmbientRejected("tools")
    }
}
