package com.yage.opencode_client.ambient.ui

import com.yage.opencode_client.ambient.protocol.AmbientBackendUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientDetailsFormatTest {
    @Test
    fun durationsUseUnwrappedClockAndRejectInvalidVoice() {
        assertEquals("00:00:00", elapsedText(0L, UNCONFIRMED))
        assertEquals("00:00:03", voiceDurationText(3.999, true, false, UNCONFIRMED))
        assertEquals("00:01:00", voiceDurationText(60.0, true, false, UNCONFIRMED))
        assertEquals("23:59:59", elapsedText(86_399L, UNCONFIRMED))
        assertEquals("24:00:00", elapsedText(86_400L, UNCONFIRMED))
        assertEquals("25:01:01", elapsedText(90_061L, UNCONFIRMED))
        assertEquals("100:00:00", elapsedText(360_000L, UNCONFIRMED))
        assertEquals("2562047788015215:30:07", elapsedText(Long.MAX_VALUE, UNCONFIRMED))
        assertEquals("00:00:00", voiceDurationText(0.0, true, false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, elapsedText(-1L, UNCONFIRMED))
        assertEquals(UNCONFIRMED, elapsedText(Long.MIN_VALUE, UNCONFIRMED))
        assertEquals(UNCONFIRMED, voiceDurationText(12.0, hasSnapshot = false, unconfirmedFlag = false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, voiceDurationText(12.0, hasSnapshot = true, unconfirmedFlag = true, UNCONFIRMED))
        assertEquals(UNCONFIRMED, voiceDurationText(Double.NaN, true, false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, voiceDurationText(Double.POSITIVE_INFINITY, true, false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, voiceDurationText(Double.NEGATIVE_INFINITY, true, false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, voiceDurationText(-0.01, true, false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, voiceDurationText(Double.MAX_VALUE, true, false, UNCONFIRMED))
        assertFalse(elapsedText(-5L, UNCONFIRMED) == "00:00:00")
    }

    @Test
    fun tokensUseDecimalUnitsAndKeepExactLongs() {
        assertEquals("0", formatTokenCount(0L))
        assertEquals("999", formatTokenCount(999L))
        assertEquals("1K", formatTokenCount(1_000L))
        assertEquals("1.2K", formatTokenCount(1_200L))
        assertEquals("1.5K", formatTokenCount(1_499L))
        assertEquals("1.99K", formatTokenCount(1_994L))
        assertEquals("2K", formatTokenCount(1_995L))
        assertEquals("999.5K", formatTokenCount(999_500L))
        assertEquals("1M", formatTokenCount(999_999L))
        assertEquals("1M", formatTokenCount(1_000_000L))
        assertEquals("1.23M", formatTokenCount(1_234_567L))
        assertEquals("12.35M", formatTokenCount(12_350_000L))
        assertEquals("1B", formatTokenCount(999_996_000L))
        assertEquals("1.23B", formatTokenCount(1_234_567_890L))
        assertEquals("1000B", formatTokenCount(999_995_000_000L))
        assertEquals("-1", formatTokenCount(-1L))
        assertFalse(formatTokenCount(-1L) == "0")

        val exact = 9_007_199_254_740_993L
        assertEquals("9007199254740993", tokenExactText(exact))
        assertEquals("9007199.25B", formatTokenCount(exact))
        assertEquals(exact.toString(), tokenExactText(exact))
    }

    @Test
    fun costsKeepZeroTinyPartialAndUnconfirmedDistinct() {
        assertEquals(usd("0.00"), formatUsdAmount(0.0))
        assertEquals(usd("0.00"), formatUsdAmount(-0.0))
        assertEquals(usd("0.01", tiny = true), formatUsdAmount(0.004))
        assertEquals(usd("0.01", tiny = true), formatUsdAmount(0.009))
        assertEquals(usd("0.01"), formatUsdAmount(0.01))
        assertEquals(usd("0.01"), formatUsdAmount(0.014))
        assertEquals(usd("0.02"), formatUsdAmount(0.015))
        assertEquals(usd("0.13"), formatUsdAmount(0.125))
        assertEquals(usd("0.14"), formatUsdAmount(0.135))
        assertEquals(usd("1.20"), formatUsdAmount(1.2))
        assertEquals(null, formatUsdAmount(Double.NaN))
        assertEquals(null, formatUsdAmount(Double.POSITIVE_INFINITY))
        assertEquals(null, formatUsdAmount(-1.0))

        assertEquals(usd("0.00"), localEstimateText(AmbientBackendUsage(), false, UNCONFIRMED))
        assertEquals(
            partial(usd("0.00")),
            localEstimateText(AmbientBackendUsage(unpricedCount = 1), false, UNCONFIRMED),
        )
        assertEquals(
            partial(usd("0.00")),
            localEstimateText(AmbientBackendUsage(inputTokens = 10), true, UNCONFIRMED),
        )
        assertEquals(
            partial(usd("0.01", tiny = true)),
            localEstimateText(AmbientBackendUsage(estimatedUsd = 0.004, unpricedCount = 2), false, UNCONFIRMED),
        )
        assertEquals(
            partial(usd("1.50")),
            localEstimateText(AmbientBackendUsage(estimatedUsd = 1.5, unpricedCount = 1), false, UNCONFIRMED),
        )
        assertEquals(UNCONFIRMED, localEstimateText(AmbientBackendUsage(estimatedUsd = Double.NaN), false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, localEstimateText(AmbientBackendUsage(estimatedUsd = -2.0), false, UNCONFIRMED))

        assertEquals(UNCONFIRMED, portalValueText(null, settled = true, usageUnconfirmed = false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, portalValueText(-0.5, settled = true, usageUnconfirmed = false, UNCONFIRMED))
        assertEquals(UNCONFIRMED, portalValueText(Double.NaN, settled = true, usageUnconfirmed = false, UNCONFIRMED))
        assertEquals(usd("0.00"), portalValueText(0.0, settled = true, usageUnconfirmed = false, UNCONFIRMED))
        assertEquals(
            partial(usd("0.00")),
            portalValueText(0.0, settled = false, usageUnconfirmed = false, UNCONFIRMED),
        )
        assertEquals(
            partial(usd("0.01", tiny = true)),
            portalValueText(0.004, settled = true, usageUnconfirmed = true, UNCONFIRMED),
        )
        assertEquals(usd("2.50"), portalValueText(2.5, settled = true, usageUnconfirmed = false, UNCONFIRMED))

        val local = localEstimateText(AmbientBackendUsage(estimatedUsd = 1.0), false, UNCONFIRMED)
        val portal = portalValueText(2.0, settled = true, usageUnconfirmed = false, UNCONFIRMED)
        assertEquals(usd("1.00"), local)
        assertEquals(usd("2.00"), portal)
        assertFalse(local.contains("3"))
        assertFalse(portal.contains("3"))
    }

    @Test
    fun columnCountUsesDetailsWidthAndLargeFont() {
        assertEquals(1, detailColumnCount(160f, 1f))
        assertEquals(1, detailColumnCount(219f, 1f))
        assertEquals(2, detailColumnCount(220f, 1f))
        assertEquals(2, detailColumnCount(400f, 1.15f))
        assertEquals(1, detailColumnCount(400f, 1.3f))
        assertEquals(1, detailColumnCount(960f, 2f))
        assertEquals(3, detailColumnCount(700f, 1f))
        assertEquals(4, detailColumnCount(900f, 1f))
        assertEquals(1, detailColumnCount(Float.NaN, 1f))
        assertEquals(1, detailColumnCount(400f, Float.NaN))
        assertTrue(detailColumnCount(400f, 1f) in 2..4)
    }

    private fun usd(amount: String, tiny: Boolean = false): String =
        (if (tiny) "<" else "") + '$' + amount

    private fun partial(amount: String): String = amount + " · " + UNCONFIRMED

    private companion object {
        const val UNCONFIRMED = "Unconfirmed"
    }
}
