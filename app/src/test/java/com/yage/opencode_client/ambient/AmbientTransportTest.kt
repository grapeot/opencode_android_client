package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.protocol.AmbientClientEvent
import com.yage.opencode_client.ambient.protocol.AmbientConfiguration
import com.yage.opencode_client.ambient.protocol.AmbientCreateBody
import com.yage.opencode_client.ambient.protocol.AmbientServerEvent
import com.yage.opencode_client.ambient.protocol.AmbientWire
import com.yage.opencode_client.ambient.protocol.jsonObject
import com.yage.opencode_client.ambient.protocol.jsonString
import com.yage.opencode_client.ambient.transport.AmbientBuilderClients
import com.yage.opencode_client.ambient.transport.AmbientInbound
import com.yage.opencode_client.ambient.transport.AmbientLinkException
import com.yage.opencode_client.ambient.transport.AmbientLinkFailure
import com.yage.opencode_client.ambient.transport.AmbientOrderedLink
import com.yage.opencode_client.ambient.transport.OkHttpAmbientTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class AmbientTransportTest {
    private val server = MockWebServer()
    private val client: OkHttpClient = AmbientBuilderClients.create()
    private val transport = OkHttpAmbientTransport(client)

    @Before
    fun setup() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun builderClientRefusesRedirectsCacheLoggingAndAuthenticator() {
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
        assertEquals(okhttp3.Authenticator.NONE, client.authenticator)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertTrue(client.interceptors.none { it is HttpLoggingInterceptor })
    }

    @Test
    fun create200And201UseBearerBodyAndAdvertisedBackendSocket() = runBlocking {
        for (status in listOf(200, 201)) {
            val frames = CopyOnWriteArrayList<String>()
            server.enqueue(MockResponse().setResponseCode(status).setBody(ticketBody()).setHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"session.started","session_id":"portal-session"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(text)
                }
            }))
            val link = transport.open(configuration())
            try {
                val post = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val socket = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("POST", post.method)
                assertEquals("/backend/v1/audio/live/sessions", post.requestUrl?.encodedPath)
                assertEquals("Bearer synthetic-builder-token", post.getHeader("Authorization"))
                assertFalse(post.headers.values("Authorization").any { it.startsWith("Basic ") })
                assertTrue(post.getHeader("Content-Type")!!.contains("application/json"))
                val posted = jsonObject(post.body.readUtf8())
                assertEquals(AmbientCreateBody.keys, posted?.keys)
                assertNull(socket.getHeader("Authorization"))
                assertEquals("/backend", socket.requestUrl?.encodedPath)
                assertEquals("ticket_1", socket.requestUrl?.queryParameter("ticket"))

                val started = withTimeout(5_000) { link.incoming.first() }
                assertEquals("portal-session", (started as AmbientServerEvent.Started).sessionId)

                link.send(AmbientClientEvent.Audio(byteArrayOf(0, 1)))
                link.send(AmbientClientEvent.Mute)
                link.send(AmbientClientEvent.FunctionOutput("call_1", """{"ok":true}"""))
                link.send(AmbientClientEvent.ContinueResponse)
                val sent = withTimeout(5_000) {
                    while (frames.size < 4) yield()
                    frames.toList()
                }
                val types = sent.map { jsonString(jsonObject(it)?.get("type")) }
                assertEquals(
                    listOf(
                        "session.input_audio.append",
                        "session.input_audio.mute",
                        "response.item.create",
                        "response.create",
                    ),
                    types,
                )
                assertTrue(types.all { it in AmbientWire.allowed && !AmbientWire.forbidden(it!!) })
            } finally {
                link.close()
            }
        }
    }

    @Test
    fun createStatusAndRedirectDoNotLeakBodyOrUrl() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("http://secret.example/ticket"))
        val status = runCatching { transport.open(configuration()) }
        val statusError = status.exceptionOrNull() as AmbientLinkException
        assertEquals(AmbientLinkFailure.CREATE_STATUS, statusError.failure)
        assertEquals("CREATE_STATUS", statusError.message)
        assertFalse(statusError.toString().contains("secret.example"))

        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/other").toString())
                .setBody("http://secret.example/next"),
        )
        val redirect = runCatching { transport.open(configuration()) }
        val redirectError = redirect.exceptionOrNull() as AmbientLinkException
        assertEquals(AmbientLinkFailure.CREATE_STATUS, redirectError.failure)
        assertFalse(redirectError.toString().contains("http"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun mismatchedTicketAndExpiredExpiryDoNotOpenTheSocket() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(ticketBody(ws = "/backend?ticket=other")))
        val mismatch = runCatching { transport.open(configuration()) }
        assertEquals(AmbientLinkFailure.ORIGIN, (mismatch.exceptionOrNull() as AmbientLinkException).failure)

        server.enqueue(MockResponse().setResponseCode(200).setBody(ticketBody(expiry = "2000-01-01T00:00:00Z")))
        val expired = runCatching { transport.open(configuration()) }
        assertEquals(AmbientLinkFailure.EXPIRY, (expired.exceptionOrNull() as AmbientLinkException).failure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rejectedTransmitFailsTheOrderedSend() = runBlocking {
        val seen = mutableListOf<String>()
        val link = orderedLink { text ->
            seen.add(text)
            false
        }
        try {
            val rejected = runCatching { link.send(AmbientClientEvent.Mute) }
            assertEquals(AmbientLinkFailure.REJECTED, (rejected.exceptionOrNull() as AmbientLinkException).failure)
            val later = runCatching { link.send(AmbientClientEvent.Unmute) }
            assertTrue(later.exceptionOrNull() is AmbientLinkException)
            assertEquals(1, seen.size)
            assertFalse(seen.single().contains("session.start"))
            assertFalse(seen.single().contains("session.update"))
        } finally {
            link.close()
        }
    }

    @Test
    fun staleSendAndCloseDoNotWaitBehindHungAudio() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val link = orderedLink { text ->
            seen.add(text)
            if (text.contains("input_audio")) {
                entered.complete(Unit)
                release.await()
            }
            true
        }
        try {
            val audio = async {
                runCatching { link.send(AmbientClientEvent.Audio(byteArrayOf(0, 1))) }
            }
            withTimeout(5_000) { entered.await() }
            val control = async {
                runCatching { link.send(AmbientClientEvent.FunctionOutput("call_1", """{"ok":true}""")) }
            }
            yield()
            val started = System.nanoTime()
            withTimeout(1_000) { link.close() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 800)
            release.cancel()
            assertEquals(AmbientLinkFailure.STALE, (audio.await().exceptionOrNull() as AmbientLinkException).failure)
            assertEquals(AmbientLinkFailure.STALE, (control.await().exceptionOrNull() as AmbientLinkException).failure)
            assertTrue(seen.none { it.contains("function_call_output") || it.contains("session.start") })
        } finally {
            release.cancel()
            link.close()
        }
    }

    @Test
    fun blankCommentaryIsEncodeFailure() = runBlocking {
        var transmitted = false
        val link = orderedLink {
            transmitted = true
            true
        }
        try {
            val result = runCatching { link.send(AmbientClientEvent.Commentary("   ")) }
            assertEquals(AmbientLinkFailure.ENCODE, (result.exceptionOrNull() as AmbientLinkException).failure)
            assertFalse(transmitted)
        } finally {
            link.close()
        }
    }

    @Test
    fun startedStaysInOrderAndOverflowFailsVisibly() = runBlocking {
        val inbound = AmbientInbound(capacity = 2)
        inbound.offer(AmbientServerEvent.Started("kept"))
        repeat(10) { inbound.offer(AmbientServerEvent.OutputAudio(byteArrayOf(1, 2))) }
        inbound.close()
        val flooded = drain(inbound)
        assertTrue(flooded.first() is AmbientServerEvent.Started)
        assertEquals("kept", (flooded.first() as AmbientServerEvent.Started).sessionId)
        assertTrue(flooded.any { it is AmbientServerEvent.Failed && it.code == "buffer" })
        assertEquals(1, flooded.count { it is AmbientServerEvent.Started })

        val ordered = AmbientInbound(capacity = 4)
        ordered.offer(AmbientServerEvent.OutputAudio(byteArrayOf(1, 2)))
        ordered.offer(AmbientServerEvent.Started("later"))
        ordered.close()
        val events = drain(ordered)
        assertTrue(events[0] is AmbientServerEvent.OutputAudio)
        assertEquals("later", (events[1] as AmbientServerEvent.Started).sessionId)

        val full = AmbientInbound(capacity = 2)
        full.offer(AmbientServerEvent.Started("s"))
        full.offer(AmbientServerEvent.InvalidAudio)
        full.offer(AmbientServerEvent.UsageUnconfirmed)
        full.offer(AmbientServerEvent.Closed(null))
        val overflow = drain(full)
        assertTrue(overflow.first() is AmbientServerEvent.Started)
        assertTrue(overflow.any { it is AmbientServerEvent.Failed && it.code == "buffer" })
        assertEquals(1, overflow.count { it is AmbientServerEvent.Started })
    }

    @Test
    fun closeSettlesSuspendedTransmitWithoutRelease() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var transmittedControl = false
        val link = orderedLink { text ->
            if (text.contains("input_audio")) {
                entered.complete(Unit)
                release.await()
            } else {
                transmittedControl = true
            }
            true
        }
        try {
            val audio = async { runCatching { link.send(AmbientClientEvent.Audio(byteArrayOf(0, 1))) } }
            withTimeout(5_000) { entered.await() }
            val control = async {
                runCatching { link.send(AmbientClientEvent.FunctionOutput("call_1", """{"ok":true}""")) }
            }
            yield()
            val started = System.nanoTime()
            withTimeout(1_000) { link.close() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 800)
            assertEquals(AmbientLinkFailure.STALE, (audio.await().exceptionOrNull() as AmbientLinkException).failure)
            assertEquals(AmbientLinkFailure.STALE, (control.await().exceptionOrNull() as AmbientLinkException).failure)
            assertFalse(transmittedControl)
            assertFalse(release.isCompleted)
        } finally {
            release.complete(Unit)
            link.close()
        }
    }

    @Test
    fun closeSettlesNoncooperativeTransmitWithoutWaiting() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val link = orderedLink {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            true
        }
        try {
            val audio = async { runCatching { link.send(AmbientClientEvent.Audio(byteArrayOf(0, 1))) } }
            withTimeout(5_000) { entered.await() }
            val started = System.nanoTime()
            withTimeout(1_000) { link.close() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 800)
            val settled = withTimeout(1_000) { audio.await() }
            assertEquals(AmbientLinkFailure.STALE, (settled.exceptionOrNull() as AmbientLinkException).failure)
            assertFalse(release.isCompleted)
        } finally {
            release.complete(Unit)
            link.close()
        }
    }

    @Test
    fun thrownTransmitSettlesSendersAndRejectsLaterSends() = runBlocking {
        var calls = 0
        val link = orderedLink {
            calls += 1
            throw IllegalStateException("http://secret.example/body")
        }
        try {
            val failed = runCatching { link.send(AmbientClientEvent.Mute) }
            val error = failed.exceptionOrNull() as AmbientLinkException
            assertEquals(AmbientLinkFailure.SOCKET, error.failure)
            assertFalse(error.toString().contains("secret"))
            assertFalse(error.toString().contains("http"))
            val later = runCatching { link.send(AmbientClientEvent.Unmute) }
            assertTrue(later.exceptionOrNull() is AmbientLinkException)
            assertEquals(1, calls)
        } finally {
            link.close()
        }
    }

    @Test
    fun cancelledQueuedControlIsNotTransmitted() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val link = orderedLink { text ->
            seen.add(text)
            if (text.contains("input_audio")) {
                entered.complete(Unit)
                release.await()
            }
            true
        }
        try {
            val audio = async { runCatching { link.send(AmbientClientEvent.Audio(byteArrayOf(0, 1))) } }
            withTimeout(5_000) { entered.await() }
            val control = launch {
                link.send(AmbientClientEvent.FunctionOutput("call_1", """{"ok":true}"""))
            }
            withTimeout(5_000) {
                while (!control.isActive) yield()
                yield()
            }
            control.cancel()
            withTimeout(5_000) { control.join() }
            release.complete(Unit)
            withTimeout(5_000) { audio.await() }
            assertTrue(seen.none { it.contains("function_call_output") })
        } finally {
            release.complete(Unit)
            link.close()
        }
    }

    @Test
    fun functionOutputStaysAheadOfResponseCreate() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val link = orderedLink { text ->
            seen.add(text)
            if (text.contains("input_audio")) {
                entered.complete(Unit)
                release.await()
            }
            true
        }
        try {
            val audio = async { link.send(AmbientClientEvent.Audio(byteArrayOf(0, 1))) }
            withTimeout(5_000) { entered.await() }
            val function = async { link.send(AmbientClientEvent.FunctionOutput("call_1", """{"ok":true}""")) }
            val create = async { link.send(AmbientClientEvent.ContinueResponse) }
            withTimeout(5_000) {
                while (!function.isActive || !create.isActive) yield()
                yield()
            }
            release.complete(Unit)
            withTimeout(5_000) {
                function.await()
                create.await()
                audio.await()
            }
            assertEquals(
                listOf(
                    "session.input_audio.append",
                    "response.item.create",
                    "response.create",
                ),
                seen.map { jsonString(jsonObject(it)?.get("type")) },
            )
        } finally {
            release.complete(Unit)
            link.close()
        }
    }

    @Test
    fun handshakeTimesOutWithoutOpenOrFailure() = runBlocking {
        val fast = client.newBuilder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .writeTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val timed = OkHttpAmbientTransport(fast)
        server.enqueue(MockResponse().setResponseCode(200).setBody(ticketBody()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try {
            val started = System.nanoTime()
            val result = runCatching { timed.open(configuration()) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val error = result.exceptionOrNull() as AmbientLinkException
            assertEquals(AmbientLinkFailure.SOCKET, error.failure)
            assertFalse(error.toString().contains("http"))
            assertFalse(error.toString().contains("ticket"))
            assertTrue(elapsedMs < 2_000)
        } finally {
            fast.dispatcher.cancelAll()
            fast.dispatcher.executorService.shutdown()
            fast.connectionPool.evictAll()
        }
    }

    @Test
    fun incomingAllowsOneReader() = runBlocking {
        val link = orderedLink { true }
        val first = async { runCatching { link.incoming.first() } }
        yield()
        val second = runCatching { link.incoming.first() }
        try {
            assertEquals(AmbientLinkFailure.READER, (second.exceptionOrNull() as AmbientLinkException).failure)
        } finally {
            first.cancel()
            link.close()
        }
    }

    private fun orderedLink(transmit: suspend (String) -> Boolean): AmbientOrderedLink {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return AmbientOrderedLink(
            transmit = transmit,
            inbound = AmbientInbound(),
            scope = scope,
            onEmergencyClose = {},
        )
    }

    private suspend fun drain(inbound: AmbientInbound): List<AmbientServerEvent> {
        val events = mutableListOf<AmbientServerEvent>()
        while (true) {
            events += inbound.take() ?: return events
        }
    }

    private fun configuration(): AmbientConfiguration {
        val base = server.url("/backend").toString().trimEnd('/')
        return AmbientConfiguration(
            portalBaseUrl = base,
            builderToken = "synthetic-builder-token",
            instructions = "front",
            backendInstructions = "back",
            toolsJson = "[]",
        )
    }

    private fun ticketBody(
        ws: String = "/backend?ticket=ticket_1",
        expiry: String = "2099-01-01T00:00:00Z",
    ): String =
        """{"portal_session_id":"portal-session","ticket":"ticket_1","ws_url":"$ws","expiry":"$expiry"}"""
}
