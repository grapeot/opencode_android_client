package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientAudioEngine
import com.yage.opencode_client.ambient.protocol.AmbientAudioEvent
import com.yage.opencode_client.ambient.protocol.AmbientCaptureHandle
import com.yage.opencode_client.ambient.protocol.AmbientClientEvent
import com.yage.opencode_client.ambient.protocol.AmbientConfiguration
import com.yage.opencode_client.ambient.protocol.AmbientHostedInbound
import com.yage.opencode_client.ambient.protocol.AmbientLease
import com.yage.opencode_client.ambient.protocol.AmbientLifecycle
import com.yage.opencode_client.ambient.protocol.AmbientLink
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import com.yage.opencode_client.ambient.protocol.AmbientServerEvent
import com.yage.opencode_client.ambient.protocol.AmbientToolEffect
import com.yage.opencode_client.ambient.protocol.AmbientToolExecutor
import com.yage.opencode_client.ambient.protocol.AmbientTranscriptFragment
import com.yage.opencode_client.ambient.protocol.AmbientTransport
import com.yage.opencode_client.ambient.protocol.ManualAmbientClock
import com.yage.opencode_client.ambient.session.AmbientCaptureGate
import com.yage.opencode_client.ambient.session.AmbientController
import com.yage.opencode_client.ambient.session.AmbientRunAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class AmbientControllerTest {
    @Test
    fun startedThenForegroundGate_captureWaitsAndRejectionOpensNoMic() {
        runTest {
        val waiting = Fixture(this, emitStartedOnOpen = false)
        try {
            val link = waiting.link()
            val starting = launch { waiting.controller.start(config(), lease(), 7L) }
            runCurrent()
            assertEquals(AmbientPhase.CONNECTING, waiting.controller.snapshot.value.phase)
            assertEquals(0, waiting.audio.captureStarts.get())
            link.emit(AmbientServerEvent.Started("portal"))
            runCurrent()
            assertEquals(AmbientPhase.LISTENING, waiting.controller.snapshot.value.phase)
            assertEquals(1, waiting.audio.captureStarts.get())
            starting.join()
        } finally {
            waiting.close()
        }

        val rejected = Fixture(this, allowCapture = false)
        try {
            val starting = launch { rejected.controller.start(config(), lease(), 11L) }
            runCurrent()
            starting.join()
            assertEquals(0, rejected.audio.captureStarts.get())
            assertTrue(rejected.audio.stopped.isEmpty())
            assertEquals(AmbientPhase.IDLE, rejected.controller.snapshot.value.phase)
            assertNull(rejected.controller.snapshot.value.statusMessage)
            assertEquals(listOf(11L), rejected.retired)
            assertTrue(rejected.transport.link.closes.get() > 0)
            assertTrue(rejected.transport.link.sent.none { it == AmbientClientEvent.Close })
        } finally {
            rejected.close()
        }
        }
    }

    @Test
    fun suspendedTool_doesNotBlockAudioSend() {
        runTest {
        val fixture = Fixture(this)
        fixture.tools.hold()
        try {
            val link = fixture.listen(config(), lease(), 7L)
            link.emit(functionCall("call-1"))
            runCurrent()
            assertEquals(1, fixture.tools.calls.size)
            assertEquals(AmbientPhase.LISTENING, fixture.controller.snapshot.value.phase)
            assertTrue(link.sent.none { it is AmbientClientEvent.Audio })
            fixture.audio.push(byteArrayOf(1, 2))
            runCurrent()
            assertEquals(1, link.sent.count { it is AmbientClientEvent.Audio })
            assertTrue(link.sent.none { it is AmbientClientEvent.ContinueResponse })
            assertEquals("running", fixture.controller.snapshot.value.activities.single().status)
        } finally {
            fixture.close()
        }
        }
    }

    @Test
    fun duplicateFunctionCall_survivesEmptyLifecycleAndOutputsBeforeCreate() {
        runTest {
        val fixture = Fixture(this)
        try {
            val link = fixture.listen(config(), lease(), 7L)
            link.emit(functionCall("call-1", "{}"))
            link.emit(functionCall("call-1", """{"again":true}"""))
            link.emit(emptyLifecycle())
            link.emit(functionCall("call-1", """{"after":true}"""))
            runCurrent()
            assertEquals(1, fixture.tools.calls.size)
            assertEquals("{}", fixture.tools.calls.single().arguments)
            assertEquals(1, fixture.controller.snapshot.value.activities.size)
            assertEquals("call-1", fixture.controller.snapshot.value.activities.single().id)
            assertEquals("sent", fixture.controller.snapshot.value.activities.single().status)
            val output = link.sent.indexOfFirst { it is AmbientClientEvent.FunctionOutput }
            val create = link.sent.indexOfFirst { it is AmbientClientEvent.ContinueResponse }
            assertTrue(output >= 0 && create > output)
            assertEquals(1, link.sent.count { it is AmbientClientEvent.FunctionOutput })
            assertEquals(1, link.sent.count { it is AmbientClientEvent.ContinueResponse })
            assertEquals(effect(), fixture.applied.single())
        } finally {
            fixture.close()
        }
        }
    }

    @Test
    fun rejectedFunctionOutput_doesNotContinue() {
        runTest {
        val fixture = Fixture(this)
        try {
            val link = fixture.listen(config(), lease(), 7L)
            link.rejectFunctionOutput = true
            link.emit(functionCall("call-2"))
            runCurrent()
            assertEquals(1, link.sent.count { it is AmbientClientEvent.FunctionOutput })
            assertTrue(link.sent.none { it is AmbientClientEvent.ContinueResponse })
            assertTrue(fixture.applied.isEmpty())
            val activity = fixture.controller.snapshot.value.activities.single()
            assertEquals("call-2", activity.id)
            assertEquals("failed", activity.status)
            assertEquals("", activity.result)
            assertEquals(AmbientPhase.LISTENING, fixture.controller.snapshot.value.phase)
            assertNull(fixture.controller.snapshot.value.statusMessage)
        } finally {
            fixture.close()
        }
        }
    }

    @Test
    fun captionAndToolUpdate_keepAssignedSequence() {
        runTest {
        val fixture = Fixture(this)
        fixture.tools.hold()
        try {
            val link = fixture.listen(config(), lease(), 7L)
            link.emit(user(" hello", 0, 100))
            link.emit(user(" world", 150, 200))
            runCurrent()
            assertEquals(" hello world", fixture.controller.snapshot.value.captions.single().text)
            assertEquals(1, fixture.controller.snapshot.value.captions.single().sequence)

            link.emit(user("later", 2_201, 2_300))
            runCurrent()
            assertEquals(2, fixture.controller.snapshot.value.captions[1].sequence)

            link.emit(functionCall("call-9"))
            runCurrent()
            val running = fixture.controller.snapshot.value.activities.single()
            assertEquals("running", running.status)
            assertEquals(3, running.sequence)

            fixture.tools.release()
            runCurrent()
            val sent = fixture.controller.snapshot.value.activities.single()
            assertEquals("sent", sent.status)
            assertEquals(3, sent.sequence)
            assertEquals(1, fixture.controller.snapshot.value.captions[0].sequence)
            assertEquals(2, fixture.controller.snapshot.value.captions[1].sequence)

            link.emit(assistant("yo", 3_000, 3_100))
            link.emit(assistant("!", 3_200, 3_300))
            runCurrent()
            val snap = fixture.controller.snapshot.value
            assertEquals(" hello world", snap.captions[0].text)
            assertEquals(1, snap.captions[0].sequence)
            assertEquals("later", snap.captions[1].text)
            assertEquals(2, snap.captions[1].sequence)
            assertEquals(3, snap.activities.single().sequence)
            assertEquals("yo!", snap.captions.last().text)
            assertEquals(4, snap.captions.last().sequence)
            assertEquals(1, snap.captions.count { it.text == "yo!" })
        } finally {
            fixture.close()
        }
        }
    }

    @Test
    fun duplicateEnd_mergesOnceAndTakesClosedUsage() {
        runTest {
        val fixture = Fixture(this)
        try {
            val link = fixture.listen(config(), lease(), 7L)
            link.closedSeconds = 8.5
            link.emit(AmbientServerEvent.VoiceUsage(3.0))
            runCurrent()
            assertEquals(3.0, fixture.controller.snapshot.value.voiceSeconds, 0.0001)

            val first = launch { fixture.controller.end() }
            val second = launch { fixture.controller.end() }
            first.join()
            second.join()

            assertEquals(1, link.sent.count { it == AmbientClientEvent.Close })
            assertEquals(listOf(5L), fixture.audio.stopped)
            assertEquals(listOf(7L), fixture.retired)
            val snap = fixture.controller.snapshot.value
            assertEquals(AmbientPhase.IDLE, snap.phase)
            assertEquals(8.5, snap.voiceSeconds, 0.0001)
            assertTrue(snap.voiceHasSnapshot)
            assertTrue(!snap.voiceUnconfirmed)
            assertTrue(!snap.usageUnconfirmed)
        } finally {
            fixture.close()
        }
        }
    }
}

