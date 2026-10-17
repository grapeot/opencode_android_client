package com.yage.opencode_client.ambient.protocol

import kotlinx.serialization.json.JsonObject

data class AmbientQuote(val ordinaryInputTokens: Int, val estimatedUsd: Double)

object AmbientQuoteMaker {
    fun make(usage: AmbientParsedUsage): AmbientQuote? {
        if (!usage.fieldsLegal) return null
        if (!AmbientRates.prices(usage.model)) return null
        val tier = usage.serviceTier.trim().lowercase()
        if (tier.isNotEmpty() && tier !in AmbientRates.standardTiers) return null
        val ordinary = usage.inputTokens - usage.cachedRead - usage.cacheWrite
        if (ordinary < 0) return null
        val long = usage.inputTokens > AmbientRates.LONG_CONTEXT_INPUT
        val inputScale = if (long) AmbientRates.LONG_INPUT_MULTIPLIER else 1.0
        val outputScale = if (long) AmbientRates.LONG_OUTPUT_MULTIPLIER else 1.0
        val usd = (
            ordinary * AmbientRates.INPUT * inputScale +
                usage.cachedRead * AmbientRates.CACHED_READ * inputScale +
                usage.cacheWrite * AmbientRates.CACHE_WRITE * inputScale +
                usage.outputTokens * AmbientRates.OUTPUT * outputScale
            ) / 1_000_000.0
        return AmbientQuote(ordinary, usd)
    }
}

object AmbientUsageParser {
    fun parse(objectJson: JsonObject): AmbientParsedUsage? {
        val usage = asObject(objectJson["usage"]) ?: return null
        val model = jsonString(objectJson["model"]).orEmpty()
        val responseId = jsonString(objectJson["id"]).orEmpty()
        val tier = jsonString(objectJson["service_tier"]).orEmpty()
        val reasoning = reasoningCount(usage)
        val input = jsonWhole(usage["input_tokens"])
        val output = jsonWhole(usage["output_tokens"])
        if (input == null || output == null) {
            return AmbientParsedUsage(
                inputTokens = 0,
                cachedRead = 0,
                cacheWrite = 0,
                outputTokens = 0,
                reasoningTokens = reasoning,
                model = model,
                responseId = responseId,
                serviceTier = tier,
                fieldsLegal = false,
            )
        }
        val details = usage["input_tokens_details"]
        val read: Int?
        val write: Int?
        when {
            details == null || details is kotlinx.serialization.json.JsonNull -> {
                read = 0
                write = 0
            }
            details is JsonObject -> {
                read = optionalCount(details, "cached_tokens")
                write = optionalCount(details, "cache_write_tokens")
            }
            else -> {
                read = null
                write = null
            }
        }
        val legal = read != null && write != null && (read + write) <= input
        return AmbientParsedUsage(
            inputTokens = input,
            cachedRead = read ?: 0,
            cacheWrite = write ?: 0,
            outputTokens = output,
            reasoningTokens = reasoning,
            model = model,
            responseId = responseId,
            serviceTier = tier,
            fieldsLegal = legal,
        )
    }

    private fun reasoningCount(usage: JsonObject): Int {
        val details = asObject(usage["output_tokens_details"]) ?: return 0
        return jsonWhole(details["reasoning_tokens"]) ?: 0
    }

    private fun optionalCount(objectJson: JsonObject, key: String): Int? {
        if (!objectJson.containsKey(key) || objectJson[key] is kotlinx.serialization.json.JsonNull) return 0
        return jsonWhole(objectJson[key])
    }
}

data class AmbientBackendUsage(
    val inputTokens: Int = 0,
    val cachedRead: Int = 0,
    val cacheWrite: Int = 0,
    val outputTokens: Int = 0,
    val responseCount: Int = 0,
    val estimatedUsd: Double = 0.0,
    val unpricedCount: Int = 0,
)

