package com.yage.opencode_client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AIUsageQuotasResponse(
    @SerialName("generated_at") val generatedAt: String? = null,
    val quotas: List<AIUsageQuota> = emptyList()
)

@Serializable
data class AIUsageQuota(
    val provider: String,
    val label: String,
    @SerialName("used_percentage") val usedPercentage: Int,
    @SerialName("remaining_percentage") val remainingPercentage: Int,
    @SerialName("next_reset_time_ms") val nextResetTimeMs: Long? = null,
    @SerialName("next_reset_iso") val nextResetIso: String? = null,
    val usage: Long? = null,
    val remaining: Long? = null
) {
    val clampedUsedPercentage: Int get() = usedPercentage.coerceIn(0, 100)
    val clampedRemainingPercentage: Int get() = remainingPercentage.coerceIn(0, 100)
}

data class AIUsageQuotaSnapshot(
    val generatedAt: String?,
    val fetchedAtMs: Long,
    val quotas: List<AIUsageQuota>
)
