package com.yage.opencode_client.ambient.session

import com.yage.opencode_client.ambient.protocol.AmbientAudioCause
import com.yage.opencode_client.ambient.protocol.AmbientAudioEngine
import com.yage.opencode_client.ambient.protocol.AmbientAudioEvent
import com.yage.opencode_client.ambient.protocol.AmbientBackendUsage
import com.yage.opencode_client.ambient.protocol.AmbientCallBook
import com.yage.opencode_client.ambient.protocol.AmbientCaptureHandle
import com.yage.opencode_client.ambient.protocol.AmbientClaim
import com.yage.opencode_client.ambient.protocol.AmbientClientEvent
import com.yage.opencode_client.ambient.protocol.AmbientCloseBudget
import com.yage.opencode_client.ambient.protocol.AmbientConfiguration
import com.yage.opencode_client.ambient.protocol.AmbientErrorDisposition
import com.yage.opencode_client.ambient.protocol.AmbientEventDecoder
import com.yage.opencode_client.ambient.protocol.AmbientFlightBook
import com.yage.opencode_client.ambient.protocol.AmbientFunctionOutput
import com.yage.opencode_client.ambient.protocol.AmbientHostedInbound
import com.yage.opencode_client.ambient.protocol.AmbientLease
import com.yage.opencode_client.ambient.protocol.AmbientLifecycle
import com.yage.opencode_client.ambient.protocol.AmbientLink
import com.yage.opencode_client.ambient.protocol.AmbientMonotonicClock
import com.yage.opencode_client.ambient.protocol.AmbientPcm
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientPortalLedger
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import com.yage.opencode_client.ambient.protocol.AmbientServerEvent
import com.yage.opencode_client.ambient.protocol.AmbientSnapshot
import com.yage.opencode_client.ambient.protocol.AmbientTimeline
import com.yage.opencode_client.ambient.protocol.AmbientToolEffect
import com.yage.opencode_client.ambient.protocol.AmbientToolExecutor
import com.yage.opencode_client.ambient.protocol.AmbientTranscriptFragment
import com.yage.opencode_client.ambient.protocol.AmbientTransport
import com.yage.opencode_client.ambient.protocol.AmbientUsageLedger
import com.yage.opencode_client.ambient.protocol.AmbientVoiceNote
import com.yage.opencode_client.ambient.protocol.display
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Fixed snapshot status strings. Throwable text, URLs, and tickets are never copied in. */
object AmbientRunStatus {
    const val REJECTED = "rejected"
    const val DISCONNECTED = "disconnected"
    const val MICROPHONE = "microphone"
    const val FOCUS = "focus"
    const val HEADSET = "headset"
    const val NOT_READY = "not_ready"
    const val FAILED = "failed"
}

/**
 * Run authority supplied by the later coordinator.
 * [retireDispatch] must drop only the token from this run.
 * [applyEffect] receives the full tool effect and owns binding updates.
 */
class AmbientRunAuthority(
    val ownerValid: (AmbientLease) -> Boolean,
    val retireDispatch: (Long) -> Boolean,
    val applyEffect: (AmbientToolEffect) -> Unit,
)

/** Foreground and microphone-service check. Called only after session.started. */
fun interface AmbientCaptureGate {
    suspend fun allow(): Boolean
}

/**
 * One Ambient voice run. The constructor does not open a socket or the microphone.
 * [start] clears this run's captions, tool rows, usage, and elapsed time.
 * It does not abort OpenCode and does not clear the host or directory binding.
 */
