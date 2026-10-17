package com.yage.opencode_client.ambient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.ambient.protocol.AmbientActivity
import com.yage.opencode_client.ambient.protocol.AmbientBackendUsage
import com.yage.opencode_client.ambient.protocol.AmbientCaption
import com.yage.opencode_client.ambient.protocol.AmbientMeter
import com.yage.opencode_client.ambient.protocol.AmbientPhase
import com.yage.opencode_client.ui.theme.StopRed
import java.math.BigDecimal
import kotlin.math.roundToInt

internal val AmbientControlHeight = AmbientMeter.CONTROL_ROW_DP.dp

internal fun formatPlain(value: Double): String {
    if (!value.isFinite()) return ""
    val raw = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    return if (raw == "0.0" || raw == "-0" || raw == "-0.0") "0" else raw
}

internal fun levelText(raw: Double, unconfirmed: String): String {
    if (!raw.isFinite() || raw < 0.0) return unconfirmed
    return formatPlain(raw)
}

internal fun voiceDurationText(
    seconds: Double,
    hasSnapshot: Boolean,
    unconfirmedFlag: Boolean,
    unconfirmed: String,
): String {
    if (!hasSnapshot || unconfirmedFlag || !seconds.isFinite() || seconds < 0.0) return unconfirmed
    return formatPlain(seconds)
}

internal fun elapsedText(seconds: Long): String = seconds.coerceAtLeast(0L).toString()

internal fun localEstimateText(
    local: AmbientBackendUsage,
    usageUnconfirmed: Boolean,
    unconfirmed: String,
): String {
    val amount = local.estimatedUsd
    val finite = amount.isFinite() && amount >= 0.0
    if (finite && local.unpricedCount == 0 && !usageUnconfirmed) return formatPlain(amount)
    val hasSignal = finite && (
        amount > 0.0 ||
            local.inputTokens > 0 ||
            local.outputTokens > 0 ||
            local.cachedRead > 0 ||
            local.cacheWrite > 0
        )
    if (hasSignal) return formatPlain(amount) + " · " + unconfirmed
    return unconfirmed
}

internal fun portalValueText(
    usd: Double?,
    settled: Boolean,
    usageUnconfirmed: Boolean,
    unconfirmed: String,
): String {
    if (usd == null || !usd.isFinite() || usd < 0.0) return unconfirmed
    val amount = formatPlain(usd)
    if (!settled || usageUnconfirmed) return amount + " · " + unconfirmed
    return amount
}

@Composable
internal fun ambientPhaseLabel(phase: AmbientPhase): String = when (phase) {
    AmbientPhase.IDLE -> stringResource(R.string.settings_disconnected)
    AmbientPhase.CONNECTING -> stringResource(R.string.ambient_connecting)
    AmbientPhase.LISTENING -> stringResource(R.string.ambient_listening)
    AmbientPhase.SPEAKING -> stringResource(R.string.ambient_speaking)
    AmbientPhase.CLOSING -> stringResource(R.string.ambient_ending)
    AmbientPhase.FAILED -> stringResource(R.string.settings_connection_failed)
}

@Composable
internal fun ambientConnectLabel(phase: AmbientPhase): String = when (phase) {
    AmbientPhase.FAILED -> stringResource(R.string.ambient_reconnect)
    AmbientPhase.CONNECTING -> stringResource(R.string.ambient_connecting)
    else -> stringResource(R.string.ambient_start)
}

@Composable
internal fun toolStatusLabel(status: String): String {
    val key = status.trim().lowercase()
    val mapped = when (key) {
        "running" -> R.string.ambient_tool_status_running
        "sent" -> R.string.ambient_tool_status_sent
        "dropped" -> R.string.ambient_tool_status_dropped
        "pending" -> R.string.ambient_tool_status_pending
        "failed", "error", "failure" -> R.string.task_notification_failed
        else -> null
    }
    if (mapped != null) return stringResource(mapped)
    if (key.isEmpty()) return stringResource(R.string.ambient_tool_status)
    val compact = status.trim()
    if (compact.length <= 24 && '{' !in compact && '"' !in compact && '\n' !in compact) return compact
    return stringResource(R.string.ambient_tool_status)
}

