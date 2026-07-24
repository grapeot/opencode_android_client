package com.yage.opencode_client.ui.session

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.theme.StopRed
import kotlin.math.roundToInt

private enum class SwipeAnchor { Leading, Center, Trailing }

@Composable
private fun formatRelativeTime(updatedMs: Long): String = DateUtils.getRelativeTimeSpanString(
    updatedMs,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE
).toString()

@Composable
private fun sessionStatusLabel(status: SessionStatus?, attentionCount: Int): String? = when {
    attentionCount > 1 -> "${stringResource(R.string.sessions_status_need_attention)} · $attentionCount"
    attentionCount == 1 -> stringResource(R.string.sessions_status_need_attention)
    status == null -> null
    status.isBusy -> stringResource(R.string.sessions_status_running)
    status.isRetry -> stringResource(R.string.sessions_status_retrying)
    status.isIdle -> stringResource(R.string.sessions_status_idle)
    else -> null
}

@Composable
private fun sessionStatusColor(status: SessionStatus?, attentionCount: Int): Color = when {
    attentionCount > 0 -> StopRed
    status?.isBusy == true -> MaterialTheme.colorScheme.primary
    status?.isRetry == true -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeRevealRow(
    dragState: AnchoredDraggableState<SwipeAnchor>,
    enabled: Boolean,
    isArchived: Boolean,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    isSelected: Boolean,
    isBusy: Boolean,
    displayName: String,
    updatedTime: Long? = null,
    status: SessionStatus? = null,
    attentionCount: Int = 0,
    onSelect: () -> Unit,
    depth: Int = 0,
    hasChildren: Boolean = false,
    isCollapsed: Boolean = true,
    onToggleCollapse: (() -> Unit)? = null
) {
    val swipeRevealBackgroundColor = lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primaryContainer,
        0.28f
    )
    val accentColor = MaterialTheme.colorScheme.primary
    val selectionShape = RoundedCornerShape(12.dp)
    val titleColor = when {
        isArchived -> MaterialTheme.colorScheme.onSurfaceVariant
        isBusy -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val dragFlingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = dragState,
        positionalThreshold = { total: Float -> total * 0.5f }
    )

    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight().clipToBounds()) {
        SwipeActionBackground(
            isArchived = isArchived,
            backgroundColor = swipeRevealBackgroundColor,
            onArchive = onArchive,
            onRestore = onRestore,
            onDelete = onDelete,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(x = dragState.requireOffset().roundToInt(), y = 0) }
                .anchoredDraggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    flingBehavior = dragFlingBehavior
                )
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (isSelected && !isArchived) {
                        Modifier
                            .clip(selectionShape)
                            .background(accentColor.copy(alpha = 0.08f))
                            .drawBehind {
                                drawRect(
                                    color = accentColor,
                                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)
                                )
                            }
                    } else {
                        Modifier
                    }
                )
                .clickable(onClick = onSelect)
                .padding(start = (depth * 24).dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasChildren && onToggleCollapse != null) {
                IconButton(onClick = onToggleCollapse, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (isCollapsed) Icons.Default.ChevronRight else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isCollapsed) "Expand" else "Collapse",
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
            Column(modifier = Modifier.weight(1f, fill = false).offset(x = (-12).dp)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = titleColor
                )
                if (updatedTime != null || status != null || attentionCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (updatedTime != null) {
                            Text(
                                text = formatRelativeTime(updatedTime),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if ((status != null || attentionCount > 0) && updatedTime != null) {
                            Text("  ", style = MaterialTheme.typography.bodySmall)
                        }
                        if (status != null || attentionCount > 0) {
                            Text(
                                text = sessionStatusLabel(status, attentionCount) ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = if (isArchived && attentionCount == 0) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    sessionStatusColor(status, attentionCount)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.SwipeActionBackground(
    isArchived: Boolean,
    backgroundColor: Color,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .matchParentSize()
            .background(backgroundColor),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .fillMaxHeight()
                .clickable(onClick = if (isArchived) onRestore else onArchive),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (isArchived) Icons.Default.Restore else Icons.Default.Archive,
                    contentDescription = if (isArchived) stringResource(R.string.sessions_restore) else stringResource(R.string.sessions_archive),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (isArchived) stringResource(R.string.sessions_restore) else stringResource(R.string.sessions_archive),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(72.dp)
                .fillMaxHeight()
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.sessions_delete),
                    tint = StopRed,
                )
                Text(stringResource(R.string.sessions_delete), style = MaterialTheme.typography.labelSmall, color = StopRed)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionList(
    sessions: List<Session>,
    currentSessionId: String?,
    sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    attentionSessionIds: List<String> = emptyList(),
    hasMoreSessions: Boolean = false,
    isLoadingMoreSessions: Boolean = false,
    isRefreshingSessions: Boolean = false,
    expandedSessionIds: Set<String> = emptySet(),
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onArchiveSession: (String) -> Unit = {},
    onRestoreSession: (String) -> Unit = {},
    onToggleSessionExpanded: (String) -> Unit = {},
    onLoadMoreSessions: () -> Unit = {},
    onRefreshSessions: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    onCollapseSessions: (() -> Unit)? = null
) {
    val attentionCounts = remember(sessions, attentionSessionIds) {
        attentionCountsBySession(sessions, attentionSessionIds)
    }
    val activeSessions = remember(sessions) { sessions.filter { !it.isArchived } }
    val archivedSessions = remember(sessions) { sessions.filter { it.isArchived } }
    val activeTree = remember(activeSessions, attentionCounts) {
        prioritizeAttention(buildSessionTree(activeSessions), attentionCounts)
    }
    val archivedTree = remember(archivedSessions, attentionCounts) {
        prioritizeAttention(buildSessionTree(archivedSessions), attentionCounts)
    }
    val activeRows = remember(activeTree, expandedSessionIds) { flattenVisibleTree(activeTree, expandedSessionIds) }
    var archivedExpanded by remember { mutableStateOf(false) }
    val archivedRows = remember(archivedTree, expandedSessionIds, archivedExpanded) {
        if (archivedExpanded) flattenVisibleTree(archivedTree, expandedSessionIds) else emptyList()
    }
    val listState = rememberLazyListState()
    var wasRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(isRefreshingSessions) {
        if (wasRefreshing && !isRefreshingSessions && (activeRows.isNotEmpty() || archivedRows.isNotEmpty())) {
            listState.animateScrollToItem(0)
        }
        wasRefreshing = isRefreshingSessions
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.sessions_title), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.weight(1f))
                if (isLoadingMoreSessions) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (hasMoreSessions) {
                    TextButton(onClick = onLoadMoreSessions) { Text(stringResource(R.string.sessions_load_older)) }
                }
                IconButton(onClick = onCreateSession, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.sessions_new),
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                }
                if (onCollapseSessions != null) {
                    IconButton(onClick = onCollapseSessions) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.sessions_hide))
                    }
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = isRefreshingSessions,
            onRefresh = onRefreshSessions,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("session_list")
            ) {
                item(key = "active_header") {
                    SessionSectionHeader(title = stringResource(R.string.sessions_active), isExpanded = true, onClick = {})
                }
                if (activeRows.isEmpty()) {
                    item(key = "active_empty") { EmptySectionRow(stringResource(R.string.sessions_no_active)) }
                }
                itemsIndexed(activeRows, key = { _, (node, _) -> node.session.id }) { index, (node, depth) ->
                    SessionRowItem(
                        node = node,
                        depth = depth,
                        index = index,
                        currentSessionId = currentSessionId,
                        sessionStatuses = sessionStatuses,
                        attentionCounts = attentionCounts,
                        listIsScrolling = listState.isScrollInProgress,
                        expandedSessionIds = expandedSessionIds,
                        isArchived = false,
                        onSelectSession = onSelectSession,
                        onDeleteSession = onDeleteSession,
                        onArchiveSession = onArchiveSession,
                        onRestoreSession = onRestoreSession,
                        onToggleSessionExpanded = onToggleSessionExpanded,
                        showDivider = index < activeRows.size - 1,
                    )
                }

                item(key = "archived_header") {
                    SessionSectionHeader(
                        title = stringResource(R.string.sessions_archived),
                        isExpanded = archivedExpanded,
                        onClick = { archivedExpanded = !archivedExpanded }
                    )
                }
                if (archivedExpanded && archivedRows.isEmpty()) {
                    item(key = "archived_empty") { EmptySectionRow(stringResource(R.string.sessions_no_archived)) }
                }
                itemsIndexed(archivedRows, key = { _, (node, _) -> node.session.id }) { index, (node, depth) ->
                    SessionRowItem(
                        node = node,
                        depth = depth,
                        index = index,
                        currentSessionId = currentSessionId,
                        sessionStatuses = sessionStatuses,
                        attentionCounts = attentionCounts,
                        listIsScrolling = listState.isScrollInProgress,
                        expandedSessionIds = expandedSessionIds,
                        isArchived = true,
                        onSelectSession = onSelectSession,
                        onDeleteSession = onDeleteSession,
                        onArchiveSession = onArchiveSession,
                        onRestoreSession = onRestoreSession,
                        onToggleSessionExpanded = onToggleSessionExpanded,
                        showDivider = index < archivedRows.size - 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSectionHeader(title: String, isExpanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
            contentDescription = if (isExpanded) stringResource(R.string.sessions_collapse, title) else stringResource(R.string.sessions_expand, title),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptySectionRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SessionRowItem(
    node: SessionNode,
    depth: Int,
    index: Int,
    currentSessionId: String?,
    sessionStatuses: Map<String, SessionStatus>,
    attentionCounts: Map<String, Int>,
    listIsScrolling: Boolean,
    expandedSessionIds: Set<String>,
    isArchived: Boolean,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onArchiveSession: (String) -> Unit,
    onRestoreSession: (String) -> Unit,
    onToggleSessionExpanded: (String) -> Unit,
    showDivider: Boolean,
) {
    val session = node.session
    val isSelected = session.id == currentSessionId
    val hasChildren = node.children.isNotEmpty()
    val isExpanded = expandedSessionIds.contains(session.id)
    val density = LocalDensity.current
    val actionWidthPx = with(density) { 72.dp.toPx() }
    val dragState = remember(actionWidthPx) {
        AnchoredDraggableState(
            initialValue = SwipeAnchor.Center,
            anchors = DraggableAnchors {
                SwipeAnchor.Leading at actionWidthPx
                SwipeAnchor.Center at 0f
                SwipeAnchor.Trailing at -actionWidthPx
            }
        )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SwipeRevealRow(
            dragState = dragState,
            enabled = !listIsScrolling,
            isArchived = isArchived,
            onArchive = { onArchiveSession(session.id) },
            onRestore = { onRestoreSession(session.id) },
            onDelete = { onDeleteSession(session.id) },
            isSelected = isSelected,
            isBusy = sessionStatuses[session.id]?.isBusy == true,
            displayName = session.displayName,
            updatedTime = session.time?.updated,
            status = sessionStatuses[session.id],
            attentionCount = attentionCounts.getOrDefault(session.id, 0),
            onSelect = { onSelectSession(session.id) },
            depth = depth,
            hasChildren = hasChildren,
            isCollapsed = !isExpanded,
            onToggleCollapse = if (hasChildren) { { onToggleSessionExpanded(session.id) } } else null
        )
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}
