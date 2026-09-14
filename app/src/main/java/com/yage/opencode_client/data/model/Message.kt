package com.yage.opencode_client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

@Serializable
data class Message(
    val id: String,
    @SerialName("sessionID") val sessionId: String? = null,
    val role: String,
    @SerialName("parentID") val parentId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    @SerialName("modelID") val modelId: String? = null,
    val model: ModelInfo? = null,
    val agent: String? = null,
    val error: MessageError? = null,
    val time: TimeInfo? = null,
    val finish: String? = null,
    val tokens: TokenInfo? = null,
    val cost: Double? = null
) {
    @Serializable
    data class ModelInfo(
        @SerialName("providerID") val providerId: String,
        @SerialName("modelID") val modelId: String
    )

    @Serializable
    data class TokenInfo(
        val total: Int? = null,
        val input: Int? = null,
        val output: Int? = null,
        val reasoning: Int? = null,
        val cache: CacheInfo? = null
    ) {
        @Serializable
        data class CacheInfo(
            val read: Int? = null,
            val write: Int? = null
        )
    }

    @Serializable
    data class TimeInfo(
        val created: Long? = null,
        val completed: Long? = null
    )

    @Serializable
    data class MessageError(
        val name: String? = null,
        val data: JsonObject? = null
    ) {
        val message: String?
            get() = data?.let { obj ->
                (obj["message"] as? JsonPrimitive)?.content
                    ?: (obj["error"] as? JsonPrimitive)?.content
            }
    }

    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"

    val resolvedModel: ModelInfo?
        get() = model ?: (if (providerId != null && modelId != null) {
            ModelInfo(providerId, modelId)
        } else null)

    /** Tokens the model actually emitted: visible output plus reasoning.
     *  Input and cache reads/writes are not generated tokens. */
    val generatedTokens: Int
        get() = (tokens?.output ?: 0) + (tokens?.reasoning ?: 0)

    /** Step wall-clock in seconds (created -> completed). Null while the step
     *  is incomplete (streaming) or when either bound is missing/non-positive. */
    val stepSeconds: Double?
        get() {
            val created = time?.created ?: return null
            val completed = time?.completed ?: return null
            val ms = completed - created
            if (ms <= 0) return null
            return ms / 1000.0
        }
}

/** Numerator and denominator for one step's throughput, already tool-adjusted.
 *  Shared by the per-message footer and the session aggregate so the two
 *  displays cannot drift. */
data class ThroughputComponents(
    val generatedTokens: Int,
    val effectiveSeconds: Double
) {
    val throughput: Double
        get() = generatedTokens / effectiveSeconds
}

@Serializable
data class MessageWithParts(
    val info: Message,
    val parts: List<Part> = emptyList()
) {
    /** Wall-clock this step spent inside its tools (sum of state.time). Each
     *  part only contributes when both bounds exist and end > start, so the
     *  sum is always >= 0. Zero when the server recorded no tool timing. */
    val toolRunSeconds: Double
        get() = parts.sumOf { it.toolRunSeconds ?: 0.0 }

    /** Numerator/denominator pair for this step's throughput, with the step's
     *  tool execution removed from the denominator. Falls back to the raw step
     *  window when subtracting tool time would leave nothing positive, and
     *  returns null when there is no completed step window or no generated
     *  tokens (e.g. mid-stream). */
    fun throughputComponents(): ThroughputComponents? {
        val window = info.stepSeconds ?: return null
        val generated = info.generatedTokens
        if (generated <= 0) return null
        val adjusted = window - toolRunSeconds
        val seconds = if (adjusted > 0) adjusted else window
        if (seconds <= 0) return null
        return ThroughputComponents(generated, seconds)
    }

    /** Shared tokens/second formatter: integer when >= 10, one decimal below.
     *  Used by both the message footer and the Context sheet so the two can
     *  never drift. */
    companion object {
        fun throughputText(value: Double): String {
            val text = if (value >= 10) Math.round(value).toString() else String.format(Locale.US, "%.1f", value)
            return "$text t/s"
        }
    }
}

