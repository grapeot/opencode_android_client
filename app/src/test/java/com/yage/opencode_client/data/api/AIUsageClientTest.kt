package com.yage.opencode_client.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertEquals("2026-07-12T09:00:00", result.getOrThrow().generatedAt)
        assertEquals(71, result.getOrThrow().quotas.single().clampedRemainingPercentage)
        assertEquals("/api/v1/quotas", server.takeRequest().path)
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
