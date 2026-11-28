package com.yage.opencode_client

import android.util.Log
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionPage
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.SessionListFetch
import com.yage.opencode_client.ui.SessionListPaging
import com.yage.opencode_client.ui.applySessionListFetch
import com.yage.opencode_client.ui.archivedSubtreeTops
import com.yage.opencode_client.ui.fetchSessionList
import com.yage.opencode_client.ui.isKnownServerRelease
import com.yage.opencode_client.ui.launchLoadArchivedChildren
import com.yage.opencode_client.ui.launchLoadArchivedSessions
import com.yage.opencode_client.ui.launchLoadMoreSessions
import com.yage.opencode_client.ui.launchLoadSessions
import com.yage.opencode_client.ui.launchSetSessionArchived
import com.yage.opencode_client.ui.noteArchivedInPlace
import com.yage.opencode_client.ui.removeSessionFromEvent
import com.yage.opencode_client.ui.serverMayFilterArchivedSessions
import com.yage.opencode_client.ui.sessionEventApplies
import com.yage.opencode_client.ui.upsertSessionFromEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListPagingTest {
    private val dir = "/work/project"
    private lateinit var repository: OpenCodeRepository

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        repository = mockk(relaxed = true)
        coEvery { repository.getInstanceDirectory() } returns Result.success(dir)
        restoredRoots(emptyList())
    }

    private fun restoredRoots(rows: List<Session>) {
        coEvery { repository.getSessionPage(dir, true, 300, null, true) } returns Result.success(SessionPage(rows, null))
    }

    @After
    fun tearDown() = unmockkAll()

    private fun row(
        id: String,
        updated: Long,
        archived: Long? = null,
        parent: String? = null,
        directory: String = dir,
    ) = Session(id = id, directory = directory, parentId = parent, time = Session.TimeInfo(updated = updated, archived = archived))

    private fun state(version: String? = "1.3.0", paging: SessionListPaging = SessionListPaging(), sessions: List<Session> = emptyList()) =
        MutableStateFlow(AppState(isConnected = true, serverVersion = version, sessionPaging = paging, sessions = sessions))

    private fun activePage(cursor: Long?, rows: List<Session>, next: Long?) {
        coEvery { repository.getSessionPage(dir, false, 400, cursor) } returns Result.success(SessionPage(rows, next))
    }

    private fun httpError(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    private fun TestScope.load(state: MutableStateFlow<AppState>, resetPaging: Boolean = false) {
        launchLoadSessions(this, repository, state, {}, {}, {}, resetPaging = resetPaging)
        advanceUntilIdle()
    }

    // Version gate

    @Test
    fun `version gate skips releases before 1_2_11 and probes unknown builds`() {
        assertFalse(serverMayFilterArchivedSessions("1.2.10"))
        assertFalse(serverMayFilterArchivedSessions("v1.1.99"))
        assertTrue(serverMayFilterArchivedSessions("1.2.11"))
        assertTrue(serverMayFilterArchivedSessions("1.3"))
        assertTrue(serverMayFilterArchivedSessions("2.0.0-beta.1"))
        assertTrue(serverMayFilterArchivedSessions(null))
        assertTrue(serverMayFilterArchivedSessions("local"))
        assertTrue(serverMayFilterArchivedSessions("0.0.0-dev-202611"))
    }

    @Test
    fun `known release parsing treats local and zero builds as unknown`() {
        assertTrue(isKnownServerRelease("1.2.11"))
        assertTrue(isKnownServerRelease("v1.4"))
        assertFalse(isKnownServerRelease(null))
        assertFalse(isKnownServerRelease("local"))
        assertFalse(isKnownServerRelease("0.0.0-main"))
    }

    // First page and probing

    @Test
    fun `first load fetches one active page for the instance directory`() = runTest {
        activePage(null, listOf(row("a", 300), row("b", 200)), next = 200)
        val state = state()
        load(state)

        val value = state.value
        assertEquals(listOf("a", "b"), value.sessions.map { it.id })
        assertEquals(dir, value.sessionPaging.filteredDirectory)
        assertEquals(200L, value.sessionPaging.activeCursor)
        assertTrue(value.hasMoreSessions)
        assertEquals(true, value.sessionPaging.serverFiltersArchived)
        coVerify(exactly = 0) { repository.getSessions(any()) }
    }

    @Test
    fun `empty instance directory falls back to the project worktree`() = runTest {
        coEvery { repository.getInstanceDirectory() } returns Result.success("")
        coEvery { repository.getCurrentProjectWorktree() } returns Result.success(dir)
        activePage(null, listOf(row("a", 1)), next = null)
        val state = state()
        load(state)

        assertEquals(dir, state.value.sessionPaging.filteredDirectory)
        assertFalse(state.value.hasMoreSessions)
    }

    @Test
    fun `server at root keeps the legacy window and caches the answer`() = runTest {
        coEvery { repository.getInstanceDirectory() } returns Result.success("/")
        coEvery { repository.getSessions(400) } returns Result.success(listOf(row("legacy", 1)))
        val state = state()
        load(state)
        load(state)

        assertNull(state.value.sessionPaging.filteredDirectory)
        assertEquals("", state.value.sessionPaging.defaultDirectory)
        assertEquals(listOf("legacy"), state.value.sessions.map { it.id })
        coVerify(exactly = 1) { repository.getInstanceDirectory() }
        coVerify(exactly = 0) { repository.getSessionPage(any(), any(), any(), any()) }
    }

    @Test
    fun `unsupported route falls back to legacy and is not probed again`() = runTest {
        coEvery { repository.getSessionPage(dir, false, 400, null) } returns Result.failure(httpError(404))
        coEvery { repository.getSessions(400) } returns Result.success(listOf(row("legacy", 1)))
        val state = state()
        load(state)
        load(state)

        assertEquals(false, state.value.sessionPaging.serverFiltersArchived)
        assertEquals(listOf("legacy"), state.value.sessions.map { it.id })
        coVerify(exactly = 1) { repository.getSessionPage(any(), any(), any(), any()) }
    }

    @Test
    fun `html 200 from a packaged server counts as unsupported`() = runTest {
        coEvery { repository.getSessionPage(dir, false, 400, null) } returns Result.failure(SerializationException("html"))
        coEvery { repository.getSessions(400) } returns Result.success(listOf(row("legacy", 1)))
        val state = state(version = null)
        load(state)

        assertEquals(false, state.value.sessionPaging.serverFiltersArchived)
        assertEquals(listOf("legacy"), state.value.sessions.map { it.id })
    }

    @Test
    fun `server error on a known release surfaces without downgrading`() = runTest {
        coEvery { repository.getSessionPage(dir, false, 400, null) } returns Result.failure(httpError(500))
        val state = state(version = "1.3.0")
        load(state)

        assertNull(state.value.sessionPaging.serverFiltersArchived)
        assertTrue(state.value.error.orEmpty().startsWith("Failed to load sessions"))
        coVerify(exactly = 0) { repository.getSessions(any()) }
    }

    @Test
    fun `probe error on an unknown version loads legacy this time only`() = runTest {
        coEvery { repository.getSessionPage(dir, false, 400, null) } returns Result.failure(httpError(502))
        coEvery { repository.getSessions(400) } returns Result.success(listOf(row("legacy", 1)))
        val state = state(version = "local")
        load(state)

        assertNull(state.value.sessionPaging.serverFiltersArchived)
        assertEquals(listOf("legacy"), state.value.sessions.map { it.id })

        activePage(null, listOf(row("a", 5)), next = null)
        load(state)
        assertEquals(true, state.value.sessionPaging.serverFiltersArchived)
        assertEquals(listOf("a"), state.value.sessions.map { it.id })
    }

    @Test
    fun `old server versions are not probed`() = runTest {
        coEvery { repository.getSessions(400) } returns Result.success(listOf(row("legacy", 1)))
        val state = state(version = "1.2.10")
        load(state)

        assertEquals(false, state.value.sessionPaging.serverFiltersArchived)
        coVerify(exactly = 0) { repository.getSessionPage(any(), any(), any(), any()) }
        // The directory still resolves, so SSE events can be scoped to it.
        assertEquals(dir, state.value.sessionPaging.defaultDirectory)
    }

    @Test
    fun `unresolved directory on a filtering host keeps the current list`() = runTest {
        coEvery { repository.getInstanceDirectory() } returns Result.failure(IllegalStateException("down"))
        coEvery { repository.getCurrentProjectWorktree() } returns Result.success(null)
        val existing = listOf(row("kept", 1))
        val state = state(paging = SessionListPaging(serverFiltersArchived = true), sessions = existing)
        assertNull(fetchSessionList(repository, state, 400).getOrThrow())
        load(state)

        assertEquals(existing, state.value.sessions)
        coVerify(exactly = 0) { repository.getSessions(any()) }
    }

    // Load more and incremental refresh

    @Test
    fun `load more follows the cursor without re-downloading earlier pages`() = runTest {
        activePage(null, listOf(row("a", 300), row("b", 200)), next = 200)
        activePage(200, listOf(row("c", 150), row("old-archived", 140, archived = 1)), next = 100)
        val state = state()
        load(state)
        launchLoadMoreSessions(this, repository, state) {}
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), state.value.sessions.map { it.id })
        assertEquals(100L, state.value.sessionPaging.activeCursor)
        assertTrue(state.value.sessionPaging.hasLoadedDeeperActivePages)
        assertFalse(state.value.isLoadingMoreSessions)
        coVerify(exactly = 1) { repository.getSessionPage(dir, false, 400, null) }
        coVerify(exactly = 0) { repository.getSessions(any()) }
    }

    @Test
    fun `a cursor that does not advance ends paging`() = runTest {
        activePage(null, listOf(row("a", 300)), next = 300)
        activePage(300, listOf(row("b", 300)), next = 300)
        val state = state()
        load(state)
        launchLoadMoreSessions(this, repository, state) {}
        advanceUntilIdle()

        assertNull(state.value.sessionPaging.activeCursor)
        assertFalse(state.value.hasMoreSessions)
    }

    @Test
    fun `incremental refresh drops vanished rows and keeps deeper pages and the deepest cursor`() = runTest {
        activePage(null, listOf(row("a", 300), row("b", 200)), next = 200)
        activePage(200, listOf(row("c", 150)), next = 100)
        val state = state()
        load(state)
        launchLoadMoreSessions(this, repository, state) {}
        advanceUntilIdle()

        // "b" was archived elsewhere; the new first page reaches down to 180.
        activePage(null, listOf(row("new", 400), row("a", 300), row("d", 180)), next = 180)
        load(state)

        assertEquals(listOf("new", "a", "d", "c"), state.value.sessions.map { it.id })
        assertEquals(100L, state.value.sessionPaging.activeCursor)
        assertTrue(state.value.sessionPaging.hasLoadedDeeperActivePages)
    }

    @Test
    fun `pull to refresh resets deeper pages`() = runTest {
        activePage(null, listOf(row("a", 300)), next = 300)
        activePage(300, listOf(row("c", 150)), next = 100)
        val state = state()
        load(state)
        launchLoadMoreSessions(this, repository, state) {}
        advanceUntilIdle()

        load(state, resetPaging = true)

        assertEquals(listOf("a"), state.value.sessions.map { it.id })
        assertEquals(300L, state.value.sessionPaging.activeCursor)
        assertFalse(state.value.sessionPaging.hasLoadedDeeperActivePages)
    }

    @Test
    fun `a complete first page drops every other active row`() = runTest {
        activePage(null, listOf(row("a", 300)), next = 300)
        activePage(300, listOf(row("c", 150)), next = null)
        val state = state()
        load(state)
        launchLoadMoreSessions(this, repository, state) {}
        advanceUntilIdle()

        activePage(null, listOf(row("a", 300)), next = null)
        load(state)

        assertEquals(listOf("a"), state.value.sessions.map { it.id })
        assertFalse(state.value.hasMoreSessions)
    }

    @Test
    fun `a selected deep row survives a pull to refresh`() = runTest {
        val selected = row("deep", 50)
        activePage(null, listOf(row("a", 300)), next = 300)
        val state = state(sessions = listOf(selected))
        state.value = state.value.copy(currentSessionId = "deep")
        load(state, resetPaging = true)

        assertEquals(setOf("a", "deep"), state.value.sessions.map { it.id }.toSet())
        assertEquals("deep", state.value.currentSessionId)
    }

    @Test
    fun `a legacy window clears filtered paging and load more stays on the legacy path`() = runTest {
        val filtered = AppState(
            sessions = listOf(row("a", 1)),
            sessionPaging = SessionListPaging(filteredDirectory = dir, activeCursor = 5, hasLoadedDeeperActivePages = true,
                loadedArchivedIds = setOf("x"), hasLoadedArchived = true),
        )
        val next = applySessionListFetch(filtered, SessionListFetch(listOf(row("legacy", 2)), hasMore = true), keepDeeperPages = true)

        assertNull(next.sessionPaging.filteredDirectory)
        assertNull(next.sessionPaging.activeCursor)
        assertFalse(next.sessionPaging.hasLoadedArchived)
        assertTrue(next.hasMoreSessions)

        coEvery { repository.getSessions(800) } returns Result.success(listOf(row("legacy", 2)))
        val state = MutableStateFlow(next.copy(isConnected = true))
        launchLoadMoreSessions(this, repository, state) {}
        advanceUntilIdle()
        coVerify { repository.getSessions(800) }
        coVerify(exactly = 0) { repository.getSessionPage(any(), any(), any(), any()) }
    }

    @Test
    fun `a directory change resets archived pages and deeper rows`() {
        val current = AppState(
            sessions = listOf(row("old", 1), row("arch", 1, archived = 1)),
            sessionPaging = SessionListPaging(filteredDirectory = "/other", loadedArchivedIds = setOf("arch"), hasLoadedArchived = true,
                activeCursor = 3, hasLoadedDeeperActivePages = true),
        )
        val next = applySessionListFetch(current, SessionListFetch(listOf(row("a", 9)), true, dir, nextCursor = 9), keepDeeperPages = true)

        assertEquals(listOf("a"), next.sessions.map { it.id })
        assertEquals(dir, next.sessionPaging.filteredDirectory)
        assertFalse(next.sessionPaging.hasLoadedArchived)
        assertEquals(9L, next.sessionPaging.activeCursor)
    }

    // Archived section

    @Test
    fun `archived subtree tops include a child archived under an active parent`() {
        val rows = listOf(
            row("root", 9, archived = 1),
            row("child-of-archived", 8, archived = 1, parent = "root"),
            row("lone-child", 7, archived = 1, parent = "active-parent"),
            row("active", 6),
        )
        val tops = archivedSubtreeTops(rows, known = listOf(row("active-parent", 10)))
        assertEquals(listOf("root", "lone-child"), tops.map { it.id })
    }

    @Test
    fun `archived load pages until a page of tops and keeps the cursor`() = runTest {
        val first = (1..30).map { row("arch-$it", 1000L - it, archived = 1) } + listOf(row("active", 990))
        val second = (31..60).map { row("arch-$it", 1000L - it, archived = 1) }
        coEvery { repository.getSessionPage(dir, true, 300, null) } returns Result.success(SessionPage(first, 900))
        coEvery { repository.getSessionPage(dir, true, 300, 900) } returns Result.success(SessionPage(second, 800))
        val state = state(paging = SessionListPaging(filteredDirectory = dir))
        launchLoadArchivedSessions(this, repository, state, reset = false) {}
        advanceUntilIdle()

        val paging = state.value.sessionPaging
        assertEquals(60, paging.loadedArchivedIds.size)
        assertEquals(800L, paging.archivedCursor)
        assertTrue(paging.hasMoreArchived)
        assertTrue(paging.canLoadMoreArchived)
        assertFalse(paging.isLoadingArchived)
        assertTrue(state.value.sessions.none { it.id == "active" })
    }

    @Test
    fun `archived load marks the end when the server runs out`() = runTest {
        coEvery { repository.getSessionPage(dir, true, 300, null) } returns
            Result.success(SessionPage(listOf(row("arch", 5, archived = 1)), null))
        val state = state(paging = SessionListPaging(filteredDirectory = dir))
        launchLoadArchivedSessions(this, repository, state, reset = false) {}
        advanceUntilIdle()

        assertFalse(state.value.sessionPaging.hasMoreArchived)
        assertTrue(state.value.sessionPaging.hasLoadedArchived)
        assertNull(state.value.sessionPaging.archivedCursor)
    }

    @Test
    fun `archived load does nothing on a legacy list`() = runTest {
        val state = state()
        launchLoadArchivedSessions(this, repository, state, reset = false) {}
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.getSessionPage(any(), any(), any(), any()) }
    }

    @Test
    fun `only paged-in archived rows survive a refresh`() {
        val current = AppState(
            sessions = listOf(row("a", 9), row("paged", 5, archived = 1), row("archived-here", 4, archived = 1)),
            sessionPaging = SessionListPaging(filteredDirectory = dir, loadedArchivedIds = setOf("paged"), hasLoadedArchived = true),
        )
        val next = applySessionListFetch(current, SessionListFetch(listOf(row("a", 9)), false, dir, null), keepDeeperPages = true)
        assertEquals(listOf("a", "paged"), next.sessions.map { it.id })
    }

    @Test
    fun `rows archived on this device stay when inside the loaded window`() {
        val current = AppState(
            sessions = listOf(row("paged", 50, archived = 1), row("recent", 80, archived = 2), row("older", 10, archived = 2)),
            sessionPaging = SessionListPaging(filteredDirectory = dir, loadedArchivedIds = setOf("paged"),
                hasLoadedArchived = true, hasMoreArchived = true),
        )
        val next = noteArchivedInPlace(current, setOf("recent", "older"))
        assertEquals(setOf("paged", "recent"), next.sessionPaging.loadedArchivedIds)
    }

    @Test
    fun `archived rows lazy load children and retry after failure`() = runTest {
        coEvery { repository.getSessionChildren("root") } returns Result.failure(IllegalStateException("down"))
        val state = state(
            paging = SessionListPaging(filteredDirectory = dir, hasLoadedArchived = true),
            sessions = listOf(row("root", 5, archived = 1)),
        )
        assertTrue(state.value.sessionPaging.mayHaveUnloadedChildren(state.value.sessions.single()))
        launchLoadArchivedChildren(this, repository, state, "root")
        advanceUntilIdle()
        assertFalse("root" in state.value.sessionPaging.archivedChildrenLoadedIds)

        coEvery { repository.getSessionChildren("root") } returns
            Result.success(listOf(row("kid", 4, archived = 1, parent = "root"), row("stranger", 4, archived = 1, parent = "x")))
        launchLoadArchivedChildren(this, repository, state, "root")
        advanceUntilIdle()

        assertEquals(listOf("root", "kid"), state.value.sessions.map { it.id })
        assertTrue("kid" in state.value.sessionPaging.loadedArchivedIds)
        assertFalse(state.value.sessionPaging.mayHaveUnloadedChildren(state.value.sessions.first()))
    }

    @Test
    fun `restore fetches every archived descendant before patching parent first`() = runTest {
        coEvery { repository.getSessionChildren("root") } returns Result.success(listOf(row("kid", 4, archived = 1, parent = "root")))
        coEvery { repository.getSessionChildren("kid") } returns Result.success(listOf(row("grandkid", 3, archived = 1, parent = "kid")))
        coEvery { repository.getSessionChildren("grandkid") } returns Result.success(emptyList())
        val patched = mutableListOf<String>()
        coEvery { repository.updateSessionArchived(any(), -1L) } answers {
            val id = firstArg<String>()
            patched += id
            Result.success(row(id, 9))
        }
        val state = state(paging = SessionListPaging(filteredDirectory = dir), sessions = listOf(row("root", 5, archived = 1)))
        launchSetSessionArchived(this, repository, state, "root", archived = false)
        advanceUntilIdle()

        assertEquals(listOf("root", "kid", "grandkid"), patched)
        assertTrue(state.value.sessions.none { it.isArchived })
    }

    @Test
    fun `restore refuses when a descendant level cannot be read`() = runTest {
        coEvery { repository.getSessionChildren("root") } returns Result.failure(IllegalStateException("down"))
        val state = state(paging = SessionListPaging(filteredDirectory = dir), sessions = listOf(row("root", 5, archived = 1)))
        launchSetSessionArchived(this, repository, state, "root", archived = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateSessionArchived(any(), any()) }
        assertTrue(state.value.error.orEmpty().startsWith("Failed to restore session"))
    }

    // Restored rows (archived = -1)

    @Test
    fun `a full load lists restored roots as active and an incremental refresh keeps them`() = runTest {
        activePage(null, listOf(row("a", 300)), next = 300)
        restoredRoots(listOf(row("restored", 350, archived = -1), row("still-archived", 340, archived = 9)))
        val state = state()
        load(state)

        assertEquals(listOf("a", "restored"), state.value.sessions.map { it.id })
        val restored = state.value.sessions.single { it.id == "restored" }
        assertTrue(restored.isRestored)
        assertFalse(restored.isArchived)
        assertTrue(state.value.sessionPaging.mayHaveUnloadedChildren(restored))

        // An incremental refresh does not rescan and does not treat the row as vanished.
        load(state)
        assertTrue(state.value.sessions.any { it.id == "restored" })
        coVerify(exactly = 1) { repository.getSessionPage(dir, true, 300, null, true) }

        // Pull to refresh rescans; a row archived again elsewhere leaves.
        restoredRoots(emptyList())
        load(state, resetPaging = true)
        assertEquals(listOf("a"), state.value.sessions.map { it.id })
        coVerify(exactly = 2) { repository.getSessionPage(dir, true, 300, null, true) }
    }

    @Test
    fun `restored rows keep their loaded children and are not duplicated as deeper rows`() = runTest {
        activePage(null, listOf(row("a", 300)), next = 300)
        restoredRoots(listOf(row("restored", 100, archived = -1)))
        coEvery { repository.getSessionChildren("restored") } returns
            Result.success(listOf(row("kid", 90, archived = -1, parent = "restored")))
        val state = state()
        load(state)
        launchLoadArchivedChildren(this, repository, state, "restored")
        advanceUntilIdle()
        assertEquals(listOf("a", "restored", "kid"), state.value.sessions.map { it.id })

        load(state)
        assertEquals(listOf("a", "restored", "kid"), state.value.sessions.map { it.id })
        restoredRoots(listOf(row("restored", 100, archived = -1)))
        load(state, resetPaging = true)
        assertEquals(listOf("a", "restored", "kid"), state.value.sessions.map { it.id })
    }

    @Test
    fun `a failed restored scan still loads the active page`() = runTest {
        activePage(null, listOf(row("a", 300)), next = null)
        coEvery { repository.getSessionPage(dir, true, 300, null, true) } returns Result.failure(httpError(500))
        val state = state()
        load(state)
        assertEquals(listOf("a"), state.value.sessions.map { it.id })
        assertNull(state.value.error)
    }

    @Test
    fun `the restored scan pages every root and stops at the cap`() = runTest {
        var calls = 0
        coEvery { repository.getSessionPage(dir, true, 300, any(), true) } answers {
            calls += 1
            val cursor = arg<Long?>(3) ?: 100_000L
            Result.success(SessionPage(listOf(row("r$calls", cursor - 1, archived = -1)), cursor - 10))
        }
        val roots = com.yage.opencode_client.ui.fetchRestoredRoots(repository, dir).getOrThrow()
        assertEquals(10, calls)
        assertEquals(10, roots.size)
    }

    // SSE scope

    @Test
    fun `session events decode the server info shape with unmodeled fields`() {
        val info = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive("s1"))
            put("directory", kotlinx.serialization.json.JsonPrimitive(dir))
            put("workspaceID", kotlinx.serialization.json.JsonPrimitive("w"))
            put("permission", kotlinx.serialization.json.JsonArray(emptyList()))
            put("time", kotlinx.serialization.json.buildJsonObject {
                put("updated", kotlinx.serialization.json.JsonPrimitive(5))
                put("compacting", kotlinx.serialization.json.JsonPrimitive(4))
            })
        }
        fun event(type: String) = com.yage.opencode_client.data.model.SSEEvent(
            payload = com.yage.opencode_client.data.model.SSEPayload(
                type = type,
                properties = kotlinx.serialization.json.buildJsonObject {
                    put("sessionID", kotlinx.serialization.json.JsonPrimitive("s1"))
                    put("info", info)
                },
            )
        )
        assertEquals("s1", com.yage.opencode_client.ui.parseSessionCreatedEvent(event("session.created"))?.session?.id)
        assertEquals(5L, com.yage.opencode_client.ui.parseSessionUpdatedEvent(event("session.updated"))?.time?.updated)
    }

    @Test
    fun `session events from other directories are ignored unless listed or selected`() {
        val state = AppState(
            sessions = listOf(row("known", 1, directory = "/elsewhere")),
            currentSessionId = "selected",
            sessionPaging = SessionListPaging(filteredDirectory = dir),
        )
        assertTrue(sessionEventApplies(state, row("new", 2)))
        assertFalse(sessionEventApplies(state, row("foreign", 2, directory = "/elsewhere")))
        assertTrue(sessionEventApplies(state, row("known", 2, directory = "/elsewhere")))
        assertTrue(sessionEventApplies(state, row("selected", 2, directory = "/elsewhere")))
        val unresolved = AppState(sessions = listOf(row("known", 1)), currentSessionId = "selected")
        assertFalse(sessionEventApplies(unresolved, row("anything", 2)))
        assertTrue(sessionEventApplies(unresolved, row("known", 2)))
        assertTrue(sessionEventApplies(unresolved, row("selected", 2)))
    }

    @Test
    fun `an sse upsert during a first page request survives the stale response`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.getSessionPage(dir, false, 400, null) } coAnswers {
            gate.await()
            Result.success(SessionPage(listOf(row("a", 300, archived = null), row("gone", 250)), null))
        }
        val state = state(paging = SessionListPaging(serverFiltersArchived = true, defaultDirectory = dir, filteredDirectory = dir),
            sessions = listOf(row("a", 300), row("gone", 250)))
        launchLoadSessions(this, repository, state, {}, {}, {})
        runCurrent()
        // While the request is in flight: a new sub-agent, an archive elsewhere, and a deletion.
        state.value = state.value.upsertSessionFromEvent(row("sub", 400, parent = "a"))
        state.value = state.value.upsertSessionFromEvent(row("a", 300, archived = 9))
        state.value = state.value.removeSessionFromEvent("gone")
        gate.complete(Unit)
        advanceUntilIdle()

        val byId = state.value.sessions.associateBy { it.id }
        assertTrue("sub" in byId)
        assertTrue(byId.getValue("a").isArchived)
        assertFalse("gone" in byId)
    }

    @Test
    fun `a restored selection missing from the first page is fetched instead of replaced`() = runTest {
        activePage(null, listOf(row("a", 300)), next = null)
        coEvery { repository.getSession("saved") } returns Result.success(row("saved", 5, archived = 3))
        val selected = mutableListOf<String>()
        val state = state()
        state.value = state.value.copy(currentSessionId = "saved")
        launchLoadSessions(this, repository, state, { selected += it }, {}, {})
        advanceUntilIdle()

        assertEquals("saved", state.value.currentSessionId)
        assertTrue(state.value.sessions.any { it.id == "saved" })
        assertTrue(selected.isEmpty())

        coEvery { repository.getSession("saved") } returns Result.failure(httpError(404))
        state.value = state.value.copy(sessions = listOf(row("a", 300)))
        launchLoadSessions(this, repository, state, { selected += it }, {}, {})
        advanceUntilIdle()
        assertEquals(listOf("a"), selected)
    }

    @Test
    fun `a failed archived load offers a retry`() = runTest {
        coEvery { repository.getSessionPage(dir, true, 300, null) } returns Result.failure(httpError(500))
        val state = state(paging = SessionListPaging(filteredDirectory = dir))
        launchLoadArchivedSessions(this, repository, state, reset = false) {}
        advanceUntilIdle()
        assertTrue(state.value.sessionPaging.archivedLoadFailed)
        assertFalse(state.value.sessionPaging.isLoadingArchived)

        coEvery { repository.getSessionPage(dir, true, 300, null) } returns
            Result.success(SessionPage(listOf(row("arch", 5, archived = 1)), null))
        launchLoadArchivedSessions(this, repository, state, reset = false) {}
        advanceUntilIdle()
        assertFalse(state.value.sessionPaging.archivedLoadFailed)
        assertEquals(setOf("arch"), state.value.sessionPaging.loadedArchivedIds)
    }
}
