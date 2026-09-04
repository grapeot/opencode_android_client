package com.yage.opencode_client.data.model

import kotlinx.serialization.Serializable

/**
 * A user-curated model entry shown in the chat model picker. Persisted locally
 * (JSON array) and managed from Settings. Mirrors iOS `ModelShortlistItem`.
 * The stable identity is `providerId/modelId`; `displayName`/`shortName` are
 * display-only and may be refreshed from the server catalog.
 */
@Serializable
data class ModelShortlistItem(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val shortName: String
) {
    val id: String get() = "$providerId/$modelId"
}