@Composable
internal fun AmbientCaptionBubble(caption: AmbientCaption) {
    val assistant = caption.role.equals("assistant", ignoreCase = true)
    val bubble = if (assistant) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = caption.text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .align(if (assistant) Alignment.CenterEnd else Alignment.CenterStart)
                .background(bubble, MaterialTheme.shapes.medium)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(AmbientTags.caption(caption.id)),
        )
    }
}

@Composable
internal fun AmbientToolCard(
    activity: AmbientActivity,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val name = activity.name.trim().ifEmpty { stringResource(R.string.ambient_tool) }
    val collapsed = name + " · " + toolStatusLabel(activity.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AmbientMeter.MIN_TOUCH_DP.dp)
                .testTag(AmbientTags.tool(activity.id))
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics {
                    if (expanded) {
                        collapse { onToggle(); true }
                    } else {
                        expand { onToggle(); true }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = collapsed,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (expanded) {
            ToolBody(activity)
        }
    }
}

@Composable
private fun ToolBody(activity: AmbientActivity) {
    val resultScroll = rememberScrollState()
    LaunchedEffect(activity.result) {
        withFrameNanos { }
        resultScroll.scrollTo(resultScroll.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.ambient_tool_arguments),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = activity.arguments, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = stringResource(R.string.ambient_tool_results),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(resultScroll)
                .testTag(AmbientTags.toolResult(activity.id)),
        ) {
            Text(text = activity.result, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun AmbientDetails(
    snapshot: com.yage.opencode_client.ambient.protocol.AmbientSnapshot,
    bodyMaxHeight: Dp,
) {
    var open by remember { mutableStateOf(false) }
    val unconfirmed = stringResource(R.string.ambient_usage_unconfirmed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AmbientMeter.MIN_TOUCH_DP.dp)
                .testTag(AmbientTags.DETAILS)
                .clickable(role = Role.Button) { open = !open }
                .semantics {
                    if (open) {
                        collapse { open = false; true }
                    } else {
                        expand { open = true; true }
                    }
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.ambient_details),
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(
                imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open && bodyMaxHeight > 0.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .testTag(AmbientTags.DETAILS_BODY),
            ) {
                DetailRow(
                    label = stringResource(R.string.ambient_input_level),
                    value = levelText(snapshot.inputRms, unconfirmed),
                    tag = AmbientTags.INPUT_LEVEL,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_output_level),
                    value = levelText(snapshot.outputRms, unconfirmed),
                    tag = AmbientTags.OUTPUT_LEVEL,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_voice_duration),
                    value = voiceDurationText(
                        seconds = snapshot.voiceSeconds,
                        hasSnapshot = snapshot.voiceHasSnapshot,
                        unconfirmedFlag = snapshot.voiceUnconfirmed,
                        unconfirmed = unconfirmed,
                    ),
                    tag = AmbientTags.VOICE_DURATION,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_session_duration),
                    value = elapsedText(snapshot.elapsedSeconds),
                    tag = AmbientTags.SESSION_DURATION,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_input_usage),
                    value = snapshot.local.inputTokens.toString(),
                    tag = AmbientTags.INPUT_TOKENS,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_output_usage),
                    value = snapshot.local.outputTokens.toString(),
                    tag = AmbientTags.OUTPUT_TOKENS,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_cache_read),
                    value = snapshot.local.cachedRead.toString(),
                    tag = AmbientTags.CACHE_READ,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_cache_write),
                    value = snapshot.local.cacheWrite.toString(),
                    tag = AmbientTags.CACHE_WRITE,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_local_estimate_note),
                    value = localEstimateText(snapshot.local, snapshot.usageUnconfirmed, unconfirmed),
                    tag = AmbientTags.LOCAL_ESTIMATE,
                )
                DetailRow(
                    label = stringResource(R.string.ambient_portal_estimate),
                    value = portalValueText(
                        usd = snapshot.portalUsd,
                        settled = snapshot.portalSettled,
                        usageUnconfirmed = snapshot.usageUnconfirmed,
                        unconfirmed = unconfirmed,
                    ),
                    tag = AmbientTags.PORTAL_ESTIMATE,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f).testTag(tag),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun AmbientVolumeSlider(
    volume: AmbientOutputVolume,
    onChange: (Int) -> Unit,
) {
    val max = volume.max.coerceAtLeast(0)
    var drag by remember { mutableStateOf<Int?>(null) }
    val external = volume.value.coerceIn(0, max)
    val shown = (drag ?: external).coerceIn(0, max)
    val volumeName = stringResource(R.string.ambient_output_volume)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Slider(
            value = if (max == 0) 0f else shown.toFloat(),
            onValueChange = { raw ->
                if (max <= 0) return@Slider
                val next = raw.roundToInt().coerceIn(0, max)
                drag = next
                if (next != shown) onChange(next)
            },
            onValueChangeFinished = { drag = null },
            valueRange = 0f..(if (max == 0) 1f else max.toFloat()),
            steps = if (max <= 1) 0 else max - 1,
            enabled = max > 0,
            modifier = Modifier
                .weight(1f)
                .testTag(AmbientTags.VOLUME)
                .semantics { contentDescription = volumeName },
        )
    }
}

