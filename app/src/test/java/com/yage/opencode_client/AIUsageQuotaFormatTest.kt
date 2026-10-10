package com.yage.opencode_client

import com.yage.opencode_client.data.model.AIUsageQuota
import com.yage.opencode_client.data.model.formatQuotaResetSuffix
import com.yage.opencode_client.data.model.quotaBadgeText
import com.yage.opencode_client.data.model.quotaResetEpochMs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AIUsageQuotaFormatTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `five point one days floors to one decimal day`() {
        val tenthDayMs = 8_640_000L
        val quota = AIUsageQuota(
            "codex",
            "7d",
            13,
            87,
            nextResetTimeMs = now + 51L * tenthDayMs
        )
        assertEquals("5.1d", formatQuotaResetSuffix(quota.nextResetTimeMs!!, now))
        assertEquals("87% / 5.1d", quotaBadgeText(quota, now))
        assertEquals("5.1d", formatQuotaResetSuffix(now + 445_824_000L, now))
    }

    @Test
    fun `twenty four hours is one day not twenty four hours`() {
        assertEquals("1.0d", formatQuotaResetSuffix(now + 86_400_000L, now))
        assertEquals("23h", formatQuotaResetSuffix(now + 86_400_000L - 1L, now))
    }

    @Test
    fun `hours floor and sub hour stays under one hour`() {
        assertEquals("13h", formatQuotaResetSuffix(now + 13L * 3_600_000L + 3_500_000L, now))
        assertEquals("1h", formatQuotaResetSuffix(now + 3_600_000L, now))
        assertEquals("<1h", formatQuotaResetSuffix(now + 3_600_000L - 1L, now))
        assertEquals("<1h", formatQuotaResetSuffix(now + 1L, now))
    }

    @Test
    fun `expired reset is zero hours`() {
        assertEquals("0h", formatQuotaResetSuffix(now, now))
        assertEquals("0h", formatQuotaResetSuffix(now - 1L, now))
    }

    @Test
    fun `missing or second-scale reset falls back to window label`() {
        val missing = AIUsageQuota("codex", "7d", 13, 87)
        assertNull(quotaResetEpochMs(missing))
        assertEquals("87% @ 7d", quotaBadgeText(missing, now))
        val seconds = missing.copy(nextResetTimeMs = 1_783_842_841L)
        assertNull(quotaResetEpochMs(seconds))
        assertEquals("87% @ 7d", quotaBadgeText(seconds, now))
    }

    @Test
    fun `iso is ignored when millis are absent`() {
        val withOffset = AIUsageQuota(
            "grok",
            "Weekly",
            18,
            82,
            nextResetIso = "2026-09-29T00:00:00Z"
        )
        val bare = withOffset.copy(nextResetIso = "2026-09-29T00:00:00")
        assertNull(quotaResetEpochMs(withOffset))
        assertNull(quotaResetEpochMs(bare))
        assertEquals("82% @ Weekly", quotaBadgeText(withOffset, now))
        assertEquals("82% @ Weekly", quotaBadgeText(bare, now))
    }

    @Test
    fun `displayed used is derived from rounded remaining so the pair sums to 100`() {
        assertComplementary(2.5, 97.5, expectedUsed = 2, expectedRemaining = 98)
        assertComplementary(49.5, 50.5, expectedUsed = 49, expectedRemaining = 51)
        assertComplementary(0.0, 100.0, expectedUsed = 0, expectedRemaining = 100)
        assertComplementary(100.0, 0.0, expectedUsed = 100, expectedRemaining = 0)

        val half = decodedQuota(2.5, 97.5)
        assertEquals(3, half.usedPercentage)
        assertEquals(98, half.remainingPercentage)
        assertEquals(100, half.clampedUsedPercentage + half.clampedRemainingPercentage)
        assertEquals("98% @ 5h", quotaBadgeText(half, now))

        val over = AIUsageQuota("codex", "5h", usedPercentage = 0, remainingPercentage = 150)
        assertEquals(100, over.clampedRemainingPercentage)
        assertEquals(0, over.clampedUsedPercentage)
        val under = AIUsageQuota("codex", "5h", usedPercentage = 200, remainingPercentage = -5)
        assertEquals(0, under.clampedRemainingPercentage)
        assertEquals(100, under.clampedUsedPercentage)
    }

    private fun assertComplementary(
        used: Double,
        remaining: Double,
        expectedUsed: Int,
        expectedRemaining: Int
    ) {
        val quota = decodedQuota(used, remaining)
        assertEquals(expectedRemaining, quota.clampedRemainingPercentage)
        assertEquals(expectedUsed, quota.clampedUsedPercentage)
        assertEquals(100, quota.clampedUsedPercentage + quota.clampedRemainingPercentage)
    }

    private fun decodedQuota(used: Double, remaining: Double): AIUsageQuota =
        json.decodeFromString(
            """{"provider":"codex","label":"5h","used_percentage":$used,"remaining_percentage":$remaining}"""
        )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
