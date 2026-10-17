package com.yage.opencode_client.ambient.audio

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Streaming PCM16LE resampler. The fractional read position and the last input
 * sample survive across chunks. An empty input emits nothing.
 */
internal class AmbientResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    private var produced = 0L
    private var nextIndex = 0L
    private var held = 0
    private var heldIndex = Long.MIN_VALUE
    private var oddLow = -1

    init {
        require(inputRate > 0 && outputRate > 0)
    }

    fun reset() {
        produced = 0L
        nextIndex = 0L
        held = 0
        heldIndex = Long.MIN_VALUE
        oddLow = -1
    }

    fun process(pcm: ByteArray): ByteArray {
        val samples = decode(pcm)
        if (samples.isEmpty()) return ByteArray(0)
        if (inputRate == outputRate) return encode(samples)
        return encode(resample(samples))
    }

    private fun resample(samples: IntArray): IntArray {
        val start = nextIndex
        val out = ArrayList<Int>()
        val limit = samples.size.toLong() * outputRate / inputRate + 4L
        var guard = 0L
        while (guard < limit) {
            guard += 1
            val position = produced.toDouble() * inputRate.toDouble() / outputRate.toDouble()
            val leftIndex = floor(position).toLong()
            val left = at(leftIndex, samples, start) ?: break
            val right = at(leftIndex + 1L, samples, start) ?: break
            val fraction = position - leftIndex.toDouble()
            out.add(mix(left, right, fraction))
            produced += 1
        }
        held = samples.last()
        heldIndex = start + samples.lastIndex
        nextIndex = start + samples.size
        return out.toIntArray()
    }

    private fun at(index: Long, samples: IntArray, start: Long): Int? {
        if (index == heldIndex) return held
        val local = index - start
        if (local < 0L || local >= samples.size) return null
        return samples[local.toInt()]
    }

    private fun decode(pcm: ByteArray): IntArray {
        if (pcm.isEmpty()) return IntArray(0)
        val values = ArrayList<Int>(pcm.size / 2 + 1)
        var index = 0
        if (oddLow >= 0) {
            values.add(sample16(oddLow, pcm[0]))
            oddLow = -1
            index = 1
        }
        while (index + 1 < pcm.size) {
            values.add(sample16(pcm[index].toInt() and 0xff, pcm[index + 1]))
            index += 2
        }
        if (index < pcm.size) oddLow = pcm[index].toInt() and 0xff
        return values.toIntArray()
    }

    private fun sample16(low: Int, high: Byte): Int {
        val bits = (high.toInt() shl 8) or (low and 0xff)
        return bits.toShort().toInt()
    }

    private fun encode(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val bits = samples[index].toShort().toInt()
            out[index * 2] = (bits and 0xff).toByte()
            out[index * 2 + 1] = ((bits shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun mix(left: Int, right: Int, fraction: Double): Int {
        val value = left.toDouble() + (right - left).toDouble() * fraction
        return value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
}
