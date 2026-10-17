package com.yage.opencode_client.ambient.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object AmbientDiagnostics {
    val blocked: List<String> = listOf(
        "password",
        "ticket",
        "secret",
        "bearer",
        "authorization",
        "api_key",
        "apikey",
        "private key",
    )

    fun allows(text: String): Boolean {
        val lowered = text.lowercase()
        return blocked.none { lowered.contains(it) }
    }
}

object AmbientToolJson {
    fun error(code: String, message: String, httpStatus: Int? = null): String {
        val objectJson = buildJsonObject {
            put("ok", false)
            put("error", code)
            put("message", message)
            if (httpStatus != null) put("http_status", httpStatus)
        }
        return objectJson.toString()
    }

    fun accepted(sessionId: String, messageId: String, model: String?): String = buildJsonObject {
        put("ok", true)
        put("status", "accepted")
        put("sessionID", sessionId)
        put("messageID", messageId)
        if (!model.isNullOrEmpty()) put("model", model)
    }.toString()

    fun stopped(): String = buildJsonObject {
        put("ok", true)
        put("status", "cancelled")
        put("message", "已确认停止，任务不在执行")
    }.toString()

    fun json(objectJson: JsonObject): String = objectJson.toString()
}

object AmbientFunctionOutput {
    fun clipUtf8(text: String, limit: Int): Pair<String, Boolean> {
        if (text.toByteArray(StandardCharsets.UTF_8).size <= limit) return text to false
        val out = StringBuilder()
        var used = 0
        for (ch in text) {
            val next = ch.toString().toByteArray(StandardCharsets.UTF_8).size
            if (used + next > limit) break
            out.append(ch)
            used += next
        }
        return out.toString() to true
    }

    /** Keeps a payload that fits. Oversized text becomes a small structured error instead of being dropped. */
    fun ensureSendable(payload: String): String {
        if (payload.toByteArray(StandardCharsets.UTF_8).size <= AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES) {
            return payload
        }
        return AmbientToolJson.error(
            AmbientToolCodes.OUTPUT_TOO_LARGE,
            AmbientToolCodes.OUTPUT_TOO_LARGE_MESSAGE,
        )
    }
}

object AmbientPromptWire {
    fun sessionCreate(title: String?): String =
        if (title == null) "{}" else buildJsonObject { put("title", title) }.toString()

    fun sessionPageQuery(cursor: Long?): List<Pair<String, String>> {
        val items = mutableListOf(
            "roots" to "true",
            "archived" to "true",
            "limit" to AmbientProtocol.ROOT_PAGE_LIMIT.toString(),
        )
        if (cursor != null) items.add("cursor" to cursor.toString())
        return items
    }

    /**
     * `x-next-cursor` is `String(time.updated)`: decimal milliseconds, wider than Int.
     * Only a canonical non-negative Long is accepted, so the next request can send that exact text.
     * Leading zeros, signs, fractions, and values past Long are malformed.
     */
    fun listCursor(header: String): Long? {
        if (header.isEmpty() || header.length > 19 || header.any { it < '0' || it > '9' }) return null
        if (header.length > 1 && header[0] == '0') return null
        val value = header.toLongOrNull() ?: return null
        return if (value.toString() == header) value else null
    }

