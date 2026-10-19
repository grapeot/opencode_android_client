package com.yage.opencode_client.data.api

import com.yage.opencode_client.ambient.protocol.AmbientDispatchGate
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SharedSSERepositoryTest {
    @Test fun chatDetachAndSameConfigurationKeepOneHttpSourceAndVoiceLease() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"payload\":{\"type\":\"server.heartbeat\"}}\n\n".repeat(100))
            .throttleBody(64, 20, TimeUnit.MILLISECONDS))
        server.start()
        val repository = OpenCodeRepository()
        val url = server.url("/").toString().trimEnd('/')
        repository.configure(url, "synthetic", "credential")
        val gate = AmbientDispatchGate()
        val token = repository.armAmbient(gate, "host", 1, "/work", 1, "owner")
        val chatReady = CompletableDeferred<Unit>()
        val voiceReady = CompletableDeferred<Unit>()
        val voiceFrame = CompletableDeferred<Unit>()
        val chat = launch { repository.connectSSE({ chatReady.complete(Unit) }).collect {} }
        var voice: kotlinx.coroutines.Job? = null
        try {
            withTimeout(5000) { chatReady.await() }
            assertTrue(repository.hasActiveAmbientTransport())
            assertFalse(repository.configureForHealth(url, "synthetic", "credential"))
            assertNotNull(gate.authorize(token))
            voice = launch {
                repository.connectSSE({ voiceReady.complete(Unit) }).collect { result ->
                    if (result.isSuccess) voiceFrame.complete(Unit)
                }
            }
            withTimeout(5000) { voiceReady.await() }
            chat.cancelAndJoin()
            withTimeout(5000) { voiceFrame.await() }
            assertEquals(1, server.requestCount)
            assertNotNull(gate.authorize(token))
        } finally {
            chat.cancelAndJoin()
            voice?.cancelAndJoin()
            repository.configure(OpenCodeRepository.DEFAULT_SERVER)
            server.shutdown()
        }
    }
}
