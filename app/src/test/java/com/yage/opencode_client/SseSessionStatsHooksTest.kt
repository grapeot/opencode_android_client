package com.yage.opencode_client

import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.SSEPayload
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.handleIncomingSseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseSessionStatsHooksTest {

    private val state = MutableStateFlow(AppState(currentSessionId = "s1"))
    private var userMessages = mutableListOf<String>()
    private var toolParts = mutableListOf<String>()

    private fun handle(event: SSEEvent) {
        handleIncomingSseEvent(
            state = state,
            event = event,
            onRefreshMessages = { _, _ -> },
            onRefreshSessions = { },
            onLoadPendingPermissions = { },
            onNonFatalIssue = { },
            onRecordUserMessage = { _, messageId -> userMessages += messageId },
            onRecordToolPart = { _, partId -> toolParts += partId }
        )
    }

    private fun messageEvent(type: String, sessionId: String, messageId: String, role: String) =
        SSEEvent(
            payload = SSEPayload(
                type = type,
                properties = buildJsonObject {
                    put("sessionID", JsonPrimitive(sessionId))
                    put(
                        "info",
                        buildJsonObject {
                            put("id", JsonPrimitive(messageId))
                            put("role", JsonPrimitive(role))
                        }
                    )
                }
            )
        )

    private fun partEvent(partId: String, partType: String, sessionId: String = "s1") =
        SSEEvent(
            payload = SSEPayload(
                type = "message.part.updated",
                properties = buildJsonObject {
                    put("sessionID", JsonPrimitive(sessionId))
                    put(
                        "part",
                        buildJsonObject {
                            put("id", JsonPrimitive(partId))
                            put("type", JsonPrimitive(partType))
                        }
                    )
                }
            )
        )

    @Test
    fun `created event with user role records a round`() {
        handle(messageEvent("message.created", "s1", "msg_u1", "user"))
        assertEquals(listOf("msg_u1"), userMessages)
    }

    @Test
    fun `updated event with user role also records a round`() {
        handle(messageEvent("message.updated", "s1", "msg_u2", "user"))
        assertEquals(listOf("msg_u2"), userMessages)
    }

    @Test
    fun `assistant messages do not record rounds`() {
        handle(messageEvent("message.created", "s1", "msg_a1", "assistant"))
        handle(messageEvent("message.updated", "s1", "msg_a1", "assistant"))
        assertNull(userMessages.firstOrNull())
    }

    @Test
    fun `events from other sessions are ignored`() {
        handle(messageEvent("message.created", "s2", "msg_u9", "user"))
        handle(partEvent("p9", "tool", sessionId = "s2"))
        assertNull(userMessages.firstOrNull())
        assertNull(toolParts.firstOrNull())
    }

    @Test
    fun `tool part records a tool call, text and reasoning do not`() {
        handle(partEvent("p1", "text"))
        handle(partEvent("p2", "reasoning"))
        handle(partEvent("p3", "tool"))
        assertEquals(listOf("p3"), toolParts)
        assertNull(userMessages.firstOrNull())
    }

    @Test
    fun `message event without info payload is ignored gracefully`() {
        handle(
            SSEEvent(
                payload = SSEPayload(
                    type = "message.created",
                    properties = buildJsonObject { put("sessionID", JsonPrimitive("s1")) }
                )
            )
        )
        assertNull(userMessages.firstOrNull())
    }
}
