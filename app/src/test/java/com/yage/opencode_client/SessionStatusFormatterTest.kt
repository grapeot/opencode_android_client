package com.yage.opencode_client

import com.yage.opencode_client.ui.chat.compactTokenCount
import org.junit.Assert.assertEquals
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
    fun `values of ten or more in a unit use one decimal`() {
        assertEquals("10K", compactTokenCount(10_000))
        assertEquals("10M", compactTokenCount(10_000_000))
        assertEquals("12.3B", compactTokenCount(12_300_000_000))
    }
}
