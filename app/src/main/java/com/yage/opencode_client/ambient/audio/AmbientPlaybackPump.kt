package com.yage.opencode_client.ambient.audio

/**
 * Serializes one playback generation's flush and write against a hardware writer.
 * The engine playback thread is the only production caller. Native writes stay
 * chunked so a stop or a newer clear is observed between chunks.
 */
internal interface AmbientHardwareWriter {
    /** Discard queued hardware audio. Must not start or restart playback. */
    fun pauseAndFlush(stillOwner: () -> Boolean): Boolean

    /** Start playback for a real PCM buffer that is about to be written. */
    fun startForPcm(stillOwner: () -> Boolean): Boolean

    /**
     * Write one bounded slice. Return the byte count accepted, 0 to retry, or a
     * negative value when the device rejects the write.
     */
    fun writeChunk(pcm: ByteArray, offset: Int, length: Int, stillOwner: () -> Boolean): Int
}

internal class AmbientPlaybackPump(
    private val writer: AmbientHardwareWriter,
    private val resampler: AmbientResampler,
) {
    var appliedFlush: Int = 0
        private set

    private var playing = false

    fun acknowledgeFlush(requested: Int, stillOwner: () -> Boolean): Int {
        if (requested == appliedFlush) return appliedFlush
        if (!stillOwner()) return appliedFlush
        val performed = writer.pauseAndFlush(stillOwner)
        playing = false
        if (!performed || !stillOwner()) return appliedFlush
        resampler.reset()
        appliedFlush = requested
        return appliedFlush
    }

    fun resampleIfEligible(pcm: ByteArray, eligible: Boolean): ByteArray? {
        if (!eligible) return null
        return resampler.process(pcm)
    }

    fun resetResampler() {
        resampler.reset()
    }

    fun playAndWrite(hardware: ByteArray, stillOwner: () -> Boolean): Boolean {
        if (hardware.size < 2 || !stillOwner()) return false
        if (!playing) {
            if (!writer.startForPcm(stillOwner) || !stillOwner()) return false
            playing = true
        }
        var offset = 0
        var spins = 0
        while (offset < hardware.size) {
            if (!stillOwner()) return false
            val length = minOf(WRITE_CHUNK_BYTES, hardware.size - offset)
            val wrote = writer.writeChunk(hardware, offset, length, stillOwner)
            if (wrote < 0) return false
            if (wrote == 0) {
                spins += 1
                if (spins > WRITE_SPIN_LIMIT || !stillOwner()) return false
                try {
                    Thread.sleep(WRITE_RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    return false
                }
                continue
            }
            spins = 0
            offset += wrote
        }
        return true
    }

    private companion object {
        const val WRITE_CHUNK_BYTES = 1920
        const val WRITE_SPIN_LIMIT = 40
        const val WRITE_RETRY_MILLIS = 5L
    }
}
