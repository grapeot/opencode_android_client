package com.yage.opencode_client.ui.chat

import com.yage.opencode_client.data.model.Part

enum class TaskState {
    COMPLETED,
    ERROR,
    RUNNING;

    companion object {
        fun fromWire(value: String): TaskState? = when (value) {
            "completed" -> COMPLETED
            "error" -> ERROR
            "running" -> RUNNING
            else -> null
        }
    }
}

data class TaskNotification(
    val sessionID: String,
    val state: TaskState,
    val summary: String?,
    val resultText: String
) {
    val isFailed: Boolean get() = state == TaskState.ERROR
}

object TaskNotificationParser {
    const val LARGE_MESSAGE_LIMIT = 12_000
    private const val SHORT_SESSION_ID_LENGTH = 8
    private const val COMPLETED_PREFIX = "Background task completed:"
    private const val FAILED_PREFIX = "Background task failed:"

    fun parse(text: String?): TaskNotification? {
        if (text.isNullOrEmpty()) return null
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return null
        val open = findOpenTag(text, "task", start) ?: return null
        if (open != start) return null
        val openEnd = indexOfTagClose(text, open + 1) ?: return null
        val close = trailingEnvelopeClose(text) ?: return null
        if (close <= openEnd) return null

        val openTag = text.substring(open, openEnd + 1)
        val id = attribute(openTag, "id")?.takeIf { it.isNotEmpty() } ?: return null
        val state = attribute(openTag, "state")?.let { TaskState.fromWire(it) } ?: return null
        val inner = text.substring(openEnd + 1, close)
        val summary = elementText(inner, "summary", outermostClose = false)
        val resultText = resultBody(inner, state) ?: return null
        return TaskNotification(sessionID = id, state = state, summary = summary, resultText = resultText)
    }

    fun notificationFor(part: Part, text: String? = part.text): TaskNotification? {
        if (!part.isSyntheticText) return null
        return parse(text)
    }

    fun isTaskNotificationMessage(parts: List<Part>): Boolean =
        parts.any { notificationFor(it) != null }

    fun offersEditFromHere(isUser: Boolean, parts: List<Part>): Boolean =
        isUser && !isTaskNotificationMessage(parts)

    fun displayTitle(notification: TaskNotification): String {
        val stripped = stripStatusPrefix(notification.summary?.trim().orEmpty())
        if (stripped.isNotEmpty()) return stripped
        return shortSessionId(notification.sessionID)
    }

    fun largeMessagePreview(text: String): String? {
        if (text.length <= LARGE_MESSAGE_LIMIT) return null
        return text.take(LARGE_MESSAGE_LIMIT)
    }

    private fun stripStatusPrefix(summary: String): String {
        val prefix = when {
            summary.startsWith(COMPLETED_PREFIX) -> COMPLETED_PREFIX
            summary.startsWith(FAILED_PREFIX) -> FAILED_PREFIX
            else -> return summary
        }
        return summary.removePrefix(prefix).trim()
    }

    // 服务端会话 id 是 ses_ + 可时间排序前缀 + 随机后缀，区分度在尾部随机段，取前缀会把邻近会话压成同一串。
    private fun shortSessionId(id: String): String =
        if (id.length <= SHORT_SESSION_ID_LENGTH) id else "…" + id.takeLast(SHORT_SESSION_ID_LENGTH)

    private fun resultBody(inner: String, state: TaskState): String? {
        val preferred = if (state == TaskState.ERROR) "task_error" else "task_result"
        val fallback = if (preferred == "task_error") "task_result" else "task_error"
        return elementText(inner, preferred, outermostClose = true)
            ?: elementText(inner, fallback, outermostClose = true)
    }

    private fun elementText(inner: String, tag: String, outermostClose: Boolean): String? {
        val open = findOpenTag(inner, tag, 0) ?: return null
        val openEnd = indexOfTagClose(inner, open + 1) ?: return null
        val closeToken = "</$tag>"
        val close = if (outermostClose) {
            inner.lastIndexOf(closeToken)
        } else {
            inner.indexOf(closeToken, openEnd + 1)
        }
        if (close < openEnd) return null
        // 信封用 \n join，标签与内文之间各恰好一对换行不属于正文；只剥各一个，不 trim。
        return inner.substring(openEnd + 1, close).removePrefix("\n").removeSuffix("\n")
    }

    private fun trailingEnvelopeClose(text: String): Int? {
        var end = text.length - 1
        while (end >= 0 && text[end].isWhitespace()) end--
        val close = "</task>"
        val start = end - close.length + 1
        if (start < 0) return null
        if (!text.regionMatches(start, close, 0, close.length)) return null
        return start
    }

    private fun findOpenTag(text: String, tag: String, from: Int): Int? {
        val needle = "<$tag"
        var search = from
        while (search < text.length) {
            val idx = text.indexOf(needle, search)
            if (idx < 0) return null
            val after = idx + needle.length
            if (after < text.length) {
                val boundary = text[after]
                if (boundary == '>' || boundary.isWhitespace()) return idx
            }
            search = idx + 1
        }
        return null
    }

    private fun indexOfTagClose(text: String, from: Int): Int? {
        var quote: Char? = null
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return null
    }

    private fun attribute(openTag: String, name: String): String? {
        val key = "$name="
        var search = 0
        while (search < openTag.length) {
            val idx = openTag.indexOf(key, search)
            if (idx < 0) return null
            val beforeOk = idx == 0 || openTag[idx - 1].isWhitespace()
            if (!beforeOk) {
                search = idx + 1
                continue
            }
            val valueStart = idx + key.length
            if (valueStart >= openTag.length) return null
            val quote = openTag[valueStart]
            if (quote == '"' || quote == '\'') {
                val end = openTag.indexOf(quote, valueStart + 1)
                if (end < 0) return null
                return openTag.substring(valueStart + 1, end)
            }
            val end = openTag.indexOfAny(charArrayOf(' ', '\n', '\t', '\r', '>'), valueStart)
            val stop = if (end < 0) openTag.length else end
            return openTag.substring(valueStart, stop).takeIf { it.isNotEmpty() }
        }
        return null
    }
}
