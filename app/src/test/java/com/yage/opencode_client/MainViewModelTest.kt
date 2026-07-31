package com.yage.opencode_client

import android.util.Log
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.PermissionResponse
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.SSEPayload
import com.yage.opencode_client.data.model.HealthResponse
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostTransport
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ssh.SSHKeyManager
import com.yage.opencode_client.ssh.TunnelManager
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.DeepLinkError
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.ModelPresets
import com.yage.opencode_client.ui.session.buildSessionTree
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.ThemeMode
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowMicrophone
import com.yage.voiceflowkit.VoiceFlowPreservedAudio
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import com.yage.voiceflowkit.VoiceFlowSession
import com.yage.voiceflowkit.TranscriptionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var voiceFlowClient: VoiceFlowClient
    private lateinit var microphone: VoiceFlowMicrophone
    private lateinit var hostProfileStore: HostProfileStore
    private lateinit var tunnelManager: TunnelManager
    private lateinit var sshKeyManager: SSHKeyManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        voiceFlowClient = mockk(relaxed = true)
        microphone = mockk(relaxed = true)
        hostProfileStore = mockk(relaxed = true)
        tunnelManager = mockk(relaxed = true)
        sshKeyManager = mockk(relaxed = true)

        val defaultProfile = HostProfile.defaultDirect("http://server.test")
        every { hostProfileStore.currentProfile() } returns defaultProfile
        every { hostProfileStore.profiles() } returns listOf(defaultProfile)

        every { settingsManager.serverUrl } returns "http://server.test"
        every { settingsManager.username } returns null
        every { settingsManager.password } returns null
        every { settingsManager.currentSessionId } returns null
        every { settingsManager.selectedModelIndex } returns 0
        every { settingsManager.selectedAgentName } returns null
        every { settingsManager.themeMode } returns ThemeMode.SYSTEM
        every { settingsManager.aiBuilderBaseURL } returns "https://space.ai-builders.com/backend"
        every { settingsManager.aiBuilderToken } returns ""
        every { settingsManager.aiBuilderCustomPrompt } returns ""
        every { settingsManager.aiBuilderTerminology } returns ""
        every { settingsManager.aiBuilderRecordingStrategy } returns "OPENAI_REALTIME"
        every { settingsManager.aiBuilderLastOKSignature } returns null
        every { settingsManager.aiBuilderLastOKTestedAt } returns 0L

        every { settingsManager.serverUrl = any() } just runs
        every { settingsManager.username = any() } just runs
        every { settingsManager.password = any() } just runs
        every { settingsManager.currentSessionId = any() } just runs
        every { settingsManager.selectedModelIndex = any() } just runs
        every { settingsManager.selectedAgentName = any() } just runs
        every { settingsManager.themeMode = any() } just runs
        every { settingsManager.aiBuilderBaseURL = any() } just runs
        every { settingsManager.aiBuilderToken = any() } just runs
        every { settingsManager.aiBuilderCustomPrompt = any() } just runs
        every { settingsManager.aiBuilderTerminology = any() } just runs
        every { settingsManager.aiBuilderRecordingStrategy = any() } just runs
        every { settingsManager.aiBuilderLastOKSignature = any() } just runs
        every { settingsManager.aiBuilderLastOKTestedAt = any() } just runs

        every { settingsManager.getDraftText(any()) } returns ""
        every { settingsManager.setDraftText(any(), any()) } just runs
        every { settingsManager.getModelForSession(any()) } returns null
        every { settingsManager.setModelForSession(any(), any()) } just runs
        every { settingsManager.getAgentForSession(any()) } returns null
        every { settingsManager.setAgentForSession(any(), any()) } just runs

        every { repository.connectSSE() } returns emptyFlow()
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(repository, settingsManager, voiceFlowClient, microphone, hostProfileStore, tunnelManager, sshKeyManager)
    }

    private fun updateState(viewModel: MainViewModel, transform: (AppState) -> AppState) {
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<AppState>
        flow.value = transform(flow.value)
    }

    private suspend fun awaitSpeechWork(viewModel: MainViewModel) {
        val field = MainViewModel::class.java.getDeclaredField("speechTranscriptionJob")
        field.isAccessible = true
        repeat(3) {
            val job = field.get(viewModel) as? Job ?: return
            job.join()
        }
    }

    private fun handleSse(viewModel: MainViewModel, event: SSEEvent) {
        val method = MainViewModel::class.java.getDeclaredMethod("handleSSEEvent", SSEEvent::class.java)
        method.isAccessible = true
        method.invoke(viewModel, event)
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `deep link stays pending until connected`() = runTest {
        val viewModel = createViewModel()

        viewModel.receiveDeepLink("opencode://session/ses_later")
        advanceUntilIdle()

        assertEquals("ses_later", viewModel.state.value.pendingDeepLinkSessionId)
        assertFalse(viewModel.state.value.isResolvingDeepLink)
        coVerify(exactly = 0) { repository.getSession(any()) }
    }

    @Test
    fun `deep link verifies and hydrates session outside list`() = runTest {
        val source = Session(id = "ses_source", directory = "/source", title = "Source")
        val target = Session(id = "ses_target", directory = "/target", title = "Target")
        coEvery { repository.getSession(target.id) } returns Result.success(target)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(isConnected = true, sessions = listOf(source), currentSessionId = source.id)
        }

        viewModel.receiveDeepLink("opencode://session/${target.id}")
        advanceUntilIdle()

        assertEquals(target.id, viewModel.state.value.currentSessionId)
        assertEquals(target, viewModel.state.value.currentSession)
        assertNull(viewModel.state.value.pendingDeepLinkSessionId)
        assertFalse(viewModel.state.value.isResolvingDeepLink)
        assertEquals(1L, viewModel.state.value.deepLinkNavigationVersion)
        coVerify(exactly = 1) { repository.getSession(target.id) }
        coVerify(atLeast = 1) { repository.getMessages(target.id, any()) }
    }

    @Test
    fun `deep link failure preserves current session`() = runTest {
        val source = Session(id = "ses_source", directory = "/source", title = "Source")
        coEvery { repository.getSession("ses_missing") } returns Result.failure(IllegalStateException("offline"))
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(isConnected = true, sessions = listOf(source), currentSessionId = source.id)
        }

        viewModel.receiveDeepLink("opencode://session/ses_missing")
        advanceUntilIdle()

        assertEquals(source.id, viewModel.state.value.currentSessionId)
        assertEquals(listOf(source), viewModel.state.value.sessions)
        assertEquals(DeepLinkError.OPEN_FAILED, viewModel.state.value.deepLinkError)
    }

    @Test
    fun `invalid deep link cancels older pending route`() = runTest {
        val viewModel = createViewModel()
        viewModel.receiveDeepLink("opencode://session/ses_older")

        viewModel.receiveDeepLink("opencode://session/not-valid")
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingDeepLinkSessionId)
        assertEquals(DeepLinkError.INVALID, viewModel.state.value.deepLinkError)
        coVerify(exactly = 0) { repository.getSession(any()) }
    }

    @Test
    fun `reprocessing same pending deep link invalidates cancelled request`() = runTest {
        val target = Session(id = "ses_target", directory = "/target", title = "Target")
        var requestCount = 0
        coEvery { repository.getSession(target.id) } coAnswers {
            requestCount += 1
            if (requestCount == 1) {
                delay(10_000)
            }
            Result.success(target)
        }
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(isConnected = true) }

        viewModel.receiveDeepLink("opencode://session/${target.id}")
        runCurrent()
        viewModel.processPendingDeepLinkIfPossible()
        advanceUntilIdle()

        assertEquals(2, requestCount)
        assertEquals(target.id, viewModel.state.value.currentSessionId)
        assertNull(viewModel.state.value.pendingDeepLinkSessionId)
        assertNull(viewModel.state.value.deepLinkError)
    }

    @Test
    fun `host switch clears old runtime and keeps pending deep link`() = runTest {
        val first = HostProfile(
            id = "host-1",
            name = "First",
            transport = HostTransport.DIRECT,
            serverUrl = "http://first.test"
        )
        val second = HostProfile(
            id = "host-2",
            name = "Second",
            transport = HostTransport.DIRECT,
            serverUrl = "http://second.test"
        )
        var currentProfile = first
        every { hostProfileStore.currentProfile() } answers { currentProfile }
        every { hostProfileStore.profiles() } returns listOf(first, second)
        every { hostProfileStore.select(second.id) } answers {
            currentProfile = second
            second
        }
        coEvery { repository.checkHealth() } returns Result.failure(IllegalStateException("offline"))
        coEvery { repository.getSession("ses_target") } coAnswers {
            delay(10_000)
            Result.success(Session(id = "ses_target", directory = "/target", title = "Target"))
        }
        val viewModel = createViewModel()
        val source = Session(id = "ses_source", directory = "/source", title = "Source")
        updateState(viewModel) {
            it.copy(
                isConnected = true,
                sessions = listOf(source),
                currentSessionId = source.id,
                messages = listOf(MessageWithParts(Message(id = "m1", sessionId = source.id, role = "user"))),
                streamingPartTexts = mapOf("p1" to "old"),
                streamingReasoningPart = Part(id = "p2", type = "reasoning", text = "old"),
                sessionTodos = mapOf(source.id to emptyList()),
                sendingSessionIds = setOf(source.id),
                filePathToShowInFiles = "old.md"
            )
        }
        viewModel.receiveDeepLink("opencode://session/ses_target")
        runCurrent()

        viewModel.selectHostProfile(second.id)
        advanceUntilIdle()

        assertEquals(second.id, viewModel.state.value.currentHostProfileId)
        assertTrue(viewModel.state.value.sessions.isEmpty())
        assertNull(viewModel.state.value.currentSessionId)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
        assertTrue(viewModel.state.value.sessionTodos.isEmpty())
        assertTrue(viewModel.state.value.sendingSessionIds.isEmpty())
        assertNull(viewModel.state.value.filePathToShowInFiles)
        assertEquals("ses_target", viewModel.state.value.pendingDeepLinkSessionId)
        assertFalse(viewModel.state.value.isResolvingDeepLink)
        verify(exactly = 1) { tunnelManager.disconnect() }
    }

    @Test
    fun `selectSession clears streaming state from previous session`() = runTest {
        val source = Session(id = "ses_source", directory = "/source", title = "Source")
        val target = Session(id = "ses_target", directory = "/target", title = "Target")
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = listOf(source, target),
                currentSessionId = source.id,
                streamingPartTexts = mapOf("part" to "old text"),
                streamingReasoningPart = Part(id = "reasoning", type = "reasoning", text = "old")
            )
        }

        viewModel.selectSession(target.id)

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
    }

    @Test
    fun `init clamps saved model index and configures repository`() = runTest {
        every { settingsManager.selectedModelIndex } returns 999

        val viewModel = createViewModel()

        assertEquals(ModelPresets.list.lastIndex, viewModel.state.value.selectedModelIndex)
        verify { settingsManager.selectedModelIndex = ModelPresets.list.lastIndex }
        verify { repository.configure("http://server.test", null, null) }
    }

    @Test
    fun `init restores AI Builder connection when signature matches`() = runTest {
        val baseUrl = "https://builder.example.com"
        val token = "secret-token"
        every { settingsManager.aiBuilderBaseURL } returns baseUrl
        every { settingsManager.aiBuilderToken } returns token
        every { settingsManager.aiBuilderLastOKSignature } returns sha256("$baseUrl|$token")

        val viewModel = createViewModel()

        assertTrue(viewModel.state.value.aiBuilderConnectionOK)
    }

    @Test
    fun `sendMessage success clears input and uses selected preset model`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(400) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("  hello world  ")
        viewModel.selectAgent("review")
        viewModel.selectModel(1)

        viewModel.sendMessage()
        advanceUntilIdle()

        val selected = ModelPresets.list[1]
        coVerify {
            repository.sendMessage(
                "session-1",
                "hello world",
                "review",
                Message.ModelInfo(selected.providerId, selected.modelId)
            )
        }
        assertEquals("", viewModel.state.value.inputText)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `sendMessage ignores duplicate sends while request is in flight`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } coAnswers {
            delay(100)
            Result.success(Unit)
        }

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        viewModel.sendMessage()

        advanceUntilIdle()

        coVerify(exactly = 1) { repository.sendMessage(any(), any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.sendingSessionIds.contains("session-1"))
    }

    @Test
    fun `sendMessage success refreshes sessions`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(400) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project", title = "Updated"))
        )

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(atLeast = 1) { repository.getSessions(400) }
        assertEquals("Updated", viewModel.state.value.sessions.single().title)
    }

    @Test
    fun `sendMessage bumps current session above stale refreshed ordering`() = runTest {
        val current = com.yage.opencode_client.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "Current",
            time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 1_000)
        )
        val previousTop = com.yage.opencode_client.data.model.Session(
            id = "session-2",
            directory = "/tmp/project",
            title = "Previous Top",
            time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 2_000)
        )
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(400) } returns Result.success(listOf(previousTop, current))

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(previousTop, current),
                inputText = "hello"
            )
        }

        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("session-1", buildSessionTree(viewModel.state.value.sessions).first().session.id)
    }

    @Test
    fun `sendMessage failure keeps input and exposes error`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.failure(IllegalStateException("send failed"))

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("hello", viewModel.state.value.inputText)
        assertEquals("send failed", viewModel.state.value.error)
    }

    @Test
    fun `sendMessage still queues prompt when current session is busy`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        updateState(viewModel) {
            it.copy(
                inputText = "queue this next",
                sessionStatuses = it.sessionStatuses + ("session-1" to SessionStatus(type = "busy"))
            )
        }

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                "session-1",
                "queue this next",
                any(),
                any()
            )
        }
        assertEquals("", viewModel.state.value.inputText)
    }

    @Test
    fun `sendMessage ignores request while recording`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                inputText = "do not send yet",
                isRecording = true
            )
        }

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any()) }
        assertEquals("do not send yet", viewModel.state.value.inputText)
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("   ")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("   ", viewModel.state.value.inputText)
    }

    @Test
    fun `sendMessage ignores request when no session is selected`() = runTest {
        val viewModel = createViewModel()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("hello", viewModel.state.value.inputText)
    }

    @Test
    fun `createSession and session created SSE keep a single unique session`() = runTest {
        val created = com.yage.opencode_client.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "New Session"
        )
        coEvery { repository.createSession(any()) } returns Result.success(created)

        val viewModel = createViewModel()

        viewModel.createSession()
        advanceUntilIdle()

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.created",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("Server Title"))
                            }
                        )
                    }
                )
            )
        )

        val sessions = viewModel.state.value.sessions
        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions.single().id)
        assertEquals("Server Title", sessions.single().title)
    }

    @Test
    fun `session updated SSE refreshes session list from server`() = runTest {
        val updatedSessions = listOf(
            com.yage.opencode_client.data.model.Session(
                id = "session-1",
                directory = "/tmp/project",
                title = "Server Refreshed"
            )
        )
        coEvery { repository.getSessions(400) } returns Result.success(updatedSessions)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project", title = "Old"))
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("SSE Only"))
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify { repository.getSessions(400) }
        assertEquals("Server Refreshed", viewModel.state.value.sessions.single().title)
    }

    @Test
    fun `session updated SSE title survives a stale concurrent refresh`() = runTest {
        // The server's session.updated event carries the generated title with a fresh timestamp,
        // but the full refresh it triggers returns a stale snapshot (placeholder title, older
        // timestamp). The freshly received title must remain visible (Chat header reads it from
        // state.sessions) rather than being clobbered by the stale refresh.
        coEvery { repository.getSessions(400) } returns Result.success(
            listOf(
                com.yage.opencode_client.data.model.Session(
                    id = "session-1",
                    directory = "/tmp/project",
                    title = "New session - 1700000000",
                    time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 1_000)
                )
            )
        )

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(
                    com.yage.opencode_client.data.model.Session(
                        id = "session-1",
                        directory = "/tmp/project",
                        title = "New session - 1700000000",
                        time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 1_000)
                    )
                )
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("Pythagorean theorem: history, proof, engineering"))
                                put(
                                    "time",
                                    buildJsonObject { put("updated", JsonPrimitive(2_000)) }
                                )
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify { repository.getSessions(400) }
        assertEquals(
            "Pythagorean theorem: history, proof, engineering",
            viewModel.state.value.sessions.single { it.id == "session-1" }.title
        )
    }

    @Test
    fun `message created SSE refreshes session list for incoming assistant activity`() = runTest {
        val refreshedSessions = listOf(
            com.yage.opencode_client.data.model.Session(
                id = "session-2",
                directory = "/tmp/project",
                title = "New Activity",
                time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 2_000)
            ),
            com.yage.opencode_client.data.model.Session(
                id = "session-1",
                directory = "/tmp/project",
                title = "Current",
                time = com.yage.opencode_client.data.model.Session.TimeInfo(updated = 1_000)
            )
        )
        coEvery { repository.getSessions(400) } returns Result.success(refreshedSessions)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(refreshedSessions[1], refreshedSessions[0])
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.created",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-2"))
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify { repository.getSessions(400) }
        assertEquals("session-2", viewModel.state.value.sessions.first().id)
    }

    @Test
    fun `message updated SSE refreshes current messages and sessions`() = runTest {
        coEvery { repository.getSessions(400) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify { repository.getSessions(400) }
        coVerify { repository.getMessages("session-1", 30) }
    }

    @Test
    fun `loadSessions requests current limit and tracks hasMore`() = runTest {
        val sessions = (1..400).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(400) } returns Result.success(sessions)

        val viewModel = createViewModel()

        viewModel.loadSessions()
        advanceUntilIdle()

        coVerify { repository.getSessions(400) }
        assertEquals(400, viewModel.state.value.loadedSessionLimit)
        assertTrue(viewModel.state.value.hasMoreSessions)
        assertEquals(400, viewModel.state.value.sessions.size)
        assertFalse(viewModel.state.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions clears isRefreshingSessions after successful fetch`() = runTest {
        val sessions = listOf(
            com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/1")
        )
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)

        val viewModel = createViewModel()

        viewModel.loadSessions()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions fetches sub_agent sessions created after initial load`() = runTest {
        val initialSessions = listOf(
            com.yage.opencode_client.data.model.Session(id = "parent-1", directory = "/tmp/project")
        )
        coEvery { repository.getSessions(400) } returns Result.success(initialSessions)

        val viewModel = createViewModel()
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.sessions.size)
        assertEquals("parent-1", viewModel.state.value.sessions.single().id)

        val refreshedSessions = listOf(
            com.yage.opencode_client.data.model.Session(id = "parent-1", directory = "/tmp/project"),
            com.yage.opencode_client.data.model.Session(
                id = "child-1",
                directory = "/tmp/project",
                parentId = "parent-1"
            )
        )
        coEvery { repository.getSessions(400) } returns Result.success(refreshedSessions)

        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.sessions.size)
        assertEquals("child-1", viewModel.state.value.sessions.find { it.parentId == "parent-1" }?.id)
        assertFalse(viewModel.state.value.isRefreshingSessions)
    }

    @Test
    fun `loadSessions clears isRefreshingSessions on failure`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.failure(IllegalStateException("network error"))

        val viewModel = createViewModel()

        viewModel.loadSessions()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRefreshingSessions)
        assertEquals("Failed to load sessions: network error", viewModel.state.value.error)
    }

    @Test
    fun `loadMoreSessions requests higher limit and replaces sessions`() = runTest {
        val initial = (1..400).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        val expanded = (1..450).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(800) } returns Result.success(expanded)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = initial,
                loadedSessionLimit = 400,
                hasMoreSessions = true,
                currentSessionId = "session-20"
            )
        }

        viewModel.loadMoreSessions()
        advanceUntilIdle()

        coVerify { repository.getSessions(800) }
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertFalse(viewModel.state.value.hasMoreSessions)
        assertEquals(450, viewModel.state.value.sessions.size)
        assertEquals("session-20", viewModel.state.value.currentSessionId)
    }

    @Test
    fun `loadMoreSessions ignores duplicate triggers while request is in flight`() = runTest {
        val expanded = (1..450).map { index ->
            com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index")
        }
        coEvery { repository.getSessions(800) } coAnswers {
            kotlinx.coroutines.delay(100)
            Result.success(expanded)
        }

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                loadedSessionLimit = 400,
                hasMoreSessions = true,
                sessions = (1..400).map { index -> com.yage.opencode_client.data.model.Session(id = "session-$index", directory = "/tmp/$index") }
            )
        }

        viewModel.loadMoreSessions()
        viewModel.loadMoreSessions()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getSessions(800) }
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
    }

    @Test
    fun `archiveSession archives subtree children before parent`() = runTest {
        val parent = Session(id = "parent", directory = "/tmp/project")
        val child = Session(id = "child", directory = "/tmp/project", parentId = "parent")
        coEvery { repository.updateSessionArchived("child", any()) } returns Result.success(
            child.copy(time = Session.TimeInfo(archived = 1_000))
        )
        coEvery { repository.updateSessionArchived("parent", any()) } returns Result.success(
            parent.copy(time = Session.TimeInfo(archived = 1_000))
        )

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(sessions = listOf(parent, child)) }

        viewModel.archiveSession("parent")
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateSessionArchived("child", any())
            repository.updateSessionArchived("parent", any())
        }
        assertTrue(viewModel.state.value.sessions.all { it.isArchived })
    }

    @Test
    fun `restoreSession restores subtree parent before children`() = runTest {
        val parent = Session(
            id = "parent",
            directory = "/tmp/project",
            time = Session.TimeInfo(archived = 1_000)
        )
        val child = Session(
            id = "child",
            directory = "/tmp/project",
            parentId = "parent",
            time = Session.TimeInfo(archived = 1_000)
        )
        coEvery { repository.updateSessionArchived("parent", -1L) } returns Result.success(
            parent.copy(time = Session.TimeInfo(archived = -1))
        )
        coEvery { repository.updateSessionArchived("child", -1L) } returns Result.success(
            child.copy(time = Session.TimeInfo(archived = -1))
        )

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(sessions = listOf(parent, child)) }

        viewModel.restoreSession("parent")
        advanceUntilIdle()

        coVerifyOrder {
            repository.updateSessionArchived("parent", -1L)
            repository.updateSessionArchived("child", -1L)
        }
        assertFalse(viewModel.state.value.sessions.any { it.isArchived })
    }

    @Test
    fun `loadMessages updates selected agent and preset model from last assistant`() = runTest {
        val preset = ModelPresets.list[2]
        val messages = listOf(
            MessageWithParts(info = Message(id = "u1", role = "user")),
            MessageWithParts(
                info = Message(
                    id = "a1",
                    role = "assistant",
                    agent = "plan",
                    model = Message.ModelInfo(preset.providerId, preset.modelId)
                )
            )
        )
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals(messages, viewModel.state.value.messages)
        assertEquals("plan", viewModel.state.value.selectedAgentName)
        assertEquals(2, viewModel.state.value.selectedModelIndex)
    }

    @Test
    fun `toggleRecording shows token guidance when AI Builder token missing`() = runTest {
        val viewModel = createViewModel()

        viewModel.toggleRecording()

        assertEquals(
            "Speech recognition requires an AI Builder token. Configure it in Settings.",
            viewModel.state.value.speechError
        )
        assertFalse(viewModel.state.value.isRecording)
    }

    @Test
    fun `toggleRecording requires successful AI Builder connection before recording`() = runTest {
        every { settingsManager.aiBuilderToken } returns "token"
        val viewModel = createViewModel()

        viewModel.toggleRecording()

        assertEquals(
            "AI Builder connection test has not passed. Please test in Settings first.",
            viewModel.state.value.speechError
        )
        assertFalse(viewModel.state.value.isRecording)
    }

    @Test
    fun `toggleRecording handles missing realtime session when stopping recording`() = runTest {
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "OPENAI_REALTIME"
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(isRecording = true, aiBuilderConnectionOK = true, inputText = "draft") }

        viewModel.toggleRecording()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        assertFalse(viewModel.state.value.isTranscribing)
        assertEquals("Recording failed: realtime session missing", viewModel.state.value.speechError)
        assertEquals("draft", viewModel.state.value.inputText)
    }

    @Test
    fun `GPT Live start uses snapshotted strategy`() = runTest {
        val session = mockk<VoiceFlowSession>(relaxed = true)
        val realtimeWav = File.createTempFile("opencode-realtime-success", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GPT_LIVE_TRANSCRIBE"
        coEvery { voiceFlowClient.startSession(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE) } returns session
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
                persist = any(),
                onPCMChunk = any(),
            )
        } just runs
        coEvery { microphone.stop() } returns realtimeWav
        coEvery { session.commitAndStop(any()) } returns "live words"
        coEvery { session.abortPreservingAudio() } returns null
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(aiBuilderConnectionOK = true, inputText = "prefix") }

        viewModel.toggleRecording()
        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        runCurrent()

        assertTrue(viewModel.state.value.isRecording)
        coVerify(exactly = 1) {
            voiceFlowClient.startSession(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE)
        }
        coVerify(exactly = 1) {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
                persist = any(),
                onPCMChunk = any(),
            )
        }
        viewModel.toggleRecording()
        awaitSpeechWork(viewModel)
        withTimeout(5_000) {
            viewModel.state.first { it.inputText == "prefix live words" }
        }
        assertEquals("prefix live words", viewModel.state.value.inputText)
        assertFalse(viewModel.state.value.hasPreservedSpeechAudio)
        assertFalse(realtimeWav.exists())
    }

    @Test
    fun `GPT Live finalize failure preserves originating audio for retry`() = runTest {
        val session = mockk<VoiceFlowSession>(relaxed = true)
        val preserved = mockk<VoiceFlowPreservedAudio>(relaxed = true)
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GPT_LIVE_TRANSCRIBE"
        coEvery { voiceFlowClient.startSession(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE) } returns session
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
                persist = any(),
                onPCMChunk = any(),
            )
        } just runs
        coEvery { microphone.stop() } returns null
        coEvery { session.commitAndStop(any()) } throws IllegalStateException("timeout")
        coEvery { session.abortPreservingAudio() } returns preserved
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(aiBuilderConnectionOK = true, inputText = "prefix") }

        viewModel.toggleRecording()
        runCurrent()
        viewModel.toggleRecording()
        awaitSpeechWork(viewModel)

        assertTrue(viewModel.state.value.hasPreservedSpeechAudio)
        assertEquals("prefix", viewModel.state.value.inputText)
        assertEquals("timeout", viewModel.state.value.speechError)

        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        coEvery { voiceFlowClient.transcribe(preserved, any()) } returns
            TranscriptionResult(text = "recovered", requestId = "retry-1")
        viewModel.retryPreservedSpeechAudio()
        advanceUntilIdle()

        assertEquals("prefix recovered", viewModel.state.value.inputText)
        assertFalse(viewModel.state.value.hasPreservedSpeechAudio)
        coVerify(exactly = 1) { voiceFlowClient.transcribe(preserved, any()) }
    }

    @Test
    fun `Grok failure preserves file and originating strategy for retry`() = runTest {
        val grokFile = File.createTempFile("opencode-grok-failure", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GROK_BATCH,
                persist = any(),
                onPCMChunk = null,
            )
        } just runs
        coEvery { microphone.stop() } returns grokFile
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } throws IllegalStateException("upload failed")
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(aiBuilderConnectionOK = true, inputText = "prefix") }

        viewModel.toggleRecording()
        runCurrent()
        viewModel.toggleRecording()
        advanceUntilIdle()

        assertTrue(grokFile.exists())
        assertTrue(viewModel.state.value.hasPreservedSpeechAudio)
        assertEquals("upload failed", viewModel.state.value.speechError)

        every { settingsManager.aiBuilderRecordingStrategy } returns "OPENAI_REALTIME"
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } returns TranscriptionResult(text = "recovered", requestId = "grok-retry")
        viewModel.retryPreservedSpeechAudio()
        advanceUntilIdle()

        assertEquals("prefix recovered", viewModel.state.value.inputText)
        assertFalse(viewModel.state.value.hasPreservedSpeechAudio)
        assertFalse(grokFile.exists())
        coVerify(exactly = 2) {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        }
    }

    @Test
    fun `background cancellation leaves failed Grok retry available`() = runTest {
        val grokFile = File.createTempFile("opencode-grok-cancel", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GROK_BATCH,
                persist = any(),
                onPCMChunk = null,
            )
        } just runs
        coEvery { microphone.stop() } returns grokFile
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } throws IllegalStateException("upload failed")
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(aiBuilderConnectionOK = true) }

        viewModel.toggleRecording()
        runCurrent()
        viewModel.toggleRecording()
        advanceUntilIdle()
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } coAnswers { awaitCancellation() }

        viewModel.retryPreservedSpeechAudio()
        runCurrent()
        assertTrue(viewModel.state.value.isRetryingSpeech)
        viewModel.stopSpeechForBackground()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRetryingSpeech)
        assertTrue(viewModel.state.value.hasPreservedSpeechAudio)
        assertTrue(grokFile.exists())
        viewModel.discardPreservedSpeechAudio()
        assertFalse(grokFile.exists())
    }

    @Test
    fun `discard joins active retry before deleting preserved file`() = runTest {
        val grokFile = File.createTempFile("opencode-grok-discard-join", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val retryStarted = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GROK_BATCH,
                persist = any(),
                onPCMChunk = null,
            )
        } just runs
        coEvery { microphone.stop() } returns grokFile
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } throws IllegalStateException("upload failed")
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "source", aiBuilderConnectionOK = true) }

        viewModel.toggleRecording()
        runCurrent()
        viewModel.toggleRecording()
        advanceUntilIdle()
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } coAnswers {
            retryStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cancellationObserved.complete(Unit)
                    releaseCancellation.await()
                }
            }
        }

        viewModel.retryPreservedSpeechAudio()
        retryStarted.await()
        viewModel.discardPreservedSpeechAudio()
        cancellationObserved.await()

        assertTrue(grokFile.exists())
        assertTrue(viewModel.state.value.hasPreservedSpeechAudio)

        releaseCancellation.complete(Unit)
        advanceUntilIdle()

        assertFalse(grokFile.exists())
        assertFalse(viewModel.state.value.hasPreservedSpeechAudio)
    }

    @Test
    fun `switching sessions stops Grok recording and retry writes source draft only`() = runTest {
        val grokFile = File.createTempFile("opencode-grok-session-switch", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        every { settingsManager.getDraftText("destination") } returns "destination draft"
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GROK_BATCH,
                persist = any(),
                onPCMChunk = null,
            )
        } just runs
        coEvery { microphone.stop() } returns grokFile
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } returns TranscriptionResult(text = "source words", requestId = "switch-retry")
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "source",
                inputText = "source prefix",
                aiBuilderConnectionOK = true,
            )
        }

        viewModel.toggleRecording()
        runCurrent()
        viewModel.selectSession("destination")
        advanceUntilIdle()

        assertEquals("destination draft", viewModel.state.value.inputText)
        assertTrue(viewModel.state.value.hasPreservedSpeechAudio)
        assertTrue(grokFile.exists())
        coVerify(exactly = 0) {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        }

        viewModel.retryPreservedSpeechAudio()
        advanceUntilIdle()

        assertEquals("destination draft", viewModel.state.value.inputText)
        verify { settingsManager.setDraftText("source", "source prefix source words") }
        assertFalse(viewModel.state.value.hasPreservedSpeechAudio)
        assertFalse(grokFile.exists())
    }

    @Test
    fun `Grok completion after session switch writes source draft only`() = runTest {
        val grokFile = File.createTempFile("opencode-grok-owned-completion", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<TranscriptionResult>()
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        every { settingsManager.getDraftText("destination") } returns "destination draft"
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GROK_BATCH,
                persist = any(),
                onPCMChunk = null,
            )
        } just runs
        coEvery { microphone.stop() } returns grokFile
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } coAnswers {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentSessionId = "source", inputText = "source prefix", aiBuilderConnectionOK = true)
        }

        viewModel.toggleRecording()
        runCurrent()
        viewModel.toggleRecording()
        uploadStarted.await()
        viewModel.selectSession("destination")
        uploadResult.complete(TranscriptionResult(text = "finished", requestId = "owned-grok"))
        advanceUntilIdle()

        assertEquals("destination draft", viewModel.state.value.inputText)
        verify { settingsManager.setDraftText("source", "source prefix finished") }
        assertFalse(grokFile.exists())
    }

    @Test
    fun `realtime partial and final after session switch cannot overwrite destination`() = runTest {
        val session = mockk<VoiceFlowSession>(relaxed = true)
        val realtimeWav = File.createTempFile("opencode-realtime-owned-completion", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val commitStarted = CompletableDeferred<Unit>()
        val commitResult = CompletableDeferred<String>()
        lateinit var sendPartial: (String) -> Unit
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GPT_LIVE_TRANSCRIBE"
        every { settingsManager.getDraftText("destination") } returns "destination draft"
        coEvery { voiceFlowClient.startSession(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE) } returns session
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
                persist = any(),
                onPCMChunk = any(),
            )
        } just runs
        coEvery { microphone.stop() } returns realtimeWav
        coEvery { session.commitAndStop(any()) } coAnswers {
            sendPartial = firstArg<((String) -> Unit)?>()!!
            commitStarted.complete(Unit)
            commitResult.await()
        }
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentSessionId = "source", inputText = "source prefix", aiBuilderConnectionOK = true)
        }

        viewModel.toggleRecording()
        withTimeout(5_000) { viewModel.state.first { it.isRecording } }
        viewModel.toggleRecording()
        runCurrent()
        withTimeout(5_000) { commitStarted.await() }
        viewModel.selectSession("destination")
        sendPartial("stale partial")
        runCurrent()
        assertEquals("destination draft", viewModel.state.value.inputText)

        commitResult.complete("finished")
        runCurrent()

        assertEquals("destination draft", viewModel.state.value.inputText)
        verify { settingsManager.setDraftText("source", "source prefix finished") }
        assertFalse(realtimeWav.exists())
        viewModel.stopSpeechForBackground()
        advanceUntilIdle()
    }

    @Test
    fun `realtime sender saturation preserves complete WAV for explicit retry`() = runTest {
        val session = mockk<VoiceFlowSession>(relaxed = true)
        val realtimeWav = File.createTempFile("opencode-realtime-backpressure", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val sendStarted = CompletableDeferred<Unit>()
        lateinit var sendPcm: (ByteArray) -> Unit
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GPT_LIVE_TRANSCRIBE"
        coEvery { voiceFlowClient.startSession(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE) } returns session
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
                persist = any(),
                onPCMChunk = any(),
            )
        } coAnswers {
            sendPcm = arg<(ByteArray) -> Unit>(2)
        }
        coEvery { microphone.stop() } returns realtimeWav
        coEvery { session.sendAudioChunk(any()) } coAnswers {
            sendStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery { session.abortPreservingAudio() } returns null
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentSessionId = "source", inputText = "prefix", aiBuilderConnectionOK = true)
        }

        viewModel.toggleRecording()
        runCurrent()
        sendPcm(byteArrayOf(1))
        sendStarted.await()
        repeat(9) { sendPcm(byteArrayOf((it + 2).toByte())) }

        assertEquals(
            "Live audio buffer saturated; the complete recording was saved for retry.",
            viewModel.state.value.speechError,
        )

        viewModel.toggleRecording()
        awaitSpeechWork(viewModel)

        assertTrue(realtimeWav.exists())
        assertTrue(viewModel.state.value.hasPreservedSpeechAudio)
        assertEquals(
            "Live audio buffer saturated; the complete recording was saved for retry.",
            viewModel.state.value.speechError,
        )
    }

    @Test
    fun `background joins initial Grok upload before preserving owned WAV`() = runTest {
        val grokFile = File.createTempFile("opencode-grok-background-join", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val uploadStarted = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GROK_BATCH,
                persist = any(),
                onPCMChunk = null,
            )
        } just runs
        coEvery { microphone.stop() } returns grokFile
        coEvery {
            voiceFlowClient.transcribe(grokFile, VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } coAnswers {
            uploadStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cancellationObserved.complete(Unit)
                    releaseCancellation.await()
                }
            }
        }
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentSessionId = "source", inputText = "prefix", aiBuilderConnectionOK = true)
        }

        viewModel.toggleRecording()
        runCurrent()
        viewModel.toggleRecording()
        uploadStarted.await()
        viewModel.stopSpeechForBackground()
        cancellationObserved.await()

        assertTrue(grokFile.exists())
        assertFalse(viewModel.state.value.hasPreservedSpeechAudio)

        releaseCancellation.complete(Unit)
        advanceUntilIdle()

        assertTrue(grokFile.exists())
        assertTrue(viewModel.state.value.hasPreservedSpeechAudio)
    }

    @Test
    fun `handleSSEEvent appends streaming reasoning delta for current session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "part",
                            buildJsonObject {
                                put("messageID", JsonPrimitive("message-1"))
                                put("id", JsonPrimitive("part-1"))
                                put("type", JsonPrimitive("reasoning"))
                            }
                        )
                        put("delta", JsonPrimitive("thinking"))
                    }
                )
            )
        )

        assertEquals("thinking", viewModel.state.value.streamingPartTexts["message-1:part-1"])
        assertEquals("part-1", viewModel.state.value.streamingReasoningPart?.id)
    }

    @Test
    fun `handleSSEEvent session created prepends parsed session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(sessions = listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/old")))
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.created",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-2"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("New Session"))
                            }
                        )
                    }
                )
            )
        )

        assertEquals(listOf("session-2", "session-1"), viewModel.state.value.sessions.map { it.id })
    }

    @Test
    fun `handleSSEEvent session updated replaces existing session title`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(sessions = listOf(
                com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project", title = null)
            ))
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("Refactor auth module"))
                            }
                        )
                    }
                )
            )
        )

        val sessions = viewModel.state.value.sessions
        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions[0].id)
        assertEquals("Refactor auth module", sessions[0].title)
    }

    @Test
    fun `handleSSEEvent session updated inserts unknown session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(sessions = listOf(
                com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/old")
            ))
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-new"))
                                put("directory", JsonPrimitive("/tmp/new"))
                                put("title", JsonPrimitive("New Feature"))
                            }
                        )
                    }
                )
            )
        )

        val sessions = viewModel.state.value.sessions
        assertEquals(2, sessions.size)
        assertEquals("session-new", sessions[0].id)
        assertEquals("New Feature", sessions[0].title)
        assertEquals("session-1", sessions[1].id)
    }

    @Test
    fun `handleSSEEvent missing delta clears streaming state and refreshes messages`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a2", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("message-1:part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("part", buildJsonObject { put("type", JsonPrimitive("reasoning")) })
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
        assertEquals(messages, viewModel.state.value.messages)
    }

    @Test
    fun `handleSSEEvent ignores message updates when no current session is selected`() = runTest {
        val viewModel = createViewModel()

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("part", buildJsonObject { put("type", JsonPrimitive("reasoning")) })
                        put("delta", JsonPrimitive("ignored"))
                    }
                )
            )
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
    }

    @Test
    fun `handleSSEEvent idle status clears streaming state and refreshes messages`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        coEvery { repository.getSessions(400) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("message-1:part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "status",
                            buildJsonObject {
                                put("type", JsonPrimitive("idle"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
        assertEquals(messages, viewModel.state.value.messages)
    }

    @Test
    fun `handleSSEEvent permission asked refreshes pending permissions`() = runTest {
        val permissions = listOf(
            PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.read")
        )
        coEvery { repository.getPendingPermissions() } returns Result.success(permissions)
        val viewModel = createViewModel()

        handleSse(
            viewModel,
            SSEEvent(payload = SSEPayload(type = "permission.asked"))
        )
        advanceUntilIdle()

        assertEquals(permissions, viewModel.state.value.pendingPermissions)
    }

    @Test
    fun `clearSpeechError clears speech error state`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(speechError = "bad mic") }

        viewModel.clearSpeechError()

        assertNull(viewModel.state.value.speechError)
    }

    @Test
    fun `setInputText with active session saves draft to settings manager`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1") }

        viewModel.setInputText("hello")

        verify { settingsManager.setDraftText("s1", "hello") }
    }

    @Test
    fun `selectSession saves old draft and restores new draft from settings manager`() = runTest {
        every { settingsManager.getDraftText("s2") } returns "draft2"

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1", inputText = "draft1") }

        viewModel.selectSession("s2")
        advanceUntilIdle()

        verify { settingsManager.setDraftText("s1", "draft1") }
        verify { settingsManager.getDraftText("s2") }
        assertEquals("draft2", viewModel.state.value.inputText)
    }

    @Test
    fun `selectModel with active session saves model index per session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1") }

        viewModel.selectModel(2)

        verify { settingsManager.setModelForSession("s1", 2) }
    }

    @Test
    fun `selectAgent with active session saves agent name per session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1") }

        viewModel.selectAgent("oracle")

        verify { settingsManager.setAgentForSession("s1", "oracle") }
    }

    @Test
    fun `sendMessage on success clears draft for current session`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        verify { settingsManager.setDraftText("s1", "") }
    }

    @Test
    fun `loadMessages uses per-session saved model index over message inference`() = runTest {
        val inferredPreset = ModelPresets.list[2]
        val messages = listOf(
            MessageWithParts(
                info = Message(
                    id = "a1",
                    role = "assistant",
                    model = Message.ModelInfo(inferredPreset.providerId, inferredPreset.modelId)
                )
            )
        )
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        every { settingsManager.getModelForSession("session-1") } returns 3

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.selectedModelIndex)
    }

    @Test
    fun `abortSession calls repository for current session`() = runTest {
        coEvery { repository.abortSession("session-1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        viewModel.abortSession()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.abortSession("session-1") }
    }

    @Test
    fun `deleteSession removes deleted session from state`() = runTest {
        coEvery { repository.deleteSession("session-1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = listOf(
                    com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/one"),
                    com.yage.opencode_client.data.model.Session(id = "session-2", directory = "/tmp/two")
                ),
                currentSessionId = "session-2"
            )
        }

        viewModel.deleteSession("session-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteSession("session-1") }
        assertEquals(listOf("session-2"), viewModel.state.value.sessions.map { it.id })
    }

    @Test
    fun `updateSessionTitle calls repository and updates session title`() = runTest {
        val updated = com.yage.opencode_client.data.model.Session(
            id = "session-1",
            directory = "/tmp/project",
            title = "Updated Title"
        )
        coEvery { repository.updateSession("session-1", "Updated Title") } returns Result.success(updated)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = listOf(
                    com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project", title = "Old Title")
                )
            )
        }

        viewModel.updateSessionTitle("session-1", "Updated Title")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateSession("session-1", "Updated Title") }
        assertEquals("Updated Title", viewModel.state.value.sessions.single().title)
    }

    @Test
    fun `respondPermission calls repository and removes pending permission`() = runTest {
        coEvery {
            repository.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                pendingPermissions = listOf(
                    PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.write"),
                    PermissionRequest(id = "perm-2", sessionId = "session-2", permission = "file.read")
                )
            )
        }

        viewModel.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        }
        assertEquals(listOf("perm-2"), viewModel.state.value.pendingPermissions.map { it.id })
    }

    @Test
    fun `loadPendingPermissions loads permissions into state`() = runTest {
        val permissions = listOf(
            PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.read"),
            PermissionRequest(id = "perm-2", sessionId = "session-2", permission = "command.exec")
        )
        coEvery { repository.getPendingPermissions() } returns Result.success(permissions)

        val viewModel = createViewModel()

        viewModel.loadPendingPermissions()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPendingPermissions() }
        assertEquals(permissions, viewModel.state.value.pendingPermissions)
    }

    @Test
    fun `replyQuestion calls repository and removes answered question`() = runTest {
        val answers = listOf(listOf("React"), listOf("Custom"))
        coEvery { repository.replyQuestion("question-1", answers) } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "question-1",
                        sessionId = "session-1",
                        questions = emptyList()
                    ),
                    QuestionRequest(
                        id = "question-2",
                        sessionId = "session-2",
                        questions = emptyList()
                    )
                )
            )
        }

        viewModel.replyQuestion("question-1", answers)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.replyQuestion("question-1", answers) }
        assertEquals(listOf("question-2"), viewModel.state.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `rejectQuestion calls repository and removes rejected question`() = runTest {
        coEvery { repository.rejectQuestion("question-1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "question-1",
                        sessionId = "session-1",
                        questions = emptyList()
                    ),
                    QuestionRequest(
                        id = "question-2",
                        sessionId = "session-2",
                        questions = emptyList()
                    )
                )
            )
        }

        viewModel.rejectQuestion("question-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.rejectQuestion("question-1") }
        assertEquals(listOf("question-2"), viewModel.state.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `testConnection skips second health check within cooldown`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        val viewModel = createViewModel()

        viewModel.testConnection()
        viewModel.testConnection()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.checkHealth() }
    }

    @Test
    fun `handleSSEEvent message created refreshes messages for current session`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "m1", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        coEvery { repository.getSessions(400) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.created",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                    }
                )
            )
        )
        advanceTimeBy(400)
        advanceUntilIdle()

        assertEquals(messages, viewModel.state.value.messages)
    }

    @Test
    fun `handleSSEEvent question asked appends pending question`() = runTest {
        val viewModel = createViewModel()

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "question.asked",
                    properties = buildJsonObject {
                        put("id", JsonPrimitive("question-1"))
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "questions",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("question", JsonPrimitive("What framework do you use?"))
                                        put("header", JsonPrimitive("Framework Choice"))
                                        put(
                                            "options",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("label", JsonPrimitive("React"))
                                                        put("description", JsonPrimitive("Popular UI library"))
                                                    }
                                                )
                                            }
                                        )
                                        put("multiple", JsonPrimitive(false))
                                        put("custom", JsonPrimitive(true))
                                    }
                                )
                            }
                        )
                    }
                )
            )
        )

        assertEquals(listOf("question-1"), viewModel.state.value.pendingQuestions.map { it.id })
        assertEquals("session-1", viewModel.state.value.pendingQuestions.single().sessionId)
    }

    @Test
    fun `handleSSEEvent question rejected removes pending question`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()),
                    QuestionRequest(id = "question-2", sessionId = "session-2", questions = emptyList())
                )
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "question.rejected",
                    properties = buildJsonObject {
                        put("requestID", JsonPrimitive("question-1"))
                    }
                )
            )
        )

        assertEquals(listOf("question-2"), viewModel.state.value.pendingQuestions.map { it.id })
    }

    @org.junit.After
    fun tearDown() {
        unmockkAll()
    }
}
