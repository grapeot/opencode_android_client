package com.yage.opencode_client

import com.yage.opencode_client.data.model.AIUsageQuota
import com.yage.opencode_client.data.model.AIUsageQuotaKey
import com.yage.opencode_client.data.model.AIUsageQuotaSnapshot
import com.yage.opencode_client.data.model.ModelShortlistItem
import com.yage.opencode_client.data.model.isQuotaSnapshotStale
import com.yage.opencode_client.data.model.primaryQuotaKey
import com.yage.opencode_client.ui.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `quota snapshot is stale after ten minutes or a failed refresh`() {
        val fetched = 1_000_000L
        assertFalse(isQuotaSnapshotStale(fetched, fetched + 600_000, hasError = false))
        assertTrue(isQuotaSnapshotStale(fetched, fetched + 600_001, hasError = false))
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
}
