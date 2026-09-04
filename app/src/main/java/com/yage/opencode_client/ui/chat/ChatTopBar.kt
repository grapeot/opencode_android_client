package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.AIUsageQuota
import com.yage.opencode_client.data.model.AIUsageQuotaSnapshot
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.session.SessionList
import com.yage.opencode_client.ui.theme.BrandGold
import java.util.Locale

internal data class ChatTopBarState(
    val sessions: List<Session>,
    val currentSessionId: String?,
    val sessionStatuses: Map<String, SessionStatus>,
    val attentionSessionIds: List<String> = emptyList(),
    val hasMoreSessions: Boolean,
    val isLoadingMoreSessions: Boolean,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val availableModels: List<AppState.ModelOption>,
    val selectedModelIndex: Int,
    val contextUsage: AppState.ContextUsage?,
    val sessionTodos: List<TodoItem> = emptyList(),
    val aiUsageEnabled: Boolean = false,
    val selectedAIUsageQuota: AIUsageQuota? = null,
    val aiUsageQuotaSnapshot: AIUsageQuotaSnapshot? = null,
    val isLoadingAIUsage: Boolean = false,
    val isRefreshingAIUsage: Boolean = false,
    val aiUsageError: String? = null,
    val aiUsageDashboardUrl: String = "",
    val showSettingsButton: Boolean = true,
    val showNewSessionInTopBar: Boolean = true,
    val showSessionListInTopBar: Boolean = true
)

