package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.audio.AmbientEventPipe
import com.yage.opencode_client.ambient.protocol.AmbientAudioCause
import com.yage.opencode_client.ambient.protocol.AmbientAudioEngine
import com.yage.opencode_client.ambient.protocol.AmbientAudioEvent
import com.yage.opencode_client.ambient.protocol.AmbientCaptureHandle
import com.yage.opencode_client.ambient.protocol.AmbientClientEvent
import com.yage.opencode_client.ambient.protocol.AmbientConfiguration
import com.yage.opencode_client.ambient.protocol.AmbientLease
import com.yage.opencode_client.ambient.protocol.AmbientLink
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import com.yage.opencode_client.ambient.protocol.AmbientServerEvent
import com.yage.opencode_client.ambient.protocol.AmbientToolEffect
import com.yage.opencode_client.ambient.protocol.AmbientToolExecutor
import com.yage.opencode_client.ambient.protocol.AmbientTransport
import com.yage.opencode_client.ambient.protocol.ManualAmbientClock
import com.yage.opencode_client.ambient.session.AmbientCaptureGate
import com.yage.opencode_client.ambient.session.AmbientController
import com.yage.opencode_client.ambient.session.AmbientRunAuthority
import com.yage.opencode_client.ambient.session.AmbientRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

class AmbientCloseBudgetTest {
    @Test
    fun pendingThenConfirmedPortal_closesSettledWithoutGlobalUnconfirmed() = runBlocking {
        withTimeout(2_000) {
            val link = EdgeLink()
            val audio = EdgeAudio()
            val run = EdgeRun(coroutineContext, link, audio)
            try {
                run.controller.start(config(), lease(), 7L)
                assertEquals(AmbientPhase.LISTENING, run.controller.snapshot.value.phase)
                link.emit(AmbientServerEvent.PortalUsage("req-1", 1.5, confirmed = false, status = "provisional"))
                until { run.controller.snapshot.value.portalUsd == 1.5 }
                link.emit(AmbientServerEvent.PortalUsage("req-1", 4.25, confirmed = true, status = "confirmed"))
                until { run.controller.snapshot.value.portalSettled && run.controller.snapshot.value.portalUsd == 4.25 }
                link.emit(AmbientServerEvent.Closed(9.0))
                until { run.controller.snapshot.value.phase == AmbientPhase.IDLE }
                val snap = run.controller.snapshot.value
                assertEquals(4.25, snap.portalUsd!!, 0.0001)
                assertTrue(snap.portalSettled)
                assertTrue(!snap.usageUnconfirmed)
                assertEquals(9.0, snap.voiceSeconds, 0.0001)
                assertTrue(snap.voiceHasSnapshot)
                assertTrue(!snap.voiceUnconfirmed)
            } finally {
                run.cancel()
            }
        }
    }

    @Test
    fun statusEmittedBeforeCollect_survivesLevelFlood() = runBlocking {
        withTimeout(1_000) {
            val pipe = AmbientEventPipe()
            pipe.emitStatus(AmbientAudioEvent.Status(4L, AmbientAudioCause.FAILED))
            repeat(64) {
                pipe.emitLevel(AmbientAudioEvent.Level(4L, 0.2, 0.0))
            }
            val status = pipe.events.first { it is AmbientAudioEvent.Status } as AmbientAudioEvent.Status
            assertEquals(4L, status.generation)
            assertEquals(AmbientAudioCause.FAILED, status.cause)
        }
    }

    @Test
    fun replayedTerminalDuringStart_endsRunWithoutReopen() = runBlocking {
        withTimeout(2_000) {
            val link = EdgeLink()
            val audio = EdgeAudio(replayTerminalOnStart = true)
            val run = EdgeRun(coroutineContext, link, audio)
            try {
                run.controller.start(config(), lease(), 7L)
                until { run.controller.snapshot.value.phase == AmbientPhase.FAILED }
                yield()
                val snap = run.controller.snapshot.value
                assertEquals(AmbientPhase.FAILED, snap.phase)
                assertEquals(AmbientRunStatus.MICROPHONE, snap.statusMessage)
                assertEquals(1, audio.captureStarts.get())
                assertEquals(listOf(5L), audio.stopped)
                assertEquals(listOf(7L), run.retired)
            } finally {
                run.cancel()
            }
        }
    }