class AmbientUsageLedger {
    var usage: AmbientBackendUsage = AmbientBackendUsage()
        private set
    private val seen = HashSet<String>()

    /**
     * Blank [AmbientLifecycle.responseId] cannot be deduped. Those rows return false
     * and do not change tokens, cost, or [AmbientBackendUsage.responseCount].
     * A non-blank id is applied once.
     */
    fun record(life: AmbientLifecycle): Boolean {
        val id = life.responseId.trim()
        if (id.isEmpty()) return false
        if (!seen.add(id)) return false
        var parsed = life.usage
        usage = usage.copy(responseCount = usage.responseCount + 1)
        if (parsed == null) {
            usage = usage.copy(unpricedCount = usage.unpricedCount + 1)
            return true
        }
        if (parsed.responseId.isEmpty()) parsed = parsed.copy(responseId = id)
        if (parsed.model.isEmpty()) parsed = parsed.copy(model = life.model)
        val quote = if (parsed.fieldsLegal) AmbientQuoteMaker.make(parsed) else null
        if (quote == null) {
            usage = usage.copy(unpricedCount = usage.unpricedCount + 1)
            return true
        }
        usage = usage.copy(
            inputTokens = usage.inputTokens + quote.ordinaryInputTokens,
            cachedRead = usage.cachedRead + parsed.cachedRead,
            cacheWrite = usage.cacheWrite + parsed.cacheWrite,
            outputTokens = usage.outputTokens + parsed.outputTokens,
            estimatedUsd = usage.estimatedUsd + quote.estimatedUsd,
        )
        return true
    }
}

data class AmbientPortalEntry(
    val estimatedUsd: Double,
    val confirmed: Boolean,
    val status: String,
)

class AmbientPortalLedger {
    private val byRequest = LinkedHashMap<String, AmbientPortalEntry>()

    val entries: Map<String, AmbientPortalEntry> get() = byRequest

    fun replace(requestId: String, estimatedUsd: Double, confirmed: Boolean, status: String) {
        if (requestId.isEmpty() || !estimatedUsd.isFinite() || estimatedUsd < 0.0) return
        val normalized = if (status == "confirmed" || status == "provisional" || status == "anomaly") {
            status
        } else if (confirmed) {
            "confirmed"
        } else {
            "provisional"
        }
        byRequest[requestId] = AmbientPortalEntry(
            estimatedUsd = estimatedUsd,
            confirmed = confirmed && normalized == "confirmed",
            status = normalized,
        )
    }

    /** Null when nothing billable is known. Anomalies are omitted. Never coerced to zero. */
    val total: Double?
        get() {
            val rows = byRequest.values.filter { it.status != "anomaly" }
            if (rows.isEmpty()) return null
            return rows.sumOf { it.estimatedUsd }
        }

    /** False when unknown. Unconfirmed rows are not settled. */
    val settled: Boolean
        get() {
            val rows = byRequest.values.filter { it.status != "anomaly" }
            return rows.isNotEmpty() && rows.all { it.confirmed && it.status == "confirmed" }
        }
}

data class AmbientPortalDisplay(val amountUsd: Double?, val settled: Boolean)

fun AmbientPortalLedger.display(): AmbientPortalDisplay = AmbientPortalDisplay(total, settled)

/**
 * Server voice usage is a cumulative snapshot. Later events replace it; they are not added.
 */
class AmbientVoiceNote {
    var seconds: Double = 0.0
        private set
    var hasSnapshot: Boolean = false
        private set
    var unconfirmed: Boolean = false
        private set

    fun replace(seconds: Double) {
        if (!seconds.isFinite() || seconds < 0.0) return
        this.seconds = seconds
        hasSnapshot = true
    }

    fun close(seconds: Double?) {
        if (seconds != null && seconds.isFinite() && seconds >= 0.0) {
            this.seconds = seconds
            hasSnapshot = true
            return
        }
        unconfirmed = true
    }
}
