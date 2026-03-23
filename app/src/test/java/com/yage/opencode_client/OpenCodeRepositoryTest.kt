package com.yage.opencode_client

import com.yage.opencode_client.data.model.ConfigProvider
import com.yage.opencode_client.data.model.AgentInfo
import com.yage.opencode_client.data.model.FileDiff
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.model.FileNode
import com.yage.opencode_client.data.model.FileStatusEntry
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.PermissionResponse
import com.yage.opencode_client.data.model.ProviderModel
import com.yage.opencode_client.data.model.ProvidersResponse
import com.yage.opencode_client.data.model.QuestionInfo
import com.yage.opencode_client.data.model.QuestionOption
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.model.TodoItem
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

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setBody(body)
            .setHeader("Content-Type", "application/json")

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
    fun `createSession returns session and sends title body`() = runBlocking {
        val session = Session(id = "session-1", directory = "/project", title = "New chat")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.createSession("New chat")

        assertTrue(result.isSuccess)
        assertEquals("session-1", result.getOrThrow().id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session", request.path)
        assertEquals("{\"title\":\"New chat\"}", request.body.readUtf8())
    }

    @Test
    fun `createSession fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.createSession("Broken")

        assertTrue(result.isFailure)
    }

    @Test
    fun `updateSession returns updated session and sends patch body`() = runBlocking {
        val session = Session(id = "session-1", directory = "/project", title = "Renamed")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.updateSession("session-1", "Renamed")

        assertTrue(result.isSuccess)
        assertEquals("Renamed", result.getOrThrow().title)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/session/session-1", request.path)
        assertEquals("{\"title\":\"Renamed\"}", request.body.readUtf8())
    }

    @Test
    fun `updateSession fails on bad request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = repository.updateSession("session-1", "Renamed")

        assertTrue(result.isFailure)
    }

    @Test
    fun `deleteSession returns success and sends delete request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.deleteSession("session-1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/session/session-1", request.path)
    }

    @Test
    fun `getSessionStatus returns map`() = runBlocking {
        val statuses = mapOf(
            "session-1" to SessionStatus(type = "busy", attempt = 2, message = "Running", next = 123L)
        )
        server.enqueue(jsonResponse(json.encodeToString(statuses)))

        val result = repository.getSessionStatus()

        assertTrue(result.isSuccess)
        val status = result.getOrThrow().getValue("session-1")
        assertEquals("busy", status.type)
        assertEquals(2, status.attempt)
        assertEquals("Running", status.message)
    }

    @Test
    fun `getSessionStatus fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getSessionStatus()

        assertTrue(result.isFailure)
    }

    @Test
    fun `getMessages returns message list and forwards limit`() = runBlocking {
        val messages = listOf(
            MessageWithParts(
                info = Message(id = "msg-1", sessionId = "session-1", role = "assistant"),
                parts = listOf(Part(id = "part-1", type = "text", text = "Hello"))
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(messages)))

        val result = repository.getMessages("session-1", limit = 30)

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("msg-1", list[0].info.id)
        assertEquals("Hello", list[0].parts.single().text)
        assertEquals("/session/session-1/message?limit=30", server.takeRequest().path)
    }

    @Test
    fun `getMessages fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getMessages("session-1")

        assertTrue(result.isFailure)
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
    fun `abortSession returns success and sends abort request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = repository.abortSession("session-1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/abort", request.path)
    }

    @Test
    fun `forkSession returns forked session and sends message id`() = runBlocking {
        val session = Session(id = "fork-1", directory = "/project", parentId = "session-1", title = "Fork")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.forkSession("session-1", "msg-42")

        assertTrue(result.isSuccess)
        val forked = result.getOrThrow()
        assertEquals("fork-1", forked.id)
        assertEquals("session-1", forked.parentId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/fork", request.path)
        assertEquals("{\"messageID\":\"msg-42\"}", request.body.readUtf8())
    }

    @Test
    fun `forkSession fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.forkSession("session-1", "msg-42")

        assertTrue(result.isFailure)
    }

    @Test
    fun `getPendingPermissions returns permission list`() = runBlocking {
        val permissions = listOf(
            PermissionRequest(
                id = "perm-1",
                sessionId = "session-1",
                permission = "write",
                patterns = listOf("app/src/**"),
                metadata = PermissionRequest.Metadata(filepath = "app/src/Main.kt", parentDir = "app/src")
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(permissions)))

        val result = repository.getPendingPermissions()

        assertTrue(result.isSuccess)
        val permission = result.getOrThrow().single()
        assertEquals("perm-1", permission.id)
        assertEquals("write", permission.permission)
        assertEquals("/permission", server.takeRequest().path)
    }

    @Test
    fun `getPendingPermissions returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getPendingPermissions()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `respondPermission returns success and sends response body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/permissions/perm-1", request.path)
        assertEquals("{\"response\":\"always\"}", request.body.readUtf8())
    }

    @Test
    fun `getPendingQuestions returns question list`() = runBlocking {
        val questions = listOf(
            QuestionRequest(
                id = "question-1",
                sessionId = "session-1",
                questions = listOf(
                    QuestionInfo(
                        question = "Which file?",
                        header = "Choose target",
                        options = listOf(QuestionOption(label = "A", description = "Option A")),
                        multiple = false,
                        custom = true
                    )
                )
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(questions)))

        val result = repository.getPendingQuestions()

        assertTrue(result.isSuccess)
        val question = result.getOrThrow().single()
        assertEquals("question-1", question.id)
        assertEquals("Which file?", question.questions.single().question)
        assertEquals("/question", server.takeRequest().path)
    }

    @Test
    fun `getPendingQuestions returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getPendingQuestions()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `replyQuestion returns success and sends answers body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.replyQuestion("question-1", listOf(listOf("A"), listOf("custom", "value")))

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/question/question-1/reply", request.path)
        assertEquals("{\"answers\":[[\"A\"],[\"custom\",\"value\"]]}", request.body.readUtf8())
    }

    @Test
    fun `replyQuestion surfaces status code and error body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("reply failed")
        )

        val result = repository.replyQuestion("question-1", listOf(listOf("A")))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("reply failed"))
    }

    @Test
    fun `rejectQuestion returns success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.rejectQuestion("question-1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/question/question-1/reject", request.path)
    }

    @Test
    fun `rejectQuestion surfaces status code and error body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("cannot reject")
        )

        val result = repository.rejectQuestion("question-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("400"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("cannot reject"))
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
    fun `getSessionDiff returns file diff list`() = runBlocking {
        val diffs = listOf(
            FileDiff(filePath = "app/src/Main.kt", additions = 10, deletions = 2, status = "modified")
        )
        server.enqueue(jsonResponse(json.encodeToString(diffs)))

        val result = repository.getSessionDiff("session-1")

        assertTrue(result.isSuccess)
        val diff = result.getOrThrow().single()
        assertEquals("app/src/Main.kt", diff.file)
        assertEquals(10, diff.additions)
        assertEquals("/session/session-1/diff", server.takeRequest().path)
    }

    @Test
    fun `getSessionDiff returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSessionDiff("session-1")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getSessionTodos returns todo list`() = runBlocking {
        val todos = listOf(
            TodoItem(content = "Write tests", status = "pending", priority = "high", id = "todo-1")
        )
        server.enqueue(jsonResponse(json.encodeToString(todos)))

        val result = repository.getSessionTodos("session-1")

        assertTrue(result.isSuccess)
        val todo = result.getOrThrow().single()
        assertEquals("Write tests", todo.content)
        assertEquals("high", todo.priority)
        assertEquals("/session/session-1/todo", server.takeRequest().path)
    }

    @Test
    fun `getSessionTodos returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSessionTodos("session-1")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getFileTree returns file nodes and sends path query`() = runBlocking {
        val nodes = listOf(
            FileNode(name = "src", path = "app/src", type = "directory"),
            FileNode(name = "Main.kt", path = "app/src/Main.kt", type = "file")
        )
        server.enqueue(jsonResponse(json.encodeToString(nodes)))

        val result = repository.getFileTree("app/src")

        assertTrue(result.isSuccess)
        val tree = result.getOrThrow()
        assertEquals(2, tree.size)
        assertEquals("app/src/Main.kt", tree[1].path)
        assertEquals("/file?path=app%2Fsrc", server.takeRequest().path)
    }

    @Test
    fun `getFileTree returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getFileTree("missing")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getFileStatus returns file status list`() = runBlocking {
        val statuses = listOf(
            FileStatusEntry(path = "app/src/Main.kt", status = "M"),
            FileStatusEntry(path = "README.md", status = "??")
        )
        server.enqueue(jsonResponse(json.encodeToString(statuses)))

        val result = repository.getFileStatus()

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(2, list.size)
        assertEquals("M", list[0].status)
        assertEquals("/file/status", server.takeRequest().path)
    }

    @Test
    fun `getFileStatus fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getFileStatus()

        assertTrue(result.isFailure)
    }

    @Test
    fun `findFile returns matches and forwards query params`() = runBlocking {
        val matches = listOf("app/src/Main.kt", "app/src/test/MainTest.kt")
        server.enqueue(jsonResponse(json.encodeToString(matches)))

        val result = repository.findFile(query = "Main", limit = 2)

        assertTrue(result.isSuccess)
        assertEquals(matches, result.getOrThrow())
        assertEquals("/find/file?query=Main&limit=2", server.takeRequest().path)
    }

    @Test
    fun `findFile fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.findFile("Main")

        assertTrue(result.isFailure)
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
