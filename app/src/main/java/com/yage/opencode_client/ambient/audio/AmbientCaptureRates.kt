package com.yage.opencode_client.ambient.audio

import com.yage.opencode_client.ambient.protocol.AmbientProtocol

/**
 * Picks a hardware rate the device can actually initialize.
 * Wire audio stays [AmbientProtocol.SAMPLE_RATE_HZ]. A null result is unsupported,
 * not a silent 24 kHz success.
 */
internal object AmbientCaptureRates {
    val preferredHertz: IntArray = intArrayOf(
        24_000,
        16_000,
        8_000,
        48_000,
        32_000,
        12_000,
        44_100,
        22_050,
    )

    data class Choice(
        val hertz: Int,
        val minBufferBytes: Int,
        val bufferBytes: Int,
        val directWire: Boolean,
    )

    fun bufferBytes(minBufferBytes: Int, hertz: Int): Int {
        val fortyMs = (hertz * 2 / 25).coerceAtLeast(2)
        var size = maxOf(minBufferBytes, fortyMs)
        if (size % 2 != 0) size += 1
        return size
    }

    fun select(
        minBufferBytes: (Int) -> Int,
        initialize: (Choice) -> Boolean,
    ): Choice? {
        for (hertz in preferredHertz) {
            val minBuffer = minBufferBytes(hertz)
            if (minBuffer <= 0) continue
            val choice = Choice(
                hertz = hertz,
                minBufferBytes = minBuffer,
                bufferBytes = bufferBytes(minBuffer, hertz),
                directWire = hertz == AmbientProtocol.SAMPLE_RATE_HZ,
            )
            if (initialize(choice)) return choice
        }
        return null
    }
}