@Composable
internal fun AmbientControlSlot(
    phase: AmbientPhase,
    muted: Boolean,
    inputRms: Double,
    onConnect: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onInterruptPlayback: () -> Unit,
    onEnd: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().testTag(AmbientTags.CONTROLS)) {
        val live = phase == AmbientPhase.LISTENING ||
            phase == AmbientPhase.SPEAKING ||
            phase == AmbientPhase.CLOSING
        when {
            live -> {
                val enabled = phase == AmbientPhase.LISTENING || phase == AmbientPhase.SPEAKING
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuteControl(
                        phase = phase,
                        muted = muted,
                        inputRms = inputRms,
                        enabled = enabled,
                        onClick = { onSetMuted(!muted) },
                    )
                    ControlButton(
                        tag = AmbientTags.STOP,
                        icon = Icons.Filled.Stop,
                        label = stringResource(R.string.ambient_stop_playback),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurface,
                        enabled = enabled,
                        marked = false,
                        onClick = onInterruptPlayback,
                    )
                    ControlButton(
                        tag = AmbientTags.END,
                        icon = Icons.Filled.CallEnd,
                        label = stringResource(R.string.ambient_end),
                        container = StopRed,
                        content = Color.White,
                        enabled = enabled,
                        marked = false,
                        onClick = onEnd,
                    )
                }
            }
            phase == AmbientPhase.CONNECTING -> {
                Button(
                    onClick = onEnd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AmbientControlHeight)
                        .testTag(AmbientTags.END),
                    shape = RoundedCornerShape(16.dp),
                    elevation = flatElevation(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StopRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.ambient_end),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            else -> {
                Button(
                    onClick = onConnect,
                    enabled = phase == AmbientPhase.IDLE || phase == AmbientPhase.FAILED,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AmbientControlHeight)
                        .testTag(AmbientTags.CONNECT),
                    shape = RoundedCornerShape(16.dp),
                    elevation = flatElevation(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = ambientConnectLabel(phase),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MuteControl(
    phase: AmbientPhase,
    muted: Boolean,
    inputRms: Double,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(if (muted) R.string.ambient_unmute else R.string.ambient_mute)
    val fill = rememberAmbientCapsuleFill(inputLevel = inputRms, phase = phase, muted = muted)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(AmbientControlHeight)
            .testTag(AmbientTags.MUTE)
            .semantics {
                contentDescription = label
                selected = muted
            },
        shape = RoundedCornerShape(16.dp),
        elevation = flatElevation(),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (muted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = if (muted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
            disabledContentColor = if (muted) {
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            },
        ),
    ) {
        AmbientMicGlyph(
            fill = fill,
            showSlash = AmbientMeter.showsSlash(muted),
            modifier = Modifier
                .size(AmbientMeter.GLYPH_DP.dp)
                .testTag(AmbientTags.MIC),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ControlButton(
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(AmbientControlHeight)
            .testTag(tag)
            .semantics { selected = marked },
        shape = RoundedCornerShape(16.dp),
        elevation = flatElevation(),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.45f),
            disabledContentColor = content.copy(alpha = 0.7f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun flatElevation() = ButtonDefaults.buttonElevation(
    defaultElevation = 0.dp,
    pressedElevation = 0.dp,
    focusedElevation = 0.dp,
    hoveredElevation = 0.dp,
    disabledElevation = 0.dp,
)
