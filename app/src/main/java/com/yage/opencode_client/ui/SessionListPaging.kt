package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.session.ArchivedSessionPagingUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Paging state for the session list on hosts that filter archived rows on the
 * server (`/experimental/session`). A legacy host keeps the recent-N `/session`
 * window, which mixes archived rows in and leaves [filteredDirectory] null.
 */
data class SessionListPaging(
    /** null until the host answers; false once `/experimental/session` proved unsupported. */
    val serverFiltersArchived: Boolean? = null,
    /** Resolved default directory for this host; "" records a server at "/", which keeps the legacy window. */
    val defaultDirectory: String? = null,
    /** Directory of the list on screen when it came from the filtered path; null for a legacy window. */
    val filteredDirectory: String? = null,
    /** Cursor for the next active page; null once the oldest page is loaded. */
    val activeCursor: Long? = null,
    /** "Load more" paged active rows past the first page since the last full refresh. */
    val hasLoadedDeeperActivePages: Boolean = false,
    /** Archived rows the user paged in or expanded. Only these survive a refresh. */
    val loadedArchivedIds: Set<String> = emptySet(),
    val archivedChildrenLoadedIds: Set<String> = emptySet(),
    val archivedCursor: Long? = null,
    val hasLoadedArchived: Boolean = false,
    val hasMoreArchived: Boolean = false,
    val isLoadingArchived: Boolean = false,
    /** The last archived load failed; the section offers a retry instead of staying blank. */
    val archivedLoadFailed: Boolean = false,
) {
    /** The list on screen came from the filtered path (a per-list fact, not a per-host one). */
    val usesFilteredList: Boolean get() = filteredDirectory != null

    val canLoadMoreArchived: Boolean
        get() = usesFilteredList && hasLoadedArchived && hasMoreArchived && !isLoadingArchived

    /**
     * Archived rows page in as subtree tops and restored rows as roots; their
     * children (archived or restored too) arrive when a row expands.
     */
    fun mayHaveUnloadedChildren(session: Session): Boolean =
        usesFilteredList && (session.isArchived || session.isRestored) && session.id !in archivedChildrenLoadedIds

    fun withArchivedPagesReset(): SessionListPaging = copy(
        loadedArchivedIds = emptySet(),
        archivedChildrenLoadedIds = emptySet(),
        archivedCursor = null,
        hasLoadedArchived = false,
        hasMoreArchived = false,
        archivedLoadFailed = false,
    )

    /** A reconnect may change the server's directory or protocol; probe both again. */
    fun withHostProbesReset(): SessionListPaging = copy(serverFiltersArchived = null, defaultDirectory = null)
}

internal fun AppState.archivedPagingUi(onLoad: () -> Unit) = ArchivedSessionPagingUi(
    filteredDirectory = sessionPaging.filteredDirectory,
    isLoading = sessionPaging.isLoadingArchived,
    hasLoaded = sessionPaging.hasLoadedArchived,
    canLoadMore = sessionPaging.canLoadMoreArchived || sessionPaging.archivedLoadFailed,
    failed = sessionPaging.archivedLoadFailed,
    childrenLoadedIds = sessionPaging.archivedChildrenLoadedIds,
    onLoad = onLoad,
)

internal object SessionListPagingLimits {
    /** Active rows load one server page at a time, newest first. */
    const val activePageSize = MainViewModelTimings.sessionPageSize
    /** Archived subtree tops per load, read from at most [maxArchivedRequestsPerLoad] pages of [archivedRequestSize]. */
    const val archivedPageSize = 50
    const val archivedRequestSize = 300
    const val maxArchivedRequestsPerLoad = 4
    /** Restore walks a whole subtree; past this many rows it refuses instead of restoring part of it. */
    const val descendantRestoreCap = 3000
    const val descendantFetchConcurrency = 6
    /** Restored roots are found by paging every root of the directory; see [fetchRestoredRoots]. */
    const val restoredScanPageSize = 300
    const val maxRestoredScanPages = 10
}

internal data class SessionListFetch(
    val rows: List<Session>,
    val hasMore: Boolean,
    /** Set when the server filtered out archived rows for this directory. */
    val filteredDirectory: String? = null,
    /** Filtered path only: the cursor after this first page, null when it held every active row. */
    val nextCursor: Long? = null,
    /** [AppState.sessionEventRevision] when the request started. */
    val startRevision: Long = 0,
    /** Restored roots from a full scan; null when this load did not scan. */
    val restoredRoots: List<Session>? = null,
)

