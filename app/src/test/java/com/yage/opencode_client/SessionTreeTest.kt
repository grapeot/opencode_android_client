package com.yage.opencode_client

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.session.attentionCountsBySession
import com.yage.opencode_client.ui.session.buildSessionTree
import com.yage.opencode_client.ui.session.flattenVisibleTree
import com.yage.opencode_client.ui.session.prioritizeAttention
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
}
