package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientDiagnostics
import com.yage.opencode_client.ambient.protocol.AmbientHistoryText
import com.yage.opencode_client.ambient.protocol.AmbientHostedFunction
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class AmbientHistoryTest {
    @Test fun bothRolesUseTextPartsAndServerUtc() {
        val created = 1_700_000_000_123L
        val completed = 1_700_000_000_456L
        val script = historyScript(
            messages = listOf(
                historyMessage("m_user", "user", created, "the password is swordfish", completed = created),
                historyMessage(
                    "m_assistant",
                    "assistant",
                    created + 1_000,
                    text = "可见回复",
                    reasoning = "hidden chain of thought",
                    completed = completed,
                ),
                historyMessage("m_system", "system", created + 2_000, "系统旁白"),
            ),
        )
        val effect = script.execute(AmbientHostedFunction.GET_SESSION_MESSAGES, historyArgs("ses_1"))
        assertEquals(listOf("/session/ses_1", "/session/ses_1/message"), script.calls.map { it.path })
        assertEquals("200", script.calls[1].query["limit"])
        val json = Json.parseToJsonElement(effect.output).jsonObject
        val messages = json.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("the password is swordfish", messages[0].getValue("text").jsonPrimitive.content)
        assertEquals("可见回复", messages[1].getValue("text").jsonPrimitive.content)
        assertFalse(effect.output.contains("hidden chain of thought"))
        assertFalse(effect.output.contains("系统旁白"))
        assertEquals(AmbientHistoryText.iso(created), messages[0].getValue("created_at").jsonPrimitive.content)
        assertEquals(AmbientHistoryText.iso(completed), messages[1].getValue("completed_at").jsonPrimitive.content)
        assertTrue(effect.output.contains("the password is swordfish"))
        assertFalse(AmbientDiagnostics.allows(effect.output))
        assertNull(effect.clearSessionId)
    }

    @Test fun recentOffsetReturnsTheLatestWindow() {
        val messages = (1..12).map { index ->
            historyMessage("m$index", if (index % 2 == 0) "assistant" else "user", index.toLong(), "t$index")
        }
        val script = historyScript(messages)
        val effect = script.execute(AmbientHostedFunction.GET_SESSION_MESSAGES, historyArgs("ses_1", count = 10))
        val json = Json.parseToJsonElement(effect.output).jsonObject
        val ids = json.getValue("messages").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals((3..12).map { "m$it" }, ids)
        val page = json.getValue("pagination").jsonObject
        assertEquals(JsonPrimitive(true), page["hasMore"])
        assertEquals(10, page.getValue("nextSkip").jsonPrimitive.content.toInt())

        val older = historyScript(messages).execute(
            AmbientHostedFunction.GET_SESSION_MESSAGES,
            historyArgs("ses_1", skip = 10, count = 10),
        )
        val olderJson = Json.parseToJsonElement(older.output).jsonObject
        assertEquals(
            listOf("m1", "m2"),
            olderJson.getValue("messages").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content },
        )
        assertEquals(false, olderJson.getValue("pagination").jsonObject.getValue("hasMore").jsonPrimitive.content.toBoolean())
    }

    @Test fun fullFetchPageKeepsOlderUnknown() {
        val messages = (1..AmbientProtocol.HISTORY_FETCH_LIMIT).map { index ->
            historyMessage("m$index", "user", index.toLong(), "t")
        }
        val effect = historyScript(messages).execute(
            AmbientHostedFunction.GET_SESSION_MESSAGES,
            historyArgs("ses_1", skip = AmbientProtocol.HISTORY_FETCH_LIMIT - 1, count = 1),
        )
        val json = Json.parseToJsonElement(effect.output).jsonObject
        assertEquals("m1", json.getValue("messages").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val page = json.getValue("pagination").jsonObject
        assertEquals(true, page.getValue("hasMore").jsonPrimitive.content.toBoolean())
        assertEquals(AmbientProtocol.HISTORY_FETCH_LIMIT, page.getValue("nextSkip").jsonPrimitive.content.toInt())
        assertTrue(effect.output.toByteArray(StandardCharsets.UTF_8).size <= AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES)
    }

    @Test fun oversizedHistoryStaysLegalJson() {
        val huge = "字".repeat(80_000)
        val effect = historyScript(listOf(historyMessage("m1", "user", 10, huge))).execute(
            AmbientHostedFunction.GET_SESSION_MESSAGES,
            historyArgs("ses_1"),
        )
        Json.parseToJsonElement(effect.output)
        assertTrue(effect.output.isNotEmpty())
        assertTrue(effect.output.toByteArray(StandardCharsets.UTF_8).size <= AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES)
    }

    @Test fun unknownHistoryDoesNotFallBackAndUnauthorizedKeepsBinding() {
        val unknown = AmbientToolScript(bindingSession = binding("ses_real"))
        unknown.handler = { call ->
            assertEquals("/session/ses_missing", call.path)
            toolHttp(404, "")
        }
        val missed = unknown.execute(AmbientHostedFunction.GET_SESSION_MESSAGES, historyArgs("ses_missing"))
        assertEquals(1, unknown.calls.size)
        assertNull(missed.clearSessionId)
        assertEquals("ses_real", unknown.binding?.sessionId)

        val matched = AmbientToolScript(bindingSession = binding("ses_missing"))
        matched.handler = { toolHttp(404, "") }
        val cleared = matched.execute(AmbientHostedFunction.GET_SESSION_MESSAGES, historyArgs("ses_missing"))
        assertEquals("ses_missing", cleared.clearSessionId)

        val denied = AmbientToolScript(bindingSession = binding("ses_1"))
        denied.handler = { toolHttp(401, "") }
        val effect = denied.execute(AmbientHostedFunction.GET_SESSION_MESSAGES, historyArgs("ses_1"))
        assertNull(effect.clearSessionId)
        assertEquals("ses_1", denied.binding?.sessionId)
        assertEquals(401, Json.parseToJsonElement(effect.output).jsonObject.getValue("http_status").jsonPrimitive.content.toInt())
        assertTrue(denied.calls.none { it.path.endsWith("/message") })
    }

    @Test fun blankSessionUsesTheTrackedId() {
        val script = AmbientToolScript(bindingSession = binding("ses_tracked"))
        script.handler = { call ->
            when (call.path) {
                "/session/ses_tracked" -> toolHttp(200, sessionBody("ses_tracked", title = "跟踪"))
                "/session/ses_tracked/message" -> toolHttp(200, "[${historyMessage("m1", "assistant", 5, "在")}]")
                else -> unexpected(call)
            }
        }
        val effect = script.execute(AmbientHostedFunction.GET_SESSION_MESSAGES, historyArgs(null))
        assertEquals("m1", Json.parseToJsonElement(effect.output).jsonObject.getValue("messages").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("assistant", Json.parseToJsonElement(effect.output).jsonObject.getValue("messages").jsonArray.single().jsonObject.getValue("role").jsonPrimitive.content)
    }
}

private fun historyScript(messages: List<String>): AmbientToolScript {
    val script = AmbientToolScript()
    script.handler = { call ->
        when (call.path) {
            "/session/ses_1" -> toolHttp(200, sessionBody("ses_1", title = "历史"))
            "/session/ses_1/message" -> toolHttp(200, "[${messages.joinToString(",")}]")
            else -> unexpected(call)
        }
    }
    return script
}

private fun historyArgs(sessionId: String?, skip: Int = 0, count: Int = 10): String = buildJsonObject {
    if (sessionId == null) put("sessionID", JsonNull) else put("sessionID", sessionId)
    put("skip", skip)
    put("count", count)
}.toString()