private fun versionNumbers(version: String): List<Int>? {
    val core = version.trim().trimStart('v').split('-', '+').first()
    val parts = core.split('.').map { it.toIntOrNull() }
    if (parts.size < 2 || parts.any { it == null }) return null
    return parts.filterNotNull()
}

/**
 * `/experimental/session` with archive filtering and cursors shipped in
 * OpenCode 1.2.11. Older 1.x servers proxy unknown paths to app.opencode.ai,
 * which can answer 5xx or time out when the host is offline, so they are not
 * probed. Unparseable versions ("local") and local builds ("0.0.0-…") are probed.
 */
internal fun serverMayFilterArchivedSessions(version: String?): Boolean {
    val numbers = versionNumbers(version ?: return true)?.plus(listOf(0, 0)) ?: return true
    if (numbers.take(3).all { it == 0 }) return true
    val (major, minor, patch) = numbers
    return when {
        major != 1 -> major > 1
        minor != 2 -> minor > 2
        else -> patch >= 11
    }
}

/** A parseable non-zero version; "local", null and "0.0.0-…" builds are unknown. */
internal fun isKnownServerRelease(version: String?): Boolean =
    version != null && versionNumbers(version)?.any { it != 0 } == true

internal fun isUnsupportedSessionPage(error: Throwable): Boolean = when (error) {
    is SerializationException -> true // e.g. an HTML 200 from a packaged server
    is HttpException -> error.code() in setOf(404, 405, 501)
    else -> false
}

private fun isCancellation(error: Throwable): Boolean =
    error is CancellationException || (error is java.io.IOException && error.message == "Canceled")

/**
 * Archived rows that head an archived subtree: roots, and rows whose parent is
 * not known to be archived (a child archived on its own under an active parent,
 * or an orphan). Rows under an archived parent arrive when that parent expands.
 */
internal fun archivedSubtreeTops(rows: List<Session>, known: List<Session>): List<Session> {
    val archivedIds = (rows + known).filter { it.isArchived }.mapTo(HashSet()) { it.id }
    return rows.filter { row -> row.isArchived && row.parentId?.let { it in archivedIds } != true }
}

/**
 * `/global/event` carries session updates from every project, while the list
 * shows one directory. Rows from other directories are ignored unless they are
 * already listed or selected. Without a resolved directory everything applies.
 */
internal fun sessionEventApplies(state: AppState, session: Session): Boolean {
    val paging = state.sessionPaging
    val directory = paging.filteredDirectory ?: paging.defaultDirectory?.ifEmpty { null }
    return directory?.let { session.directory == it } == true ||
        session.id == state.currentSessionId ||
        state.sessions.any { it.id == session.id }
}

internal fun AppState.upsertSessionFromEvent(session: Session): AppState {
    val revision = sessionEventRevision + 1
    return copy(
        sessions = upsertSession(sessions, session),
        sessionEventRevision = revision,
        sessionEventRevisions = sessionEventRevisions + (session.id to revision),
    )
}

internal fun AppState.removeSessionFromEvent(sessionId: String): AppState {
    val revision = sessionEventRevision + 1
    return copy(
        sessions = sessions.filterNot { it.id == sessionId },
        sessionEventRevision = revision,
        sessionEventRevisions = sessionEventRevisions + (sessionId to revision),
    )
}

/**
 * Rows SSE changed after a fetch started win over that fetch: the local row
 * replaces or joins the page, and a row deleted meanwhile stays gone.
 */
internal fun preferEventRows(rows: List<Session>, current: AppState, startRevision: Long): List<Session> {
    val touched = current.sessionEventRevisions.filterValues { it > startRevision }.keys
    if (touched.isEmpty()) return rows
    val localById = current.sessions.associateBy { it.id }
    val pageIds = rows.mapTo(HashSet()) { it.id }
    val added = touched.filter { it !in pageIds }.mapNotNull(localById::get)
    return added + rows.mapNotNull { if (it.id in touched) localById[it.id] else it }
}

/** Adds rows not already present; a local row is never replaced by a page row. */
internal fun addMissingSessions(sessions: List<Session>, rows: List<Session>): List<Session> {
    val known = sessions.mapTo(HashSet()) { it.id }
    val added = rows.filter { known.add(it.id) }
    return if (added.isEmpty()) sessions else sessions + added
}

