package com.yage.opencode_client.ambient.protocol

import kotlinx.coroutines.flow.Flow

interface AmbientSocket {
    suspend fun send(text: String)
    suspend fun receive(): String
    suspend fun close()
}

/** One reader. Implementations must not attach Builder bearer or OpenCode Basic auth. */
interface AmbientLink {
    val incoming: Flow<AmbientServerEvent>
    suspend fun send(event: AmbientClientEvent)
    suspend fun close()
}

interface AmbientTransport {
    suspend fun open(configuration: AmbientConfiguration): AmbientLink
}

interface AmbientCaptureHandle {
    val generation: Long
    suspend fun receive(): ByteArray?
    fun finish()
}

/**
 * Fixed causes. Free-form strings are not a cause.
 * [PLAYBACK_IDLE] means the engine's current private playback generation drained.
 * [FOCUS_LOSS] and [HEADSET_REMOVED] are interruptions.
 * [FAILED] is an engine failure. Clear and stop are caller actions, not causes.
 */
enum class AmbientAudioCause {
    PLAYBACK_IDLE,
    FOCUS_LOSS,
    HEADSET_REMOVED,
    FAILED,
}

/**
 * Audio callbacks for one run. [generation] is [AmbientCaptureHandle.generation].
 * The engine's playback generation is not on this type.
 */
sealed interface AmbientAudioEvent {
    val generation: Long

    data class Status(
        override val generation: Long,
        val cause: AmbientAudioCause,
    ) : AmbientAudioEvent

    /**
     * Throttled input and output RMS. Implementations emit this only when
     * [AmbientLevelGate] allows it. The raw samples are unchanged.
     */
    data class Level(
        override val generation: Long,
        val inputRms: Double,
        val outputRms: Double,
    ) : AmbientAudioEvent
}

/**
 * Hardware stays behind this interface. An implementation constructor must not open
 * the microphone, request audio focus, or touch the network. Protocol constructors
 * do not call this interface. This type does not reference a coordinator.
 *
 * [events] carries the capture generation that owns the run. The controller collects it.
 *
 * [enqueuePlayback] and [clearPlayback] take that same capture generation. It is run
 * ownership, not a playback counter. The engine privately owns the playback generation,
 * increments it on [clearPlayback] and [stopCapture], and drops queued buffers and late
 * completions unless both generations still match
 * ([AmbientPlaybackDecision.shouldPlayScheduled]). A stale completion must not touch a
 * released track and must not emit [AmbientAudioCause.PLAYBACK_IDLE]. Callers must not
 * keep a second playback counter.
 *
 * [clearPlayback] does not mute the run. It discards the current queue and bumps the
 * private playback generation. The next [enqueuePlayback] for the same live capture
 * generation is audible: the engine stamps that buffer with the new playback generation.
 * [AmbientPlaybackDecision.shouldRestartFromClear] stays false. Playback stays silent
 * only when the caller stops passing the live capture generation, or when that
 * generation has been stopped.
 */
interface AmbientAudioEngine {
    val events: Flow<AmbientAudioEvent>

    suspend fun startCapture(): AmbientCaptureHandle
    suspend fun stopCapture(generation: Long)
    fun setMuted(muted: Boolean)
    fun enqueuePlayback(pcm: ByteArray, generation: Long)
    fun clearPlayback(generation: Long)
}

/**
 * Eight-tool executor. Each OpenCode write goes through [AmbientAuthorizingGateway.exchange]
 * with a token that is still active at send time. [AmbientLease.allows] is only the early check.
 */
interface AmbientToolExecutor {
    suspend fun execute(
        name: String,
        arguments: String,
        lease: AmbientLease,
        dispatchToken: Long,
    ): AmbientToolEffect
}
