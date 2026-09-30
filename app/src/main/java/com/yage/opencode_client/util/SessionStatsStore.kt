package com.yage.opencode_client.util

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SessionStatsEntry(
    val rounds: Int = 0,
    val toolCalls: Int = 0,
    val seenUserMessageIds: Set<String> = emptySet(),
    val seenToolPartIds: Set<String> = emptySet()
)

/**
 * Per-session round/tool-call counters persisted across app restarts.
 *
 * The server exposes no session-level user-message or tool-part aggregate, so
 * the counts are maintained client-side: incremental events (SSE) add new IDs
 * only, and every loaded message window is reconciled against the seen sets.
 * Reconciliation is add-only; a full reseed happens after a reset (revert).
 * Counts are per-session and per-device; clearing app data reseeds them.
 */
@Singleton
class SessionStatsStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun entry(sessionId: String): SessionStatsEntry? =
        prefs.getString(keyFor(sessionId), null)?.let { raw ->
            runCatching { json.decodeFromString<SessionStatsEntry>(raw) }.getOrNull()
        }

    /** Increments rounds once per user message ID; replays of a known ID are no-ops. */
    fun recordUserMessage(sessionId: String, messageId: String) {
        mutate(sessionId) { entry ->
            if (messageId in entry.seenUserMessageIds) {
                entry
            } else {
                entry.copy(
                    rounds = entry.rounds + 1,
                    seenUserMessageIds = entry.seenUserMessageIds + messageId
                )
            }
        }
    }

    /** Increments toolCalls once per tool part ID; replays of a known ID are no-ops. */
    fun recordToolPart(sessionId: String, partId: String) {
        mutate(sessionId) { entry ->
            if (partId in entry.seenToolPartIds) {
                entry
            } else {
                entry.copy(
                    toolCalls = entry.toolCalls + 1,
                    seenToolPartIds = entry.seenToolPartIds + partId
                )
            }
        }
    }

    /**
     * Seeds from the window when the session has no entry yet, otherwise adds
     * window IDs not yet seen. Never decreases.
     */
    fun seedOrReconcile(sessionId: String, userMessageIds: Set<String>, toolPartIds: Set<String>) {
        mutate(sessionId) { entry ->
            val newUser = userMessageIds - entry.seenUserMessageIds
            val newTool = toolPartIds - entry.seenToolPartIds
            entry.copy(
                rounds = entry.rounds + newUser.size,
                toolCalls = entry.toolCalls + newTool.size,
                seenUserMessageIds = entry.seenUserMessageIds + newUser,
                seenToolPartIds = entry.seenToolPartIds + newTool
            )
        }
    }

    /** Drops the entry so the next window load reseeds it (used after revert). */
    fun reset(sessionId: String) {
        prefs.edit().remove(keyFor(sessionId)).apply()
    }

    private fun mutate(sessionId: String, transform: (SessionStatsEntry) -> SessionStatsEntry) {
        val next = transform(entry(sessionId) ?: SessionStatsEntry())
        prefs.edit().putString(keyFor(sessionId), json.encodeToString(next)).apply()
    }

    private fun keyFor(sessionId: String) = "session_stats_$sessionId"

    private companion object {
        const val PREFS_NAME = "session_stats"
    }
}
