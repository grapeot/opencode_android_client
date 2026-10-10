package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.AIUsageQuota
import com.yage.opencode_client.data.model.AIUsageQuotaKey
import com.yage.opencode_client.data.model.isQuotaStale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// quota_contract_fixtures.json 是 dashboard producer / Android / iOS decoder 三方对齐的契约样本。
// dashboard 发布前应把该文件当作 consumer 兼容性检查。iOS 与 dashboard 采用同一 fixtures 是 follow-up，本次只在 Android 落地。
class QuotaContractFixturesTest {
    private val client = AIUsageClient(OkHttpClient())

    // Must match AIUsageClient's Json: ignoreUnknownKeys + isLenient, special floats left rejected.
    private val layerProbe = Json { ignoreUnknownKeys = true; isLenient = true }
    private val fixtures = Json { ignoreUnknownKeys = true }

    @Test
    fun `quota contract fixtures match decodeQuotaFetch`() {
        val file = fixtures.decodeFromString<FixtureFile>(loadFixtures())
        assertTrue(file.note.contains("dashboard producer"))
        val failures = mutableListOf<String>()
        for (case in file.cases) {
            try {
                assertCase(case)
            } catch (error: AssertionError) {
                failures += "${case.id}: ${error.message}"
            } catch (error: Exception) {
                failures += "${case.id}: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun assertCase(case: FixtureCase) {
        val decoded = runCatching { client.decodeQuotaFetch(case.body, case.fetchedAtMs) }
        if (case.expect.outcome == "fatal") {
            assertTrue(case.id, decoded.isFailure)
            val message = decoded.exceptionOrNull()?.message.orEmpty()
            assertTrue(message, message.contains(case.expect.fatalMessageContains))
            return
        }
        assertTrue("${case.id} ${decoded.exceptionOrNull()?.message}", decoded.isSuccess)
        val result = decoded.getOrThrow()
        val quotas = result.snapshot.quotas
        assertEquals(case.id, case.expect.items.map { it.provider to it.label }, quotas.map { it.provider to it.label })
        case.expect.items.zip(quotas).forEach { (expected, quota) ->
            assertEquals(case.id, expected.used, quota.usedPercentage)
            assertEquals(case.id, expected.remaining, quota.remainingPercentage)
            assertEquals(case.id, expected.clampedUsed, quota.clampedUsedPercentage)
            assertEquals(case.id, expected.clampedRemaining, quota.clampedRemainingPercentage)
            assertEquals(case.id, 100, quota.clampedUsedPercentage + quota.clampedRemainingPercentage)
            if (expected.observedAt.isNotEmpty()) {
                assertEquals(case.id, expected.observedAt, quota.observedAtIso)
            }
            if (case.expect.assertNullOptionals) {
                assertNull(quota.observedAtIso)
                assertNull(quota.observedAtMs)
                assertNull(quota.measurementSource)
                assertNull(quota.nextResetTimeMs)
                assertNull(quota.usage)
                assertNull(quota.remaining)
            }
            if (case.expect.assertFresh) {
                assertEquals(case.id, case.observedAtEpochMs, quota.observedAtMs)
                assertFalse(isQuotaStale(quota, result.snapshot, case.nowMs, hasError = false))
            }
        }
        val failed = result.failedKeys.map { AIUsageQuotaKey(it.provider, it.label) }.toSet()
        val expectedFailed = case.expect.failed.map { AIUsageQuotaKey(it.provider, it.label) }.toSet()
        assertEquals(case.id, expectedFailed, failed)
        // Freshness and merge read result.snapshot.failedKeys, so lock it too, not only result.failedKeys.
        val snapshotFailed = result.snapshot.failedKeys.map { AIUsageQuotaKey(it.provider, it.label) }.toSet()
        assertEquals(case.id, expectedFailed, snapshotFailed)
        assertTrue(quotas.none { AIUsageQuotaKey(it.provider, it.label) in expectedFailed })
        for (failure in case.expect.failed) {
            if (failure.layer.isNotEmpty()) {
                assertFailureLayer(case, failure)
            }
        }
    }

    private fun assertFailureLayer(case: FixtureCase, failure: FixtureFailed) {
        val element = layerProbe.parseToJsonElement(case.body)
            .jsonObject.getValue("quotas")
            .jsonArray
            .first { node ->
                val obj = node.jsonObject
                obj.string("provider") == failure.provider && obj.string("label") == failure.label
            }
        val error = runCatching { layerProbe.decodeFromJsonElement<AIUsageQuota>(element) }.exceptionOrNull()
            ?: error("${case.id} ${failure.label} decoded")
        val message = error.message.orEmpty()
        when (failure.layer) {
            "serializer_range" -> {
                assertTrue(message, message.contains("out of range"))
                assertFalse(message, message.contains("non-finite floating point values are prohibited"))
            }
            "json_non_finite" -> {
                assertTrue(message, message.contains("non-finite floating point values are prohibited"))
                assertFalse(message, message.contains("out of range"))
                assertFalse(message, message.contains("is not finite"))
            }
            else -> error("${case.id} unknown layer ${failure.layer}")
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun loadFixtures(): String {
        val loader = checkNotNull(javaClass.classLoader)
        val stream = checkNotNull(loader.getResourceAsStream("quota_contract_fixtures.json")) {
            "missing quota_contract_fixtures.json"
        }
        return stream.bufferedReader().use { it.readText() }
    }
}

@Serializable
private data class FixtureFile(
    val note: String,
    val cases: List<FixtureCase>
)

@Serializable
private data class FixtureCase(
    val id: String,
    val note: String = "",
    val coverage: String = "",
    val coveredBy: String = "",
    val body: String,
    val fetchedAtMs: Long = 1_700_000_000_000L,
    val nowMs: Long = 1_700_000_001_000L,
    val observedAtEpochMs: Long = 0L,
    val expect: FixtureExpect
)

@Serializable
private data class FixtureExpect(
    val outcome: String,
    val fatalMessageContains: String = "",
    val items: List<FixtureItem> = emptyList(),
    val failed: List<FixtureFailed> = emptyList(),
    val assertFresh: Boolean = false,
    val assertNullOptionals: Boolean = false
)

@Serializable
private data class FixtureItem(
    val provider: String,
    val label: String,
    val used: Int = 0,
    val remaining: Int = 0,
    val clampedUsed: Int = 0,
    val clampedRemaining: Int = 0,
    val observedAt: String = ""
)

@Serializable
private data class FixtureFailed(
    val provider: String,
    val label: String,
    val layer: String = ""
)
