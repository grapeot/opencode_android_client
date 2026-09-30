package com.yage.opencode_client

import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppStateSessionStatsTest {

    private fun session(
        id: String,
        parent: String? = null,
        tokens: Message.TokenInfo? = null
    ) = Session(id = id, directory = "/project", parentId = parent, tokens = tokens)

    private fun tokens(
        total: Int,
        input: Int? = null,
        output: Int? = null,
        cacheRead: Int? = null
    ) = Message.TokenInfo(
        total = total,
        input = input,
        output = output,
        cache = cacheRead?.let { Message.TokenInfo.CacheInfo(read = it) }
    )

    private fun assistantMessage(id: String, tokens: Message.TokenInfo?) =
        MessageWithParts(
            info = Message(id = id, sessionId = "main", role = "assistant", tokens = tokens)
        )

    @Test
    fun `total tokens sum the main session and all descendants`() {
        val state = AppState(
            currentSessionId = "main",
            sessions = listOf(
                session("main", tokens = tokens(total = 200, input = 90, output = 10, cacheRead = 100)),
                session("child", parent = "main", tokens = tokens(total = 50, input = 40, output = 10)),
                session("grandchild", parent = "child", tokens = tokens(total = 60, input = 20, output = 5, cacheRead = 35))
            ),
            currentSessionCounts = AppState.SessionCounts(rounds = 3, toolCalls = 7)
        )

        val stats = state.sessionStats
        assertEquals(310, stats?.totalTokens)
        assertEquals(3, stats?.rounds)
        assertEquals(7, stats?.toolCalls)
        // cache read 135 over fresh input 150 + cache read 135
        assertEquals(135f / 285f, stats?.cacheHitRate!!, 1e-6f)
    }

    @Test
    fun `cache hit rate uses the whole tree denominator`() {
        val state = AppState(
            currentSessionId = "main",
            sessions = listOf(
                session("main", tokens = tokens(total = 100, input = 50, output = 50)),
                session("child", parent = "main", tokens = tokens(total = 100, input = 50, output = 50))
            )
        )

        assertEquals(0f, state.sessionStats?.cacheHitRate!!, 1e-6f)
    }

    @Test
    fun `main without aggregate and incomplete window hides the whole row`() {
        val state = AppState(
            currentSessionId = "main",
            messageLimit = 1,
            messages = listOf(assistantMessage("m1", tokens(total = 40, input = 30, output = 10))),
            sessions = listOf(session("main")),
            currentSessionCounts = AppState.SessionCounts(rounds = 5, toolCalls = 9)
        )

        // Window is not proven complete (size == limit), aggregate missing:
        // the main baseline is unknowable, so even the counters hide.
        assertNull(state.sessionStats)
    }

    @Test
    fun `main without aggregate falls back to the complete window`() {
        val state = AppState(
            currentSessionId = "main",
            messageLimit = 30,
            messages = listOf(
                MessageWithParts(info = Message(id = "u1", sessionId = "main", role = "user")),
                assistantMessage("m1", tokens(total = 40, input = 30, output = 10)),
                assistantMessage("m2", tokens(total = 60, input = 40, output = 20))
            ),
            sessions = listOf(
                session("main"),
                session("child", parent = "main", tokens = tokens(total = 20, input = 15, output = 5))
            ),
            currentSessionCounts = AppState.SessionCounts(rounds = 2, toolCalls = 0)
        )

        val stats = state.sessionStats
        assertEquals(120, stats?.totalTokens) // 40 + 60 + 20
        assertEquals(2, stats?.rounds)
        assertEquals(0, stats?.toolCalls)
        assertEquals(0f, stats?.cacheHitRate!!, 1e-6f) // input 85, no cache
    }

    @Test
    fun `fresh session with zero aggregate shows counters only`() {
        val state = AppState(
            currentSessionId = "main",
            sessions = listOf(session("main", tokens = tokens(total = 0, input = 0, output = 0))),
            currentSessionCounts = AppState.SessionCounts(rounds = 1, toolCalls = 0)
        )

        val stats = state.sessionStats
        assertEquals(1, stats?.rounds)
        assertEquals(0, stats?.toolCalls)
        assertNull(stats?.totalTokens)
        assertNull(stats?.cacheHitRate)
    }

    @Test
    fun `input without cache is a real zero percent`() {
        val state = AppState(
            currentSessionId = "main",
            sessions = listOf(session("main", tokens = tokens(total = 110, input = 100, output = 10)))
        )

        assertEquals(0f, state.sessionStats?.cacheHitRate!!, 1e-6f)
    }

    @Test
    fun `parentID cycles terminate`() {
        val state = AppState(
            currentSessionId = "a",
            sessions = listOf(
                session("a", parent = "b", tokens = tokens(total = 10, input = 5, output = 5)),
                session("b", parent = "a", tokens = tokens(total = 10, input = 5, output = 5))
            )
        )

        assertEquals(20, state.sessionStats?.totalTokens)
    }

    @Test
    fun `unrelated sessions do not roll up`() {
        val state = AppState(
            currentSessionId = "main",
            sessions = listOf(
                session("main", tokens = tokens(total = 10, input = 5, output = 5)),
                session("other", tokens = tokens(total = 999, input = 500, output = 499)),
                session("other-child", parent = "other", tokens = tokens(total = 888, input = 400, output = 488))
            )
        )

        assertEquals(10, state.sessionStats?.totalTokens)
    }

    @Test
    fun `current session missing from the list hides the row`() {
        val state = AppState(currentSessionId = "gone", sessions = listOf(session("main")))
        assertNull(state.sessionStats)
    }

    @Test
    fun `no current session hides the row`() {
        val state = AppState()
        assertNull(state.sessionStats)
    }

    @Test
    fun `hasVisibleSegments reflects populated fields`() {
        val all = AppState.SessionStats(1, 2, 3, 0.5f)
        val none = AppState.SessionStats(null, null, null, null)
        val partial = AppState.SessionStats(1, null, null, null)
        val zeroRate = AppState.SessionStats(1, 2, null, 0f)
        assertEquals(true, all.hasVisibleSegments)
        assertEquals(false, none.hasVisibleSegments)
        assertEquals(true, partial.hasVisibleSegments)
        assertEquals(true, zeroRate.hasVisibleSegments)
        // Guard against drift: 0f is a real value, not an absent one.
        assertEquals(0f, zeroRate.cacheHitRate)
    }
}
