package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientCallBook
import com.yage.opencode_client.ambient.protocol.AmbientClientEvent
import com.yage.opencode_client.ambient.protocol.AmbientErrorDisposition
import com.yage.opencode_client.ambient.protocol.AmbientEventDecoder
import com.yage.opencode_client.ambient.protocol.AmbientFunctionOutput
import com.yage.opencode_client.ambient.protocol.AmbientHostedFunction
import com.yage.opencode_client.ambient.protocol.AmbientHostedInbound
import com.yage.opencode_client.ambient.protocol.AmbientModels
import com.yage.opencode_client.ambient.protocol.AmbientPromptWire
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import com.yage.opencode_client.ambient.protocol.AmbientSchema
import com.yage.opencode_client.ambient.protocol.AmbientServerEvent
import com.yage.opencode_client.ambient.protocol.AmbientToolCodes
import com.yage.opencode_client.ambient.protocol.AmbientToolDefaults
import com.yage.opencode_client.ambient.protocol.AmbientWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class AmbientProtocolTest {
    @Test fun forbiddenSessionStartUpdateAndAllowedNames() {
        assertTrue(AmbientWire.forbidden("session.start"))
        assertTrue(AmbientWire.forbidden("session.update"))
        assertEquals(
            setOf(
                "session.input_audio.append", "session.close",
                "session.input_audio.mute", "session.input_audio.unmute",
                "session.commentary.append", "session.thinking.append", "session.instructions.append",
                "response.item.create", "response.create",
            ),
            AmbientWire.allowed,
        )
        val produced = listOf(
            AmbientClientEvent.Close, AmbientClientEvent.Mute,
            AmbientClientEvent.Unmute, AmbientClientEvent.ContinueResponse,
        ).map { AmbientWire.eventType(AmbientWire.encode(it, "evt_named")!!)!! }
        assertTrue(produced.all { it in AmbientWire.allowed && !AmbientWire.forbidden(it) })
        assertFalse(produced.any { it == "session.start" || it == "session.update" })
    }

    @Test fun evenPcmAudioAndBase64SizeBounds() {
        assertNull(AmbientWire.encode(AmbientClientEvent.Audio(byteArrayOf(1)), "evt_short"))
        val odd = audioOf(AmbientWire.encode(AmbientClientEvent.Audio(byteArrayOf(9, 8, 7)), "evt_odd")!!)
        assertEquals(2, odd.size)
        assertEquals(9.toByte(), odd[0])
        assertEquals(8.toByte(), odd[1])
        val cap = AmbientProtocol.MAX_AUDIO_BYTES
        assertEquals(cap, audioOf(AmbientWire.encode(AmbientClientEvent.Audio(ByteArray(cap)), "evt_cap")!!).size)
        assertEquals(cap, audioOf(AmbientWire.encode(AmbientClientEvent.Audio(ByteArray(cap + 1)), "evt_trim")!!).size)
        assertNull(AmbientWire.encode(AmbientClientEvent.Audio(ByteArray(cap + 2)), "evt_over"))
        val encoded = json(AmbientWire.encode(AmbientClientEvent.Audio(byteArrayOf(1, 2)), "evt_b64")!!)
        val b64 = encoded.getValue("audio").jsonPrimitive.content
        assertEquals("session.input_audio.append", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(4, b64.length)
        assertEquals(2, Base64.getDecoder().decode(b64).size)
    }

    @Test fun appendClipUsesRealUtf8AndNullDelegation() {
        assertNull(AmbientWire.clip(" \n\t"))
        assertNull(AmbientWire.encode(AmbientClientEvent.Commentary("  "), "evt_blank"))
        assertEquals("a".repeat(2000), AmbientWire.clip("a".repeat(2001)))
        val hanClipped = AmbientWire.clip("汉".repeat(667))!!
        assertEquals("汉".repeat(666), hanClipped)
        assertEquals(1998, hanClipped.toByteArray(StandardCharsets.UTF_8).size)
        val emoji = "😀"
        assertEquals(4, emoji.toByteArray(StandardCharsets.UTF_8).size)
        val emojiClipped = AmbientWire.clip(emoji.repeat(501))!!
        assertEquals(emoji.repeat(500), emojiClipped)
        assertEquals(2000, emojiClipped.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals("a".repeat(1995) + emoji, AmbientWire.clip("a".repeat(1995) + emoji))
        val commentary = json(AmbientWire.encode(AmbientClientEvent.Commentary("  note  "), "evt_c")!!)
        val thinking = json(AmbientWire.encode(AmbientClientEvent.Thinking(emoji), "evt_t")!!)
        assertEquals(JsonNull, commentary["delegation_id"])
        assertEquals(JsonNull, thinking["delegation_id"])
        assertEquals("note", commentary.getValue("content").jsonPrimitive.content)
        assertEquals(emoji, thinking.getValue("content").jsonPrimitive.content)
    }

    @Test fun functionOutputShapeCallIdAndBoundedFallback() {
        val encoded = json(AmbientWire.encode(AmbientClientEvent.FunctionOutput("call_1", "{\"ok\":true}"), "evt_fn")!!)
        assertEquals("response.item.create", encoded.getValue("type").jsonPrimitive.content)
        val item = encoded.getValue("item").jsonObject
        assertEquals("function_call_output", item.getValue("type").jsonPrimitive.content)
        assertEquals("call_1", item.getValue("call_id").jsonPrimitive.content)
        assertEquals("{\"ok\":true}", item.getValue("output").jsonPrimitive.content)
        assertNull(AmbientWire.encode(AmbientClientEvent.FunctionOutput("", "x"), "evt_empty"))
        val huge = "x".repeat(AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES + 1)
        assertNull(AmbientWire.encode(AmbientClientEvent.FunctionOutput("call_1", huge), "evt_huge"))
        val fallback = AmbientFunctionOutput.ensureSendable(huge)
        val error = json(fallback)
        assertEquals(JsonPrimitive(false), error["ok"])
        assertEquals(AmbientToolCodes.OUTPUT_TOO_LARGE, error.getValue("error").jsonPrimitive.content)
        assertEquals(AmbientToolCodes.OUTPUT_TOO_LARGE_MESSAGE, error.getValue("message").jsonPrimitive.content)
        assertTrue(fallback.toByteArray(StandardCharsets.UTF_8).size <= AmbientProtocol.MAX_FUNCTION_OUTPUT_BYTES)
        assertNotNull(AmbientWire.encode(AmbientClientEvent.FunctionOutput("call_1", fallback), "evt_send"))
    }

    @Test fun eightStrictSchemasWithDeepSeekAndSol() {
        val tools = Json.parseToJsonElement(AmbientSchema.toolsJson()).jsonArray
        assertEquals(AmbientHostedFunction.names, tools.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        for (tool in tools) {
            val obj = tool.jsonObject
            assertEquals("function", obj.getValue("type").jsonPrimitive.content)
            assertEquals(JsonPrimitive(true), obj["strict"])
            assertEquals(JsonPrimitive(false), obj.getValue("parameters").jsonObject["additionalProperties"])
        }
        val anyOf = tools.first {
            it.jsonObject.getValue("name").jsonPrimitive.content == AmbientHostedFunction.START_JOB
        }.jsonObject.getValue("parameters").jsonObject.getValue("properties").jsonObject
            .getValue("requestedModel").jsonObject.getValue("anyOf").jsonArray
        assertEquals(
            listOf(AmbientToolDefaults.DEEPSEEK, AmbientToolDefaults.SOL),
            anyOf[0].jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("null", anyOf[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(AmbientToolDefaults.DEEPSEEK, AmbientModels.canonicalize("DeepSeek").getOrThrow())
        assertEquals(AmbientToolDefaults.SOL, AmbientModels.canonicalize("Sol").getOrThrow())
        assertEquals(AmbientToolDefaults.SOL, AmbientModels.canonicalize("SoA").getOrThrow())
        assertTrue(AmbientModels.canonicalize("other").isFailure)
    }

    @Test fun decoderTranscriptFragmentTextStartEnd() {
        val input = AmbientEventDecoder.decode(
            """{"type":"session.input_transcript.delta","event_id":"evt_in","delta":"你好","start_ms":10,"end_ms":40}""",
        ) as AmbientServerEvent.InputTranscript
        assertEquals("你好", input.fragment.text)
        assertEquals("evt_in", input.fragment.eventId)
        assertEquals(10, input.fragment.startMs)
        assertEquals(40, input.fragment.endMs)
        val output = AmbientEventDecoder.decode(
            """{"type":"session.output_transcript.delta","text":"ok","start_ms":1,"end_ms":2}""",
        ) as AmbientServerEvent.OutputTranscript
        assertEquals("ok", output.fragment.text)
        assertEquals(1, output.fragment.startMs)
        assertEquals(2, output.fragment.endMs)
    }

    @Test fun nestedFunctionCallSurvivesEmptyLifecycle() {
        val call = (AmbientEventDecoder.decode(
            """{"type":"response.event","event":{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_1","name":"query_jobs","arguments":"{}"}}}""",
        ) as AmbientServerEvent.Hosted).inbound as AmbientHostedInbound.FunctionCall
        assertEquals("call_1", call.callId)
        assertEquals("query_jobs", call.name)
        assertEquals("{}", call.arguments)
        val lifecycle = ((AmbientEventDecoder.decode(
            """{"type":"response.event","event":{"type":"response.completed","response":{"output":[]}}}""",
        ) as AmbientServerEvent.Hosted).inbound as AmbientHostedInbound.Lifecycle).lifecycle
        assertEquals("response.completed", lifecycle.type)
        assertEquals(0, lifecycle.outputCount)
        val book = AmbientCallBook(1L)
        book.collect(call.callId, call.name)
        book.noteLifecycle(lifecycle.outputCount)
        assertEquals(listOf("call_1" to "query_jobs"), book.pending)
    }

    @Test fun listCursorKeepsMillisecondTextPastInt() {
        val millis = "1729160345123"
        val pastInt = "2147483648"
        val wide = "1792230000123"
        assertEquals(1_729_160_345_123L, AmbientPromptWire.listCursor(millis))
        assertEquals(2_147_483_648L, AmbientPromptWire.listCursor(pastInt))
        assertEquals(1_792_230_000_123L, AmbientPromptWire.listCursor(wide))
        assertTrue(1_792_230_000_123L > Int.MAX_VALUE)
        assertEquals(millis, AmbientPromptWire.sessionPageQuery(AmbientPromptWire.listCursor(millis)).last().second)
        assertEquals(pastInt, AmbientPromptWire.sessionPageQuery(AmbientPromptWire.listCursor(pastInt)).last().second)
        assertEquals(wide, AmbientPromptWire.sessionPageQuery(AmbientPromptWire.listCursor(wide)).last().second)
        assertEquals("50", AmbientPromptWire.sessionPageQuery(50L).last().second)
        assertNull(AmbientPromptWire.listCursor("9223372036854775808"))
        assertNull(AmbientPromptWire.listCursor("50.0"))
        assertNull(AmbientPromptWire.listCursor("+50"))
        assertNull(AmbientPromptWire.listCursor("050"))
        assertNull(AmbientPromptWire.listCursor("cur-2"))
        assertNull(AmbientPromptWire.listCursor(""))
    }

    @Test fun portalUnconfirmedAndNonfatalInvalidAudio() {
        assertTrue(AmbientEventDecoder.decode("""{"type":"usage","request_id":"req_1"}""") is AmbientServerEvent.UsageUnconfirmed)
        val usage = AmbientEventDecoder.decode(
            """{"type":"usage","request_id":"req_1","estimated_cost_usd":2,"confirmed":false,"status":"provisional"}""",
        ) as AmbientServerEvent.PortalUsage
        assertEquals("req_1", usage.requestId)
        assertEquals(2.0, usage.estimatedUsd, 0.0)
        assertFalse(usage.confirmed)
        assertEquals("provisional", usage.status)
        assertTrue(AmbientEventDecoder.decode("""{"type":"error","code":"invalid_audio"}""") is AmbientServerEvent.InvalidAudio)
        assertEquals(AmbientErrorDisposition.IGNORE, AmbientEventDecoder.errorDisposition("invalid_audio", false, false))
        assertEquals(AmbientErrorDisposition.IGNORE, AmbientEventDecoder.errorDisposition("usage_unconfirmed", false, false))
        assertEquals(AmbientErrorDisposition.FAIL, AmbientEventDecoder.errorDisposition("session_error", false, false))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun audioOf(text: String): ByteArray =
        Base64.getDecoder().decode(json(text).getValue("audio").jsonPrimitive.content)
}
