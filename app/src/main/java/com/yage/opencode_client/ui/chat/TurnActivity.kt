package com.yage.opencode_client.ui.chat

import com.yage.opencode_client.data.model.MessageWithParts

internal data class TurnActivity(
    val id: String,
    val isRunning: Boolean,
    val text: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
) {
    fun elapsedSeconds(nowMillis: Long): Int {
        val end = endedAtMillis ?: nowMillis
        return ((end - startedAtMillis).coerceAtLeast(0L) / 1000L).toInt()
    }

    fun elapsedString(nowMillis: Long): String {
        val secs = elapsedSeconds(nowMillis)
        return "%d:%02d".format(secs / 60, secs % 60)
    }
}

internal enum class TurnActivityMode { CompletedOnly, RunningOnly }

internal fun turnActivitiesForSession(
    sessionId: String?,
    messages: List<MessageWithParts>,
    isSessionBusy: Boolean,
    activityText: String,
    mode: TurnActivityMode,
): List<TurnActivity> {
    val sid = sessionId ?: return emptyList()
    if (messages.isEmpty()) return emptyList()

    val userIndices = messages.indices.filter { messages[it].info.sessionId == sid && messages[it].info.isUser }
    if (userIndices.isEmpty()) return emptyList()

    val lastUserId = messages.lastOrNull { it.info.sessionId == sid && it.info.isUser }?.info?.id
    val results = mutableListOf<TurnActivity>()

    for (pos in userIndices.indices) {
        val ui = userIndices[pos]
        val nextUserIndex = if (pos + 1 < userIndices.size) userIndices[pos + 1] else messages.size

        var lastAssistantCreated: Long? = null
        var lastCompletedAssistant: Long? = null
        for (j in (ui + 1) until nextUserIndex) {
            val m = messages[j]
            if (m.info.isAssistant) {
                m.info.time?.created?.let { lastAssistantCreated = it }
                m.info.time?.completed?.let { lastCompletedAssistant = it }
            }
        }

        val userMsg = messages[ui]
        val startedAt = userMsg.info.time?.created ?: continue
        val endedAt = lastCompletedAssistant ?: lastAssistantCreated

        val isLatestTurn = userMsg.info.id == lastUserId
        val isRunning = isLatestTurn && isSessionBusy

        when (mode) {
            TurnActivityMode.RunningOnly -> {
                if (isRunning) {
                    results.add(
                        TurnActivity(
                            id = userMsg.info.id,
                            isRunning = true,
                            text = activityText,
                            startedAtMillis = startedAt,
                            endedAtMillis = null,
                        )
                    )
                }
            }
            TurnActivityMode.CompletedOnly -> {
                if (isRunning) continue
                if (endedAt == null) continue
                results.add(
                    TurnActivity(
                        id = userMsg.info.id,
                        isRunning = false,
                        text = activityText,
                        startedAtMillis = startedAt,
                        endedAtMillis = endedAt,
                    )
                )
            }
        }
    }
    return results
}