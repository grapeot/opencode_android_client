package com.yage.opencode_client

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.mergeRefreshedSessionsPreservingLocalActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMergeTest {
    @Test
    fun `preserves selected local session outside refreshed window`() {
        val selected = Session(id = "ses_old", directory = "/old", title = "Old target")
        val recent = Session(id = "ses_recent", directory = "/new", title = "Recent")

        val merged = mergeRefreshedSessionsPreservingLocalActivity(
            refreshed = listOf(recent),
            local = listOf(selected),
            currentSessionId = selected.id
        )

        assertEquals(listOf(selected, recent), merged)
    }


    @Test
    fun `fresher server data wins title and time`() {
        val refreshed = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Server Authoritative",
                time = Session.TimeInfo(updated = 2_000)
            )
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Local Old",
                time = Session.TimeInfo(updated = 1_000)
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(refreshed, local)

        assertEquals("Server Authoritative", merged.single().title)
        assertEquals(2_000L, merged.single().time?.updated)
    }

    @Test
    fun `local activity bump is preserved when server snapshot is older`() {
        // Models the real send-message flow: the local session was just bumped (newer time)
        // but the full refresh returned a snapshot that predates the bump.
        val refreshed = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Server Title",
                time = Session.TimeInfo(updated = 1_000)
            )
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Server Title",
                time = Session.TimeInfo(updated = 5_000)
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(refreshed, local)

        // Local newer time is preserved (original protection: keeps ordering/activity).
        assertEquals(5_000L, merged.single().time?.updated)
    }

    @Test
    fun `freshly upserted SSE title survives a stale concurrent refresh`() {
        // Reproduces the title-refresh bug: a session.updated SSE upserted the generated title
        // into local state (with a fresh time), then a concurrently-issued full refresh returns
        // a stale snapshot that still has the placeholder title and an older time. The merge must
        // keep the server-authoritative title we already received via SSE.
        val refreshed = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "New session - 1700000000",
                time = Session.TimeInfo(updated = 1_000)
            )
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Pythagorean theorem: history, proof, engineering",
                time = Session.TimeInfo(updated = 2_000)
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(refreshed, local)

        assertEquals(
            "Pythagorean theorem: history, proof, engineering",
            merged.single().title
        )
        // The local (newer) activity timestamp is still respected.
        assertEquals(2_000L, merged.single().time?.updated)
    }

    @Test
    fun `null server time treated as older so local title is preserved`() {
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Placeholder", time = null)
        )
        val local = listOf(
            Session(
                id = "s1",
                directory = "/tmp/project",
                title = "Real Title",
                time = Session.TimeInfo(updated = 2_000)
            )
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(refreshed, local)

        assertEquals("Real Title", merged.single().title)
        assertEquals(2_000L, merged.single().time?.updated)
    }

    @Test
    fun `no local activity signal keeps server title`() {
        // When neither side carries a time signal, the server snapshot remains authoritative.
        val refreshed = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Server", time = null)
        )
        val local = listOf(
            Session(id = "s1", directory = "/tmp/project", title = "Local", time = null)
        )

        val merged = mergeRefreshedSessionsPreservingLocalActivity(refreshed, local)

        assertEquals("Server", merged.single().title)
    }
}
