package com.yage.opencode_client.data.api

import com.yage.opencode_client.data.model.AIUsageQuota
import com.yage.opencode_client.data.model.AIUsageQuotaKey
import com.yage.opencode_client.data.model.AIUsageQuotaSnapshot
import com.yage.opencode_client.data.model.ModelShortlistItem
import com.yage.opencode_client.data.model.applyQuotaFetchResult
import com.yage.opencode_client.data.model.isQuotaStale
import com.yage.opencode_client.ui.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AIUsageClientTest {
    private val server = MockWebServer()
    private lateinit var client: AIUsageClient

    @Before
    fun setup() {
        server.start()
        client = AIUsageClient(OkHttpClient())
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `fetch decodes compact quota contract`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"generated_at":"2026-07-12T09:00:00","quotas":[{"provider":"codex","label":"5h","used_percentage":29,"remaining_percentage":71,"next_reset_time_ms":1783842841000}]}"""
        ))

        val result = client.fetchQuotas(server.url("/").toString())

        assertTrue(result.isSuccess)
        val decoded = result.getOrThrow().snapshot
        assertTrue(decoded.failedKeys.isEmpty())
        assertEquals("2026-07-12T09:00:00", decoded.generatedAt)
        val quota = decoded.quotas.single()
        assertEquals(71, quota.clampedRemainingPercentage)
        assertEquals(1783842841000L, quota.nextResetTimeMs)
        assertNull(quota.nextResetIso)
        assertNull(quota.observedAtIso)
        assertNull(quota.measurementSource)
        assertNull(quota.observedAtMs)
        assertEquals("/api/v1/quotas", server.takeRequest().path)
    }

    @Test
    fun `fetch decodes observed_at separately from the transport timestamp`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"generated_at":"2026-10-10T15:49:12","quotas":[{"provider":"codex","label":"5h","used_percentage":29,"remaining_percentage":71,"observed_at":"2026-10-10T08:49:11.527738-07:00","measurement_source":"cache"}]}"""
        ))

        val before = System.currentTimeMillis()
        val decoded = client.fetchQuotas(server.url("/").toString()).getOrThrow().snapshot
        val after = System.currentTimeMillis()

        val quota = decoded.quotas.single()
        assertEquals("2026-10-10T08:49:11.527738-07:00", quota.observedAtIso)
        assertEquals(1_791_647_351_527L, quota.observedAtMs)
        assertEquals("cache", quota.measurementSource)
        assertEquals("2026-10-10T15:49:12", decoded.generatedAt)
        assertTrue(decoded.fetchedAtMs in before..after)
    }

    @Test
    fun `fetch decodes fractional and float-formatted whole percentages`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"generated_at":"2020-01-01T00:00:00","quotas":[
              {"provider":"test-a","label":"5h","used_percentage":9.6,"remaining_percentage":90.4},
              {"provider":"test-b","label":"7d","used_percentage":2.555555,"remaining_percentage":97.444445},
              {"provider":"codex","label":"5h","used_percentage":29.0,"remaining_percentage":71.0}
            ]}"""
        ))

        val result = client.fetchQuotas(server.url("/").toString())

        assertTrue(result.isSuccess)
        val fetched = result.getOrThrow()
        assertTrue(fetched.failedKeys.isEmpty())
        val quotas = fetched.snapshot.quotas
        assertEquals(3, quotas.size)
        // rounding is locked on the raw fields: 9.6 rounds up to 10, 90.4 rounds down to 90
        assertEquals(10, quotas[0].usedPercentage)
        assertEquals(90, quotas[0].remainingPercentage)
        // long decimals parse and round: 2.555555 -> 3, 97.444445 -> 97
        assertEquals(3, quotas[1].usedPercentage)
        assertEquals(97, quotas[1].remainingPercentage)
        // a whole number written in float form still decodes
        assertEquals(29, quotas[2].usedPercentage)
        assertEquals(71, quotas[2].remainingPercentage)
    }

    @Test
    fun `manual refresh can run update before quota fetch`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"generated_at":null,"quotas":[]}"""
        ))
        val baseUrl = server.url("/").toString()

        client.refreshDashboard(baseUrl).getOrThrow()
        client.fetchQuotas(baseUrl).getOrThrow()

        val update = server.takeRequest()
        assertEquals("POST", update.method)
        assertEquals("/api/v1/display/update", update.path)
        assertTrue(update.body.readUtf8().contains("opencode-android"))
        val fetch = server.takeRequest()
        assertEquals("GET", fetch.method)
        assertEquals("/api/v1/quotas", fetch.path)
    }

    @Test
    fun `normalization accepts private HTTP and rejects public HTTP`() {
        assertEquals("http://192.168.1.4:7995/api/v1/quotas", client.quotasEndpoint("192.168.1.4:7995"))
        assertEquals("http://host.example.ts.net:7995/api/v1/quotas", client.quotasEndpoint("http://host.example.ts.net:7995"))
        assertTrue(runCatching { client.quotasEndpoint("http://example.com:7995") }.isFailure)
    }

    @Test
    fun `one bad quota item does not drop the other providers`() = runBlocking {
        val now = System.currentTimeMillis()
        val freshIso = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now - 1_000), ZoneOffset.UTC).toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"generated_at":"2026-10-10T15:49:12","quotas":[
              {"provider":"codex","label":"5h","used_percentage":29,"remaining_percentage":71,"observed_at":"$freshIso"},
              {"provider":"grok","label":"Weekly","used_percentage":"oops"},
              null,
              {"provider":"ollama","label":"5h","used_percentage":15,"remaining_percentage":85,"observed_at":"$freshIso"}
            ]}"""
        ))

        val result = client.fetchQuotas(server.url("/").toString())

        assertTrue(result.isSuccess)
        val fetched = result.getOrThrow()
        val snapshot = fetched.snapshot
        assertEquals(setOf(AIUsageQuotaKey("grok", "Weekly")), fetched.failedKeys)
        assertEquals(fetched.failedKeys, snapshot.failedKeys)
        assertEquals(listOf("codex", "ollama"), snapshot.quotas.map { it.provider })
        assertEquals(71, snapshot.quotas[0].remainingPercentage)
        assertEquals(85, snapshot.quotas[1].remainingPercentage)
        assertFalse(isQuotaStale(snapshot.quotas[0], snapshot, now, hasError = false))
        assertFalse(isQuotaStale(snapshot.quotas[1], snapshot, now, hasError = false))
        assertTrue(snapshot.quotas.none { it.provider.equals("grok", ignoreCase = true) })
    }

    @Test
    fun `invalid json is a fatal fetch failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("{not-json"))

        val result = client.fetchQuotas(server.url("/").toString())

        assertTrue(result.isFailure)
    }

    @Test
    fun `top-level json that is not an object is a fatal fetch failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        assertTrue(client.fetchQuotas(server.url("/").toString()).isFailure)
    }

    @Test
    fun `quotas field that is not an array is a fatal fetch failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"generated_at":"2026-10-10T00:00:00","quotas":{"provider":"codex"}}"""))

        assertTrue(client.fetchQuotas(server.url("/").toString()).isFailure)
    }

    @Test
    fun `http error is a fatal fetch failure even when the body is valid json`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"generated_at":"2026-10-10T00:00:00","quotas":[]}""")
        )

        val result = client.fetchQuotas(server.url("/").toString())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }

    @Test
    fun `unreadable quota items are a fatal fetch and keep the previous snapshot`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"generated_at":"2026-10-10T00:00:00","quotas":[null,{}]}"""))
        val previous = AIUsageQuotaSnapshot(
            generatedAt = "old",
            fetchedAtMs = 1_000L,
            quotas = listOf(AIUsageQuota("codex", "5h", 29, 71))
        )

        val result = client.fetchQuotas(server.url("/").toString())
        val applied = applyQuotaFetchResult(previous, result)
        val state = AppState(
            selectedModelIndex = 0,
            modelShortlist = listOf(ModelShortlistItem("openai", "gpt-5", "GPT", "GPT")),
            aiUsageError = applied.error,
            aiUsageQuotaSnapshot = applied.snapshot
        )

        assertTrue(result.isFailure)
        assertEquals(previous, applied.snapshot)
        assertTrue(applied.error?.isNotBlank() == true)
        assertTrue(state.isSelectedModelQuotaStale)
    }

    @Test
    fun `empty quotas array still replaces the previous snapshot`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"generated_at":"2026-10-10T00:00:00","quotas":[]}"""))
        val previous = AIUsageQuotaSnapshot(
            generatedAt = "old",
            fetchedAtMs = 1_000L,
            quotas = listOf(AIUsageQuota("codex", "5h", 29, 71))
        )

        val result = client.fetchQuotas(server.url("/").toString())
        val applied = applyQuotaFetchResult(previous, result)

        assertTrue(result.isSuccess)
        assertTrue(applied.snapshot!!.quotas.isEmpty())
        assertTrue(applied.snapshot.failedKeys.isEmpty())
        assertNull(applied.error)
        assertEquals("2026-10-10T00:00:00", applied.snapshot.generatedAt)
    }

    @Test
    fun `case variant bad items collapse to one failed key`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"quotas":[
              {"provider":"grok","label":"Weekly","used_percentage":"oops"},
              {"provider":"GROK","label":"weekly","used_percentage":"oops"}
            ]}"""
        ))

        val fetched = client.fetchQuotas(server.url("/").toString()).getOrThrow()

        val failed = fetched.failedKeys.single()
        assertEquals(1, fetched.snapshot.failedKeys.size)
        assertTrue(failed.provider.equals("grok", ignoreCase = true))
        assertTrue(failed.label.equals("Weekly", ignoreCase = true))
    }

    @Test
    fun `cancellation propagates instead of becoming a failed result`() {
        val cancelling = AIUsageClient(
            OkHttpClient.Builder().addInterceptor { throw CancellationException("stop") }.build()
        )
        val url = "http://127.0.0.1:9"
        val fetch = runCatching { runBlocking { cancelling.fetchQuotas(url) } }
        val refresh = runCatching { runBlocking { cancelling.refreshDashboard(url) } }

        assertTrue(fetch.exceptionOrNull() is CancellationException)
        assertTrue(refresh.exceptionOrNull() is CancellationException)
    }
}
