package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.*
import com.yage.opencode_client.ambient.session.AmbientReplyBridge
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.SSEPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AmbientReplyBridgeTest {
    @Test fun multipleSessionsMilestonesAndDuplicateIdle() = runTest {
        val h = ReplyHarness(this)
        var summary = false
        h.pages = { id, _, _ -> replyPage(replyRow("same-id", "里程碑$id"), *if (summary) arrayOf(replyRow("summary", "汇总$id")) else emptyArray()) }
        h.accept("A"); h.accept("B")
        runCurrent()
        assertEquals(2, h.sent.size) // Same message ID in different sessions is independent.
        assertTrue(h.sent.any { it.second.contains("来源会话：任务A") && it.second.contains("里程碑A") })
        h.bridge.onEvent(statusEvent("A", "idle")); runCurrent()
        assertEquals(2, h.sent.size)
        summary = true
        h.bridge.onEvent(statusEvent("A", "idle")); runCurrent()
        assertEquals(3, h.sent.size)
        assertTrue(h.sent.last().second.contains("汇总A"))
        assertEquals(0, h.childrenRequests)
    }

    @Test fun preAcceptIdleSurvivesBusyOrFailedSnapshot() = runTest {
        val h = ReplyHarness(this)
        h.status = { _, _ -> false }
        val token = h.reserve("A")
        h.bridge.onEvent(statusEvent("A", "idle"))
        runCurrent()
        assertTrue(h.sent.isEmpty())
        h.bridge.accept(token); runCurrent()
        assertEquals(1, h.sent.size)
    }

    @Test fun staleIdleSnapshotCannotOverrideNewBusy() = runTest {
        val h = ReplyHarness(this)
        val status = CompletableDeferred<Boolean?>()
        h.status = { _, _ -> status.await() }
        h.accept("A"); runCurrent()
        h.bridge.onEvent(statusEvent("A", "busy"))
        status.complete(true); runCurrent()
        assertTrue(h.sent.isEmpty())
        h.bridge.onEvent(statusEvent("A", "idle")); runCurrent()
        assertEquals(1, h.sent.size)
    }

    @Test fun newerIdleWinsOverStaleBusySnapshot() = runTest {
        val h = ReplyHarness(this)
        val status = CompletableDeferred<Boolean?>()
        h.status = { _, _ -> status.await() }
        h.accept("A"); runCurrent()
        h.bridge.onEvent(statusEvent("A", "idle"))
        status.complete(false); runCurrent()
        assertEquals(1, h.sent.size)
    }

    @Test fun bodyArrivingAfterVisibilityRetriesStillWakesOnce() = runTest {
        val h = ReplyHarness(this)
        var ready = false
        h.pages = { _, _, _ -> replyPage(replyRow("u", "request", role = "user"), replyRow("a", if (ready) "晚到正文" else "", ready)) }
        h.accept("A"); runCurrent()
        assertEquals(3, h.reads)
        assertTrue(h.sent.isEmpty())
        ready = true
        h.bridge.onEvent(terminalEvent("A")); runCurrent()
        assertEquals(1, h.sent.size)
        val reads = h.reads
        h.bridge.onEvent(terminalEvent("A")); runCurrent()
        assertEquals(reads, h.reads)
    }

    @Test fun terminalBeforeIdleAndUnknownStatusNeverStartsRead() = runTest {
        val h = ReplyHarness(this)
        h.status = { _, _ -> null }
        h.accept("A"); runCurrent()
        h.bridge.onEvent(terminalEvent("A"))
        h.bridge.onEvent(statusEvent("A", "unknown")); runCurrent()
        assertEquals(0, h.reads)
        h.bridge.onEvent(statusEvent("A", "idle")); runCurrent()
        assertEquals(1, h.sent.size)
    }

    @Test fun sendingOldReplyDoesNotCloseNewUsersLateBodyWindow() = runTest {
        val h = ReplyHarness(this)
        var lateBody = false
        h.pages = { _, _, _ -> replyPage(
            replyRow("a1", "旧里程碑"),
            replyRow("u2", "新请求", role = "user"),
            *if (lateBody) arrayOf(replyRow("a2", "新回复")) else emptyArray(),
        ) }
        h.accept("A"); runCurrent()
        assertEquals(1, h.sent.size)
        assertEquals(3, h.reads) // Old reply sent, bounded visibility retries exhausted.
        lateBody = true
        h.bridge.onEvent(terminalEvent("A")); runCurrent()
        assertEquals(2, h.sent.size)
        assertTrue(h.sent.last().second.contains("新回复"))
        assertEquals(1, h.sent.count { it.second.contains("旧里程碑") })
        val settledReads = h.reads
        h.bridge.onEvent(terminalEvent("A")); runCurrent()
        assertEquals(settledReads, h.reads)
    }

    @Test fun dirtyEventsBeyondThreeReadsAreNotDropped() = runTest {
        val h = ReplyHarness(this)
        val gates = List(5) { CompletableDeferred<Unit>() }
        h.pages = { _, _, _ ->
            val index = h.reads - 1
            if (index < gates.size) gates[index].await()
            replyPage(*((0..index).map { replyRow("a$it", "reply$it") }.toTypedArray()))
        }
        h.accept("A"); runCurrent()
        repeat(5) { index ->
            h.bridge.onEvent(statusEvent("A", "idle"))
            gates[index].complete(Unit)
            runCurrent()
        }
        assertEquals(6, h.reads)
        assertEquals(6, h.sent.size)
        assertEquals(1, h.maximumReads)
    }

    @Test fun repeatContinueDoesNotSkipUnreadAndRollbackDoesNotRemoveSubscription() = runTest {
        val h = ReplyHarness(this)
        h.status = { _, _ -> false }
        h.accept("A"); runCurrent()
        val token = h.bridge.reserve(reservation("A", false), Json.parseToJsonElement("[${historyMessage("new-baseline", "user", 1, "next") }]") as JsonArray)!!
        h.bridge.accept(token)
        h.bridge.rollback(h.reserve("A"))
        h.bridge.onEvent(statusEvent("A", "idle")); runCurrent()
        assertEquals(1, h.sent.size)
    }

    @Test fun paginationFindsRawBoundaryAndPreservesDirectoryCursor() = runTest {
        val h = ReplyHarness(this)
        h.pages = { _, directory, before ->
            assertEquals("/work/A", directory)
            if (before == null) AmbientReplyPage(listOf(replyRow("a", "new")), "opaque+/=%")
            else { assertEquals("opaque+/=%", before); replyPage(replyRow("tool-boundary", "", role = "user")) }
        }
        val history = Json.parseToJsonElement("[${historyMessage("tool-boundary", "user", 1)}]") as JsonArray
        val token = h.bridge.reserve(reservation("A", false), history)!!
        h.bridge.accept(token); runCurrent()
        assertEquals(2, h.reads)
        assertEquals(1, h.sent.size)
    }

    @Test fun missingBoundaryOrDecodeGapsNeverReplayHistory() = runTest {
        for (gaps in listOf(false, true)) {
            val h = ReplyHarness(this)
            h.pages = { _, _, _ -> AmbientReplyPage(listOf(replyRow("old", "history")), gaps = gaps) }
            val history = Json.parseToJsonElement("[${historyMessage("boundary", "user", 1)}]") as JsonArray
            h.bridge.accept(h.bridge.reserve(reservation("A", false), history)!!)
            runCurrent()
            assertTrue(h.sent.isEmpty())
        }
    }

    @Test fun rejectedCanRetryButUnknownOrEarlyAckDoesNotReplay() = runTest {
        for (result in AmbientReplySend.entries) {
            val h = ReplyHarness(this)
            h.result = result
            h.accept("A"); runCurrent()
            repeat(3) { h.bridge.onEvent(statusEvent("A", "idle")); runCurrent() }
            assertEquals(if (result == AmbientReplySend.REJECTED) 2 else 1, h.sent.size)
        }
        val h = ReplyHarness(this)
        h.result = AmbientReplySend.REJECTED
        h.earlyAck = true
        h.accept("A"); runCurrent()
        h.bridge.onEvent(statusEvent("A", "idle")); runCurrent()
        assertEquals(1, h.sent.size)
    }

    @Test fun closeWhileReadingOrReservingNeverRevivesOldOwner() = runTest {
        val h = ReplyHarness(this)
        val gate = CompletableDeferred<Unit>()
        h.pages = { _, _, _ -> gate.await(); replyPage(replyRow("a", "late")) }
        h.accept("A"); runCurrent()
        val pending = h.reserve("B")
        h.live = false
        h.bridge.close()
        gate.complete(Unit)
        h.bridge.accept(pending)
        h.bridge.onEvent(statusEvent("A", "idle")); runCurrent()
        assertTrue(h.sent.isEmpty())
    }

    @Test fun toolReasoningSyntheticAndUnfinishedMessagesAreNotSpoken() = runTest {
        val h = ReplyHarness(this)
        h.pages = { _, _, _ -> replyPage(
            replyRow("u", "user", role = "user"),
            replyRow("tool", "tool").copy(toolCall = true),
            replyRow("unfinished", "unfinished", false),
            replyRow("a", "真实回复").copy(agent = "research"),
        ) }
        h.accept("A"); runCurrent()
        assertEquals(1, h.sent.size)
        assertTrue(h.sent.single().second.contains("来源 agent：research"))
        assertFalse(h.sent.single().second.contains("unfinished"))
    }
}