private class Fixture(
    private val test: TestScope,
    allowCapture: Boolean = true,
    emitStartedOnOpen: Boolean = true,
) {
    val clock = ManualAmbientClock()
    private val job: Job = SupervisorJob(test.coroutineContext.job)
    val scope = CoroutineScope(test.coroutineContext + job)
    val transport = FakeTransport(emitStartedOnOpen)
    val audio = FakeAudio()
    val tools = FakeTools()
    val applied = CopyOnWriteArrayList<AmbientToolEffect>()
    val retired = CopyOnWriteArrayList<Long>()
    val controller = AmbientController(
        transport = transport,
        audio = audio,
        tools = tools,
        authority = AmbientRunAuthority(
            ownerValid = { true },
            retireDispatch = { token ->
                retired += token
                true
            },
            applyEffect = { applied += it },
        ),
        beforeCapture = AmbientCaptureGate { allowCapture },
        clock = clock,
        scope = scope,
    )

    fun link(): FakeLink = transport.link

    suspend fun listen(
        configuration: AmbientConfiguration,
        lease: AmbientLease,
        dispatchToken: Long,
    ): FakeLink {
        val starting = test.launch { controller.start(configuration, lease, dispatchToken) }
        test.runCurrent()
        starting.join()
        assertEquals(AmbientPhase.LISTENING, controller.snapshot.value.phase)
        return transport.link
    }

    suspend fun close() {
        tools.release()
        test.runCurrent()
        val phase = controller.snapshot.value.phase
        val ending = if (phase != AmbientPhase.IDLE && phase != AmbientPhase.FAILED) {
            test.launch { runCatching { controller.end() } }
        } else {
            null
        }
        test.runCurrent()
        clock.advanceTo(clock.nowNanos() + AmbientProtocol.CLOSE_GRACE_NANOS + 1_000_000_000L)
        test.runCurrent()
        ending?.join()
        job.cancel()
    }
}

