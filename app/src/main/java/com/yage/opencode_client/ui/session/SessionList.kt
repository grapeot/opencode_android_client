package com.yage.opencode_client.ui.session

import android.text.format.DateUtils
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

private enum class SwipeAnchor { Start, End }

@Composable
private fun formatRelativeTime(updatedMs: Long): String {
    return DateUtils.getRelativeTimeSpanString(
        updatedMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
}

@Composable
private fun sessionStatusLabel(status: SessionStatus?): String? = when {
    status == null -> null
    status.isBusy -> "Running"
    status.isRetry -> "Retrying"
    status.isIdle -> "Idle"
    else -> null
}

@Composable
private fun sessionStatusColor(status: SessionStatus?): Color = when {
    status?.isBusy == true -> MaterialTheme.colorScheme.primary
    status?.isRetry == true -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeRevealRow(
    dragState: AnchoredDraggableState<SwipeAnchor>,
    enabled: Boolean,
    onDelete: () -> Unit,
    altBg: Boolean,
    isSelected: Boolean,
    isBusy: Boolean,
    displayName: String,
    updatedTime: Long? = null,
    status: SessionStatus? = null,
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
    val titleColor = if (isBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(swipeRevealBackgroundColor)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete session",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(x = -dragState.requireOffset().roundToInt(), y = 0) }
                .anchoredDraggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    reverseDirection = true
                )
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (isSelected) {
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
                .padding(start = (12 + depth * 24).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasChildren && onToggleCollapse != null) {
                IconButton(
                    onClick = onToggleCollapse,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (isCollapsed) Icons.Default.ChevronRight else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isCollapsed) "Expand" else "Collapse",
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                // Reserve the chevron's width even when there's no chevron, so
                // rows with and without children align their titles identically.
                Spacer(modifier = Modifier.size(24.dp))
            }
            Column(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = titleColor
                )
                if (updatedTime != null || status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (updatedTime != null) {
                            Text(
                                text = formatRelativeTime(updatedTime),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (status != null && updatedTime != null) {
                            Text(
                                text = "  ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (status != null) {
                            Text(
                                text = sessionStatusLabel(status) ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = sessionStatusColor(status)
                            )
                        }
                    }
                }
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
    hasMoreSessions: Boolean = false,
    isLoadingMoreSessions: Boolean = false,
    isRefreshingSessions: Boolean = false,
    expandedSessionIds: Set<String> = emptySet(),
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onToggleSessionExpanded: (String) -> Unit = {},
    onLoadMoreSessions: () -> Unit = {},
    onRefreshSessions: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null
) {
    val tree = remember(sessions) { buildSessionTree(sessions) }
    val visibleRows = remember(tree, expandedSessionIds) {
        flattenVisibleTree(tree, expandedSessionIds)
    }
    val listState = rememberLazyListState()

    LaunchedEffect(listState, visibleRows.size, hasMoreSessions, isLoadingMoreSessions) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible != null && hasMoreSessions && !isLoadingMoreSessions && lastVisible >= visibleRows.lastIndex - 2) {
                    onLoadMoreSessions()
                }
            }
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onCreateSession) {
                    Text("New")
                }
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            itemsIndexed(visibleRows, key = { _, (node, _) -> node.session.id }) { index, (node, depth) ->
                val session = node.session
                val isSelected = session.id == currentSessionId
                val altBg = index % 2 == 1
                val hasChildren = node.children.isNotEmpty()
                val isExpanded = expandedSessionIds.contains(session.id)
                val density = LocalDensity.current
                val deleteWidthPx = with(density) { 56.dp.toPx() }
                val decay = rememberSplineBasedDecay<Float>()
                val dragState = remember(deleteWidthPx) {
                    AnchoredDraggableState(
                        initialValue = SwipeAnchor.Start,
                        anchors = DraggableAnchors {
                            SwipeAnchor.Start at 0f
                            SwipeAnchor.End at deleteWidthPx
                        },
                        positionalThreshold = { total: Float -> total * 0.5f },
                        velocityThreshold = { with(density) { 100.dp.toPx() } },
                        snapAnimationSpec = tween(),
                        decayAnimationSpec = decay
                    )
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwipeRevealRow(
                        dragState = dragState,
                        enabled = !listState.isScrollInProgress,
                        onDelete = { onDeleteSession(session.id) },
                        altBg = altBg,
                        isSelected = isSelected,
                        isBusy = sessionStatuses[session.id]?.isBusy == true,
                        displayName = session.displayName,
                        updatedTime = session.time?.updated,
                        status = sessionStatuses[session.id],
                        onSelect = { onSelectSession(session.id) },
                        depth = depth,
                        hasChildren = hasChildren,
                        isCollapsed = !isExpanded,
                        onToggleCollapse = if (hasChildren) { { onToggleSessionExpanded(session.id) } } else null
                    )
                    if (index < visibleRows.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            if (isLoadingMoreSessions) {
                item(key = "load_more_progress") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        }
    }
}
