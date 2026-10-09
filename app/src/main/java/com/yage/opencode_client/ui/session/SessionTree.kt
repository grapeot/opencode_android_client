package com.yage.opencode_client.ui.session

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus

data class SessionNode(
    val session: Session,
    val children: List<SessionNode>
)

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
