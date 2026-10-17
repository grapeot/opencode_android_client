package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientBackendUsage
import com.yage.opencode_client.ambient.protocol.AmbientCostSnapshot
import com.yage.opencode_client.ambient.protocol.AmbientLifecycle
import com.yage.opencode_client.ambient.protocol.AmbientParsedUsage
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ambient.protocol.AmbientPortalLedger
import com.yage.opencode_client.ambient.protocol.AmbientProtocol
import com.yage.opencode_client.ambient.protocol.AmbientQuoteMaker
import com.yage.opencode_client.ambient.protocol.AmbientRates
import com.yage.opencode_client.ambient.protocol.AmbientSnapshot
import com.yage.opencode_client.ambient.protocol.AmbientUsageLedger
import com.yage.opencode_client.ambient.protocol.AmbientUsageParser
import com.yage.opencode_client.ambient.protocol.AmbientVoiceNote
import com.yage.opencode_client.ambient.protocol.display
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure ledger, quote, portal, and voice contracts.
 * Blank response ids are ignored: return false and leave totals untouched.
 * Model arguments are explicit; an empty model is the Luna base rate.
 */
class AmbientUsageTest {
    @Test
    fun `known response id is counted once across terminal events`() {
        val ledger = AmbientUsageLedger()
        val first = priced(inputTokens = 1_000, outputTokens = 100)
        val larger = priced(inputTokens = 9_000, outputTokens = 900)
        assertTrue(ledger.record(life("response.completed", "resp-1", first)))
        assertFalse(ledger.record(life("response.failed", " resp-1 ", larger)))
        assertFalse(ledger.record(life("response.incomplete", "resp-1", larger)))
        assertEquals(1, ledger.usage.responseCount)
        assertEquals(1_000, ledger.usage.inputTokens)
        assertEquals(100, ledger.usage.outputTokens)
        assertEquals(0, ledger.usage.unpricedCount)
        assertEquals(usd(ordinary = 1_000, output = 100), ledger.usage.estimatedUsd, DELTA)
    }

    @Test
    fun `blank response id is ignored`() {
        val ledger = AmbientUsageLedger()
        val row = priced(inputTokens = 2_000, outputTokens = 50)
        assertFalse(ledger.record(life("response.completed", "", row)))
        assertFalse(ledger.record(life("response.failed", "   ", row)))
        assertFalse(ledger.record(life("response.incomplete", "\n", row)))
        assertEquals(0, ledger.usage.responseCount)
        assertEquals(0, ledger.usage.inputTokens)
        assertEquals(0, ledger.usage.cachedRead)
        assertEquals(0, ledger.usage.cacheWrite)
        assertEquals(0, ledger.usage.outputTokens)
        assertEquals(0.0, ledger.usage.estimatedUsd, DELTA)
        assertEquals(0, ledger.usage.unpricedCount)

        assertTrue(ledger.record(life("response.completed", "resp-known", row)))
        val kept = ledger.usage.copy()
        assertFalse(ledger.record(life("response.completed", "", row)))
        assertEquals(kept.responseCount, ledger.usage.responseCount)
        assertEquals(kept.inputTokens, ledger.usage.inputTokens)
        assertEquals(kept.outputTokens, ledger.usage.outputTokens)
        assertEquals(kept.cachedRead, ledger.usage.cachedRead)
        assertEquals(kept.cacheWrite, ledger.usage.cacheWrite)
        assertEquals(kept.estimatedUsd, ledger.usage.estimatedUsd, DELTA)
        assertEquals(kept.unpricedCount, ledger.usage.unpricedCount)
    }

    @Test
    fun `cache tokens are not added twice`() {
        val parsed = AmbientUsageParser.parse(
            usageObject(
                inputTokens = 1_000,
                outputTokens = 50,
                cachedRead = 400,
                cacheWrite = 100,
                reasoningTokens = 80,
            ),
        )
        assertNotNull(parsed)
        assertEquals(400, parsed!!.cachedRead)
        assertEquals(100, parsed.cacheWrite)
        assertTrue(parsed.fieldsLegal)
        val quote = AmbientQuoteMaker.make(parsed)
        assertNotNull(quote)
        assertEquals(500, quote!!.ordinaryInputTokens)
        val ledger = AmbientUsageLedger()
        assertTrue(ledger.record(life("response.completed", "resp-cache", parsed)))
        assertEquals(500, ledger.usage.inputTokens)
        assertEquals(400, ledger.usage.cachedRead)
        assertEquals(100, ledger.usage.cacheWrite)
        assertEquals(50, ledger.usage.outputTokens)
        assertEquals(1_000, ledger.usage.inputTokens + ledger.usage.cachedRead + ledger.usage.cacheWrite)
        assertEquals(
            usd(ordinary = 500, cachedRead = 400, cacheWrite = 100, output = 50),
            ledger.usage.estimatedUsd,
            DELTA,
        )
    }

