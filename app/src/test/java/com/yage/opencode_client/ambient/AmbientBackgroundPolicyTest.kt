package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientToolEffect
import com.yage.opencode_client.ambient.session.AmbientActivityDecision
import com.yage.opencode_client.ambient.session.AmbientBackgroundPolicy
import com.yage.opencode_client.ambient.session.AmbientBindingBook
import com.yage.opencode_client.ambient.session.AmbientCallerGate
import com.yage.opencode_client.ambient.session.AmbientRevokeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientBackgroundPolicyTest {
    @Test fun captureHoldWhileListeningOrSpeaking() {
        val gate = AmbientCallerGate()
        val token = gate.onStart()
        assertTrue(gate.onStop(token, changingConfigurations = false))
        assertEquals(
            AmbientActivityDecision.HOLD,
            AmbientBackgroundPolicy.activityStop(true, AmbientPhase.LISTENING, serviceReady = true),
        )
        assertEquals(
            AmbientActivityDecision.HOLD,
            AmbientBackgroundPolicy.activityStop(true, AmbientPhase.SPEAKING, serviceReady = true),
        )
        assertEquals(
            AmbientActivityDecision.END_OWNER,
            AmbientBackgroundPolicy.activityStop(true, AmbientPhase.LISTENING, serviceReady = false),
        )
    }

    @Test fun connectingStopCancels() {
        assertEquals(
            AmbientActivityDecision.CANCEL_CONNECTING,
            AmbientBackgroundPolicy.activityStop(true, AmbientPhase.CONNECTING, serviceReady = false),
        )
        assertEquals(
            AmbientActivityDecision.CANCEL_CONNECTING,
            AmbientBackgroundPolicy.activityStop(true, AmbientPhase.CONNECTING, serviceReady = true),
        )
    }

    @Test fun disabledOrDisconnectedEndsWithoutRestart() {
        val disabled = AmbientBackgroundPolicy.revoke(AmbientRevokeReason.FEATURE_DISABLED)
        val disconnected = AmbientBackgroundPolicy.revoke(AmbientRevokeReason.DISCONNECT)
        assertTrue(disabled.endOwner)
        assertFalse(disabled.restart)
        assertTrue(disconnected.endOwner)
        assertFalse(disconnected.restart)
    }

    @Test fun configChangeAndStaleTokenIgnored() {
        val gate = AmbientCallerGate()
        val token = gate.onStart()
        assertFalse(gate.onStop(token, changingConfigurations = true))
        assertFalse(gate.onStop(0L, changingConfigurations = false))
        assertFalse(gate.onStop(token + 1, changingConfigurations = false))
        assertTrue(gate.isForeground)
        assertEquals(
            AmbientActivityDecision.IGNORE,
            AmbientBackgroundPolicy.activityStop(false, AmbientPhase.LISTENING, serviceReady = true),
        )
        assertEquals(
            AmbientActivityDecision.IGNORE,
            AmbientBackgroundPolicy.activityStop(false, AmbientPhase.CONNECTING, serviceReady = false),
        )
        assertTrue(gate.onStop(token, changingConfigurations = false))
        assertFalse(gate.isForeground)
        assertFalse(gate.onStop(token, changingConfigurations = false))
    }

    @Test fun unexpectedServiceEndsCurrentOwnerOnly() {
        val current = AmbientBackgroundPolicy.serviceDestroyed("run-a", "run-a", intentional = false)
        assertTrue(current.endOwner)
        assertFalse(current.restart)
        val stale = AmbientBackgroundPolicy.serviceDestroyed("run-a", "run-b", intentional = false)
        assertFalse(stale.endOwner)
        assertFalse(stale.restart)
        val missing = AmbientBackgroundPolicy.serviceDestroyed(null, "run-b", intentional = false)
        assertFalse(missing.endOwner)
        assertFalse(missing.restart)
        val intended = AmbientBackgroundPolicy.serviceDestroyed("run-b", "run-b", intentional = true)
        assertFalse(intended.endOwner)
        assertFalse(intended.restart)
    }

    @Test fun chatSelectionKeepsBinding() {
        val decision = AmbientBackgroundPolicy.chatSelection()
        assertFalse(decision.stop)
        assertFalse(decision.rebindWorkspace)
        val book = AmbientBindingBook()
        book.begin("host", "/work")
        book.apply(
            true,
            AmbientToolEffect(output = "ok", sessionId = "s1", directory = "/work", model = "m1"),
        )
        book.noteChatSelection("other-session", "/elsewhere")
        assertEquals("host", book.capturedHost())
        assertEquals("/work", book.capturedDirectory())
        assertEquals("s1", book.active()?.sessionId)
        assertEquals("/work", book.active()?.directory)
        assertEquals("m1", book.active()?.model)
    }

    @Test fun nullModelKeptAndMatchingClearDrops() {
        val book = AmbientBindingBook()
        book.begin("host", "/work")
        book.apply(
            true,
            AmbientToolEffect(output = "ok", sessionId = "s1", directory = "/work", model = "m1"),
        )
        book.apply(
            true,
            AmbientToolEffect(output = "continue", sessionId = "s1", directory = null, model = null),
        )
        assertEquals("s1", book.active()?.sessionId)
        assertEquals("/work", book.active()?.directory)
        assertEquals("m1", book.active()?.model)
        book.apply(true, AmbientToolEffect(output = "miss", clearSessionId = "other"))
        assertNotNull(book.active())
        book.apply(false, AmbientToolEffect(output = "no-owner", clearSessionId = "s1"))
        assertEquals("s1", book.active()?.sessionId)
        book.apply(true, AmbientToolEffect(output = "404", clearSessionId = "s1"))
        assertNull(book.active())
    }
}
