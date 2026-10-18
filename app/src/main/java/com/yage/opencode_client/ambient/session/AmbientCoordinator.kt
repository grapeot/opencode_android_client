package com.yage.opencode_client.ambient.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.yage.opencode_client.ambient.audio.AmbientNativeAudioEngine
import com.yage.opencode_client.ambient.gateway.AmbientLiveGateway
import com.yage.opencode_client.ambient.protocol.AmbientBackendUsage
import com.yage.opencode_client.ambient.protocol.AmbientConfiguration
import com.yage.opencode_client.ambient.protocol.AmbientEndpoint
import com.yage.opencode_client.ambient.protocol.AmbientLease
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientSnapshot
import com.yage.opencode_client.ambient.protocol.AmbientStartPolicy
import com.yage.opencode_client.ambient.protocol.AmbientToolEffect
import com.yage.opencode_client.ambient.service.AmbientVoiceBridge
import com.yage.opencode_client.ambient.service.AmbientVoiceHost
import com.yage.opencode_client.ambient.service.AmbientVoiceService
import com.yage.opencode_client.ambient.tools.AmbientToolRunner
import com.yage.opencode_client.ambient.transport.OkHttpAmbientTransport
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One Ambient run for the process. The constructor does not read tokens, open the
 * microphone, or start the foreground service. Hilt constructs it directly; no module change.
 */