    @Test
    fun `reasoning tokens are not added to output`() {
        val parsed = AmbientUsageParser.parse(
            usageObject(
                inputTokens = 100,
                outputTokens = 50,
                reasoningTokens = 80,
            ),
        )
        assertNotNull(parsed)
        assertEquals(80, parsed!!.reasoningTokens)
        assertEquals(50, parsed.outputTokens)
        val ledger = AmbientUsageLedger()
        assertTrue(ledger.record(life("response.completed", "resp-reason", parsed)))
        assertEquals(50, ledger.usage.outputTokens)
        assertEquals(usd(ordinary = 100, output = 50), ledger.usage.estimatedUsd, DELTA)
        val withReasoningFoldedIn = usd(ordinary = 100, output = 130)
        assertTrue(ledger.usage.estimatedUsd < withReasoningFoldedIn - DELTA)
    }

    @Test
    fun `base and snapshot luna rates match`() {
        val tokens = priced(inputTokens = 10_000, outputTokens = 2_000, serviceTier = "standard")
        val base = AmbientQuoteMaker.make(tokens)
        val snapshot = AmbientQuoteMaker.make(tokens.copy(model = "gpt-6-luna-2026-10-17"))
        val prefixed = AmbientQuoteMaker.make(tokens.copy(model = " GPT-6-LUNA-snap "))
        val barePrefix = AmbientQuoteMaker.make(tokens.copy(model = "gpt-6-luna-"))
        val autoTier = AmbientQuoteMaker.make(tokens.copy(serviceTier = "AUTO"))
        assertNotNull(base)
        assertNotNull(snapshot)
        assertNotNull(prefixed)
        assertNotNull(barePrefix)
        assertNotNull(autoTier)
        val expected = usd(ordinary = 10_000, output = 2_000)
        assertEquals(expected, base!!.estimatedUsd, DELTA)
        assertEquals(expected, snapshot!!.estimatedUsd, DELTA)
        assertEquals(expected, prefixed!!.estimatedUsd, DELTA)
        assertEquals(expected, barePrefix!!.estimatedUsd, DELTA)
        assertEquals(expected, autoTier!!.estimatedUsd, DELTA)
        assertNull(AmbientQuoteMaker.make(tokens.copy(model = "gpt-6-lunatic")))
        assertEquals(272_000, AmbientRates.LONG_CONTEXT_INPUT)
        assertTrue(AmbientRates.prices(""))
        assertTrue(AmbientRates.prices("gpt-6-luna"))
        assertTrue(AmbientRates.prices("gpt-6-luna-2026-10-17"))
        assertFalse(AmbientRates.prices("gpt-6-lunatic"))
    }

    @Test
    fun `omitted model uses base luna rates`() {
        val omitted = priced(inputTokens = 1_000_000, outputTokens = 0, model = "")
        val ledger = AmbientUsageLedger()
        assertTrue(ledger.record(life("response.completed", "resp-omit", omitted, model = "")))
        assertEquals(0.20, ledger.usage.estimatedUsd, DELTA)
        assertEquals(1_000_000, ledger.usage.inputTokens)
        assertEquals(0, ledger.usage.unpricedCount)

        val blankModel = priced(inputTokens = 1_000_000, outputTokens = 0, model = "   ")
        assertTrue(ledger.record(life("response.completed", "resp-blank-model", blankModel, model = "")))
        assertEquals(0.40, ledger.usage.estimatedUsd, DELTA)

        val inherited = priced(inputTokens = 1_000_000, outputTokens = 0, model = "")
        assertTrue(
            ledger.record(
                life("response.completed", "resp-inherited", inherited, model = "gpt-6-luna-2026-10-17"),
            ),
        )
        assertEquals(0.60, ledger.usage.estimatedUsd, DELTA)
        assertEquals(0, ledger.usage.unpricedCount)
    }