/**
 * V1 `/session` without a directory lists the server's current project, while
 * `/experimental/session` without one lists every project and with one matches
 * `session.directory` exactly. Sessions record the server's instance directory
 * (`GET /path`), which can differ from the project worktree, so prefer it.
 * Cached per host; a server at "/" is not a project and keeps the legacy window.
 */
internal suspend fun resolveSessionListDirectory(
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
): String? {
    state.value.sessionPaging.defaultDirectory?.let { return it.ifEmpty { null } }
    val generation = state.value.runtimeGeneration
    var resolved = repository.getInstanceDirectory().getOrNull()
    if (resolved.isNullOrEmpty()) resolved = repository.getCurrentProjectWorktree().getOrNull()
    if (resolved.isNullOrEmpty()) return null
    val cached = if (resolved == "/") "" else resolved
    state.update {
        if (it.runtimeGeneration != generation) it
        else it.copy(sessionPaging = it.sessionPaging.copy(defaultDirectory = cached))
    }
    return cached.ifEmpty { null }
}

/** One page of unarchived rows; a next cursor that does not strictly decrease ends paging. */
internal suspend fun fetchActiveSessionPage(
    repository: OpenCodeRepository,
    directory: String,
    cursor: Long?,
) = repository.getSessionPage(directory, includeArchived = false, limit = SessionListPagingLimits.activePageSize, cursor = cursor)
    .map { page ->
        val next = page.nextCursor
        if (next != null && cursor != null && next >= cursor) page.copy(nextCursor = null) else page
    }

/**
 * Restore writes `archived = -1` and the server keeps it, so a restored row is
 * neither in the unarchived list (which needs the field empty) nor archived.
 * Restore always starts at a subtree top, and roots are a small share of all
 * rows, so a full load pages every root with `archived=true&roots=true` and
 * keeps the restored ones. Their restored children load on expand.
 */
internal suspend fun fetchRestoredRoots(repository: OpenCodeRepository, directory: String): Result<List<Session>> {
    val restored = ArrayList<Session>()
    var cursor: Long? = null
    repeat(SessionListPagingLimits.maxRestoredScanPages) {
        val from = cursor
        val page = repository.getSessionPage(
            directory, includeArchived = true, limit = SessionListPagingLimits.restoredScanPageSize,
            cursor = from, rootsOnly = true,
        ).getOrElse { return Result.failure(it) }
        restored += page.rows.filter { it.isRestored }
        val next = page.nextCursor
        if (next == null || (from != null && next >= from)) return Result.success(restored)
        cursor = next
    }
    return Result.success(restored)
}

/**
 * Unarchived rows when the host filters on the server, else the legacy recent-N
 * window. A null success keeps the list on screen: a filtering host whose
 * default directory cannot be resolved must not mix the legacy window in.
 */
internal suspend fun fetchSessionList(
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    legacyLimit: Int,
    resetPaging: Boolean = false,
): Result<SessionListFetch?> {
    val generation = state.value.runtimeGeneration
    val version = state.value.serverVersion
    fun setFilters(value: Boolean) = state.update {
        if (it.runtimeGeneration != generation) it
        else it.copy(sessionPaging = it.sessionPaging.copy(serverFiltersArchived = value))
    }
    if (state.value.sessionPaging.serverFiltersArchived == null && !serverMayFilterArchivedSessions(version)) {
        setFilters(false)
    }
    if (state.value.sessionPaging.serverFiltersArchived != false) {
        val directory = resolveSessionListDirectory(repository, state)
        if (directory == null) {
            val paging = state.value.sessionPaging
            if (paging.serverFiltersArchived == true && paging.defaultDirectory == null) return Result.success(null)
        } else {
            val result = fetchActiveSessionPage(repository, directory, cursor = null)
            val page = result.getOrNull()
            if (page != null) {
                setFilters(true)
                // A full load (a new list or pull to refresh) rescans restored roots;
                // an incremental refresh keeps the ones already shown.
                val fullLoad = resetPaging || state.value.sessionPaging.filteredDirectory != directory
                val restored = if (!fullLoad) null else fetchRestoredRoots(repository, directory).getOrElse { error ->
                    android.util.Log.w("SessionListPaging", "restored root scan failed: ${error.message}")
                    null
                }
                return Result.success(SessionListFetch(page.rows, page.nextCursor != null, directory, page.nextCursor,
                    restoredRoots = restored))
            }
            val error = result.exceptionOrNull()!!
            when {
                isUnsupportedSessionPage(error) -> setFilters(false)
                // Unconfirmed host of unknown version: it may be a pre-1.2.11
                // server whose upstream proxy 5xxs or times out offline. Load
                // the legacy window this time and probe again later. A known
                // release has the route, so its errors surface instead.
                state.value.sessionPaging.serverFiltersArchived == null &&
                    !isKnownServerRelease(version) && !isCancellation(error) -> Unit
                else -> return Result.failure(error)
            }
        }
    }
    // Legacy hosts still resolve the directory once, so SSE events can be scoped to it.
    if (state.value.sessionPaging.defaultDirectory == null) resolveSessionListDirectory(repository, state)
    return repository.getSessions(legacyLimit).map { rows ->
        SessionListFetch(rows, hasMore = rows.size >= legacyLimit)
    }
}

