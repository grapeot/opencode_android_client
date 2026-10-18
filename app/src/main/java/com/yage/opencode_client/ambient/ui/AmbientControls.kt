package com.yage.opencode_client.ambient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import java.math.RoundingMode
import kotlin.math.floor

internal val AmbientControlHeight = AmbientMeter.CONTROL_ROW_DP.dp

internal fun formatPlain(value: Double): String {
    if (!value.isFinite()) return ""
    return BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString()
}

internal fun levelText(raw: Double, unconfirmed: String): String {
    if (!raw.isFinite() || raw < 0.0) return unconfirmed
    return formatPlain(raw)
}

/** Values at or above 2^63 do not fit in a signed second count. */
private const val MAX_CLOCK_SECONDS = 9223372036854775808.0

internal fun wholeSecondsOrNull(seconds: Double): Long? {
    if (!seconds.isFinite() || seconds < 0.0 || seconds >= MAX_CLOCK_SECONDS) return null
    val whole = floor(seconds)
    if (!whole.isFinite() || whole >= MAX_CLOCK_SECONDS) return null
    return whole.toLong()
}

internal fun formatHms(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return hours.toString().padStart(2, '0') +
        ":" + minutes.toString().padStart(2, '0') +
        ":" + secs.toString().padStart(2, '0')
}

internal fun voiceDurationText(
    seconds: Double,
    hasSnapshot: Boolean,
    unconfirmedFlag: Boolean,
    unconfirmed: String,
): String {
    if (!hasSnapshot || unconfirmedFlag) return unconfirmed
    val whole = wholeSecondsOrNull(seconds) ?: return unconfirmed
    return formatHms(whole)
}

internal fun elapsedText(seconds: Long, unconfirmed: String): String {
    if (seconds < 0L) return unconfirmed
    return formatHms(seconds)
}

internal fun formatTokenCount(value: Long): String {
    if (value < 1_000L) return value.toString()
    val divisors = longArrayOf(1_000L, 1_000_000L, 1_000_000_000L)
    val suffixes = arrayOf("K", "M", "B")
    var index = 0
    for (i in divisors.indices) {
        if (value >= divisors[i]) index = i
    }
    while (true) {
        val scaled = BigDecimal.valueOf(value).divide(
            BigDecimal.valueOf(divisors[index]),
            2,
            RoundingMode.HALF_UP,
        )
        if (scaled >= BigDecimal.valueOf(1_000L) && index < divisors.lastIndex) {
            index += 1
            continue
        }
        return scaled.stripTrailingZeros().toPlainString() + suffixes[index]
    }
}

internal fun tokenExactText(value: Long): String = value.toString()

