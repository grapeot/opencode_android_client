package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientAuthorizingGateway
import com.yage.opencode_client.ambient.protocol.AmbientCapturedConfig
import com.yage.opencode_client.ambient.protocol.AmbientDispatchGate
import com.yage.opencode_client.ambient.protocol.AmbientGatewayCall
import com.yage.opencode_client.ambient.protocol.AmbientGatewayExchange
import com.yage.opencode_client.ambient.protocol.AmbientHttpResult
import com.yage.opencode_client.ambient.protocol.AmbientLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientContractsTest {
    @Test
    fun retireThenConfigure_issuesDistinctToken() {
        val gate = AmbientDispatchGate()
        val first = gate.configure(config())
        assertTrue(gate.retire(first))
        val second = gate.configure(config())
        assertTrue(first != second)
        assertFalse(gate.retire(first))
        assertNull(gate.authorize(0L))
        assertNull(gate.authorize(first))
        assertEquals(config(), gate.authorize(second))
    }

    @Test
    fun sameHostReplace_invalidate_overflowFailsClosed() {
        val gate = AmbientDispatchGate()
        val first = gate.configure(config())
        val second = gate.configure(config())
        assertTrue(first != second)
        assertNull(gate.authorize(first))
        assertEquals(config(), gate.authorize(second))
        gate.invalidate()
        assertNull(gate.authorize(second))

        val overflow = AmbientDispatchGate(Long.MAX_VALUE)
        assertEquals(0L, overflow.configure(config()))
        assertNull(overflow.authorize(0L))
    }

    @Test
    fun leaseAllowsExactMatch_rejectsEachMismatch() {
        val lease = AmbientLease("host", 3L, "/synthetic", 4L, "owner")
        assertTrue(lease.allows(true, "host", 3L, true, 4L, "owner"))
        assertFalse(lease.allows(false, "host", 3L, true, 4L, "owner"))
        assertFalse(lease.allows(true, "other", 3L, true, 4L, "owner"))
        assertFalse(lease.allows(true, "host", 9L, true, 4L, "owner"))
        assertFalse(lease.allows(true, "host", 3L, false, 4L, "owner"))
        assertFalse(lease.allows(true, "host", 3L, true, 9L, "owner"))
        assertFalse(lease.allows(true, "host", 3L, true, 4L, "other"))
        assertTrue(lease.copy(directory = "/other-chat").allows(true, "host", 3L, true, 4L, "owner"))
    }

    @Test
    fun staleToken_doesNotInvokeTransport() = runBlocking {
        var invoked = false
        val gateway = AmbientAuthorizingGateway(AmbientDispatchGate()) { _, _, _ ->
            invoked = true
            http()
        }
        assertTrue(gateway.exchange(1L, call()) is AmbientGatewayExchange.StaleLease)
        assertFalse(invoked)
    }

    @Test
    fun transportRetire_returnsStaleLease() = runBlocking {
        val gate = AmbientDispatchGate()
        val token = gate.configure(config())
        val gateway = AmbientAuthorizingGateway(gate) { _, _, current ->
            gate.retire(current)
            http()
        }
        assertTrue(gateway.exchange(token, call()) is AmbientGatewayExchange.StaleLease)
    }

    @Test
    fun successPreservesHttp_cancellationPropagates() = runBlocking {
        val gate = AmbientDispatchGate()
        val token = gate.configure(config())
        val ok = AmbientAuthorizingGateway(gate) { _, _, _ ->
            AmbientHttpResult(207, mapOf("X-A" to listOf("v")), "body")
        }.exchange(token, call()) as AmbientGatewayExchange.Completed
        assertEquals(207, ok.http.code)
        assertEquals(listOf("v"), ok.http.headers["X-A"])
        assertEquals("body", ok.http.body)

        val next = gate.configure(config())
        try {
            AmbientAuthorizingGateway(gate) { _, _, _ ->
                throw CancellationException("stop")
            }.exchange(next, call())
            throw AssertionError("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    private fun config() = AmbientCapturedConfig(
        hostProfileId = "host",
        runtimeGeneration = 3L,
        directory = "/synthetic",
        epoch = 4L,
        ownerToken = "owner",
        baseUrl = "https://example.invalid",
        username = "synthetic-user",
        password = "synthetic-pass",
    )

    private fun call() = AmbientGatewayCall("GET", "/synthetic")

    private fun http() = AmbientHttpResult(200, emptyMap(), "")
}