    fun startData(messageId: String, text: String, agent: String, providerId: String, modelId: String): String =
        buildJsonObject {
            put("messageID", messageId)
            put("parts", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("agent", agent)
            put("model", buildJsonObject {
                put("providerID", providerId)
                put("modelID", modelId)
            })
        }.toString()

    /** Continuation must not carry a model override. */
    fun continueData(messageId: String, text: String, agent: String?, variant: String?): String =
        buildJsonObject {
            put("messageID", messageId)
            put("parts", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            val named = agent?.trim().orEmpty()
            if (named.isNotEmpty()) put("agent", named)
            if (variant != null) put("variant", variant)
        }.toString()
}

object AmbientArchiveWire {
    fun body(archived: Long): String = buildJsonObject {
        put("time", buildJsonObject { put("archived", archived) })
    }.toString()

    fun requiredSessionId(objectJson: JsonObject): String? {
        val value = objectJson["sessionID"] ?: return null
        if (value is JsonNull) return null
        val text = jsonString(value) ?: return null
        val trimmed = text.trim()
        return trimmed.ifEmpty { null }
    }

    fun isArchived(archived: Long?): Boolean = archived != null && archived > 0L

    fun isUnarchived(archived: Long?): Boolean = archived == null || archived == 0L

    fun archiveMillis(nowEpochMillis: Long): Long = if (nowEpochMillis > 0L) nowEpochMillis else 1L

    fun ids(root: String, children: Map<String, List<String>>, parentFirst: Boolean): List<String> {
        val seen = HashSet<String>()
        fun collect(id: String): List<String> {
            if (!seen.add(id)) return emptyList()
            val nested = (children[id] ?: emptyList()).flatMap { collect(it) }
            return if (parentFirst) listOf(id) + nested else nested + listOf(id)
        }
        return collect(root)
    }

    fun partial(
        sessionId: String,
        title: String,
        archived: Boolean,
        verified: List<String>,
        failed: List<String>,
        pending: List<String>,
        error: String,
        message: String,
    ): String {
        var verifiedIds = verified
        var failedIds = failed
        var pendingIds = pending
        var truncated = false
        var last: String
        while (true) {
            last = renderPartial(
                sessionId, title, archived,
                verified.size, failed.size, pending.size,
                verifiedIds, failedIds, pendingIds,
                error, message, truncated,
            )
            if (last.toByteArray(StandardCharsets.UTF_8).size <= AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES) {
                return last
            }
            if (verifiedIds.isEmpty() && failedIds.isEmpty() && pendingIds.isEmpty()) {
                return AmbientFunctionOutput.ensureSendable(last)
            }
            truncated = true
            when {
                pendingIds.isNotEmpty() -> pendingIds = pendingIds.dropLast(1)
                failedIds.isNotEmpty() -> failedIds = failedIds.dropLast(1)
                else -> verifiedIds = verifiedIds.dropLast(1)
            }
        }
    }

    private fun renderPartial(
        sessionId: String,
        title: String,
        archived: Boolean,
        verified: Int,
        failed: Int,
        pending: Int,
        verifiedIds: List<String>,
        failedIds: List<String>,
        pendingIds: List<String>,
        error: String,
        message: String,
        truncated: Boolean,
    ): String = buildJsonObject {
        put("ok", false)
        put("status", "partial")
        put("error", error)
        put("message", message)
        put("sessionID", sessionId)
        put("title", title)
        put("archived", archived)
        put("snapshot", true)
        put("verified", verified)
        put("failed", failed)
        put("pending", pending)
        put("verifiedIDs", buildJsonArray { verifiedIds.forEach { add(JsonPrimitive(it)) } })
        put("failedIDs", buildJsonArray { failedIds.forEach { add(JsonPrimitive(it)) } })
        put("pendingIDs", buildJsonArray { pendingIds.forEach { add(JsonPrimitive(it)) } })
        if (truncated) put("truncated", true)
    }.toString()
}

data class AmbientListItem(
    val id: String,
    val title: String,
    val directory: String,
    val status: String,
    val updated: Long,
    val archivedAt: Long,
    val parentId: String,
)

data class AmbientListPage(val rows: List<AmbientListItem>, val nextCursor: Long?)

data class AmbientListWindow(
    val records: List<AmbientListItem>,
    val skip: Int,
    val count: Int,
    val returned: Int,
    val hasMore: Boolean?,
    val nextSkip: Int?,
    val partial: Boolean,
    val fetchMore: Boolean,
)

object AmbientSessionWalk {
    fun include(item: AmbientListItem, archivedOnly: Boolean): Boolean {
        if (item.parentId.trim().isNotEmpty()) return false
        if (archivedOnly) return item.archivedAt > 0L
        return true
    }

    fun walk(
        pages: List<AmbientListPage>,
        archivedOnly: Boolean,
        skip: Int,
        count: Int,
        maxPages: Int = AmbientProtocol.ROOT_PAGE_MAX,
    ): AmbientListWindow {
        val seen = HashSet<String>()
        val qualified = ArrayList<AmbientListItem>()
        var cursor: Long? = null
        var fetched = 0
        var exhausted = false
        var stalled = false
        for (page in pages.take(maxPages)) {
            fetched += 1
            if (cursor != null && page.nextCursor == cursor) stalled = true
            for (row in page.rows) {
                if (seen.add(row.id) && include(row, archivedOnly)) qualified.add(row)
            }
            if (qualified.size >= skip + count + 1) break
            if (stalled) break
            if (page.nextCursor == null) {
                exhausted = true
                break
            }
            cursor = page.nextCursor
        }
        val need = skip + count + 1
        val cursorRemains = cursor != null && !exhausted && !stalled
        val fetchMore = cursorRemains && qualified.size < need && fetched < maxPages
        val partial = !exhausted && qualified.size < need && !fetchMore
        val start = minOf(maxOf(skip, 0), qualified.size)
        val end = minOf(start + count, qualified.size)
        val records = qualified.subList(start, end).toList()
        val known = !partial && !fetchMore
        val hasMore: Boolean? = if (known) qualified.size > skip + count else null
        return AmbientListWindow(
            records = records,
            skip = skip,
            count = count,
            returned = records.size,
            hasMore = hasMore,
            nextSkip = if (hasMore == true) skip + count else null,
            partial = partial,
            fetchMore = fetchMore,
        )
    }
}

data class AmbientHistoryDraft(
    val id: String,
    val role: String,
    val createdMs: Long?,
    val completedMs: Long?,
    val text: String,
    val synthetic: Boolean,
)

object AmbientHistoryText {
    const val TEXT_BYTE_LIMIT = 8_000

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun iso(epochMillis: Long): String = isoFormatter.format(Instant.ofEpochMilli(epochMillis))

    fun render(
        sessionId: String,
        title: String,
        directory: String,
        drafts: List<AmbientHistoryDraft>,
        skip: Int,
        count: Int,
        hasOlder: Boolean,
    ): String {
        val ordered = drafts.sortedWith { lhs, rhs ->
            when {
                lhs.createdMs != null && rhs.createdMs != null && lhs.createdMs != rhs.createdMs ->
                    lhs.createdMs.compareTo(rhs.createdMs)
                lhs.createdMs == null && rhs.createdMs != null -> -1
                lhs.createdMs != null && rhs.createdMs == null -> 1
                else -> lhs.id.compareTo(rhs.id)
            }
        }
        val end = ordered.size - maxOf(skip, 0)
        val slice: List<AmbientHistoryDraft>
        val hasMore: Boolean?
        val nextSkip: Int?
        if (end <= 0) {
            slice = emptyList()
            hasMore = if (hasOlder) null else false
            nextSkip = null
        } else {
            val start = maxOf(0, end - count)
            slice = ordered.subList(start, end)
            if (start > 0 || hasOlder) {
                hasMore = true
                nextSkip = skip + slice.size
            } else {
                hasMore = false
                nextSkip = null
            }
        }
        return AmbientFunctionOutput.ensureSendable(
            bounded(sessionId, title, directory, slice, skip, count, hasMore, nextSkip),
        )
    }

    private fun bounded(
        sessionId: String,
        title: String,
        directory: String,
        messages: List<AmbientHistoryDraft>,
        skip: Int,
        count: Int,
        hasMore: Boolean?,
        nextSkip: Int?,
    ): String {
        var limit = TEXT_BYTE_LIMIT
        var drop = 0
        var last = AmbientToolJson.error(AmbientToolCodes.FAILED, AmbientToolCodes.HISTORY_FAILED_MESSAGE)
        while (drop <= messages.size) {
            val kept = messages.drop(drop)
            val json = payload(sessionId, title, directory, kept, skip, count, hasMore, nextSkip, limit, drop > 0)
            last = json
            if (json.toByteArray(StandardCharsets.UTF_8).size <= AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES) return json
            if (limit > 160) {
                limit /= 2
                continue
            }
            drop += 1
            limit = TEXT_BYTE_LIMIT
        }
        return last
    }

    private fun payload(
        sessionId: String,
        title: String,
        directory: String,
        messages: List<AmbientHistoryDraft>,
        skip: Int,
        count: Int,
        hasMore: Boolean?,
        nextSkip: Int?,
        textLimit: Int,
        dropped: Boolean,
    ): String {
        var truncated = dropped
        val rows = messages.map { item ->
            val clipped = AmbientFunctionOutput.clipUtf8(item.text, textLimit)
            if (clipped.second) truncated = true
            buildJsonObject {
                put("id", item.id)
                put("role", item.role)
                put("created_at", item.createdMs?.let { JsonPrimitive(iso(it)) } ?: JsonNull)
                put("created_at_ms", item.createdMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("completed_at", item.completedMs?.let { JsonPrimitive(iso(it)) } ?: JsonNull)
                put("text", clipped.first)
                if (item.synthetic) put("synthetic", true)
                if (clipped.second) put("truncated", true)
            }
        }
        return buildJsonObject {
            put("ok", true)
            put("session", buildJsonObject {
                put("id", sessionId)
                put("title", AmbientFunctionOutput.clipUtf8(title, 240).first.ifEmpty { sessionId })
                put("directory", AmbientFunctionOutput.clipUtf8(directory, 240).first)
            })
            put("messages", JsonArray(rows))
            put("pagination", buildJsonObject {
                put("skip", skip)
                put("count", count)
                put("returned", rows.size)
                if (hasMore != null) put("hasMore", hasMore)
                if (nextSkip != null) put("nextSkip", nextSkip)
                if (truncated) put("partial", true)
            })
        }.toString()
    }
}

object AmbientListOutput {
    fun bound(objectJson: JsonObject): String {
        val rows = stringRows(objectJson["records"]) ?: stringRows(objectJson["sessions"])
            ?: return AmbientFunctionOutput.ensureSendable(objectJson.toString())
        var limit = 512
        var dropped = 0
        var last = AmbientToolJson.error(AmbientToolCodes.FAILED, AmbientToolCodes.LIST_FAILED_MESSAGE)
        while (true) {
            val kept = if (dropped < rows.size) rows.dropLast(dropped) else emptyList()
            val clipped = kept.map { row ->
                row.mapValues { (key, value) ->
                    if (key == "title" || key == "directory" || key == "status") {
                        AmbientFunctionOutput.clipUtf8(value, limit).first
                    } else {
                        value
                    }
                }
            }
            val copy = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
            objectJson.forEach { (key, value) -> copy[key] = value }
            val array = buildJsonArray {
                clipped.forEach { row ->
                    add(buildJsonObject { row.forEach { (key, value) -> put(key, value) } })
                }
            }
            copy["records"] = array
            copy["sessions"] = array
            val pagination = (asObject(copy["pagination"])?.toMutableMap() ?: linkedMapOf()).toMutableMap()
            pagination["returned"] = JsonPrimitive(clipped.size)
            if (dropped > 0 || limit < 512) pagination["partial"] = JsonPrimitive(true)
            copy["pagination"] = JsonObject(pagination)
            val json = JsonObject(copy).toString()
            last = json
            if (json.toByteArray(StandardCharsets.UTF_8).size <= AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES) return json
            if (limit > 32) {
                limit /= 2
                continue
            }
            if (dropped >= rows.size) return AmbientFunctionOutput.ensureSendable(last)
            dropped += 1
            limit = 512
        }
    }

    private fun stringRows(element: kotlinx.serialization.json.JsonElement?): List<Map<String, String>>? {
        val array = element as? JsonArray ?: return null
        val rows = ArrayList<Map<String, String>>(array.size)
        for (item in array) {
            val objectJson = item as? JsonObject ?: return null
            val row = LinkedHashMap<String, String>()
            for ((key, value) in objectJson) {
                val text = jsonString(value) ?: return null
                row[key] = text
            }
            rows.add(row)
        }
        return rows
    }
}