internal fun formatUsdAmount(amount: Double): String? {
    if (!amount.isFinite() || amount < 0.0) return null
    if (amount == 0.0) return "\$0.00"
    if (amount < 0.01) return "<\$0.01"
    val plain = BigDecimal(amount.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString()
    return "\$" + plain
}

internal fun localEstimateText(
    local: AmbientBackendUsage,
    usageUnconfirmed: Boolean,
    unconfirmed: String,
): String {
    val shown = formatUsdAmount(local.estimatedUsd) ?: return unconfirmed
    val confirmed = local.unpricedCount == 0 && !usageUnconfirmed
    if (confirmed) return shown
    return shown + " · " + unconfirmed
}

internal fun portalValueText(
    usd: Double?,
    settled: Boolean,
    usageUnconfirmed: Boolean,
    unconfirmed: String,
): String {
    if (usd == null) return unconfirmed
    val shown = formatUsdAmount(usd) ?: return unconfirmed
    if (!settled || usageUnconfirmed) return shown + " · " + unconfirmed
    return shown
}

/**
 * Column count from the Details container width, in dp, and the current font scale.
 * Large text and extremely narrow panes use one column. Wider panes use up to four.
 */
internal fun detailColumnCount(widthDp: Float, fontScale: Float): Int {
    if (!widthDp.isFinite() || widthDp < 220f) return 1
    if (!fontScale.isFinite() || fontScale >= 1.3f) return 1
    if (widthDp < 600f) return 2
    if (widthDp < 900f) return 3
    return 4
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
            val voice = voiceDurationText(
                seconds = snapshot.voiceSeconds,
                hasSnapshot = snapshot.voiceHasSnapshot,
                unconfirmedFlag = snapshot.voiceUnconfirmed,
                unconfirmed = unconfirmed,
            )
            val session = elapsedText(snapshot.elapsedSeconds, unconfirmed)
            val ordinary = snapshot.local.inputTokens.toLong()
            val output = snapshot.local.outputTokens.toLong()
            val cacheRead = snapshot.local.cachedRead.toLong()
            val cacheWrite = snapshot.local.cacheWrite.toLong()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = detailColumnCount(maxWidth.value, LocalDensity.current.fontScale)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = bodyMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .testTag(AmbientTags.DETAILS_BODY),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailMetricGrid(
                        columns = columns,
                        metrics = listOf(
                            DetailMetric(
                                label = stringResource(R.string.ambient_voice_duration),
                                value = voice,
                                tag = AmbientTags.VOICE_DURATION,
                            ),
                            DetailMetric(
                                label = stringResource(R.string.ambient_session_duration),
                                value = session,
                                tag = AmbientTags.SESSION_DURATION,
                            ),
                            DetailMetric(
                                label = stringResource(R.string.ambient_input_usage),
                                value = formatTokenCount(ordinary),
                                tag = AmbientTags.INPUT_TOKENS,
                                exact = tokenExactText(ordinary),
                            ),
                            DetailMetric(
                                label = stringResource(R.string.ambient_output_usage),
                                value = formatTokenCount(output),
                                tag = AmbientTags.OUTPUT_TOKENS,
                                exact = tokenExactText(output),
                            ),
                            DetailMetric(
                                label = stringResource(R.string.ambient_cache_read),
                                value = formatTokenCount(cacheRead),
                                tag = AmbientTags.CACHE_READ,
                                exact = tokenExactText(cacheRead),
                            ),
                            DetailMetric(
                                label = stringResource(R.string.ambient_cache_write),
                                value = formatTokenCount(cacheWrite),
                                tag = AmbientTags.CACHE_WRITE,
                                exact = tokenExactText(cacheWrite),
                            ),
                            DetailMetric(
                                label = stringResource(R.string.ambient_input_level),
                                value = levelText(snapshot.inputRms, unconfirmed),
                                tag = AmbientTags.INPUT_LEVEL,
                            ),
                            DetailMetric(
                                label = stringResource(R.string.ambient_output_level),
                                value = levelText(snapshot.outputRms, unconfirmed),
                                tag = AmbientTags.OUTPUT_LEVEL,
                            ),
                        ),
                    )
                    Text(
                        text = stringResource(R.string.ambient_cost_heading),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AmbientTags.COST_HEADING),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    DetailMetricGrid(
                        columns = columns,
                        metrics = listOf(
                            DetailMetric(
                                label = stringResource(R.string.ambient_local_estimate_note),
                                value = localEstimateText(snapshot.local, snapshot.usageUnconfirmed, unconfirmed),
                                tag = AmbientTags.LOCAL_ESTIMATE,
                            ),
                            DetailMetric(
                                label = stringResource(R.string.ambient_portal_estimate),
                                value = portalValueText(
                                    usd = snapshot.portalUsd,
                                    settled = snapshot.portalSettled,
                                    usageUnconfirmed = snapshot.usageUnconfirmed,
                                    unconfirmed = unconfirmed,
                                ),
                                tag = AmbientTags.PORTAL_ESTIMATE,
                            ),
                        ),
                    )
                    Text(
                        text = stringResource(R.string.ambient_cost_explanation),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(AmbientTags.COST_NOTE),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class DetailMetric(
    val label: String,
    val value: String,
    val tag: String,
    val exact: String? = null,
)

@Composable
private fun DetailMetricGrid(columns: Int, metrics: List<DetailMetric>) {
    val count = columns.coerceIn(1, 4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(count).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowItems.forEach { metric ->
                    DetailMetricCell(metric, Modifier.weight(1f))
                }
                repeat(count - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DetailMetricCell(metric: DetailMetric, modifier: Modifier) {
    Column(modifier) {
        Text(
            text = metric.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = metric.value,
            modifier = Modifier
                .testTag(metric.tag)
                .semantics {
                    val exact = metric.exact
                    if (exact != null) contentDescription = exact
                },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
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
