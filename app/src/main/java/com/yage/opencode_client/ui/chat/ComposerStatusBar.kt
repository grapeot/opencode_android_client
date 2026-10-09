package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.theme.StopRed
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

/** Compact token count mirroring the iOS status line: < 1000 as-is, then
 *  K/M/B/T with one decimal for values >= 10 and two below, trailing zeros
 *  trimmed ("950", "1K", "85.2K", "1.11M", "2B"). Rounding that overflows the
 *  unit carries up (999_999 -> "1M", 999_999_999_999 -> "1T"). */
internal fun compactTokenCount(value: Long): String {
    if (value < 1000) return value.toString()
    var divisor = when {
        value >= 1_000_000_000_000 -> 1_000_000_000_000L
        value >= 1_000_000_000 -> 1_000_000_000L
        value >= 1_000_000 -> 1_000_000L
        else -> 1_000L
    }
    var suffix = when (divisor) {
        1_000_000_000_000L -> "T"
        1_000_000_000L -> "B"
        1_000_000L -> "M"
        else -> "K"
    }
    var scaled = value / divisor.toDouble()
    var decimals = if (scaled >= 10) 1 else 2
    // Carry into the next unit when rounding overflows (999_999 -> 1M).
    val factor = if (decimals == 1) 10.0 else 100.0
    if (suffix != "T" && (scaled * factor).roundToLong() / factor >= 1000) {
        divisor *= 1000
        suffix = when (suffix) {
            "K" -> "M"
            "M" -> "B"
            else -> "T"
        }
        scaled = value / divisor.toDouble()
        decimals = 2
    }
    val text = String.format(Locale.US, "%.${decimals}f", scaled)
    return trimTrailingZeros(text) + suffix
}

private fun trimTrailingZeros(text: String): String {
    if ('.' !in text) return text
    return text.trimEnd('0').trimEnd('.')
}

@Composable
private fun StatusSeparator(style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    Text("·", style = style, color = color)
}

/** Zero-padded turn stopwatch for the persistent status line: `MM:SS` below
 *  an hour, `HH:MM:SS` at or above. It counts up while the agent works and
 *  holds at the completion instant once the turn stops (the caller freezes
 *  the end time). Negative input (clock skew) clamps to `00:00`. Hours are not
 *  capped or truncated, so a long-lived session reads `49:23:10` in full. */
