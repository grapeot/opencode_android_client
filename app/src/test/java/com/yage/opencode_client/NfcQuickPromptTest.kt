package com.yage.opencode_client

import android.util.Log
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.ProviderRegistryResponse
import com.yage.opencode_client.data.model.ProvidersResponse
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ssh.SSHKeyManager
import com.yage.opencode_client.ssh.TunnelManager
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.util.SettingsManager
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowMicrophone
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import com.yage.opencode_client.util.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NfcQuickPromptTest {

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
        every { settingsManager.aiBuilderLastOKSignature = any() } just runs
        every { settingsManager.aiBuilderLastOKTestedAt = any() } just runs

        every { settingsManager.getDraftText(any()) } returns ""
        every { settingsManager.setDraftText(any(), any()) } just runs
        every { settingsManager.getModelIdForSession(any()) } returns null
        every { settingsManager.getLegacySessionModels() } returns emptyMap()
        every { settingsManager.getAgentForSession(any()) } returns null
        every { settingsManager.setAgentForSession(any(), any()) } just runs

        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getAgents() } returns Result.success(emptyList())
        coEvery { repository.getProviders() } returns Result.success(ProvidersResponse())
        coEvery { repository.getProviderRegistry() } returns Result.success(ProviderRegistryResponse())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(
            repository,
            settingsManager,
            voiceFlowClient,
            microphone,
            hostProfileStore,
            tunnelManager,
            sshKeyManager
        )
    }

    @Test
    fun `handleNfcPrompt ignored when NFC disabled`() = runTest {
        every { settingsManager.nfcEnabled } returns false
        val vm = createViewModel()

        vm.handleNfcPrompt("hello world", autoSend = true)
        advanceUntilIdle()

        assertNull(vm.state.value.pendingNfcAction)
        coVerify(exactly = 0) { repository.createSession(any()) }
    }

    @Test
    fun `handleNfcPrompt sets pending action when enabled`() = runTest {
        every { settingsManager.nfcEnabled } returns true
        coEvery { repository.createSession(any()) } returns Result.success(
            Session(id = "s1", directory = "/tmp")
        )
        every { settingsManager.getDraftText(any()) } returns ""
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())

        val vm = createViewModel()
        vm.handleNfcPrompt("review my code", autoSend = true)
        advanceUntilIdle()

        // After full chain: pending action should be consumed
        assertNull(vm.state.value.pendingNfcAction)
    }

    @Test
    fun `consumePendingNfcAction fills input and sends when autoSend`() = runTest {
        every { settingsManager.nfcEnabled } returns true
        every { settingsManager.currentSessionId } returns "s1"
        every { settingsManager.getDraftText("s1") } returns ""
        coEvery { repository.createSession(any()) } returns Result.success(
            Session(id = "s1", directory = "/tmp")
        )
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.sendMessage(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        val vm = createViewModel()
        vm.handleNfcPrompt("test prompt", autoSend = true)
        advanceUntilIdle()

        // autoSend=true: sendMessage clears inputText on success
        assertNull(vm.state.value.pendingNfcAction)
        // Verify sendMessage was called
        coVerify { repository.sendMessage("s1", "test prompt", any(), any(), any(), any()) }
    }

    @Test
    fun `consumePendingNfcAction fills input only when autoSend false`() = runTest {
        every { settingsManager.nfcEnabled } returns true
        every { settingsManager.currentSessionId } returns "s1"
        every { settingsManager.getDraftText("s1") } returns ""
        coEvery { repository.createSession(any()) } returns Result.success(
            Session(id = "s1", directory = "/tmp")
        )
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())

        val vm = createViewModel()
        vm.handleNfcPrompt("just fill", autoSend = false)
        advanceUntilIdle()

        // autoSend=false: inputText should be filled, no send
        assertEquals("just fill", vm.state.value.inputText)
        assertEquals(0, vm.state.value.sendingSessionIds.size)
    }

    @Test
    fun `consumePendingNfcAction no-op when no pending action`() = runTest {
        val vm = createViewModel()
        vm.consumePendingNfcAction()
        assertNull(vm.state.value.pendingNfcAction)
    }

    @Test
    fun `NfcPendingAction holds prompt and autoSend`() {
        val action = AppState.NfcPendingAction("hello", true)
        assertEquals("hello", action.prompt)
        assertEquals(true, action.autoSend)
    }
}