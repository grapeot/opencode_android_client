package com.yage.opencode_client

import com.yage.opencode_client.data.model.ConfigProvider
import com.yage.opencode_client.data.model.AgentInfo
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.ProviderModel
import com.yage.opencode_client.data.model.ProvidersResponse
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenCodeRepositoryTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Before
    fun setup() = runBlocking {
        server.start()
        repository = OpenCodeRepository()
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `default server URL is localhost`() {
        assertEquals(
            "http://localhost:4096",
            OpenCodeRepository.DEFAULT_SERVER
        )
    }

    @Test
    fun `checkHealth returns success`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"healthy": true, "version": "1.0.0"}""")
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.checkHealth()

        assertTrue(result.isSuccess)
        val health = result.getOrThrow()
        assertTrue(health.healthy)
        assertEquals("1.0.0", health.version)
    }

    @Test
    fun `checkHealth handles network error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.checkHealth()

        assertTrue(result.isFailure)
    }

    @Test
    fun `getSessions returns list`() = runBlocking {
        val sessions = listOf(
            Session(id = "s1", directory = "/project", title = "Test")
        )
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(sessions))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.getSessions()

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("s1", list[0].id)
        assertEquals("/project", list[0].directory)
    }

    @Test
    fun `getAgents returns list`() = runBlocking {
        val agents = listOf(
            AgentInfo(
                name = "Build",
                mode = "primary",
                hidden = false
            )
        )
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(agents))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.getAgents()

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Build", list[0].name)
    }

    @Test
    fun `sendMessage sends prompt body with auth header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret"
        )

        val result = repository.sendMessage(
            sessionId = "session-1",
            text = "hello repo",
            agent = "review",
            model = Message.ModelInfo(providerId = "openai", modelId = "gpt-4")
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/prompt_async", request.path)
        assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"agent\":\"review\""))
        assertTrue(body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"text\":\"hello repo\""))
        assertTrue(body.contains("\"providerID\":\"openai\""))
        assertTrue(body.contains("\"modelID\":\"gpt-4\""))
    }

    @Test
    fun `sendMessage omits null model from payload`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = repository.sendMessage(
            sessionId = "session-1",
            text = "hello without model",
            agent = "build",
            model = null
        )

        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"text\":\"hello without model\""))
        assertFalse(body.contains("\"model\":null"))
    }

    @Test
    fun `sendMessage surfaces status code and server error body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("bad request body")
        )

        val result = repository.sendMessage(sessionId = "session-1", text = "hello")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("400"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("bad request body"))
    }

    @Test
    fun `getFileContent parses text response and sends path query`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(FileContent(type = "text", content = "# Hello")))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.getFileContent("docs/README.md")

        assertTrue(result.isSuccess)
        assertEquals("# Hello", result.getOrThrow().content)
        assertEquals("/file/content?path=docs%2FREADME.md", server.takeRequest().path)
    }

    @Test
    fun `getProviders parses default provider mapping`() = runBlocking {
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf("gpt-4" to ProviderModel(id = "gpt-4", name = "GPT-4"))
                )
            ),
            defaultByProvider = mapOf("openai" to "gpt-4")
        )
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(providers))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.getProviders()

        assertTrue(result.isSuccess)
        assertEquals("openai", result.getOrThrow().default?.providerId)
        assertEquals("gpt-4", result.getOrThrow().default?.modelId)
    }

    @Test
    fun `configure rebuilds clients for new base url and credentials`() = runBlocking {
        val replacementServer = MockWebServer()
        replacementServer.start()
        try {
            repository.configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                username = "old",
                password = "creds"
            )
            repository.configure(
                baseUrl = replacementServer.url("/").toString().trimEnd('/'),
                username = "new",
                password = "secret"
            )
            replacementServer.enqueue(
                MockResponse()
                    .setBody("""{"healthy": true, "version": "2.0.0"}""")
                    .setHeader("Content-Type", "application/json")
            )

            val result = repository.checkHealth()

            assertTrue(result.isSuccess)
            assertEquals("2.0.0", result.getOrThrow().version)
            assertEquals(0, server.requestCount)
            assertEquals("Basic bmV3OnNlY3JldA==", replacementServer.takeRequest().getHeader("Authorization"))
        } finally {
            replacementServer.shutdown()
        }
    }

    @Test
    fun `configure is thread-safe under concurrent calls`() = runBlocking {
        val replacementServer = MockWebServer()
        replacementServer.start()
        val mutex = Mutex()
        var maxConcurrent = 0
        var activeCount = 0

        try {
            replacementServer.enqueue(
                MockResponse()
                    .setBody("""{"healthy": true, "version": "3.0.0"}""")
                    .setHeader("Content-Type", "application/json")
            )

            val jobs = List(10) {
                launch {
                    mutex.withLock { activeCount++ }
                    maxConcurrent = maxOf(maxConcurrent, activeCount)
                    repository.configure(
                        baseUrl = replacementServer.url("/").toString().trimEnd('/'),
                        username = "user$it",
                        password = "pass$it"
                    )
                    activeCount--
                }
            }
            jobs.forEach { it.join() }

            val result = repository.checkHealth()
            assertTrue(result.isSuccess)
            assertEquals("3.0.0", result.getOrThrow().version)
        } finally {
            replacementServer.shutdown()
        }
    }
}