/**
 * Applies a first-page fetch. With [keepDeeperPages], active rows older than
 * the first page's boundary stay as they were (they may be stale until a full
 * refresh); rows inside the first page's range that it no longer lists were
 * archived or deleted and leave. Archived rows the user paged in for the same
 * directory are kept. A legacy window replaces the list wholesale.
 */
internal fun applySessionListFetch(
    current: AppState,
    fetched: SessionListFetch,
    keepDeeperPages: Boolean,
): AppState {
    val paging = current.sessionPaging
    val directory = fetched.filteredDirectory
    val pageRows = preferEventRows(fetched.rows, current, fetched.startRevision)
    if (directory == null) {
        // Filtered paging state from an earlier list must not carry into a legacy window.
        return current.copy(
            sessions = mergeRefreshedSessionsPreservingLocalActivity(pageRows, current.sessions, current.currentSessionId),
            hasMoreSessions = fetched.hasMore,
            sessionPaging = paging.withArchivedPagesReset().copy(
                filteredDirectory = null,
                activeCursor = null,
                hasLoadedDeeperActivePages = false,
            ),
        )
    }
    var keepDeeper = keepDeeperPages
    var next = paging
    if (paging.filteredDirectory != directory) {
        next = paging.withArchivedPagesReset().copy(filteredDirectory = directory)
        keepDeeper = false
    }
    val activeIds = pageRows.mapTo(HashSet()) { it.id }
    // The first page covers every active row with time.updated >= its next
    // cursor (the server pages on `< cursor`); with no cursor it covers all.
    val boundary = fetched.nextCursor
    val deeper = if (!keepDeeper || boundary == null) emptyList() else current.sessions.filter { row ->
        val updated = row.time?.updated
        !row.isArchived && !row.isRestored && row.id !in activeIds && updated != null && updated < boundary
    }
    val retained = current.sessions.filter {
        it.isArchived && it.id in next.loadedArchivedIds && it.id !in activeIds
    }
    // The unarchived page never lists restored rows, so it cannot speak for
    // them: keep the local ones, or take a fresh scan's roots plus local
    // restored children (with their parent still listed) when this load scanned.
    val localRestored = if (paging.filteredDirectory != directory) emptyList() else current.sessions.filter { it.isRestored }
    val restored = fetched.restoredRoots?.let { roots ->
        val rootIds = roots.mapTo(HashSet()) { it.id }
        roots + localRestored.filter { it.parentId != null && it.id !in rootIds }
    } ?: localRestored
    val restoredRows = restored.filter { it.id !in activeIds && it.directory == directory }
    val sessions = mergeRefreshedSessionsPreservingLocalActivity(
        pageRows + restoredRows + deeper + retained,
        current.sessions,
        current.currentSessionId,
    )
    next = if (keepDeeper && next.hasLoadedDeeperActivePages && fetched.nextCursor != null) {
        // Keep paging from the deepest page already loaded (null when it was the last).
        next.copy(activeCursor = next.activeCursor?.let { minOf(it, fetched.nextCursor) })
    } else {
        next.copy(activeCursor = fetched.nextCursor, hasLoadedDeeperActivePages = false)
    }
    return current.copy(sessions = sessions, hasMoreSessions = next.activeCursor != null, sessionPaging = next)
}