    @Test
    fun hungCloseSend_duplicateEndReturnsOnGraceWithoutRelease() = runBlocking {
        withTimeout(3_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val link = EdgeLink(onCloseSend = {
                check(entered.complete(Unit))
                withContext(NonCancellable) { release.await() }
            })
            val audio = EdgeAudio()
            val run = EdgeRun(coroutineContext, link, audio)
            val ends = ArrayList<Job>(2)
            try {
                run.controller.start(config(), lease(), 7L)
                assertEquals(AmbientPhase.LISTENING, run.controller.snapshot.value.phase)
                assertEquals(1, audio.captureStarts.get())
                val first = launch { run.controller.end() }
                val second = launch { run.controller.end() }
                ends.add(first)
                ends.add(second)
                withTimeout(1_000) { entered.await() }
                assertTrue(!release.isCompleted)
                val startedAt = System.nanoTime()
                run.clock.advanceTo(run.clock.nowNanos() + AmbientProtocol.CLOSE_GRACE_NANOS)
                first.join()
                second.join()
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
                assertTrue(elapsedMillis < 2_000L)
                assertTrue(!release.isCompleted)
                assertEquals(1, link.sent.count { it == AmbientClientEvent.Close })
                assertEquals(listOf(7L), run.retired)
                assertEquals(listOf(5L), audio.stopped)
                val snap = run.controller.snapshot.value
                assertEquals(AmbientPhase.IDLE, snap.phase)
                assertTrue(snap.usageUnconfirmed)
            } finally {
                if (!release.isCompleted) release.complete(Unit)
                ends.forEach { it.cancel() }
                run.cancel()
            }
        }
    }

    @Test
    fun endDuringDelayedCapture_finishesLateHandleAndLeavesNextRun() = runBlocking {
        withTimeout(2_000) {
            val hold = CompletableDeferred<Unit>()
            val audio = EdgeAudio(holdCapture = hold)
            val first = EdgeRun(coroutineContext, EdgeLink(), audio)
            val jobs = ArrayList<Job>(2)
            var second: EdgeRun? = null
            try {
                jobs += launch { first.controller.start(config(), lease(), 7L) }
                until { audio.captureStarts.get() == 1 }
                jobs += launch { first.controller.end() }
                until {
                    val phase = first.controller.snapshot.value.phase
                    phase == AmbientPhase.CLOSING || phase == AmbientPhase.IDLE
                }
                hold.complete(Unit)
                jobs.forEach { it.join() }
                until { first.controller.snapshot.value.phase == AmbientPhase.IDLE }
                assertEquals(listOf(5L), audio.finished)
                assertEquals(listOf(5L), audio.stopped)
                assertEquals(listOf(7L), first.retired)
                second = EdgeRun(coroutineContext, EdgeLink(), audio)
                second.controller.start(config(), lease(), 8L)
                assertEquals(AmbientPhase.LISTENING, second.controller.snapshot.value.phase)
                assertEquals(AmbientPhase.IDLE, first.controller.snapshot.value.phase)
                assertEquals(2, audio.captureStarts.get())
                assertEquals(listOf(5L), audio.stopped)
                assertEquals(listOf(5L), audio.finished)
                assertTrue(second.retired.isEmpty())
            } finally {
                if (!hold.isCompleted) hold.complete(Unit)
                jobs.forEach { it.cancel() }
                second?.cancel()
                first.cancel()
            }
        }
    }
}

private suspend fun until(ready: () -> Boolean) {
    withTimeout(1_000) {
        while (!ready()) yield()
    }
}

