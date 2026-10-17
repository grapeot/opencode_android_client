package com.yage.opencode_client.ambient.audio

import com.yage.opencode_client.ambient.protocol.AmbientAudioEvent
import com.yage.opencode_client.ambient.protocol.AmbientLevelGate
import com.yage.opencode_client.ambient.protocol.AmbientPcm
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge

/** 20 ms at 24 kHz mono PCM16. */
internal const val AMBIENT_UPLINK_FRAME_BYTES = 960

internal class AmbientFrameAssembler(
    private val frameBytes: Int = AMBIENT_UPLINK_FRAME_BYTES,
) {
    private var pending = ByteArray(0)

    fun push(wire: ByteArray): List<ByteArray> {
        if (wire.isEmpty()) return emptyList()
        val combined = if (pending.isEmpty()) wire else pending + wire
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (combined.size - offset >= frameBytes) {
            frames.add(combined.copyOfRange(offset, offset + frameBytes))
            offset += frameBytes
        }
        pending = if (offset == 0) combined.copyOf() else combined.copyOfRange(offset, combined.size)
        return frames
    }

    fun buffered(): Int = pending.size

    fun clear() {
        pending = ByteArray(0)
    }
}

internal class AmbientLevelTap(
    private val gate: AmbientLevelGate = AmbientLevelGate(),
) {
    var inputRms: Double = 0.0
        private set
    var outputRms: Double = 0.0
        private set

    @Synchronized
    fun noteInput(pcm: ByteArray, nowNanos: Long, generation: Long): AmbientAudioEvent.Level? {
        inputRms = AmbientPcm.rms(pcm)
        return emit(nowNanos, generation)
    }

    @Synchronized
    fun noteOutput(pcm: ByteArray, nowNanos: Long, generation: Long): AmbientAudioEvent.Level? {
        outputRms = AmbientPcm.rms(pcm)
        return emit(nowNanos, generation)
    }

    /** Clears output RMS from the last real input. Does not synthesize a PCM frame. */
    @Synchronized
    fun markOutputSilent(nowNanos: Long, generation: Long): AmbientAudioEvent.Level? {
        outputRms = 0.0
        return emit(nowNanos, generation)
    }

    private fun emit(nowNanos: Long, generation: Long): AmbientAudioEvent.Level? {
        if (!gate.allow(nowNanos)) return null
        return AmbientAudioEvent.Level(generation, inputRms, outputRms)
    }
}

/**
 * Status and level do not share a drop-oldest buffer. A level flood cannot
 * discard FOCUS_LOSS, HEADSET_REMOVED, FAILED, or PLAYBACK_IDLE.
 * Status keeps one replayed value so a terminal emitted during start, before
 * the controller collects, is still delivered. Levels are not replayed.
 */
internal class AmbientEventPipe {
    private val statuses = MutableSharedFlow<AmbientAudioEvent>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val levels = MutableSharedFlow<AmbientAudioEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: Flow<AmbientAudioEvent> = merge(statuses, levels)

    fun emitStatus(event: AmbientAudioEvent) {
        statuses.tryEmit(event)
    }

    fun emitLevel(event: AmbientAudioEvent) {
        levels.tryEmit(event)
    }
}
