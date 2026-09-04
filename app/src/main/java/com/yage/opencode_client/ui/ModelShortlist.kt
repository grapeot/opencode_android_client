package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.ConfigProvider
import com.yage.opencode_client.data.model.ModelShortlistItem
import com.yage.opencode_client.data.model.ProviderModel
import com.yage.opencode_client.data.model.ProvidersResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A model the server reports as available (from `GET /provider` or the
 * `config/providers` fallback). Used to populate the Settings "add model"
 * catalog. Mirrors iOS `catalogModelPresets`.
 */
data class CatalogModel(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val shortName: String
) {
    val id: String get() = "$providerId/$modelId"
}

/** Result of building the catalog: the flat model list plus provider display names. */
data class CatalogBuildResult(
    val models: List<CatalogModel>,
    val providerDisplayNames: Map<String, String>
)

/**
 * One-time migration output: the shortlist to persist (seeded when absent),
 * the selected model ID derived from the legacy index, and the per-session
 * ID map derived from the legacy per-session index map.
 */
data class ModelShortlistMigration(
    val shortlist: List<ModelShortlistItem>,
    val selectedModelId: String?,
    val sessionModelIds: Map<String, String>
)

private val shortlistJson = Json { ignoreUnknownKeys = true }

fun encodeShortlist(items: List<ModelShortlistItem>): String =
    shortlistJson.encodeToString(items)

fun decodeShortlist(json: String?): List<ModelShortlistItem>? = try {
    json?.let { shortlistJson.decodeFromString<List<ModelShortlistItem>>(it) }
} catch (_: Exception) {
    null
}

/**
 * Display-name → short-name heuristic. Shared by the seeded presets, the
 * catalog, and [AppState.ModelOption.shortName] so a model always gets the
 * same compact label unless the user overrides it.
 */
fun suggestedShortName(displayName: String): String = when {
    displayName == "DeepSeek V4 Flash" -> "DS-Flash"
    displayName == "DeepSeek Local" -> "DS-L"
    displayName == "Ollama GLM 5.2" -> "OGLM-5.2"
    displayName == "GPT-5.6 Terra Fast" -> "GPT-TF"
    displayName == "GPT-5.6 Luna" -> "GPT-L"
    "Haiku" in displayName -> "Haiku"
    "Gemini" in displayName -> "Gemini"
    "GPT" in displayName -> "GPT"
    "Grok" in displayName -> "Grok"
    "Qwen" in displayName -> "Qwen"
    else -> displayName.split(" ").firstOrNull() ?: displayName
}

/** Seeds the shortlist from the hardcoded presets on first launch (D1). */
fun seedShortlistFromPresets(): List<ModelShortlistItem> =
    ModelPresets.list.map { option ->
        ModelShortlistItem(
            providerId = option.providerId,
            modelId = option.modelId,
            displayName = option.displayName,
            shortName = suggestedShortName(option.displayName)
        )
    }

/**
 * Builds the Settings catalog from a provider list. When [connectedProviderIds]
 * is non-null the catalog is scoped to connected providers (the `/provider`
 * path); when null every provider is included (the `config/providers`
 * fallback, D4). Only chat-capable models are kept.
 */
fun buildCatalog(
    providers: List<ConfigProvider>,
    connectedProviderIds: Set<String>?
): CatalogBuildResult {
    val names = mutableMapOf<String, String>()
    val models = mutableListOf<CatalogModel>()
    for (provider in providers) {
        if (connectedProviderIds != null && provider.id !in connectedProviderIds) continue
        provider.name?.takeIf { it.isNotEmpty() }?.let { names[provider.id] = it }
        for ((modelId, model) in provider.models.toSortedMap()) {
            if (!(model.capabilities?.isChatCapable ?: true)) continue
            val displayName = model.name?.takeIf { it.isNotBlank() } ?: modelId
            models.add(CatalogModel(provider.id, modelId, displayName, suggestedShortName(displayName)))
        }
    }
    models.sortWith(compareBy({ it.providerId }, { it.displayName }))
    return CatalogBuildResult(models, names)
}

/**
 * Flat "providerId/modelId" -> model index across all providers. Used to look
 * up a model's display name / context limit from the `config/providers` payload.
 */
