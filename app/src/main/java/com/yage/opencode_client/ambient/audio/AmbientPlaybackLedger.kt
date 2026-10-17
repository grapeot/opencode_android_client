package com.yage.opencode_client.ambient.audio

import com.yage.opencode_client.ambient.protocol.AmbientPlaybackDecision

internal data class AmbientClearEffect(
    val accepted: Boolean,
    val flushLivePlayer: Boolean,
) {
    val shouldRestart: Boolean get() = false
}

internal data class AmbientDrain(
    val matches: Boolean,
    val idle: Boolean,
)

/**
 * Capture generation is the public run id. Playback generation is private and
 * increments on clear and stop. Not thread-safe; the engine serializes calls.
 */
internal class AmbientPlaybackLedger {
    var captureGeneration: Long = 0L
        private set
    var playbackGeneration: Long = 0L
        private set
    var running: Boolean = false
        private set
    var playerAttached: Boolean = false
        private set
    var staging: Int = 0
        private set
    var scheduled: Int = 0
        private set

    fun open(): Long {
        if (running) throw AmbientAudioStartException(AmbientAudioCodes.CAPTURE_ALREADY_RUNNING)
        if (captureGeneration == Long.MAX_VALUE || playbackGeneration == Long.MAX_VALUE) {
            throw AmbientAudioStartException(AmbientAudioCodes.GENERATION_OVERFLOW)
        }
        captureGeneration += 1L
        playbackGeneration += 1L
        running = true
        playerAttached = true
        staging = 0
        scheduled = 0
        return captureGeneration
    }

    fun stop(capture: Long): Boolean {
        if (!running || capture != captureGeneration) return false
        running = false
        playerAttached = false
        staging = 0
        scheduled = 0
        if (playbackGeneration != Long.MAX_VALUE) playbackGeneration += 1L
        return true
    }

    fun acceptCallback(capture: Long): Boolean = running && capture != 0L && capture == captureGeneration

    fun clear(capture: Long): AmbientClearEffect {
        if (!running || capture != captureGeneration) {
            return AmbientClearEffect(accepted = false, flushLivePlayer = false)
        }
        if (playbackGeneration == Long.MAX_VALUE) {
            throw AmbientAudioStartException(AmbientAudioCodes.GENERATION_OVERFLOW)
        }
        playbackGeneration += 1L
        staging = 0
        scheduled = 0
        return AmbientClearEffect(accepted = true, flushLivePlayer = playerAttached)
    }

    fun enqueue(capture: Long, evenBytes: Int): Long? {
        if (!running || capture != captureGeneration || evenBytes < 2) return null
        staging += 1
        return playbackGeneration
    }

    fun undoEnqueue() {
        if (staging > 0) staging -= 1
    }

    fun beginSlice(capture: Long, playback: Long, hasHardwareBytes: Boolean): Boolean {
        if (playback != playbackGeneration || capture != captureGeneration || !running || !playerAttached) {
            return false
        }
        if (staging > 0) staging -= 1
        if (!hasHardwareBytes) return false
        scheduled += 1
        return true
    }

    fun noteDrained(capture: Long, playback: Long): AmbientDrain {
        if (playback != playbackGeneration || capture != captureGeneration || !running || !playerAttached) {
            return AmbientDrain(matches = false, idle = false)
        }
        if (scheduled <= 0) return AmbientDrain(matches = false, idle = false)
        scheduled -= 1
        return AmbientDrain(matches = true, idle = scheduled == 0 && staging == 0)
    }

    fun decision(capture: Long, playback: Long, hasAudio: Boolean): AmbientPlaybackDecision {
        return AmbientPlaybackDecision(
            queuedCapture = capture,
            currentCapture = captureGeneration,
            queuedPlayback = playback,
            currentPlayback = playbackGeneration,
            intendedRunning = running,
            engineRunning = running,
            playerAttached = playerAttached,
            hasAudio = hasAudio,
        )
    }
}

internal class AmbientPlaybackCursor {
    data class Mark(
        val endFrame: Int,
        val captureGeneration: Long,
        val playbackGeneration: Long,
    )

    private val pending = ArrayDeque<Mark>()
    var writtenFrames: Int = 0
        private set

    fun reset() {
        pending.clear()
        writtenFrames = 0
    }

    fun addWritten(frames: Int, capture: Long, playback: Long): Int {
        writtenFrames += frames
        pending.addLast(Mark(writtenFrames, capture, playback))
        return writtenFrames
    }

    fun takeCompleted(headFrames: Int): List<Mark> {
        val done = ArrayList<Mark>()
        while (pending.isNotEmpty() && pending.first().endFrame <= headFrames) {
            done.add(pending.removeFirst())
        }
        return done
    }

    fun pendingCount(): Int = pending.size
}