class AmbientController(
    private val transport: AmbientTransport,
    private val audio: AmbientAudioEngine,
    private val tools: AmbientToolExecutor,
    private val authority: AmbientRunAuthority,
    private val beforeCapture: AmbientCaptureGate,
    private val clock: AmbientMonotonicClock = AndroidAmbientClock(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val stateLock = Any()
    private val startMutex = Mutex()
    private val shutdownRef = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val snapshotFlow = MutableStateFlow(idleSnapshot())

    val snapshot: StateFlow<AmbientSnapshot> = snapshotFlow.asStateFlow()

    private var runToken = 0L
    private var phase = AmbientPhase.IDLE
    private var muted = false
    private var inputRms = 0.0
    private var outputRms = 0.0
    private var elapsedSeconds = 0L
    private var usageUnconfirmed = false
    private var statusMessage: String? = null
    private var closing = false
    private var closeSeen = false
    private var captureGeneration = 0L
    private var dispatchToken = 0L
    private var lease: AmbientLease? = null
    private var link: AmbientLink? = null
    private var capture: AmbientCaptureHandle? = null
    private var timeline = AmbientTimeline()
    private var calls = AmbientCallBook(0L)
    private var flight = AmbientFlightBook()
    private var ledger = AmbientUsageLedger()
    private var portal = AmbientPortalLedger()
    private var voice = AmbientVoiceNote()
    private var closeGate = CompletableDeferred<Unit>()
    private var startSignal: CompletableDeferred<Boolean>? = null

    private var readerJob: Job? = null
    private var pumpJob: Job? = null
    private var clockJob: Job? = null
    private var audioJob: Job? = null

    suspend fun start(
        configuration: AmbientConfiguration,
        lease: AmbientLease,
        dispatchToken: Long,
    ) {
        startMutex.withLock {
            shutdownRef.get()?.await()
            if (isLive()) endInternal(failed = false, status = null, sendClose = true)
            val started = beginRun(lease, dispatchToken)
            val token = started.first
            val signal = started.second
            var opened: AmbientLink? = null
            var handle: AmbientCaptureHandle? = null
            try {
                val sessionLink = when (val attempt = openSession(configuration)) {
                    OpenAttempt.Failed -> {
                        if (same(token)) {
                            endInternal(failed = true, status = AmbientRunStatus.REJECTED, sendClose = false)
                        }
                        return
                    }
                    is OpenAttempt.Ready -> attempt.link
                }
                opened = sessionLink
                if (!same(token)) {
                    releaseUnused(sessionLink, token)
                    return
                }
                synchronized(stateLock) {
                    if (runToken == token && !closing) link = sessionLink
                }
                if (!same(token)) {
                    releaseUnused(sessionLink, token)
                    return
                }
                readerJob = scope.launch { readIncoming(sessionLink, token, signal) }
                if (!awaitStarted(token, signal)) {
                    if (same(token)) endInternal(failed = true, status = AmbientRunStatus.NOT_READY, sendClose = false)
                    return
                }
                val allowed = try {
                    beforeCapture.allow()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!allowed || !same(token)) {
                    if (same(token)) endInternal(failed = false, status = null, sendClose = false)
                    return
                }
                handle = try {
                    audio.startCapture()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (same(token)) endInternal(failed = true, status = AmbientRunStatus.MICROPHONE, sendClose = false)
                    return
                }
                currentCoroutineContext().ensureActive()
                if (!same(token) || !arm(token, handle)) {
                    handle.finish()
                    stopGeneration(handle.generation)
                    return
                }
            } catch (cancelled: CancellationException) {
                handle?.finish()
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (synchronized(stateLock) { runToken == token && !closing }) {
                        endInternal(failed = false, status = null, sendClose = false)
                    }
                    val late = handle
                    if (late != null && synchronized(stateLock) { captureGeneration != late.generation }) {
                        stopGeneration(late.generation)
                    }
                    val owned = opened
                    if (owned != null && synchronized(stateLock) { link !== owned }) {
                        closeLink(owned)
                    }
                }
                throw cancelled
            }
        }
    }

    fun mute(muted: Boolean) {
        val token = synchronized(stateLock) {
            this.muted = muted
            audio.setMuted(muted)
            publishLocked()
            runToken
        }
        scope.launch {
            sendIfCurrent(token, if (muted) AmbientClientEvent.Mute else AmbientClientEvent.Unmute)
        }
    }

    fun interrupt() {
        val generation = synchronized(stateLock) {
            outputRms = 0.0
            if (phase == AmbientPhase.SPEAKING) phase = AmbientPhase.LISTENING
            publishLocked()
            captureGeneration
        }
        if (generation != 0L) audio.clearPlayback(generation)
    }

    suspend fun end() {
        endInternal(failed = false, status = null, sendClose = true)
    }

    private suspend fun openSession(configuration: AmbientConfiguration): OpenAttempt {
        try {
            return OpenAttempt.Ready(transport.open(configuration.copy(history = emptyList())))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return OpenAttempt.Failed
        }
    }

    private suspend fun awaitStarted(token: Long, signal: CompletableDeferred<Boolean>): Boolean {
        val now = clock.nowNanos()
        val span = AmbientProtocol.CREATE_TIMEOUT_MILLIS * 1_000_000L
        val deadline = if (now > Long.MAX_VALUE - span) now else now + span
        val waiter = scope.launch {
            clock.sleepUntil(deadline)
            signal.complete(false)
        }
        val ready = try {
            signal.await()
        } finally {
            waiter.cancel()
        }
        return ready && same(token)
    }

    private fun arm(token: Long, handle: AmbientCaptureHandle): Boolean {
        val origin = clock.nowNanos()
        val sendMute = synchronized(stateLock) {
            if (runToken != token || closing) return@synchronized null
            capture = handle
            captureGeneration = handle.generation
            phase = AmbientPhase.LISTENING
            audio.setMuted(muted)
            publishLocked()
            muted
        } ?: return false
        audioJob = scope.launch { collectAudio(token) }
        pumpJob = scope.launch { pump(token, handle) }
        clockJob = scope.launch { tick(token, origin) }
        if (sendMute) scope.launch { sendIfCurrent(token, AmbientClientEvent.Mute) }
        return true
    }

    private suspend fun readIncoming(
        opened: AmbientLink,
        token: Long,
        signal: CompletableDeferred<Boolean>,
    ) {
        try {
            opened.incoming.collect { event -> onEvent(event, token, signal) }
            if (synchronized(stateLock) { runToken == token && !closing }) {
                failAsync(token, AmbientRunStatus.DISCONNECTED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failAsync(token, AmbientRunStatus.DISCONNECTED)
        }
    }

    private fun onEvent(
        event: AmbientServerEvent,
        token: Long,
        signal: CompletableDeferred<Boolean>,
    ) {
        when (event) {
            is AmbientServerEvent.Started -> {
                if (!same(token)) signal.complete(false) else signal.complete(true)
            }
            is AmbientServerEvent.OutputAudio -> onOutputAudio(token, event.pcm)
            is AmbientServerEvent.InputTranscript -> appendCaption(token, "user", event.fragment)
            is AmbientServerEvent.OutputTranscript -> appendCaption(token, "assistant", event.fragment)
            is AmbientServerEvent.Hosted -> when (val inbound = event.inbound) {
                is AmbientHostedInbound.FunctionCall -> onFunction(token, inbound)
                is AmbientHostedInbound.Lifecycle -> onLifecycle(token, inbound.lifecycle)
                is AmbientHostedInbound.Ignored -> Unit
            }
            is AmbientServerEvent.VoiceUsage -> synchronized(stateLock) {
                if (runToken != token) return@synchronized
                voice.replace(event.seconds)
                publishLocked()
            }
            is AmbientServerEvent.Closed -> onClosed(token, event.seconds)
            is AmbientServerEvent.PortalUsage -> synchronized(stateLock) {
                if (runToken != token) return@synchronized
                portal.replace(event.requestId, event.estimatedUsd, event.confirmed, event.status)
                publishLocked()
            }
            AmbientServerEvent.UsageUnconfirmed -> synchronized(stateLock) {
                if (runToken != token) return@synchronized
                usageUnconfirmed = true
                publishLocked()
            }
            AmbientServerEvent.InvalidAudio -> Unit
            is AmbientServerEvent.Failed -> onFailed(token, event.code, signal)
            is AmbientServerEvent.Ignored -> Unit
        }
    }

    private fun onOutputAudio(token: Long, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val generation = synchronized(stateLock) {
            val live = runToken == token && !closing &&
                (phase == AmbientPhase.LISTENING || phase == AmbientPhase.SPEAKING) &&
                captureGeneration != 0L
            if (!live) return@synchronized 0L
            if (phase == AmbientPhase.LISTENING) phase = AmbientPhase.SPEAKING
            publishLocked()
            captureGeneration
        }
        if (generation != 0L) audio.enqueuePlayback(pcm, generation)
    }

    private fun appendCaption(token: Long, role: String, fragment: AmbientTranscriptFragment) {
        synchronized(stateLock) {
            if (runToken != token || closing) return
            timeline.appendCaption(role, fragment)
            publishLocked()
        }
    }

    private fun onFunction(token: Long, call: AmbientHostedInbound.FunctionCall) {
        val accepted = synchronized(stateLock) {
            if (runToken != token || closing) return
            calls.collect(call.callId, call.name)
            if (calls.claim(call.callId, token) != AmbientClaim.ACCEPTED) return
            flight.begin()
            timeline.upsertActivity(call.callId, call.name, call.arguments, "running", "")
            publishLocked()
            true
        }
        if (accepted) scope.launch { runTool(token, call) }
    }

    private suspend fun runTool(token: Long, call: AmbientHostedInbound.FunctionCall) {
        val context = synchronized(stateLock) {
            val current = lease
            if (runToken != token || closing || current == null || !authority.ownerValid(current)) {
                null
            } else {
                current to dispatchToken
            }
        }
        if (context == null) {
            noteTool(token, call, "failed", "")
            finishFlight(token, false)
            return
        }
        val effect = try {
            tools.execute(call.name, call.arguments, context.first, context.second)
        } catch (cancelled: CancellationException) {
            finishFlight(token, false)
            throw cancelled
        } catch (_: Exception) {
            noteTool(token, call, "failed", "")
            finishFlight(token, false)
            return
        }
        val opened = synchronized(stateLock) {
            val current = lease
            if (runToken != token || closing || current == null || !authority.ownerValid(current)) {
                null
            } else {
                link
            }
        }
        if (opened == null) {
            finishFlight(token, false)
            return
        }
        val output = AmbientFunctionOutput.ensureSendable(effect.output)
        val sent = try {
            opened.send(AmbientClientEvent.FunctionOutput(call.callId, output))
            true
        } catch (cancelled: CancellationException) {
            finishFlight(token, false)
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!sent) {
            noteTool(token, call, "failed", "")
            finishFlight(token, false)
            return
        }
        val apply = synchronized(stateLock) {
            val current = lease
            runToken == token && !closing && current != null && authority.ownerValid(current)
        }
        if (!apply) {
            finishFlight(token, false)
            return
        }
        try {
            authority.applyEffect(effect)
        } catch (cancelled: CancellationException) {
            finishFlight(token, false)
            throw cancelled
        } catch (_: Exception) {
            finishFlight(token, false)
            failAsync(token, AmbientRunStatus.FAILED)
            return
        }
        val recorded = synchronized(stateLock) {
            if (runToken != token || closing) {
                false
            } else {
                timeline.upsertActivity(call.callId, call.name, call.arguments, "sent", output)
                publishLocked()
                true
            }
        }
        if (!recorded || !finishFlight(token, true)) return
        val current = synchronized(stateLock) {
            if (runToken == token && !closing) link else null
        } ?: return
        try {
            current.send(AmbientClientEvent.ContinueResponse)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failAsync(token, AmbientRunStatus.DISCONNECTED)
        }
    }

    private fun noteTool(
        token: Long,
        call: AmbientHostedInbound.FunctionCall,
        status: String,
        result: String,
    ) {
        synchronized(stateLock) {
            if (runToken != token || closing) return
            timeline.upsertActivity(call.callId, call.name, call.arguments, status, result)
            publishLocked()
        }
    }

    private fun finishFlight(token: Long, sent: Boolean): Boolean = synchronized(stateLock) {
        if (runToken != token) return false
        flight.finish(sent, accepting = !closing && calls.accepting)
    }

    private fun onLifecycle(token: Long, life: AmbientLifecycle) {
        synchronized(stateLock) {
            if (runToken != token) return
            calls.noteLifecycle(life.outputCount)
            ledger.record(life)
            publishLocked()
        }
    }

    private fun onClosed(token: Long, seconds: Double?) {
        val follow = synchronized(stateLock) {
            if (runToken != token) return
            voice.close(seconds)
            if (voice.unconfirmed) usageUnconfirmed = true
            closeSeen = true
            publishLocked()
            closeGate.complete(Unit)
            !closing
        }
        if (follow) launchEnd(token, failed = false, status = null, sendClose = false)
    }

    private fun onFailed(token: Long, code: String, signal: CompletableDeferred<Boolean>) {
        val disposition = synchronized(stateLock) {
            if (runToken != token) return
            AmbientEventDecoder.errorDisposition(code, closing, closeSeen)
        }
        when (disposition) {
            AmbientErrorDisposition.IGNORE -> Unit
            AmbientErrorDisposition.SOFT -> synchronized(stateLock) {
                if (runToken != token || closing) return@synchronized
                statusMessage = AmbientRunStatus.REJECTED
                publishLocked()
            }
            AmbientErrorDisposition.FAIL -> {
                signal.complete(false)
                failAsync(token, AmbientRunStatus.REJECTED)
            }
        }
    }

    private suspend fun collectAudio(token: Long) {
        try {
            audio.events.collect { event ->
                if (event.generation != generationOf(token)) return@collect
                when (event) {
                    is AmbientAudioEvent.Level -> synchronized(stateLock) {
                        if (runToken != token || closing || captureGeneration != event.generation) return@synchronized
                        inputRms = event.inputRms
                        outputRms = event.outputRms
                        publishLocked()
                    }
                    is AmbientAudioEvent.Status -> onAudioStatus(token, event)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failAsync(token, AmbientRunStatus.MICROPHONE)
        }
    }

    private fun onAudioStatus(token: Long, event: AmbientAudioEvent.Status) {
        val failure = synchronized(stateLock) {
            if (runToken != token || closing || captureGeneration != event.generation) return
            when (event.cause) {
                AmbientAudioCause.PLAYBACK_IDLE -> {
                    outputRms = 0.0
                    if (phase == AmbientPhase.SPEAKING) phase = AmbientPhase.LISTENING
                    publishLocked()
                    null
                }
                AmbientAudioCause.FOCUS_LOSS -> AmbientRunStatus.FOCUS
                AmbientAudioCause.HEADSET_REMOVED -> AmbientRunStatus.HEADSET
                AmbientAudioCause.FAILED -> AmbientRunStatus.MICROPHONE
            }
        }
        if (failure != null) failAsync(token, failure)
    }

    private suspend fun pump(token: Long, handle: AmbientCaptureHandle) {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = handle.receive() ?: return
                var stop = false
                var uplink: ByteArray? = null
                var opened: AmbientLink? = null
                synchronized(stateLock) {
                    if (runToken != token || closing) {
                        stop = true
                    } else {
                        uplink = AmbientPcm.uplinkFrame(frame, muted)
                        opened = link
                    }
                }
                if (stop) return
                val payload = uplink ?: continue
                val target = opened ?: return
                target.send(AmbientClientEvent.Audio(payload))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failAsync(token, AmbientRunStatus.DISCONNECTED)
        }
    }

    private suspend fun tick(token: Long, origin: Long) {
        while (currentCoroutineContext().isActive) {
            val seconds = ((clock.nowNanos() - origin) / 1_000_000_000L).coerceAtLeast(0L)
            val keep = synchronized(stateLock) {
                if (runToken != token || closing) {
                    false
                } else {
                    elapsedSeconds = seconds
                    publishLocked()
                    true
                }
            }
            if (!keep) return
            clock.sleepUntil(origin + (seconds + 1L) * 1_000_000_000L)
        }
    }

    private suspend fun sendIfCurrent(token: Long, event: AmbientClientEvent) {
        val opened = synchronized(stateLock) {
            if (runToken != token || closing) null else link
        } ?: return
        try {
            opened.send(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failAsync(token, AmbientRunStatus.DISCONNECTED)
        }
    }

    private fun failAsync(token: Long, status: String) {
        launchEnd(token, failed = true, status = status, sendClose = false)
    }

    private fun launchEnd(token: Long, failed: Boolean, status: String?, sendClose: Boolean) {
        val deadline = freshDeadline()
        scope.launch {
            endInternal(failed, status, sendClose, deadline, onlyToken = token)
        }
    }

    private suspend fun endInternal(
        failed: Boolean,
        status: String?,
        sendClose: Boolean,
        deadline: Long = freshDeadline(),
        onlyToken: Long? = null,
    ) {
        while (true) {
            val existing = shutdownRef.get()
            if (existing != null) {
                existing.await()
                return
            }
            if (onlyToken != null && synchronized(stateLock) { runToken != onlyToken }) return
            if (!isLive()) return
            val ownedDeadline = deadline
            val deferred = CompletableDeferred<Unit>()
            if (!shutdownRef.compareAndSet(null, deferred)) continue
            val claim = synchronized(stateLock) {
                if (onlyToken != null && runToken != onlyToken) {
                    null
                } else {
                    takeClaim(ownedDeadline, failed, status, sendClose)
                }
            }
            if (claim == null) {
                deferred.complete(Unit)
                shutdownRef.compareAndSet(deferred, null)
                return
            }
            if (claim.live && claim.dispatchToken != 0L) {
                authority.retireDispatch(claim.dispatchToken)
            }
            try {
                if (claim.live) withContext(Dispatchers.Default) { perform(claim) }
                deferred.complete(Unit)
            } catch (cancelled: CancellationException) {
                abandon(claim)
                deferred.complete(Unit)
                throw cancelled
            } catch (_: Exception) {
                if (claim.live) finalize(claim)
                deferred.complete(Unit)
            } finally {
                shutdownRef.compareAndSet(deferred, null)
            }
            return
        }
    }

    private suspend fun perform(claim: Claim) {
        currentCoroutineContext().ensureActive()
        if (claim.generation != 0L) audio.clearPlayback(claim.generation)
        synchronized(stateLock) { capture?.finish() }
        pumpJob?.cancel()
        clockJob?.cancel()
        audioJob?.cancel()
        val budget = AmbientCloseBudget(clock)
        if (claim.generation != 0L) {
            budget.run(remaining(claim.deadline)) { audio.stopCapture(claim.generation) }
        }
        currentCoroutineContext().ensureActive()
        if (claim.sendClose && claim.link != null) {
            budget.run(remaining(claim.deadline)) { claim.link.send(AmbientClientEvent.Close) }
        }
        currentCoroutineContext().ensureActive()
        if (claim.waitForClose && !claim.closeSeenAtStart) {
            budget.run(remaining(claim.deadline)) { claim.closeGate.await() }
        }
        currentCoroutineContext().ensureActive()
        readerJob?.cancel()
        val linked = claim.link
        val closed = if (linked == null) {
            true
        } else {
            budget.run(remaining(claim.deadline)) { linked.close() }
        }
        if (!closed && linked != null) scheduleClose(linked)
        finalize(claim)
    }

    private fun abandon(claim: Claim) {
        pumpJob?.cancel()
        clockJob?.cancel()
        audioJob?.cancel()
        readerJob?.cancel()
        if (!claim.live) return
        if (claim.generation != 0L) {
            val generation = claim.generation
            scope.launch {
                try {
                    audio.stopCapture(generation)
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
        }
        claim.link?.let { scheduleClose(it) }
        finalize(claim)
    }

    private fun scheduleClose(owned: AmbientLink) {
        scope.launch {
            try {
                owned.close()
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
        }
    }

    private fun finalize(claim: Claim) {
        synchronized(stateLock) {
            if (runToken != claim.token) return
            if (!closeSeen || voice.unconfirmed) usageUnconfirmed = true
            link = null
            capture = null
            captureGeneration = 0L
            closing = false
            phase = if (claim.failed) AmbientPhase.FAILED else AmbientPhase.IDLE
            statusMessage = if (claim.failed) fixedStatus(claim.status) else null
            runToken += 1
            calls.bind(runToken)
            flight = AmbientFlightBook()
            publishLocked()
        }
    }

    private suspend fun releaseUnused(opened: AmbientLink, token: Long) {
        val owned = synchronized(stateLock) { closing && link === opened }
        if (!owned) closeLink(opened)
        if (synchronized(stateLock) { runToken == token && !closing }) {
            endInternal(failed = false, status = null, sendClose = false)
        }
    }

    private suspend fun closeLink(owned: AmbientLink) {
        val done = AmbientCloseBudget(clock).run(remaining(freshDeadline())) { owned.close() }
        if (!done) scheduleClose(owned)
    }

    private suspend fun stopGeneration(generation: Long) {
        AmbientCloseBudget(clock).run(remaining(freshDeadline())) { audio.stopCapture(generation) }
    }

    private fun beginRun(nextLease: AmbientLease, nextDispatch: Long): Pair<Long, CompletableDeferred<Boolean>> {
        synchronized(stateLock) {
            runToken += 1
            val token = runToken
            lease = nextLease
            dispatchToken = nextDispatch
            closing = false
            closeSeen = false
            captureGeneration = 0L
            capture = null
            link = null
            phase = AmbientPhase.CONNECTING
            statusMessage = null
            usageUnconfirmed = false
            elapsedSeconds = 0L
            inputRms = 0.0
            outputRms = 0.0
            timeline = AmbientTimeline()
            timeline.reset(emptyList())
            calls = AmbientCallBook(token)
            calls.bind(token)
            flight = AmbientFlightBook()
            ledger = AmbientUsageLedger()
            portal = AmbientPortalLedger()
            voice = AmbientVoiceNote()
            closeGate = CompletableDeferred()
            startSignal?.complete(false)
            val signal = CompletableDeferred<Boolean>()
            startSignal = signal
            publishLocked()
            return token to signal
        }
    }

    private fun takeClaim(deadline: Long, failed: Boolean, status: String?, sendClose: Boolean): Claim {
        val live = phase == AmbientPhase.CONNECTING ||
            phase == AmbientPhase.LISTENING ||
            phase == AmbientPhase.SPEAKING ||
            link != null
        if (!live || closing) {
            return Claim(
                live = false,
                token = runToken,
                generation = 0L,
                dispatchToken = 0L,
                link = null,
                deadline = deadline,
                failed = failed,
                status = status,
                sendClose = false,
                waitForClose = false,
                closeSeenAtStart = closeSeen,
                closeGate = closeGate,
            )
        }
        closing = true
        phase = AmbientPhase.CLOSING
        startSignal?.complete(false)
        publishLocked()
        return Claim(
            live = true,
            token = runToken,
            generation = captureGeneration,
            dispatchToken = dispatchToken,
            link = link,
            deadline = deadline,
            failed = failed,
            status = status,
            sendClose = sendClose && link != null && !closeSeen,
            waitForClose = sendClose && link != null,
            closeSeenAtStart = closeSeen,
            closeGate = closeGate,
        )
    }

    private fun isLive(): Boolean = synchronized(stateLock) {
        phase == AmbientPhase.CONNECTING ||
            phase == AmbientPhase.LISTENING ||
            phase == AmbientPhase.SPEAKING ||
            link != null
    }

    private fun same(token: Long): Boolean = synchronized(stateLock) {
        runToken == token && !closing
    }

    private fun generationOf(token: Long): Long = synchronized(stateLock) {
        if (runToken == token && !closing) captureGeneration else -1L
    }

    private fun freshDeadline(): Long {
        val now = clock.nowNanos()
        val budget = AmbientProtocol.CLOSE_GRACE_NANOS
        if (now > Long.MAX_VALUE - budget) return now
        return now + budget
    }

    private fun remaining(deadline: Long): Long {
        val now = clock.nowNanos()
        return if (now >= deadline) 0L else deadline - now
    }

    private fun fixedStatus(status: String?): String = when (status) {
        AmbientRunStatus.REJECTED,
        AmbientRunStatus.DISCONNECTED,
        AmbientRunStatus.MICROPHONE,
        AmbientRunStatus.FOCUS,
        AmbientRunStatus.HEADSET,
        AmbientRunStatus.NOT_READY,
        AmbientRunStatus.FAILED,
        -> status
        else -> AmbientRunStatus.FAILED
    }

    private fun publishLocked() {
        val shown = portal.display()
        snapshotFlow.value = AmbientSnapshot(
            phase = phase,
            captions = timeline.captions.toList(),
            activities = timeline.activities.toList(),
            muted = muted,
            inputRms = inputRms,
            outputRms = outputRms,
            voiceSeconds = voice.seconds,
            voiceHasSnapshot = voice.hasSnapshot,
            voiceUnconfirmed = voice.unconfirmed,
            local = ledger.usage,
            portalUsd = shown.amountUsd,
            portalSettled = shown.settled,
            usageUnconfirmed = usageUnconfirmed || voice.unconfirmed,
            statusMessage = statusMessage,
            elapsedSeconds = elapsedSeconds,
        )
    }

    private fun idleSnapshot(): AmbientSnapshot = AmbientSnapshot(
        phase = AmbientPhase.IDLE,
        captions = emptyList(),
        activities = emptyList(),
        muted = false,
        inputRms = 0.0,
        outputRms = 0.0,
        voiceSeconds = 0.0,
        voiceHasSnapshot = false,
        voiceUnconfirmed = false,
        local = AmbientBackendUsage(),
        portalUsd = null,
        portalSettled = false,
        usageUnconfirmed = false,
        statusMessage = null,
        elapsedSeconds = 0L,
    )

    private sealed interface OpenAttempt {
        class Ready(val link: AmbientLink) : OpenAttempt
        data object Failed : OpenAttempt
    }

    private class Claim(
        val live: Boolean,
        val token: Long,
        val generation: Long,
        val dispatchToken: Long,
        val link: AmbientLink?,
        val deadline: Long,
        val failed: Boolean,
        val status: String?,
        val sendClose: Boolean,
        val waitForClose: Boolean,
        val closeSeenAtStart: Boolean,
        val closeGate: CompletableDeferred<Unit>,
    )
}
