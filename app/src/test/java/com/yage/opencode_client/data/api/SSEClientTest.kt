package com.yage.opencode_client.data.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SSEClientTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `onConnected fires when the stream opens`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n")
        )

        val connected = AtomicBoolean(false)
        val firstEvent = CompletableDeferred<Unit>()
        // newEventSource blocks its thread, so keep it off the runBlocking loop.
        val job = launch(Dispatchers.Default) {
            SSEClient(OkHttpClient())
                .connect(server.url("/").toString(), onConnected = { connected.set(true) })
                .collect { firstEvent.complete(Unit) }
        }
        try {
            withTimeout(5_000) { firstEvent.await() }
            assertTrue(connected.get())
        } finally {
            job.cancel()
        }
    }
}
