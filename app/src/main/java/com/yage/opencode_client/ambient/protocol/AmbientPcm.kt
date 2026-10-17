package com.yage.opencode_client.ambient.protocol

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

object AmbientPcm {
    fun silence(byteCount: Int): ByteArray {
        if (byteCount <= 0) return ByteArray(0)
        val even = byteCount - byteCount % 2
        return ByteArray(even)
    }

    /** Little-endian PCM16. Values are divided by 32768 and the root mean square is capped at 1. */
    fun rms(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        val even = pcm.size - pcm.size % 2
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < even) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toDouble() / 32768.0
            sum += sample * sample
            count += 1
            index += 2
        }
        if (count == 0) return 0.0
        return min(1.0, sqrt(sum / count))
    }

    /**
     * Muted uplink is silence of the same even length. Odd trailing bytes are dropped.
     * A frame outside 2..maxAudioBytes is not sendable.
     */
    fun uplinkFrame(captured: ByteArray, muted: Boolean): ByteArray? {
        if (captured.isEmpty()) return null
        val even = captured.size - captured.size % 2
        if (even < 2 || even > AmbientProtocol.MAX_AUDIO_BYTES) return null
        if (muted) return ByteArray(even)
        return if (even == captured.size) captured.copyOf() else captured.copyOf(even)
    }
}

object AmbientMeter {
    const val GLYPH_DP = 48
    const val CONTROL_ROW_DP = 72
    const val MIN_TOUCH_DP = 48
    const val FULL_SCALE_INPUT = 0.25
    const val ATTACK_NANOS = 65_000_000L
    const val RELEASE_NANOS = 320_000_000L
    const val NAV_ICON_ANIMATES_LEVEL = false

    enum class Motion { SNAP, ATTACK, RELEASE }

    data class Transition(val fill: Double, val motion: Motion) {
        val durationNanos: Long
            get() = when (motion) {
                Motion.SNAP -> 0L
                Motion.ATTACK -> ATTACK_NANOS
                Motion.RELEASE -> RELEASE_NANOS
            }
    }

    fun sanitized(inputLevel: Double): Double {
        if (!inputLevel.isFinite()) return 0.0
        return min(1.0, maxOf(0.0, inputLevel))
    }

    fun displayFill(inputLevel: Double): Double {
        val raw = sanitized(inputLevel)
        if (raw <= 0.0) return 0.0
        return min(1.0, sqrt(raw / FULL_SCALE_INPUT))
    }

    fun isLive(phase: AmbientPhase, muted: Boolean): Boolean {
        if (muted) return false
        return phase == AmbientPhase.LISTENING || phase == AmbientPhase.SPEAKING
    }

    fun targetFill(inputLevel: Double, phase: AmbientPhase, muted: Boolean): Double {
        if (!isLive(phase, muted)) return 0.0
        return displayFill(inputLevel)
    }

    fun showsSlash(muted: Boolean): Boolean = muted

    fun transition(
        from: Double,
        inputLevel: Double,
        phase: AmbientPhase,
        muted: Boolean,
        reduceMotion: Boolean,
    ): Transition {
        val fill = targetFill(inputLevel, phase, muted)
        val live = isLive(phase, muted)
        if (reduceMotion || !live || abs(fill - from) <= 0.000_001) {
            return Transition(fill, Motion.SNAP)
        }
        return if (fill > from) Transition(fill, Motion.ATTACK) else Transition(fill, Motion.RELEASE)
    }
}

data class AmbientPlaybackDecision(
    val queuedCapture: Long,
    val currentCapture: Long,
    val queuedPlayback: Long,
    val currentPlayback: Long,
    val intendedRunning: Boolean,
    val engineRunning: Boolean,
    val playerAttached: Boolean,
    val hasAudio: Boolean,
) {
    val ownsQueuedClear: Boolean
        get() = intendedRunning && queuedCapture == currentCapture && queuedPlayback == currentPlayback

    val shouldRestartFromClear: Boolean get() = false

    val shouldPlayScheduled: Boolean
        get() = ownsQueuedClear && engineRunning && playerAttached && hasAudio
}

class AmbientLevelGate(private val intervalNanos: Long = 100_000_000L) {
    private var primed = false
    private var lastAt = 0L

    fun allow(nowNanos: Long): Boolean {
        if (primed && nowNanos - lastAt < intervalNanos) return false
        primed = true
        lastAt = nowNanos
        return true
    }
}