data class ComposerImageAttachment(
    val id: String,
    val filename: String,
    val mime: String,
    val dataUrl: String,
    val thumbnailData: ByteArray,
    val byteSize: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComposerImageAttachment) return false
        return id == other.id && filename == other.filename && mime == other.mime &&
            dataUrl == other.dataUrl && thumbnailData.contentEquals(other.thumbnailData) && byteSize == other.byteSize
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + dataUrl.hashCode()
        result = 31 * result + thumbnailData.contentHashCode()
        result = 31 * result + byteSize
        return result
    }
}

@Serializable
data class Part(
    val id: String,
    @SerialName("messageID") val messageId: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    @SerialName("callID") val callId: String? = null,
    val state: PartState? = null,
    val metadata: PartMetadata? = null,
    @Serializable(with = PartFilesSerializer::class) val files: List<FileChange>? = null,
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
    val source: String? = null
) {
    val isText: Boolean get() = type == "text"
    val isReasoning: Boolean get() = type == "reasoning"
    val isTool: Boolean get() = type == "tool"
    val isPatch: Boolean get() = type == "patch"
    val isFile: Boolean get() = type == "file"
    val isImageAttachment: Boolean get() = isFile && mime?.startsWith("image/") == true
    val isStepStart: Boolean get() = type == "step-start"
    val isStepFinish: Boolean get() = type == "step-finish"

    val stateDisplay: String? get() = state?.displayString
    val toolReason: String? get() = state?.title
    val toolInputSummary: String? get() = state?.inputSummary
    val toolOutput: String? get() = state?.output

    /** Wall-clock this tool itself ran, from state.time. Null unless both
     *  bounds exist and end > start (mirrors iOS Message.swift:464-469), so a
     *  running or malformed tool contributes nothing to throughput
     *  denominators. */
    val toolRunSeconds: Double?
        get() {
            val start = state?.runStartMillis ?: return null
            val end = state?.runEndMillis ?: return null
            if (end <= start) return null
            return (end - start) / 1000.0
        }

    val toolTodos: List<TodoItem>
        get() {
            if (!metadata?.todos.isNullOrEmpty()) return metadata?.todos ?: emptyList()
            if (!state?.todos.isNullOrEmpty()) return state?.todos ?: emptyList()
            return emptyList()
        }

    val filePathsForNavigation: List<String>
        get() {
            val result = mutableListOf<String>()
            files?.forEach { result.add(it.path.normalizePath()) }
            metadata?.path?.let { result.add(it.normalizePath()) }
            state?.pathFromInput?.let { 
                val normalized = it.normalizePath()
                if (normalized !in result) result.add(normalized)
            }
            return result
        }

    /** Paths that look like files (have extension), not directories. API often returns dirs in patch. */
    val filePathsForNavigationFiltered: List<String>
        get() = filePathsForNavigation.filter { path ->
            path.substringAfterLast("/").contains(".")
        }

    @Serializable
    data class FileChange(
        val path: String,
        val additions: Int? = null,
        val deletions: Int? = null,
        val status: String? = null
    )
}

/** Handles API returning files as either ["path1","path2"] or [{path,additions,...}]. */
private object PartFilesSerializer : KSerializer<List<Part.FileChange>?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PartFiles", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<Part.FileChange>?) {
        encoder.encodeSerializableValue(JsonArray.serializer(), kotlinx.serialization.json.buildJsonArray {
            value?.forEach { add(kotlinx.serialization.json.JsonObject(mapOf("path" to kotlinx.serialization.json.JsonPrimitive(it.path)))) }
        })
    }

    override fun deserialize(decoder: Decoder): List<Part.FileChange>? {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        if (element is JsonNull) return null
        val arr = element as? JsonArray ?: return null
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> Part.FileChange(path = item.content)
                is JsonObject -> {
                    val path = (item["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    Part.FileChange(
                        path = path,
                        additions = (item["additions"] as? JsonPrimitive)?.content?.toIntOrNull(),
                        deletions = (item["deletions"] as? JsonPrimitive)?.content?.toIntOrNull(),
                        status = (item["status"] as? JsonPrimitive)?.content
                    )
                }
                else -> null
            }
        }
    }
}

@Serializable(with = PartStateSerializer::class)
data class PartState(
    val displayString: String,
    val title: String? = null,
    val inputSummary: String? = null,
    val output: String? = null,
    val pathFromInput: String? = null,
    val todos: List<TodoItem>? = null,
    /** Tool execution bounds from state.time, in epoch milliseconds. Doubles
     *  because the server may emit Int or Double-encoded numbers. Null while
     *  running or when the server recorded no timing. */
    val runStartMillis: Double? = null,
    val runEndMillis: Double? = null
)

