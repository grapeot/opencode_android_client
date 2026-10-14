package com.yage.opencode_client.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SessionReadState(
    val lastViewedAt: Long? = null,
    val failedAt: Long? = null,
    val blueAckBySource: Map<String, Long> = emptyMap()
)

@Singleton
class SessionReadStateStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("session_read_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private var keyPrefix: String? = null

    fun selectHost(hostProfileId: String) {
        keyPrefix = "session_read_state_${hostProfileId}_"
    }

    private fun entry(sessionId: String): SessionReadState? =
        keyPrefix?.let { prefs.getString("$it$sessionId", null) }?.let { raw ->
            runCatching { json.decodeFromString<SessionReadState>(raw) }.getOrNull()
        }

    fun lastViewedAt(sessionId: String): Long? = entry(sessionId)?.lastViewedAt
    fun failedAt(sessionId: String): Long? = entry(sessionId)?.failedAt

    fun blueAcknowledgements(sessionId: String): Map<String, Long> = entry(sessionId)?.blueAckBySource.orEmpty()

    fun acknowledgeBlue(sessionId: String, sources: Map<String, Long>) {
        val valid = sources.filter { (source, version) -> source != sessionId && version > 0 }
        if (valid.isEmpty()) return
        val current = entry(sessionId) ?: SessionReadState()
        save(sessionId, current.copy(blueAckBySource = current.blueAckBySource + valid))
    }

    fun markViewed(sessionId: String, atMillis: Long) {
        save(sessionId, (entry(sessionId) ?: SessionReadState()).copy(lastViewedAt = atMillis))
    }

    fun markFailed(sessionId: String, atMillis: Long) {
        save(sessionId, (entry(sessionId) ?: SessionReadState()).copy(failedAt = atMillis))
    }

    fun clearFailed(sessionId: String) {
        val current = entry(sessionId) ?: return
        if (current.failedAt != null) save(sessionId, current.copy(failedAt = null))
    }

    fun remove(sessionId: String) {
        val editor = prefs.edit().remove(keyFor(sessionId))
        all().forEach { (rowId, state) ->
            if (rowId != sessionId && sessionId in state.blueAckBySource) {
                editor.putString(keyFor(rowId), json.encodeToString(
                    state.copy(blueAckBySource = state.blueAckBySource - sessionId)))
            }
        }
        editor.apply()
    }

    fun all(): Map<String, SessionReadState> {
        val prefix = keyPrefix ?: return emptyMap()
        return prefs.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { key ->
                val id = key.removePrefix(prefix)
                entry(id)?.let { id to it }
            }.toMap()
    }

    private fun save(sessionId: String, state: SessionReadState) {
        prefs.edit().putString(keyFor(sessionId), json.encodeToString(state)).apply()
    }

    private fun keyFor(sessionId: String) = "${checkNotNull(keyPrefix) { "Select a host before writing read state" }}$sessionId"
}
