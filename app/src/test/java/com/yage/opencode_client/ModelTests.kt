package com.yage.opencode_client

import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.data.api.PromptRequest
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.util.migrateLegacyModelIndex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

private fun modelOption(displayName: String) =
    AppState.ModelOption(displayName = displayName, providerId = "test", modelId = "test")

class ModelTests {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun `Session serialization and deserialization`() {
        val session = Session(
            id = "test-id",
            slug = "test-slug",
            projectId = "project-1",
            directory = "/home/user/project",
            parentId = null,
            title = "Test Session",
            version = "1.0",
            time = Session.TimeInfo(created = 1000L, updated = 2000L),
            share = null,
            summary = null
        )
        
        val encoded = json.encodeToString(session)
        val decoded = json.decodeFromString<Session>(encoded)
        
        assertEquals(session.id, decoded.id)
        assertEquals(session.directory, decoded.directory)
        assertEquals(session.title, decoded.title)
    }

    @Test
    fun `Session decodes revert metadata`() {
        val decoded = json.decodeFromString<Session>(
            """
            {
              "id": "s1",
              "directory": "/tmp/project",
              "revert": {
                "messageID": "msg-2",
                "partID": "part-1",
                "snapshot": "snap",
                "diff": "diff"
              }
            }
            """.trimIndent()
        )

        assertEquals("msg-2", decoded.revert?.messageId)
        assertEquals("part-1", decoded.revert?.partId)
        assertEquals("snap", decoded.revert?.snapshot)
        assertEquals("diff", decoded.revert?.diff)
    }

    @Test
    fun `Session displayName prefers title then directory last segment then id`() {
        assertEquals("My Session", Session(id = "s1", directory = "/a/b", title = "My Session").displayName)
        assertEquals("project", Session(id = "s2", directory = "/home/user/project", title = null).displayName)
        assertEquals("s3", Session(id = "s3", directory = "", title = null).displayName)
    }

    @Test
    fun `SessionStatus type checks`() {
        val idle = SessionStatus(type = "idle")
        assertTrue(idle.isIdle)
        assertFalse(idle.isBusy)
        
        val busy = SessionStatus(type = "busy")
        assertTrue(busy.isBusy)
        assertFalse(busy.isIdle)
        
        val retry = SessionStatus(type = "retry", attempt = 1, message = "Retrying...")
        assertTrue(retry.isRetry)
    }

    @Test
    fun `Message role checks`() {
        val userMessage = Message(
            id = "msg-1",
            role = "user",
            sessionId = "session-1"
        )
        assertTrue(userMessage.isUser)
        assertFalse(userMessage.isAssistant)
        
        val assistantMessage = Message(
            id = "msg-2",
            role = "assistant",
            sessionId = "session-1"
        )
        assertTrue(assistantMessage.isAssistant)
        assertFalse(assistantMessage.isUser)
    }

    @Test
    fun `Message resolvedModel returns correct model`() {
        val messageWithModel = Message(
            id = "msg-1",
            role = "assistant",
            model = Message.ModelInfo(providerId = "openai", modelId = "gpt-4")
        )
        assertNotNull(messageWithModel.resolvedModel)
        assertEquals("openai", messageWithModel.resolvedModel?.providerId)
        assertEquals("gpt-4", messageWithModel.resolvedModel?.modelId)
        
        val messageWithTopLevel = Message(
            id = "msg-2",
            role = "assistant",
            providerId = "anthropic",
            modelId = "claude-3"
        )
        assertNotNull(messageWithTopLevel.resolvedModel)
        assertEquals("anthropic", messageWithTopLevel.resolvedModel?.providerId)
        assertEquals("claude-3", messageWithTopLevel.resolvedModel?.modelId)
    }

    @Test
    fun `Part type checks`() {
        val textPart = Part(id = "p1", type = "text", text = "Hello")
        assertTrue(textPart.isText)
        assertFalse(textPart.isTool)
        
        val toolPart = Part(id = "p2", type = "tool", tool = "bash")
        assertTrue(toolPart.isTool)
        
        val reasoningPart = Part(id = "p3", type = "reasoning")
        assertTrue(reasoningPart.isReasoning)
        
        val patchPart = Part(id = "p4", type = "patch")
        assertTrue(patchPart.isPatch)
    }

