package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.audio.AmbientCaptureRates
import com.yage.opencode_client.ambient.audio.AmbientResampler
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientResamplerTest {
    @Test
    fun `rate selection prefers an initialized wire rate`() {
        val attempts = ArrayList<Int>()
        val choice = AmbientCaptureRates.select(
            minBufferBytes = { rate -> if (rate == 24_000 || rate == 16_000) 640 else -2 },
            initialize = { probe ->
                attempts.add(probe.hertz)
                probe.hertz == 24_000
            },
        )
        assertEquals(listOf(24_000), attempts)
        assertEquals(24_000, choice!!.hertz)
        assertTrue(choice.directWire)
        assertTrue(choice.bufferBytes >= choice.minBufferBytes)
        assertEquals(0, choice.bufferBytes % 2)
    }

    @Test
    fun `failed 24k init falls through to 16k and never invents success`() {
        val attempts = ArrayList<Int>()
        val choice = AmbientCaptureRates.select(
            minBufferBytes = { rate ->
                when (rate) {
                    24_000, 16_000, 8_000 -> 320
                    else -> -1
                }
            },
            initialize = { probe ->
                attempts.add(probe.hertz)
                probe.hertz == 16_000
            },
        )
        assertEquals(listOf(24_000, 16_000), attempts)
        assertEquals(16_000, choice!!.hertz)
        assertFalse(choice.directWire)
        assertEquals(AmbientProtocol.SAMPLE_RATE_HZ, 24_000)

        val none = AmbientCaptureRates.select(
            minBufferBytes = { -2 },
            initialize = { error("unsupported rate must not be opened") },
        )
        assertNull(none)
    }

    @Test
    fun `buffer size stays even and at least the hardware minimum`() {
        assertEquals(1920, AmbientCaptureRates.bufferBytes(100, 24_000))
        assertEquals(640, AmbientCaptureRates.bufferBytes(100, 8_000))
        assertEquals(1922, AmbientCaptureRates.bufferBytes(1921, 24_000))
        assertEquals(24_000, AmbientCaptureRates.preferredHertz.first())
        assertEquals(16_000, AmbientCaptureRates.preferredHertz[1])
        assertEquals(8_000, AmbientCaptureRates.preferredHertz[2])
    }

    @Test
    fun `same rate keeps complete samples and carries a trailing byte`() {
        val resampler = AmbientResampler(24_000, 24_000)
        assertArrayEquals(ByteArray(0), resampler.process(ByteArray(0)))
        val first = resampler.process(byteArrayOf(0x01, 0x02, 0x03))
        assertArrayEquals(le(0x0201), first)
        val second = resampler.process(byteArrayOf(0x04, 0x05, 0x06))
        assertArrayEquals(le(0x0403, 0x0605), second)
        assertArrayEquals(ByteArray(0), resampler.process(ByteArray(0)))
    }

    @Test
    fun `split chunks match one continuous 16k to 24k pass`() {
        val whole = AmbientResampler(16_000, 24_000)
        val split = AmbientResampler(16_000, 24_000)
        val pcm = le(0, 300, 600, 900)
        val expected = le(0, 200, 400, 600, 800)
        assertArrayEquals(expected, whole.process(pcm))
        val head = split.process(le(0, 300))
        val tail = split.process(le(600, 900))
        assertArrayEquals(expected, head + tail)
        assertEquals(0, (head + tail).size % 2)
        assertArrayEquals(ByteArray(0), split.process(ByteArray(0)))
    }

    @Test
    fun `dc and downsampling stay continuous across chunks`() {
        val up = AmbientResampler(8_000, 24_000)
        val upSplit = AmbientResampler(8_000, 24_000)
        val dc = le(*IntArray(8) { 1_000 })
        val once = up.process(dc)
        val parts = upSplit.process(dc.copyOfRange(0, 6)) + upSplit.process(dc.copyOfRange(6, dc.size))
        assertArrayEquals(once, parts)
        assertTrue(once.isNotEmpty())
        assertTrue(samples(once).all { it == 1_000 })

        val down = AmbientResampler(24_000, 16_000)
        val downSplit = AmbientResampler(24_000, 16_000)
        val source = le(*IntArray(12) { index -> index * 50 })
        val downOnce = down.process(source)
        val downParts = downSplit.process(source.copyOfRange(0, 10)) +
            downSplit.process(source.copyOfRange(10, source.size))
        assertArrayEquals(downOnce, downParts)
        assertEquals(0, downOnce.size % 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero rate is rejected`() {
        AmbientResampler(0, 24_000)
    }

    private fun le(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val bits = sample.toShort().toInt()
            bytes[index * 2] = (bits and 0xff).toByte()
            bytes[index * 2 + 1] = ((bits shr 8) and 0xff).toByte()
        }
        return bytes
    }

    private fun samples(pcm: ByteArray): List<Int> {
        val out = ArrayList<Int>(pcm.size / 2)
        var index = 0
        while (index + 1 < pcm.size) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt()
            out.add(((high shl 8) or low).toShort().toInt())
            index += 2
        }
        return out
    }
}
