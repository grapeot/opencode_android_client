package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AmbientReplyParserTest {
    @Test fun opaqueHeaderWinsAndLinkOnlyExtractsBefore() {
        val body = "[${historyMessage("a", "assistant", 1, "body")}]"
        val header = AmbientReplyParser.page(toolHttp(200, body, mapOf(
            "x-NeXt-CuRsOr" to listOf("  opaque+/=%  "),
            "Link" to listOf("<https://other.invalid/evil?before=ignored>; rel=\"next\""),
        )), "s")
        assertEquals("  opaque+/=%  ", header.before)
        val link = AmbientReplyParser.page(toolHttp(200, body, mapOf(
            "Link" to listOf("<https://other.invalid/evil?before=a%2Bb%2Fc%3D%25>; rel=\"prev next\""),
        )), "s")
        assertEquals("a+b/c=%", link.before)
    }

    @Test fun rawNonTextBoundaryAndMixedSyntheticPartsArePreserved() {
        val row = """{"info":{"id":"a","role":"assistant","finish":"stop","agent":"actual"},"parts":[{"type":"text","text":"synthetic","synthetic":true},{"type":"reasoning","text":"reason"},{"type":"text","text":"actual"}]}"""
        val page = AmbientReplyParser.page(toolHttp(200, "[${historyMessage("boundary", "user", 1)},$row]"), "s")
        assertFalse(page.gaps)
        assertEquals("boundary", page.rows.first().id)
        assertEquals("actual", page.rows.last().text)
        assertTrue(page.rows.last().complete)
        assertEquals("actual", page.rows.last().agent)
    }

    @Test fun malformedRowsOrWrongSessionMarkDecodeGap() {
        for (bad in listOf("{}", """{"info":{"id":"a","role":"assistant","sessionID":"other"},"parts":[]}""")) {
            assertTrue(AmbientReplyParser.page(toolHttp(200, "[$bad]"), "s").gaps)
        }
    }

    @Test fun longUnicodePayloadIncludesSourceAndTruncationWithinBudget() {
        val text = AmbientReplyCopy.render("标题".repeat(100), "s", "research", "🙂中文".repeat(1000))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 2000)
        assertTrue(text.contains("正文已截断"))
        assertTrue(text.contains("来源 agent：research"))
        assertFalse(text.contains('\uFFFD'))
    }

    @Test fun knownCommentaryIdAndStrictAckDecode() {
        val wire = Json.parseToJsonElement(AmbientWire.encode(AmbientClientEvent.Commentary("hello", "known"))!!) as JsonObject
        assertEquals(JsonPrimitive("known"), wire["event_id"])
        assertEquals(JsonNull, wire["delegation_id"])
        assertEquals(AmbientServerEvent.CommentaryAppended("known"), AmbientEventDecoder.decode("""{"type":"session.commentary.appended","client_event_id":"known"}"""))
        for (id in listOf("null", "1", "\" \"", "{}")) {
            assertTrue(AmbientEventDecoder.decode("""{"type":"session.commentary.appended","client_event_id":$id}""") is AmbientServerEvent.Ignored)
        }
    }
}
