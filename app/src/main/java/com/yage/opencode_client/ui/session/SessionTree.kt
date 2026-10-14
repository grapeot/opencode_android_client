package com.yage.opencode_client.ui.session

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus

data class SessionNode(
    val session: Session,
    val children: List<SessionNode>
)

enum class SessionIndicator { NEEDS_YOU, FAILED, RUNNING, UNREAD }

fun settled(status: SessionStatus?): Boolean = status?.isIdle == true

fun unreadCountsBySession(
    sessions: List<Session>,
    statuses: Map<String, SessionStatus>,
    lastViewedAt: Map<String, Long>
): Map<String, Int> = indicatorCountsBySession(
    sessions,
    sessions.mapNotNull { session ->
        session.time?.updated?.takeIf { it > 0 && settled(statuses[session.id]) }?.let { session.id to it }
    }.toMap(),
    lastViewedAt
)

fun failureCountsBySession(
    sessions: List<Session>,
    failedAt: Map<String, Long>,
    lastViewedAt: Map<String, Long>
): Map<String, Int> = indicatorCountsBySession(sessions, failedAt, lastViewedAt)

private fun indicatorCountsBySession(
    sessions: List<Session>,
    sourceTimes: Map<String, Long>,
    lastViewedAt: Map<String, Long>
): Map<String, Int> {
    val sessionsById = sessions.associateBy { it.id }
    val counts = mutableMapOf<String, Int>()
    sourceTimes.forEach { (sourceId, time) ->
        if (time <= 0 || time <= (lastViewedAt[sourceId] ?: Long.MIN_VALUE)) return@forEach
        var sessionId: String? = sourceId
        val visited = mutableSetOf<String>()
        while (sessionId != null && visited.add(sessionId)) {
            counts[sessionId] = counts.getOrDefault(sessionId, 0) + 1
            sessionId = sessionsById[sessionId]?.parentId
        }
    }
    return counts
}

fun rowIndicator(
    session: Session,
    status: SessionStatus?,
    isCurrent: Boolean,
    attentionCount: Int,
    unreadCount: Int,
    failureCount: Int,
    descendantActiveCount: Int = 0,
    unknown: Boolean = status == null
): SessionIndicator? = when {
    session.isArchived -> null
    attentionCount > 0 -> SessionIndicator.NEEDS_YOU
    failureCount > 0 -> SessionIndicator.FAILED
    status?.isBusy == true || status?.isRetry == true || descendantActiveCount > 0 -> SessionIndicator.RUNNING
    unknown || !settled(status) || isCurrent -> null
    unreadCount > 0 -> SessionIndicator.UNREAD
    else -> null
}

data class SessionPresentation(
    val indicator: SessionIndicator?,
    val ownStatus: SessionStatus?,
    val activeDescendants: Int,
    val unknown: Boolean
)

/** All facts are calculated once per state change, never in an animated row. */
fun sessionPresentations(
    sessions: List<Session>, statuses: Map<String, SessionStatus>,
    known: Set<String>, snapshotKnown: Boolean, connected: Boolean,
    viewed: Map<String, Long>, failed: Map<String, Long>, attentionIds: List<String>, current: String?
): Map<String, SessionPresentation> {
    val effective = sessions.associate { s ->
        s.id to if (connected && (snapshotKnown || s.id in known)) statuses[s.id] ?: SessionStatus("idle")
        else SessionStatus("unknown")
    }
    val unknownCounts = attentionCountsBySession(sessions, effective.filterValues { !it.isIdle && !it.isBusy && !it.isRetry }.keys.toList())
    val active = descendantBusyCountsBySession(sessions, effective)
    val unread = unreadCountsBySession(sessions, effective, viewed)
    val failures = failureCountsBySession(sessions, failed, viewed)
    val attention = attentionCountsBySession(sessions, attentionIds)
    return sessions.associate { s ->
        val status = effective[s.id]
        val unknown = unknownCounts.getOrDefault(s.id, 0) > 0
        val descendants = active.getOrDefault(s.id, 0)
        s.id to SessionPresentation(rowIndicator(s, status, s.id == current,
            attention.getOrDefault(s.id, 0), unread.getOrDefault(s.id, 0), failures.getOrDefault(s.id, 0),
            descendants, unknown), status, descendants, unknown)
    }
}

