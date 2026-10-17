package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientMeter
import com.yage.opencode_client.ambient.protocol.AmbientPcm
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientPlaybackDecision
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientPcmTest {
    @Test
    fun `rms uses little endian signed samples`() {
        assertEquals(0.0, AmbientPcm.rms(ByteArray(0)), 0.0)
        assertEquals(0.0, AmbientPcm.rms(byteArrayOf(0x7f)), 0.0)
        assertEquals(0.0, AmbientPcm.rms(le(0, 0)), 0.0)
        assertEquals(0.5, AmbientPcm.rms(le(16_384, -16_384)), 1e-12)
        assertEquals(1.0, AmbientPcm.rms(le(-32_768)), 0.0)
        assertEquals(32_767 / 32_768.0, AmbientPcm.rms(le(32_767)), 1e-12)
        assertEquals(256 / 32_768.0, AmbientPcm.rms(byteArrayOf(0x00, 0x01)), 1e-12)
        val withOddTail = le(16_384) + 0x55.toByte()
        assertEquals(16_384 / 32_768.0, AmbientPcm.rms(withOddTail), 1e-12)
        assertEquals(1.0, AmbientPcm.rms(le(-32_768, -32_768)), 0.0)
    }

    @Test
    fun `uplink drops odd tails and rejects illegal sizes`() {
        assertEquals(0, AmbientPcm.silence(0).size)
        assertEquals(0, AmbientPcm.silence(-4).size)
        assertEquals(0, AmbientPcm.silence(1).size)
        assertArrayEquals(ByteArray(4), AmbientPcm.silence(5))

        assertNull(AmbientPcm.uplinkFrame(ByteArray(0), muted = false))
        assertNull(AmbientPcm.uplinkFrame(byteArrayOf(1), muted = false))
        assertNull(AmbientPcm.uplinkFrame(byteArrayOf(1), muted = true))

        val odd = byteArrayOf(0x00, 0x01, 0x7f)
        val framed = AmbientPcm.uplinkFrame(odd, muted = false)
        assertArrayEquals(byteArrayOf(0x00, 0x01), framed)
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x7f), odd)
        assertFalse(framed!! === odd)

        val max = AmbientProtocol.MAX_AUDIO_BYTES
        val captured = ByteArray(max)
        captured[0] = 9
        captured[captured.lastIndex] = 8
        val full = AmbientPcm.uplinkFrame(captured, muted = false)
        assertNotNull(full)
        assertEquals(max, full!!.size)
        assertEquals(9, full[0].toInt())
        assertEquals(8, full.last().toInt())
        assertFalse(full === captured)

        val oddOverMax = ByteArray(max + 1)
        oddOverMax[0] = 4
        val trimmed = AmbientPcm.uplinkFrame(oddOverMax, muted = false)
        assertNotNull(trimmed)
        assertEquals(max, trimmed!!.size)
        assertEquals(4, trimmed[0].toInt())
        assertEquals(4, oddOverMax[0].toInt())

        assertNull(AmbientPcm.uplinkFrame(ByteArray(max + 2), muted = false))
        assertNull(AmbientPcm.uplinkFrame(ByteArray(max + 2), muted = true))
    }

    @Test
    fun `muted uplink is even zeros and does not mutate input`() {
        val captured = byteArrayOf(1, 2, 3, 4, 5)
        val before = captured.copyOf()
        val muted = AmbientPcm.uplinkFrame(captured, muted = true)
        assertNotNull(muted)
        assertEquals(4, muted!!.size)
        assertTrue(muted.all { it == 0.toByte() })
        assertArrayEquals(before, captured)
        assertFalse(muted === captured)

        val even = byteArrayOf(9, 8)
        val silenced = AmbientPcm.uplinkFrame(even, muted = true)
        assertArrayEquals(byteArrayOf(0, 0), silenced)
        assertArrayEquals(byteArrayOf(9, 8), even)

        val max = AmbientProtocol.MAX_AUDIO_BYTES
        val oddOverMax = ByteArray(max + 1)
        oddOverMax[0] = 6
        oddOverMax[oddOverMax.lastIndex] = 7
        val zeros = AmbientPcm.uplinkFrame(oddOverMax, muted = true)
        assertNotNull(zeros)
        assertEquals(max, zeros!!.size)
        assertTrue(zeros.all { it == 0.toByte() })
        assertEquals(6, oddOverMax[0].toInt())
        assertEquals(7, oddOverMax.last().toInt())
    }

    @Test
    fun `meter uses 48 dp glyph and 72 dp row`() {
        assertEquals(48, AmbientMeter.GLYPH_DP)
        assertEquals(72, AmbientMeter.CONTROL_ROW_DP)
        assertEquals(48, AmbientMeter.MIN_TOUCH_DP)
        assertFalse(AmbientMeter.NAV_ICON_ANIMATES_LEVEL)
        assertEquals(0.25, AmbientMeter.FULL_SCALE_INPUT, 0.0)
    }

    @Test
    fun `meter fill is monotonic sqrt and clamps non finite`() {
        assertEquals(0.0, AmbientMeter.displayFill(0.0), 0.0)
        assertEquals(0.5, AmbientMeter.displayFill(0.0625), 1e-12)
        assertEquals(1.0, AmbientMeter.displayFill(AmbientMeter.FULL_SCALE_INPUT), 1e-12)
        assertEquals(1.0, AmbientMeter.displayFill(1.0), 0.0)
        assertEquals(1.0, AmbientMeter.sanitized(4.0), 0.0)
        assertEquals(0.0, AmbientMeter.sanitized(-0.4), 0.0)
        assertEquals(0.0, AmbientMeter.sanitized(Double.NaN), 0.0)
        assertEquals(0.0, AmbientMeter.displayFill(Double.NaN), 0.0)
        assertEquals(0.0, AmbientMeter.displayFill(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, AmbientMeter.displayFill(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(0.0, AmbientMeter.targetFill(0.0, AmbientPhase.LISTENING, muted = false), 0.0)

        val levels = doubleArrayOf(0.0, 0.0001, 0.01, 0.0625, 0.25, 0.5, 1.0, 10.0)
        var previous = -1.0
        for (level in levels) {
            val fill = AmbientMeter.displayFill(level)
            assertTrue(fill >= previous)
            assertTrue(fill in 0.0..1.0)
            previous = fill
        }
    }

    @Test
    fun `mute and non live fill drop to zero immediately`() {
        val hot = 0.0625
        assertTrue(AmbientMeter.targetFill(hot, AmbientPhase.LISTENING, muted = false) > 0.0)
        assertTrue(AmbientMeter.targetFill(hot, AmbientPhase.SPEAKING, muted = false) > 0.0)
        assertEquals(0.0, AmbientMeter.targetFill(hot, AmbientPhase.LISTENING, muted = true), 0.0)
        assertEquals(0.0, AmbientMeter.targetFill(hot, AmbientPhase.SPEAKING, muted = true), 0.0)
        for (phase in listOf(AmbientPhase.IDLE, AmbientPhase.CONNECTING, AmbientPhase.CLOSING, AmbientPhase.FAILED)) {
            assertFalse(AmbientMeter.isLive(phase, muted = false))
            assertEquals(0.0, AmbientMeter.targetFill(hot, phase, muted = false), 0.0)
            val dropped = AmbientMeter.transition(0.8, hot, phase, muted = false, reduceMotion = false)
            assertEquals(0.0, dropped.fill, 0.0)
            assertEquals(AmbientMeter.Motion.SNAP, dropped.motion)
            assertEquals(0L, dropped.durationNanos)
        }
        val muted = AmbientMeter.transition(
            from = 0.8,
            inputLevel = hot,
            phase = AmbientPhase.LISTENING,
            muted = true,
            reduceMotion = false,
        )
        assertEquals(0.0, muted.fill, 0.0)
        assertEquals(AmbientMeter.Motion.SNAP, muted.motion)
        assertEquals(0L, muted.durationNanos)
        assertTrue(AmbientMeter.showsSlash(muted = true))
        assertFalse(AmbientMeter.showsSlash(muted = false))
    }

    @Test
    fun `meter attack release and reduce motion`() {
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

        val speaking = AmbientMeter.transition(
            from = 0.0,
            inputLevel = 0.0625,
            phase = AmbientPhase.SPEAKING,
            muted = false,
            reduceMotion = false,
        )
        assertEquals(AmbientMeter.Motion.ATTACK, speaking.motion)
        assertEquals(65_000_000L, speaking.durationNanos)

        val falling = AmbientMeter.transition(
            from = 0.8,
            inputLevel = 0.01,
            phase = AmbientPhase.LISTENING,
            muted = false,
            reduceMotion = false,
        )
        assertEquals(0.2, falling.fill, 1e-12)
        assertEquals(AmbientMeter.Motion.RELEASE, falling.motion)
        assertEquals(320_000_000L, falling.durationNanos)

        val reduced = AmbientMeter.transition(
            from = 0.0,
            inputLevel = 0.0625,
            phase = AmbientPhase.LISTENING,
            muted = false,
            reduceMotion = true,
        )
        assertEquals(0.5, reduced.fill, 1e-12)
        assertEquals(AmbientMeter.Motion.SNAP, reduced.motion)
        assertEquals(0L, reduced.durationNanos)

        val unchanged = AmbientMeter.transition(
            from = AmbientMeter.displayFill(0.01),
            inputLevel = 0.01,
            phase = AmbientPhase.SPEAKING,
            muted = false,
            reduceMotion = false,
        )
        assertEquals(AmbientMeter.Motion.SNAP, unchanged.motion)
        assertEquals(0L, unchanged.durationNanos)
    }

    @Test
    fun `playback guards reject stale stopped or released and never restart from clear`() {
        val live = decision()
        assertTrue(live.ownsQueuedClear)
        assertTrue(live.shouldPlayScheduled)
        assertFalse(live.shouldRestartFromClear)

        val rejected = listOf(
            decision(intendedRunning = false),
            decision(engineRunning = false),
            decision(playerAttached = false),
            decision(queuedCapture = 8L, currentCapture = 7L),
            decision(queuedPlayback = 4L, currentPlayback = 3L),
            decision(hasAudio = false),
        )
        rejected.forEach { item ->
            assertFalse(item.shouldPlayScheduled)
            assertFalse(item.shouldRestartFromClear)
        }

        val afterClear = decision(queuedPlayback = 3L, currentPlayback = 4L, hasAudio = true)
        assertFalse(afterClear.ownsQueuedClear)
        assertFalse(afterClear.shouldPlayScheduled)
        assertFalse(afterClear.shouldRestartFromClear)

        val resumed = decision(queuedPlayback = 4L, currentPlayback = 4L, hasAudio = true)
        assertTrue(resumed.shouldPlayScheduled)
        assertFalse(resumed.shouldRestartFromClear)
    }

    private fun decision(
        queuedCapture: Long = 7L,
        currentCapture: Long = 7L,
        queuedPlayback: Long = 3L,
        currentPlayback: Long = 3L,
        intendedRunning: Boolean = true,
        engineRunning: Boolean = true,
        playerAttached: Boolean = true,
        hasAudio: Boolean = true,
    ) = AmbientPlaybackDecision(
        queuedCapture = queuedCapture,
        currentCapture = currentCapture,
        queuedPlayback = queuedPlayback,
        currentPlayback = currentPlayback,
        intendedRunning = intendedRunning,
        engineRunning = engineRunning,
        playerAttached = playerAttached,
        hasAudio = hasAudio,
    )

    private fun le(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val bits = sample.toShort().toInt()
            bytes[index * 2] = (bits and 0xff).toByte()
            bytes[index * 2 + 1] = ((bits shr 8) and 0xff).toByte()
        }
        return bytes
    }

    private companion object
}