    @Test
    fun `Part decodes image file attachments`() {
        val part = json.decodeFromString<Part>(
            """
            {
              "id": "p-file",
              "type": "file",
              "mime": "image/jpeg",
              "filename": "photo.jpg",
              "url": "data:image/jpeg;base64,abc123",
              "source": "attachment"
            }
            """.trimIndent()
        )

        assertTrue(part.isFile)
        assertTrue(part.isImageAttachment)
        assertEquals("photo.jpg", part.filename)
        assertEquals("data:image/jpeg;base64,abc123", part.url)
    }

    @Test
    fun `PromptRequest serializes mixed text and image file parts`() {
        val request = PromptRequest(
            parts = listOf(
                PromptRequest.PartInput(type = "text", text = "describe this"),
                PromptRequest.PartInput(
                    type = "file",
                    mime = "image/jpeg",
                    filename = "photo.jpg",
                    url = "data:image/jpeg;base64,abc123"
                )
            ),
            agent = "build"
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("\"type\":\"file\""))
        assertTrue(encoded.contains("\"mime\":\"image/jpeg\""))
        assertTrue(encoded.contains("\"url\":\"data:image/jpeg;base64,abc123\""))
    }

    @Test
    fun `Part filePathsForNavigation from files`() {
        val part = Part(
            id = "p1",
            type = "patch",
            files = listOf(
                Part.FileChange(path = "src/main.kt"),
                Part.FileChange(path = "app/build.gradle.kts")
            )
        )
        assertEquals(listOf("src/main.kt", "app/build.gradle.kts"), part.filePathsForNavigation)
    }

    @Test
    fun `Part filePathsForNavigation from metadata path`() {
        val part = Part(
            id = "p1",
            type = "patch",
            metadata = PartMetadata(path = "README.md")
        )
        assertEquals(listOf("README.md"), part.filePathsForNavigation)
    }

    @Test
    fun `Part filePathsForNavigationFiltered excludes directories`() {
        val part = Part(
            id = "p1",
            type = "patch",
            files = listOf(
                Part.FileChange(path = "adhoc_jobs"),
                Part.FileChange(path = "src/main.kt"),
                Part.FileChange(path = "README.md"),
                Part.FileChange(path = "ai_builder_courses/circle_context")
            )
        )
        assertEquals(listOf("src/main.kt", "README.md"), part.filePathsForNavigationFiltered)
    }

    @Test
    fun `AgentInfo visibility checks`() {
        val primaryAgent = AgentInfo(
            name = "Sisyphus",
            mode = "primary",
            hidden = false
        )
        assertTrue(primaryAgent.isVisible)
        
        val hiddenAgent = AgentInfo(
            name = "Hidden",
            mode = "primary",
            hidden = true
        )
        assertFalse(hiddenAgent.isVisible)
        
        val subAgent = AgentInfo(
            name = "SubAgent",
            mode = "subagent",
            hidden = false
        )
        assertFalse(subAgent.isVisible)
    }

    @Test
    fun `AgentInfo shortName extraction`() {
        val agent1 = AgentInfo(name = "Sisyphus (Ultraworker)")
        assertEquals("Sisyphus", agent1.shortName)
        
        val agent2 = AgentInfo(name = "Build Agent")
        assertEquals("Build", agent2.shortName)
        
        val agent3 = AgentInfo(name = "Oracle")
        assertEquals("Oracle", agent3.shortName)
    }