object PartStateSerializer : kotlinx.serialization.KSerializer<PartState> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("PartState", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: PartState) {
        encoder.encodeString(value.displayString)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): PartState {
        val input = decoder.decodeSerializableValue(JsonElement.serializer())
        return parsePartState(input)
    }

    private fun parsePartState(element: JsonElement): PartState {
        return when (element) {
            is JsonPrimitive -> PartState(element.content)
            is JsonObject -> {
                val status = (element["status"] as? JsonPrimitive)?.content
                    ?: (element["title"] as? JsonPrimitive)?.content
                    ?: "…"

                var title: String? = (element["title"] as? JsonPrimitive)?.content
                var output: String? = (element["output"] as? JsonPrimitive)?.content

                val metadata = element["metadata"] as? JsonObject
                if (metadata != null) {
                    if (output == null) output = (metadata["output"] as? JsonPrimitive)?.content
                    if (title == null) title = (metadata["description"] as? JsonPrimitive)?.content
                }

                var inputSummary: String? = null
                var pathFromInput: String? = null
                var todos: List<TodoItem>? = null
                var runStartMillis: Double? = null
                var runEndMillis: Double? = null

                val timeObj = element["time"] as? JsonObject
                if (timeObj != null) {
                    runStartMillis = (timeObj["start"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                    runEndMillis = (timeObj["end"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                }

                val inputObj = element["input"]
                if (inputObj is JsonPrimitive) {
                    inputSummary = inputObj.content
                } else if (inputObj is JsonObject) {
                    inputSummary = (inputObj["command"] as? JsonPrimitive)?.content
                        ?: (inputObj["path"] as? JsonPrimitive)?.content

                    val todosObj = inputObj["todos"]
                    if (todosObj is JsonArray) {
                        todos = parseTodos(todosObj)
                    }

                    var pathVal = (inputObj["path"] as? JsonPrimitive)?.content
                        ?: (inputObj["file_path"] as? JsonPrimitive)?.content
                        ?: (inputObj["filePath"] as? JsonPrimitive)?.content

                    if (pathVal == null) {
                        val patchText = (inputObj["patchText"] as? JsonPrimitive)?.content
                        if (patchText != null) {
                            for (prefix in listOf("*** Add File: ", "*** Update File: ")) {
                                val idx = patchText.indexOf(prefix)
                                if (idx >= 0) {
                                    val rest = patchText.substring(idx + prefix.length)
                                    pathVal = rest.split("\n").firstOrNull()?.trim()
                                    break
                                }
                            }
                        }
                    }
                    pathFromInput = pathVal
                }

                if (todos == null && metadata != null) {
                    val todosObj = metadata["todos"]
                    if (todosObj is JsonArray) {
                        todos = parseTodos(todosObj)
                    }
                }

                PartState(
                    displayString = status,
                    title = title,
                    inputSummary = inputSummary,
                    output = output,
                    pathFromInput = pathFromInput,
                    todos = todos,
                    runStartMillis = runStartMillis,
                    runEndMillis = runEndMillis
                )
            }
            else -> PartState("…")
        }
    }

    private fun parseTodos(array: JsonArray): List<TodoItem> {
        return array.mapNotNull { item ->
            if (item is JsonObject) {
                try {
                    val content = (item["content"] as? JsonPrimitive)?.content?.trim() ?: "Untitled todo"
                    val status = (item["status"] as? JsonPrimitive)?.content
                        ?: if ((item["completed"] as? JsonPrimitive)?.content == "true") "completed"
                        else if ((item["isCompleted"] as? JsonPrimitive)?.content == "true") "completed"
                        else "pending"
                    val priority = (item["priority"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: "medium"
                    val id = (item["id"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: java.util.UUID.randomUUID().toString()
                    TodoItem(content, status, priority, id)
                } catch (e: Exception) { null }
            } else null
        }
    }
}

@Serializable
data class PartMetadata(
    val path: String? = null,
    val title: String? = null,
    val input: String? = null,
    val todos: List<TodoItem>? = null
)

private fun String.normalizePath(): String {
    return this.replace("\\", "/").trim('/')
}
