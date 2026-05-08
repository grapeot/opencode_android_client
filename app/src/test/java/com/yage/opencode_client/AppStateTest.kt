package com.yage.opencode_client

import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.ModelPresets
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.util.ThemeMode
import org.junit.Assert.*
import org.junit.Test

class AppStateTest {
    
    @Test
    fun `AppState default values`() {
        val state = AppState()
        
        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
        assertNull(state.serverVersion)
        assertTrue(state.sessions.isEmpty())
        assertEquals(100, state.loadedSessionLimit)
        assertTrue(state.hasMoreSessions)
        assertFalse(state.isLoadingMoreSessions)
        assertNull(state.currentSessionId)
        assertTrue(state.sessionStatuses.isEmpty())
        assertTrue(state.messages.isEmpty())
        assertEquals(30, state.messageLimit)
        assertFalse(state.isLoadingMessages)
        assertTrue(state.agents.isEmpty())
        assertEquals("build", state.selectedAgentName)
        assertEquals(2, state.selectedModelIndex)
        assertNull(state.providers)
        assertTrue(state.pendingPermissions.isEmpty())
        assertEquals("", state.inputText)
        assertNull(state.error)
        assertEquals(ThemeMode.SYSTEM, state.themeMode)
        assertNull(state.filePathToShowInFiles)
        assertNull(state.filePreviewOriginRoute)
        assertTrue(state.canLoadMoreSessions)
    }

    @Test
    fun `filePathToShowInFiles can be set and read`() {
        val state = AppState(filePathToShowInFiles = "src/main.kt")
        assertEquals("src/main.kt", state.filePathToShowInFiles)
    }

    @Test
    fun `filePreviewOriginRoute can be set and read`() {
        val state = AppState(
            filePathToShowInFiles = "src/main.kt",
            filePreviewOriginRoute = "chat"
        )
        assertEquals("chat", state.filePreviewOriginRoute)
    }

    @Test
    fun `filePreviewOriginRoute defaults to null`() {
        val state = AppState(filePathToShowInFiles = "src/main.kt")
        assertNull(state.filePreviewOriginRoute)
    }
    
    @Test
    fun `currentSession returns correct session`() {
        val session1 = Session(id = "s1", directory = "/project1")
        val session2 = Session(id = "s2", directory = "/project2")
        
        val state = AppState(
            sessions = listOf(session1, session2),
            currentSessionId = "s2"
        )
        
        assertEquals(session2, state.currentSession)
    }
    
    @Test
    fun `currentSession returns null when no session selected`() {
        val session1 = Session(id = "s1", directory = "/project1")
        
        val state = AppState(
            sessions = listOf(session1),
            currentSessionId = null
        )
        
        assertNull(state.currentSession)
    }
    
    @Test
    fun `currentSessionStatus returns correct status`() {
        val status1 = SessionStatus(type = "idle")
        val status2 = SessionStatus(type = "busy")
        
        val state = AppState(
            sessionStatuses = mapOf("s1" to status1, "s2" to status2),
            currentSessionId = "s2"
        )
        
        assertEquals(status2, state.currentSessionStatus)
    }
    
    @Test
    fun `isCurrentSessionBusy returns true when busy`() {
        val state = AppState(
            sessionStatuses = mapOf("s1" to SessionStatus(type = "busy")),
            currentSessionId = "s1"
        )
        
        assertTrue(state.isCurrentSessionBusy)
    }
    
    @Test
    fun `isCurrentSessionBusy returns false when idle`() {
        val state = AppState(
            sessionStatuses = mapOf("s1" to SessionStatus(type = "idle")),
            currentSessionId = "s1"
        )
        
        assertFalse(state.isCurrentSessionBusy)
    }
    
    @Test
    fun `isCurrentSessionBusy returns false when no status`() {
        val state = AppState(currentSessionId = "s1")
        
        assertFalse(state.isCurrentSessionBusy)
    }
    
    @Test
    fun `visibleAgents filters correctly`() {
        val agents = listOf(
            AgentInfo(name = "Visible1", mode = "primary", hidden = false),
            AgentInfo(name = "Hidden", mode = "primary", hidden = true),
            AgentInfo(name = "SubAgent", mode = "subagent", hidden = false),
            AgentInfo(name = "Visible2", mode = "all", hidden = false)
        )
        
        val state = AppState(agents = agents)
        
        assertEquals(2, state.visibleAgents.size)
        assertEquals("Visible1", state.visibleAgents[0].name)
        assertEquals("Visible2", state.visibleAgents[1].name)
    }

    private fun makeProviders(vararg models: Triple<String, String, String?>): ProvidersResponse {
        val providers = models.groupBy { it.first }.map { (providerId, group) ->
            ConfigProvider(
                id = providerId,
                models = group.associate { (_, modelId, name) ->
                    modelId to ProviderModel(id = modelId, name = name)
                }
            )
        }
        return ProvidersResponse(providers = providers)
    }

    @Test
    fun `availableModels returns curated presets (filtered like iOS)`() {
        val state = AppState()
        val models = state.availableModels

        assertEquals(ModelPresets.list.size, models.size)
        assertEquals(ModelPresets.list, models)
        assertEquals("GLM-5.1", models[0].displayName)
        assertEquals("zai-coding-plan", models[0].providerId)
        assertEquals("glm-5.1", models[0].modelId)
        assertEquals("GPT-5.5", models[1].displayName)
        assertEquals("openai", models[1].providerId)
        assertEquals("gpt-5.5", models[1].modelId)
    }

