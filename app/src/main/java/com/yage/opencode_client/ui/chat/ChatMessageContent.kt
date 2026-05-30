package com.yage.opencode_client.ui.chat

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import com.yage.opencode_client.ui.util.DataUriImageTransformer
import com.yage.opencode_client.ui.util.HttpImageHolder
import com.yage.opencode_client.ui.util.MarkdownImageResolver
import kotlinx.coroutines.flow.collect

@Composable
internal fun ChatMessageList(
    messages: List<MessageWithParts>,
    streamingPartTexts: Map<String, String>,
    streamingReasoningPart: Part?,
    isLoading: Boolean,
    messageLimit: Int,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onLoadMore: () -> Unit,
    onFileClick: (String) -> Unit,
    onForkFromMessage: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val layoutInfo = listState.layoutInfo
    var shouldAutoScroll by remember { mutableStateOf(true) }
    val contentVersion = remember(messages, streamingPartTexts, streamingReasoningPart, isLoading) {
        messages.size +
            messages.sumOf { it.parts.size } +
            streamingPartTexts.hashCode() +
            (if (streamingReasoningPart != null) 1 else 0) +
            (if (isLoading) 1 else 0)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 24
        }.collect { atBottom ->
            shouldAutoScroll = atBottom
        }
    }

    LaunchedEffect(contentVersion) {
        if (shouldAutoScroll && (messages.isNotEmpty() || streamingReasoningPart != null)) {
            listState.animateScrollToItem(0)
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        if (streamingReasoningPart != null) {
            val streamingKey = "${streamingReasoningPart.messageId}:${streamingReasoningPart.id}"
            val streamingText = streamingPartTexts[streamingKey] ?: ""
            item(key = "streaming-reasoning") {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ReasoningCard(
                        text = streamingText,
                        title = streamingReasoningPart.toolReason,
                        isStreaming = true
                    )
                }
            }
        }
        items(messages.reversed(), key = { it.info.id }) { message ->
            MessageRow(
                message = message,
                streamingPartTexts = streamingPartTexts,
                repository = repository,
                workspaceDirectory = workspaceDirectory,
                onFileClick = onFileClick,
                onForkFromMessage = onForkFromMessage
            )
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

@Composable
private fun MessageRow(
    message: MessageWithParts,
    streamingPartTexts: Map<String, String>,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    onForkFromMessage: (String) -> Unit
) {
    val isUser = message.info.isUser

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        // "OpenCode" header: not an avatar/icon, just a small accent title so it's
        // obvious the AI is speaking (contrast with the user's blue left bar). The
        // user side keeps its blue left color bar (rendered in TextPart) instead.
        if (!isUser) {
            Text(
                text = "OpenCode",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp).testTag("assistant.header")
            )
        }

        var i = 0
        while (i < message.parts.size) {
            val part = message.parts[i]
            val streamingText = streamingPartTexts["${message.info.id}:${part.id}"]
            val isToolLike = part.isTool || (part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty())
            if (isToolLike) {
                // Buffer a contiguous run of tool/patch parts, then split it via
                // ToolCardClassifier into file ops (→ 2-column file-card grid) and
                // everything else (→ a single merged "N tool calls" row). Layout-first
                // near-time order: file cards cluster, other tools cluster.
                val run = mutableListOf<Part>()
                var j = i
                while (j < message.parts.size) {
                    val p = message.parts[j]
                    if (p.isTool || (p.isPatch && p.filePathsForNavigationFiltered.isNotEmpty())) {
                        run.add(p)
                        j++
                    } else break
                }

                val (fileParts, otherParts) = ToolCardClassifier.split(run)

                if (fileParts.isNotEmpty()) {
                    // Android can't nest LazyVGrid inside LazyColumn, so use the existing
                    // chunked(2) + manual Row two-column layout (matches iPhone 2-up grid).
                    fileParts.chunked(2).forEach { chunk ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            chunk.forEach { p ->
                                FileCard(
                                    part = p,
                                    onFileClick = onFileClick,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (chunk.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (otherParts.isNotEmpty()) {
                    ToolCallsRow(
                        parts = otherParts,
                        onFileClick = onFileClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                i = j
            } else {
                PartView(
                    part = part,
                    isUser = isUser,
                    streamingTextOverride = streamingText,
                    repository = repository,
                    workspaceDirectory = workspaceDirectory,
                    onFileClick = onFileClick,
                    modifier = Modifier.fillMaxWidth()
                )
                i += 1
            }
        }
        if (!isUser) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                message.info.resolvedModel?.let { model ->
                    Text(
                        text = "${model.providerId}/${model.modelId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Fork from here") },
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

@Composable
private fun PartView(
    part: Part,
    isUser: Boolean,
    streamingTextOverride: String?,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    when {
        part.isText -> TextPart(
            text = streamingTextOverride ?: part.text ?: "",
            isUser = isUser,
            modifier = modifier,
            repository = repository,
            workspaceDirectory = workspaceDirectory
        )
        part.isReasoning -> ReasoningCard(streamingTextOverride ?: part.text ?: "", part.toolReason, false, modifier)
        part.isTool -> ToolCard(part, onFileClick, modifier)
        part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty() -> PatchCard(part.filePathsForNavigationFiltered, onFileClick, modifier)
    }
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
private fun FileCard(
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
    var showFolderSheet by remember { mutableStateOf(false) }

    val tag = if (isDirectoryRead) "toolcard.folder.$basename" else "toolcard.file.$basename"

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
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
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
 * Merged "N tool calls" row for non-file tools. Collapsed by default; expanding
 * reveals each tool's full body (reused ToolCard content). Mirrors iOS's
 * DisclosureGroup-based toolCallsRow.
 */
@Composable
private fun ToolCallsRow(
    parts: List<Part>,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.padding(vertical = 4.dp).testTag("toolcard.toolcalls"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${parts.size} tool calls",
                    style = MaterialTheme.typography.labelLarge,
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
                Spacer(modifier = Modifier.size(8.dp))
                parts.forEach { part ->
                    ToolCard(part, onFileClick, Modifier.fillMaxWidth())
                }
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
    workspaceDirectory: String? = null
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
                modifier = innerModifier
            )
        } else {
            val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }
            SelectionContainer {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Markdown(content = normalizedText, typography = markdownTypographyCompact(), modifier = innerModifier, imageTransformer = DataUriImageTransformer)
                }
            }
        }
    }
}

@Composable
private fun ResolvedMarkdownText(
    text: String,
    repository: OpenCodeRepository,
    workspaceDirectory: String?,
    modifier: Modifier = Modifier
) {
    var resolvedText by remember(text, workspaceDirectory) { mutableStateOf<String?>(null) }
    val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }

    LaunchedEffect(normalizedText, workspaceDirectory, repository) {
        resolvedText = null
        resolvedText = MarkdownImageResolver.resolveImages(
            text = normalizedText,
            workspaceDirectory = workspaceDirectory,
            fetchContent = { path -> repository.getFileContent(path).getOrThrow() }
        )
        val finalText = resolvedText ?: normalizedText
        val httpsUrls = """!\[[^\]]*\]\((https?://[^)]+)\)""".toRegex().findAll(finalText).map { it.groupValues[1] }.toList().distinct()
        for (url in httpsUrls) {
            HttpImageHolder.prefetch(url)
        }
    }

    SelectionContainer {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Markdown(
                content = resolvedText ?: normalizedText,
                typography = markdownTypographyCompact(),
                modifier = modifier,
                imageTransformer = DataUriImageTransformer
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
    var expanded by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming) {
        if (isStreaming) expanded = true
    }

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title ?: "Thinking",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!isStreaming) {
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            if ((expanded || isStreaming) && text.isNotBlank()) {
                val normalizedText = remember(text) { MarkdownImageResolver.normalizeStandaloneImageBlocks(text) }
                SelectionContainer {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        Markdown(
                            content = normalizedText,
                            typography = markdownTypographyCompact(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            imageTransformer = DataUriImageTransformer
                        )
                    }
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
                            tint = MaterialTheme.colorScheme.primary
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
                                Icons.Default.OpenInNew,
                                contentDescription = "Show in Files",
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

                    // todowrite shows only its todos; input/output are hidden (matches iOS).
                    if (isTodoWrite) {
                        if (todos.isNotEmpty()) {
                            Spacer(modifier = Modifier.size(8.dp))
                            TodoListInline(todos)
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
                                        Icons.Default.OpenInNew,
                                        contentDescription = "Show in Files",
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
                                Icons.Default.OpenInNew,
                                contentDescription = "Show in Files",
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
