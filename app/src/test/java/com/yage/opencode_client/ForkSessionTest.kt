package com.yage.opencode_client

import android.util.Log
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.ThemeMode
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForkSessionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var voiceFlowClient: VoiceFlowClient
    private lateinit var microphone: VoiceFlowMicrophone

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
        every { settingsManager.getModelForSession(any()) } returns null
        every { settingsManager.setModelForSession(any(), any()) } just runs
        every { settingsManager.getAgentForSession(any()) } returns null
        every { settingsManager.setAgentForSession(any(), any()) } just runs

        every { repository.connectSSE() } returns emptyFlow()
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(repository, settingsManager, voiceFlowClient, microphone)
    }

    @Test
    fun `forkSession success upserts forked session and selects it`() = runTest {
        val parentSession = Session(id = "parent-1", directory = "/tmp/project", title = "Parent Session")
        val forkedSession = Session(
            id = "forked-1",
            directory = "/tmp/project",
            title = "Parent Session",
            parentId = "parent-1"
        )
        coEvery { repository.forkSession("parent-1", "msg-42") } returns Result.success(forkedSession)

        val viewModel = createViewModel()
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as MutableStateFlow<AppState>
        stateFlow.value = stateFlow.value.copy(
            sessions = listOf(parentSession),
            currentSessionId = "parent-1"
        )

        viewModel.forkSession("parent-1", "msg-42")
        advanceUntilIdle()

        coVerify { repository.forkSession("parent-1", "msg-42") }
        val sessions = viewModel.state.value.sessions
        assertTrue("forked session should be in list", sessions.any { it.id == "forked-1" })
        assertEquals("forked-1", viewModel.state.value.currentSessionId)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `forkSession with null messageId calls repository correctly`() = runTest {
        val forkedSession = Session(id = "forked-2", directory = "/tmp/project")
        coEvery { repository.forkSession("parent-1", null) } returns Result.success(forkedSession)

        val viewModel = createViewModel()

        viewModel.forkSession("parent-1", null)
        advanceUntilIdle()

        coVerify { repository.forkSession("parent-1", null) }
        assertTrue(viewModel.state.value.sessions.any { it.id == "forked-2" })
    }

    @Test
    fun `forkSession failure sets error in state`() = runTest {
        coEvery { repository.forkSession(any(), any()) } returns Result.failure(RuntimeException("fork failed"))

        val viewModel = createViewModel()
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as MutableStateFlow<AppState>
        stateFlow.value = stateFlow.value.copy(currentSessionId = "parent-1")

        viewModel.forkSession("parent-1", "msg-99")
        advanceUntilIdle()

        val error = viewModel.state.value.error
        assertNotNull("error should be set on failure", error)
        assertTrue("error should mention fork", error!!.contains("fork", ignoreCase = true))
        assertEquals("parent-1", viewModel.state.value.currentSessionId)
    }
}
