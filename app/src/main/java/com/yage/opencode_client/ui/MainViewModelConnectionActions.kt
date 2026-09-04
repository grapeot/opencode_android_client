package com.yage.opencode_client.ui

import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.util.SettingsManager
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun applySavedSettings(
    repository: OpenCodeRepository,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    state: MutableStateFlow<AppState>
) {
    settingsManager.migrateRemovedGpt56SolProModelIndices()
    migrateModelSelectionToIds(settingsManager)
    val currentProfile = hostProfileStore.currentProfile()
    val password = currentProfile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
    repository.configure(
        baseUrl = currentProfile.serverUrl,
        username = currentProfile.basicAuth?.username,
        password = password
    )

    val shortlist = decodeShortlist(settingsManager.modelShortlistJson) ?: seedShortlistFromPresets()
    val selectedModelId = settingsManager.selectedModelId
    val clampedModelIndex = reanchorSelectedModelIndex(shortlist, selectedModelId)

    state.update {
        it.copy(
            currentSessionId = settingsManager.currentSessionId,
            hostProfiles = hostProfileStore.profiles(),
            currentHostProfileId = currentProfile.id,
            selectedModelIndex = clampedModelIndex,
            selectedModelId = selectedModelId,
            modelShortlist = shortlist,
            selectedAgentName = settingsManager.selectedAgentName ?: "build",
            themeMode = settingsManager.themeMode,
            languageMode = settingsManager.languageMode
        )
    }

    val savedSignature = settingsManager.aiBuilderLastOKSignature
    val currentSignature = aiBuilderSignature(
        settingsManager.aiBuilderBaseURL.trim(),
        sanitizeBearerToken(settingsManager.aiBuilderToken)
    )
    if (savedSignature != null && savedSignature == currentSignature) {
        state.update { it.copy(aiBuilderConnectionOK = true) }
    }
}

/**
 * One-time migration from the legacy index-based model selection to the
 * ID-based model. Seeds the shortlist from the presets when none is persisted,
 * maps the legacy selected index and per-session index map onto stable
 * "providerId/modelId" strings, and stamps the schema version so it runs once.
 */
internal fun migrateModelSelectionToIds(settingsManager: SettingsManager) {
    if (settingsManager.modelShortlistSchemaVersion >= SettingsManager.MODEL_SHORTLIST_SCHEMA_VERSION) return
    val rawShortlistJson = settingsManager.modelShortlistJson
    // Seed only when the shortlist key is genuinely absent. When the key is
    // present but undecodable, keep the on-disk value untouched (don't clobber a
    // user's list with the defaults) and fall back to the seed in-memory.
    val existingShortlist = rawShortlistJson?.let { decodeShortlist(it) }
    val migration = migrateToIdBasedModelSelection(
        existingShortlist = existingShortlist,
        legacySelectedIndex = settingsManager.selectedModelIndex,
        legacySessionModels = settingsManager.getLegacySessionModels()
    )
    // Persist the resolved shortlist only when we have a real one (absent -> seed,
    // present -> kept). A present-but-malformed list is left on disk as-is.
    if (rawShortlistJson == null || existingShortlist != null) {
        settingsManager.modelShortlistJson = encodeShortlist(migration.shortlist)
    }
    migration.selectedModelId?.let { settingsManager.selectedModelId = it }
    settingsManager.setSessionModelIds(migration.sessionModelIds)
    settingsManager.modelShortlistSchemaVersion = SettingsManager.MODEL_SHORTLIST_SCHEMA_VERSION
}

internal fun launchConnectionTest(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onHealthyConnection: () -> Unit
) {
    scope.launch {
        state.update { it.copy(isConnecting = true, error = null) }
        repository.checkHealth()
            .onSuccess { health ->
                state.update {
                    it.copy(
                        isConnected = health.healthy,
                        serverVersion = health.version,
                        isConnecting = false
                    )
                }
                if (health.healthy) {
                    onHealthyConnection()
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isConnected = false,
                        isConnecting = false,
                        error = errorMessageOrFallback(error, "Connection failed")
                    )
                }
            }
    }
}

internal fun launchAIBuilderConnectionTest(
    scope: CoroutineScope,
    settingsManager: SettingsManager,
    voiceFlowClient: VoiceFlowClient,
    state: MutableStateFlow<AppState>
) {
    scope.launch {
        state.update { it.copy(isTestingAIBuilderConnection = true, aiBuilderConnectionError = null) }
        val token = sanitizeBearerToken(settingsManager.aiBuilderToken)
        if (token.isEmpty()) {
            state.update {
                it.copy(
                    isTestingAIBuilderConnection = false,
                    aiBuilderConnectionOK = false,
                    aiBuilderConnectionError = "AI Builder token is empty"
                )
            }
            return@launch
        }

        val baseURL = settingsManager.aiBuilderBaseURL.trim()
        // Refresh the library config with the current endpoint before probing so the
        // reachability check hits the same backend the realtime session will use.
        voiceFlowClient.updateConfig(
            VoiceFlowConfig(
                endpoint = baseURL.ifEmpty { VoiceFlowConfig.DEFAULT_ENDPOINT },
                tokenProvider = { token },
            )
        )
        runCatching { voiceFlowClient.testConnection() }
            .onSuccess {
                val signature = aiBuilderSignature(baseURL, token)
                settingsManager.aiBuilderLastOKSignature = signature
                settingsManager.aiBuilderLastOKTestedAt = System.currentTimeMillis()
                state.update {
                    it.copy(
                        isTestingAIBuilderConnection = false,
                        aiBuilderConnectionOK = true,
                        aiBuilderConnectionError = null
                    )
                }
            }
            .onFailure { error ->
                settingsManager.aiBuilderLastOKSignature = null
                state.update {
                    it.copy(
                        isTestingAIBuilderConnection = false,
                        aiBuilderConnectionOK = false,
                        aiBuilderConnectionError = errorMessageOrFallback(error, "Connection failed")
                    )
                }
            }
    }
}