fun attentionCountsBySession(
    sessions: List<Session>,
    attentionSessionIds: List<String>
): Map<String, Int> {
    val sessionsById = sessions.associateBy { it.id }
    val counts = mutableMapOf<String, Int>()

    attentionSessionIds.forEach { sourceSessionId ->
        var sessionId: String? = sourceSessionId
        val visited = mutableSetOf<String>()
        while (sessionId != null && visited.add(sessionId)) {
            counts[sessionId] = counts.getOrDefault(sessionId, 0) + 1
            sessionId = sessionsById[sessionId]?.parentId
        }
    }
    return counts
}

/** Running descendant subagent sessions per session. Each busy session adds one
 *  to every ancestor in the loaded tree (never to itself), so a parent row can
 *  surface a subordinate "subagents running" signal while it is itself idle.
 *  Cycle-safe; scoped to the loaded list. */
fun descendantBusyCountsBySession(
    sessions: List<Session>,
    sessionStatuses: Map<String, SessionStatus>
): Map<String, Int> {
    val sessionsById = sessions.associateBy { it.id }
    val counts = mutableMapOf<String, Int>()

    fun isBusy(id: String): Boolean {
        val status = sessionStatuses[id] ?: return false
        return status.isBusy || status.isRetry
    }

    sessions.forEach { session ->
        if (!isBusy(session.id)) return@forEach
        var ancestorId: String? = sessionsById[session.id]?.parentId
        val visited = mutableSetOf<String>()
        while (ancestorId != null && visited.add(ancestorId)) {
            counts[ancestorId] = counts.getOrDefault(ancestorId, 0) + 1
            ancestorId = sessionsById[ancestorId]?.parentId
        }
    }
    return counts
}

/** Running descendant subagent sessions of [rootId], most recently updated
 *  first. Cycle-safe BFS over `parentID` links; covers arbitrary nesting. */
fun runningDescendantSessions(
    sessions: List<Session>,
    sessionStatuses: Map<String, SessionStatus>,
    rootId: String
): List<Session> {
    val childrenByParent = sessions.groupBy { it.parentId }
    val seen = mutableSetOf(rootId)
    val out = mutableListOf<Session>()
    val queue = ArrayDeque(listOf(rootId))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (child in childrenByParent[current].orEmpty()) {
            if (!seen.add(child.id)) continue
            val status = sessionStatuses[child.id]
            if (status?.isBusy == true || status?.isRetry == true) {
                out += child
            }
            queue += child.id
        }
    }
    return out.sortedByDescending { it.time?.updated ?: 0L }
}

fun buildSessionTree(sessions: List<Session>): List<SessionNode> {
    val sessionIds = sessions.map { it.id }.toSet()
    val childrenMap = sessions.groupBy { it.parentId }
    fun buildNodes(parentId: String?): List<SessionNode> =
        (childrenMap[parentId] ?: emptyList())
            .sortedByDescending { it.time?.updated ?: 0L }
            .map { s -> SessionNode(session = s, children = buildNodes(s.id)) }
    val roots = buildNodes(null)
    val orphans = sessions
        .filter { it.parentId != null && it.parentId !in sessionIds }
        .sortedByDescending { it.time?.updated ?: 0L }
        .map { s -> SessionNode(session = s, children = buildNodes(s.id)) }
    return (roots + orphans).sortedByDescending { it.session.time?.updated ?: 0L }
}

fun prioritizeAttention(
    nodes: List<SessionNode>,
    attentionCounts: Map<String, Int>,
    descendantBusyCounts: Map<String, Int> = emptyMap()
): List<SessionNode> = nodes
    .map { node ->
        node.copy(
            children = prioritizeAttention(node.children, attentionCounts, descendantBusyCounts)
        )
    }
    .sortedWith(
        // A collapsed row hides its children, so a tree whose delegated work is
        // still running must surface by its parent row the same way an
        // attention-needing tree does.
        compareByDescending<SessionNode> {
            attentionCounts.getOrDefault(it.session.id, 0) > 0 ||
                descendantBusyCounts.getOrDefault(it.session.id, 0) > 0
        }
            .thenByDescending { it.session.time?.updated ?: 0L }
    )

fun flattenVisibleTree(
    nodes: List<SessionNode>,
    expandedIds: Set<String>,
    depth: Int = 0
): List<Pair<SessionNode, Int>> =
    nodes.flatMap { node ->
        listOf(node to depth) + if (expandedIds.contains(node.session.id)) {
            flattenVisibleTree(node.children, expandedIds, depth + 1)
        } else emptyList()
    }
