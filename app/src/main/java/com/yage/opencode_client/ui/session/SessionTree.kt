package com.yage.opencode_client.ui.session

import com.yage.opencode_client.data.model.Session

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
    attentionCounts: Map<String, Int>
): List<SessionNode> = nodes
    .map { node -> node.copy(children = prioritizeAttention(node.children, attentionCounts)) }
    .sortedWith(
        compareByDescending<SessionNode> { attentionCounts.getOrDefault(it.session.id, 0) > 0 }
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
