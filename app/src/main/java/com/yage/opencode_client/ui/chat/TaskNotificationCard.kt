package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.AddedFile
import com.yage.opencode_client.ui.theme.StopRed

@Composable
internal fun TaskNotificationCard(
    notification: TaskNotification,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onMarkdownLinkClick: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 默认折叠：结论在随后 assistant 消息里，卡片正文是参考材料，用户 tap 展开。
    var expanded by remember(notification.sessionID) {
        mutableStateOf(false)
    }
    val statusColor = when (notification.state) {
        TaskState.COMPLETED -> AddedFile
        TaskState.ERROR -> StopRed
        TaskState.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (notification.state) {
        TaskState.COMPLETED -> Icons.Filled.Verified
        TaskState.ERROR -> Icons.Filled.Cancel
        TaskState.RUNNING -> Icons.Filled.Schedule
    }
    val statusLabel = when (notification.state) {
        TaskState.COMPLETED -> stringResource(R.string.task_notification_completed)
        TaskState.ERROR -> stringResource(R.string.task_notification_failed)
        TaskState.RUNNING -> null
    }
    val title = TaskNotificationParser.displayTitle(notification)

    // 与 ReasoningCard 同例：clickable 只放头部行，不把 SelectionContainer 和跳转按钮包进折叠手势。
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .padding(vertical = 4.dp)
            .testTag("task-notification-card")
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (statusLabel != null) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.size(8.dp))
                TaskNotificationBody(
                    notification = notification,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory,
                    onMarkdownLinkClick = onMarkdownLinkClick
                )
                TextButton(
                    onClick = { onOpenSession(notification.sessionID) },
                    modifier = Modifier.testTag("task-notification-open-session")
                ) {
                    Text(
                        text = stringResource(R.string.task_notification_open_session),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskNotificationBody(
    notification: TaskNotification,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onMarkdownLinkClick: (String) -> Unit
) {
    val result = notification.resultText
    if (result.isBlank()) {
        Text(
            text = stringResource(R.string.task_notification_no_output),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val preview = TaskNotificationParser.largeMessagePreview(result)
    if (preview != null) {
        SelectionContainer {
            Column {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(
                        R.string.chat_large_message_preview_notice,
                        preview.length,
                        result.length
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    ResolvedMarkdownText(
        text = result,
        repository = repository,
        workspaceDirectory = workspaceDirectory,
        onMarkdownLinkClick = onMarkdownLinkClick
    )
}