@Singleton
class AmbientCoordinator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsManager,
    private val repository: OpenCodeRepository,
) : AmbientVoiceHost {
    private val lock = Any()
    private val admit = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callerGate = AmbientCallerGate()
    private val bindingBook = AmbientBindingBook()
    private val published = MutableStateFlow(idleSnapshot())
    private val reservedFlow = MutableStateFlow(false)

    val snapshot: StateFlow<AmbientSnapshot> = published.asStateFlow()
    val microphoneReserved: StateFlow<Boolean> = reservedFlow.asStateFlow()

    private var engineRef: AmbientNativeAudioEngine? = null
    private var gatewayRef: AmbientLiveGateway? = null
    private var transportRef: OkHttpAmbientTransport? = null
    private var runId: String? = null
    private var dispatchToken: Long = 0L
    private var phase: AmbientPhase = AmbientPhase.IDLE
    private var reserved = false
    private var revoked = false
    private var linkRevoked = false
    private var serviceReady = false
    private var serviceRunId: String? = null
    private var activeContext: AmbientStartContext? = null
    private var activeOwner: String? = null
    private var agent: String? = null
    private var lease: AmbientLease? = null
    private var controllerRef: AmbientController? = null
    private var startJob: Job? = null
    private var collector: Job? = null
    private var pendingAck: CompletableDeferred<String>? = null

    init {
        AmbientVoiceBridge.attach(this)
    }

    /** Returns the caller token MainActivity must pass back to [onActivityStop]. */
    fun onActivityStart(): Long {
        val token = synchronized(lock) { callerGate.onStart() }
        return token
    }

    /**
     * Configuration changes and a token that is not the current [onActivityStart] result
     * are ignored. Connecting work is cancelled. Listening or speaking with the microphone
     * service already up keeps the same run.
     */
    fun onActivityStop(callerToken: Long, changingConfigurations: Boolean) {
        val action = synchronized(lock) {
            val accepted = callerGate.onStop(callerToken, changingConfigurations)
            val id = runId
            id to AmbientBackgroundPolicy.activityStop(accepted, phase, serviceReady)
        }
        val id = action.first ?: return
        when (action.second) {
            AmbientActivityDecision.IGNORE, AmbientActivityDecision.HOLD -> Unit
            AmbientActivityDecision.CANCEL_CONNECTING, AmbientActivityDecision.END_OWNER -> {
                requestEnd(id)
            }
        }
    }

    fun invalidateHost(hostProfileId: String, runtimeGeneration: Long) {
        val id = synchronized(lock) {
            val active = activeContext ?: return
            val hostChanged = active.hostProfileId != hostProfileId
            val runtimeChanged = active.runtimeGeneration != runtimeGeneration
            if (!hostChanged && !runtimeChanged) return
            val reason = if (hostChanged) AmbientRevokeReason.HOST else AmbientRevokeReason.RUNTIME
            if (!AmbientBackgroundPolicy.revoke(reason).endOwner) return
            revoked = true
            runId
        } ?: return
        requestEnd(id)
    }

    fun invalidateDisconnected() {
        val id = synchronized(lock) {
            if (runId == null) return
            if (!AmbientBackgroundPolicy.revoke(AmbientRevokeReason.DISCONNECT).endOwner) return
            linkRevoked = true
            revoked = true
            runId
        } ?: return
        requestEnd(id)
    }

    fun invalidateFeatureDisabled() {
        val id = synchronized(lock) {
            if (runId == null) return
            if (!AmbientBackgroundPolicy.revoke(AmbientRevokeReason.FEATURE_DISABLED).endOwner) return
            revoked = true
            runId
        } ?: return
        requestEnd(id)
    }

    /** Chat selection does not stop the run and does not replace the captured directory. */
    fun onChatSelectionChanged(sessionId: String?, directory: String?) {
        synchronized(lock) {
            val decision = AmbientBackgroundPolicy.chatSelection()
            if (decision.stop || decision.rebindWorkspace) return
            bindingBook.noteChatSelection(sessionId, directory)
        }
    }

    fun foregroundEligible(runId: String): Boolean = synchronized(lock) {
        callerGate.isForeground &&
            serviceReady &&
            serviceRunId == runId &&
            this.runId == runId &&
            !revoked
    }

    fun mute(muted: Boolean) {
        controllerRef?.mute(muted)
    }

    fun interrupt() {
        controllerRef?.interrupt()
    }

    suspend fun end() {
        val id = synchronized(lock) { runId } ?: return
        endMatching(id)
    }

    suspend fun start(context: AmbientStartContext, composerMicBusy: Boolean): AmbientStartResult {
        val committed = admit.withLock { commit(context, composerMicBusy) }
        if (committed is AdmitCommit.Denied) return reject(committed.code)
        val run = (committed as AdmitCommit.Ready).run
        val job = kotlin.coroutines.coroutineContext[Job]
        synchronized(lock) { if (runId == run.id) startJob = job }
        var controller: AmbientController? = null
        try {
            val epoch = System.currentTimeMillis()
            val token = gateway().arm(
                run.context.hostProfileId,
                run.context.runtimeGeneration,
                run.directory,
                epoch,
                run.id,
            )
            val activeLease = synchronized(lock) {
                if (runId != run.id || revoked || token == 0L) {
                    null
                } else {
                    dispatchToken = token
                    activeOwner = run.id
                    val next = AmbientLease(
                        hostProfileId = run.context.hostProfileId,
                        runtimeGeneration = run.context.runtimeGeneration,
                        directory = run.directory,
                        epoch = epoch,
                        ownerToken = run.id,
                        transportToken = token,
                    )
                    lease = next
                    next
                }
            }
            if (activeLease == null) {
                if (token != 0L) gatewayRef?.retire(token)
                releaseIfCurrent(run.id)
                return reject(AmbientRunStatus.FAILED)
            }
            val created = newController(run)
            controller = created
            val owned = synchronized(lock) {
                if (runId != run.id || revoked) {
                    false
                } else {
                    controllerRef = created
                    true
                }
            }
            if (!owned) {
                releaseIfCurrent(run.id)
                return reject(AmbientRunStatus.FAILED)
            }
            watch(run.id, created)
            created.start(
                AmbientConfiguration(
                    portalBaseUrl = run.portalBaseUrl,
                    builderToken = run.builderToken,
                    ownerToken = run.id,
                ),
                activeLease,
                token,
            )
            copySnapshot(run.id, created)
            return if (stillLive(run.id)) {
                AmbientStartResult.Started
            } else {
                AmbientStartResult.Rejected(published.value.statusMessage ?: AmbientRunStatus.REJECTED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return reject(AmbientRunStatus.FAILED)
        } finally {
            withContext(NonCancellable) {
                val created = controller
                if (created != null) copySnapshot(run.id, created)
                if (!stillLive(run.id)) releaseIfCurrent(run.id)
            }
        }
    }

    override fun canStartService(runId: String): Boolean = synchronized(lock) {
        val waiter = pendingAck
        waiter != null &&
            !waiter.isCompleted &&
            this.runId == runId &&
            activeOwner == runId &&
            !revoked &&
            callerGate.isForeground
    }

    override fun onServiceForeground(runId: String): Boolean {
        val waiter = synchronized(lock) {
            if (this.runId != runId || pendingAck == null) return false
            serviceRunId = runId
            serviceReady = true
            pendingAck
        } ?: return false
        if (!waiter.isCompleted) waiter.complete(runId)
        return true
    }

    override fun onServiceStop(runId: String) {
        if (synchronized(lock) { this.runId != runId }) return
        requestEnd(runId)
    }

    override fun onServiceDestroyed(runId: String) {
        val fate = synchronized(lock) {
            AmbientBackgroundPolicy.serviceDestroyed(runId, this.runId, intentional = false)
        }
        if (!fate.endOwner) return
        requestEnd(runId)
    }

    private fun requestEnd(id: String) {
        val token = synchronized(lock) {
            if (runId != id) return
            revoked = true
            dispatchToken
        }
        if (token != 0L) gatewayRef?.retire(token)
        scope.launch { endMatching(id) }
    }

    private suspend fun endMatching(id: String) {
        val owned = synchronized(lock) {
            if (runId != id) return
            revoked = true
            EndOwned(dispatchToken, startJob, controllerRef)
        }
        if (owned.token != 0L) gatewayRef?.retire(owned.token)
        owned.job?.cancel()
        try {
            owned.controller?.end()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { releaseIfCurrent(id) }
            throw cancelled
        }
        releaseIfCurrent(id)
    }

    private suspend fun allowCapture(id: String): Boolean {
        val deferred = CompletableDeferred<String>()
        val launch = synchronized(lock) {
            if (runId != id || revoked || !callerGate.isForeground) return false
            serviceRunId = id
            serviceReady = false
            pendingAck = deferred
            true
        }
        if (!launch) return false
        val started = try {
            // Visible owner already passed the foreground gate. startService lets a
            // rejected or stale run exit without a startForegroundService deadline.
            appContext.startService(startIntent(id))
            true
        } catch (_: Exception) {
            false
        }
        if (!started) {
            stopMicService(id)
            return false
        }
        val acked = try {
            withTimeoutOrNull(SERVICE_ACK_MILLIS) { deferred.await() }
        } finally {
            synchronized(lock) { if (pendingAck === deferred) pendingAck = null }
        }
        if (acked != id) {
            stopMicService(id)
            return false
        }
        val ok = synchronized(lock) {
            runId == id && !revoked && callerGate.isForeground && serviceReady && serviceRunId == id
        }
        if (!ok) stopMicService(id)
        return ok
    }

    private fun watch(id: String, controller: AmbientController) {
        val job = scope.launch {
            var seen = false
            try {
                controller.snapshot.collect { snap ->
                    val release = synchronized(lock) {
                        if (!AmbientBackgroundPolicy.publishAllowed(id, runId)) {
                            false
                        } else if (!seen && snap.phase == AmbientPhase.IDLE) {
                            false
                        } else {
                            seen = true
                            phase = snap.phase
                            published.value = snap
                            snap.phase == AmbientPhase.IDLE || snap.phase == AmbientPhase.FAILED
                        }
                    }
                    if (release) releaseIfCurrent(id)
                }
            } catch (_: CancellationException) {
            }
        }
        synchronized(lock) {
            if (runId == id) collector = job else job.cancel()
        }
    }

    private fun newController(run: StartedRun): AmbientController {
        val tools = AmbientToolRunner(
            gateway = gateway().authorizing,
            currentLease = {
                synchronized(lock) { if (revoked || runId != run.id) null else lease }
            },
            currentBinding = { synchronized(lock) { if (runId != run.id) null else bindingBook.active() } },
            currentAgent = { synchronized(lock) { if (runId != run.id) null else agent } },
        )
        return AmbientController(
            transport = transport(),
            audio = engine(),
            tools = tools,
            authority = AmbientRunAuthority(
                ownerValid = { ownerValid(it) },
                retireDispatch = { token -> retireDispatch(run.id, token) },
                applyEffect = { effect -> applyEffect(run.id, effect) },
            ),
            beforeCapture = { allowCapture(run.id) },
            clock = AndroidAmbientClock(),
        )
    }

    private fun ownerValid(lease: AmbientLease): Boolean = synchronized(lock) {
        if (revoked || linkRevoked) return false
        val active = activeContext ?: return false
        if (lease.ownerToken != activeOwner) return false
        if (!settings.ambientEnabled) return false
        lease.allows(
            connected = active.connected,
            hostProfileId = active.hostProfileId,
            runtimeGeneration = active.runtimeGeneration,
            workspaceUnchanged = true,
            epoch = lease.epoch,
            ownerToken = lease.ownerToken,
        )
    }

    private fun retireDispatch(id: String, token: Long): Boolean {
        val mine = synchronized(lock) { runId == id && token == dispatchToken && token != 0L }
        val retired = gatewayRef?.retire(token) == true
        return retired && mine
    }

    private fun applyEffect(id: String, effect: AmbientToolEffect) {
        synchronized(lock) {
            val owner = AmbientBackgroundPolicy.publishAllowed(id, runId) && !revoked
            bindingBook.apply(owner, effect)
        }
    }

    private fun commit(context: AmbientStartContext, composerMicBusy: Boolean): AdmitCommit {
        return synchronized(lock) {
            val probe = AmbientBackgroundPolicy.admit(
                composerMicBusy = composerMicBusy,
                alreadyReserved = reserved,
                foreground = callerGate.isForeground,
                recordGranted = hasRecordAudio(),
                enabled = settings.ambientEnabled,
                connected = context.connected,
                hostPresent = context.hostProfileId.isNotBlank(),
                builderReady = true,
            )
            if (!probe.accepted) return AdmitCommit.Denied(probe.code ?: AmbientRunStatus.REJECTED)
            val credential = builderCredential()
                ?: return AdmitCommit.Denied(AmbientRunStatus.NOT_READY)
            val id = UUID.randomUUID().toString()
            val directory = AmbientStartPolicy.createDirectoryArgument(context.directory)
            reserved = true
            reservedFlow.value = true
            revoked = false
            linkRevoked = false
            phase = AmbientPhase.CONNECTING
            runId = id
            dispatchToken = 0L
            serviceReady = false
            serviceRunId = null
            activeContext = context
            activeOwner = id
            agent = context.agent?.takeIf { it.isNotBlank() }
            lease = null
            controllerRef = null
            bindingBook.begin(context.hostProfileId, directory)
            AdmitCommit.Ready(
                StartedRun(
                    id = id,
                    directory = directory,
                    portalBaseUrl = credential.first,
                    builderToken = credential.second,
                    context = context,
                ),
            )
        }
    }

    private fun reject(code: String): AmbientStartResult {
        synchronized(lock) {
            if (reserved) return@synchronized
            val current = published.value
            if (current.phase == AmbientPhase.IDLE || current.phase == AmbientPhase.FAILED) {
                published.value = current.copy(statusMessage = code)
            }
        }
        return AmbientStartResult.Rejected(code)
    }

    private fun releaseIfCurrent(id: String) {
        val stopService = synchronized(lock) {
            if (runId != id) return
            val token = dispatchToken
            val svc = serviceRunId
            runId = null
            dispatchToken = 0L
            lease = null
            activeContext = null
            activeOwner = null
            controllerRef = null
            startJob = null
            reserved = false
            revoked = false
            linkRevoked = false
            serviceReady = false
            serviceRunId = null
            reservedFlow.value = false
            pendingAck?.let { if (!it.isCompleted) it.complete("") }
            pendingAck = null
            collector?.cancel()
            collector = null
            bindingBook.endVoice()
            token to svc
        }
        if (stopService.first != 0L) gatewayRef?.retire(stopService.first)
        val svc = stopService.second
        if (svc != null) AmbientVoiceBridge.requestStop(svc)
    }

    private fun stopMicService(id: String) {
        val stop = synchronized(lock) {
            if (serviceRunId != id) return
            serviceRunId = null
            serviceReady = false
            if (pendingAck?.isCompleted == false) pendingAck?.complete("")
            true
        }
        if (stop) AmbientVoiceBridge.requestStop(id)
    }

    private fun startIntent(id: String): Intent =
        Intent(appContext, AmbientVoiceService::class.java)
            .setAction(AmbientVoiceService.ACTION_START)
            .putExtra(AmbientVoiceService.EXTRA_RUN_ID, id)

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun builderCredential(): Pair<String, String>? {
        val base = settings.aiBuilderBaseURL.trim()
        val token = sanitizeBearer(settings.aiBuilderToken)
        if (token.isEmpty() || AmbientEndpoint.sessionUrl(base) == null) return null
        return base to token
    }

    private fun engine(): AmbientNativeAudioEngine = synchronized(lock) {
        engineRef ?: AmbientNativeAudioEngine(appContext).also { engineRef = it }
    }

    private fun gateway(): AmbientLiveGateway = synchronized(lock) {
        gatewayRef ?: AmbientLiveGateway(repository).also { gatewayRef = it }
    }

    private fun transport(): OkHttpAmbientTransport = synchronized(lock) {
        transportRef ?: OkHttpAmbientTransport().also { transportRef = it }
    }

    private fun copySnapshot(id: String, controller: AmbientController) {
        synchronized(lock) {
            if (!AmbientBackgroundPolicy.publishAllowed(id, runId)) return
            val snap = controller.snapshot.value
            phase = snap.phase
            published.value = snap
        }
    }

    private fun stillLive(id: String): Boolean = synchronized(lock) {
        if (runId != id) return false
        phase == AmbientPhase.LISTENING || phase == AmbientPhase.SPEAKING || phase == AmbientPhase.CLOSING
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
    )

    private class StartedRun(
        val id: String,
        val directory: String?,
        val portalBaseUrl: String,
        val builderToken: String,
        val context: AmbientStartContext,
    )

    private class EndOwned(
        val token: Long,
        val job: Job?,
        val controller: AmbientController?,
    )

    private sealed interface AdmitCommit {
        class Ready(val run: StartedRun) : AdmitCommit
        class Denied(val code: String) : AdmitCommit
    }

    companion object {
        const val SERVICE_ACK_MILLIS = 5_000L
    }
}

private fun sanitizeBearer(raw: String): String =
    raw.trim().filterNot { ch ->
        ch.isWhitespace() || Character.getType(ch) == Character.FORMAT.toInt() || ch == '\uFEFF'
    }
