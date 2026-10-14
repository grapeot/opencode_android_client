package com.yage.opencode_client

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.session.SessionIndicator
import com.yage.opencode_client.ui.session.failureCountsBySession
import com.yage.opencode_client.ui.session.settled
import com.yage.opencode_client.ui.session.unreadCountsBySession
import com.yage.opencode_client.util.SessionReadState
import org.junit.Assert.*
import org.junit.Test

class SessionIndicatorTest {
    private fun session(id: String = "s", parent: String? = null, updated: Long = 100L, archived: Long? = null) =
        Session(id = id, directory = "/test", parentId = parent,
            time = Session.TimeInfo(updated = updated, archived = archived))

    @Test
    fun `only null and idle statuses are settled`() {
        assertTrue(settled(null))
        assertTrue(settled(SessionStatus("idle")))
        assertFalse(settled(SessionStatus("busy")))
        assertFalse(settled(SessionStatus("retry")))
        assertFalse(settled(SessionStatus("unknown")))
    }

    @Test
    fun `unread uses strict timestamp comparison and excludes missing update timestamps`() {
        val sessions = listOf(session())
        assertEquals(mapOf("s" to 1), unreadCountsBySession(sessions, emptyMap(), mapOf("s" to 99L)))
        assertTrue(unreadCountsBySession(sessions, emptyMap(), mapOf("s" to 100L)).isEmpty())
        assertTrue(unreadCountsBySession(sessions, emptyMap(), mapOf("s" to 101L)).isEmpty())
        val missingTimes = listOf(
            Session("no-time", directory = "/test"),
            Session("no-update", directory = "/test", time = Session.TimeInfo(created = 10L))
        )
        assertTrue(unreadCountsBySession(missingTimes, emptyMap(), emptyMap()).isEmpty())
        assertTrue(unreadCountsBySession(missingTimes, emptyMap(), mapOf("no-time" to 0L)).isEmpty())
        assertEquals(mapOf("s" to 1), unreadCountsBySession(listOf(session(updated = 0L)), emptyMap(), emptyMap()))
    }

    @Test
    fun `priority is attention then failure then unread and archived suppresses all`() {
        val unread = AppState(sessions = listOf(session()))
        assertEquals(SessionIndicator.UNREAD, unread.rowIndicator("s", 0))
        val failed = unread.copy(sessionReadStates = mapOf("s" to SessionReadState(failedAt = 80L)))
        assertEquals(SessionIndicator.FAILED, failed.rowIndicator("s", 0))
        assertEquals(SessionIndicator.NEEDS_YOU, failed.rowIndicator("s", 1))
        val archived = failed.copy(sessions = listOf(session(archived = 1L)))
        assertNull(archived.rowIndicator("s", 1))
        assertNull(unread.rowIndicator("missing", 1))
    }

    @Test
    fun `current suppresses unread but not attention or failure`() {
        val state = AppState(sessions = listOf(session()), currentSessionId = "s")
        assertNull(state.rowIndicator("s", 0))
        assertEquals(SessionIndicator.NEEDS_YOU, state.rowIndicator("s", 1))
        assertEquals(SessionIndicator.FAILED,
            state.copy(sessionReadStates = mapOf("s" to SessionReadState(failedAt = 50L))).rowIndicator("s", 0))
    }

    @Test
    fun `busy and retry suppress unread but not higher priority indicators`() {
        for (type in listOf("busy", "retry")) {
            val state = AppState(sessions = listOf(session()), sessionStatuses = mapOf("s" to SessionStatus(type)))
            assertTrue(state.sessionUnreadCounts.isEmpty())
            assertNull(state.rowIndicator("s", 0))
            assertEquals(SessionIndicator.NEEDS_YOU, state.rowIndicator("s", 1))
            assertEquals(SessionIndicator.FAILED,
                state.copy(sessionReadStates = mapOf("s" to SessionReadState(failedAt = 50L))).rowIndicator("s", 0))
        }
    }

    @Test
    fun `unread rolls up all ancestors against their own viewing time even after child is read`() {
        val sessions = listOf(session("root", updated = 10), session("parent", "root", 20), session("child", "parent", 100))
        val viewed = mapOf("root" to 99L, "parent" to 100L, "child" to 110L)
        assertEquals(mapOf("root" to 1), unreadCountsBySession(sessions, emptyMap(), viewed))
        assertTrue(unreadCountsBySession(sessions, mapOf("child" to SessionStatus("retry")), viewed).isEmpty())
        val state = AppState(sessions = sessions, sessionStatuses = mapOf("root" to SessionStatus("busy")),
            sessionReadStates = viewed.mapValues { SessionReadState(lastViewedAt = it.value) })
        assertEquals(1, state.sessionUnreadCounts["root"])
        assertNull(state.rowIndicator("root", 0))
    }

    @Test
    fun `failure rolls up against each ancestor viewing time and clears at equality`() {
        val sessions = listOf(session("root"), session("parent", "root"), session("child", "parent"))
        val failed = mapOf("child" to 100L)
        assertEquals(mapOf("root" to 1), failureCountsBySession(sessions, failed,
            mapOf("root" to 99L, "parent" to 100L, "child" to 101L)))
        val state = AppState(sessions = sessions, sessionReadStates = mapOf(
            "root" to SessionReadState(lastViewedAt = 99L),
            "parent" to SessionReadState(lastViewedAt = 100L),
            "child" to SessionReadState(lastViewedAt = 101L, failedAt = 100L)))
        assertEquals(SessionIndicator.FAILED, state.rowIndicator("root", 0))
        assertNull(state.copy(sessionReadStates = state.sessionReadStates +
            ("root" to SessionReadState(lastViewedAt = 100L))).rowIndicator("root", 0))
    }

    @Test
    fun `rollups count each source once per ancestor and terminate for cycles`() {
        val sessions = listOf(session("a", "b"), session("b", "a"))
        assertEquals(mapOf("a" to 2, "b" to 2), unreadCountsBySession(sessions, emptyMap(), emptyMap()))
        assertEquals(mapOf("a" to 2, "b" to 2), failureCountsBySession(sessions,
            mapOf("a" to 1L, "b" to 2L), emptyMap()))
        val selfCycle = listOf(session("a", "a"))
        assertEquals(mapOf("a" to 1), unreadCountsBySession(selfCycle, emptyMap(), emptyMap()))
    }

    @Test
    fun `derived counts only look up loaded session read states`() {
        val stored = mapOf(
            "s" to SessionReadState(lastViewedAt = 50L, failedAt = 80L),
            "unloaded" to SessionReadState(failedAt = 90L)
        )
        val lookupOnly = object : Map<String, SessionReadState> by stored {
            override val entries: Set<Map.Entry<String, SessionReadState>>
                get() = error("Read states must not be scanned")
            override val keys: Set<String>
                get() = error("Read states must not be scanned")
            override val values: Collection<SessionReadState>
                get() = error("Read states must not be scanned")
        }
        val state = AppState(sessions = listOf(session()), sessionReadStates = lookupOnly)
        assertEquals(mapOf("s" to 1), state.sessionUnreadCounts)
        assertEquals(mapOf("s" to 1), state.sessionFailureCounts)
    }
}