private class FakeTransport(private val emitStartedOnOpen: Boolean) : AmbientTransport {
    val link = FakeLink()

    override suspend fun open(configuration: AmbientConfiguration): AmbientLink {
        if (emitStartedOnOpen) link.emit(AmbientServerEvent.Started("portal"))
        return link
    }
}

private class FakeLink : AmbientLink {
    private val inbound = Channel<AmbientServerEvent>(16)
    override val incoming: Flow<AmbientServerEvent> = inbound.receiveAsFlow()
    val sent = CopyOnWriteArrayList<AmbientClientEvent>()
    val closes = AtomicInteger()
    var rejectFunctionOutput = false
    var closedSeconds: Double? = 4.0

    override suspend fun send(event: AmbientClientEvent) {
        sent += event
        if (rejectFunctionOutput && event is AmbientClientEvent.FunctionOutput) {
            throw IllegalStateException("https://secret?ticket=abc")
        }
        if (event == AmbientClientEvent.Close) {
            check(inbound.trySend(AmbientServerEvent.Closed(closedSeconds)).isSuccess)
        }
    }

    override suspend fun close() {
        closes.incrementAndGet()
        inbound.close()
    }

    fun emit(event: AmbientServerEvent) {
        check(inbound.trySend(event).isSuccess)
    }
}

private class FakeAudio : AmbientAudioEngine {
    private val levels = MutableSharedFlow<AmbientAudioEvent>(replay = 4, extraBufferCapacity = 4)
    private val frames = Channel<ByteArray>(8)
    override val events: Flow<AmbientAudioEvent> = levels
    val captureStarts = AtomicInteger()
    val stopped = CopyOnWriteArrayList<Long>()
    private val generation = 5L

    override suspend fun startCapture(): AmbientCaptureHandle {
        captureStarts.incrementAndGet()
        return object : AmbientCaptureHandle {
            override val generation: Long = this@FakeAudio.generation
            override suspend fun receive(): ByteArray? = frames.receiveCatching().getOrNull()
            override fun finish() {
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

    fun push(frame: ByteArray) {
        check(frames.trySend(frame).isSuccess)
    }
}

private class FakeTools : AmbientToolExecutor {
    val calls = CopyOnWriteArrayList<RecordedCall>()
    private var gate: CompletableDeferred<AmbientToolEffect>? = null

    fun hold() {
        gate = CompletableDeferred()
    }

    fun release(effect: AmbientToolEffect = effect()) {
        gate?.complete(effect)
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        lease: AmbientLease,
        dispatchToken: Long,
    ): AmbientToolEffect {
        calls += RecordedCall(name, arguments, lease, dispatchToken)
        return gate?.await() ?: effect()
    }
}

private data class RecordedCall(
    val name: String,
    val arguments: String,
    val lease: AmbientLease,
    val dispatchToken: Long,
)

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

private fun functionCall(id: String, arguments: String = "{}") = AmbientServerEvent.Hosted(
    AmbientHostedInbound.FunctionCall(id, "query_jobs", arguments),
)

private fun emptyLifecycle() = AmbientServerEvent.Hosted(
    AmbientHostedInbound.Lifecycle(
        AmbientLifecycle(
            type = "response.completed",
            responseId = "resp-1",
            model = "gpt-6-luna",
            outputCount = 0,
            usage = null,
        ),
    ),
)

private fun effect() = AmbientToolEffect(output = """{"ok":true}""")

private fun user(text: String, start: Int, end: Int) = AmbientServerEvent.InputTranscript(
    AmbientTranscriptFragment(text, startMs = start, endMs = end),
)

private fun assistant(text: String, start: Int, end: Int) = AmbientServerEvent.OutputTranscript(
    AmbientTranscriptFragment(text, startMs = start, endMs = end),
)
