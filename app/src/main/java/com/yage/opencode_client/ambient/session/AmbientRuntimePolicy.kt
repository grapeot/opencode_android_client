package com.yage.opencode_client.ambient.session

import com.yage.opencode_client.ambient.protocol.AmbientBinding
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientToolEffect

/**
 * Values the later main screen passes into [AmbientCoordinator.start].
 * Tool arguments cannot fill these fields.
 */
data class AmbientStartContext(
    val hostProfileId: String,
    val runtimeGeneration: Long,
    val connected: Boolean,
    val directory: String? = null,
    val agent: String? = null,
)

sealed interface AmbientStartResult {
    data object Started : AmbientStartResult
    data class Rejected(val code: String) : AmbientStartResult
}

/** Fixed [AmbientRunStatus] code, or null when the start was admitted. */
data class AmbientAdmission(
    val code: String?,
    val reserve: Boolean,
) {
    val accepted: Boolean get() = code == null && reserve
}

data class AmbientStreamVolume(
    val min: Int,
    val max: Int,
    val current: Int,
)

enum class AmbientRevokeReason {
    HOST,
    RUNTIME,
    DISCONNECT,
    FEATURE_DISABLED,
}

data class AmbientRevokeDecision(
    val endOwner: Boolean,
    val restart: Boolean,
)

data class AmbientChatDecision(
    val stop: Boolean,
    val rebindWorkspace: Boolean,
)

enum class AmbientActivityDecision {
    IGNORE,
    HOLD,
    CANCEL_CONNECTING,
    END_OWNER,
}

data class AmbientServiceFate(
    val endOwner: Boolean,
    val restart: Boolean,
)

/**
 * Activity start/stop token. Configuration changes and a stale token do not count
 * as leaving the foreground.
 */
class AmbientCallerGate {
    private var nextToken = 0L
    private var foregroundToken = 0L

    val isForeground: Boolean
        get() = foregroundToken != 0L

    fun onStart(): Long {
        nextToken += 1
        foregroundToken = nextToken
        return foregroundToken
    }

    fun onStop(callerToken: Long, changingConfigurations: Boolean): Boolean {
        if (changingConfigurations || callerToken == 0L || callerToken != foregroundToken) return false
        foregroundToken = 0L
        return true
    }
}

object AmbientBackgroundPolicy {
    fun admit(
        composerMicBusy: Boolean,
        alreadyReserved: Boolean,
        foreground: Boolean,
        recordGranted: Boolean,
        enabled: Boolean,
        connected: Boolean,
        hostPresent: Boolean,
        builderReady: Boolean,
    ): AmbientAdmission {
        if (composerMicBusy) return AmbientAdmission(AmbientRunStatus.REJECTED, reserve = false)
        if (alreadyReserved) return AmbientAdmission(AmbientRunStatus.REJECTED, reserve = false)
        if (!foreground) return AmbientAdmission(AmbientRunStatus.REJECTED, reserve = false)
        if (!recordGranted) return AmbientAdmission(AmbientRunStatus.MICROPHONE, reserve = false)
        if (!enabled) return AmbientAdmission(AmbientRunStatus.REJECTED, reserve = false)
        if (!connected) return AmbientAdmission(AmbientRunStatus.DISCONNECTED, reserve = false)
        if (!hostPresent || !builderReady) return AmbientAdmission(AmbientRunStatus.NOT_READY, reserve = false)
        return AmbientAdmission(code = null, reserve = true)
    }

    fun activityStop(
        callerAccepted: Boolean,
        phase: AmbientPhase,
        serviceReady: Boolean,
    ): AmbientActivityDecision {
        if (!callerAccepted) return AmbientActivityDecision.IGNORE
        return when (phase) {
            AmbientPhase.LISTENING, AmbientPhase.SPEAKING ->
                if (serviceReady) AmbientActivityDecision.HOLD else AmbientActivityDecision.END_OWNER
            AmbientPhase.CONNECTING -> AmbientActivityDecision.CANCEL_CONNECTING
            AmbientPhase.CLOSING -> AmbientActivityDecision.END_OWNER
            AmbientPhase.IDLE, AmbientPhase.FAILED ->
                if (serviceReady) AmbientActivityDecision.END_OWNER else AmbientActivityDecision.IGNORE
        }
    }

    fun revoke(reason: AmbientRevokeReason): AmbientRevokeDecision =
        when (reason) {
            AmbientRevokeReason.HOST,
            AmbientRevokeReason.RUNTIME,
            AmbientRevokeReason.DISCONNECT,
            AmbientRevokeReason.FEATURE_DISABLED,
            -> AmbientRevokeDecision(endOwner = true, restart = false)
        }

    fun chatSelection(): AmbientChatDecision =
        AmbientChatDecision(stop = false, rebindWorkspace = false)

    fun serviceDestroyed(
        reportedRunId: String?,
        currentRunId: String?,
        intentional: Boolean,
    ): AmbientServiceFate {
        if (intentional || reportedRunId.isNullOrBlank() || reportedRunId != currentRunId) {
            return AmbientServiceFate(endOwner = false, restart = false)
        }
        return AmbientServiceFate(endOwner = true, restart = false)
    }

    /** A late open or callback may publish only while it is still the current run. */
    fun publishAllowed(writerRunId: String, currentRunId: String?): Boolean =
        currentRunId != null && writerRunId == currentRunId
}

/**
 * Bindings keyed by host plus the directory captured at start.
 * Voice end keeps the slot. A 404 clears it only when the session id matches.
 * A null model on a later effect keeps the model already stored.
 */
class AmbientBindingBook {
    private val slots = LinkedHashMap<String, AmbientBinding>()
    private var activeKey: String? = null
    private var activeHost: String? = null
    private var activeDirectory: String? = null

    fun begin(hostProfileId: String, capturedDirectory: String?) {
        activeHost = hostProfileId
        activeDirectory = capturedDirectory
        activeKey = key(hostProfileId, capturedDirectory)
    }

    fun active(): AmbientBinding? = activeKey?.let { slots[it] }

    fun capturedHost(): String? = activeHost

    fun capturedDirectory(): String? = activeDirectory

    fun noteChatSelection(@Suppress("UNUSED_PARAMETER") sessionId: String?, @Suppress("UNUSED_PARAMETER") directory: String?) = Unit

    fun endVoice() = Unit

    fun apply(ownerMatches: Boolean, effect: AmbientToolEffect) {
        val key = activeKey ?: return
        if (!ownerMatches) return
        val current = slots[key]
        val clearId = effect.clearSessionId
        if (!clearId.isNullOrBlank()) {
            if (current != null && current.sessionId == clearId) slots.remove(key)
            return
        }
        val sessionId = effect.sessionId?.takeIf { it.isNotBlank() } ?: return
        val directory = effect.directory?.takeIf { it.isNotBlank() } ?: current?.directory ?: return
        val model = effect.model?.takeIf { it.isNotBlank() } ?: current?.model ?: return
        slots[key] = AmbientBinding(
            sessionId = sessionId,
            messageId = effect.messageId?.takeIf { it.isNotBlank() } ?: current?.messageId,
            directory = directory,
            model = model,
        )
    }

    fun key(hostProfileId: String, capturedDirectory: String?): String =
        hostProfileId + "\u0000" + (capturedDirectory ?: "")
}
