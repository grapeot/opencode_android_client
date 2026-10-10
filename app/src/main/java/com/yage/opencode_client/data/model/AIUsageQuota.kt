package com.yage.opencode_client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.roundToInt

@Serializable
data class AIUsageQuotasResponse(
    @SerialName("generated_at") val generatedAt: String? = null,
    val quotas: List<AIUsageQuota> = emptyList()
)

@Serializable
data class AIUsageQuota(
    val provider: String,
    val label: String,
    @SerialName("used_percentage")
    @Serializable(with = RoundedPercentageSerializer::class)
    val usedPercentage: Int,
    @SerialName("remaining_percentage")
    @Serializable(with = RoundedPercentageSerializer::class)
    val remainingPercentage: Int,
    @SerialName("next_reset_time_ms") val nextResetTimeMs: Long? = null,
    @SerialName("next_reset_iso") val nextResetIso: String? = null,
    val usage: Long? = null,
    val remaining: Long? = null
) {
    val clampedRemainingPercentage: Int get() = remainingPercentage.coerceIn(0, 100)
    val clampedUsedPercentage: Int get() = 100 - clampedRemainingPercentage
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

fun quotaResetEpochMs(quota: AIUsageQuota): Long? {
    val ms = quota.nextResetTimeMs ?: return null
    if (ms < 1_000_000_000_000L) return null
    return ms
}

fun formatQuotaResetSuffix(resetMs: Long, nowMs: Long): String {
    val remainingMs = resetMs - nowMs
    if (remainingMs <= 0L) return "0h"
    if (remainingMs < 3_600_000L) return "<1h"
    if (remainingMs < 86_400_000L) return "${remainingMs / 3_600_000L}h"
    val tenths = remainingMs / 8_640_000L
    return "${tenths / 10}.${tenths % 10}d"
}

fun quotaBadgeText(quota: AIUsageQuota, nowMs: Long): String {
    val pct = quota.clampedRemainingPercentage
    val resetMs = quotaResetEpochMs(quota) ?: return "$pct% @ ${quota.label}"
    return "$pct% / ${formatQuotaResetSuffix(resetMs, nowMs)}"
}

// The AI usage dashboard emits percentage fields as floats (e.g. 9.6, 2.555555)
// after switching them from int to float. Decode them as Double and round to Int
// so the whole quotas array does not fail to parse, mirroring the iOS client.
// A non-finite or out-of-Int-range value throws a typed error instead of trapping.
private object RoundedPercentageSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RoundedPercentage", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeDouble(value.toDouble())
    }

    override fun deserialize(decoder: Decoder): Int {
        val value = decoder.decodeDouble()
        if (!value.isFinite()) {
            throw SerializationException("percentage value $value is not finite")
        }
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw SerializationException("percentage value $value is out of range")
        }
        return value.roundToInt()
    }
}
