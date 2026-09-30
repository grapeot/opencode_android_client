package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.ui.AppState
import java.util.Locale
import kotlin.math.roundToLong

/** Compact token count mirroring the iOS status line: < 1000 as-is, then
 *  K/M/B/T with one decimal for values >= 10 and two below, trailing zeros
 *  trimmed ("950", "1K", "85.2K", "1.11M", "2B"). */
internal fun compactTokenCount(value: Long): String {
    if (value < 1000) return value.toString()
    val (divisor, suffix) = when {
        value >= 1_000_000_000_000 -> 1_000_000_000_000L to "T"
        value >= 1_000_000_000 -> 1_000_000_000L to "B"
        value >= 1_000_000 -> 1_000_000L to "M"
        else -> 1_000L to "K"
    }
    val scaled = value / divisor.toDouble()
    val text = if (scaled >= 10) {
        String.format(Locale.US, "%.1f", scaled)
    } else {
        String.format(Locale.US, "%.2f", scaled)
    }
    return trimTrailingZeros(text) + suffix
}

private fun trimTrailingZeros(text: String): String {
    if ('.' !in text) return text
    return text.trimEnd('0').trimEnd('.')
}

/** Persistent session status line shown directly above the composer:
 *  rounds / tool calls / total tokens / cache hit rate. Segments with no
 *  data are omitted; the row is not composed at all when every segment is
 *  unknown. */
@Composable
internal fun SessionStatusRow(stats: AppState.SessionStats) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var hasPrevious = false

        @Composable
        fun separator() {
            Text("·", style = labelStyle, color = color)
        }

        stats.rounds?.let { rounds ->
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.chat_status_rounds),
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(text = rounds.toString(), style = labelStyle, color = color)
            hasPrevious = true
        }

        stats.toolCalls?.let { toolCalls ->
            if (hasPrevious) separator()
            Icon(
                Icons.Default.Construction,
                contentDescription = stringResource(R.string.chat_status_tool_calls),
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(text = toolCalls.toString(), style = labelStyle, color = color)
            hasPrevious = true
        }

        stats.totalTokens?.let { totalTokens ->
            if (hasPrevious) separator()
            Text(
                text = "${compactTokenCount(totalTokens.toLong())} ${stringResource(R.string.chat_status_tokens)}",
                style = labelStyle,
                color = color
            )
            hasPrevious = true
        }

        stats.cacheHitRate?.let { rate ->
            if (hasPrevious) separator()
            val percent = (rate * 100f).roundToLong().coerceIn(0L, 100L)
            Text(
                text = "$percent% ${stringResource(R.string.chat_status_cache_hit)}",
                style = labelStyle,
                color = color
            )
        }
    }
}
