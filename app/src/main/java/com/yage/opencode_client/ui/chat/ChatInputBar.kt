package com.yage.opencode_client.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.PermissionResponse
import com.yage.opencode_client.ui.theme.StopRed
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun ChatInputBar(
    text: String,
    isBusy: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    hasPreservedSpeechAudio: Boolean,
    isRetryingSpeech: Boolean,
    speechAudioLevel: Float,
    isSpeechConfigured: Boolean,
    agentActivityText: String?,
    agentStartedAtMillis: Long?,
    imageAttachments: List<ComposerImageAttachment>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAddImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onAbort: () -> Unit,
    onAbortSpeech: () -> Unit,
    onRetrySpeech: () -> Unit,
    onDiscardSpeech: () -> Unit,
    onToggleRecording: () -> Unit
) {
    val canSend = (text.isNotBlank() || imageAttachments.isNotEmpty()) &&
        !isRecording && !isTranscribing && !isRetryingSpeech
    val voiceStatus = when {
        isRecording -> stringResource(R.string.chat_listening)
        isTranscribing -> stringResource(R.string.chat_transcribing)
        isRetryingSpeech -> stringResource(R.string.chat_retry_segment)
        hasPreservedSpeechAudio -> stringResource(R.string.chat_preserved_audio)
        else -> null
    }
    val composerStatus = listOfNotNull(
        if (isBusy) agentActivityText ?: stringResource(R.string.chat_agent_running) else null,
        voiceStatus
    ).joinToString(" · ").takeIf { it.isNotEmpty() }

    Surface(
        modifier = Modifier.fillMaxWidth().imePadding(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (composerStatus != null) {
                QuietComposerStatus(
                    status = composerStatus,
                    isBusy = isBusy,
                    startedAtMillis = if (isBusy) agentStartedAtMillis else null,
                    onAbort = onAbort,
                )
            }

            VoiceRail(
                isRecording = isRecording,
                isTranscribing = isTranscribing,
                isRetryingSpeech = isRetryingSpeech,
                hasPreservedSpeechAudio = hasPreservedSpeechAudio,
                audioLevel = speechAudioLevel,
                isSpeechConfigured = isSpeechConfigured,
                onToggleRecording = onToggleRecording,
                onAbortSpeech = onAbortSpeech,
                onRetrySpeech = onRetrySpeech,
                onDiscardSpeech = onDiscardSpeech,
            )

            if (imageAttachments.isNotEmpty()) {
                ImageAttachmentStrip(
                    attachments = imageAttachments,
                    onRemoveImage = onRemoveImage,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                ChatPrimaryActionButton(
                    onClick = onAddImages,
                    enabled = imageAttachments.size < 4 && !isTranscribing && !isRetryingSpeech,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    dimWhenDisabled = true,
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.chat_add_image)
                )
                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            if (isRecording) stringResource(R.string.chat_transcription_placeholder) else stringResource(R.string.chat_type_message),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 66.dp, max = 132.dp),
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6
                    )
                }

                ChatPrimaryActionButton(
                    onClick = onSend,
                    enabled = canSend,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    dimWhenDisabled = true,
                    icon = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send)
                )
            }

        }
    }
}