    @Test
    fun `malformed nonfinite and unpriceable usage is not confirmed free`() {
        val ledger = AmbientUsageLedger()
        assertUnpriced(ledger, "resp-missing", null)
        assertUnpriced(
            ledger,
            "resp-illegal",
            priced(inputTokens = 10, cachedRead = 8, cacheWrite = 8, outputTokens = 4, fieldsLegal = false),
        )
        assertUnpriced(
            ledger,
            "resp-negative-ordinary",
            priced(inputTokens = 5, cachedRead = 9, outputTokens = 3, fieldsLegal = true),
        )
        assertUnpriced(ledger, "resp-unknown-model", priced(model = "gpt-4o"))
        assertUnpriced(ledger, "resp-tier", priced(serviceTier = "priority"))
        assertUnpriced(ledger, "resp-fraction", parsed("resp-fraction", input = JsonPrimitive(10.5), output = JsonPrimitive(1)))
        assertUnpriced(ledger, "resp-nan", parsed("resp-nan", input = JsonPrimitive(Double.NaN), output = JsonPrimitive(1)))
        assertUnpriced(
            ledger,
            "resp-infinity",
            parsed("resp-infinity", input = JsonPrimitive(1), output = JsonPrimitive(Double.POSITIVE_INFINITY)),
        )
        assertUnpriced(ledger, "resp-negative", parsed("resp-negative", input = JsonPrimitive(4), output = JsonPrimitive(-1)))
        assertUnpriced(ledger, "resp-text", parsed("resp-text", input = JsonPrimitive("12"), output = JsonPrimitive(1)))
        val overflow = AmbientUsageParser.parse(
            usageObject(inputTokens = 10, outputTokens = 4, cachedRead = 8, cacheWrite = 8),
        )
        assertNotNull(overflow)
        assertFalse(overflow!!.fieldsLegal)
        assertUnpriced(ledger, "resp-overflow", overflow)
        assertNull(AmbientUsageParser.parse(buildJsonObject { put("model", "gpt-6-luna") }))
        assertEquals(0.0, ledger.usage.estimatedUsd, DELTA)
        assertEquals(0, ledger.usage.inputTokens)
        assertEquals(0, ledger.usage.outputTokens)
        assertEquals(11, ledger.usage.unpricedCount)
        assertEquals(11, ledger.usage.responseCount)

        val portal = AmbientPortalLedger()
        portal.replace("bad", Double.NaN, confirmed = true, status = "confirmed")
        portal.replace("bad", Double.POSITIVE_INFINITY, confirmed = true, status = "confirmed")
        portal.replace("bad", Double.NEGATIVE_INFINITY, confirmed = true, status = "confirmed")
        portal.replace("bad", -1.0, confirmed = true, status = "confirmed")
        portal.replace("", 0.0, confirmed = true, status = "confirmed")
        assertTrue(portal.entries.isEmpty())
        assertNull(portal.total)
        assertFalse(portal.settled)
        assertNull(portal.display().amountUsd)
        assertFalse(portal.display().settled)
    }

    @Test
    fun `long context scales input by two and output by one and a half`() {
        val below = AmbientQuoteMaker.make(priced(inputTokens = AmbientRates.LONG_CONTEXT_INPUT, outputTokens = 0))
        val above = AmbientQuoteMaker.make(priced(inputTokens = AmbientRates.LONG_CONTEXT_INPUT + 1, outputTokens = 1_000))
        assertNotNull(below)
        assertNotNull(above)
        assertEquals(0.0272, below!!.estimatedUsd, DELTA)
        assertEquals(0.0551502, above!!.estimatedUsd, DELTA)

        val scaled = priced(
            inputTokens = 300_000,
            cachedRead = 100_000,
            outputTokens = 1_000,
        )
        val quote = AmbientQuoteMaker.make(scaled)
        assertNotNull(quote)
        assertEquals(200_000, quote!!.ordinaryInputTokens)
        assertEquals(0.04275, quote.estimatedUsd, DELTA)
        val ledger = AmbientUsageLedger()
        assertTrue(ledger.record(life("response.completed", "resp-long", scaled)))
        assertEquals(200_000, ledger.usage.inputTokens)
        assertEquals(100_000, ledger.usage.cachedRead)
        assertEquals(1_000, ledger.usage.outputTokens)
        assertEquals(0.04275, ledger.usage.estimatedUsd, DELTA)
    }

    @Test
    fun `portal request snapshots replace`() {
        val portal = AmbientPortalLedger()
        portal.replace("req-1", 1.25, confirmed = true, status = "confirmed")
        portal.replace("req-1", 0.40, confirmed = true, status = "confirmed")
        portal.replace("req-2", 0.10, confirmed = true, status = "confirmed")
        assertEquals(0.50, portal.total!!, DELTA)
        assertEquals(0.40, portal.entries.getValue("req-1").estimatedUsd, DELTA)
        assertEquals(2, portal.entries.size)
        assertTrue(portal.settled)
        portal.replace("req-1", Double.NaN, confirmed = true, status = "confirmed")
        assertEquals(0.40, portal.entries.getValue("req-1").estimatedUsd, DELTA)
        assertEquals(0.50, portal.total!!, DELTA)
    }

