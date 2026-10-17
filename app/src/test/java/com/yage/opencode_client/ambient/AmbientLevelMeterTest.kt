package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.audio.AMBIENT_UPLINK_FRAME_BYTES
import com.yage.opencode_client.ambient.audio.AmbientFrameAssembler
import com.yage.opencode_client.ambient.audio.AmbientLevelTap
import com.yage.opencode_client.ambient.protocol.AmbientLevelGate
import com.yage.opencode_client.ambient.protocol.AmbientMeter
import com.yage.opencode_client.ambient.protocol.AmbientPcm
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientLevelMeterTest {
    @Test
    fun `frames are complete 20 ms blocks and a short tail is not sent`() {
        val assembler = AmbientFrameAssembler()
        assertTrue(assembler.push(ByteArray(0)).isEmpty())
        assertEquals(0, assembler.push(ByteArray(500)).size)
        assertEquals(500, assembler.buffered())
        val frames = assembler.push(ByteArray(500))
        assertEquals(1, frames.size)
        assertEquals(AMBIENT_UPLINK_FRAME_BYTES, frames[0].size)
        assertEquals(40, assembler.buffered())
        assertTrue(assembler.buffered() < AMBIENT_UPLINK_FRAME_BYTES)
        assertTrue(AMBIENT_UPLINK_FRAME_BYTES % 2 == 0)
        assertTrue(AMBIENT_UPLINK_FRAME_BYTES <= AmbientProtocol.MAX_AUDIO_BYTES)
        assembler.clear()
        assertEquals(0, assembler.buffered())
    }

    @Test
    fun `muted uplink is same length silence while the level uses the raw frame`() {
        val raw = le(*IntArray(AMBIENT_UPLINK_FRAME_BYTES / 2) { 16_384 })
        val send = AmbientPcm.uplinkFrame(raw, muted = true)
        assertNotNull(send)
        assertEquals(raw.size, send!!.size)
        assertTrue(send.all { it == 0.toByte() })
        assertEquals(0x40, raw[1].toInt())

        val tap = AmbientLevelTap(AmbientLevelGate(100_000_000L))
        val level = tap.noteInput(raw, nowNanos = 0L, generation = 7L)
        assertNotNull(level)
        assertEquals(7L, level!!.generation)
        assertEquals(0.5, level.inputRms, 1e-12)
        assertEquals(0.0, level.outputRms, 0.0)
        assertTrue(level.inputRms > AmbientPcm.rms(send))
    }

    @Test
    fun `level events are throttled and do not keep the pcm`() {
        val tap = AmbientLevelTap(AmbientLevelGate(100_000_000L))
        val loud = le(16_384)
        val quiet = le(0)
        val first = tap.noteInput(loud, 1_000L, 3L)
        assertNotNull(first)
        assertNull(tap.noteOutput(le(16_384, -16_384), 1_000L + 50_000_000L, 3L))
        assertEquals(0.5, tap.outputRms, 1e-12)
        val next = tap.noteInput(quiet, 1_000L + 100_000_000L, 3L)
        assertNotNull(next)
        assertEquals(3L, next!!.generation)
        assertEquals(0.0, next.inputRms, 0.0)
        assertEquals(0.5, next.outputRms, 1e-12)
        assertEquals(0.0, tap.inputRms, 0.0)
    }

    @Test
    fun `display fill uses 65 ms attack 320 ms release and mute snaps to zero`() {
        assertEquals(65_000_000L, AmbientMeter.ATTACK_NANOS)
        assertEquals(320_000_000L, AmbientMeter.RELEASE_NANOS)
        val rising = AmbientMeter.transition(
            from = 0.0,
            inputLevel = 0.0625,
            phase = AmbientPhase.LISTENING,
            muted = false,
            reduceMotion = false,
        )
        assertEquals(0.5, rising.fill, 1e-12)
        assertEquals(AmbientMeter.Motion.ATTACK, rising.motion)
        assertEquals(65_000_000L, rising.durationNanos)
        val falling = AmbientMeter.transition(
            from = 0.8,
            inputLevel = 0.01,
            phase = AmbientPhase.SPEAKING,
            muted = false,
            reduceMotion = false,
        )
        assertEquals(0.2, falling.fill, 1e-12)
        assertEquals(AmbientMeter.Motion.RELEASE, falling.motion)
        assertEquals(320_000_000L, falling.durationNanos)
        val muted = AmbientMeter.transition(
            from = 0.8,
            inputLevel = 0.0625,
            phase = AmbientPhase.LISTENING,
            muted = true,
            reduceMotion = false,
        )
        assertEquals(0.0, muted.fill, 0.0)
        assertEquals(AmbientMeter.Motion.SNAP, muted.motion)
        assertEquals(0.0, AmbientMeter.targetFill(0.0625, AmbientPhase.LISTENING, muted = true), 0.0)
    }

    @Test
    fun `output rms falls to zero from the last real input`() {
        val tap = AmbientLevelTap(AmbientLevelGate(100_000_000L))
        val raw = le(16_384, 16_384, 16_384, 16_384)
        val input = tap.noteInput(raw, 0L, 4L)
        assertEquals(0.5, input!!.inputRms, 1e-12)
        val played = tap.noteOutput(raw, 100_000_000L, 4L)
        assertEquals(0.5, played!!.outputRms, 1e-12)
        val silent = tap.markOutputSilent(200_000_000L, 4L)
        assertNotNull(silent)
        assertEquals(4L, silent!!.generation)
        assertEquals(0.5, silent.inputRms, 1e-12)
        assertEquals(0.0, silent.outputRms, 0.0)
        assertNull(tap.markOutputSilent(200_000_000L + 1L, 4L))
        assertEquals(0.0, tap.outputRms, 0.0)
        assertEquals(0.5, tap.inputRms, 1e-12)
    }

    @Test
    fun `odd input byte is not its own uplink frame`() {
        val assembler = AmbientFrameAssembler(frameBytes = 4)
        val frames = assembler.push(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frames[0])
        assertEquals(1, assembler.buffered())
        val rest = assembler.push(byteArrayOf(6, 7, 8))
        assertEquals(1, rest.size)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), rest[0])
        assertEquals(0, assembler.buffered())
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
}