@Composable
private fun ImageAttachmentStrip(
    attachments: List<ComposerImageAttachment>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            val bitmap = remember(attachment.id, attachment.thumbnailData) {
                BitmapFactory.decodeByteArray(attachment.thumbnailData, 0, attachment.thumbnailData.size)?.asImageBitmap()
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                IconButton(
                    onClick = { onRemoveImage(attachment.id) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.chat_remove_image),
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceRail(
    isRecording: Boolean,
    isTranscribing: Boolean,
    isRetryingSpeech: Boolean,
    hasPreservedSpeechAudio: Boolean,
    audioLevel: Float,
    isSpeechConfigured: Boolean,
    onToggleRecording: () -> Unit,
    onAbortSpeech: () -> Unit,
    onRetrySpeech: () -> Unit,
    onDiscardSpeech: () -> Unit,
) {
    val railTitle = when {
        isRecording -> stringResource(R.string.chat_listening)
        isTranscribing -> stringResource(R.string.chat_transcribing)
        isRetryingSpeech -> stringResource(R.string.chat_retry_segment)
        hasPreservedSpeechAudio -> stringResource(R.string.chat_preserved_audio)
        else -> stringResource(R.string.chat_tap_to_speak)
    }
    val mode = when {
        isRecording -> WaveformMode.Active
        isTranscribing || isRetryingSpeech -> WaveformMode.Generating
        else -> WaveformMode.Idle
    }
    val accent = MaterialTheme.colorScheme.primary
    val waveformDescription = stringResource(R.string.chat_speech_waveform)
    val railColor = if (mode == WaveformMode.Idle) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    } else {
        accent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VoiceTransportButton(
            onClick = if (hasPreservedSpeechAudio) onRetrySpeech else onToggleRecording,
            enabled = !isTranscribing && !isRetryingSpeech,
            containerColor = if (isRecording) StopRed else accent,
            icon = when {
                isRecording -> Icons.Default.Stop
                hasPreservedSpeechAudio -> Icons.Default.Refresh
                else -> Icons.Default.Mic
            },
            contentDescription = if (hasPreservedSpeechAudio) stringResource(R.string.chat_retry_segment) else railTitle,
        )

        VoiceRailWaveform(
            mode = mode,
            color = railColor,
            level = speechLevelForMode(mode, audioLevel),
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .semantics { contentDescription = waveformDescription }
        )

        when {
            isTranscribing -> TextButton(onClick = onAbortSpeech) {
                Text(stringResource(R.string.chat_stop_transcription_wait), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            hasPreservedSpeechAudio -> TextButton(
                onClick = onDiscardSpeech,
                enabled = !isRetryingSpeech,
            ) {
                Text(stringResource(R.string.chat_discard_audio), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Text(
                text = if (isSpeechConfigured) railTitle else stringResource(R.string.chat_configure_speech),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun speechLevelForMode(mode: WaveformMode, audioLevel: Float): Float = when (mode) {
    WaveformMode.Active -> audioLevel
    WaveformMode.Generating -> 0.2f
    WaveformMode.Idle -> 0.04f
}

@Composable
private fun QuietComposerStatus(
    status: String,
    isBusy: Boolean,
    startedAtMillis: Long?,
    onAbort: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var nowMillis by remember(startedAtMillis) { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isBusy, startedAtMillis) {
        while (isBusy && startedAtMillis != null) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBusy) {
            Icon(
                Icons.Default.Circle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (isBusy && startedAtMillis != null) {
            Text(
                text = formatElapsed(nowMillis - startedAtMillis),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (isBusy) {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.chat_interrupt_agent),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_interrupt_agent)) },
                        leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null, tint = StopRed) },
                        onClick = {
                            menuExpanded = false
                            onAbort()
                        }
                    )
                }
            }
        }
    }
}

private fun formatElapsed(elapsedMillis: Long): String {
    val seconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private enum class WaveformMode { Idle, Active, Generating }

private const val WAVEFORM_TICK_MS = 66L

@Composable
private fun VoiceRailWaveform(
    mode: WaveformMode,
    color: Color,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0.04f) } } }
    var phase by remember { mutableStateOf(0f) }

    LaunchedEffect(mode, level) {
        while (mode != WaveformMode.Idle) {
            if (mode == WaveformMode.Active) {
                bars.removeAt(0)
                bars.add(level.coerceIn(0.04f, 1f))
            } else {
                phase += 0.22f
            }
            delay(WAVEFORM_TICK_MS)
        }
    }

    Canvas(modifier = modifier) {
        val gap = 4.dp.toPx()
        val barWidth = 3.dp.toPx()
        val barCount = minOf(
            bars.size,
            ((size.width + gap) / (barWidth + gap)).toInt().coerceAtLeast(1)
        )
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = ((size.width - totalWidth) / 2f).coerceAtLeast(0f)
        val centerY = size.height / 2f
        val firstBarIndex = bars.size - barCount

        repeat(barCount) { index ->
            val raw = when (mode) {
                WaveformMode.Active -> bars[firstBarIndex + index]
                WaveformMode.Generating -> 0.15f + 0.55f * ((sin(index * 0.45f + phase) + 1f) / 2f)
                WaveformMode.Idle -> 0.08f + 0.05f * ((sin(index * PI.toFloat() / 5f) + 1f) / 2f)
            }
            val height = (2.dp.toPx() + raw.coerceIn(0f, 1f) * (size.height - 2.dp.toPx()))
                .coerceAtLeast(2.dp.toPx())
            val left = startX + index * (barWidth + gap)
            drawRoundRect(
                color = color.copy(alpha = if (mode == WaveformMode.Idle) 0.55f else 1f),
                topLeft = Offset(left, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun VoiceTransportButton(
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    icon: ImageVector,
    contentDescription: String,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(containerColor.copy(alpha = if (enabled) 1f else 0.35f))
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
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ChatPrimaryActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    dimWhenDisabled: Boolean,
    icon: ImageVector,
    contentDescription: String
) {
    val effectiveAlpha = if (!enabled && dimWhenDisabled) 0.35f else 1f
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
                        Text(stringResource(R.string.permission_reject), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRespond(PermissionResponse.ONCE) }) {
                        Text(stringResource(R.string.permission_allow_once), color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRespond(PermissionResponse.ALWAYS) }) {
                        Text(stringResource(R.string.permission_always_allow), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
