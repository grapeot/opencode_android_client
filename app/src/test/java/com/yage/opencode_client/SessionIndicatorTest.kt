package com.yage.opencode_client

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.session.*
import org.junit.Assert.*
import org.junit.Test

class SessionIndicatorTest {
    private val tree = listOf(s("p"), s("c", "p"), s("g", "c"), s("b", "p"))
    private fun s(id: String, parent: String? = null) = Session(id, directory = "/test", parentId = parent,
        time = Session.TimeInfo(updated = 100))
    private val idle get() = tree.associate { it.id to SessionStatus("idle") }

    @Test fun `row acknowledgement neither stops propagation nor overrides source viewing`() {
        val ack = mapOf("c" to mapOf("g" to 100L), "g" to mapOf("g" to 100L))
        val sources = blueReminderSourcesBySession(tree, idle, mapOf("p" to 100L, "c" to 100L, "b" to 100L), ack)
        assertEquals(mapOf("g" to 100L), sources["p"])
        assertNull(sources["c"])
        assertEquals(mapOf("g" to 100L), sources["g"])
        assertTrue(blueReminderSourcesBySession(tree, idle, tree.associate { it.id to 100L }, ack).isEmpty())
    }

    @Test fun `source helper is cycle safe and rejects nonpositive versions`() {
        val cyclic = listOf(s("p", "c"), s("c", "p"), s("zero").copy(time = Session.TimeInfo(updated = 0)))
        val sources = blueReminderSourcesBySession(cyclic, cyclic.associate { it.id to SessionStatus("idle") }, emptyMap(),
            mapOf("p" to mapOf("c" to 100L)))
        assertEquals(mapOf("p" to 100L), sources["p"])
        assertEquals(mapOf("p" to 100L, "c" to 100L), sources["c"])
        assertNull(sources["zero"])
    }

    @Test fun `confirmation filters sources before aggregation and preserves siblings and parent`() {
        val events = tree.associate { it.id to 100L }
        val viewed = mapOf("g" to 101L, "c" to 101L)
        assertEquals(mapOf("p" to 2, "b" to 1), failureCountsBySession(tree, events, viewed))
        assertEquals(mapOf("p" to 2, "b" to 1), unreadCountsBySession(tree, idle, viewed))
        // Looking at the parent never acknowledges descendants.
        assertEquals(mapOf("p" to 3, "c" to 2, "g" to 1, "b" to 1),
            failureCountsBySession(tree, events, mapOf("p" to 101)))
        assertEquals(1, failureCountsBySession(tree, events + ("g" to 102), viewed)["c"])
    }

    @Test fun `timestamps must be positive and strict and execution inactive`() {
        assertFalse(settled(null))
        assertTrue(settled(SessionStatus("idle")))
        assertTrue(failureCountsBySession(tree, mapOf("g" to 0), emptyMap()).isEmpty())
        assertTrue(unreadCountsBySession(tree, emptyMap(), emptyMap()).isEmpty())
        assertTrue(unreadCountsBySession(tree, idle, tree.associate { it.id to 100L }).isEmpty())
        assertFalse(unreadCountsBySession(tree, idle + ("g" to SessionStatus("retry")), emptyMap()).containsKey("g"))
    }

    private fun presentation(statuses: Map<String, SessionStatus> = idle, known: Boolean = true,
        connected: Boolean = true, failed: Map<String, Long> = emptyMap(), pending: List<String> = emptyList(),
        current: String? = null) = sessionPresentations(tree, statuses, emptySet(), known, connected,
        emptyMap(), failed, pending, current)

    @Test fun `priority and own plus multilevel execution facts survive yellow and red`() {
        val active = idle + ("p" to SessionStatus("busy")) + ("g" to SessionStatus("retry"))
        assertEquals(SessionIndicator.RUNNING, presentation(active)["p"]?.indicator)
        val red = presentation(active, failed = mapOf("b" to 100))["p"]!!
        assertEquals(SessionIndicator.FAILED, red.indicator)
        assertEquals(1, red.activeDescendants)
        assertTrue(red.ownStatus!!.isBusy)
        assertEquals(SessionIndicator.NEEDS_YOU, presentation(active, failed = mapOf("b" to 100), pending = listOf("c"))["p"]?.indicator)
        assertEquals(SessionIndicator.UNREAD, presentation()["p"]?.indicator)
        assertNull(presentation(current = "p")["p"]?.indicator)
        assertEquals(SessionIndicator.RUNNING, presentation(active, current = "p")["p"]?.indicator)
    }

    @Test fun `unknown and disconnect cannot turn old execution blue and last child idle can`() {
        assertNull(presentation(emptyMap(), known = false)["p"]?.indicator)
        assertTrue(presentation(emptyMap(), known = false)["p"]!!.unknown)
        assertEquals(SessionIndicator.UNREAD, presentation(emptyMap())["p"]?.indicator)
        val running = idle + ("g" to SessionStatus("busy"))
        assertEquals(SessionIndicator.RUNNING, presentation(running)["p"]?.indicator)
        assertNull(presentation(running, connected = false)["p"]?.indicator)
        assertEquals(SessionIndicator.FAILED, presentation(running, connected = false, failed = mapOf("g" to 100))["p"]?.indicator)
        assertEquals(SessionIndicator.UNREAD, presentation(idle)["p"]?.indicator)
        assertNull(presentation(idle + ("g" to SessionStatus("new-status")))["p"]?.indicator)
    }

    @Test fun `archived no lamp and animation lifecycle gate`() {
        assertNull(rowIndicator(s("x").copy(time = Session.TimeInfo(archived = 1)), SessionStatus("busy"), false, 2, 2, 2))
        assertFalse(runningAnimationEnabled(true, true, 0f))
        assertFalse(runningAnimationEnabled(false, true, 1f))
        assertFalse(runningAnimationEnabled(true, false, 1f))
        assertTrue(runningAnimationEnabled(true, true, 1f))
    }
}
