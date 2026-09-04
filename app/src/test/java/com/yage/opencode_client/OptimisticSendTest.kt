package com.yage.opencode_client

import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.SSEPayload
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.buildOptimisticMessage
import com.yage.opencode_client.ui.makeServerId
import com.yage.opencode_client.ui.mergePendingOptimisticMessages
import com.yage.opencode_client.ui.parseSessionErrorReason
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticSendTest {

    @Test
    fun `makeServerId produces a prefixed dashless uuid`() {
        val id = makeServerId("msg")
        assertTrue(id.startsWith("msg_"))
        assertEquals(36, id.length)
        assertTrue(id.substring(4).all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `makeServerId is unique across calls`() {
        val a = makeServerId("msg")
        val b = makeServerId("msg")
        assertTrue(a != b)
    }

    @Test
    fun `buildOptimisticMessage builds a user row with text and file parts`() {
        val attachment = ComposerImageAttachment(
            id = "att-1",
            filename = "a.png",
            mime = "image/png",
            dataUrl = "data:image/png;base64,xxx",
            thumbnailData = byteArrayOf(1, 2, 3),
            byteSize = 3
        )
        val row = buildOptimisticMessage(
            sessionId = "s1",
            text = "hello",
            attachments = listOf(attachment),
            messageId = "msg_abc",
            parentMessageId = "msg_parent"
        )
        assertEquals("msg_abc", row.info.id)
        assertEquals("user", row.info.role)
        assertEquals("s1", row.info.sessionId)
        assertEquals("msg_parent", row.info.parentId)
        assertEquals(2, row.parts.size)
        assertEquals("text", row.parts[0].type)
        assertEquals("hello", row.parts[0].text)
        assertEquals("file", row.parts[1].type)
        assertEquals("a.png", row.parts[1].filename)
        assertEquals("data:image/png;base64,xxx", row.parts[1].url)
    }

    @Test
    fun `buildOptimisticMessage with blank text and no attachments has no parts`() {
        val row = buildOptimisticMessage(
            sessionId = "s1",
            text = "   ",
            attachments = emptyList(),
            messageId = "msg_abc",
            parentMessageId = null
        )
        assertEquals(0, row.parts.size)
        assertEquals("user", row.info.role)
    }

    @Test
    fun `merge keeps unconfirmed pending rows and prunes confirmed ids`() {
        val pendingRow = MessageWithParts(
            info = Message(id = "msg_pending", sessionId = "s1", role = "user"),
            parts = emptyList()
        )
        val confirmedRow = MessageWithParts(
            info = Message(id = "msg_confirmed", sessionId = "s1", role = "assistant"),
            parts = emptyList()
        )
        val currentState = AppState(
            messages = listOf(pendingRow, confirmedRow),
            pendingOptimisticMessageIds = setOf("msg_pending", "msg_confirmed")
        )
        // Server returns the confirmed message; the pending one is still in flight.
        val (merged, pruned) = mergePendingOptimisticMessages(listOf(confirmedRow), currentState)
        assertEquals(listOf("msg_confirmed", "msg_pending"), merged.map { it.info.id })
        assertEquals(setOf("msg_pending"), pruned)
    }

    @Test
    fun `merge drops the pending row once the server confirms it`() {
        val confirmedRow = MessageWithParts(
            info = Message(id = "msg_x", sessionId = "s1", role = "user"),
            parts = emptyList()
        )
        val currentState = AppState(
            messages = listOf(confirmedRow),
            pendingOptimisticMessageIds = setOf("msg_x")
        )
        val (merged, pruned) = mergePendingOptimisticMessages(listOf(confirmedRow), currentState)
        assertEquals(listOf("msg_x"), merged.map { it.info.id })
        assertEquals(emptySet<String>(), pruned)
    }

    @Test
    fun `merge is a no-op when there are no pending rows`() {
        val server = MessageWithParts(
            info = Message(id = "msg_a", sessionId = "s1", role = "assistant"),
            parts = emptyList()
        )
        val currentState = AppState(messages = listOf(server), pendingOptimisticMessageIds = emptySet())
        val (merged, pruned) = mergePendingOptimisticMessages(listOf(server), currentState)
        assertEquals(listOf("msg_a"), merged.map { it.info.id })
        assertEquals(emptySet<String>(), pruned)
    }

    @Test
    fun `parseSessionErrorReason combines name and first message line`() {
        val event = SSEEvent(
            payload = SSEPayload(
                type = "session.error",
                properties = buildJsonObject {
                    put("sessionID", JsonPrimitive("s1"))
                    put(
                        "error",
                        buildJsonObject {
                            put("name", JsonPrimitive("ProviderAuthError"))
                            put("data", buildJsonObject { put("message", JsonPrimitive("boom")) })
                        }
                    )
                }
            )
        )
        assertEquals("ProviderAuthError: boom", parseSessionErrorReason(event))
    }

    @Test
    fun `parseSessionErrorReason keeps only the first meaningful line of a multi-line message`() {
        val event = SSEEvent(
            payload = SSEPayload(
                type = "session.error",
                properties = buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("name", JsonPrimitive("APIError"))
                            put(
                                "data",
                                buildJsonObject {
                                    put("message", JsonPrimitive("first line\nsecond line\nthird"))
                                }
                            )
                        }
                    )
                }
            )
        )
        assertEquals("APIError: first line", parseSessionErrorReason(event))
    }

    @Test
    fun `parseSessionErrorReason falls back to name when there is no message`() {
        val event = SSEEvent(
            payload = SSEPayload(
                type = "session.error",
                properties = buildJsonObject {
                    put("error", buildJsonObject { put("name", JsonPrimitive("MessageAbortedError")) })
                }
            )
        )
        assertEquals("MessageAbortedError", parseSessionErrorReason(event))
    }

    @Test
    fun `parseSessionErrorReason returns fallback when the error object is missing`() {
        val event = SSEEvent(payload = SSEPayload(type = "session.error"))
        assertEquals("unknown error", parseSessionErrorReason(event))
    }
}