internal data class ChatTopBarActions(
    val onSelectSession: (String) -> Unit,
    val onCreateSession: () -> Unit,
    val onDeleteSession: (String) -> Unit,
    val onArchiveSession: (String) -> Unit = {},
    val onRestoreSession: (String) -> Unit = {},
    val onLoadMoreSessions: () -> Unit,
    val onRefreshSessions: () -> Unit = {},
    val onToggleSessionExpanded: (String) -> Unit = {},
    val onSelectModel: (Int) -> Unit,
    val onManageModels: () -> Unit = {},
    val onOpenAIUsage: () -> Unit = {},
    val onRefreshAIUsage: () -> Unit = {},
    val onNavigateToSettings: () -> Unit = {},
    val onRenameSession: (String) -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    state: ChatTopBarState,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    val currentSession = state.sessions.find { it.id == state.currentSessionId }
    var showSessionSheet by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTodoDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }
    var showAIUsageSheet by remember { mutableStateOf(false) }

    LaunchedEffect(showSessionSheet) {
        if (showSessionSheet) actions.onRefreshSessions()
    }
    LaunchedEffect(showAIUsageSheet) {
        if (showAIUsageSheet) actions.onOpenAIUsage()
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val titleText = currentSession?.title
                ?: currentSession?.directory?.split("/")?.lastOrNull()
                ?: "OpenCode"
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.showSessionListInTopBar) {
                        IconButton(
                            onClick = { showSessionSheet = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.sessions_title),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    IconButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.sessions_rename_title),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (state.showNewSessionInTopBar) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = actions.onCreateSession,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.sessions_new),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        Surface(
                            onClick = { showModelMenu = true },
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = state.availableModels.getOrNull(state.selectedModelIndex)?.shortName ?: stringResource(R.string.chat_model_fallback),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.chat_switch_model),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false }
                        ) {
                            if (state.availableModels.isEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.sessions_no_models),
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    },
                                    onClick = { }
                                )
                            }
                            state.availableModels.forEachIndexed { index, model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                model.displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (index == state.selectedModelIndex)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                model.modelId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        actions.onSelectModel(index)
                                        showModelMenu = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.model_shortlist_manage)) },
                                onClick = {
                                    actions.onManageModels()
                                    showModelMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) }
                            )
                        }
                    }

                    if (state.aiUsageEnabled) {
                        val quota = state.selectedAIUsageQuota
                        val badgeText = if (quota == null) "-- @ 5h" else "${quota.clampedRemainingPercentage}% @ ${quota.label}"
                        Surface(
                            onClick = { showAIUsageSheet = true },
                            shape = RoundedCornerShape(50),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                            modifier = Modifier.testTag("ai_usage.badge")
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    quota == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    quota.clampedRemainingPercentage <= 10 -> MaterialTheme.colorScheme.error
                                    quota.clampedRemainingPercentage <= 20 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                maxLines = 1
                            )
                        }
                    }

                    val todoList = state.sessionTodos
                    val todoBadge = if (todoList.isNotEmpty()) {
                        "${todoList.count { it.isCompleted }}/${todoList.size}"
                    } else ""
                    Surface(
                        onClick = { showTodoDialog = true },
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Default.Checklist,
                                contentDescription = if (todoBadge.isEmpty()) stringResource(R.string.chat_todo) else "${stringResource(R.string.chat_todo)} $todoBadge",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (todoBadge.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    todoBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = { showContextDialog = true },
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent
                    ) {
                        ContextUsageRing(usage = state.contextUsage)
                    }

                    if (state.showSettingsButton) {
                        IconButton(
                            onClick = actions.onNavigateToSettings,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.nav_settings),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

    if (showSessionSheet) {
        ModalBottomSheet(onDismissRequest = { showSessionSheet = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChatUiTuning.sessionSheetHeight)
            ) {
                SessionList(
                    sessions = state.sessions,
                    currentSessionId = state.currentSessionId,
                    sessionStatuses = state.sessionStatuses,
                    attentionSessionIds = state.attentionSessionIds,
                    hasMoreSessions = state.hasMoreSessions,
                    isLoadingMoreSessions = state.isLoadingMoreSessions,
                    isRefreshingSessions = state.isRefreshingSessions,
                    expandedSessionIds = state.expandedSessionIds,
                    onSelectSession = {
                        actions.onSelectSession(it)
                        showSessionSheet = false
                    },
                    onCreateSession = {
                        actions.onCreateSession()
                        showSessionSheet = false
                    },
                    onDeleteSession = {
                        actions.onDeleteSession(it)
                        showSessionSheet = false
                    },
                    onArchiveSession = actions.onArchiveSession,
                    onRestoreSession = actions.onRestoreSession,
                    onLoadMoreSessions = actions.onLoadMoreSessions,
                    onRefreshSessions = actions.onRefreshSessions,
                    onToggleSessionExpanded = actions.onToggleSessionExpanded,
                    onOpenSettings = null
                )
            }
        }
    }

    if (showAIUsageSheet) {
        ModalBottomSheet(onDismissRequest = { showAIUsageSheet = false }) {
            AIUsageSheet(
                snapshot = state.aiUsageQuotaSnapshot,
                isLoading = state.isLoadingAIUsage,
                isRefreshing = state.isRefreshingAIUsage,
                error = state.aiUsageError,
                dashboardUrl = state.aiUsageDashboardUrl,
                onRefresh = actions.onRefreshAIUsage
            )
        }
    }

    if (showRenameDialog) {
        var renameText by remember(currentSession?.id) {
            mutableStateOf(
                currentSession?.title
                    ?: currentSession?.directory?.split("/")?.lastOrNull()
                    ?: ""
            )
        }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.sessions_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.sessions_title_label)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            actions.onRenameSession(renameText.trim())
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.sessions_rename_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showTodoDialog) {
        AlertDialog(
            onDismissRequest = { showTodoDialog = false },
            title = { Text(stringResource(R.string.chat_todo)) },
            text = {
                TodoListPanel(
                    todos = state.sessionTodos,
                    modifier = Modifier.heightIn(max = 400.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showTodoDialog = false }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        )
    }

    if (showContextDialog) {
        ContextUsageDialog(
            usage = state.contextUsage,
            onDismiss = { showContextDialog = false }
        )
    }
}

@Composable
private fun ContextUsageDialog(
    usage: AppState.ContextUsage?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_context)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (usage == null) {
                    Text(
                        stringResource(R.string.chat_no_usage_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ContextUsageSection(stringResource(R.string.chat_context_model_section)) {
                        ContextUsageRow(stringResource(R.string.chat_context_provider), usage.providerId ?: stringResource(R.string.chat_context_unknown))
                        ContextUsageRow(stringResource(R.string.chat_context_model), usage.modelId ?: stringResource(R.string.chat_context_unknown))
                        ContextUsageRow(stringResource(R.string.chat_context_limit), formatCount(usage.contextLimit))
                    }
                    ContextUsageSection(stringResource(R.string.chat_context_tokens)) {
                        ContextUsageRow(stringResource(R.string.chat_context_total), formatCount(usage.totalTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_input), formatOptionalCount(usage.inputTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_output), formatOptionalCount(usage.outputTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_reasoning), formatOptionalCount(usage.reasoningTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_cached_read), formatOptionalCount(usage.cachedReadTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_cached_write), formatOptionalCount(usage.cachedWriteTokens))
                    }
                    ContextUsageSection(stringResource(R.string.chat_context_cost)) {
                        ContextUsageRow(stringResource(R.string.chat_context_cost), usage.cost?.let { "$" + String.format(Locale.US, "%.4f", it) } ?: stringResource(R.string.chat_context_no_cost))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        }
    )
}

@Composable
private fun ContextUsageSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun ContextUsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)

private fun formatOptionalCount(value: Int?): String = value?.let(::formatCount) ?: "-"

@Composable
internal fun ContextUsageRing(usage: AppState.ContextUsage?) {
    val ringColor = when {
        usage == null -> MaterialTheme.colorScheme.onSurfaceVariant
        usage.percentage >= 0.9f -> MaterialTheme.colorScheme.error
        usage.percentage >= 0.7f -> BrandGold
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (usage == null) 0.55f else 0.25f
    )

    Box(
        modifier = Modifier.size(ChatUiTuning.contextRingOuterSize),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(ChatUiTuning.contextRingInnerSize),
            color = trackColor,
            strokeWidth = 3.dp
        )
        if (usage != null) {
            CircularProgressIndicator(
                progress = { usage.percentage },
                modifier = Modifier.size(ChatUiTuning.contextRingInnerSize),
                color = ringColor,
                strokeWidth = 3.dp
            )
        }
    }
}

@Composable
internal fun ChatEmptyState(
    isConnected: Boolean,
    onConnect: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (isConnected) stringResource(R.string.chat_select_or_create_session) else stringResource(R.string.chat_connect_to_server),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (!isConnected) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.chat_connect))
                }
            }
        }
    }
}
