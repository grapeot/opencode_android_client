package com.yage.opencode_client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatsStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = testSessionStatsStore(prefs)

    @Test
    fun `seed from window creates entry and is idempotent on replay`() {
        store.seedOrReconcile("s1", setOf("u1", "u2"), setOf("t1", "t2", "t3"))

        val entry = store.entry("s1")
        assertNotNull(entry)
        assertEquals(2, entry!!.rounds)
        assertEquals(3, entry.toolCalls)

        store.seedOrReconcile("s1", setOf("u1", "u2"), setOf("t1", "t2", "t3"))
        assertEquals(2, store.entry("s1")!!.rounds)
        assertEquals(3, store.entry("s1")!!.toolCalls)
    }

    @Test
    fun `record increments once per id and ignores replays`() {
        store.seedOrReconcile("s1", setOf("u1"), setOf("t1"))

        store.recordUserMessage("s1", "u1")
        assertEquals(1, store.entry("s1")!!.rounds)

        store.recordUserMessage("s1", "u2")
        assertEquals(2, store.entry("s1")!!.rounds)

        store.recordToolPart("s1", "t9")
        assertEquals(2, store.entry("s1")!!.toolCalls)

        store.recordToolPart("s1", "t9")
        assertEquals(2, store.entry("s1")!!.toolCalls)
    }

    @Test
    fun `reconcile after incremental events adds only unseen ids`() {
        store.seedOrReconcile("s1", setOf("u1"), setOf("t1"))
        store.recordUserMessage("s1", "u2")
        store.recordToolPart("s1", "t2")

        // A refresh reloaded the whole window, including already-counted ids.
        store.seedOrReconcile("s1", setOf("u1", "u2"), setOf("t1", "t2"))

        assertEquals(2, store.entry("s1")!!.rounds)
        assertEquals(2, store.entry("s1")!!.toolCalls)
    }

    @Test
    fun `reset drops the entry so the next load reseeds it`() {
        store.seedOrReconcile("s1", setOf("u1", "u2", "u3"), setOf("t1", "t2"))
        store.reset("s1")

        assertNull(store.entry("s1"))

        // Post-revert window is shorter; reseed must not keep the old total.
        store.seedOrReconcile("s1", setOf("u1"), setOf())
        assertEquals(1, store.entry("s1")!!.rounds)
        assertEquals(0, store.entry("s1")!!.toolCalls)
    }

    @Test
    fun `sessions are keyed independently`() {
        store.seedOrReconcile("s1", setOf("u1"), setOf("t1", "t2"))
        store.seedOrReconcile("s2", setOf("u1", "u2", "u3"), setOf())

        assertEquals(1, store.entry("s1")!!.rounds)
        assertEquals(3, store.entry("s2")!!.rounds)
        assertNull(store.entry("s3"))
    }

    @Test
    fun `entries survive a store rebuild (restart)`() {
        store.recordUserMessage("s1", "u1")
        store.recordToolPart("s1", "t1")

        val rebuilt = testSessionStatsStore(prefs)
        assertEquals(1, rebuilt.entry("s1")!!.rounds)
        assertEquals(1, rebuilt.entry("s1")!!.toolCalls)
    }

}