    @Test
    fun `portal anomaly is omitted and unknown stays null`() {
        val onlyAnomaly = AmbientPortalLedger()
        onlyAnomaly.replace("req-a", 9.0, confirmed = true, status = "anomaly")
        assertEquals("anomaly", onlyAnomaly.entries.getValue("req-a").status)
        assertFalse(onlyAnomaly.entries.getValue("req-a").confirmed)
        assertNull(onlyAnomaly.total)
        assertFalse(onlyAnomaly.settled)
        assertNull(onlyAnomaly.display().amountUsd)

        val portal = AmbientPortalLedger()
        portal.replace("req-b", 2.0, confirmed = true, status = "confirmed")
        portal.replace("req-b", 2.0, confirmed = false, status = "anomaly")
        assertNull(portal.total)
        assertFalse(portal.settled)
        portal.replace("req-c", 1.5, confirmed = true, status = "confirmed")
        portal.replace("req-d", 8.0, confirmed = false, status = "anomaly")
        assertEquals(1.5, portal.total!!, DELTA)
        assertTrue(portal.settled)

        val unknown = AmbientPortalLedger()
        assertNull(unknown.total)
        assertFalse(unknown.settled)
        unknown.replace("req-e", 1.0, confirmed = false, status = "later")
        assertEquals("provisional", unknown.entries.getValue("req-e").status)
        assertFalse(unknown.entries.getValue("req-e").confirmed)
        assertEquals(1.0, unknown.total!!, DELTA)
        assertFalse(unknown.settled)
    }

    @Test
    fun `provisional portal usage is unsettled`() {
        val portal = AmbientPortalLedger()
        portal.replace("req-1", 1.0, confirmed = true, status = "confirmed")
        portal.replace("req-2", 0.25, confirmed = false, status = "provisional")
        assertEquals(1.25, portal.total!!, DELTA)
        assertFalse(portal.settled)
        assertFalse(portal.display().settled)
        portal.replace("req-2", 0.25, confirmed = false, status = "confirmed")
        assertFalse(portal.entries.getValue("req-2").confirmed)
        assertFalse(portal.settled)
        portal.replace("req-2", 0.25, confirmed = true, status = "confirmed")
        assertTrue(portal.settled)
        assertEquals(1.25, portal.display().amountUsd!!, DELTA)
    }

    @Test
    fun `local estimate and portal cost stay separate`() {
        val local = AmbientUsageLedger()
        assertTrue(
            local.record(life("response.completed", "resp-local", priced(inputTokens = 1_000_000, outputTokens = 0))),
        )
        val portal = AmbientPortalLedger()
        portal.replace("req-1", 4.5, confirmed = true, status = "confirmed")
        val cost = AmbientCostSnapshot(
            localEstimateUsd = local.usage.estimatedUsd,
            localUnpricedCount = local.usage.unpricedCount,
            portalUsd = portal.total,
            portalSettled = portal.settled,
        )
        assertEquals(0.20, cost.localEstimateUsd, DELTA)
        assertEquals(0, cost.localUnpricedCount)
        assertEquals(4.5, cost.portalUsd!!, DELTA)
        assertTrue(cost.portalSettled)
        assertEquals(AmbientProtocol.ESTIMATE_NOTE, cost.estimateNote)
        assertEquals(0.20, local.usage.estimatedUsd, DELTA)
        assertEquals(4.5, portal.total!!, DELTA)

        val snap = AmbientSnapshot(
            phase = AmbientPhase.LISTENING,
            captions = emptyList(),
            activities = emptyList(),
            muted = false,
            inputRms = 0.0,
            outputRms = 0.0,
            voiceSeconds = 0.0,
            voiceHasSnapshot = false,
            voiceUnconfirmed = false,
            local = local.usage,
            portalUsd = portal.total,
            portalSettled = portal.settled,
            usageUnconfirmed = false,
            statusMessage = null,
        )
        assertEquals(0.20, snap.local.estimatedUsd, DELTA)
        assertEquals(4.5, snap.portalUsd!!, DELTA)
        assertFalse(snap.local.estimatedUsd == snap.portalUsd)
    }

