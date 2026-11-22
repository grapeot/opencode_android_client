package com.yage.opencode_client

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.session.executeSessionArchive
import com.yage.opencode_client.ui.session.planSessionArchive
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class SessionArchiveBatchTest {
    private val parent = Session("parent", directory = "/fixture")
    private val child = Session("child", directory = "/fixture", parentId = "parent")
    private val grandchild = Session("grandchild", directory = "/fixture", parentId = "child")
    private val other = Session("other", directory = "/fixture")

    @Test fun combinesOverlappingSelectionsAndTraversesArchivedIntermediaries() {
        val plan = planSessionArchive(listOf(parent, child.archived(), grandchild, other), setOf("parent", "grandchild", "other"))
        assertEquals(listOf("grandchild", "parent", "other"), plan.ids)
        assertEquals(0, plan.additionalCount)
        assertEquals(1, planSessionArchive(listOf(parent, child.archived(), grandchild), setOf("parent")).additionalCount)
    }

    @Test fun discoversUnapprovedGrandchildAndContinuesUnrelatedBranch() = runTest {
        val server = Fixture(parent, child.archived(), grandchild, other)
        val result = server.run(listOf(parent, other), setOf("parent", "other"))
        assertEquals(listOf("other"), server.writes)
        assertTrue(grandchild in server.published)
        assertTrue(result.foundChildren)
        assertEquals(1, result.remaining)
        assertEquals(0, server.run(server.rows.values.toList(), setOf("parent")).remaining)
        assertEquals(listOf("other", "grandchild", "parent"), server.writes)
    }

    @Test fun ignoredArchiveResponseBlocksAncestorsButKeepsSuccessfulBranches() = runTest {
        val server = Fixture(parent, child, other)
        server.ignoredWrite = "child"
        val result = server.run(server.rows.values.toList(), setOf("parent", "child", "other"))
        assertEquals(listOf("child", "other"), server.writes)
        assertEquals(setOf("other"), result.archived)
        assertEquals(2, result.remaining)
    }

    @Test fun missingChildrenRouteDoesNotPretendSessionDisappeared() = runTest {
        val server = Fixture(parent)
        server.missingRoute = "parent"
        assertEquals(setOf("parent"), server.run(listOf(parent), setOf("parent")).archived)
        assertTrue(server.removed.isEmpty())
        assertEquals(listOf("parent"), server.writes)
        server.rows.clear()
        assertEquals(setOf("parent"), server.run(listOf(parent), setOf("parent")).missing)
        assertEquals(listOf("parent"), server.removed)
    }

    @Test fun malformedChildrenResponseNeverArchivesParent() = runTest {
        val server = Fixture(parent)
        server.overrideChildren = { id -> if (id == "parent") listOf(child.copy(parentId = null)) else null }
        assertEquals(1, server.run(listOf(parent), setOf("parent")).remaining)
        assertTrue(server.writes.isEmpty())
    }

    @Test fun activeBranchIsDiscoveredInOnePassAndLateBranchRequiresReview() = runTest {
        val server = Fixture(parent, child, grandchild)
        assertTrue(server.run(listOf(parent), setOf("parent")).foundChildren)
        assertTrue(grandchild in server.published)
        server.rows["child"] = child.archived()
        server.rows.remove("grandchild")
        var directReads = 0
        server.overrideChildren = { id ->
            if (id == "parent" && ++directReads == 2) listOf(child.archived(), other.copy(parentId = "parent")) else null
        }
        assertTrue(server.run(listOf(parent, child.archived()), setOf("parent")).foundChildren)
        assertTrue(server.writes.isEmpty())
    }

    @Test fun confirmedWriteStillPublishesWhenConnectionDropsOnSameHost() = runTest {
        val server = Fixture(parent, other)
        server.afterWrite = { server.inScope = false }
        val result = server.run(listOf(parent, other), setOf("parent", "other"))
        assertEquals(listOf("parent"), server.writes)
        assertEquals(setOf("parent"), result.archived)
        assertEquals(1, result.remaining)
        assertTrue(parent.archived() in server.published)
    }

    @Test fun scopeChangeDuringReadStopsWritesAndPublishing() = runTest {
        val server = Fixture(parent, child)
        server.afterRead = { server.inScope = false }
        assertEquals(1, server.run(listOf(parent), setOf("parent")).remaining)
        assertTrue(server.writes.isEmpty())
        assertTrue(server.published.isEmpty())
    }

    private class Fixture(vararg initial: Session) {
        val rows = initial.associateBy { it.id }.toMutableMap()
        val writes = mutableListOf<String>()
        val published = mutableListOf<Session>()
        val removed = mutableListOf<String>()
        var inScope = true
        var afterRead: () -> Unit = {}
        var afterWrite: () -> Unit = {}
        var overrideChildren: (String) -> List<Session>? = { null }
        var ignoredWrite: String? = null
        var missingRoute: String? = null

        suspend fun run(visible: List<Session>, selected: Set<String>) = executeSessionArchive(
            plan = planSessionArchive(visible, selected),
            timestamp = 10L,
            canContinue = { inScope },
            ownsResult = { true },
            readChildren = { id ->
                afterRead()
                if (id == missingRoute) Result.failure(notFound())
                else Result.success(overrideChildren(id) ?: rows.values.filter { it.parentId == id })
            },
            readSession = { id -> rows[id]?.let { Result.success(it) } ?: Result.failure(notFound()) },
            archive = { id, _ ->
                writes += id
                val current = rows.getValue(id)
                val updated = if (id == ignoredWrite) current else current.archived()
                rows[id] = updated
                afterWrite()
                Result.success(updated)
            },
            publish = { published += it },
            remove = { removed += it },
        )

        private fun notFound() = HttpException(Response.error<Unit>(404, "not found".toResponseBody()))
    }
}

private fun Session.archived() = copy(time = Session.TimeInfo(archived = 10L))
