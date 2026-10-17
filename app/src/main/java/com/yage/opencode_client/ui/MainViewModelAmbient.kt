package com.yage.opencode_client.ui

import com.yage.opencode_client.ambient.protocol.AmbientBackendUsage
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientSnapshot
import com.yage.opencode_client.ambient.session.AmbientStartContext

internal fun idleAmbientSnapshot(): AmbientSnapshot = AmbientSnapshot(
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

/** Immutable values for one [com.yage.opencode_client.ambient.session.AmbientCoordinator.start] call. */
internal fun ambientStartContext(state: AppState): AmbientStartContext {
    return AmbientStartContext(
        hostProfileId = state.currentHostProfileId.orEmpty(),
        runtimeGeneration = state.runtimeGeneration,
        connected = state.isConnected,
        directory = state.currentSession?.directory,
        agent = state.selectedAgentName,
    )
}

/**
 * True while composer capture owns the microphone.
 * [speechCaptureOpen] stays set for draft anchoring after the mic is released, so it
 * counts only together with the file owner that is installed before [isRecording].
 * Transcription and preserved-audio retry do not hold the microphone.
 */
internal fun composerMicrophoneBusy(
    speechCaptureOpen: Boolean,
    isRecording: Boolean,
    captureReserved: Boolean,
    startupJobActive: Boolean,
    cleanupJobActive: Boolean,
): Boolean {
    if (isRecording || cleanupJobActive || startupJobActive) return true
    return speechCaptureOpen && captureReserved
}

internal data class AmbientLinkSample(
    val hostProfileId: String?,
    val runtimeGeneration: Long,
    val connected: Boolean,
)

internal data class AmbientLinkRevocation(
    val hostOrRuntime: Boolean,
    val disconnected: Boolean,
)

internal fun ambientLinkRevocation(
    previous: AmbientLinkSample?,
    current: AmbientLinkSample,
): AmbientLinkRevocation {
    if (previous == null) return AmbientLinkRevocation(hostOrRuntime = false, disconnected = false)
    return AmbientLinkRevocation(
        hostOrRuntime = previous.hostProfileId != current.hostProfileId ||
            previous.runtimeGeneration != current.runtimeGeneration,
        disconnected = previous.connected && !current.connected,
    )
}
