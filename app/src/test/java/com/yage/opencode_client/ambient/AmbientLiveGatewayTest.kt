package com.yage.opencode_client.ambient

import com.yage.opencode_client.ambient.gateway.AmbientLiveGateway
import com.yage.opencode_client.ambient.gateway.AmbientLivePaths
import com.yage.opencode_client.ambient.gateway.AmbientOpenCodeClients
import com.yage.opencode_client.ambient.protocol.AmbientGatewayCall
import com.yage.opencode_client.ambient.protocol.AmbientGatewayExchange
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class AmbientLiveGatewayTest {
    private val server = MockWebServer()
    private val http: OkHttpClient = AmbientOpenCodeClients.create()
    private lateinit var repository: OpenCodeRepository

    @Before
    fun setup() {
        server.start()
        repository = OpenCodeRepository()
        repository.configure(base(), "synthetic-user", "synthetic-pass")
    }

    @After
    fun tearDown() {
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun openCodeClientHasNoRedirectCacheLoggingOrAuthenticator() {
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
        assertNull(http.cache)
        assertEquals(okhttp3.Authenticator.NONE, http.authenticator)
        assertTrue(http.interceptors.none { it is HttpLoggingInterceptor })
        assertTrue(http.networkInterceptors.isEmpty())
    }

    @Test
    fun httpStatusHeadersAndBodyStayDistinguishable() = runBlocking {
        val gateway = gateway()
        val token = arm(gateway)
        for (code in listOf(401, 404, 500)) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setHeader("X-Case", "c$code")
                    .setBody("body-$code"),
            )
            val done = gateway.exchange(token, AmbientGatewayCall("GET", "/provider")) as AmbientGatewayExchange.Completed
            assertEquals(code, done.http.code)
            assertEquals("c$code", done.http.header("X-Case"))
            assertEquals("body-$code", done.http.body)
            assertBasic(server.takeRequest())
        }
        server.enqueue(MockResponse().setResponseCode(204).setHeader("X-Case", "c204"))
        val noContent = gateway.exchange(token, promptCall()) as AmbientGatewayExchange.Completed
        assertEquals(204, noContent.http.code)
        assertEquals("c204", noContent.http.header("X-Case"))
        assertEquals("", noContent.http.body)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun experimentalPageKeepsCursorHeader() = runBlocking {
        val gateway = gateway()
        val token = arm(gateway)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("x-next-cursor", "cur-2").setBody("[]"))
        val done = gateway.exchange(
            token,
            AmbientGatewayCall(
                "GET",
                "/experimental/session",
                mapOf("roots" to "true", "archived" to "true", "limit" to "50", "cursor" to "cur-1"),
            ),
        ) as AmbientGatewayExchange.Completed
        assertEquals(200, done.http.code)
        assertEquals("cur-2", done.http.header("x-next-cursor"))
        val recorded = server.takeRequest()
        assertEquals("/experimental/session", recorded.requestUrl?.encodedPath)
        assertEquals("cur-1", recorded.requestUrl?.queryParameter("cursor"))
        assertEquals("true", recorded.requestUrl?.queryParameter("roots"))
        assertEquals("true", recorded.requestUrl?.queryParameter("archived"))
        assertBasic(recorded)
    }

    @Test
    fun trustedToolPathsUseCapturedBasicOnTheConfiguredHost() = runBlocking {
        val gateway = gateway()
        val token = arm(gateway)
        val cases = listOf(
            AmbientGatewayCall("GET", "/provider"),
            AmbientGatewayCall("GET", "/config/providers"),
            AmbientGatewayCall("GET", "/experimental/session", mapOf("roots" to "true", "archived" to "true", "limit" to "50")),
            AmbientGatewayCall("GET", "/session/ses_1"),
            AmbientGatewayCall("POST", "/session", emptyMap(), "{}"),
            AmbientGatewayCall("GET", "/session/ses_1/children"),
            AmbientGatewayCall("GET", "/session/status", mapOf("directory" to "/work/space")),
            AmbientGatewayCall("GET", "/session/ses_1/message", mapOf("limit" to "200")),
            AmbientGatewayCall("POST", "/session/ses_1/prompt_async", emptyMap(), "{}"),
            AmbientGatewayCall("POST", "/session/ses_1/abort"),
            AmbientGatewayCall("GET", "/permission"),
            AmbientGatewayCall("POST", "/session/ses_1/permissions/per_1", emptyMap(), """{"response":"once"}"""),
            AmbientGatewayCall("GET", "/question"),
            AmbientGatewayCall("POST", "/question/que_1/reply", emptyMap(), """{"answers":[[]]}"""),
            AmbientGatewayCall("PATCH", "/session/ses_1", emptyMap(), """{"time":{"archived":0}}"""),
        )
        for (call in cases) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val done = gateway.exchange(token, call) as AmbientGatewayExchange.Completed
            assertEquals(200, done.http.code)
            val recorded = server.takeRequest()
            assertEquals(call.method, recorded.method)
            assertEquals(AmbientLivePaths.normalize(call.path), recorded.requestUrl?.encodedPath?.trimStart('/'))
            assertEquals(server.url("/").host, recorded.requestUrl?.host)
            assertBasic(recorded)
            if (call.method == "PATCH") {
                assertEquals("""{"time":{"archived":0}}""", recorded.body.readUtf8())
            }
        }
    }

    @Test
    fun defaultDirectoryIsOmittedAndOverrideStaysOnTheCapturedHost() = runBlocking {
        val gateway = gateway()
        val token = arm(gateway, directory = null)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        gateway.exchange(token, AmbientGatewayCall("POST", "/session", mapOf("directory" to "  "), "{}"))
        val created = server.takeRequest()
        assertEquals("/session", created.requestUrl?.encodedPath)
        assertNull(created.requestUrl?.queryParameter("directory"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        gateway.exchange(
            token,
            AmbientGatewayCall("POST", "/session", mapOf("directory" to "/work/space"), "{}"),
        )
        val override = server.takeRequest()
        assertEquals("/work/space", override.requestUrl?.queryParameter("directory"))
        assertEquals(server.url("/").host, override.requestUrl?.host)
        assertBasic(override)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val body = """{"baseUrl":"https://evil.example","username":"other","password":"other"}"""
        gateway.exchange(token, AmbientGatewayCall("POST", "/session", emptyMap(), body))
        val smuggled = server.takeRequest()
        assertEquals(server.url("/").host, smuggled.requestUrl?.host)
        assertEquals(body, smuggled.body.readUtf8())
        assertBasic(smuggled)
        assertFalse(smuggled.getHeader("Authorization")!!.startsWith("Bearer"))
    }

    @Test
    fun functionPathAndQueryCannotSelectAnotherAuthority() = runBlocking {
        val gateway = gateway()
        val token = arm(gateway)
        val rejected = listOf(
            AmbientGatewayCall("GET", "https://evil.example/provider"),
            AmbientGatewayCall("GET", "//evil.example/provider"),
            AmbientGatewayCall("GET", "/session/../provider"),
            AmbientGatewayCall("GET", "/session/ses_1?next=https://evil.example"),
            AmbientGatewayCall("GET", "/provider", mapOf("url" to "https://evil.example")),
            AmbientGatewayCall("POST", "http://evil.example/session", emptyMap(), "{}"),
        )
        for (call in rejected) {
            val result = gateway.exchange(token, call)
            assertTrue(result is AmbientGatewayExchange.TransportFailed)
            assertEquals("rejected", (result as AmbientGatewayExchange.TransportFailed).reason)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sameHostConfigureInvalidatesTheActiveToken() = runBlocking {
        val gateway = gateway()
        val token = arm(gateway)
        assertTrue(gateway.gate.authorize(token) != null)
        repository.configure(base(), "synthetic-user", "synthetic-pass")
        assertNull(gateway.gate.authorize(token))
        assertFalse(gateway.retire(token))
    }

    @Test
    fun queuedPatchAndPromptDoNotReachTheNewHost() = runBlocking {
        for (call in listOf(patchCall(), promptCall())) {
            val hold = Hold()
            val gateway = holding(hold)
            repository.configure(base(), "old-user", "old-pass")
            val token = arm(gateway)
            val pending = async { gateway.exchange(token, call) }
            withTimeout(5_000) { hold.entered.await() }
            repository.configure("http://127.0.0.1:9", "new-user", "new-pass")
            hold.release.complete(Unit)
            assertTrue(withTimeout(5_000) { pending.await() } is AmbientGatewayExchange.StaleLease)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sameHostRearmRejectsTheQueuedWriteAndUsesNewBasic() = runBlocking {
        val hold = Hold()
        val gateway = holding(hold)
        repository.configure(base(), "user-a", "pass-a")
        val old = arm(gateway)
        val pending = async { gateway.exchange(old, promptCall()) }
        withTimeout(5_000) { hold.entered.await() }
        repository.configure(base(), "user-b", "pass-b")
        val newer = gateway.arm("host-a", 8L, "/synthetic", 4L, "owner-b")
        assertNull(gateway.gate.authorize(old))
        hold.release.complete(Unit)
        assertTrue(withTimeout(5_000) { pending.await() } is AmbientGatewayExchange.StaleLease)
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(204))
        val done = gateway.exchange(newer, promptCall()) as AmbientGatewayExchange.Completed
        assertEquals(204, done.http.code)
        val recorded = server.takeRequest()
        assertEquals(AmbientLiveGateway.basic("user-b", "pass-b"), recorded.getHeader("Authorization"))
        assertFalse(recorded.getHeader("Authorization")!!.startsWith("Bearer"))
    }

    @Test
    fun retireDoesNotRevokeANewerRun() = runBlocking {
        val gateway = gateway()
        val first = arm(gateway)
        val second = gateway.arm("host-a", 8L, "/synthetic", 4L, "owner-b")
        assertFalse(gateway.retire(first))
        assertTrue(gateway.gate.authorize(second) != null)
        server.enqueue(MockResponse().setResponseCode(204))
        val done = gateway.exchange(second, promptCall()) as AmbientGatewayExchange.Completed
        assertEquals(204, done.http.code)
        assertEquals(
            AmbientLiveGateway.basic("synthetic-user", "synthetic-pass"),
            server.takeRequest().getHeader("Authorization"),
        )
        assertTrue(gateway.retire(second))
        assertFalse(gateway.retire(first))
        assertNull(gateway.gate.authorize(second))
    }

    @Test
    fun delayedRetireRejectsTheQueuedWrite() = runBlocking {
        val hold = Hold()
        val gateway = holding(hold)
        val token = arm(gateway)
        val pending = async { gateway.exchange(token, patchCall()) }
        withTimeout(5_000) { hold.entered.await() }
        assertTrue(gateway.retire(token))
        val newer = gateway.arm("host-a", 8L, "/synthetic", 4L, "owner-b")
        hold.release.complete(Unit)
        assertTrue(withTimeout(5_000) { pending.await() } is AmbientGatewayExchange.StaleLease)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(204))
        val done = gateway.exchange(newer, promptCall()) as AmbientGatewayExchange.Completed
        assertEquals(204, done.http.code)
    }

    @Test
    fun dispatchedOldHostResultIsDiscarded() = runBlocking {
        val gateway = gateway()
        repository.configure(base(), "old-user", "old-pass")
        val token = arm(gateway)
        val pending = async { gateway.exchange(token, promptCall()) }
        val recorded = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertEquals(AmbientLiveGateway.basic("old-user", "old-pass"), recorded!!.getHeader("Authorization"))
        repository.configure("http://127.0.0.1:9", "new-user", "new-pass")
        server.enqueue(MockResponse().setResponseCode(200).setBody("old-host-body"))
        val result = withTimeout(5_000) { pending.await() }
        assertTrue(result is AmbientGatewayExchange.StaleLease)
    }

    @Test
    fun queuedOkHttpCallsDoNotReachOldOrNewHost() = runBlocking {
        val dispatcher = Dispatcher()
        dispatcher.maxRequests = 1
        dispatcher.maxRequestsPerHost = 1
        val client = AmbientOpenCodeClients.create().newBuilder().dispatcher(dispatcher).build()
        try {
            for (mode in listOf("configure", "retire")) {
                val before = server.requestCount
                repository.configure(base(), "old-user", "old-pass")
                val gateway = AmbientLiveGateway(repository, baseClient = client)
                val token = arm(gateway)
                client.newCall(Request.Builder().url(server.url("/hold")).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = Unit

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        response.close()
                    }
                })
                assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
                val patch = async { gateway.exchange(token, patchCall()) }
                val prompt = async { gateway.exchange(token, promptCall()) }
                withTimeout(5_000) {
                    while (dispatcher.queuedCallsCount() < 2) yield()
                }
                if (mode == "configure") {
                    repository.configure("http://127.0.0.1:9", "new-user", "new-pass")
                } else {
                    assertTrue(gateway.retire(token))
                }
                server.enqueue(MockResponse().setResponseCode(204).setBody("hold"))
                assertTrue(withTimeout(5_000) { patch.await() } is AmbientGatewayExchange.StaleLease)
                assertTrue(withTimeout(5_000) { prompt.await() } is AmbientGatewayExchange.StaleLease)
                assertEquals(before + 1, server.requestCount)
            }
        } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun networkFailureUsesFixedTransportReason() = runBlocking {
        val fast = http.newBuilder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        repository.configure("http://127.0.0.1:1", "synthetic-user", "synthetic-pass")
        val gateway = AmbientLiveGateway(repository, baseClient = fast)
        val token = arm(gateway)
        val result = gateway.exchange(token, AmbientGatewayCall("GET", "/provider"))
        assertTrue(result is AmbientGatewayExchange.TransportFailed)
        assertEquals("transport", (result as AmbientGatewayExchange.TransportFailed).reason)
        fast.dispatcher.executorService.shutdown()
        fast.connectionPool.evictAll()
    }

    private fun gateway(): AmbientLiveGateway = AmbientLiveGateway(repository, baseClient = http)

    private fun holding(hold: Hold): AmbientLiveGateway = AmbientLiveGateway(
        repository,
        baseClient = http,
        beforeDispatch = {
            hold.entered.complete(Unit)
            hold.release.await()
        },
    )

    private fun arm(gateway: AmbientLiveGateway, directory: String? = "/synthetic"): Long =
        gateway.arm("host-a", 7L, directory, 3L, "owner-a")

    private fun base(): String = server.url("/").toString().trimEnd('/')

    private fun patchCall() = AmbientGatewayCall(
        "PATCH",
        "/session/ses_1",
        jsonBody = """{"time":{"archived":0}}""",
    )

    private fun promptCall() = AmbientGatewayCall(
        "POST",
        "/session/ses_1/prompt_async",
        jsonBody = """{"messageID":"msg_1","parts":[{"type":"text","text":"hello"}]}""",
    )

    private fun assertBasic(recorded: RecordedRequest) {
        assertEquals(AmbientLiveGateway.basic("synthetic-user", "synthetic-pass"), recorded.getHeader("Authorization"))
        assertFalse(recorded.getHeader("Authorization")!!.startsWith("Bearer"))
    }

    private class Hold(
        val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )
}
