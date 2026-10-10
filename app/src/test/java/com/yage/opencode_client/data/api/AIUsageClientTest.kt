package com.yage.opencode_client.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        val decoded = result.getOrThrow()
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
        val decoded = client.fetchQuotas(server.url("/").toString()).getOrThrow()
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
        val quotas = result.getOrThrow().quotas
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
}
