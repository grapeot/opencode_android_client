package com.yage.opencode_client.ui.chat

import android.content.ClipData
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import com.yage.opencode_client.ui.util.DataUriImageTransformer
import com.yage.opencode_client.ui.util.HttpImageHolder
import com.yage.opencode_client.ui.util.MarkdownImageResolver
import com.yage.opencode_client.ui.files.WorkspaceLinkMarkdown
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun ChatMessageList(
    messages: List<MessageWithParts>,
    streamingPartTexts: Map<String, String>,
    streamingReasoningPart: Part?,
    isLoading: Boolean,
    messageLimit: Int,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    completedTurnActivities: List<TurnActivity>,
    onLoadMore: () -> Unit,
    onFileClick: (String) -> Unit,
    onMarkdownLinkClick: (String) -> Unit,
    onForkFromMessage: (String) -> Unit,
    onEditFromMessage: (String) -> Unit,
    onOpenChildSession: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val layoutInfo = listState.layoutInfo
    // Stick until the user drags away. Layout-driven offset changes must not
    // release it: those are the scroll jumps, and treating them as "user left
    // the bottom" is what stopped follow.
    var stickToBottom by remember(listState) { mutableStateOf(true) }
    val releaseFollow = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && !restingAtBottom(listState)) {
                    stickToBottom = false
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            // Offset drifts above 24 while the latest row grows. That is not
            // the user leaving. Re-stick whenever the latest row is still the
            // one on screen and the finger is up.
            !listState.isScrollInProgress && listState.firstVisibleItemIndex == 0
        }.collect { onLatest ->
            if (onLatest) stickToBottom = true
        }
    }
    // Pin before draw. animateScrollToItem per token restarts a spring and
    // flashes; requestScrollToItem applies in the same frame as the growth.
    SideEffect {
        if (
            stickToBottom &&
            (messages.isNotEmpty() || streamingReasoningPart != null) &&
            (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
        ) {
            listState.requestScrollToItem(0)
        }
    }


    // remember keys prevent stale-closure: isLoading/messages/messageLimit are plain values, not State.
    // reverseLayout=true: highest index = visual top (oldest). lastVisible >= total-3 fires there.
    val shouldLoadMore = remember(isLoading, messages.size, messageLimit) {
        derivedStateOf {
            if (isLoading || messages.isEmpty()) return@derivedStateOf false
            if (messages.size < messageLimit) return@derivedStateOf false
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf false
            val total = layoutInfo.totalItemsCount
            val lastVisible = visible.maxOfOrNull { it.index } ?: return@derivedStateOf false
            lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) onLoadMore()
    }

    // Interleave completed turn activity rows after each assistant turn, matching iOS.
    val interleaved = remember(messages, completedTurnActivities) {
        if (completedTurnActivities.isEmpty()) {
            messages.map { ChatItem.Message(it) }
        } else {
            val activityByUserId = completedTurnActivities.associateBy { it.id }
            val items = mutableListOf<ChatItem>()
            var currentUserId: String? = null
            var seenAssistantForCurrentUser = false
            for (message in messages) {
                if (message.info.isUser) {
                    if (currentUserId != null && seenAssistantForCurrentUser) {
                        activityByUserId[currentUserId]?.let { items.add(ChatItem.Activity(it)) }
                    }
                    currentUserId = message.info.id
                    seenAssistantForCurrentUser = false
                    items.add(ChatItem.Message(message))
                } else if (message.info.isAssistant) {
                    if (currentUserId != null) seenAssistantForCurrentUser = true
                    items.add(ChatItem.Message(message))
                } else {
                    items.add(ChatItem.Message(message))
                }
            }
            if (currentUserId != null && seenAssistantForCurrentUser) {
                activityByUserId[currentUserId]?.let { items.add(ChatItem.Activity(it)) }
            }
            items
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(releaseFollow),
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        // Reasoning stays in the message row. A separate bottom streaming card
        // appears and disappears against that tile on every part.updated, which
        // is the single-card vs streaming flicker.
        items(interleaved.reversed(), key = {
            when (it) {
                is ChatItem.Message -> it.message.info.id
                is ChatItem.Activity -> "activity-${it.activity.id}"
            }
        }) { item ->
            when (item) {
                is ChatItem.Message -> MessageRow(
                    message = item.message,
                    streamingPartTexts = streamingPartTexts,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory,
                    onFileClick = onFileClick,
                    onMarkdownLinkClick = onMarkdownLinkClick,
                    onForkFromMessage = onForkFromMessage,
                    onEditFromMessage = onEditFromMessage,
                    onOpenChildSession = onOpenChildSession
                )
                is ChatItem.Activity -> TurnActivityRow(activity = item.activity)
            }
        }
        if (isLoading && messages.size >= messageLimit) {
            item(key = "load-more-indicator") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
        if (!isLoading && messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet. Send a message to start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun shownMarkdown(resolved: String?, normalized: String): String {
    val current = resolved ?: return normalized
    return if (current.length >= normalized.length) current else normalized
}

private fun restingAtBottom(listState: LazyListState): Boolean {
    return listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 24
}

internal fun copyableMessageText(parts: List<Part>): String = parts
    .asSequence()
    .filter { it.isText }
    .mapNotNull { part ->
        val notification = TaskNotificationParser.notificationFor(part)
        val text = notification?.resultText ?: part.text
        text?.takeIf { it.isNotEmpty() }
    }
    .joinToString("\n\n")

@Composable
private fun MessageRow(
    message: MessageWithParts,
    streamingPartTexts: Map<String, String>,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    onMarkdownLinkClick: (String) -> Unit,
    onForkFromMessage: (String) -> Unit,
    onEditFromMessage: (String) -> Unit,
    onOpenChildSession: (String) -> Unit
) {
    val isUser = message.info.isUser
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val copyableText = remember(message.parts) { copyableMessageText(message.parts) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        // No "OpenCode" speaker title — the user's blue left bar vs the
        // assistant's container-less reply already make it clear who's speaking,
        // so an extra blue label is redundant.

        if (isUser) {
            var i = 0
            while (i < message.parts.size) {
                val part = message.parts[i]
                val streamingText = streamingPartTexts["${message.info.id}:${part.id}"]
                key(part.id) {
                    PartView(
                        part = part,
                        isUser = isUser,
                        streamingTextOverride = streamingText,
                        repository = repository,
                        workspaceDirectory = workspaceDirectory,
                        onFileClick = onFileClick,
                        onMarkdownLinkClick = onMarkdownLinkClick,
                        onOpenChildSession = onOpenChildSession,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                i += 1
            }
        } else {
            // Assistant reply: split parts into card tiles (thinking / tool calls /
            // file cards — always half width, sharing one 2-up grid) and full-width
            // content blocks (text / attachments), both kept in part order. The grid
            // renders first and the content after it; that card-first read order is
            // the accepted trade-off of the half-width tile design.
            val tiles = mutableListOf<CardTile>()
            val contentBlocks = mutableListOf<ContentBlock>()
            var i = 0
            while (i < message.parts.size) {
                val part = message.parts[i]
                when {
                    part.isReasoning -> {
                        tiles.add(ThinkingTile(part, streamingPartTexts["${message.info.id}:${part.id}"]))
                        i += 1
                    }
                    part.isTool || part.isPatch -> {
                        // Buffer a contiguous run of tool/patch parts and split it once
                        // via ToolCardClassifier: file ops each become a FileCard tile,
                        // the non-file rest merges into a single ToolCallsRow tile that
                        // sits at the end of the run (file cards cluster, then calls).
                        val run = mutableListOf<Part>()
                        var j = i
                        while (j < message.parts.size) {
                            val p = message.parts[j]
                            if (p.isTool || p.isPatch) {
                                run.add(p)
                                j++
                            } else break
                        }
                        val (fileParts, otherParts) = ToolCardClassifier.split(run)
                        fileParts.forEach { fp -> tiles.add(FileTile(fp)) }
                        if (otherParts.isNotEmpty()) tiles.add(ToolCallTile(otherParts))
                        i = j
                    }
                    part.isStepStart || part.isStepFinish -> i += 1
                    else -> {
                        contentBlocks.add(ContentBlock(part, streamingPartTexts["${message.info.id}:${part.id}"]))
                        i += 1
                    }
                }
            }

            if (tiles.isNotEmpty()) {
                // Same 2-up grid as the file cards: chunked(2) + manual Row (Android
                // can't nest LazyVGrid inside LazyColumn), tiles at weight(1f), a lone
                // tile leaves the right slot empty. Tiles stay half width whether
                // collapsed or expanded; expanded content lives inside the tile.
                tiles.chunked(2).forEach { chunk ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        chunk.forEach { tile ->
                            val tileKey = when (tile) {
                                is ThinkingTile -> tile.part.id
                                is FileTile -> tile.part.id
                                is ToolCallTile -> tile.parts.first().id
                            }
                            key(tileKey) {
                                when (tile) {
                                    is ThinkingTile -> ReasoningCard(
                                        text = displayedStreamingText(tile.streamingText, tile.part.text),
                                        title = tile.part.toolReason,
                                        isStreaming = false,
                                        modifier = Modifier.weight(1f)
                                    )
                                    is ToolCallTile -> ToolCallsRow(
                                        parts = tile.parts,
                                        onFileClick = onFileClick,
                                        modifier = Modifier.weight(1f)
                                    )
                                    is FileTile -> FileCard(
                                        part = tile.part,
                                        onFileClick = onFileClick,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        if (chunk.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            contentBlocks.forEach { block ->
                key(block.part.id) {
                    PartView(
                        part = block.part,
                        isUser = false,
                        streamingTextOverride = block.streamingText,
                        repository = repository,
                        workspaceDirectory = workspaceDirectory,
                        onFileClick = onFileClick,
                        onMarkdownLinkClick = onMarkdownLinkClick,
                        onOpenChildSession = onOpenChildSession,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isUser) message.info.resolvedModel?.let { model ->
                val segments = mutableListOf("${model.providerId}/${model.modelId}")
                message.throughputComponents()?.let { components ->
                    val rate = components.throughput
                    if (rate > 0) segments.add(MessageWithParts.throughputText(rate))
                }
                Text(
                    text = segments.joinToString(" | "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                var showMenu by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.chat_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_copy_message)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null
                            )
                        },
                        enabled = copyableText.isNotEmpty(),
                        onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("message", copyableText))
                                )
                            }
                            showMenu = false
                        }
                    )
                    if (TaskNotificationParser.offersEditFromHere(isUser, message.parts)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_edit_from_here)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMenu = false
                                onEditFromMessage(message.info.id)
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_fork_from_here)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMenu = false
                                onForkFromMessage(message.info.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** One tile in the assistant 2-up card grid. Every tile is always half width
 *  (a weight(1f) slot); expanded content stays inside the tile, never full width. */
private sealed class CardTile

/** Half-width thinking tile (ReasoningCard). Carries the streaming text override
 *  so live reasoning text can replace the part's own text. */
private data class ThinkingTile(val part: Part, val streamingText: String?) : CardTile()

/** One merged half-width "N tool calls" tile (ToolCallsRow) holding a run's
 *  non-file tools. */
private data class ToolCallTile(val parts: List<Part>) : CardTile()

/** Half-width file card tile (FileCard) for one file-operation tool/patch. */
private data class FileTile(val part: Part) : CardTile()

/** Full-width content block rendered after the card grid (text / attachment). */
private data class ContentBlock(val part: Part, val streamingText: String?)

/** Longer of the live overlay and the persisted part text. A one-token overlay
 *  must not hide text the row already rendered. */
internal fun displayedStreamingText(override: String?, partText: String?): String {
    val streamed = override.orEmpty()
    val persisted = partText.orEmpty()
    return if (streamed.length >= persisted.length) streamed else persisted
}

@Composable
private fun PartView(
    part: Part,
    isUser: Boolean,
    streamingTextOverride: String?,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    onMarkdownLinkClick: (String) -> Unit,
    onOpenChildSession: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val displayedText = displayedStreamingText(streamingTextOverride, part.text)
    val taskNotification = if (part.isText) TaskNotificationParser.notificationFor(part, displayedText) else null
    when {
        taskNotification != null -> TaskNotificationCard(
            notification = taskNotification,
            repository = repository,
            workspaceDirectory = workspaceDirectory,
            onMarkdownLinkClick = onMarkdownLinkClick,
            onOpenSession = onOpenChildSession,
            modifier = modifier
        )
        part.isText -> TextPart(
            text = displayedText,
            isUser = isUser,
            modifier = modifier,
            repository = repository,
            workspaceDirectory = workspaceDirectory,
            onMarkdownLinkClick = onMarkdownLinkClick
        )
        part.isReasoning -> ReasoningCard(streamingTextOverride ?: part.text ?: "", part.toolReason, false, modifier)
        part.isImageAttachment -> ImageFilePart(part, modifier)
        part.isFile -> FileAttachmentPart(part, modifier)
        part.isTool -> ToolCard(part, onFileClick, modifier)
        part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty() -> PatchCard(part.filePathsForNavigationFiltered, onFileClick, modifier)
    }
}

@Composable
private fun ImageFilePart(part: Part, modifier: Modifier = Modifier.fillMaxWidth()) {
    val imageBitmap = remember(part.url) {
        part.url?.decodeDataUriImage()?.asImageBitmap()
    }
    if (imageBitmap == null) {
        FileAttachmentPart(part, modifier)
        return
    }
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Image(
            bitmap = imageBitmap,
            contentDescription = part.filename ?: "Attached image",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth
        )
        part.filename?.let { filename ->
            Text(
                text = filename,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun FileAttachmentPart(part: Part, modifier: Modifier = Modifier.fillMaxWidth()) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = part.filename ?: part.mime ?: "Attached file",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun String.decodeDataUriImage(): android.graphics.Bitmap? {
    val marker = ";base64,"
    val markerIndex = indexOf(marker)
    if (!startsWith("data:image/") || markerIndex < 0) return null
    return runCatching {
        val bytes = Base64.decode(substring(markerIndex + marker.length), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

/**
 * Compact file card for file-operation tools (patch / edit / write / read). Quiet
 * Tech styling: neutral surface body, single blue accent on the doc icon, monospace
 * basename, chevron to navigate. When the part is a `read` of a *directory* (server
 * reports `<type>directory</type>`), the card switches to a folder icon and tapping
 * opens a bottom sheet listing the already-returned `<entries>` — no API call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileCard(
    part: Part,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    // Prefer an explicit path; fall back to the first navigable path so the card
    // always has a label even when metadata.path is absent (matches iOS priority).
    val displayPath = part.metadata?.path?.takeIf { it.isNotEmpty() }
        ?: part.state?.pathFromInput?.takeIf { it.isNotEmpty() }
        ?: part.filePathsForNavigation.firstOrNull()
    val basename = displayPath?.takeIf { it.isNotEmpty() }
        ?.substringAfterLast("/")?.takeIf { it.isNotEmpty() }
        ?: "file"

    val isDirectoryRead = ToolCardClassifier.isDirectoryRead(part)
    val isReadOnlyFileTool = part.tool?.lowercase()?.let { tool ->
        ToolCardClassifier.readToolPrefixes.any { tool.startsWith(it) }
    } == true
    var showFolderSheet by remember { mutableStateOf(false) }

    // testTag encodes the read/write nature of the card so the semantics tree can
    // distinguish them. A directory listing is always a read. For files, the tool
    // prefix decides: readToolPrefixes -> read, otherwise write/edit/patch -> write.
    // Layers 2-4 (component / integration-UI / LLM-driven) all rely on this; the icon
    // color alone is invisible to the semantics tree.
    val tag = when {
        isDirectoryRead -> "toolcard.folder.$basename"
        isReadOnlyFileTool -> "toolcard.read.$basename"
        else -> "toolcard.write.$basename"
    }
    val iconDescription = when {
        isDirectoryRead -> "Read directory $basename"
        isReadOnlyFileTool -> "Read file $basename"
        else -> "Write file $basename"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .padding(vertical = 4.dp)
            .testTag(tag)
            .clickable {
                if (isDirectoryRead) {
                    showFolderSheet = true
                } else {
                    val paths = part.filePathsForNavigation
                    val target = paths.firstOrNull() ?: displayPath
                    if (target != null) onFileClick(target)
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDirectoryRead) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = iconDescription,
                modifier = Modifier.size(16.dp),
                tint = if (isReadOnlyFileTool) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = basename,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }

    if (showFolderSheet) {
        val entries = remember(part.id, part.toolOutput) {
            ToolCardClassifier.parseDirectoryEntries(part.toolOutput)
        }
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFolderSheet = false },
            sheetState = sheetState,
            modifier = Modifier.testTag("toolcard.folder.sheet.$basename")
        ) {
            FolderContents(folderName = basename, entries = entries)
        }
    }
}

/** Sheet body listing a read directory's contents. Subdirectories sort above files. */
@Composable
private fun FolderContents(
    folderName: String,
    entries: List<ToolCardClassifier.DirectoryEntry>
) {
    val sorted = remember(entries) {
        entries.sortedWith(
            compareByDescending<ToolCardClassifier.DirectoryEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = folderName,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (sorted.isEmpty()) {
            Text(
                text = "This directory has no entries.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            sorted.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .testTag("toolcard.folder.entry.${entry.name}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
    }
}

/**
 * Merged "N tool calls" tile for non-file tools — always half width: it lives in a
 * weight(1f) slot of the shared 2-up card grid, next to thinking and file tiles.
 * Collapsed by default; expanding reveals each tool's full body (reused ToolCard
 * content) inside the same half-width tile, which just gets taller. Mirrors iOS's
 * DisclosureGroup-based toolCallsRow.
 */
@Composable
private fun ToolCallsRow(
    parts: List<Part>,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var expanded by remember { mutableStateOf(false) }
    // No card surface: the header indents 12dp to align with the answer body; the
    // header row fills its (half-width) slot so the spacer pushes the chevron to
    // the tile's right edge; expanded ToolCards stay inside the same slot.
    Column(modifier = modifier.padding(vertical = 4.dp).testTag("toolcard.toolcalls")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${parts.size} tool calls",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            parts.forEach { part ->
                ToolCard(part, onFileClick, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TextPart(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    repository: OpenCodeRepository? = null,
    workspaceDirectory: String? = null,
    onMarkdownLinkClick: (String) -> Unit = {}
) {
    val innerModifier = modifier.padding(12.dp)
    if (isUser) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            shape = RoundedCornerShape(12.dp),
            modifier = modifier
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    } else {
        if (repository != null) {
            ResolvedMarkdownText(
                text = text,
                repository = repository,
                workspaceDirectory = workspaceDirectory,
                onMarkdownLinkClick = onMarkdownLinkClick,
                modifier = innerModifier
            )
        } else {
            val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }
            SelectionContainer {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Markdown(
                        markdownState = rememberMarkdownState(content = normalizedText, retainState = true),
                        typography = markdownTypographyCompact(),
                        modifier = innerModifier,
                        imageTransformer = DataUriImageTransformer
                    )
                }
            }
        }
    }
}

@Composable
internal fun ResolvedMarkdownText(
    text: String,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onMarkdownLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Do not key this on text. A new token used to reset resolvedText to null,
    // the block collapsed, and reverseLayout jumped the viewport. Keep the
    // last resolved body until the new resolve finishes.
    var resolvedText by remember(workspaceDirectory) { mutableStateOf<String?>(null) }
    val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }

    LaunchedEffect(normalizedText, workspaceDirectory, repository) {
        val resolved = MarkdownImageResolver.resolveImages(
            text = normalizedText,
            workspaceDirectory = workspaceDirectory,
            fetchContent = { path -> repository.getFileContent(path).getOrThrow() }
        )
        resolvedText = resolved
        val finalText = resolved
        val httpsUrls = """!\[[^\]]*\]\((https?://[^)]+)\)""".toRegex().findAll(finalText).map { it.groupValues[1] }.toList().distinct()
        for (url in httpsUrls) {
            HttpImageHolder.prefetch(url)
        }
    }

    SelectionContainer {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            WorkspaceLinkMarkdown(
                content = shownMarkdown(resolvedText, normalizedText),
                modifier = modifier,
                onLinkClick = onMarkdownLinkClick
            )
        }
    }
}

@Composable
private fun ReasoningCard(
    text: String,
    title: String?,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    // Thinking defaults to collapsed, including the live streaming card: the
    // header conveys the state, and the body only appears on explicit expand.
    var expanded by remember { mutableStateOf(false) }

    // Always a half-width tile (weight(1f) slot of the shared 2-up card grid) once
    // the thinking lands in a message; the list-level streaming item is the only
    // full-width use (live content never goes half width). No card surface: the
    // 12dp horizontal padding aligns header/text with the answer body, and the
    // header row fills its slot so the spacer pushes the chevron to the tile's
    // right edge. Expanded thinking stays inside the tile. No clickable modifier
    // while streaming: the row is not a control then.
    val toggle = if (isStreaming) Modifier else Modifier.clickable { expanded = !expanded }
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(toggle)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                title ?: "Thinking",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!isStreaming) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (expanded && text.isNotBlank()) {
            val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }
            SelectionContainer {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    Markdown(
                        markdownState = rememberMarkdownState(content = normalizedText, retainState = true),
                        typography = markdownTypographyCompact(),
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 8.dp),
                        imageTransformer = DataUriImageTransformer
                    )
                }
            }
        }
    }
}

/**
 * Inline todo list, extracted from the old ToolCard expanded body so it matches
 * iOS TodoListInlineView. Used for `todowrite`, whose expanded card shows only the
 * todos (input/output hidden).
 */
@Composable
private fun TodoListInline(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Column(modifier = modifier) {
        todos.forEach { todo ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (todo.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (todo.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = todo.content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else null
                    ),
                    color = if (todo.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (todo.priority != "medium") {
                    Text(text = todo.priority, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    part: Part,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val toolName = part.tool ?: ""
    val status = part.stateDisplay
    val reason = part.toolReason
    val filePaths = part.filePathsForNavigationFiltered
    val todos = part.toolTodos
    val isTodoWrite = part.tool == "todowrite"
    val input = part.toolInputSummary
    val output = part.toolOutput

    val isRunning = status == "running"
    var expanded by remember { mutableStateOf(isRunning) }
    val firstFile = filePaths.firstOrNull()
    val displayName = if (toolName == "apply_patch") "patch" else toolName

    val isReadOnlyTool = listOf("read_file", "read", "grep", "glob", "list", "webfetch", "task", "todoread")
        .any { toolName.startsWith(it) }

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isReadOnlyTool) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = displayName.ifEmpty { reason ?: "tool" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (firstFile != null) {
                        IconButton(onClick = { onFileClick(firstFile) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.files_show_in_files),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (expanded) {
                    if (!reason.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // todowrite shows a compact badge; full list is in the toolbar panel (matches iOS).
                    if (isTodoWrite) {
                        if (todos.isNotEmpty()) {
                            Spacer(modifier = Modifier.size(8.dp))
                            val completed = todos.count { it.isCompleted }
                            Text(
                                "Todo updated · $completed/${todos.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        if (todos.isNotEmpty()) {
                            Spacer(modifier = Modifier.size(8.dp))
                            TodoListInline(todos)
                        }
                        if (!input.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = input,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!output.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = output,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (filePaths.isNotEmpty()) {
                        Spacer(modifier = Modifier.size(8.dp))
                        filePaths.forEach { path ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onFileClick(path) }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = stringResource(R.string.files_show_in_files),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchCard(
    filePaths: List<String>,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Card(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${filePaths.size} ${if (filePaths.size == 1) "file" else "files"} changed",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                filePaths.forEach { path ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onFileClick(path) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.files_show_in_files),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class ChatItem {
    data class Message(val message: MessageWithParts) : ChatItem()
    data class Activity(val activity: TurnActivity) : ChatItem()
}

@Composable
private fun TurnActivityRow(activity: TurnActivity) {
    val nowMillis by produceState(initialValue = System.currentTimeMillis(), activity.isRunning, activity.endedAtMillis) {
        if (activity.isRunning) {
            while (true) {
                value = System.currentTimeMillis()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (activity.isRunning) Icons.Default.Schedule else Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = activity.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = activity.elapsedString(nowMillis),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