internal fun formatTurnStopwatch(elapsedMillis: Long): String {
    val total = (elapsedMillis.coerceAtLeast(0L) / 1_000L).toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** End millis for the turn stopwatch: the frozen completion instant when the
 *  turn has stopped, otherwise `nowMillis` so it keeps counting while busy. */
internal fun turnStopwatchEnd(frozenEndMillis: Long?, nowMillis: Long): Long =
    frozenEndMillis ?: nowMillis

/** Frozen end for the current turn, or null while it is still running.
 *  Preference order: the turn's completion instant when the turn stopped;
 *  null (follow `now`) while it runs; and, if the session is idle yet the turn
 *  has no completion instant (aborted/errored before any assistant output),
 *  the turn's own start so the reading holds at 00:00 instead of ticking on. */
internal fun turnStopwatchFrozenEnd(
    completedTurnEndMillis: Long?,
    isRunning: Boolean,
    lastUserCreatedMillis: Long?,
): Long? = when {
    completedTurnEndMillis != null -> completedTurnEndMillis
    isRunning -> null
    else -> lastUserCreatedMillis
}

/** Single status bar shown directly above the composer. Left side: the
 *  persistent session counters (rounds / tool calls / total tokens / cache
 *  hit rate, segments with no data omitted) followed by the turn stopwatch.
 *  Right side: the transient agent activity (dot + activity + interrupt menu)
 *  and voice status, joined with middots exactly as the old in-composer row
 *  did. The row is not composed when there is nothing to show.
 *
 *  The stopwatch is visible persistently once a session has a user message:
 *  it counts up while the agent works, freezes at the completion instant once
 *  the turn stops, and resets to zero when a new message moves the anchor. */
@Composable
internal fun ComposerStatusBar(
    stats: AppState.SessionStats?,
    isBusy: Boolean,
    agentActivityText: String?,
    backgroundTaskLabel: String?,
    onOpenBackgroundTask: () -> Unit,
    stopwatchStartedAtMillis: Long?,
    stopwatchEndedAtMillis: Long?,
    isRecording: Boolean,
    isTranscribing: Boolean,
    hasPreservedSpeechAudio: Boolean,
    isRetryingSpeech: Boolean,
    onAbort: () -> Unit
) {
    val voiceStatus = when {
        isRecording -> stringResource(R.string.chat_listening)
        isTranscribing -> stringResource(R.string.chat_transcribing)
        isRetryingSpeech -> stringResource(R.string.chat_retry_segment)
        hasPreservedSpeechAudio -> stringResource(R.string.chat_preserved_audio)
        else -> null
    }
    val activityStatus = if (isBusy) agentActivityText ?: stringResource(R.string.chat_agent_running) else null
    val status = listOfNotNull(activityStatus, voiceStatus).joinToString(" · ").takeIf { it.isNotEmpty() }
    val visibleStats = stats?.takeIf { it.hasVisibleSegments }
    val showCountersRow = visibleStats != null || stopwatchStartedAtMillis != null
    val hasBackgroundTasks = backgroundTaskLabel != null
    if (!showCountersRow && status == null && !hasBackgroundTasks) return

    val labelStyle = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    var menuExpanded by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    // Tick once per second while the stopwatch is running (anchor present and
    // not yet frozen); once frozen the value is pinned, so no ticking needed.
    LaunchedEffect(stopwatchStartedAtMillis, stopwatchEndedAtMillis) {
        while (stopwatchStartedAtMillis != null && stopwatchEndedAtMillis == null) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (hasBackgroundTasks) {
            BackgroundTasksRow(
                label = backgroundTaskLabel!!,
                onOpen = onOpenBackgroundTask
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        if (showCountersRow) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                var hasPrevious = false

                if (visibleStats != null) {
                    visibleStats.rounds?.let { rounds ->
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.chat_status_rounds),
                            tint = color,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(text = rounds.toString(), style = labelStyle, color = color)
                        hasPrevious = true
                    }

                    visibleStats.toolCalls?.let { toolCalls ->
                        if (hasPrevious) StatusSeparator(labelStyle, color)
                        Icon(
                            Icons.Default.Construction,
                            contentDescription = stringResource(R.string.chat_status_tool_calls),
                            tint = color,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(text = toolCalls.toString(), style = labelStyle, color = color)
                        hasPrevious = true
                    }

                    visibleStats.totalTokens?.let { totalTokens ->
                        if (hasPrevious) StatusSeparator(labelStyle, color)
                        Text(
                            text = "${compactTokenCount(totalTokens.toLong())} ${stringResource(R.string.chat_status_tokens)}",
                            style = labelStyle,
                            color = color
                        )
                        hasPrevious = true
                    }

                    visibleStats.cacheHitRate?.let { rate ->
                        if (hasPrevious) StatusSeparator(labelStyle, color)
                        val percent = (rate * 100f).roundToLong().coerceIn(0L, 100L)
                        Text(
                            text = "$percent% ${stringResource(R.string.chat_status_cache_hit)}",
                            style = labelStyle,
                            color = color
                        )
                        hasPrevious = true
                    }
                }

                stopwatchStartedAtMillis?.let { startedAt ->
                    if (hasPrevious) StatusSeparator(labelStyle, color)
                    Text(
                        text = formatTurnStopwatch(
                            turnStopwatchEnd(stopwatchEndedAtMillis, nowMillis) - startedAt
                        ),
                        style = labelStyle,
                        fontFamily = FontFamily.Monospace,
                        color = color
                    )
                }
            }
        }

        status?.let {
            if (showCountersRow) Spacer(modifier = Modifier.width(8.dp))
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
                text = it,
                style = labelStyle,
                color = color,
                maxLines = 1,
                modifier = if (showCountersRow) Modifier.weight(1f) else Modifier
            )
            if (isBusy) {
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.MoreHoriz,
                            contentDescription = stringResource(R.string.chat_interrupt_agent),
                            tint = color
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
    }
}

/** Delegated work still in flight while the current session is idle. Rendered on
 *  its own line, visually apart from the session's own "Agent running" state:
 *  a branch icon, the subagent title (or a count), and a chevron. Tap opens the
 *  running subagent session. Deliberately no stopwatch and no interrupt control —
 *  those belong to the subagent's own session, not this turn's clock. */
@Composable
private fun BackgroundTasksRow(
    label: String,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.AccountTree,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}
