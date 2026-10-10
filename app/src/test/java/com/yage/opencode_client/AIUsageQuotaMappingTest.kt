package com.yage.opencode_client

import com.yage.opencode_client.data.model.AIUsageQuota
import com.yage.opencode_client.data.model.AIUsageQuotaKey
import com.yage.opencode_client.data.model.AIUsageQuotaSnapshot
import com.yage.opencode_client.data.model.ModelShortlistItem
import com.yage.opencode_client.data.model.QUOTA_STALE_AFTER_MS
import com.yage.opencode_client.data.model.isQuotaSnapshotStale
import com.yage.opencode_client.data.model.isQuotaStale
import com.yage.opencode_client.data.model.primaryQuotaKey
import com.yage.opencode_client.data.model.resolveQuota
import com.yage.opencode_client.ui.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AIUsageQuotaMappingTest {
    @Test
    fun `maps supported models to quota windows`() {
        assertEquals(AIUsageQuotaKey("codex", "5h"), primaryQuotaKey("openai"))
        assertEquals(AIUsageQuotaKey("glm", "5h"), primaryQuotaKey("zai-coding-plan"))
        assertEquals(AIUsageQuotaKey("ollama", "5h"), primaryQuotaKey("ollama-cloud"))
        assertEquals(AIUsageQuotaKey("grok", "Weekly"), primaryQuotaKey("xai"))
        assertNull(primaryQuotaKey("google"))
    }

    @Test
    fun `selected grok model resolves weekly quota`() {
        val quota = AIUsageQuota(
            provider = "grok",
            label = "Weekly",
            usedPercentage = 18,
            remainingPercentage = 82
        )
        val state = AppState(
            selectedModelIndex = 0,
            modelShortlist = listOf(
                ModelShortlistItem("xai", "grok-4.7", "Grok 4.7", "Grok")
            ),
            aiUsageQuotaSnapshot = AIUsageQuotaSnapshot(
                generatedAt = "2026-09-26T09:00:00",
                fetchedAtMs = 0,
                quotas = listOf(quota)
            )
        )

        assertEquals(quota, state.selectedAIUsageQuota)
    }

    @Test
    fun `missing preferred window falls back to the provider window that exists`() {
        val weekly = AIUsageQuota("grok", "Weekly", 23, 77)
        val sevenDay = AIUsageQuota("codex", "7d", 0, 100)
        val quotas = listOf(weekly, sevenDay)

        assertEquals(weekly, resolveQuota(quotas, AIUsageQuotaKey("grok", "Weekly")))
        assertEquals(sevenDay, resolveQuota(quotas, AIUsageQuotaKey("codex", "5h")))
        assertNull(resolveQuota(quotas, AIUsageQuotaKey("glm", "5h")))
    }

    @Test
    fun `quota snapshot is stale after one hour or a failed refresh`() {
        val fetched = 1_000_000L
        assertFalse(isQuotaSnapshotStale(fetched, fetched + 3_600_000, hasError = false))
        assertTrue(isQuotaSnapshotStale(fetched, fetched + 3_600_001, hasError = false))
        assertTrue(isQuotaSnapshotStale(fetched, fetched + 1, hasError = true))
    }

    @Test
    fun `selected model quota is stale when the snapshot failed`() {
        val state = AppState(
            aiUsageError = "refresh failed",
            aiUsageQuotaSnapshot = AIUsageQuotaSnapshot(
                generatedAt = null,
                fetchedAtMs = System.currentTimeMillis(),
                quotas = emptyList()
            )
        )

        assertTrue(state.isSelectedModelQuotaStale)
    }

    @Test
    fun `offset observed_at parses to epoch millis and ignores the offset display`() {
        val pacific = quota(observedAtIso = OBSERVED_ISO)
        val utc = quota(observedAtIso = "2026-10-10T15:49:11.527738Z")
        assertEquals(OBSERVED_EPOCH_MS, pacific.observedAtMs)
        assertEquals(OBSERVED_EPOCH_MS, utc.observedAtMs)
        assertNull(quota(observedAtIso = "not-a-timestamp").observedAtMs)
        assertNull(quota(observedAtIso = "2026-10-10T08:49:11").observedAtMs)
    }

    @Test
    fun `fresh fetch of a two hour old observation is stale`() {
        val now = OBSERVED_EPOCH_MS + TWO_HOURS_MS
        assertTrue(isQuotaStale(quota(observedAtIso = OBSERVED_ISO), snapshot(now), now, hasError = false))
    }

    @Test
    fun `fresh observation is not stale even when the fetch timestamp is old`() {
        val now = OBSERVED_EPOCH_MS + 60_000L
        val fetchedTwoHoursAgo = now - TWO_HOURS_MS
        assertFalse(
            isQuotaStale(
                quota(observedAtIso = OBSERVED_ISO),
                snapshot(fetchedTwoHoursAgo, generatedAt = "1999-01-01T00:00:00"),
                now,
                hasError = false
            )
        )
    }

    @Test
    fun `missing observed_at falls back to fetchedAtMs`() {
        val now = OBSERVED_EPOCH_MS
        assertFalse(isQuotaStale(quota(), snapshot(now), now, hasError = false))
        assertTrue(isQuotaStale(quota(), snapshot(now - TWO_HOURS_MS), now, hasError = false))
        assertFalse(isQuotaStale(quota(), snapshot(now - QUOTA_STALE_AFTER_MS), now, hasError = false))
        assertTrue(isQuotaStale(quota(), snapshot(now - QUOTA_STALE_AFTER_MS - 1), now, hasError = false))
        assertTrue(isQuotaStale(quota(observedAtIso = OBSERVED_ISO), snapshot(now), now, hasError = true))
    }

    @Test
    fun `unparseable observed_at falls back to fetchedAtMs`() {
        val now = OBSERVED_EPOCH_MS
        val broken = quota(observedAtIso = "not-a-timestamp")
        assertFalse(isQuotaStale(broken, snapshot(now), now, hasError = false))
        assertTrue(isQuotaStale(broken, snapshot(now - TWO_HOURS_MS), now, hasError = false))
    }

    @Test
    fun `measurement source does not override observation age`() {
        val now = OBSERVED_EPOCH_MS + TWO_HOURS_MS
        val freshNow = OBSERVED_EPOCH_MS + 60_000L
        assertTrue(
            isQuotaStale(
                quota(observedAtIso = OBSERVED_ISO, measurementSource = "cache"),
                snapshot(now),
                now,
                hasError = false
            )
        )
        assertFalse(
            isQuotaStale(
                quota(observedAtIso = OBSERVED_ISO, measurementSource = "live"),
                snapshot(freshNow - TWO_HOURS_MS),
                freshNow,
                hasError = false
            )
        )
        assertFalse(
            isQuotaStale(
                quota(observedAtIso = OBSERVED_ISO, measurementSource = "cache"),
                snapshot(freshNow - TWO_HOURS_MS),
                freshNow,
                hasError = false
            )
        )
    }

    @Test
    fun `selected model quota stale follows observation time not fetch time`() {
        val now = System.currentTimeMillis()
        val oldObservation = offsetIso(now - TWO_HOURS_MS)
        val freshObservation = offsetIso(now - 1_000L)
        val selected = AppState(
            selectedModelIndex = 0,
            modelShortlist = listOf(ModelShortlistItem("openai", "gpt-5", "GPT", "GPT")),
            aiUsageQuotaSnapshot = AIUsageQuotaSnapshot(
                generatedAt = "2099-01-01T00:00:00",
                fetchedAtMs = now,
                quotas = listOf(quota(observedAtIso = oldObservation, measurementSource = "cache"))
            )
        )
        val freshObservationOldFetch = selected.copy(
            aiUsageQuotaSnapshot = AIUsageQuotaSnapshot(
                generatedAt = "1999-01-01T00:00:00",
                fetchedAtMs = now - TWO_HOURS_MS,
                quotas = listOf(quota(observedAtIso = freshObservation, measurementSource = "live"))
            )
        )

        assertTrue(selected.isSelectedModelQuotaStale)
        assertFalse(freshObservationOldFetch.isSelectedModelQuotaStale)
    }

    @Test
    fun `no selected quota falls back to snapshot freshness`() {
        val now = System.currentTimeMillis()
        val staleFetch = AppState(
            aiUsageQuotaSnapshot = AIUsageQuotaSnapshot(null, now - TWO_HOURS_MS, emptyList())
        )
        val freshFetch = AppState(
            aiUsageQuotaSnapshot = AIUsageQuotaSnapshot(null, now, emptyList())
        )

        assertTrue(staleFetch.isSelectedModelQuotaStale)
        assertFalse(freshFetch.isSelectedModelQuotaStale)
    }

    private fun quota(
        observedAtIso: String? = null,
        measurementSource: String? = null
    ) = AIUsageQuota(
        provider = "codex",
        label = "5h",
        usedPercentage = 29,
        remainingPercentage = 71,
        observedAtIso = observedAtIso,
        measurementSource = measurementSource
    )

    private fun snapshot(
        fetchedAtMs: Long,
        generatedAt: String? = "2026-10-10T15:49:12",
        quotas: List<AIUsageQuota> = listOf(quota())
    ) = AIUsageQuotaSnapshot(generatedAt, fetchedAtMs, quotas)

    private fun offsetIso(epochMs: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.ofHours(-7)).toString()

    private companion object {
        const val OBSERVED_ISO = "2026-10-10T08:49:11.527738-07:00"
        const val OBSERVED_EPOCH_MS = 1_791_647_351_527L
        const val TWO_HOURS_MS = 2 * 3_600_000L
    }
}
