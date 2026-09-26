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

data class AIUsageQuotaKey(val provider: String, val label: String)

fun primaryQuotaKey(providerId: String?): AIUsageQuotaKey? = when (providerId) {
    "openai" -> AIUsageQuotaKey("codex", "5h")
    "zai-coding-plan" -> AIUsageQuotaKey("glm", "5h")
    "ollama-cloud" -> AIUsageQuotaKey("ollama", "5h")
    "xai" -> AIUsageQuotaKey("grok", "Weekly")
    else -> null
}

const val QUOTA_STALE_AFTER_MS = 3_600_000L

private val quotaLabelPreference = listOf("5h", "7d", "Weekly")

fun resolveQuota(quotas: List<AIUsageQuota>, key: AIUsageQuotaKey): AIUsageQuota? {
    val matches = quotas.filter { it.provider.equals(key.provider, ignoreCase = true) }
    matches.firstOrNull { it.label.equals(key.label, ignoreCase = true) }?.let { return it }
    for (label in quotaLabelPreference) {
        matches.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { return it }
    }
    return matches.minByOrNull { it.label }
}

fun isQuotaSnapshotStale(fetchedAtMs: Long, nowMs: Long, hasError: Boolean): Boolean {
    if (hasError) return true
    return nowMs - fetchedAtMs > QUOTA_STALE_AFTER_MS
}