private class EdgeRun(
    parentContext: CoroutineContext,
    link: EdgeLink,
    val audio: EdgeAudio,
) {
    val clock = ManualAmbientClock()
    val retired = CopyOnWriteArrayList<Long>()
    private val job: Job = SupervisorJob(parentContext[Job])
    val scope = CoroutineScope(parentContext + job)
    val controller = AmbientController(
        transport = EdgeTransport(link),
        audio = audio,
        tools = EdgeTools(),
        authority = AmbientRunAuthority(
            ownerValid = { true },
            retireDispatch = { token ->
                retired += token
                true
            },
            applyEffect = {},
        ),
        beforeCapture = AmbientCaptureGate { true },
        clock = clock,
        scope = scope,
    )

    fun cancel() {
        job.cancel()
    }
}

private class EdgeTransport(private val link: EdgeLink) : AmbientTransport {
    override suspend fun open(configuration: AmbientConfiguration): AmbientLink {
        link.emit(AmbientServerEvent.Started("portal"))
        return link
    }
}

private class EdgeLink(
    private val onCloseSend: (suspend () -> Unit)? = null,
) : AmbientLink {
    private val inbound = Channel<AmbientServerEvent>(16)
    override val incoming: Flow<AmbientServerEvent> = inbound.receiveAsFlow()
    val sent = CopyOnWriteArrayList<AmbientClientEvent>()

    override suspend fun send(event: AmbientClientEvent) {
        sent += event
        if (event != AmbientClientEvent.Close) return
        val hook = onCloseSend
        if (hook != null) {
            hook()
            return
        }
        check(inbound.trySend(AmbientServerEvent.Closed(4.0)).isSuccess)
    }

    override suspend fun close() {
        inbound.close()
    }

    fun emit(event: AmbientServerEvent) {
        check(inbound.trySend(event).isSuccess)
    }
}

private class EdgeAudio(
    private val replayTerminalOnStart: Boolean = false,
    private val holdCapture: CompletableDeferred<Unit>? = null,
) : AmbientAudioEngine {
    private val replay = MutableSharedFlow<AmbientAudioEvent>(replay = 1, extraBufferCapacity = 4)
    private val nextGeneration = AtomicLong(5L)
    override val events: Flow<AmbientAudioEvent> = replay
    val captureStarts = AtomicInteger()
    val stopped = CopyOnWriteArrayList<Long>()
    val finished = CopyOnWriteArrayList<Long>()

    override suspend fun startCapture(): AmbientCaptureHandle {
        val generation = nextGeneration.getAndIncrement()
        captureStarts.incrementAndGet()
        holdCapture?.await()
        if (replayTerminalOnStart) {
            check(replay.tryEmit(AmbientAudioEvent.Status(generation, AmbientAudioCause.FAILED)))
        }
        val frames = Channel<ByteArray>(4)
        return object : AmbientCaptureHandle {
            override val generation: Long = generation
            override suspend fun receive(): ByteArray? = frames.receiveCatching().getOrNull()
            override fun finish() {
                finished += generation
                frames.close()
            }
        }
    }

    override suspend fun stopCapture(generation: Long) {
        stopped += generation
    }

    override fun setMuted(muted: Boolean) = Unit

    override fun enqueuePlayback(pcm: ByteArray, generation: Long) = Unit

    override fun clearPlayback(generation: Long) = Unit
}

private class EdgeTools : AmbientToolExecutor {
    override suspend fun execute(
        name: String,
        arguments: String,
        lease: AmbientLease,
        dispatchToken: Long,
    ): AmbientToolEffect = AmbientToolEffect(output = """{"ok":true}""")
}

private fun config() = AmbientConfiguration(
    portalBaseUrl = "https://portal.example/backend",
    builderToken = "builder",
)

private fun lease() = AmbientLease(
    hostProfileId = "host",
    runtimeGeneration = 3L,
    directory = "/work",
    epoch = 4L,
    ownerToken = "owner",
)
