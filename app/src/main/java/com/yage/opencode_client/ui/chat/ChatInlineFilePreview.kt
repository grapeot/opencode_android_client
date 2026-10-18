package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.files.FilePreviewPane
import com.yage.opencode_client.ui.files.WorkspaceMarkdownLinkResolver
import com.yage.opencode_client.ui.files.buildDirectoryPreviewContent
import com.yage.opencode_client.ui.files.resolveRelativePreviewPath
import kotlinx.coroutines.CancellationException

data class ChatFilePreviewRequest(
    val path: String,
    val hostProfileId: String?,
    val sessionId: String,
    val workspaceDirectory: String?
)

fun ChatFilePreviewRequest.belongsTo(
    hostProfileId: String?,
    sessionId: String?,
    workspaceDirectory: String?
): Boolean {
    return this.hostProfileId == hostProfileId &&
        this.sessionId == sessionId &&
        this.workspaceDirectory == workspaceDirectory
}

val ChatFilePreviewRequestSaver: Saver<ChatFilePreviewRequest?, Any> = mapSaver(
    save = { request ->
        if (request == null) {
            emptyMap()
        } else {
            mapOf(
                "path" to request.path,
                "host" to request.hostProfileId,
                "session" to request.sessionId,
                "workspace" to request.workspaceDirectory
            )
        }
    },
    restore = { map ->
        val path = map["path"] as? String ?: return@mapSaver null
        val session = map["session"] as? String ?: return@mapSaver null
        ChatFilePreviewRequest(
            path = path,
            hostProfileId = map["host"] as? String,
            sessionId = session,
            workspaceDirectory = map["workspace"] as? String
        )
    }
)

internal fun shouldAcceptPreviewResult(
    sourceRequest: ChatFilePreviewRequest,
    currentRequest: ChatFilePreviewRequest?,
    resultGeneration: Long,
    currentGeneration: Long
): Boolean {
    return currentRequest === sourceRequest && resultGeneration == currentGeneration
}

internal suspend fun loadChatPreviewContent(
    repository: OpenCodeRepository,
    request: ChatFilePreviewRequest
): Result<FileContent> {
    val relPath = resolveRelativePreviewPath(request.path, request.workspaceDirectory)
    val directory = request.workspaceDirectory
    val contentResult = repository.getFileContent(relPath, directory)
    val content = contentResult.getOrNull()
    if (content != null && !content.content.isNullOrBlank()) {
        return Result.success(content)
    }
    val treeResult = repository.getFileTree(relPath, directory)
    val tree = treeResult.getOrNull()
    if (tree != null) {
        return Result.success(
            FileContent(
                type = "text",
                content = buildDirectoryPreviewContent(relPath, tree)
            )
        )
    }
    val error = contentResult.exceptionOrNull() ?: treeResult.exceptionOrNull()
    return Result.failure(error ?: IllegalStateException("Failed to load file"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatInlineFilePreview(
    request: ChatFilePreviewRequest,
    repository: OpenCodeRepository,
    onClose: () -> Unit,
    onRequestChange: (source: ChatFilePreviewRequest, updated: ChatFilePreviewRequest) -> Unit,
    onLinkError: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    loadContent: suspend (ChatFilePreviewRequest) -> Result<FileContent> = { loadChatPreviewContent(repository, it) }
) {
    var content by remember(request.hostProfileId, request.sessionId, request.workspaceDirectory, request.path) {
        mutableStateOf<FileContent?>(null)
    }
    var error by remember(request.hostProfileId, request.sessionId, request.workspaceDirectory, request.path) {
        mutableStateOf<String?>(null)
    }
    var isLoading by remember(request.hostProfileId, request.sessionId, request.workspaceDirectory, request.path) {
        mutableStateOf(true)
    }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(request, refreshGeneration) {
        val keepExisting = content != null && refreshGeneration > 0
        if (keepExisting) {
            isRefreshing = true
        } else {
            content = null
            error = null
            isLoading = true
        }
        try {
            val result = loadContent(request)
            result
                .onSuccess { loaded ->
                    content = loaded
                    error = null
                    isLoading = false
                    isRefreshing = false
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    if (!keepExisting) content = null
                    error = throwable.message ?: "Failed to load file"
                    isLoading = false
                    isRefreshing = false
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("chat-inline-file-preview")
    ) {
        val loaded = content
        if (loaded != null) {
            key(request.hostProfileId, request.sessionId, request.workspaceDirectory, request.path) {
                FilePreviewPane(
                    path = request.path,
                    fileContent = loaded,
                    repository = repository,
                    sessionDirectory = request.workspaceDirectory,
                    isRefreshing = isRefreshing,
                    onRefresh = { refreshGeneration += 1 },
                    onMarkdownLinkClick = { href, sourcePath ->
                        when (
                            val resolution = WorkspaceMarkdownLinkResolver.resolve(
                                href,
                                request.workspaceDirectory,
                                sourcePath
                            )
                        ) {
                            is WorkspaceMarkdownLinkResolver.Resolution.External -> onOpenExternal(resolution.url)
                            is WorkspaceMarkdownLinkResolver.Resolution.Preview -> {
                                onRequestChange(request, request.copy(path = resolution.path))
                            }
                            WorkspaceMarkdownLinkResolver.Resolution.Ignored -> Unit
                            is WorkspaceMarkdownLinkResolver.Resolution.Rejected -> onLinkError(resolution.message)
                        }
                    },
                    onClose = onClose
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            request.path.substringAfterLast('/').ifBlank { request.path },
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                        }
                    }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            error ?: "Failed to load file",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
