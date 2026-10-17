package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientEndpoint
import com.yage.opencode_client.ambient.protocol.AmbientTicketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientEndpointTest {
    private val portal = "https://portal.example/backend"

    @Test fun sessionUrlIncludesBackendOnceAndToleratesTrailingSlash() {
        val bare = AmbientEndpoint.sessionUrl(portal)
        val slashed = AmbientEndpoint.sessionUrl("$portal/")
        assertEquals("https://portal.example/backend/v1/audio/live/sessions", bare)
        assertEquals(bare, slashed)
        assertEquals(1, "/backend".toRegex().findAll(bare!!).count())
    }

    @Test fun relativeSocketWithBackendResolvesAgainstOrigin() {
        assertEquals(
            "wss://portal.example/backend/live?ticket=ticket_1",
            AmbientEndpoint.resolveWebSocket(portal, "/backend/live?ticket=ticket_1", "ticket_1"),
        )
    }

    @Test fun sameOriginDefaultPortAllowedForeignHostRejected() {
        val allowed = listOf(
            "wss://portal.example/live?ticket=ticket_1" to "wss://portal.example/live?ticket=ticket_1",
            "https://portal.example/live?ticket=ticket_1" to "wss://portal.example/live?ticket=ticket_1",
            "wss://portal.example:443/live?ticket=ticket_1" to "wss://portal.example:443/live?ticket=ticket_1",
        )
        for ((socket, expected) in allowed) {
            assertEquals(expected, AmbientEndpoint.resolveWebSocket(portal, socket, "ticket_1"))
        }
        assertEquals(
            "wss://portal.example/live?ticket=ticket_1",
            AmbientEndpoint.resolveWebSocket(
                "https://portal.example:443/backend",
                "wss://portal.example/live?ticket=ticket_1",
                "ticket_1",
            ),
        )
        assertNull(
            AmbientEndpoint.resolveWebSocket(portal, "wss://other.example/live?ticket=ticket_1", "ticket_1"),
        )
    }

    @Test fun httpsRejectsDowngradeAndHttpLocalhostWorks() {
        for (socket in listOf(
            "ws://portal.example/live?ticket=ticket_1",
            "http://portal.example/live?ticket=ticket_1",
        )) {
            assertNull(socket, AmbientEndpoint.resolveWebSocket(portal, socket, "ticket_1"))
        }
        assertEquals(
            "ws://localhost/live?ticket=ticket_1",
            AmbientEndpoint.resolveWebSocket(
                "http://localhost/backend",
                "ws://localhost/live?ticket=ticket_1",
                "ticket_1",
            ),
        )
    }

    @Test fun malformedSocketUrlsRejected() {
        val rejected = listOf(
            "/live?ticket=ticket_1&ticket=ticket_1",
            "/live?ticket=ticket_1&extra=1",
            "/live?ticket=",
            "/live",
            "wss://user:pass@portal.example/live?ticket=ticket_1",
            "/live?ticket=ticket_1#frag",
            "/live\\path?ticket=ticket_1",
            "//portal.example/live?ticket=ticket_1",
        )
        for (socket in rejected) {
            assertNull(socket, AmbientEndpoint.resolveWebSocket(portal, socket, "ticket_1"))
        }
    }

    @Test fun jsonTicketMismatchRejected() {
        val parsed = AmbientTicketParser.parse(
            """{"portal_session_id":"s","ticket":"ticket_1","ws_url":"wss://portal.example/live?ticket=other","expiry":"e"}""",
        )
        assertNotNull(parsed)
        assertNull(AmbientEndpoint.resolveWebSocket(portal, parsed!!.wsUrl, parsed.ticket))
    }

    @Test fun createStatusAcceptsOnly200And201() {
        assertTrue(AmbientEndpoint.acceptsCreateStatus(200))
        assertTrue(AmbientEndpoint.acceptsCreateStatus(201))
        for (status in listOf(199, 202, 204, 301, 400, 401, 404, 500)) {
            assertFalse(status.toString(), AmbientEndpoint.acceptsCreateStatus(status))
        }
    }

    @Test fun ticketJsonParsesFourStringsAndRejectsBadFields() {
        val parsed = AmbientTicketParser.parse(
            """{"portal_session_id":"s","ticket":"ticket_1","ws_url":"wss://portal.example/live?ticket=ticket_1","expiry":"e"}""",
        )
        assertNotNull(parsed)
        assertEquals("s", parsed!!.portalSessionId)
        assertEquals("ticket_1", parsed.ticket)
        assertEquals("wss://portal.example/live?ticket=ticket_1", parsed.wsUrl)
        assertEquals("e", parsed.expiry)
        val rejected = listOf(
            """{"ticket":"ticket_1","ws_url":"wss://portal.example/live?ticket=ticket_1","expiry":"e"}""",
            """{"portal_session_id":"","ticket":"ticket_1","ws_url":"wss://portal.example/live?ticket=ticket_1","expiry":"e"}""",
            """{"portal_session_id":1,"ticket":"ticket_1","ws_url":"wss://portal.example/live?ticket=ticket_1","expiry":"e"}""",
            """{"portal_session_id":"s","ticket":"ticket_1","ws_url":{"u":"wss://portal.example/live"},"expiry":"e"}""",
        )
        for (json in rejected) {
            assertNull(json, AmbientTicketParser.parse(json))
        }
    }
}
