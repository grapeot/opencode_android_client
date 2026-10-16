package com.yage.opencode_client

import android.util.Log
import com.yage.opencode_client.data.api.SSEClient
import com.yage.opencode_client.data.model.AgentInfo
import com.yage.opencode_client.data.model.ComposerImageAttachment
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
import com.yage.opencode_client.data.model.ConfigProvider
import com.yage.opencode_client.data.model.ModelShortlistItem
import com.yage.opencode_client.data.model.ProviderRegistryResponse
import com.yage.opencode_client.data.model.ProvidersResponse
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ssh.SSHKeyManager
import com.yage.opencode_client.ssh.TunnelManager
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.SpeechDraftTarget
import com.yage.opencode_client.ui.DeepLinkError
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.MainViewModelTimings
import com.yage.opencode_client.ui.effectiveSelectedAgent
import com.yage.opencode_client.ui.ModelPresets
import com.yage.opencode_client.ui.encodeShortlist
import com.yage.opencode_client.ui.seedShortlistFromPresets
import com.yage.opencode_client.ui.session.buildSessionTree
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.SessionReadStateStore
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
import io.mockk.slot
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        voiceFlowClient = mockk(relaxed = true)
        microphone = mockk(relaxed = true)
        hostProfileStore = mockk(relaxed = true)
        tunnelManager = mockk(relaxed = true)
        sshKeyManager = mockk(relaxed = true)

        val defaultProfile = HostProfile.defaultDirect("http://server.test").copy(id = "test-host")
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
        every { settingsManager.getOwnedDraftOrNull(any(), any()) } returns null
        every { settingsManager.draftMap() } returns emptyMap()
        every { settingsManager.setOwnedDraft(any(), any(), any()) } just runs
        every { settingsManager.getModelIdForSession(any()) } returns null
        every { settingsManager.getLegacySessionModels() } returns emptyMap()
        every { settingsManager.getAgentForSession(any()) } returns null
        every { settingsManager.setAgentForSession(any(), any()) } just runs

        every { repository.connectSSE(any(), any()) } returns emptyFlow()
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getSessionStatusInDirectory(any()) } coAnswers { repository.getSessionStatus() }
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
        coEvery { repository.getPendingQuestions() } returns Result.success(emptyList())
        coEvery { repository.getProviders() } returns Result.success(ProvidersResponse())
        coEvery { repository.getProviderRegistry() } returns Result.success(ProviderRegistryResponse())
    }

    private fun createViewModel(readStateStore: SessionReadStateStore = testSessionReadStateStore()): MainViewModel {
        return MainViewModel(repository, settingsManager, voiceFlowClient, microphone, hostProfileStore, tunnelManager, sshKeyManager, testSessionStatsStore(), readStateStore)
    }

    private fun indicatorEvent(sessionId: String, status: String? = null) = SSEEvent(
        payload = SSEPayload(
            type = if (status == null) "session.error" else "session.status",
            properties = buildJsonObject {
                put("sessionID", JsonPrimitive(sessionId))
                if (status != null) put("status", buildJsonObject { put("type", JsonPrimitive(status)) })
            }
        )
    )

    @Test
    fun `indicator errors settle only after idle and successful turns retain unviewed failure`() = runTest {
        val store = testSessionReadStateStore()
        val viewModel = createViewModel(store)
        updateState(viewModel) { it.copy(currentSessionId = "current") }
        handleSse(viewModel, indicatorEvent("other"))
        assertNotNull(viewModel.state.value.pendingSessionErrorAt["other"])
        assertNull(store.failedAt("other"))
        handleSse(viewModel, indicatorEvent("other", "retry"))
        assertTrue(viewModel.state.value.pendingSessionErrorAt.isEmpty())
        handleSse(viewModel, indicatorEvent("other", "idle"))
        assertNull(store.failedAt("other"))
        handleSse(viewModel, indicatorEvent("other"))
        val errorAt = viewModel.state.value.pendingSessionErrorAt["other"]
        handleSse(viewModel, indicatorEvent("other", "idle"))
        assertEquals(errorAt, store.failedAt("other"))
        assertEquals(errorAt, viewModel.state.value.sessionReadStates["other"]?.failedAt)
        assertTrue(viewModel.state.value.pendingSessionErrorAt.isEmpty())
        // Idle snapshots alone must not clear a previously recorded failure.
        handleSse(viewModel, indicatorEvent("other", "idle"))
        assertEquals(errorAt, store.failedAt("other"))
        handleSse(viewModel, indicatorEvent("other", "busy"))
        handleSse(viewModel, indicatorEvent("other", "idle"))
        assertEquals(errorAt, store.failedAt("other"))
        store.markViewed("other", errorAt!!)
        handleSse(viewModel, indicatorEvent("other", "retry"))
        handleSse(viewModel, indicatorEvent("other", "idle"))
        assertNull(store.failedAt("other"))
        assertNull(viewModel.state.value.sessionReadStates["other"]?.failedAt)
    }

    @Test
    fun `status poll settles missing active sessions and preserves failures on idle snapshots`() = runTest {
        val store = testSessionReadStateStore()
        store.markFailed("snapshot", 10L)
        store.markFailed("success", 20L)
        val viewModel = createViewModel(store)
        updateState(viewModel) {
            it.copy(sessions = listOf("failed", "success", "snapshot").map { id -> Session(id, directory = "/test") },
                sessionStatuses = mapOf("failed" to SessionStatus("retry"), "success" to SessionStatus("busy")))
        }
        handleSse(viewModel, indicatorEvent("failed"))
        val errorAt = viewModel.state.value.pendingSessionErrorAt["failed"]
        coEvery { repository.getSessionStatus() } returns Result.success(mapOf("snapshot" to SessionStatus("idle")))
        invokePrivateNoArgs(viewModel, "loadSessionStatus")
        advanceUntilIdle()
        assertEquals(errorAt, store.failedAt("failed"))
        assertEquals(20L, store.failedAt("success"))
        assertEquals(10L, store.failedAt("snapshot"))
        assertTrue(viewModel.state.value.sessionStatuses["failed"]!!.isIdle)
        assertTrue(viewModel.state.value.sessionStatuses["success"]!!.isIdle)
        assertTrue(viewModel.state.value.pendingSessionErrorAt.isEmpty())
    }

    @Test
    fun `cold restore preserves read timestamps but selecting marks outgoing and incoming viewed`() = runTest {
        val store = testSessionReadStateStore()
        store.markViewed("old", 10L)
        store.markFailed("old", 20L)
        every { settingsManager.currentSessionId } returns "old"
        val viewModel = createViewModel(store)
        assertEquals("old", viewModel.state.value.currentSessionId)
        assertEquals(10L, viewModel.state.value.sessionReadStates["old"]?.lastViewedAt)
        viewModel.selectSession("new")
        advanceUntilIdle()
        assertTrue(store.lastViewedAt("old")!! > 20L)
        assertEquals(store.lastViewedAt("old"), store.lastViewedAt("new"))
        assertEquals(store.all(), viewModel.state.value.sessionReadStates)
    }

    @Test
    fun `deleting current session removes read state after selecting replacement`() = runTest {
        val store = testSessionReadStateStore()
        store.markFailed("old", 10L)
        val viewModel = createViewModel(store)
        updateState(viewModel) { it.copy(currentSessionId = "old", sessions = listOf(
            Session("old", directory = "/tmp"), Session("new", directory = "/tmp")
        ), pendingSessionErrorAt = mapOf("old" to 20L)) }
        coEvery { repository.deleteSession("old") } returns Result.success(Unit)
        viewModel.deleteSession("old")
        advanceUntilIdle()
        assertEquals("new", viewModel.state.value.currentSessionId)
        assertFalse(store.all().containsKey("old"))
        assertFalse(viewModel.state.value.pendingSessionErrorAt.containsKey("old"))
        assertEquals(store.all(), viewModel.state.value.sessionReadStates)
    }

    @Test
    fun `restoring session selects it and marks it viewed`() = runTest {
        val store = testSessionReadStateStore()
        val viewModel = createViewModel(store)
        val restored = Session("restored", directory = "/tmp")
        updateState(viewModel) { it.copy(sessions = listOf(restored.copy(time = Session.TimeInfo(archived = 1L)))) }
        coEvery { repository.updateSessionArchived("restored", any()) } returns Result.success(restored)
        viewModel.restoreSession("restored")
        advanceUntilIdle()
        assertEquals("restored", viewModel.state.value.currentSessionId)
        assertNotNull(store.lastViewedAt("restored"))
        assertEquals(store.all(), viewModel.state.value.sessionReadStates)
    }

    @Test
    fun `SSE disconnect and host switch discard tentative errors`() = runTest {
        val listener = slot<EventSourceListener>()
        val source = mockk<EventSource>(relaxed = true)
        val factory = mockk<EventSource.Factory>()
        mockkStatic(EventSources::class)
        every { EventSources.createFactory(any()) } returns factory
        every { factory.newEventSource(any(), capture(listener)) } returns source
        val client = SSEClient(OkHttpClient())
        every { repository.connectSSE(any(), any()) } answers {
            client.connect("http://server.test", onConnected = firstArg(), onDisconnected = secondArg())
        }
        val store = testSessionReadStateStore()
        val viewModel = createViewModel(store)
        updateState(viewModel) { it.copy(sessions = listOf(Session("other", directory = "/test")), sessionStatuses = mapOf("other" to SessionStatus("busy"))) }
        invokePrivateNoArgs(viewModel, "startSSE")
        runCurrent()
        listener.captured.onEvent(source, null, null,
            """{"payload":{"type":"session.error","properties":{"sessionID":"other"}}}""")
        runCurrent()
        assertNotNull(viewModel.state.value.pendingSessionErrorAt["other"])

        listener.captured.onFailure(source, IllegalStateException("disconnected"), null)
        runCurrent()
        assertTrue(viewModel.state.value.pendingSessionErrorAt.isEmpty())
        // Poll while the real SSEClient retry loop is still in its 1s backoff.
        invokePrivateNoArgs(viewModel, "loadSessionStatus")
        runCurrent()
        assertNull(store.failedAt("other"))
        assertTrue(viewModel.state.value.sessionStatuses["other"]!!.isIdle)
        advanceTimeBy(999)
        runCurrent()
        verify(exactly = 1) { factory.newEventSource(any(), any()) }
        handleSse(viewModel, indicatorEvent("other"))
        invokePrivateNoArgs(viewModel, "resetRuntimeForHostSwitch")
        runCurrent()
        assertTrue(viewModel.state.value.pendingSessionErrorAt.isEmpty())
    }

    @Test
    fun `init and active host refresh publish only that hosts read state`() = runTest {
        val store = testSessionReadStateStore()
        store.markViewed("shared", 10L)
        store.selectHost("other-host")
        store.markFailed("shared", 20L)
        val viewModel = createViewModel(store)
        assertEquals(10L, viewModel.state.value.sessionReadStates["shared"]?.lastViewedAt)
        assertNull(viewModel.state.value.sessionReadStates["shared"]?.failedAt)
        every { hostProfileStore.currentProfile() } returns
            HostProfile.defaultDirect("http://other.test").copy(id = "other-host")
        invokePrivateNoArgs(viewModel, "refreshHostProfileState")
        assertEquals("other-host", viewModel.state.value.currentHostProfileId)
        assertNull(viewModel.state.value.sessionReadStates["shared"]?.lastViewedAt)
        assertEquals(20L, viewModel.state.value.sessionReadStates["shared"]?.failedAt)
    }

    private fun reminderTree(store: SessionReadStateStore): MainViewModel {
        val vm = createViewModel(store)
        val sessions = listOf(
            Session("g", directory = "/test", time = Session.TimeInfo(updated = 0)),
            Session("p", directory = "/test", parentId = "g", time = Session.TimeInfo(updated = 0)),
            Session("c", directory = "/test", parentId = "p", time = Session.TimeInfo(updated = 100)),
            Session("d", directory = "/test", parentId = "p", time = Session.TimeInfo(updated = 200)),
            Session("x", directory = "/test", time = Session.TimeInfo(updated = 0)))
        sessions.forEach { store.markViewed(it.id, 0) }
        updateState(vm) { it.copy(sessions = sessions, sessionReadStates = store.all(),
            sessionStatuses = sessions.associate { s -> s.id to SessionStatus("idle") },
            statusKnownIds = sessions.map { s -> s.id }.toSet(), statusStreamConnected = true,
            appForeground = true) }
        return vm
    }

    @Test fun `selecting blue parent confirms only that row and keeps both children unread`() = runTest {
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD, vm.state.value.sessionPresentations["p"]?.indicator)
        vm.selectSession("p")
        vm.selectSession("x")
        assertNull(vm.state.value.sessionPresentations["p"]?.indicator)
        for (id in listOf("g", "c", "d")) assertEquals(
            com.yage.opencode_client.ui.session.SessionIndicator.UNREAD, vm.state.value.sessionPresentations[id]?.indicator)
        assertEquals(mapOf("c" to 100L, "d" to 200L), store.blueAcknowledgements("p"))
        assertTrue(store.blueAcknowledgements("g").isEmpty())
        assertTrue(store.blueAcknowledgements("c").isEmpty())
        assertEquals(0L, store.lastViewedAt("c"))
        assertEquals(0L, store.lastViewedAt("d"))
        assertEquals(2, vm.state.value.sessionUnreadCounts["g"])
    }

    @Test fun `viewing child removes its source from all ancestors without reading sibling`() = runTest {
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        vm.selectSession("c")
        vm.selectSession("x")
        for (id in listOf("p", "g")) assertEquals(mapOf("d" to 200L), vm.state.value.sessionBlueReminderSources[id])
        assertNull(vm.state.value.sessionPresentations["c"]?.indicator)
        assertEquals(0L, store.lastViewedAt("d"))
    }

    @Test fun `smaller sibling token and newly loaded child independently relight parent`() = runTest {
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        vm.selectSession("p")
        vm.selectSession("x")
        updateState(vm) { it.copy(sessions = it.sessions.map { s ->
            if (s.id == "c") s.copy(time = Session.TimeInfo(updated = 101)) else s }) }
        assertEquals(mapOf("c" to 101L), vm.state.value.sessionBlueReminderSources["p"])
        vm.selectSession("p")
        vm.selectSession("x")
        updateState(vm) { it.copy(sessions = it.sessions + Session("e", directory = "/test", parentId = "p",
            time = Session.TimeInfo(updated = 50)), statusKnownIds = it.statusKnownIds + "e") }
        assertEquals(mapOf("e" to 50L), vm.state.value.sessionBlueReminderSources["p"])
        assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD, vm.state.value.sessionPresentations["p"]?.indicator)
    }

    @Test fun `new child token during parent stay survives reselection background and exit`() = runTest {
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        vm.selectSession("p")
        updateState(vm) { it.copy(sessions = it.sessions.map { s ->
            if (s.id == "c") s.copy(time = Session.TimeInfo(updated = 101)) else s }) }
        assertNull(vm.state.value.sessionPresentations["p"]?.indicator)
        vm.selectSession("p")
        vm.setAppForeground(false)
        vm.selectSession("x")
        assertEquals(100L, store.blueAcknowledgements("p")["c"])
        updateState(vm) { it.copy(appForeground = true, statusStreamConnected = true,
            statusKnownIds = it.sessions.map { s -> s.id }.toSet()) }
        assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD, vm.state.value.sessionPresentations["p"]?.indicator)
    }

    @Test fun `pagination preserves acknowledgements while confirmed deletion cleans references`() = runTest {
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        vm.selectSession("p")
        vm.selectSession("x")
        val child = vm.state.value.sessions.first { it.id == "c" }
        updateState(vm) { it.copy(sessions = it.sessions.filterNot { s -> s.id == "c" }) }
        assertEquals(100L, store.blueAcknowledgements("p")["c"])
        updateState(vm) { it.copy(sessions = it.sessions + child) }
        assertNull(vm.state.value.sessionPresentations["p"]?.indicator)
        coEvery { repository.deleteSession("c") } returns Result.success(Unit)
        vm.deleteSession("c")
        advanceUntilIdle()
        assertEquals(mapOf("d" to 200L), store.blueAcknowledgements("p"))
        assertEquals(store.all(), vm.state.value.sessionReadStates)
    }

    @Test fun `parent own future update is not hidden by child acknowledgements`() = runTest {
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        vm.selectSession("p")
        vm.selectSession("x")
        val future = System.currentTimeMillis() + 60_000
        updateState(vm) { it.copy(sessions = it.sessions.map { s ->
            if (s.id == "p") s.copy(time = Session.TimeInfo(updated = future)) else s }) }
        assertEquals(mapOf("p" to future), vm.state.value.sessionBlueReminderSources["p"])
        assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD, vm.state.value.sessionPresentations["p"]?.indicator)
    }

    @Test fun `nonblue selection never acknowledges hidden child reminders`() = runTest {
        for (kind in listOf("yellow", "red", "busy", "retry", "unknown", "stale", "disconnected", "archived")) {
            val store = testSessionReadStateStore()
            val vm = reminderTree(store)
            if (kind == "red") store.markFailed("c", 300)
            updateState(vm) { s -> when (kind) {
                "yellow" -> s.copy(pendingPermissions = listOf(PermissionRequest("req", "c")))
                "red" -> s.copy(sessionReadStates = store.all())
                "busy", "retry", "unknown" -> s.copy(sessionStatuses = s.sessionStatuses + ("d" to SessionStatus(kind)))
                "stale" -> s.copy(statusKnownIds = emptySet())
                "disconnected" -> s.copy(statusStreamConnected = false)
                else -> s.copy(sessions = s.sessions.map { if (it.id == "p") it.copy(time = Session.TimeInfo(archived = 1)) else it })
            } }
            assertTrue(kind, vm.state.value.sessionPresentations["p"]?.indicator != com.yage.opencode_client.ui.session.SessionIndicator.UNREAD)
            vm.selectSession("p")
            vm.selectSession("x")
            assertTrue(kind, store.blueAcknowledgements("p").isEmpty())
            assertEquals(0L, store.lastViewedAt("c"))
            if (kind == "yellow") assertEquals(1, vm.state.value.pendingPermissions.size)
            if (kind == "red") assertEquals(300L, store.failedAt("c"))
        }
    }

    @Test fun `cold restore and host refresh preserve isolated parent acknowledgements`() = runTest {
        val store = testSessionReadStateStore()
        store.acknowledgeBlue("p", mapOf("c" to 100L))
        every { settingsManager.currentSessionId } returns "p"
        val vm = createViewModel(store)
        assertEquals(mapOf("c" to 100L), vm.state.value.sessionReadStates["p"]?.blueAckBySource)
        assertNull(store.lastViewedAt("p"))
        store.selectHost("other-host")
        store.acknowledgeBlue("p", mapOf("c" to 200L))
        every { hostProfileStore.currentProfile() } returns HostProfile.defaultDirect("http://other.test").copy(id = "other-host")
        invokePrivateNoArgs(vm, "refreshHostProfileState")
        assertEquals(mapOf("c" to 200L), vm.state.value.sessionReadStates["p"]?.blueAckBySource)
        store.selectHost("test-host")
        assertEquals(mapOf("c" to 100L), store.blueAcknowledgements("p"))
    }

    private fun parentFirst(vm: MainViewModel): List<Session> =
        vm.state.value.sessions.sortedBy { if (it.id == "p") 0 else 1 }

    private fun assertAutomaticParentKeptReminders(vm: MainViewModel, store: SessionReadStateStore) {
        assertEquals("p", vm.state.value.currentSessionId)
        assertTrue(store.blueAcknowledgements("p").isEmpty())
        assertNotNull(store.lastViewedAt("p")) // Keep the existing self-viewing behavior.
        assertEquals(0L, store.lastViewedAt("c"))
        assertEquals(0L, store.lastViewedAt("d"))
        vm.selectSession("x")
        assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD,
            vm.state.value.sessionPresentations["p"]?.indicator)
        vm.selectSession("p")
        assertEquals(mapOf("c" to 100L, "d" to 200L), store.blueAcknowledgements("p"))
    }

    @Test fun `refresh automatic selection never acknowledges blue parent for empty or missing current`() = runTest {
        for (current in listOf(null, "missing")) {
            val store = testSessionReadStateStore()
            val vm = reminderTree(store)
            val sessions = parentFirst(vm)
            updateState(vm) { it.copy(currentSessionId = current) }
            assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD,
                vm.state.value.sessionPresentations["p"]?.indicator)
            coEvery { repository.getSessions(any()) } returns Result.success(sessions)
            vm.loadSessions()
            advanceUntilIdle()
            assertAutomaticParentKeptReminders(vm, store)
            advanceUntilIdle()
        }
    }

    @Test fun `pagination automatic selection never acknowledges blue parent for empty or missing current`() = runTest {
        for (current in listOf(null, "missing")) {
            val store = testSessionReadStateStore()
            val vm = reminderTree(store)
            val sessions = parentFirst(vm)
            updateState(vm) { it.copy(currentSessionId = current, hasMoreSessions = true) }
            assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD,
                vm.state.value.sessionPresentations["p"]?.indicator)
            coEvery { repository.getSessions(any()) } returns Result.success(sessions)
            vm.loadMoreSessions()
            advanceUntilIdle()
            assertAutomaticParentKeptReminders(vm, store)
            advanceUntilIdle()
        }
    }

    @Test fun `deleting current automatically navigates to parent without acknowledging its children`() = runTest {
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        updateState(vm) { it.copy(sessions = parentFirst(vm), currentSessionId = "x") }
        coEvery { repository.deleteSession("x") } returns Result.success(Unit)
        vm.deleteSession("x")
        advanceUntilIdle()
        assertAutomaticParentKeptReminders(vm, store)
    }

    @Test fun `unarchive automatic navigation does not acknowledge restored parent reminders`() = runTest {
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        val sessions = vm.state.value.sessions
        updateState(vm) { it.copy(currentSessionId = "x", sessions = it.sessions.map { s ->
            if (s.id in setOf("p", "c", "d")) s.copy(time = s.time!!.copy(archived = 1)) else s }) }
        coEvery { repository.updateSessionArchived(any(), any()) } answers {
            Result.success(sessions.first { it.id == firstArg<String>() })
        }
        vm.restoreSession("p")
        advanceUntilIdle()
        assertAutomaticParentKeptReminders(vm, store)
    }

    @Test fun `cold restored parent refresh does not acknowledge child reminders`() = runTest {
        every { settingsManager.currentSessionId } returns "p"
        val store = testSessionReadStateStore()
        val vm = reminderTree(store)
        val sessions = parentFirst(vm)
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        vm.loadSessions()
        advanceUntilIdle()
        assertAutomaticParentKeptReminders(vm, store)
    }

    private fun updateState(viewModel: MainViewModel, transform: (AppState) -> AppState) {
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<AppState>
        flow.value = transform(flow.value)
    }

    private fun numberedSessions(count: Int): List<Session> {
        return (1..count).map { index ->
            Session(id = "session-$index", directory = "/tmp/$index")
        }
    }

    private suspend fun awaitSpeechWork(viewModel: MainViewModel) {
        val field = MainViewModel::class.java.getDeclaredField("speechTranscriptionJob")
        field.isAccessible = true
        repeat(3) {
            val job = field.get(viewModel) as? Job ?: return
            job.join()
        }
    }

    private suspend fun TestScope.startRealtimeCapture(sessionId: String?, input: String): RealtimeCapture {
        val session = mockk<VoiceFlowSession>(relaxed = true)
        val wav = File.createTempFile("opencode-empty-anchor", ".wav").apply { writeBytes(byteArrayOf(1)) }
        val owned = mutableMapOf<Pair<String, String>, String>()
        owned["test-host" to "session-b"] = "b draft"
        var legacyWrites = 0
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GPT_LIVE_TRANSCRIBE"
        every { settingsManager.getOwnedDraftOrNull(any(), any()) } answers {
            owned[invocation.args[0] as String to invocation.args[1] as String]
        }
        every { settingsManager.setOwnedDraft(any(), any(), any()) } answers {
            owned[invocation.args[0] as String to invocation.args[1] as String] = invocation.args[2] as String
        }
        every { settingsManager.setDraftText(any(), any()) } answers { legacyWrites += 1 }
        coEvery { voiceFlowClient.startSession(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE) } returns session
        coEvery {
            microphone.start(
                strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
                persist = any(),
                onPCMChunk = any(),
            )
        } just runs
        coEvery { microphone.stop() } returns wav
        lateinit var sendPartial: (String) -> Unit
        val results = ArrayDeque<CompletableDeferred<String>>()
        var commitArrived: CompletableDeferred<Unit>? = null
        coEvery { session.commitAndStop(any()) } coAnswers {
            sendPartial = firstArg()
            val result = CompletableDeferred<String>()
            results.add(result)
            commitArrived?.complete(Unit)
            result.await()
        }
        val viewModel = createViewModel()
        val writesAfterInit = legacyWrites
        updateState(viewModel) {
            it.copy(
                currentHostProfileId = "test-host",
                currentSessionId = sessionId,
                inputText = input,
                aiBuilderConnectionOK = true,
            )
        }
        viewModel.toggleRecording()
        runCurrent()
        assertTrue(viewModel.state.value.isRecording)
        return RealtimeCapture(
            viewModel = viewModel,
            owned = owned,
            legacyWrites = { legacyWrites },
            legacyWritesAfterInit = writesAfterInit,
            onPartial = { text ->
                sendPartial(text)
                runCurrent()
            },
            onStop = {
                commitArrived = CompletableDeferred()
                viewModel.toggleRecording()
                var waited = 0L
                while (commitArrived?.isCompleted != true && waited < 5_000) {
                    Thread.sleep(20)
                    waited += 20
                    runCurrent()
                }
                assertTrue(commitArrived?.isCompleted == true)
            },
            onFinish = { text ->
                results.removeFirst().complete(text)
                runCurrent()
            },
        )
    }

    private class RealtimeCapture(
        val viewModel: MainViewModel,
        val owned: MutableMap<Pair<String, String>, String>,
        val legacyWrites: () -> Int,
        val legacyWritesAfterInit: Int,
        private val onPartial: suspend (String) -> Unit,
        private val onStop: suspend () -> Unit,
        private val onFinish: suspend (String) -> Unit,
    ) {
        suspend fun partial(text: String) = onPartial(text)
        suspend fun stopAndCapture() = onStop()
        suspend fun finish(text: String) = onFinish(text)
    }

    private fun handleSse(viewModel: MainViewModel, event: SSEEvent) {
        val method = MainViewModel::class.java.getDeclaredMethod("handleSSEEvent", SSEEvent::class.java)
        method.isAccessible = true
        method.invoke(viewModel, event)
    }

    private fun publishSpeechForTest(viewModel: MainViewModel, target: SpeechDraftTarget, transcript: String) {
        val method = MainViewModel::class.java.getDeclaredMethod(
            "publishSpeech",
            SpeechDraftTarget::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(viewModel, target, transcript, false)
    }

    @Test fun `real permission HTTP failures retain pending and ancestor yellow`() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val http = OpenCodeRepository().apply { configure(server.url("/").toString()) }
            val vm = createViewModel()
            val pending = PermissionRequest("request", "child")
            updateState(vm) { it.copy(sessions = listOf(Session("parent", directory = "/test"),
                Session("child", directory = "/test", parentId = "parent")), pendingPermissions = listOf(pending)) }
            for (code in listOf(403, 500)) {
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(code).setBody("private-body-marker"))
                val done = CompletableDeferred<Unit>()
                coEvery { repository.respondPermission("child", "request", PermissionResponse.ONCE) } coAnswers {
                    http.respondPermission("child", "request", PermissionResponse.ONCE).also { done.complete(Unit) }
                }
                vm.respondPermission("child", "request", PermissionResponse.ONCE)
                done.await(); runCurrent()
                assertEquals(listOf(pending), vm.state.value.pendingPermissions)
                for (id in listOf("parent", "child")) assertEquals(
                    com.yage.opencode_client.ui.session.SessionIndicator.NEEDS_YOU,
                    vm.state.value.sessionPresentations[id]!!.indicator)
                assertFalse(vm.state.value.error.orEmpty().contains("private-body-marker"))
            }
        } finally { server.shutdown() }
    }

    @Test fun `unviewed child failure survives later success and only child viewing removes ancestor contribution`() = runTest {
        val store = testSessionReadStateStore()
        val vm = createViewModel(store)
        updateState(vm) { it.copy(sessions = listOf(Session("parent", directory = "/test"),
            Session("child", directory = "/test", parentId = "parent")), currentSessionId = "parent", statusStreamConnected = true) }
        handleSse(vm, indicatorEvent("child"))
        handleSse(vm, indicatorEvent("child", "idle"))
        for (type in listOf("busy", "retry", "idle")) {
            handleSse(vm, indicatorEvent("child", type))
            for (id in listOf("parent", "child")) assertEquals(
                com.yage.opencode_client.ui.session.SessionIndicator.FAILED, vm.state.value.sessionPresentations[id]!!.indicator)
        }
        vm.selectSession("child"); runCurrent()
        assertTrue(vm.state.value.sessionFailureCounts.isEmpty())
    }

    @Test fun `fresh same-value SSE survives snapshot expiry and repeated failed GETs`() = runTest {
        val vm = createViewModel()
        val initialTime = 1_000L
        updateState(vm) { it.copy(sessions = listOf(Session("child", directory = "/test"),
            Session("snapshot-only", directory = "/test")), statusStreamConnected = true,
            statusSnapshotKnown = true, statusConfirmedAt = initialTime,
            statusKnownIds = setOf("child", "snapshot-only"),
            sessionStatuses = mapOf("child" to SessionStatus("busy"), "snapshot-only" to SessionStatus("idle"))) }
        coEvery { repository.getSessionStatusInDirectory(any()) } returns Result.failure(IllegalStateException("offline"))
        val stateField = MainViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val flow = stateField.get(vm) as MutableStateFlow<AppState>
        val job = com.yage.opencode_client.ui.launchSseWatchdog(backgroundScope, flow, { _, _ -> },
            { invokePrivateNoArgs(vm, "loadSessionStatus") }, { initialTime + testScheduler.currentTime }, {},
            { initialTime + testScheduler.currentTime })
        runCurrent(); advanceTimeBy(64_000); runCurrent()
        repeat(2) { handleSse(vm, indicatorEvent("child", "busy")) }
        advanceTimeBy(7_000); runCurrent()
        assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.RUNNING, vm.state.value.sessionPresentations["child"]!!.indicator)
        assertTrue(vm.state.value.sessionPresentations["snapshot-only"]!!.unknown)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.RUNNING, vm.state.value.sessionPresentations["child"]!!.indicator)
        job.cancel()
    }

    @Test fun `permission replied is exact and stale GET cannot resurrect resolved requests`() = runTest {
        val vm = createViewModel()
        val p = PermissionRequest("p", "child")
        val sibling = PermissionRequest("p", "sibling")
        updateState(vm) { it.copy(pendingPermissions = listOf(p, sibling)) }
        val old = CompletableDeferred<Result<List<PermissionRequest>>>()
        coEvery { repository.getPendingPermissions() } coAnswers { old.await() } andThen Result.success(listOf(sibling))
        vm.loadPendingPermissions()
        runCurrent()
        handleSse(vm, SSEEvent(payload = SSEPayload(type = "permission.replied", properties = buildJsonObject {
            put("sessionID", JsonPrimitive("child")); put("requestID", JsonPrimitive("p"))
        })))
        assertEquals(listOf(sibling), vm.state.value.pendingPermissions)
        old.complete(Result.success(listOf(p, sibling)))
        advanceUntilIdle()
        assertEquals(listOf(sibling), vm.state.value.pendingPermissions)
    }

    @Test fun `question asked during GET survives obsolete snapshot`() = runTest {
        val vm = createViewModel()
        val old = CompletableDeferred<Result<List<QuestionRequest>>>()
        val q = QuestionRequest(id = "new", sessionId = "child", questions = emptyList())
        coEvery { repository.getPendingQuestions() } coAnswers { old.await() } andThen Result.success(listOf(q))
        vm.loadPendingQuestions(); runCurrent()
        handleSse(vm, SSEEvent(payload = SSEPayload(type = "question.asked", properties = buildJsonObject {
            put("id", JsonPrimitive("new")); put("sessionID", JsonPrimitive("child")); put("questions", buildJsonArray {})
        })))
        old.complete(Result.success(emptyList())); advanceUntilIdle()
        assertEquals(listOf(q), vm.state.value.pendingQuestions)
    }

    @Test fun `status snapshot preserves newer and same-value SSE and host generation`() = runTest {
        val vm = createViewModel()
        val old = CompletableDeferred<Result<Map<String, SessionStatus>>>()
        coEvery { repository.getSessionStatus() } coAnswers { old.await() }
        invokePrivateNoArgs(vm, "loadSessionStatus"); runCurrent()
        repeat(2) {
            handleSse(vm, SSEEvent(payload = SSEPayload(type = "session.status", properties = buildJsonObject {
                put("sessionID", JsonPrimitive("child")); put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
            })))
        }
        assertEquals(2L, vm.state.value.statusRevisions["child"])
        old.complete(Result.success(emptyMap())); advanceUntilIdle()
        assertTrue(vm.state.value.sessionStatuses["child"]!!.isBusy)
        val other = CompletableDeferred<Result<Map<String, SessionStatus>>>()
        coEvery { repository.getSessionStatus() } coAnswers { other.await() }
        invokePrivateNoArgs(vm, "loadSessionStatus"); runCurrent()
        updateState(vm) { it.copy(runtimeGeneration = it.runtimeGeneration + 1, sessionStatuses = emptyMap()) }
        other.complete(Result.success(mapOf("old" to SessionStatus("busy")))); advanceUntilIdle()
        assertTrue(vm.state.value.sessionStatuses.isEmpty())
    }

    @Test fun `reconnect refreshes global pending even without current session`() = runTest {
        val vm = createViewModel()
        val connected = slot<(() -> Unit)?>()
        every { repository.connectSSE(captureNullable(connected), any()) } returns emptyFlow()
        invokePrivateNoArgs(vm, "startSSE"); runCurrent()
        connected.captured!!.invoke(); runCurrent()
        coVerify { repository.getPendingPermissions() }
        coVerify { repository.getPendingQuestions() }
    }

    @Test fun `failed status snapshot does not confirm null and scoped snapshot does`() = runTest {
        val vm = createViewModel()
        updateState(vm) { it.copy(sessions = listOf(Session("s", directory = "/project", time = Session.TimeInfo(updated = 100))), statusStreamConnected = true) }
        coEvery { repository.getSessionStatusInDirectory("/project") } returns Result.failure(IllegalStateException("offline"))
        invokePrivateNoArgs(vm, "loadSessionStatus"); advanceUntilIdle()
        assertTrue(vm.state.value.sessionPresentations["s"]!!.unknown)
        assertNull(vm.state.value.sessionPresentations["s"]!!.indicator)
        coEvery { repository.getSessionStatusInDirectory("/project") } returns Result.success(emptyMap())
        invokePrivateNoArgs(vm, "loadSessionStatus"); advanceUntilIdle()
        assertFalse(vm.state.value.sessionPresentations["s"]!!.unknown)
        assertEquals(com.yage.opencode_client.ui.session.SessionIndicator.UNREAD, vm.state.value.sessionPresentations["s"]!!.indicator)
        updateState(vm) { it.copy(sessions = it.sessions + listOf(
            Session("other", directory = "/other", time = Session.TimeInfo(updated = 100)),
            Session("new", directory = "/project", time = Session.TimeInfo(updated = 100)))) }
        assertTrue(vm.state.value.sessionPresentations["other"]!!.unknown)
        assertTrue(vm.state.value.sessionPresentations["new"]!!.unknown)
    }

    @Test fun `old host pending callback and failed replies cannot remove current requests`() = runTest {
        val vm = createViewModel()
        val p = PermissionRequest("p", "s")
        updateState(vm) { it.copy(pendingPermissions = listOf(p)) }
        coEvery { repository.respondPermission(any(), any(), any()) } returns Result.failure(IllegalStateException("offline"))
        vm.respondPermission("s", "p", PermissionResponse.ONCE); advanceUntilIdle()
        assertEquals(listOf(p), vm.state.value.pendingPermissions)
        val old = CompletableDeferred<Result<List<PermissionRequest>>>()
        coEvery { repository.getPendingPermissions() } coAnswers { old.await() }
        vm.loadPendingPermissions(); runCurrent()
        updateState(vm) { it.copy(runtimeGeneration = it.runtimeGeneration + 1, pendingPermissions = emptyList()) }
        old.complete(Result.success(listOf(p))); advanceUntilIdle()
        assertTrue(vm.state.value.pendingPermissions.isEmpty())
    }

    @Test fun `healthy heartbeats still receive low frequency status confirmation without selection`() = runTest {
        val state = MutableStateFlow(AppState())
        var calls = 0
        val job = com.yage.opencode_client.ui.launchSseWatchdog(backgroundScope, state,
            { _, _ -> }, { calls++ }, { testScheduler.currentTime }, {}, { testScheduler.currentTime })
        runCurrent(); advanceTimeBy(30_001); runCurrent()
        assertEquals(1, calls)
        state.value = state.value.copy(appForeground = false)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, calls)
        job.cancel()
    }

    private fun invokePrivateNoArgs(viewModel: MainViewModel, name: String) {
        val method = MainViewModel::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(viewModel)
    }

    private fun setSseLastFrameAtMs(viewModel: MainViewModel, value: Long) {
        val field = MainViewModel::class.java.getDeclaredField("sseLastFrameAtMs")
        field.isAccessible = true
        field.setLong(viewModel, value)
    }

    /** The watchdog loop is infinite; cancel it before the test ends or
     *  runTest's final idle flush spins forever re-running its 5s delay. */
    private fun cancelSseWatchdog(viewModel: MainViewModel) {
        val field = MainViewModel::class.java.getDeclaredField("watchdogJob")
        field.isAccessible = true
        (field.get(viewModel) as? Job)?.cancel()
    }

    private fun loadAgents(viewModel: MainViewModel) {
        val method = MainViewModel::class.java.getDeclaredMethod("loadAgents")
        method.isAccessible = true
        method.invoke(viewModel)
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
    fun `init seeds model shortlist and configures repository`() = runTest {
        every { settingsManager.selectedModelIndex } returns 999

        val viewModel = createViewModel()

        assertEquals(seedShortlistFromPresets().size, viewModel.state.value.modelShortlist.size)
        assertTrue(viewModel.state.value.selectedModelIndex in viewModel.state.value.modelShortlist.indices)
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
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
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
                Message.ModelInfo(selected.providerId, selected.modelId),
                any(),
                any()
            )
        }
        assertEquals("", viewModel.state.value.inputText)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `sendMessage ignores duplicate sends while request is in flight`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } coAnswers {
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

        coVerify(exactly = 1) { repository.sendMessage(any(), any(), any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.sendingSessionIds.contains("session-1"))
    }

    @Test
    fun `sendMessage success refreshes sessions`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
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
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
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
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("send failed"))

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
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

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
                any(),
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

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any(), any()) }
        assertEquals("do not send yet", viewModel.state.value.inputText)
    }

    @Test
    fun `sendMessage inserts optimistic user row and clears input immediately`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(100)
            Result.success(Unit)
        }
        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()

        // The optimistic row is inserted synchronously, before the network call resolves.
        val state = viewModel.state.value
        assertEquals("", state.inputText)
        assertTrue(state.sendingSessionIds.contains("session-1"))
        val optimistic = state.messages.lastOrNull()
        assertNotNull(optimistic)
        assertEquals("user", optimistic!!.info.role)
        assertEquals("hello", optimistic.parts.first { it.isText }.text)
        assertTrue(state.pendingOptimisticMessageIds.contains(optimistic.info.id))
    }

    @Test
    fun `sendMessage failure removes optimistic row and restores input`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns
                Result.failure(IllegalStateException("send failed"))
        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        // Optimistic row is present immediately after the send is dispatched.
        assertTrue(viewModel.state.value.messages.isNotEmpty())
        advanceUntilIdle()

        // After the failure, the optimistic row is dropped and the input restored.
        val state = viewModel.state.value
        assertEquals("hello", state.inputText)
        assertEquals("send failed", state.error)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.pendingOptimisticMessageIds.isEmpty())
    }

    @Test
    fun `sendMessage success keeps optimistic row until server confirms it`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getSessions(400) } returns Result.success(
            listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        // The server has not echoed the message yet, so the optimistic row stays visible.
        val state = viewModel.state.value
        val optimistic = state.messages.lastOrNull()
        assertNotNull(optimistic)
        assertEquals("user", optimistic!!.info.role)
        assertTrue(state.pendingOptimisticMessageIds.contains(optimistic.info.id))
    }

    @Test
    fun `sendMessage failure does not clobber another session draft after switching`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(100)
            Result.failure(IllegalStateException("send failed"))
        }
        every { settingsManager.getDraftText("session-2") } returns "session-2 draft"

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        // Switch to another session while session-1's send is still in flight.
        viewModel.selectSession("session-2")
        advanceUntilIdle()

        // session-1's send failed, but session-2's draft must be preserved.
        val state = viewModel.state.value
        assertEquals("session-2", state.currentSessionId)
        assertEquals("session-2 draft", state.inputText)
        assertEquals("send failed", state.error)
        assertTrue(state.pendingOptimisticMessageIds.isEmpty())
    }

    @Test
    fun `session_error SSE removes pending optimistic row and recovers text`() = runTest {
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(
                    MessageWithParts(
                        info = Message(id = "msg_pending", sessionId = "session-1", role = "user"),
                        parts = listOf(
                            Part(id = "p1", messageId = "msg_pending", sessionId = "session-1", type = "text", text = "hello")
                        )
                    )
                ),
                pendingOptimisticMessageIds = setOf("msg_pending")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.error",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "error",
                            buildJsonObject {
                                put("name", JsonPrimitive("ProviderAuthError"))
                                put("data", buildJsonObject { put("message", JsonPrimitive("nope")) })
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.messages.isEmpty())
        assertTrue(state.pendingOptimisticMessageIds.isEmpty())
        assertEquals("hello", state.inputText)
        assertEquals("Send failed: ProviderAuthError: nope", state.error)
    }

    @Test
    fun `recreated layout still migrates the visible chat preview without restamping owner`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentHostProfileId = "host-b",
                currentSessionId = "session-b",
                sessions = listOf(Session(id = "session-b", directory = "/b")),
                appliedLayout = com.yage.opencode_client.ui.EffectiveLayout.COMPACT,
                columnPreview = com.yage.opencode_client.ui.OwnedFilePreview(
                    path = "old-a.md",
                    hostId = "host-a",
                    sessionId = "session-a",
                    workspace = "/a",
                    kind = com.yage.opencode_client.ui.FilePreviewKind.CHAT,
                ),
            )
        }
        val inline = com.yage.opencode_client.ui.chat.ChatFilePreviewRequest("visible.md", "host-b", "session-b", "/b")
        val next = viewModel.reconcilePreviewLayout(com.yage.opencode_client.ui.EffectiveLayout.REGULAR, inline)
        assertNull(next)
        assertEquals("visible.md", viewModel.state.value.columnPreview?.path)
        assertEquals("session-b", viewModel.state.value.columnPreview?.sessionId)
        assertEquals("host-b", viewModel.state.value.columnPreview?.hostId)
    }

    @Test
    fun `session change clears chat column and keeps files navigation`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentHostProfileId = "test-host",
                currentSessionId = "session-a",
                appliedLayout = com.yage.opencode_client.ui.EffectiveLayout.REGULAR,
                columnPreview = com.yage.opencode_client.ui.OwnedFilePreview(
                    "notes.md", "test-host", "session-a", "/work", com.yage.opencode_client.ui.FilePreviewKind.CHAT,
                ),
                filePathToShowInFiles = "notes.md",
            )
        }
        viewModel.showFilesBrowserFile("tree.kt")
        viewModel.selectSession("session-b")
        advanceUntilIdle()
        viewModel.reconcilePreviewLayout(com.yage.opencode_client.ui.EffectiveLayout.REGULAR, null)
        assertEquals("tree.kt", viewModel.state.value.columnPreview?.path)
        assertEquals(com.yage.opencode_client.ui.FilePreviewKind.FILES, viewModel.state.value.columnPreview?.kind)
    }

    @Test
    fun `deferred paste is written to the starting session instead of dropped`() = runTest {
        every { settingsManager.getDraftText("destination") } returns "destination draft"
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentHostProfileId = "test-host", currentSessionId = "source", inputText = "hello")
        }
        viewModel.reportComposerCaret(focused = true, start = 5, end = 5, composing = true)
        viewModel.pasteComposerText(listOf("note"))
        viewModel.selectSession("destination")
        advanceUntilIdle()
        assertEquals("destination draft", viewModel.state.value.inputText)
        verify { settingsManager.setOwnedDraft("test-host", "source", "hellonote") }
    }

    @Test
    fun `focused paste tail survives the next speech result`() = runTest {
        every { settingsManager.aiBuilderToken } returns "token"
        every { settingsManager.aiBuilderRecordingStrategy } returns "GROK_BATCH"
        coEvery {
            microphone.start(strategy = com.yage.voiceflowkit.VoiceFlowRecordingStrategy.GROK_BATCH, persist = any(), onPCMChunk = null)
        } just runs
        val grokFile = File.createTempFile("opencode-paste-tail", ".wav").apply { writeBytes(byteArrayOf(1)) }
        coEvery { microphone.stop() } returns grokFile
        coEvery {
            voiceFlowClient.transcribe(grokFile, com.yage.voiceflowkit.VoiceFlowRecordingStrategy.GROK_BATCH, any())
        } returns com.yage.voiceflowkit.TranscriptionResult(text = "world", requestId = "tail")
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentHostProfileId = "test-host", currentSessionId = "source", inputText = "hello", aiBuilderConnectionOK = true)
        }
        viewModel.toggleRecording()
        runCurrent()
        viewModel.reportComposerCaret(true, 5, 5, false)
        viewModel.pasteComposerText(listOf("note"))
        assertEquals("hellonote", viewModel.state.value.inputText)
        assertEquals(9, viewModel.state.value.composerCursor)
        assertEquals(1L, viewModel.state.value.composerCursorRevision)
        viewModel.toggleRecording()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.inputText.contains("note"))
        assertTrue(viewModel.state.value.inputText.contains("world"))
        grokFile.delete()
    }

    @Test
    fun `send failure after reselect writes owned draft and does not read legacy`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(50)
            Result.failure(IllegalStateException("send failed"))
        }
        every { settingsManager.getDraftText(any()) } returns "legacy draft"
        every { settingsManager.getOwnedDraftOrNull("test-host", "session-1") } returns "owned"
        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")
        viewModel.sendMessage()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        verify(exactly = 0) { settingsManager.getDraftText("session-1") }
        verify { settingsManager.setOwnedDraft("test-host", "session-1", "hello\nowned") }
    }

    @Test
    fun `session_error SSE for another session is ignored`() = runTest {
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(
                    MessageWithParts(
                        info = Message(id = "msg_pending", sessionId = "session-1", role = "user"),
                        parts = listOf(
                            Part(id = "p1", messageId = "msg_pending", sessionId = "session-1", type = "text", text = "hello")
                        )
                    )
                ),
                pendingOptimisticMessageIds = setOf("msg_pending")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.error",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-2"))
                        put("error", buildJsonObject { put("name", JsonPrimitive("X")) })
                    }
                )
            )
        )
        advanceUntilIdle()

        // The error belongs to a different session, so the pending row is untouched.
        assertEquals(1, viewModel.state.value.messages.size)
        assertTrue(viewModel.state.value.pendingOptimisticMessageIds.contains("msg_pending"))
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `session error after leaving restores source owned draft without touching current session`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(60_000)
            Result.success(Unit)
        }
        val owned = mutableMapOf<Pair<String, String>, String>()
        owned["test-host" to "session-b"] = "b draft"
        var legacyReads = 0
        every { settingsManager.getOwnedDraftOrNull(any(), any()) } answers {
            owned[invocation.args[0] as String to invocation.args[1] as String]
        }
        every { settingsManager.setOwnedDraft(any(), any(), any()) } answers {
            owned[invocation.args[0] as String to invocation.args[1] as String] = invocation.args[2] as String
        }
        every { settingsManager.getDraftText(any()) } answers {
            legacyReads += 1
            "legacy-unscoped"
        }
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(currentHostProfileId = "test-host", currentSessionId = "session-a", inputText = "hello")
        }
        viewModel.sendMessage()
        viewModel.selectSession("session-b")
        val kept = ComposerImageAttachment("img-b", "b.png", "image/png", "data:image/png,b", byteArrayOf(2), 1)
        updateState(viewModel) { it.copy(imageAttachments = listOf(kept)) }
        val before = viewModel.state.value
        val readsBefore = legacyReads
        val error = SSEEvent(
            payload = SSEPayload(
                type = "session.error",
                properties = buildJsonObject {
                    put("sessionID", JsonPrimitive("session-a"))
                    put("error", buildJsonObject { put("name", JsonPrimitive("X")) })
                }
            )
        )
        handleSse(viewModel, error)
        val after = viewModel.state.value
        assertEquals("session-b", after.currentSessionId)
        assertEquals(before.inputText, after.inputText)
        assertEquals(before.messages, after.messages)
        assertEquals(before.imageAttachments, after.imageAttachments)
        assertEquals(before.error, after.error)
        assertEquals("b draft", after.inputText)
        assertEquals("hello", owned["test-host" to "session-a"])
        assertFalse(owned["test-host" to "session-a"]!!.contains("legacy"))
        assertEquals(readsBefore, legacyReads)
        handleSse(viewModel, error)
        assertEquals("hello", owned["test-host" to "session-a"])
        assertEquals(before.inputText, viewModel.state.value.inputText)
        assertEquals(before.imageAttachments, viewModel.state.value.imageAttachments)
    }

    @Test
    fun `null session speech keeps focused paste tail and does not cross host`() = runTest {
        var legacyReads = 0
        var legacyWrites = 0
        every { settingsManager.getDraftText(any()) } answers {
            legacyReads += 1
            ""
        }
        every { settingsManager.setDraftText(any(), any()) } answers { legacyWrites += 1 }
        val viewModel = createViewModel()
        val readsAfterInit = legacyReads
        val writesAfterInit = legacyWrites
        updateState(viewModel) {
            it.copy(currentHostProfileId = "test-host", currentSessionId = null, inputText = "hello")
        }
        viewModel.reportComposerCaret(focused = true, start = 5, end = 5, composing = false)
        viewModel.pasteComposerText(listOf("note"))
        assertEquals("hellonote", viewModel.state.value.inputText)
        val target = SpeechDraftTarget(sessionId = null, existingInput = "hello", hostId = "test-host")
        publishSpeechForTest(viewModel, target, "world")
        assertEquals("hello worldnote", viewModel.state.value.inputText)
        publishSpeechForTest(viewModel, target, "world!")
        assertEquals("hello world!note", viewModel.state.value.inputText)
        updateState(viewModel) { it.copy(currentHostProfileId = "other-host") }
        publishSpeechForTest(viewModel, target, "gone")
        assertEquals("hello world!note", viewModel.state.value.inputText)
        assertEquals(readsAfterInit, legacyReads)
        assertEquals(writesAfterInit, legacyWrites)
    }

    @Test
    fun `empty recording keeps paste before the first partial for a session and a new round`() = runTest {
        val harness = startRealtimeCapture(sessionId = "session-a", input = "")
        harness.viewModel.reportComposerCaret(focused = true, start = 0, end = 0, composing = false)
        harness.viewModel.pasteComposerText(listOf("note"))
        assertEquals("note", harness.viewModel.state.value.inputText)
        harness.stopAndCapture()
        harness.partial("world")
        assertEquals("worldnote", harness.viewModel.state.value.inputText)
        harness.partial("world!")
        assertEquals("world!note", harness.viewModel.state.value.inputText)
        harness.partial("")
        assertEquals("world!note", harness.viewModel.state.value.inputText)
        assertEquals(1, harness.viewModel.state.value.inputText.split("note").size - 1)
        harness.viewModel.selectSession("session-b")
        harness.partial("hijack")
        assertEquals("b draft", harness.viewModel.state.value.inputText)
        harness.finish("world!")
        assertEquals("b draft", harness.viewModel.state.value.inputText)
        assertEquals("world!note", harness.owned["test-host" to "session-a"])
        harness.viewModel.selectSession("session-a")
        harness.viewModel.setInputText("")
        harness.viewModel.toggleRecording()
        runCurrent()
        harness.stopAndCapture()
        harness.partial("fresh")
        harness.finish("fresh")
        assertEquals("fresh", harness.viewModel.state.value.inputText)
        assertFalse(harness.viewModel.state.value.inputText.contains("note"))
    }

    @Test
    fun `empty recording keeps unfocused paste before the first partial when session is null`() = runTest {
        val harness = startRealtimeCapture(sessionId = null, input = "")
        harness.viewModel.pasteComposerText(listOf("note"))
        assertEquals("note", harness.viewModel.state.value.inputText)
        harness.stopAndCapture()
        harness.partial("world")
        assertEquals("worldnote", harness.viewModel.state.value.inputText)
        harness.partial("")
        assertEquals("worldnote", harness.viewModel.state.value.inputText)
        harness.partial("world!")
        assertEquals("world!note", harness.viewModel.state.value.inputText)
        assertEquals(1, harness.viewModel.state.value.inputText.split("note").size - 1)
        updateState(harness.viewModel) { it.copy(currentHostProfileId = "other-host") }
        harness.partial("hijack")
        assertEquals("world!note", harness.viewModel.state.value.inputText)
        harness.finish("world!")
        assertEquals("world!note", harness.viewModel.state.value.inputText)
        assertEquals(harness.legacyWritesAfterInit, harness.legacyWrites())
    }

    @Test
    fun `host switch clears pending optimistic message ids`() = runTest {
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

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                isConnected = true,
                currentSessionId = "session-1",
                messages = listOf(MessageWithParts(Message(id = "msg_pending", sessionId = "session-1", role = "user"))),
                pendingOptimisticMessageIds = setOf("msg_pending")
            )
        }

        viewModel.selectHostProfile(second.id)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.pendingOptimisticMessageIds.isEmpty())
        assertNull(viewModel.state.value.currentSessionId)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("   ")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any(), any()) }
        assertEquals("   ", viewModel.state.value.inputText)
    }

    @Test
    fun `sendMessage ignores request when no session is selected`() = runTest {
        val viewModel = createViewModel()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any(), any(), any()) }
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
    fun `message created SSE on other session triggers no rest refresh`() = runTest {
        coEvery { repository.getSessions(400) } returns Result.success(
            listOf(
                com.yage.opencode_client.data.model.Session(id = "session-2", directory = "/tmp/project"),
                com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project")
            )
        )
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(
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
        advanceTimeBy(1000)
        advanceUntilIdle()

        // Other-session message events refresh the session list; the chained
        // current-session messages refresh is a loadSessions side effect.
        coVerify { repository.getSessions(any()) }
        coVerify(exactly = 0) { repository.getMessages("session-2", any()) }
        assertEquals(listOf("session-2", "session-1"), viewModel.state.value.sessions.map { it.id })
    }

    @Test
    fun `message updated SSE without info falls back to messages refresh only`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
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
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        // No parseable info payload: fall back to both REST refreshes.
        coVerify { repository.getSessions(any()) }
        assertEquals(messages, viewModel.state.value.messages)
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
    fun `refresh after load more keeps expanded window and tail sessions`() = runTest {
        coEvery { repository.getSessions(400) } returns Result.success(numberedSessions(400))
        coEvery { repository.getSessions(800) } returns Result.success(numberedSessions(800))

        val viewModel = createViewModel()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.loadMoreSessions()
        advanceUntilIdle()
        viewModel.loadSessions()
        advanceUntilIdle()

        coVerifyOrder {
            repository.getSessions(400)
            repository.getSessions(800)
            repository.getSessions(800)
        }
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertEquals(800, viewModel.state.value.sessions.size)
        assertTrue(viewModel.state.value.sessions.any { it.id == "session-650" })
        assertTrue(viewModel.state.value.hasMoreSessions)
    }

    @Test
    fun `refresh after two load more requests still uses 1200`() = runTest {
        coEvery { repository.getSessions(400) } returns Result.success(numberedSessions(400))
        coEvery { repository.getSessions(800) } returns Result.success(numberedSessions(800))
        coEvery { repository.getSessions(1200) } returns Result.success(numberedSessions(1200))

        val viewModel = createViewModel()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.loadMoreSessions()
        advanceUntilIdle()
        viewModel.loadMoreSessions()
        advanceUntilIdle()
        viewModel.loadSessions()
        advanceUntilIdle()

        coVerifyOrder {
            repository.getSessions(400)
            repository.getSessions(800)
            repository.getSessions(1200)
            repository.getSessions(1200)
        }
        assertEquals(1200, viewModel.state.value.loadedSessionLimit)
        assertEquals(1200, viewModel.state.value.sessions.size)
    }

    @Test
    fun `refresh after partial window keeps hasMore false and same limit`() = runTest {
        coEvery { repository.getSessions(400) } returns Result.success(numberedSessions(400))
        coEvery { repository.getSessions(800) } returns Result.success(numberedSessions(450))

        val viewModel = createViewModel()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.loadMoreSessions()
        advanceUntilIdle()
        viewModel.loadSessions()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getSessions(800) }
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertEquals(450, viewModel.state.value.sessions.size)
        assertFalse(viewModel.state.value.hasMoreSessions)
    }

    @Test
    fun `refresh failure after load more does not shrink window`() = runTest {
        coEvery { repository.getSessions(400) } returns Result.success(numberedSessions(400))
        var refreshShouldFail = false
        coEvery { repository.getSessions(800) } coAnswers {
            if (refreshShouldFail) {
                Result.failure(IllegalStateException("network error"))
            } else {
                Result.success(numberedSessions(800))
            }
        }

        val viewModel = createViewModel()
        viewModel.loadSessions()
        advanceUntilIdle()
        viewModel.loadMoreSessions()
        advanceUntilIdle()
        refreshShouldFail = true
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertEquals(800, viewModel.state.value.sessions.size)
        assertTrue(viewModel.state.value.hasMoreSessions)
        assertTrue(viewModel.state.value.sessions.any { it.id == "session-650" })
        assertFalse(viewModel.state.value.isRefreshingSessions)
        assertEquals("Failed to load sessions: network error", viewModel.state.value.error)
    }

    @Test
    fun `stale smaller refresh is dropped after expanded window arrives first`() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val moreGate = CompletableDeferred<Unit>()
        coEvery { repository.getSessions(400) } coAnswers {
            refreshGate.await()
            Result.success(numberedSessions(400))
        }
        coEvery { repository.getSessions(800) } coAnswers {
            moreGate.await()
            Result.success(numberedSessions(800))
        }

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-20") }
        viewModel.loadSessions()
        runCurrent()
        viewModel.loadMoreSessions()
        runCurrent()

        moreGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertEquals(800, viewModel.state.value.sessions.size)

        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertEquals(800, viewModel.state.value.sessions.size)
        assertTrue(viewModel.state.value.sessions.any { it.id == "session-650" })
        assertEquals("session-20", viewModel.state.value.currentSessionId)
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `expanded window still replaces list when smaller refresh finishes first`() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val moreGate = CompletableDeferred<Unit>()
        coEvery { repository.getSessions(400) } coAnswers {
            refreshGate.await()
            Result.success(numberedSessions(400))
        }
        coEvery { repository.getSessions(800) } coAnswers {
            moreGate.await()
            Result.success(numberedSessions(800))
        }

        val viewModel = createViewModel()
        viewModel.loadSessions()
        runCurrent()
        viewModel.loadMoreSessions()
        runCurrent()

        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(400, viewModel.state.value.loadedSessionLimit)
        assertEquals(400, viewModel.state.value.sessions.size)

        moreGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertEquals(800, viewModel.state.value.sessions.size)
        assertTrue(viewModel.state.value.sessions.any { it.id == "session-650" })
    }

    @Test
    fun `refresh does not clear in-flight load more flag`() = runTest {
        val moreGate = CompletableDeferred<Unit>()
        coEvery { repository.getSessions(400) } returns Result.success(numberedSessions(400))
        coEvery { repository.getSessions(800) } coAnswers {
            moreGate.await()
            Result.success(numberedSessions(800))
        }

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = numberedSessions(400),
                loadedSessionLimit = 400,
                hasMoreSessions = true
            )
        }
        viewModel.loadMoreSessions()
        runCurrent()
        assertTrue(viewModel.state.value.isLoadingMoreSessions)

        viewModel.loadSessions()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isLoadingMoreSessions)
        viewModel.loadMoreSessions()
        coVerify(exactly = 1) { repository.getSessions(800) }

        moreGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isLoadingMoreSessions)
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
    }

    @Test
    fun `sse refresh after load more keeps expanded window`() = runTest {
        coEvery { repository.getSessions(800) } returns Result.success(numberedSessions(800))
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-20",
                sessions = numberedSessions(800),
                loadedSessionLimit = 800,
                hasMoreSessions = true
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
                                put("id", JsonPrimitive("session-20"))
                                put("directory", JsonPrimitive("/tmp/20"))
                                put("title", JsonPrimitive("Updated"))
                            }
                        )
                    }
                )
            )
        )
        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-99"))
                    }
                )
            )
        )
        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-20"))
                        put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
                    }
                )
            )
        )
        advanceUntilIdle()

        // session.updated + session.status idle refresh the session list;
        // message.updated on a non-current session no longer does.
        coVerify(atLeast = 2) { repository.getSessions(800) }
        coVerify(exactly = 0) { repository.getSessions(400) }
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertTrue(viewModel.state.value.sessions.any { it.id == "session-650" })
    }

    @Test
    fun `sendMessage refresh after load more keeps expanded window`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(800) } returns Result.success(numberedSessions(800))

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-20",
                sessions = numberedSessions(800),
                loadedSessionLimit = 800,
                hasMoreSessions = true,
                inputText = "hello"
            )
        }

        viewModel.sendMessage()
        advanceUntilIdle()
        coVerify { repository.getSessions(800) }

        advanceTimeBy(1200)
        advanceUntilIdle()
        coVerify(atLeast = 2) { repository.getSessions(800) }
        coVerify(exactly = 0) { repository.getSessions(400) }
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertTrue(viewModel.state.value.sessions.any { it.id == "session-650" })
    }

    @Test
    fun `hasMore uses raw response size not merged selected session`() = runTest {
        val returned = numberedSessions(799)
        val outside = Session(id = "outside-session", directory = "/tmp/outside")
        coEvery { repository.getSessions(800) } returns Result.success(returned)

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = numberedSessions(400) + outside,
                loadedSessionLimit = 400,
                hasMoreSessions = true,
                currentSessionId = outside.id
            )
        }

        viewModel.loadMoreSessions()
        advanceUntilIdle()

        assertEquals(800, viewModel.state.value.sessions.size)
        assertTrue(viewModel.state.value.sessions.any { it.id == outside.id })
        assertFalse(viewModel.state.value.hasMoreSessions)
    }

    @Test
    fun `refresh does not resurrect deleted unselected sessions`() = runTest {
        coEvery { repository.getSessions(800) } returns Result.success(numberedSessions(799))

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                sessions = numberedSessions(800),
                loadedSessionLimit = 800,
                hasMoreSessions = true,
                currentSessionId = "session-20"
            )
        }

        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(799, viewModel.state.value.sessions.size)
        assertFalse(viewModel.state.value.sessions.any { it.id == "session-800" })
        assertEquals(800, viewModel.state.value.loadedSessionLimit)
        assertFalse(viewModel.state.value.hasMoreSessions)
    }

    @Test
    fun `host switch drops in-flight expanded session fetch`() = runTest {
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
        val moreGate = CompletableDeferred<Unit>()
        coEvery { repository.getSessions(800) } coAnswers {
            moreGate.await()
            Result.success(numberedSessions(800))
        }

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                isConnected = true,
                sessions = numberedSessions(400),
                loadedSessionLimit = 400,
                hasMoreSessions = true,
                currentSessionId = "session-20"
            )
        }
        viewModel.loadMoreSessions()
        runCurrent()

        viewModel.selectHostProfile(second.id)
        advanceUntilIdle()
        moreGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(second.id, viewModel.state.value.currentHostProfileId)
        assertEquals(400, viewModel.state.value.loadedSessionLimit)
        assertTrue(viewModel.state.value.sessions.isEmpty())
        assertFalse(viewModel.state.value.isLoadingMoreSessions)
        assertTrue(viewModel.state.value.hasMoreSessions)
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
    fun `effectiveSelectedAgent keeps known selection and ignores empty agent list`() {
        val agents = listOf(
            AgentInfo(name = "build", mode = "primary"),
            AgentInfo(name = "plan", mode = "primary")
        )
        assertEquals("plan", effectiveSelectedAgent("plan", agents))
        assertEquals("grok", effectiveSelectedAgent("grok", emptyList()))
    }

    @Test
    fun `effectiveSelectedAgent falls back to first visible agent`() {
        val agents = listOf(
            AgentInfo(name = "hidden", mode = "primary", hidden = true),
            AgentInfo(name = "build", mode = "primary")
        )
        assertEquals("build", effectiveSelectedAgent("grok", agents))
        assertEquals(
            "build",
            effectiveSelectedAgent("grok", listOf(AgentInfo(name = "sub", mode = "subagent", hidden = true)))
        )
    }

    @Test
    fun `loadAgents replaces unknown selected agent with first visible agent`() = runTest {
        coEvery { repository.getAgents() } returns Result.success(
            listOf(AgentInfo(name = "build", mode = "primary"))
        )
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(selectedAgentName = "grok") }

        loadAgents(viewModel)
        advanceUntilIdle()

        assertEquals("build", viewModel.state.value.selectedAgentName)
    }

    @Test
    fun `loadAgents keeps selected agent that the server still exposes`() = runTest {
        coEvery { repository.getAgents() } returns Result.success(
            listOf(AgentInfo(name = "build", mode = "primary"))
        )
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(selectedAgentName = "build") }

        loadAgents(viewModel)
        advanceUntilIdle()

        assertEquals("build", viewModel.state.value.selectedAgentName)
    }

    @Test
    fun `loadAgents leaves selection unchanged when agent list is empty`() = runTest {
        coEvery { repository.getAgents() } returns Result.success(emptyList())
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(selectedAgentName = "grok") }

        loadAgents(viewModel)
        advanceUntilIdle()

        assertEquals("grok", viewModel.state.value.selectedAgentName)
    }

    @Test
    fun `loadMessages corrects inferred agent missing from the server list`() = runTest {
        val messages = listOf(
            MessageWithParts(info = Message(id = "a1", role = "assistant", agent = "plan"))
        )
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                agents = listOf(AgentInfo(name = "build", mode = "primary")),
                selectedAgentName = "grok"
            )
        }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals("build", viewModel.state.value.selectedAgentName)
    }

    @Test
    fun `sendMessage revalidates unknown agent against loaded agents`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        updateState(viewModel) {
            it.copy(
                selectedAgentName = "grok",
                agents = listOf(AgentInfo(name = "build", mode = "primary"))
            )
        }
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage("session-1", "hello", "build", any(), any(), any())
        }
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
    fun `configuration recreation keeps recording and original host session`() = runTest {
        val grokFile = File.createTempFile("opencode-rotate-keep", ".wav").apply {
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
        } returns TranscriptionResult(text = "heard", requestId = "rotate-1")
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "source",
                currentHostProfileId = "test-host",
                inputText = "prefix",
                aiBuilderConnectionOK = true,
            )
        }

        viewModel.toggleRecording()
        runCurrent()
        assertTrue(viewModel.state.value.isRecording)
        val epochWhileRecording = viewModel.state.value.statusEpoch

        val first = viewModel.onHostActivityStart()
        viewModel.onHostActivityStop(first, changingConfigurations = true)
        val second = viewModel.onHostActivityStart()
        viewModel.onHostActivityStop(first, changingConfigurations = false)
        viewModel.onHostActivityStop(second, changingConfigurations = true)
        val third = viewModel.onHostActivityStart()
        viewModel.onHostActivityStop(first, changingConfigurations = true)
        runCurrent()

        assertTrue(viewModel.state.value.isRecording)
        assertTrue(viewModel.state.value.appForeground)
        assertEquals("source", viewModel.state.value.currentSessionId)
        assertEquals("test-host", viewModel.state.value.currentHostProfileId)
        assertEquals("prefix", viewModel.state.value.inputText)
        assertTrue(viewModel.state.value.statusEpoch >= epochWhileRecording)
        coVerify(exactly = 0) { microphone.stop() }

        val epochAfterRecreate = viewModel.state.value.statusEpoch
        viewModel.onHostActivityStop(second, changingConfigurations = false)
        runCurrent()
        assertTrue(viewModel.state.value.isRecording)
        assertTrue(viewModel.state.value.appForeground)
        assertEquals(epochAfterRecreate, viewModel.state.value.statusEpoch)
        coVerify(exactly = 0) { microphone.stop() }

        viewModel.toggleRecording()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        assertEquals("prefix heard", viewModel.state.value.inputText)
        verify { settingsManager.setOwnedDraft("test-host", "source", "prefix heard") }
        assertEquals("test-host", viewModel.state.value.currentHostProfileId)
        coVerify(exactly = 1) { microphone.stop() }
        grokFile.delete()
    }

    @Test
    fun `real background stop preserves audio for the originating session`() = runTest {
        val grokFile = File.createTempFile("opencode-background-owner", ".wav").apply {
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
        } returns TranscriptionResult(text = "heard", requestId = "background-1")
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "source",
                currentHostProfileId = "test-host",
                inputText = "prefix",
                aiBuilderConnectionOK = true,
            )
        }

        viewModel.toggleRecording()
        runCurrent()
        val token = viewModel.onHostActivityStart()
        viewModel.onHostActivityStop(token, changingConfigurations = false)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isRecording)
        assertFalse(viewModel.state.value.appForeground)
        assertTrue(viewModel.state.value.hasPreservedSpeechAudio)
        assertEquals("source", viewModel.state.value.currentSessionId)
        assertEquals("test-host", viewModel.state.value.currentHostProfileId)
        coVerify(exactly = 1) { microphone.stop() }

        viewModel.selectSession("destination")
        runCurrent()
        assertEquals("destination draft", viewModel.state.value.inputText)

        viewModel.retryPreservedSpeechAudio()
        advanceUntilIdle()

        assertEquals("destination draft", viewModel.state.value.inputText)
        verify { settingsManager.setOwnedDraft("test-host", "source", "prefix heard") }
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
        verify { settingsManager.setOwnedDraft("test-host", "source", "source prefix source words") }
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
        verify { settingsManager.setOwnedDraft("test-host", "source", "source prefix finished") }
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
        runCurrent()
        assertTrue(viewModel.state.value.isRecording)
        viewModel.toggleRecording()
        // Stop path runs closeAndDrain on Dispatchers.IO (real threads), so we
        // must yield to let it complete before commitAndStop fires. Poll with
        // small sleeps + runCurrent until commitStarted completes.
        var waited = 0L
        while (!commitStarted.isCompleted && waited < 5_000) {
            Thread.sleep(20)
            waited += 20
            runCurrent()
        }
        assertTrue(commitStarted.isCompleted)
        viewModel.selectSession("destination")
        sendPartial("stale partial")
        runCurrent()
        assertEquals("destination draft", viewModel.state.value.inputText)

        commitResult.complete("finished")
        runCurrent()

        assertEquals("destination draft", viewModel.state.value.inputText)
        verify { settingsManager.setOwnedDraft("test-host", "source", "source prefix finished") }
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
    fun `handleSSEEvent gated part without payload falls back to rest refresh`() = runTest {
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
                        // Parseable but gate-incomplete: tool part without state
                        // and no top-level delta -> REST fallback.
                        put(
                            "part",
                            buildJsonObject {
                                put("id", JsonPrimitive("part-1"))
                                put("messageID", JsonPrimitive("message-1"))
                                put("type", JsonPrimitive("tool"))
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
    fun `handleSSEEvent malformed part without id falls back to rest refresh`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a2", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("message-1:part-1" to "partial")
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

        verify { settingsManager.setOwnedDraft("test-host", "s1", "hello") }
    }

    @Test
    fun `selectSession saves old draft and restores new draft from settings manager`() = runTest {
        every { settingsManager.getDraftText("s2") } returns "draft2"

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1", inputText = "draft1") }

        viewModel.selectSession("s2")
        advanceUntilIdle()

        verify { settingsManager.setOwnedDraft("test-host", "s1", "draft1") }
        verify { settingsManager.getDraftText("s2") }
        assertEquals("draft2", viewModel.state.value.inputText)
    }

    @Test
    fun `selectModel with active session saves model id per session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "s1") }

        viewModel.selectModel(2)

        val expectedId = seedShortlistFromPresets()[2].id
        verify { settingsManager.setModelIdForSession("s1", expectedId) }
    }

    @Test
    fun `moveModelShortlist reorders and persists`() = runTest {
        val viewModel = createViewModel()
        val before = viewModel.state.value.modelShortlist
        assertTrue(before.size >= 2)

        viewModel.moveModelShortlist(0, 1)

        val after = viewModel.state.value.modelShortlist
        assertEquals(before[1], after[0])
        assertEquals(before[0], after[1])
        verify { settingsManager.modelShortlistJson = any() }
    }

    @Test
    fun `removeModelShortlistItem removes and reanchors selection`() = runTest {
        val viewModel = createViewModel()
        val before = viewModel.state.value.modelShortlist
        val targetId = before[0].id

        viewModel.removeModelShortlistItem(targetId)

        val after = viewModel.state.value.modelShortlist
        assertEquals(before.size - 1, after.size)
        assertFalse(after.any { it.id == targetId })
        assertTrue(viewModel.state.value.selectedModelIndex in after.indices)
    }

    @Test
    fun `removeModelShortlistItem of current selection falls back and persists`() = runTest {
        val viewModel = createViewModel()
        val before = viewModel.state.value.modelShortlist
        val targetId = before[0].id
        // Make the first model the active selection on a live session.
        updateState(viewModel) { it.copy(currentSessionId = "s1", selectedModelId = targetId, selectedModelIndex = 0) }

        viewModel.removeModelShortlistItem(targetId)

        val after = viewModel.state.value.modelShortlist
        assertEquals(before.size - 1, after.size)
        // Selection falls back to the new first item and is persisted globally and
        // per-session, so a restart doesn't re-read the deleted id.
        val fallbackId = after.first().id
        assertEquals(fallbackId, viewModel.state.value.selectedModelId)
        assertEquals(0, viewModel.state.value.selectedModelIndex)
        verify { settingsManager.selectedModelId = fallbackId }
        verify { settingsManager.setModelIdForSession("s1", fallbackId) }
    }

    @Test
    fun `updateModelShortlistShortName edits the short name`() = runTest {
        val viewModel = createViewModel()
        val targetId = viewModel.state.value.modelShortlist[0].id

        viewModel.updateModelShortlistShortName(targetId, "Custom")

        val updated = viewModel.state.value.modelShortlist.first { it.id == targetId }
        assertEquals("Custom", updated.shortName)
    }

    @Test
    fun `addModelsToShortlist appends catalog models and dedups`() = runTest {
        val viewModel = createViewModel()
        val beforeSize = viewModel.state.value.modelShortlist.size
        val existing = viewModel.state.value.modelShortlist[0]

        viewModel.addModelsToShortlist(
            listOf(ModelShortlistItem("anthropic", "claude-x", "Claude X", "Claude"), existing)
        )

        val after = viewModel.state.value.modelShortlist
        assertEquals(beforeSize + 1, after.size)
        assertTrue(after.any { it.id == "anthropic/claude-x" })
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
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        viewModel.selectSession("s1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        verify { settingsManager.setOwnedDraft("test-host", "s1", "") }
    }

    @Test
    fun `loadMessages uses per-session saved model id over message inference`() = runTest {
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
        val savedId = seedShortlistFromPresets()[3].id
        every { settingsManager.getModelIdForSession("session-1") } returns savedId

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.selectedModelIndex)
    }

    @Test
    fun `loadMessages auto-adds a saved model missing from the shortlist`() = runTest {
        // A saved model that is NOT in the seeded shortlist but whose provider
        // is known (present in the loaded providers list). Loading the session
        // must re-add it so the selected id and index stay consistent.
        val savedId = "anthropic/claude-x"
        every { settingsManager.getModelIdForSession("session-1") } returns savedId
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(emptyList())

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                providers = ProvidersResponse(
                    providers = listOf(ConfigProvider(id = "anthropic", name = "Anthropic"))
                )
            )
        }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.modelShortlist.any { it.id == savedId })
        assertEquals(savedId, viewModel.state.value.selectedModelId)
        val idx = viewModel.state.value.modelShortlist.indexOfFirst { it.id == savedId }
        assertEquals(idx, viewModel.state.value.selectedModelIndex)
    }

    @Test
    fun `loadMessages does not auto-add a saved model with unknown provider`() = runTest {
        // A saved model whose provider is NOT in the loaded providers list
        // (stale/retired provider). Loading the session must NOT re-add it.
        val savedId = "ghost/phantom-model"
        every { settingsManager.getModelIdForSession("session-1") } returns savedId
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(emptyList())

        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                providers = ProvidersResponse(
                    providers = listOf(ConfigProvider(id = "openai", name = "OpenAI"))
                )
            )
        }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.modelShortlist.any { it.id == savedId })
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
    fun `deleteSession failure keeps the row and shows the error`() = runTest {
        coEvery { repository.deleteSession("session-1") } returns Result.failure(
            Exception("Delete failed 501: unsupported")
        )
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

        assertEquals(listOf("session-1", "session-2"), viewModel.state.value.sessions.map { it.id })
        assertTrue(viewModel.state.value.error!!.contains("501"))
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
    fun `handleSSEEvent message created upserts info without rest`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.created",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("m1"))
                                put("role", JsonPrimitive("assistant"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(400)
        advanceUntilIdle()

        val row = viewModel.state.value.messages.single()
        assertEquals("m1", row.info.id)
        assertEquals("assistant", row.info.role)
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
        coVerify(exactly = 0) { repository.getSessions(any()) }
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

    @Test
    fun `handleSSEEvent full tool part upserts locally without rest`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        val sendPart = { status: String ->
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
                                    put("id", JsonPrimitive("part-1"))
                                    put("messageID", JsonPrimitive("message-1"))
                                    put("type", JsonPrimitive("tool"))
                                    put("tool", JsonPrimitive("bash"))
                                    put(
                                        "state",
                                        buildJsonObject { put("status", JsonPrimitive(status)) }
                                    )
                                }
                            )
                        }
                    )
                )
            )
        }
        sendPart("pending")
        sendPart("running")
        sendPart("completed")

        val row = viewModel.state.value.messages.single()
        assertEquals("message-1", row.info.id)
        assertEquals("assistant", row.info.role)
        assertEquals(1, row.parts.size)
        assertEquals("completed", row.parts[0].state?.displayString)
        assertEquals("tool", viewModel.state.value.partTypeIndex["part-1"])
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `handleSSEEvent part delta appends streaming text`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                partTypeIndex = mapOf("part-1" to "text")
            )
        }

        repeat(3) { i ->
            handleSse(
                viewModel,
                SSEEvent(
                    payload = SSEPayload(
                        type = "message.part.delta",
                        properties = buildJsonObject {
                            put("sessionID", JsonPrimitive("session-1"))
                            put("messageID", JsonPrimitive("message-1"))
                            put("partID", JsonPrimitive("part-1"))
                            put("field", JsonPrimitive("text"))
                            put("delta", JsonPrimitive("tok$i"))
                        }
                    )
                )
            )
        }

        assertEquals("tok0tok1tok2", viewModel.state.value.streamingPartTexts["message-1:part-1"])
        assertNull(viewModel.state.value.streamingReasoningPart)
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `handleSSEEvent reasoning part delta updates streaming reasoning part`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                partTypeIndex = mapOf("part-r" to "reasoning")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.delta",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("messageID", JsonPrimitive("message-1"))
                        put("partID", JsonPrimitive("part-r"))
                        put("field", JsonPrimitive("text"))
                        put("delta", JsonPrimitive("thinking"))
                    }
                )
            )
        )

        assertEquals("thinking", viewModel.state.value.streamingPartTexts["message-1:part-r"])
        assertEquals("part-r", viewModel.state.value.streamingReasoningPart?.id)
    }

    @Test
    fun `handleSSEEvent part delta ignored for other session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.delta",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-2"))
                        put("messageID", JsonPrimitive("message-1"))
                        put("partID", JsonPrimitive("part-1"))
                        put("field", JsonPrimitive("text"))
                        put("delta", JsonPrimitive("ignored"))
                    }
                )
            )
        )

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `handleSSEEvent full text part supersedes streaming text`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("message-1:part-t" to "partial"),
                partTypeIndex = mapOf("part-t" to "text")
            )
        }

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
                                put("id", JsonPrimitive("part-t"))
                                put("messageID", JsonPrimitive("message-1"))
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive("complete text"))
                            }
                        )
                    }
                )
            )
        )

        // Full frame must not wipe the overlay; idle reconcile does that.
        // Wiping it makes the next delta render as a single token.
        assertEquals("partial", viewModel.state.value.streamingPartTexts["message-1:part-t"])
        // Orphan text part (no assistant row yet) does not create a shell row.
        assertTrue(viewModel.state.value.messages.isEmpty())
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `handleSSEEvent delta after full part continues from part text`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(
                    MessageWithParts(
                        info = Message(id = "message-1", sessionId = "session-1", role = "assistant"),
                        parts = listOf(
                            Part(id = "part-t", messageId = "message-1", type = "text", text = "Hello")
                        )
                    )
                ),
                partTypeIndex = mapOf("part-t" to "text")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.delta",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("messageID", JsonPrimitive("message-1"))
                        put("partID", JsonPrimitive("part-t"))
                        put("field", JsonPrimitive("text"))
                        put("delta", JsonPrimitive("!"))
                    }
                )
            )
        )

        assertEquals("Hello!", viewModel.state.value.streamingPartTexts["message-1:part-t"])
    }

    @Test
    fun `handleSSEEvent part removed deletes part locally`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(
                    MessageWithParts(
                        info = Message(id = "message-1", role = "assistant"),
                        parts = listOf(
                            Part(id = "p1", messageId = "message-1", type = "text", text = "a"),
                            Part(id = "p2", messageId = "message-1", type = "text", text = "b")
                        )
                    )
                ),
                partTypeIndex = mapOf("p1" to "text"),
                streamingPartTexts = mapOf("message-1:p1" to "partial")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.removed",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("messageID", JsonPrimitive("message-1"))
                        put("partID", JsonPrimitive("p1"))
                    }
                )
            )
        )

        val row = viewModel.state.value.messages.single()
        assertEquals(listOf("p2"), row.parts.map { it.id })
        assertNull(viewModel.state.value.partTypeIndex["p1"])
        assertNull(viewModel.state.value.streamingPartTexts["message-1:p1"])
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `handleSSEEvent message removed deletes row and prunes optimistic set`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(
                    MessageWithParts(info = Message(id = "u1", role = "user")),
                    MessageWithParts(info = Message(id = "a1", role = "assistant"))
                ),
                pendingOptimisticMessageIds = setOf("u1")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.removed",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("messageID", JsonPrimitive("u1"))
                    }
                )
            )
        )

        assertEquals(listOf("a1"), viewModel.state.value.messages.map { it.info.id })
        assertTrue(viewModel.state.value.pendingOptimisticMessageIds.isEmpty())
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `handleSSEEvent message updated upserts info keeping local parts`() = runTest {
        val localPart = Part(id = "p1", messageId = "a1", type = "text", text = "local")
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(
                    MessageWithParts(
                        info = Message(id = "a1", sessionId = "session-1", role = "assistant"),
                        parts = listOf(localPart)
                    )
                )
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("a1"))
                                put("role", JsonPrimitive("assistant"))
                                put("modelID", JsonPrimitive("gpt-1"))
                            }
                        )
                    }
                )
            )
        )

        val row = viewModel.state.value.messages.single()
        assertEquals("gpt-1", row.info.modelId)
        assertEquals(listOf(localPart), row.parts)
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `handleSSEEvent message updated unknown message adds empty row`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("m9"))
                                put("role", JsonPrimitive("assistant"))
                            }
                        )
                    }
                )
            )
        )

        val row = viewModel.state.value.messages.single()
        assertEquals("m9", row.info.id)
        assertTrue(row.parts.isEmpty())
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `handleSSEEvent real user part supersedes optimistic temp part`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                messages = listOf(
                    MessageWithParts(
                        info = Message(id = "msg-abc", sessionId = "session-1", role = "user"),
                        parts = listOf(
                            Part(id = "temp-part-msg-abc", messageId = "msg-abc", type = "text", text = "hello"),
                            Part(id = "temp-file-1", messageId = "msg-abc", type = "file", mime = "image/png")
                        )
                    )
                ),
                pendingOptimisticMessageIds = setOf("msg-abc")
            )
        }

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
                                put("id", JsonPrimitive("prt-real"))
                                put("messageID", JsonPrimitive("msg-abc"))
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive("hello"))
                            }
                        )
                    }
                )
            )
        )

        val row = viewModel.state.value.messages.single()
        // The temp text twin is replaced by the real part (no doubled text),
        // the temp file part stays until the REST reconcile.
        assertEquals(listOf("temp-file-1", "prt-real"), row.parts.map { it.id })
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
    }

    @Test
    fun `sendMessage success skips post-send messages refresh while busy`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessions = listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/project")),
                inputText = "hello"
            )
        }

        viewModel.sendMessage()
        advanceTimeBy(MainViewModelTimings.messageRefreshDelayMs + 100)
        advanceUntilIdle()

        // Optimistic busy is written on ack, so both post-send messages
        // refreshes are skipped; the idle reconcile is the convergence point.
        coVerify(exactly = 1) { repository.sendMessage(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
        assertTrue(viewModel.state.value.sessionStatuses["session-1"]?.isBusy == true)
    }

    @Test
    fun `watchdog skips messages refresh while session is busy`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessionStatuses = mapOf("session-1" to SessionStatus(type = "busy"))
            )
        }
        setSseLastFrameAtMs(viewModel, System.currentTimeMillis() - 30_000)
        invokePrivateNoArgs(viewModel, "startSseWatchdog")
        advanceTimeBy(5_000)
        runCurrent()

        // Busy: messages reconcile is skipped, status reconcile still runs.
        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
        coVerify(exactly = 1) { repository.getSessionStatus() }
        cancelSseWatchdog(viewModel)
    }

    @Test
    fun `watchdog reconciles once after silence beyond threshold`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }
        setSseLastFrameAtMs(viewModel, System.currentTimeMillis() - 30_000)
        invokePrivateNoArgs(viewModel, "startSseWatchdog")
        advanceTimeBy(5_000)
        runCurrent()

        coVerify(exactly = 1) { repository.getMessages("session-1", 30) }
        coVerify(exactly = 1) { repository.getSessionStatus() }
        cancelSseWatchdog(viewModel)
    }

    @Test
    fun `watchdog stays quiet while frames keep arriving`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }
        setSseLastFrameAtMs(viewModel, System.currentTimeMillis())
        invokePrivateNoArgs(viewModel, "startSseWatchdog")
        advanceTimeBy(10_000)
        runCurrent()

        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
        coVerify(exactly = 0) { repository.getSessionStatus() }
        cancelSseWatchdog(viewModel)
    }

    @Test
    fun `watchdog stays quiet before any frame arrives instead of busy polling`() = runTest {
        // sseLastFrameAtMs left at 0: with busy polling removed, a busy session
        // without any SSE frame must not trigger loadMessages.
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessionStatuses = mapOf("session-1" to SessionStatus(type = "busy"))
            )
        }
        invokePrivateNoArgs(viewModel, "startSseWatchdog")
        advanceTimeBy(10_000)
        runCurrent()

        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
        cancelSseWatchdog(viewModel)
    }

    @Test
    fun `startSSE onConnected reconciles messages and status once`() = runTest {
        val captured = slot<() -> Unit>()
        coEvery { repository.connectSSE(capture(captured), any()) } returns emptyFlow()
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        invokePrivateNoArgs(viewModel, "startSSE")
        runCurrent()

        captured.captured?.invoke()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getMessages("session-1", 30) }
        coVerify(exactly = 1) { repository.getSessionStatus() }
    }

    @Test
    fun `startSSE onConnected skips messages refresh while session is busy`() = runTest {
        val captured = slot<() -> Unit>()
        coEvery { repository.connectSSE(capture(captured), any()) } returns emptyFlow()
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                sessionStatuses = mapOf("session-1" to SessionStatus(type = "busy"))
            )
        }

        invokePrivateNoArgs(viewModel, "startSSE")
        runCurrent()

        captured.captured?.invoke()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessages(any(), any()) }
        coVerify(exactly = 1) { repository.getSessionStatus() }
    }

    @org.junit.After
    fun tearDown() {
        unmockkAll()
    }
}