fun buildProviderModelsIndex(providers: ProvidersResponse?): Map<String, ProviderModel> =
    providers?.providers?.flatMap { provider ->
        provider.models.flatMap { (modelKey, model) ->
            listOfNotNull(
                "${provider.id}/$modelKey" to model,
                model.id.takeIf { it.isNotEmpty() }?.let { "${provider.id}/$it" to model },
                model.resolvedProviderId?.let { resolvedProvider ->
                    model.id.takeIf { it.isNotEmpty() }?.let { modelId -> "$resolvedProvider/$modelId" to model }
                }
            )
        }
    }?.toMap() ?: emptyMap()

/**
 * Appends [providerId]/[modelId] to the shortlist if it is not already present.
 * Returns the new list and whether anything changed.
 */
fun addModelToShortlist(
    shortlist: List<ModelShortlistItem>,
    providerId: String,
    modelId: String,
    displayName: String
): Pair<List<ModelShortlistItem>, Boolean> {
    val id = "$providerId/$modelId"
    if (shortlist.any { it.id == id }) return shortlist to false
    val item = ModelShortlistItem(providerId, modelId, displayName, suggestedShortName(displayName))
    return (shortlist + item) to true
}

fun removeShortlistItem(shortlist: List<ModelShortlistItem>, id: String): List<ModelShortlistItem> =
    shortlist.filter { it.id != id }

/** Moves the item at [from] to [to]; returns the list unchanged when either index is out of range. */
fun moveShortlistItem(shortlist: List<ModelShortlistItem>, from: Int, to: Int): List<ModelShortlistItem> {
    if (from !in shortlist.indices || to !in shortlist.indices) return shortlist
    val next = shortlist.toMutableList()
    val item = next.removeAt(from)
    next.add(to, item)
    return next
}

/** Updates the user-editable short name; a blank value falls back to the suggested one. */
fun updateShortlistShortName(
    shortlist: List<ModelShortlistItem>,
    id: String,
    shortName: String
): List<ModelShortlistItem> {
    val trimmed = shortName.trim()
    return shortlist.map { item ->
        if (item.id == id) {
            item.copy(shortName = trimmed.ifEmpty { suggestedShortName(item.displayName) })
        } else {
            item
        }
    }
}

/** Refreshes display names from the catalog; short names (user edits) are preserved (D6). */
fun refreshShortlistDisplayNames(
    shortlist: List<ModelShortlistItem>,
    catalog: List<CatalogModel>
): List<ModelShortlistItem> {
    if (shortlist.isEmpty()) return shortlist
    val names = catalog.associate { it.id to it.displayName }
    return shortlist.map { item ->
        names[item.id]?.let { name ->
            if (name != item.displayName) item.copy(displayName = name) else item
        } ?: item
    }
}

/** Index of [selectedModelId] in the shortlist, or 0 when absent/empty. */
fun reanchorSelectedModelIndex(
    shortlist: List<ModelShortlistItem>,
    selectedModelId: String?
): Int {
    if (shortlist.isEmpty()) return 0
    return shortlist.indexOfFirst { it.id == selectedModelId }.takeIf { it >= 0 } ?: 0
}

/**
 * Maps the legacy index-based selection onto the ID-based model. The legacy
 * index always pointed into [ModelPresets.list] order, so the index→ID mapping
 * uses the canonical seed regardless of any user customization. [existingShortlist]
 * is used when present (a shortlist that was persisted before the ID schema
 * landed); otherwise the seed is used and becomes the persisted shortlist.
 */
fun migrateToIdBasedModelSelection(
    existingShortlist: List<ModelShortlistItem>?,
    legacySelectedIndex: Int,
    legacySessionModels: Map<String, String>
): ModelShortlistMigration {
    val seed = seedShortlistFromPresets()
    val shortlist = existingShortlist ?: seed

    // The legacy index is already normalized to the current ModelPresets.list
    // order by migrateRemovedGpt56SolProModelIndices() before this runs, so map
    // it straight onto the seed. Re-applying the legacy remap here would shift an
    // already-migrated index one slot further (e.g. 7 -> 6 -> 1).
    fun indexToId(index: Int): String? = seed.getOrNull(index)?.id

    val selectedModelId = indexToId(legacySelectedIndex) ?: seed.firstOrNull()?.id
    // Only migrate per-session entries that parse to an in-range index. A
    // malformed or out-of-range legacy value is dropped so it can't become a
    // permanent invalid selection; message history / the global selection take
    // over for that session instead.
    val sessionModelIds = legacySessionModels.entries
        .mapNotNull { (sessionId, raw) ->
            raw.toIntOrNull()?.let { indexToId(it) }?.let { id -> sessionId to id }
        }
        .toMap()
    return ModelShortlistMigration(shortlist, selectedModelId, sessionModelIds)
}