    @Test
    fun `voice snapshots replace and missing close does not zero known seconds`() {
        val note = AmbientVoiceNote()
        note.replace(1.0)
        note.replace(12.5)
        note.replace(3.0)
        assertEquals(3.0, note.seconds, DELTA)
        assertTrue(note.hasSnapshot)
        assertFalse(note.unconfirmed)

        note.replace(Double.NaN)
        note.replace(Double.POSITIVE_INFINITY)
        note.replace(-1.0)
        assertEquals(3.0, note.seconds, DELTA)
        assertTrue(note.hasSnapshot)

        note.close(null)
        assertEquals(3.0, note.seconds, DELTA)
        assertTrue(note.hasSnapshot)
        assertTrue(note.unconfirmed)

        note.close(Double.NaN)
        note.close(-4.0)
        assertEquals(3.0, note.seconds, DELTA)
        assertTrue(note.unconfirmed)

        note.close(7.25)
        assertEquals(7.25, note.seconds, DELTA)
        assertTrue(note.hasSnapshot)

        val fresh = AmbientVoiceNote()
        fresh.close(null)
        assertEquals(0.0, fresh.seconds, DELTA)
        assertFalse(fresh.hasSnapshot)
        assertTrue(fresh.unconfirmed)
    }

    private fun assertUnpriced(ledger: AmbientUsageLedger, id: String, usage: AmbientParsedUsage?) {
        val before = ledger.usage.copy()
        if (usage != null && usage.fieldsLegal) {
            assertNull(AmbientQuoteMaker.make(usage))
        }
        assertTrue(ledger.record(life("response.completed", id, usage, model = "gpt-6-luna")))
        assertEquals(before.inputTokens, ledger.usage.inputTokens)
        assertEquals(before.cachedRead, ledger.usage.cachedRead)
        assertEquals(before.cacheWrite, ledger.usage.cacheWrite)
        assertEquals(before.outputTokens, ledger.usage.outputTokens)
        assertEquals(before.estimatedUsd, ledger.usage.estimatedUsd, DELTA)
        assertEquals(before.responseCount + 1, ledger.usage.responseCount)
        assertEquals(before.unpricedCount + 1, ledger.usage.unpricedCount)
    }

    private fun parsed(id: String, input: JsonPrimitive, output: JsonPrimitive): AmbientParsedUsage? =
        AmbientUsageParser.parse(
            buildJsonObject {
                put("id", id)
                put("model", "gpt-6-luna")
                putJsonObject("usage") {
                    put("input_tokens", input)
                    put("output_tokens", output)
                }
            },
        )

    private fun usageObject(
        inputTokens: Int,
        outputTokens: Int,
        cachedRead: Int = 0,
        cacheWrite: Int = 0,
        reasoningTokens: Int = 0,
        model: String = "gpt-6-luna",
        id: String = "resp",
    ) = buildJsonObject {
        put("id", id)
        put("model", model)
        putJsonObject("usage") {
            put("input_tokens", inputTokens)
            put("output_tokens", outputTokens)
            putJsonObject("input_tokens_details") {
                put("cached_tokens", cachedRead)
                put("cache_write_tokens", cacheWrite)
            }
            putJsonObject("output_tokens_details") {
                put("reasoning_tokens", reasoningTokens)
            }
        }
    }

    private fun priced(
        inputTokens: Int = 100,
        cachedRead: Int = 0,
        cacheWrite: Int = 0,
        outputTokens: Int = 10,
        reasoningTokens: Int = 0,
        model: String = "gpt-6-luna",
        responseId: String = "resp",
        serviceTier: String = "",
        fieldsLegal: Boolean = true,
    ) = AmbientParsedUsage(
        inputTokens = inputTokens,
        cachedRead = cachedRead,
        cacheWrite = cacheWrite,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        model = model,
        responseId = responseId,
        serviceTier = serviceTier,
        fieldsLegal = fieldsLegal,
    )

    private fun life(
        type: String,
        responseId: String,
        usage: AmbientParsedUsage?,
        model: String = "",
    ) = AmbientLifecycle(
        type = type,
        responseId = responseId,
        model = model,
        outputCount = 1,
        usage = usage,
    )

    private fun usd(
        ordinary: Int,
        cachedRead: Int = 0,
        cacheWrite: Int = 0,
        output: Int,
        inputScale: Double = 1.0,
        outputScale: Double = 1.0,
    ): Double = (
        ordinary * AmbientRates.INPUT * inputScale +
            cachedRead * AmbientRates.CACHED_READ * inputScale +
            cacheWrite * AmbientRates.CACHE_WRITE * inputScale +
            output * AmbientRates.OUTPUT * outputScale
        ) / 1_000_000.0

    private companion object {
        const val DELTA = 1e-9
    }
}

private fun AmbientBackendUsage.copy(): AmbientBackendUsage = this
