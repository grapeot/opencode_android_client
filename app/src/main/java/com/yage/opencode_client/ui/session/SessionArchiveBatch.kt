package com.yage.opencode_client.ui.session

import com.yage.opencode_client.data.model.Session
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.HttpException

internal data class SessionArchivePlan(val ids: List<String>, val additionalCount: Int, val parents: Map<String, String?>)

internal fun planSessionArchive(sessions: List<Session>, selected: Set<String>): SessionArchivePlan {
    val children = sessions.groupBy { it.parentId }
    val visited = mutableSetOf<String>()
    val ordered = mutableListOf<String>()
    fun visit(session: Session) {
        if (!visited.add(session.id)) return
        children[session.id].orEmpty().forEach(::visit)
        if (!session.isArchived) ordered += session.id
    }
    sessions.filter { it.id in selected && !it.isArchived }.forEach(::visit)
    return SessionArchivePlan(ordered, ordered.count { it !in selected }, sessions.associate { it.id to it.parentId })
}

data class SessionArchiveOutcome(
    val archived: Set<String>,
    val missing: Set<String>,
    val remaining: Int,
    val foundChildren: Boolean,
)

private fun Throwable?.isNotFound() = this is HttpException && code() == 404

internal suspend fun executeSessionArchive(
    plan: SessionArchivePlan,
    timestamp: Long,
    canContinue: () -> Boolean,
    readChildren: suspend (String) -> Result<List<Session>>,
    readSession: suspend (String) -> Result<Session>,
    archive: suspend (String, Long) -> Result<Session>,
    publish: (Session) -> Unit,
    remove: (String) -> Unit,
): SessionArchiveOutcome {
    val archived = mutableSetOf<String>()
    val missing = mutableSetOf<String>()
    val blocked = mutableSetOf<String>()
    var foundChildren = false
    suspend fun inScope(): Boolean {
        currentCoroutineContext().ensureActive()
        return canContinue()
    }
    fun blockAncestors(id: String) {
        var parent = plan.parents[id]
        while (parent != null && blocked.add(parent)) parent = plan.parents[parent]
    }
    fun markMissing(id: String) {
        missing += id
        remove(id)
    }
    batch@ for (id in plan.ids) {
        if (!inScope()) break
        if (id in blocked) continue
        val root = readChildren(id)
        if (!inScope()) break
        if (root.isFailure) {
            // A compatible server can return 404 for the route, not the session.
            if (root.exceptionOrNull().isNotFound()) {
                val session = readSession(id)
                if (!inScope()) break
                if (session.exceptionOrNull().isNotFound()) {
                    markMissing(id)
                    continue
                }
            }
            blockAncestors(id)
            continue
        }
        val frontier = ArrayDeque(root.getOrThrow().filter { it.parentId == id })
        val seen = mutableSetOf(id)
        var activeChild = false
        var incompleteRead = false
        while (frontier.isNotEmpty()) {
            val child = frontier.removeFirst()
            if (!seen.add(child.id)) continue
            publish(child)
            if (!child.isArchived) {
                activeChild = true
            } else if (child.id !in archived) {
                // An archived intermediary can have an active grandchild.
                val nested = readChildren(child.id)
                if (!inScope()) break@batch
                if (nested.isFailure) incompleteRead = true
                else frontier.addAll(nested.getOrThrow().filter { it.parentId == child.id })
            }
        }
        if (activeChild || incompleteRead) {
            foundChildren = foundChildren || activeChild
            blockAncestors(id)
            continue
        }
        if (!inScope()) break
        val result = archive(id, timestamp)
        if (!inScope()) break
        val updated = result.getOrNull()
        when {
            updated?.id == id && updated.isArchived -> {
                publish(updated)
                archived += id
            }
            result.exceptionOrNull().isNotFound() -> markMissing(id)
            else -> blockAncestors(id)
        }
    }
    return SessionArchiveOutcome(archived, missing, plan.ids.size - archived.size - missing.size, foundChildren)
}
