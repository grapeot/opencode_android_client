package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AmbientReplyAdmissionTest {
    @Test fun startReservesBeforePostAndActivatesAllAcceptedStatusCodes() {
        for (code in listOf(200, 202, 204, 500)) {
            val observer = RecordingReplies()
            val script = AmbientToolScript(replies = observer)
            script.handler = { call -> when (call.path) {
                "/provider" -> toolHttp(200, providersBody(mapOf("ollama-cloud" to listOf("deepseek-v4.1-flash"))))
                "/session" -> toolHttp(200, sessionBody("s"))
                "/session/s/prompt_async" -> {
                    assertEquals(listOf("reserve:s"), observer.actions)
                    toolHttp(code, "")
                }
                else -> error("unexpected ${call.path}")
            } }
            val effect = script.execute(AmbientHostedFunction.START_JOB, """{"text":"do it","requestedModel":null}""")
            assertEquals(code != 500, effect.poll)
            assertEquals(if (code == 500) listOf("reserve:s", "rollback") else listOf("reserve:s", "accept", "rollback"), observer.actions)
        }
    }

    @Test fun continueUsesPrePostRawHistoryAndDoesNotWaitForVoiceOutput() {
        val observer = RecordingReplies()
        val script = AmbientToolScript(replies = observer)
        script.handler = { call -> when (call.path) {
            "/session/s" -> toolHttp(200, sessionBody("s"))
            "/session/s/message" -> toolHttp(200, "[${historyMessage("non-text-boundary", "user", 1)}]")
            "/session/s/prompt_async" -> {
                assertEquals("non-text-boundary", observer.history?.single()?.jsonObject?.get("info")?.jsonObject?.get("id")?.jsonPrimitive?.content)
                toolHttp(204, "")
            }
            else -> error("unexpected ${call.path}")
        } }
        val effect = script.execute(AmbientHostedFunction.CONTINUE_JOB, continueArgs("continue", "s"))
        assertTrue(effect.poll)
        assertTrue(observer.actions.contains("accept")) // No voice link exists in this actual runner test.
    }
}

private class RecordingReplies : AmbientReplyObserver {
    val actions = mutableListOf<String>()
    var history: JsonArray? = null
    override fun reserve(reservation: AmbientReplyReservation, history: JsonArray?): String {
        actions += "reserve:${reservation.sessionId}"
        this.history = history
        return "token"
    }
    override fun accept(token: String) { actions += "accept" }
    override fun rollback(token: String) { actions += "rollback" }
}
