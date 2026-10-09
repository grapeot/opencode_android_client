package com.yage.opencode_client

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.session.attentionCountsBySession
import com.yage.opencode_client.ui.session.buildSessionTree
import com.yage.opencode_client.ui.session.descendantBusyCountsBySession
import com.yage.opencode_client.ui.session.flattenVisibleTree
import com.yage.opencode_client.ui.session.prioritizeAttention
import com.yage.opencode_client.ui.session.runningDescendantSessions
import org.junit.Assert.*
import org.junit.Test

class SessionTreeTest {

    private fun session(id: String, parentId: String? = null, updated: Long = 0) =
        Session(id = id, directory = "/tmp", parentId = parentId, time = Session.TimeInfo(updated = updated))

    @Test
    fun `buildSessionTree builds hierarchy`() {
        val sessions = listOf(
            session("parent", updated = 100),
            session("child1", parentId = "parent", updated = 90),
            session("child2", parentId = "parent", updated = 80)
        )
        val tree = buildSessionTree(sessions)
        assertEquals(1, tree.size)
        assertEquals("parent", tree[0].session.id)
        assertEquals(2, tree[0].children.size)
        val childIds = tree[0].children.map { it.session.id }.sorted()
        assertEquals(listOf("child1", "child2"), childIds)
    }

    @Test
    fun `buildSessionTree orphaned children become roots`() {
        val sessions = listOf(
            session("orphan", parentId = "missing-parent", updated = 90)
        )
        val tree = buildSessionTree(sessions)
        assertEquals(1, tree.size)
        assertEquals("orphan", tree[0].session.id)
    }

    @Test
    fun `flattenVisibleTree when collapsed shows only roots`() {
        val sessions = listOf(
            session("root", updated = 100),
            session("child", parentId = "root", updated = 90)
        )
        val tree = buildSessionTree(sessions)
        val flat = flattenVisibleTree(tree, expandedIds = emptySet())
        assertEquals(1, flat.size)
        assertEquals("root", flat[0].first.session.id)
        assertEquals(0, flat[0].second)
    }

    @Test
    fun `flattenVisibleTree when expanded shows children`() {
        val sessions = listOf(
            session("root", updated = 100),
            session("child", parentId = "root", updated = 90)
        )
        val tree = buildSessionTree(sessions)
        val flat = flattenVisibleTree(tree, expandedIds = setOf("root"))
        assertEquals(2, flat.size)
        assertEquals("root", flat[0].first.session.id)
        assertEquals(0, flat[0].second)
        assertEquals("child", flat[1].first.session.id)
        assertEquals(1, flat[1].second)
    }

    @Test
    fun `attention counts roll up through all ancestors`() {
        val sessions = listOf(
            session("root"),
            session("child", parentId = "root"),
            session("grandchild", parentId = "child")
        )

        val counts = attentionCountsBySession(
            sessions,
            attentionSessionIds = listOf("grandchild", "grandchild")
        )

        assertEquals(2, counts["grandchild"])
        assertEquals(2, counts["child"])
        assertEquals(2, counts["root"])
    }

    @Test
    fun `attention sessions sort ahead of newer sessions`() {
        val sessions = listOf(
            session("newer", updated = 200),
            session("attention", updated = 100)
        )
        val tree = buildSessionTree(sessions)

        val prioritized = prioritizeAttention(tree, mapOf("attention" to 1))

        assertEquals(listOf("attention", "newer"), prioritized.map { it.session.id })
    }

    @Test
    fun `descendant busy counts roll up and exclude self`() {
        val sessions = listOf(
            session("root"),
            session("child", parentId = "root"),
            session("grandchild", parentId = "child")
        )
        val statuses = mapOf(
            "child" to SessionStatus(type = "busy"),
            "grandchild" to SessionStatus(type = "busy")
        )

        val counts = descendantBusyCountsBySession(sessions, statuses)

        assertEquals(2, counts["root"])
        assertEquals(1, counts["child"])
        assertNull(counts["grandchild"])
    }

    @Test
    fun `descendant busy counts include retry and ignore idle`() {
        val sessions = listOf(
            session("root"),
            session("retrying", parentId = "root"),
            session("idle", parentId = "root")
        )
        val statuses = mapOf(
            "retrying" to SessionStatus(type = "retry"),
            "idle" to SessionStatus(type = "idle")
        )

        val counts = descendantBusyCountsBySession(sessions, statuses)

        assertEquals(1, counts["root"])
        assertNull(counts["idle"])
    }

    @Test
    fun `descendant busy counts ignore busy self with no children`() {
        val sessions = listOf(session("root"))
        val statuses = mapOf("root" to SessionStatus(type = "busy"))

        val counts = descendantBusyCountsBySession(sessions, statuses)

        assertNull(counts["root"])
    }

    @Test
    fun `descendant busy sessions sort ahead of newer sessions`() {
        val sessions = listOf(
            session("newer", updated = 200),
            session("delegating", updated = 100)
        )
        val tree = buildSessionTree(sessions)

        val prioritized = prioritizeAttention(
            tree,
            attentionCounts = emptyMap(),
            descendantBusyCounts = mapOf("delegating" to 1)
        )

        assertEquals(listOf("delegating", "newer"), prioritized.map { it.session.id })
    }

    @Test
    fun `running descendant sessions returns busy children newest first`() {
        val sessions = listOf(
            session("root", updated = 100),
            session("older", parentId = "root", updated = 50),
            session("newer", parentId = "root", updated = 80),
            session("idle-child", parentId = "root", updated = 90)
        )
        val statuses = mapOf(
            "older" to SessionStatus(type = "busy"),
            "newer" to SessionStatus(type = "retry"),
            "idle-child" to SessionStatus(type = "idle")
        )

        val running = runningDescendantSessions(sessions, statuses, "root")

        assertEquals(listOf("newer", "older"), running.map { it.id })
    }

    @Test
    fun `running descendant sessions cover grandchildren`() {
        val sessions = listOf(
            session("root", updated = 100),
            session("child", parentId = "root", updated = 90),
            session("grandchild", parentId = "child", updated = 80)
        )
        val statuses = mapOf("grandchild" to SessionStatus(type = "busy"))

        val running = runningDescendantSessions(sessions, statuses, "root")

        assertEquals(listOf("grandchild"), running.map { it.id })
    }
}