    @Test
    fun `availableModels independent of providers`() {
        val stateWithProviders = AppState(providers = makeProviders(Triple("openai", "gpt-4", "GPT-4")))
        val stateWithoutProviders = AppState(providers = null)
        assertEquals(stateWithProviders.availableModels, stateWithoutProviders.availableModels)
        assertEquals(ModelPresets.list, stateWithProviders.availableModels)
    }

    private fun makeContextUsageState(
        totalTokens: Int?,
        providerId: String = "openai",
        modelId: String = "gpt-4",
        contextLimit: Int? = 128000
    ): AppState {
        val message = MessageWithParts(
            info = Message(
                id = "msg-1",
                role = "assistant",
                model = Message.ModelInfo(providerId = providerId, modelId = modelId),
                tokens = Message.TokenInfo(total = totalTokens)
            )
        )
        val providerModel = ProviderModel(
            id = modelId,
            limit = if (contextLimit != null) ProviderModelLimit(context = contextLimit) else null
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = providerId,
                    models = mapOf(modelId to providerModel)
                )
            )
        )
        return AppState(messages = listOf(message), providers = providers)
    }

    @Test
    fun `contextUsage returns null when no messages`() {
        val state = AppState()
        assertNull(state.contextUsage)
    }

    @Test
    fun `contextUsage returns null when no assistant messages`() {
        val userMessage = MessageWithParts(
            info = Message(id = "msg-1", role = "user")
        )
        val state = AppState(messages = listOf(userMessage))
        assertNull(state.contextUsage)
    }

    @Test
    fun `contextUsage returns null when assistant has no tokens`() {
        val message = MessageWithParts(
            info = Message(id = "msg-1", role = "assistant", tokens = null)
        )
        val state = AppState(messages = listOf(message))
        assertNull(state.contextUsage)
    }

    @Test
    fun `contextUsage returns null when total tokens is null`() {
        val message = MessageWithParts(
            info = Message(
                id = "msg-1",
                role = "assistant",
                tokens = Message.TokenInfo(total = null),
                model = Message.ModelInfo("openai", "gpt-4")
            )
        )
        val state = AppState(messages = listOf(message))
        assertNull(state.contextUsage)
    }

    @Test
    fun `contextUsage returns null when no resolvedModel`() {
        val message = MessageWithParts(
            info = Message(
                id = "msg-1",
                role = "assistant",
                tokens = Message.TokenInfo(total = 50000)
            )
        )
        val state = AppState(messages = listOf(message))
        assertNull(state.contextUsage)
    }

    @Test
    fun `contextUsage returns null when provider model not found`() {
        val message = MessageWithParts(
            info = Message(
                id = "msg-1",
                role = "assistant",
                model = Message.ModelInfo(providerId = "unknown", modelId = "missing"),
                tokens = Message.TokenInfo(total = 50000)
            )
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf(
                        "gpt-4" to ProviderModel(
                            id = "gpt-4",
                            limit = ProviderModelLimit(context = 128000)
                        )
                    )
                )
            )
        )
        val state = AppState(messages = listOf(message), providers = providers)
        assertNull(state.contextUsage)
    }

    @Test
    fun `contextUsage returns null when context limit is zero`() {
        val state = makeContextUsageState(totalTokens = 50000, contextLimit = 0)
        assertNull(state.contextUsage)
    }

    @Test
    fun `contextUsage returns null when context limit is null`() {
        val state = makeContextUsageState(totalTokens = 50000, contextLimit = null)
        assertNull(state.contextUsage)
    }

    @Test
    fun `contextUsage calculates correct percentage`() {
        val state = makeContextUsageState(totalTokens = 64000, contextLimit = 128000)
        val usage = state.contextUsage

        assertNotNull(usage)
        assertEquals(0.5f, usage!!.percentage, 0.001f)
        assertEquals(64000, usage.totalTokens)
        assertEquals(128000, usage.contextLimit)
    }

    @Test
    fun `contextUsage clamps percentage to 1f`() {
        val state = makeContextUsageState(totalTokens = 200000, contextLimit = 128000)
        val usage = state.contextUsage

        assertNotNull(usage)
        assertEquals(1.0f, usage!!.percentage, 0.001f)
    }

    @Test
    fun `contextUsage uses last assistant message with tokens`() {
        val oldAssistant = MessageWithParts(
            info = Message(
                id = "msg-1",
                role = "assistant",
                model = Message.ModelInfo("openai", "gpt-4"),
                tokens = Message.TokenInfo(total = 10000)
            )
        )
        val userMsg = MessageWithParts(
            info = Message(id = "msg-2", role = "user")
        )
        val newAssistant = MessageWithParts(
            info = Message(
                id = "msg-3",
                role = "assistant",
                model = Message.ModelInfo("openai", "gpt-4"),
                tokens = Message.TokenInfo(total = 90000)
            )
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf(
                        "gpt-4" to ProviderModel(
                            id = "gpt-4",
                            limit = ProviderModelLimit(context = 128000)
                        )
                    )
                )
            )
        )
        val state = AppState(
            messages = listOf(oldAssistant, userMsg, newAssistant),
            providers = providers
        )
        val usage = state.contextUsage

        assertNotNull(usage)
        assertEquals(90000, usage!!.totalTokens)
    }

    @Test
    fun `contextUsage near thresholds`() {
        val lowUsage = makeContextUsageState(totalTokens = 60000, contextLimit = 128000)
        assertTrue(lowUsage.contextUsage!!.percentage < 0.7f)

        val midUsage = makeContextUsageState(totalTokens = 100000, contextLimit = 128000)
        val midPct = midUsage.contextUsage!!.percentage
        assertTrue(midPct >= 0.7f && midPct < 0.9f)

        val highUsage = makeContextUsageState(totalTokens = 120000, contextLimit = 128000)
        assertTrue(highUsage.contextUsage!!.percentage >= 0.9f)
    }
}