    @Test
    fun `TodoItem completion check`() {
        val completed = TodoItem(
            content = "Task 1",
            status = "completed",
            priority = "high",
            id = "todo-1"
        )
        assertTrue(completed.isCompleted)
        
        val pending = TodoItem(
            content = "Task 2",
            status = "pending",
            priority = "medium",
            id = "todo-2"
        )
        assertFalse(pending.isCompleted)
        
        val cancelled = TodoItem(
            content = "Task 3",
            status = "cancelled",
            priority = "low",
            id = "todo-3"
        )
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `FileNode type checks`() {
        val directory = FileNode(
            name = "src",
            path = "/project/src",
            type = "directory"
        )
        assertTrue(directory.isDirectory)
        assertFalse(directory.isFile)
        
        val file = FileNode(
            name = "main.kt",
            path = "/project/src/main.kt",
            type = "file"
        )
        assertTrue(file.isFile)
        assertFalse(file.isDirectory)
    }

    @Test
    fun `ModelOption shortName returns DeepSeek for DeepSeek models`() {
        assertEquals("DeepSeek", modelOption("DeepSeek V3").shortName)
    }

    @Test
    fun `ModelOption shortName returns Haiku for Haiku models`() {
        assertEquals("Haiku", modelOption("Claude 3 Haiku").shortName)
    }

    @Test
    fun `ModelOption shortName returns Gemini for Gemini models`() {
        assertEquals("Gemini", modelOption("Gemini 2.5 Pro").shortName)
    }

    @Test
    fun `ModelOption shortName returns GPT for GPT models`() {
        assertEquals("GPT", modelOption("GPT-4o").shortName)
    }

    @Test
    fun `ModelOption shortName distinguishes GPT Terra Fast`() {
        assertEquals("GPT-TF", modelOption("GPT-5.6 Terra Fast").shortName)
    }

    @Test
    fun `ModelOption shortName returns GPT-L for GPT Luna`() {
        assertEquals("GPT-L", modelOption("GPT-5.6 Luna").shortName)
    }

    @Test
    fun `removed GPT Sol Pro preset indices migrate without changing other slots`() {
        assertEquals(1, migrateLegacyModelIndex(6))
        assertEquals(6, migrateLegacyModelIndex(7))
        assertEquals(2, migrateLegacyModelIndex(2))
    }

    @Test
    fun `ModelOption shortName returns Grok for Grok models`() {
        assertEquals("Grok", modelOption("Grok 3").shortName)
    }

    @Test
    fun `ModelOption shortName returns OGLM-5_2 for Ollama GLM 5_2`() {
        assertEquals("OGLM-5.2", modelOption("Ollama GLM 5.2").shortName)
    }

    @Test
    fun `ModelOption shortName returns first word for unknown models`() {
        assertEquals("DeepSeek", modelOption("DeepSeek V3").shortName)
    }

    @Test
    fun `ModelOption shortName returns empty string for empty displayName`() {
        assertEquals("", modelOption("").shortName)
    }

    @Test
    fun `FileContent text extraction`() {
        val textContent = FileContent(type = "text", content = "Hello World")
        assertTrue(textContent.isText)
        assertEquals("Hello World", textContent.text)
        
        val binaryContent = FileContent(type = "binary", content = null)
        assertFalse(binaryContent.isText)
        assertNull(binaryContent.text)
    }

    @Test
    fun `HealthResponse parsing`() {
        val healthJson = """{"healthy": true, "version": "1.0.0"}"""
        val health = json.decodeFromString<HealthResponse>(healthJson)
        
        assertTrue(health.healthy)
        assertEquals("1.0.0", health.version)
    }

    @Test
    fun `SSEEvent parsing`() {
        val eventJson = """{"directory": "/project", "payload": {"type": "session.created"}}"""
        val event = json.decodeFromString<SSEEvent>(eventJson)
        
        assertEquals("/project", event.directory)
        assertEquals("session.created", event.payload.type)
    }

    @Test
    fun `ProvidersResponse parses API default format Map providerId to modelId`() {
        val apiJson = """
            {"providers":[{"id":"mistral","name":"Mistral","models":{"m1":{"id":"m1","name":"Model 1","providerID":"mistral","limit":{"context":128000}}}}],
            "default":{"anthropic":"claude-sonnet-4-6","openai":"gpt-5.3-codex"}}
        """.trimIndent()
        val resp = json.decodeFromString<ProvidersResponse>(apiJson)
        assertEquals(1, resp.providers.size)
        assertEquals(1, resp.providers[0].models.size)
        assertEquals("Model 1", resp.providers[0].models["m1"]?.name)
        assertNotNull(resp.default)
        assertTrue(resp.default!!.providerId in listOf("anthropic", "openai"))
        assertTrue(resp.default!!.modelId.isNotEmpty())
    }

    @Test
    fun `PermissionResponse values`() {
        assertEquals("once", PermissionResponse.ONCE.value)
        assertEquals("always", PermissionResponse.ALWAYS.value)
        assertEquals("reject", PermissionResponse.REJECT.value)
    }

    @Test
    fun `MessageWithParts parses real API format`() {
        // Minimal JSON matching actual API: GET /session/{id}/message
        // Note: API may return files as ["path1","path2"] (strings) or [{path,...}] (objects)
        val apiJson = """
            [{"info":{"id":"msg_1","role":"assistant","sessionID":"ses_1","parentID":"msg_0",
            "providerID":"anthropic","modelID":"claude-opus-4-6","time":{"created":1772559632705,"completed":1772559683414},
            "finish":"stop","tokens":{"total":100,"input":1,"output":99}},"parts":[
            {"type":"step-start","snapshot":"abc123","id":"prt_1","sessionID":"ses_1","messageID":"msg_1"},
            {"type":"reasoning","text":"Some reasoning content","metadata":{"anthropic":{"signature":"xyz"}},"id":"prt_2","sessionID":"ses_1","messageID":"msg_1"},
            {"type":"text","text":"Hello world","id":"prt_3","sessionID":"ses_1","messageID":"msg_1"},
            {"type":"tool","tool":"bash","id":"prt_4","sessionID":"ses_1","messageID":"msg_1","files":["/path/to/file1","/path/to/file2"]}
            ]}]
        """.trimIndent()
        val list = json.decodeFromString<List<MessageWithParts>>(apiJson)
        assertEquals(1, list.size)
        val mwp = list[0]
        assertEquals("msg_1", mwp.info.id)
        assertEquals("assistant", mwp.info.role)
        assertEquals("ses_1", mwp.info.sessionId)
        assertEquals(4, mwp.parts.size)
        assertEquals("step-start", mwp.parts[0].type)
        assertEquals("reasoning", mwp.parts[1].type)
        assertEquals("Some reasoning content", mwp.parts[1].text)
        assertEquals("text", mwp.parts[2].type)
        assertEquals("Hello world", mwp.parts[2].text)
        // files as string array (API format)
        val toolPart = mwp.parts[3]
        assertEquals("tool", toolPart.type)
        assertEquals(2, toolPart.files?.size)
        assertEquals("/path/to/file1", toolPart.files!![0].path)
        assertEquals("/path/to/file2", toolPart.files!![1].path)
    }

    @Test
    fun `message display order reverses API chronological order`() {
        // API returns [oldest, ..., newest]; ChatScreen uses reversed() so newest is at bottom
        val apiJson = """
            [
            {"info":{"id":"msg_1","role":"user","sessionID":"ses_1","time":{"created":1000}},"parts":[{"type":"text","text":"first","id":"p1","sessionID":"ses_1","messageID":"msg_1"}]},
            {"info":{"id":"msg_2","role":"assistant","sessionID":"ses_1","time":{"created":2000}},"parts":[{"type":"text","text":"second","id":"p2","sessionID":"ses_1","messageID":"msg_2"}]},
            {"info":{"id":"msg_3","role":"user","sessionID":"ses_1","time":{"created":3000}},"parts":[{"type":"text","text":"third","id":"p3","sessionID":"ses_1","messageID":"msg_3"}]}
            ]
        """.trimIndent()
        val messages = json.decodeFromString<List<MessageWithParts>>(apiJson)
        val displayOrder = messages.reversed()
        assertEquals("msg_3", displayOrder.first().info.id)
        assertEquals("msg_1", displayOrder.last().info.id)
    }

    @Test
    fun `tool part extracts todos from state input`() {
        val apiJson = """
            [{"info":{"id":"msg_1","role":"assistant","sessionID":"ses_1"},"parts":[{
            "type":"tool","id":"prt_1","sessionID":"ses_1","messageID":"msg_1","tool":"plan",
            "state":{"status":"completed","input":{"todos":[
            {"content":"Create implementation plan","status":"completed","priority":"high"},
            {"content":"Delegate to agent","status":"in_progress","priority":"medium"}
            ]}}}
            ]}]
        """.trimIndent()
        val list = json.decodeFromString<List<MessageWithParts>>(apiJson)
        val toolPart = list[0].parts[0]
        assertEquals(2, toolPart.toolTodos.size)
        assertEquals("Create implementation plan", toolPart.toolTodos[0].content)
        assertTrue(toolPart.toolTodos[0].isCompleted)
        assertEquals("Delegate to agent", toolPart.toolTodos[1].content)
        assertFalse(toolPart.toolTodos[1].isCompleted)
    }

    @Test
    fun `MessageWithParts parses files as object array`() {
        val apiJson = """
            [{"info":{"id":"msg_1","role":"assistant","sessionID":"ses_1"},"parts":[
            {"type":"tool","id":"prt_1","sessionID":"ses_1","messageID":"msg_1","files":[{"path":"/a/b","additions":1,"deletions":0}]}
            ]}]
        """.trimIndent()
        val list = json.decodeFromString<List<MessageWithParts>>(apiJson)
        val toolPart = list[0].parts[0]
        assertEquals(1, toolPart.files?.size)
        assertEquals("/a/b", toolPart.files!![0].path)
        assertEquals(1, toolPart.files!![0].additions)
        assertEquals(0, toolPart.files!![0].deletions)
    }

    // ---- Throughput (model footer t/s and Context sheet aggregation) ----

    private fun throughputMessage(
        created: Long?,
        completed: Long?,
        output: Int? = null,
        reasoning: Int? = null,
        partJson: String = "[]"
    ): MessageWithParts {
        val timeJson = when {
            created != null && completed != null -> """"time":{"created":$created,"completed":$completed}"""
            created != null -> """"time":{"created":$created}"""
            completed != null -> """"time":{"completed":$completed}"""
            else -> ""
        }
        val tokensJson = if (output != null || reasoning != null) {
            val o = output?.toString() ?: "null"
            val r = reasoning?.toString() ?: "null"
            ""","tokens":{"output":$o,"reasoning":$r}"""
        } else ""
        val infoJson = """{"id":"msg_1","role":"assistant","sessionID":"ses_1"$timeJson$tokensJson}"""
        return json.decodeFromString(
            """{"info":$infoJson,"parts":$partJson}"""
        )
    }

    @Test
    fun `state time parses int double and missing forms`() {
        val apiJson = """
            [{"info":{"id":"msg_1","role":"assistant","sessionID":"ses_1"},"parts":[
            {"type":"tool","id":"prt_1","sessionID":"ses_1","messageID":"msg_1","tool":"a",
             "state":{"status":"completed","time":{"start":1000,"end":2000}}},
            {"type":"tool","id":"prt_2","sessionID":"ses_1","messageID":"msg_1","tool":"b",
             "state":{"status":"completed","time":{"start":1000.0,"end":3500.5}}},
            {"type":"tool","id":"prt_3","sessionID":"ses_1","messageID":"msg_1","tool":"c",
             "state":{"status":"running"}}
            ]}]
        """.trimIndent()
        val mwp = json.decodeFromString<List<MessageWithParts>>(apiJson)[0]
        assertEquals(1.0, mwp.parts[0].toolRunSeconds!!, 1e-9)
        assertEquals(2.5005, mwp.parts[1].toolRunSeconds!!, 1e-9)
        assertEquals(null, mwp.parts[2].toolRunSeconds)
        // Part with no tool at all contributes nothing.
        assertEquals(3.5005, mwp.toolRunSeconds, 1e-9)
    }

    @Test
    fun `tool time with end before start contributes nothing`() {
        val apiJson = """
            [{"info":{"id":"msg_1","role":"assistant","sessionID":"ses_1"},"parts":[
            {"type":"tool","id":"prt_1","sessionID":"ses_1","messageID":"msg_1","tool":"a",
             "state":{"status":"completed","time":{"start":5000,"end":2000}}},
            {"type":"tool","id":"prt_2","sessionID":"ses_1","messageID":"msg_1","tool":"b",
             "state":{"status":"completed","time":{"start":1000,"end":1000}}}
            ]}]
        """.trimIndent()
        val mwp = json.decodeFromString<List<MessageWithParts>>(apiJson)[0]
        assertEquals(null, mwp.parts[0].toolRunSeconds)
        assertEquals(null, mwp.parts[1].toolRunSeconds)
        assertEquals(0.0, mwp.toolRunSeconds, 1e-9)
    }

    @Test
    fun `throughput excludes tool seconds from step window`() {
        // 56s step, tool ran 48s. 2000 tokens over the adjusted 8s window = 250 t/s;
        // the raw window would read ~36 t/s and a 48s-in-a-56s write would read 9 t/s.
        val created = 1000L
        val completed = created + 56_000L
        val partJson = """
            [{"type":"tool","id":"prt_1","sessionID":"ses_1","messageID":"msg_1","tool":"write",
             "state":{"status":"completed","time":{"start":$created,"end":${created + 48_000L}}}}]
        """.trimIndent()
        val m = throughputMessage(created, completed, output = 2000, partJson = partJson)
        val components = m.throughputComponents()
        assertNotNull(components)
        assertEquals(2000, components!!.generatedTokens)
        assertEquals(8.0, components.effectiveSeconds, 1e-9)
        assertEquals(250.0, components.throughput, 1e-9)
        assertEquals("250 t/s", MessageWithParts.throughputText(components.throughput))
    }

    @Test
    fun `throughput falls back to raw window when tools fill it`() {
        val created = 1000L
        val completed = created + 30_000L
        val partJson = """
            [{"type":"tool","id":"prt_1","sessionID":"ses_1","messageID":"msg_1","tool":"bash",
             "state":{"status":"completed","time":{"start":$created,"end":$completed}}}]
        """.trimIndent()
        val m = throughputMessage(created, completed, output = 300, partJson = partJson)
        val components = m.throughputComponents()
        assertNotNull(components)
        assertEquals(30.0, components!!.effectiveSeconds, 1e-9)
        assertEquals(10.0, components.throughput, 1e-9)
    }

    @Test
    fun `throughput falls back to raw window when tool time exceeds it`() {
        // Tool outlasted the step window (e.g. timing recorded across steps).
        val created = 1000L
        val completed = created + 10_000L
        val partJson = """
            [{"type":"tool","id":"prt_1","sessionID":"ses_1","messageID":"msg_1","tool":"bash",
             "state":{"status":"completed","time":{"start":$created,"end":${created + 12_000L}}}}]
        """.trimIndent()
        val m = throughputMessage(created, completed, output = 100, partJson = partJson)
        val components = m.throughputComponents()
        assertNotNull(components)
        assertEquals(10.0, components!!.effectiveSeconds, 1e-9)
        assertEquals(10.0, components.throughput, 1e-9)
    }

    @Test
    fun `throughput falls back to raw window when no tool timing recorded`() {
        val created = 1000L
        val completed = created + 10_000L
        val m = throughputMessage(created, completed, output = 83)
        val components = m.throughputComponents()
        assertNotNull(components)
        assertEquals(10.0, components!!.effectiveSeconds, 1e-9)
        assertEquals(8.3, components.throughput, 1e-9)
        assertEquals("8.3 t/s", MessageWithParts.throughputText(components.throughput))
    }

    @Test
    fun `throughput is null while streaming or without tokens`() {
        // Streaming: no completed timestamp.
        assertNull(throughputMessage(1000L, null, output = 100).throughputComponents())
        // Completed but zero generated tokens.
        assertNull(throughputMessage(1000L, 2000L, output = 0, reasoning = 0).throughputComponents())
        // Missing created bound with completed present (Android TimeInfo fields
        // are both nullable; iOS treats created as non-null).
        assertNull(throughputMessage(null, 2000L, output = 100).throughputComponents())
    }

    @Test
    fun `reasoning tokens count as generated`() {
        val created = 1000L
        val completed = created + 20_000L
        val m = throughputMessage(created, completed, output = 100, reasoning = 400)
        val components = m.throughputComponents()
        assertNotNull(components)
        assertEquals(500, components!!.generatedTokens)
        assertEquals(25.0, components.throughput, 1e-9)
    }

    @Test
    fun `throughputText formats integer above ten and one decimal below`() {
        assertEquals("10 t/s", MessageWithParts.throughputText(10.0))
        assertEquals("146 t/s", MessageWithParts.throughputText(146.2))
        // 9.96 < 10 -> one decimal, even though it rounds to 10.
        assertEquals("10.0 t/s", MessageWithParts.throughputText(9.96))
        assertEquals("0.8 t/s", MessageWithParts.throughputText(0.8))
    }

    @Test
    fun `throughputStats aggregates raw messages and skips non contributors`() {
        val m1 = throughputMessage(1000L, 11000L, output = 83) // 8.3 t/s over 10s
        val streaming = throughputMessage(2000L, null, output = 5) // skipped: incomplete
        val noTokens = throughputMessage(3000L, 13000L, output = 0, reasoning = 0) // skipped
        val m2 = throughputMessage(4000L, 14000L, output = 100, reasoning = 100) // 20 t/s over 10s
        val userMsg = json.decodeFromString<MessageWithParts>(
            """{"info":{"id":"msg_u","role":"user","sessionID":"ses_1","time":{"created":500}},"parts":[]}"""
        )
        val state = AppState(messages = listOf(m1, streaming, noTokens, m2, userMsg))
        val stats = state.throughputStats
        assertNotNull(stats)
        // 283 generated tokens over 20 seconds (83 from m1, 200 from m2).
        assertEquals(283, stats!!.totalOutputTokens)
        assertEquals(20.0, stats.totalGenerationSeconds!!, 1e-9)
        assertEquals(14.15, stats.averageThroughput!!, 1e-9)
    }

    @Test
    fun `throughputStats mixes tool-adjusted and fallback steps`() {
        // Step 1: 10s window, 4s tool, 120 tokens -> 120/6. Step 2: 10s window,
        // 12s tool (exceeds window, falls back to raw), 100 tokens -> 100/10.
        // Aggregate: 220 tokens over 16 seconds = 13.75 t/s.
        val p1 = """
            [{"type":"tool","id":"prt_1","sessionID":"ses_1","messageID":"msg_1","tool":"edit",
             "state":{"status":"completed","time":{"start":1000,"end":5000}}}]
        """.trimIndent()
        val p2 = """
            [{"type":"tool","id":"prt_2","sessionID":"ses_1","messageID":"msg_2","tool":"write",
             "state":{"status":"completed","time":{"start":11000,"end":23000}}}]
        """.trimIndent()
        val s1 = throughputMessage(1000L, 11000L, output = 120, partJson = p1)
        val s2 = throughputMessage(11000L, 21000L, output = 100, partJson = p2)
        val state = AppState(messages = listOf(s1, s2))
        val stats = state.throughputStats
        assertNotNull(stats)
        assertEquals(220, stats!!.totalOutputTokens)
        assertEquals(16.0, stats.totalGenerationSeconds!!, 1e-9)
        assertEquals(13.75, stats.averageThroughput!!, 1e-9)
    }

    @Test
    fun `throughputStats is null when nothing contributes`() {
        val state = AppState(
            messages = listOf(
                throughputMessage(1000L, 2000L, output = 0),
                throughputMessage(3000L, null, output = 10)
            )
        )
        assertNull(state.throughputStats)
    }
}
