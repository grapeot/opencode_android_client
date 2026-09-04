package com.yage.opencode_client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

@Serializable
data class HealthResponse(
    val healthy: Boolean,
    val version: String? = null
)

@Serializable
data class ProvidersResponse(
    val providers: List<ConfigProvider> = emptyList(),
    @SerialName("default") val defaultByProvider: Map<String, String> = emptyMap()
) {
    /** First default provider/model when API returns Map<providerId, modelId>. */
    val default: DefaultProvider?
        get() = defaultByProvider.entries.firstOrNull()?.let {
            DefaultProvider(providerId = it.key, modelId = it.value)
        }
}

/**
 * Response of `GET /provider`: every known provider plus the subset that is
 * connected (authenticated, or keyless-local providers like a custom Ollama
 * endpoint). Used to scope the model catalog to models the user can actually
 * run. Mirrors iOS `ProviderRegistryResponse`.
 */
@Serializable
data class ProviderRegistryResponse(
    val all: List<ConfigProvider> = emptyList(),
    @SerialName("default") val defaultByProvider: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList()
) {
    val connectedProviderIds: Set<String> get() = connected.toSet()
}

@Serializable
data class ConfigProvider(
    val id: String = "",
    val name: String? = null,
    val models: Map<String, ProviderModel> = emptyMap()
)

@Serializable
data class ProviderModel(
    val id: String = "",
    val name: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    @SerialName("providerId") val providerIdAlt: String? = null,
    val limit: ProviderModelLimit? = null,
    val capabilities: ProviderModelCapabilities? = null
) {
    val resolvedProviderId: String? get() = providerId ?: providerIdAlt
}

/**
 * Minimal slice of the server `capabilities` object. Chat-capable means the
 * model can produce text output; missing info is treated as capable (older
 * servers may not report it) so a false negative never hides a working model.
 */
@Serializable
data class ProviderModelCapabilities(
    val output: ProviderModelOutput? = null
) {
    val isChatCapable: Boolean get() = output?.text ?: true
}

@Serializable
data class ProviderModelOutput(
    val text: Boolean? = null
)

@Serializable
data class ProviderModelLimit(
    val context: Int? = null,
    val input: Int? = null,
    val output: Int? = null
)

@Serializable
data class DefaultProvider(
    val providerId: String,
    val modelId: String
)
