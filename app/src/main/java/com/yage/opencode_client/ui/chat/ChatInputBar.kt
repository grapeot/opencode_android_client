package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.PermissionResponse
import com.yage.opencode_client.ui.theme.StopRed

@Composable
internal fun ChatInputBar(
    text: String,
    isBusy: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    isSpeechConfigured: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onToggleRecording: () -> Unit
) {
    val canSend = text.isNotBlank() && !isTranscribing

    // Matches iOS exactly: one horizontal row, bottom-aligned, sitting on a
    // single rounded composer background. Mic on the far left, the text field in
    // the middle (weight 1f, no background of its own — it shares the composer's
    // fill), and the send/stop column on the far right. The whole thing is one
    // pill; the icons live inside it alongside the text, just like iOS.
    Surface(
        modifier = Modifier.fillMaxWidth().imePadding(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Mic — far left, borderless, bottom-aligned.
            IconButton(
                onClick = onToggleRecording,
                enabled = !isTranscribing,
                modifier = Modifier.size(40.dp)
            ) {
                if (isTranscribing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Speech",
                        tint = when {
                            isRecording -> StopRed
                            isSpeechConfigured -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        }
                    )
                }
            }

            // Text field — the middle, ~3 lines tall at rest, growing to ~6.
            // TopStart so text begins at the top of the multi-line box.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        "Type a message...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        // ~3 lines tall at rest (3 × ~22dp line height), growing
                        // up to ~6 lines before it starts scrolling internally.
                        .fillMaxWidth()
                        .heightIn(min = 66.dp, max = 132.dp),
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6
                )
            }

            // Send/stop — far right, bottom-aligned. Send on top, stop below.
            ChatInputActions(
                isBusy = isBusy,
                canSend = canSend,
                onAbort = onAbort,
                onSend = onSend
            )
        }
    }
}

@Composable
private fun ChatInputActions(
    isBusy: Boolean,
    canSend: Boolean,
    onAbort: () -> Unit,
    onSend: () -> Unit
) {
    // Matches iOS exactly: Send is ALWAYS present on top — you can fire a new
    // message WHILE the assistant is working, no abort-first required. Stop is an
    // ADDITIONAL control that appears only while busy and stacks BELOW send (a
    // vertical column, never side-by-side). Send blue, stop red.
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // SEND: always present, solid electric-blue rounded-square, on top
        ChatPrimaryActionButton(
            onClick = onSend,
            enabled = canSend,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            dimWhenDisabled = true,
            icon = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send"
        )
        // STOP: only when busy, solid red rounded-square stacked below send
        if (isBusy) {
            ChatPrimaryActionButton(
                onClick = onAbort,
                enabled = true,
                containerColor = StopRed,
                contentColor = Color.White,
                dimWhenDisabled = false,
                icon = Icons.Default.Stop,
                contentDescription = "Stop"
            )
        }
    }
}

@Composable
private fun ChatPrimaryActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    dimWhenDisabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String
) {
    val effectiveAlpha = if (!enabled && dimWhenDisabled) 0.35f else 1f
    // A single solid rounded square that IS the button — no nested IconButton,
    // whose 48dp touch target used to overflow this box and overlap the field.
    // The contentDescription + clickable/enabled semantics live on the SAME node
    // (the Box) so tests can find it by description and assert its enabled state.
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor.copy(alpha = effectiveAlpha))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun ChatPermissionCard(
    permission: PermissionRequest,
    onRespond: (PermissionResponse) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // 3pt electric-blue left bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    permission.permission ?: "Unknown permission",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                permission.metadata?.filepath?.let {
                    SelectionContainer {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onRespond(PermissionResponse.REJECT) }) {
                        Text("Reject", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRespond(PermissionResponse.ONCE) }) {
                        Text("Allow Once", color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRespond(PermissionResponse.ALWAYS) }) {
                        Text("Always Allow", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