/** Appends a deeper active page. Returns null when the page no longer belongs to the list on screen. */
internal fun applyActiveSessionPage(
    current: AppState,
    directory: String,
    cursor: Long,
    rows: List<Session>,
    nextCursor: Long?,
): AppState? {
    val paging = current.sessionPaging
    if (paging.filteredDirectory != directory || paging.activeCursor != cursor) return null
    return current.copy(
        sessions = addMissingSessions(current.sessions, rows.filter { !it.isArchived }),
        hasMoreSessions = nextCursor != null,
        sessionPaging = paging.copy(activeCursor = nextCursor, hasLoadedDeeperActivePages = true),
    )
}

/** Applies a load of archived subtree tops for [directory]. */
internal fun applyArchivedSessionLoad(
    current: AppState,
    directory: String,
    collected: List<Session>,
    cursor: Long?,
    exhausted: Boolean,
    reset: Boolean,
): AppState {
    var paging = current.sessionPaging
    if (paging.filteredDirectory != directory) return current
    var sessions = current.sessions
    if (reset) {
        val dropped = paging.loadedArchivedIds
        sessions = sessions.filterNot { it.id in dropped && it.isArchived && it.id != current.currentSessionId }
        paging = paging.withArchivedPagesReset()
    }
    val tops = archivedSubtreeTops(collected, sessions)
    sessions = addMissingSessions(sessions, tops + collected.filter { it.isRestored })
    paging = paging.copy(
        loadedArchivedIds = paging.loadedArchivedIds + tops.map { it.id },
        archivedCursor = if (exhausted) null else cursor,
        hasMoreArchived = !exhausted,
        hasLoadedArchived = true,
    )
    return current.copy(sessions = sessions, sessionPaging = paging)
}

internal fun applyArchivedChildren(current: AppState, children: List<Session>, parentId: String): AppState {
    val rows = children.filter { it.parentId == parentId }
    val paging = current.sessionPaging
    return current.copy(
        sessions = addMissingSessions(current.sessions, rows),
        sessionPaging = paging.copy(
            loadedArchivedIds = paging.loadedArchivedIds + rows.filter { it.isArchived }.map { it.id },
            archivedChildrenLoadedIds = paging.archivedChildrenLoadedIds + parentId,
        ),
    )
}

/**
 * Keeps rows this device just archived in a loaded archived section when they
 * fall inside the window already paged in; older rows wait for the user to page
 * that far, as they would after any refresh. No request.
 */
internal fun noteArchivedInPlace(current: AppState, ids: Set<String>): AppState {
    val paging = current.sessionPaging
    if (!paging.usesFilteredList || !paging.hasLoadedArchived || ids.isEmpty()) return current
    val oldest = if (paging.hasMoreArchived) {
        current.sessions.filter { it.id in paging.loadedArchivedIds && it.isArchived }.mapNotNull { it.time?.updated }.minOrNull()
    } else null
    val archived = current.sessions.filter { it.id in ids && it.isArchived }
    val kept = archivedSubtreeTops(archived, current.sessions)
        .filter { row -> oldest == null || (row.time?.updated ?: Long.MIN_VALUE) >= oldest }
        .map { it.id }
    if (kept.isEmpty()) return current
    return current.copy(sessionPaging = paging.copy(loadedArchivedIds = paging.loadedArchivedIds + kept))
}

/**
 * Restore walks the loaded subtree, so it first fetches every archived
 * descendant afresh, level by level with bounded concurrency. Fails when any
 * level cannot be read, so a restore never silently stops at the root.
 */
internal suspend fun fetchArchivedDescendants(
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    rootId: String,
): Result<Unit> {
    val directory = state.value.sessionPaging.filteredDirectory ?: return Result.success(Unit)
    var frontier = listOf(rootId)
    val seen = hashSetOf(rootId)
    while (frontier.isNotEmpty()) {
        if (seen.size > SessionListPagingLimits.descendantRestoreCap) {
            return Result.failure(IllegalStateException("Too many descendant sessions to restore"))
        }
        val fetched = try {
            frontier.chunked(SessionListPagingLimits.descendantFetchConcurrency).flatMap { chunk ->
                coroutineScope {
                    chunk.map { id -> async { id to repository.getSessionChildren(id).getOrThrow() } }.awaitAll()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        if (state.value.sessionPaging.filteredDirectory != directory) return Result.failure(CancellationException())
        state.update { current -> fetched.fold(current) { acc, (id, children) -> applyArchivedChildren(acc, children, id) } }
        frontier = fetched.flatMap { (id, children) ->
            children.filter { it.parentId == id && seen.add(it.id) }.map { it.id }
        }
    }
    return Result.success(Unit)
}
