package com.yage.opencode_client.ambient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.ambient.protocol.AmbientActivity
import com.yage.opencode_client.ambient.protocol.AmbientCaption
import com.yage.opencode_client.ambient.protocol.AmbientMeter
import com.yage.opencode_client.ambient.protocol.AmbientSnapshot

/** Stable tags for the Ambient surface. Integration tests should use these names. */
object AmbientTags {
    const val SCREEN = "ambient-screen"
    const val STATUS = "ambient-status"
    const val MIC = "ambient-mic"
    const val TRANSCRIPT = "ambient-transcript"
    const val DETAILS = "ambient-details"
    const val DETAILS_BODY = "ambient-details-body"
    const val INPUT_LEVEL = "ambient-input-level"
    const val OUTPUT_LEVEL = "ambient-output-level"
    const val VOICE_DURATION = "ambient-voice-duration"
    const val SESSION_DURATION = "ambient-session-duration"
    const val INPUT_TOKENS = "ambient-input-tokens"
    const val OUTPUT_TOKENS = "ambient-output-tokens"
    const val CACHE_READ = "ambient-cache-read"
    const val CACHE_WRITE = "ambient-cache-write"
    const val LOCAL_ESTIMATE = "ambient-local-estimate"
    const val PORTAL_ESTIMATE = "ambient-portal-estimate"
    const val COST_HEADING = "ambient-cost-heading"
    const val COST_NOTE = "ambient-cost-note"
    const val VOLUME = "ambient-volume"
    const val CONTROLS = "ambient-controls"
    const val MUTE = "ambient-mute"
    const val STOP = "ambient-stop-playback"
    const val END = "ambient-end"
    const val CONNECT = "ambient-connect"

    fun caption(id: String): String = "ambient-caption-$id"
    fun tool(id: String): String = "ambient-tool-$id"
    fun toolResult(id: String): String = "ambient-tool-result-$id"
}

/**
 * Current index and maximum of the output stream the audio engine already plays.
 * Neither field is an application gain.
 */
data class AmbientOutputVolume(
    val value: Int,
    val max: Int,
)

/**
 * Ambient session surface. Renders [snapshot] only. Composition does not open a
 * microphone, audio device, or network connection.
 *
 * [outputVolume] is the current index and maximum of the output stream the audio
 * engine uses. [onOutputVolumeChange] receives the index to apply on that same
 * stream. This function does not call AudioManager.
 *
 * [onConnect] starts or reconnects. [onSetMuted] receives the desired muted state.
 * [onInterruptPlayback] is momentary. [onEnd] ends the session.
 *
 * Place this in the content pane. Width changes padding only. The transcript and
 * the control slot stay one column; there is no hinge split and no navigation change.
 */
@Composable
fun AmbientScreen(
    snapshot: AmbientSnapshot,
    outputVolume: AmbientOutputVolume,
    onConnect: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onInterruptPlayback: () -> Unit,
    onEnd: () -> Unit,
    onOutputVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = ambientRows(snapshot)
    val listState = rememberLazyListState()
    val follow = remember { FollowMemory() }
    val signature = rowsSignature(rows)
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(signature) {
        val index = changedRowIndex(follow.previous, rows)
        val hadHistory = follow.previous.isNotEmpty()
        follow.previous = rows.associate { it.key to it.followText }
        if (index !in rows.indices) return@LaunchedEffect
        if (!hadHistory) {
            listState.scrollToItem(index)
        } else {
            listState.revealChangedRow(index)
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag(AmbientTags.SCREEN),
    ) {
        val pad = if (maxWidth >= 600.dp) 24.dp else 16.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = pad, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AmbientStatusHeader(snapshot)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val reserve = AmbientMeter.MIN_TOUCH_DP.dp
                val bodyMax = (maxHeight - reserve - reserve).coerceAtLeast(0.dp).coerceAtMost(280.dp)
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag(AmbientTags.TRANSCRIPT),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is CaptionRow -> AmbientCaptionBubble(row.caption)
                                is ToolRow -> AmbientToolCard(
                                    activity = row.activity,
                                    expanded = expanded[row.activity.id] == true,
                                    onToggle = {
                                        val id = row.activity.id
                                        expanded[id] = expanded[id] != true
                                    },
                                )
                            }
                        }
                    }
                    AmbientDetails(snapshot, bodyMaxHeight = bodyMax)
                }
            }
            AmbientVolumeSlider(volume = outputVolume, onChange = onOutputVolumeChange)
            AmbientControlSlot(
                phase = snapshot.phase,
                muted = snapshot.muted,
                inputRms = snapshot.inputRms,
                onConnect = onConnect,
                onSetMuted = onSetMuted,
                onInterruptPlayback = onInterruptPlayback,
                onEnd = onEnd,
            )
        }
    }
}

@Composable
private fun AmbientStatusHeader(snapshot: AmbientSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AmbientTags.STATUS),
    ) {
        Text(
            text = ambientPhaseLabel(snapshot.phase),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val message = snapshot.statusMessage
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal suspend fun LazyListState.revealChangedRow(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null || item.offset < info.viewportStartOffset) {
        scrollToItem(index)
        return
    }
    val overflow = item.offset + item.size - info.viewportEndOffset
    if (overflow > 0) {
        scroll { scrollBy(overflow.toFloat()) }
    }
}

private class FollowMemory {
    var previous: Map<String, String> = emptyMap()
}

internal sealed interface AmbientRow {
    val sequence: Int
    val key: String
    val followText: String
}

internal data class CaptionRow(val caption: AmbientCaption) : AmbientRow {
    override val sequence: Int = caption.sequence
    override val key: String = "caption:${caption.id}"
    override val followText: String = caption.text
}

internal data class ToolRow(val activity: AmbientActivity) : AmbientRow {
    override val sequence: Int = activity.sequence
    override val key: String = "tool:${activity.id}"
    override val followText: String =
        activity.status + "\u0000" + activity.arguments + "\u0000" + activity.result
}

internal fun ambientRows(snapshot: AmbientSnapshot): List<AmbientRow> {
    val rows = ArrayList<AmbientRow>(snapshot.captions.size + snapshot.activities.size)
    snapshot.captions.forEach { rows.add(CaptionRow(it)) }
    snapshot.activities.forEach { rows.add(ToolRow(it)) }
    rows.sortWith(compareBy({ it.sequence }, { it.key }))
    return rows
}

internal fun rowsSignature(rows: List<AmbientRow>): String = buildString {
    for (row in rows) {
        append(row.sequence)
        append('\u0001')
        append(row.key)
        append('\u0001')
        append(row.followText)
        append('\u0002')
    }
}

internal fun changedRowIndex(previous: Map<String, String>, rows: List<AmbientRow>): Int {
    var index = -1
    for (i in rows.indices) {
        val row = rows[i]
        if (previous[row.key] != row.followText) index = i
    }
    return index
}
