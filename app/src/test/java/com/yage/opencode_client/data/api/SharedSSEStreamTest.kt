package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.SSEPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSSEStreamTest {
    @Test fun twoConsumersOneSourceLateJoinNoDeltaReplayAndIndependentRelease() = runTest {
        val events = Channel<Result<SSEEvent>>(16)
        var opens = 0
        var closes = 0
        var uiConnections = 0
        var voiceConnections = 0
        var voiceDisconnections = 0
        val ui = mutableListOf<SSEEvent>()
        val voice = mutableListOf<SSEEvent>()
        val stream = SharedSSEStream(backgroundScope) { connected, disconnected -> flow {
            opens++
            connected()
            try { for (event in events) emit(event) } finally { closes++; disconnected() }
        } }
        val chat = backgroundScope.launch { stream.connect({ uiConnections++ }, {}).collect { ui += it.getOrThrow() } }
        runCurrent()
        val event = SSEEvent(payload = SSEPayload("message.part.delta"))
        events.send(Result.success(event)); runCurrent()
        val ambient = backgroundScope.launch { stream.connect({ voiceConnections++ }, { voiceDisconnections++ }).collect { voice += it.getOrThrow() } }
        runCurrent()
        assertEquals(1, opens)
        assertEquals(1, uiConnections)
        assertEquals(1, voiceConnections)
        assertTrue(voice.isEmpty())
        chat.cancel(); runCurrent()
        events.send(Result.success(event)); events.send(Result.success(event)); runCurrent()
        assertEquals(2, voice.size) // Equal deltas are events, not conflated state.
        assertEquals(0, voiceDisconnections)
        assertEquals(0, closes)
        ambient.cancel(); runCurrent()
        assertEquals(1, closes)
        stream.close()
    }

    @Test fun retiredHostRejectsLateOpenAndDoesNotRestart() = runTest {
        var openCallback: (() -> Unit)? = null
        var received = 0
        var connections = 0
        val events = Channel<Result<SSEEvent>>()
        val stream = SharedSSEStream(backgroundScope) { connected, _ -> flow {
            openCallback = connected
            for (event in events) emit(event)
        } }
        backgroundScope.launch { stream.connect({ connections++ }, {}).collect { received++ } }
        runCurrent()
        stream.close(); openCallback?.invoke(); runCurrent()
        backgroundScope.launch { stream.connect({ connections++ }, {}).collect { received++ } }
        runCurrent()
        assertEquals(0, connections)
        assertEquals(0, received)
    }

    @Test fun consecutiveConfigurationsPreserveAllRetirementBarriers() = runTest {
        for (subscribeIntermediate in listOf(false, true)) {
            val cleanup = CompletableDeferred<Unit>()
            var firstOpened = false
            var lastOpens = 0
            val first = SharedSSEStream(backgroundScope) { connected, _ -> flow {
                firstOpened = true
                connected()
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            } }
            backgroundScope.launch { first.connect({}, {}).collect {} }
            runCurrent()
            assertTrue(firstOpened)
            val middle = SharedSSEStream(backgroundScope, first.close()) { _, _ -> flow { error("must wait for first") } }
            if (subscribeIntermediate) {
                backgroundScope.launch { middle.connect({}, {}).collect {} }
                runCurrent()
            }
            val last = SharedSSEStream(backgroundScope, middle.close()) { connected, _ -> flow {
                lastOpens++; connected(); awaitCancellation()
            } }
            backgroundScope.launch { last.connect({}, {}).collect {} }
            runCurrent()
            assertEquals(0, lastOpens)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(1, lastOpens)
            last.close()
        }
    }

    @Test fun rapidDetachResubscribeCannotTruncateRetirementChain() = runTest {
        for (replaceConfiguration in listOf(false, true)) {
            val cleanup = CompletableDeferred<Unit>()
            var opens = 0
            val hub = SharedSSEStream(backgroundScope) { connected, _ -> flow {
                opens++; connected()
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            } }
            val first = backgroundScope.launch { hub.connect({}, {}).collect {} }
            runCurrent()
            first.cancel(); runCurrent()
            val second = backgroundScope.launch { hub.connect({}, {}).collect {} }
            runCurrent()
            second.cancel(); runCurrent()
            var successorOpens = 0
            val next = if (replaceConfiguration) SharedSSEStream(backgroundScope, hub.close()) { connected, _ -> flow {
                successorOpens++; connected(); awaitCancellation()
            } } else hub
            backgroundScope.launch { next.connect({}, {}).collect {} }
            runCurrent()
            assertEquals(1, opens)
            assertEquals(0, successorOpens)
            cleanup.complete(Unit); runCurrent()
            if (replaceConfiguration) assertEquals(1, successorOpens) else assertEquals(2, opens)
            next.close()
        }
    }
}