private class ReplyHarness(test: TestScope) {
    var live = true
    var reads = 0
    var activeReads = 0
    var maximumReads = 0
    val childrenRequests = 0
    val sent = mutableListOf<Pair<String, String>>()
    var result = AmbientReplySend.SUBMITTED
    var earlyAck = false
    var pages: suspend (String, String, String?) -> AmbientReplyPage = { _, _, _ -> replyPage(replyRow("a", "正文")) }
    var status: suspend (String, String) -> Boolean? = { _, _ -> true }
    val bridge: AmbientReplyBridge = AmbientReplyBridge(
        scope = test.backgroundScope,
        allows = { live },
        page = { id, directory, before ->
            reads++; activeReads++; maximumReads = maxOf(maximumReads, activeReads)
            try { pages(id, directory, before) } finally { activeReads-- }
        },
        idle = { id, directory -> status(id, directory) },
        submit = { text, event ->
            sent += event to text
            if (earlyAck) acknowledge(event)
            result
        },
        pause = {},
    )
    private fun acknowledge(event: String) = bridge.onAppended(event)
    fun reserve(id: String): String = bridge.reserve(reservation(id, true), null)!!
    fun accept(id: String) = bridge.accept(reserve(id))
}

private fun reservation(id: String, isNew: Boolean) = AmbientReplyReservation(id, "/work/$id", "u", "任务$id", isNew)
private fun replyRow(id: String, text: String, complete: Boolean = true, role: String = "assistant") =
    AmbientReplyRow(id, role, text, complete, false, null)
private fun replyPage(vararg rows: AmbientReplyRow) = AmbientReplyPage(rows.toList())
private fun statusEvent(id: String, type: String) = SSEEvent(payload = SSEPayload("session.status", buildJsonObject {
    put("sessionID", id); put("status", buildJsonObject { put("type", type) })
}))
private fun terminalEvent(id: String) = SSEEvent(payload = SSEPayload("message.updated", buildJsonObject {
    put("info", buildJsonObject { put("sessionID", id); put("finish", "stop") })
}))
