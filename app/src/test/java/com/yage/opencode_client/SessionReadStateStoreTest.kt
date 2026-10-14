package com.yage.opencode_client

import com.yage.opencode_client.util.SessionReadState
import org.junit.Assert.*
import org.junit.Test

class SessionReadStateStoreTest {
    private val prefs = FakeSharedPreferences()
    private val store = testSessionReadStateStore(prefs)

    @Test fun `legacy JSON retains timestamps and accepts only positive nonself source tokens`() {
        prefs.edit().putString("session_read_state_test-host_p", """{"lastViewedAt":10,"failedAt":20}""").apply()
        assertTrue(store.blueAcknowledgements("p").isEmpty())
        store.acknowledgeBlue("p", mapOf("c" to 100, "d" to 200, "p" to 50, "zero" to 0, "negative" to -1))
        store.acknowledgeBlue("p", mapOf("c" to 101))
        val restored = testSessionReadStateStore(prefs)
        assertEquals(mapOf("c" to 101L, "d" to 200L), restored.blueAcknowledgements("p"))
        assertEquals(10L, restored.lastViewedAt("p"))
        assertEquals(20L, restored.failedAt("p"))
    }

    @Test fun `deleting absent source removes references from every row only in current host`() {
        store.acknowledgeBlue("p", mapOf("c" to 100, "d" to 200))
        store.acknowledgeBlue("g", mapOf("c" to 100))
        store.selectHost("other")
        store.acknowledgeBlue("p", mapOf("c" to 300))
        store.selectHost("test-host")
        store.remove("c")
        assertEquals(mapOf("d" to 200L), store.blueAcknowledgements("p"))
        assertTrue(store.blueAcknowledgements("g").isEmpty())
        store.remove("p")
        assertFalse(store.all().containsKey("p"))
        val restored = testSessionReadStateStore(prefs)
        restored.selectHost("other")
        assertEquals(mapOf("c" to 300L), restored.blueAcknowledgements("p"))
    }

    @Test
    fun `viewing and failing preserve the other timestamp and persist across restart`() {
        assertNull(store.lastViewedAt("s1"))
        assertNull(store.failedAt("s1"))
        store.markViewed("s1", 10L)
        store.markFailed("s1", 20L)
        store.markViewed("s1", 30L)
        assertEquals(SessionReadState(30L, 20L), store.all()["s1"])
        val rebuilt = testSessionReadStateStore(prefs)
        assertEquals(30L, rebuilt.lastViewedAt("s1"))
        assertEquals(20L, rebuilt.failedAt("s1"))
        assertTrue(prefs.contains("session_read_state_test-host_s1"))
    }

    @Test
    fun `clear failure preserves viewing and remove only deletes the named session`() {
        store.markViewed("s1", 10L)
        store.markFailed("s1", 20L)
        store.markFailed("s2", 25L)
        store.clearFailed("s1")
        store.clearFailed("absent")
        assertEquals(SessionReadState(10L), store.all()["s1"])
        assertFalse(store.all().containsKey("absent"))
        store.remove("s1")
        assertNull(store.lastViewedAt("s1"))
        assertEquals(mapOf("s2" to SessionReadState(failedAt = 25L)), testSessionReadStateStore(prefs).all())
    }

    @Test
    fun `all ignores unrelated and corrupt preferences`() {
        prefs.edit().putString("unrelated", "{}").putString("session_read_state_test-host_broken", "bad json").apply()
        store.markFailed("valid", 50L)
        assertEquals(mapOf("valid" to SessionReadState(failedAt = 50L)), store.all())
    }

    @Test
    fun `hosts with the same session id keep independent read and failure state`() {
        store.selectHost("host-a")
        store.markViewed("shared", 10L)
        store.markFailed("shared", 20L)
        store.selectHost("host-b")
        assertNull(store.lastViewedAt("shared"))
        assertNull(store.failedAt("shared"))
        assertTrue(store.all().isEmpty())
        store.markViewed("shared", 30L)
        store.markFailed("shared", 40L)
        assertEquals(mapOf("shared" to SessionReadState(30L, 40L)), store.all())
        store.selectHost("host-a")
        assertEquals(mapOf("shared" to SessionReadState(10L, 20L)), store.all())
        store.clearFailed("shared")
        store.remove("shared")
        val rebuilt = testSessionReadStateStore(prefs)
        rebuilt.selectHost("host-b")
        assertEquals(mapOf("shared" to SessionReadState(30L, 40L)), rebuilt.all())
        rebuilt.selectHost("host-a")
        assertTrue(rebuilt.all().isEmpty())
    }
}
