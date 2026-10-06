package com.yage.opencode_client

import com.yage.opencode_client.ui.chat.compactTokenCount
import com.yage.opencode_client.ui.chat.formatTurnStopwatch
import com.yage.opencode_client.ui.chat.turnStopwatchEnd
import com.yage.opencode_client.ui.chat.turnStopwatchFrozenEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatusFormatterTest {

    @Test
    fun `values below one thousand render as-is`() {
        assertEquals("0", compactTokenCount(0))
        assertEquals("950", compactTokenCount(950))
        assertEquals("999", compactTokenCount(999))
    }

    @Test
    fun `values below ten in a unit use two decimals with trailing zeros trimmed`() {
        assertEquals("1K", compactTokenCount(1000))
        assertEquals("1.01K", compactTokenCount(1010))
        assertEquals("9.54K", compactTokenCount(9543))
        // Rounding can carry into the next unit: 9.999 -> "10.00" -> 10K.
        assertEquals("10K", compactTokenCount(9999))
        assertEquals("85.2K", compactTokenCount(85200))
        assertEquals("1.11M", compactTokenCount(1_110_000))
        assertEquals("9.99M", compactTokenCount(9_994_999))
        assertEquals("2.1B", compactTokenCount(2_100_000_000))
        assertEquals("1.23T", compactTokenCount(1_234_567_890_123))
    }

    @Test
    fun `rounding that overflows a unit carries up`() {
        assertEquals("1M", compactTokenCount(999_999))
        assertEquals("1B", compactTokenCount(999_999_999))
        assertEquals("1T", compactTokenCount(999_999_999_999))
    }

    @Test
    fun `values of ten or more in a unit use one decimal`() {
        assertEquals("10K", compactTokenCount(10_000))
        assertEquals("10M", compactTokenCount(10_000_000))
        assertEquals("12.3B", compactTokenCount(12_300_000_000))
    }

    @Test
    fun `stopwatch below an hour is zero-padded minutes seconds`() {
        assertEquals("00:00", formatTurnStopwatch(0))
        assertEquals("00:07", formatTurnStopwatch(7_000))
        assertEquals("03:41", formatTurnStopwatch(221_000))
        assertEquals("59:59", formatTurnStopwatch(3_599_000))
    }

    @Test
    fun `stopwatch at or above an hour adds zero-padded hours`() {
        assertEquals("01:00:00", formatTurnStopwatch(3_600_000))
        assertEquals("02:03:41", formatTurnStopwatch(7_421_000))
        assertEquals("49:23:10", formatTurnStopwatch(177_790_000))
    }

    @Test
    fun `stopwatch clamps negative skew to zero`() {
        assertEquals("00:00", formatTurnStopwatch(-1))
        assertEquals("00:00", formatTurnStopwatch(-9_999))
    }

    @Test
    fun `stopwatch end follows now while running and freezes when stopped`() {
        // Running: no frozen end, so the reading follows `now`.
        assertEquals(221_000L, turnStopwatchEnd(null, 221_000L))
        assertEquals("03:41", formatTurnStopwatch(turnStopwatchEnd(null, 221_000L)))
        // Stopped: a frozen end pins the reading regardless of later `now`.
        assertEquals(221_000L, turnStopwatchEnd(221_000L, 4_000_000L))
        assertEquals("03:41", formatTurnStopwatch(turnStopwatchEnd(221_000L, 4_000_000L)))
    }

    @Test
    fun `frozen end prefers completion then running then holds at start`() {
        // Completed turn: freeze at the completion instant.
        assertEquals(500L, turnStopwatchFrozenEnd(500L, isRunning = false, lastUserCreatedMillis = 100L))
        // Running turn: null so the reading follows `now`.
        assertNull(turnStopwatchFrozenEnd(null, isRunning = true, lastUserCreatedMillis = 100L))
        // Idle but no completion (aborted before output): hold at the start.
        assertEquals(100L, turnStopwatchFrozenEnd(null, isRunning = false, lastUserCreatedMillis = 100L))
        // No user message at all: nothing to show.
        assertNull(turnStopwatchFrozenEnd(null, isRunning = false, lastUserCreatedMillis = null))
    }
}
