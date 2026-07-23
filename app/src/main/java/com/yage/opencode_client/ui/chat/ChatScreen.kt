package com.yage.opencode_client.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.files.FilesScreen
import com.yage.opencode_client.ui.files.WorkspaceMarkdownLinkResolver
import com.yage.opencode_client.util.OpenCodeDeepLinkParser
import com.yage.opencode_client.ui.sanitizeBearerToken
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onNavigateToFiles: (String) -> Unit = {},
    useInlineFilePreview: Boolean = false,
    onNavigateToSettings: () -> Unit = {},
    showSettingsButton: Boolean = true,
    showNewSessionInTopBar: Boolean = true,
    showSessionListInTopBar: Boolean = true
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val aiBuilderToken = sanitizeBearerToken(viewModel.getAIBuilderSettings().token)
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleRecording()
        } else {
            viewModel.setSpeechError(context.getString(R.string.chat_microphone_permission_denied))
        }
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scope.launch {
            viewModel.addImageAttachments(loadImageAttachments(context, uris))
        }
    }
    var previewRequest by remember { mutableStateOf<WorkspacePreviewRequest?>(null) }
    var linkError by remember { mutableStateOf<String?>(null) }

    fun handleAssistantMarkdownLink(href: String) {
        if (OpenCodeDeepLinkParser.handles(href)) {
            viewModel.receiveDeepLink(href)
            return
        }
        val workspaceDirectory = state.currentSession?.directory
        when (val resolution = WorkspaceMarkdownLinkResolver.resolve(href, workspaceDirectory)) {
            is WorkspaceMarkdownLinkResolver.Resolution.External -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolution.url))
                runCatching { context.startActivity(intent) }
                    .onFailure { linkError = it.message ?: "Could not open link" }
            }
            is WorkspaceMarkdownLinkResolver.Resolution.Preview -> {
                if (useInlineFilePreview) {
                    onNavigateToFiles(resolution.path)
                } else {
                    previewRequest = WorkspacePreviewRequest(
                        path = resolution.path,
                        hostProfileId = state.currentHostProfileId,
                        sessionId = state.currentSessionId,
                        workspaceDirectory = workspaceDirectory.orEmpty()
                    )
                }
            }
            WorkspaceMarkdownLinkResolver.Resolution.Ignored -> Unit
            is WorkspaceMarkdownLinkResolver.Resolution.Rejected -> linkError = resolution.message
        }
    }

    // Cache last non-null contextUsage so the ring stays visible during streaming
    var cachedContextUsage by remember { mutableStateOf(state.contextUsage) }
    state.contextUsage?.let { cachedContextUsage = it }
    val currentSessionIsRunning = state.currentSessionStatus?.let { it.isBusy || it.isRetry } == true ||
        state.currentSessionId?.let { it in state.sendingSessionIds } == true
    val currentActivity = remember(
        state.currentSessionId,
        state.currentSessionStatus,
            state.visibleMessages,
        state.streamingReasoningPart,
        state.streamingPartTexts,
        state.sessionSendTimestamps,
    ) {
        currentSessionActivity(
            sessionId = state.currentSessionId,
            status = state.currentSessionStatus,
            messages = state.visibleMessages,
            streamingReasoningPart = state.streamingReasoningPart,
            streamingPartTexts = state.streamingPartTexts,
            sendTimestamp = state.currentSessionId?.let { state.sessionSendTimestamps[it] },
        )
    }
    val completedTurnActivities = remember(
        state.currentSessionId,
        state.visibleMessages,
        currentSessionIsRunning,
        currentActivity?.text,
    ) {
        turnActivitiesForSession(
            sessionId = state.currentSessionId,
            messages = state.visibleMessages,
            isSessionBusy = currentSessionIsRunning,
            activityText = currentActivity?.text ?: "",
            mode = TurnActivityMode.CompletedOnly,
        )
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.stopSpeechForBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            state = ChatTopBarState(
                sessions = state.sessions,
                currentSessionId = state.currentSessionId,
                sessionStatuses = state.sessionStatuses,
                attentionSessionIds = state.attentionSessionIds,
                hasMoreSessions = state.hasMoreSessions,
                isLoadingMoreSessions = state.isLoadingMoreSessions,
                isRefreshingSessions = state.isRefreshingSessions,
                expandedSessionIds = state.expandedSessionIds,
                availableModels = state.availableModels,
                selectedModelIndex = state.selectedModelIndex,
                contextUsage = cachedContextUsage,
                sessionTodos = state.sessionTodos[state.currentSessionId ?: ""] ?: emptyList(),
                aiUsageEnabled = state.aiUsageDashboardUrl.isNotBlank() && state.availableModels.getOrNull(state.selectedModelIndex)?.providerId in setOf("openai", "zai-coding-plan", "ollama-cloud"),
                selectedAIUsageQuota = state.selectedAIUsageQuota,
                aiUsageQuotaSnapshot = state.aiUsageQuotaSnapshot,
                isLoadingAIUsage = state.isLoadingAIUsage,
                isRefreshingAIUsage = state.isRefreshingAIUsage,
                aiUsageError = state.aiUsageError,
                aiUsageDashboardUrl = state.aiUsageDashboardUrl,
                showSettingsButton = showSettingsButton,
                showNewSessionInTopBar = showNewSessionInTopBar,
                showSessionListInTopBar = showSessionListInTopBar
            ),
            actions = ChatTopBarActions(
                onSelectSession = viewModel::selectSession,
                onCreateSession = viewModel::createSession,
                onDeleteSession = viewModel::deleteSession,
                onArchiveSession = viewModel::archiveSession,
                onRestoreSession = viewModel::restoreSession,
                onLoadMoreSessions = viewModel::loadMoreSessions,
                onRefreshSessions = viewModel::loadSessions,
                onToggleSessionExpanded = viewModel::toggleSessionExpanded,
                onSelectModel = viewModel::selectModel,
                onOpenAIUsage = viewModel::loadAIUsage,
                onRefreshAIUsage = viewModel::refreshAIUsage,
                onNavigateToSettings = onNavigateToSettings,
                onRenameSession = { title ->
                    state.currentSessionId?.let { sessionId ->
                        viewModel.updateSessionTitle(sessionId, title)
                    }
                }
            )
        )

        Box(modifier = Modifier.weight(1f)) {
            if (state.currentSessionId == null) {
                ChatEmptyState(
                    isConnected = state.isConnected,
                    onConnect = { viewModel.testConnection() }
                )
            } else {
                ChatMessageList(
                    messages = state.visibleMessages,
                    streamingPartTexts = state.streamingPartTexts,
                    streamingReasoningPart = state.streamingReasoningPart,
                    isLoading = state.isLoadingMessages,
                    messageLimit = state.messageLimit,
                    repository = viewModel.repository,
                    workspaceDirectory = state.currentSession?.directory,
                    completedTurnActivities = completedTurnActivities,
                    onLoadMore = { viewModel.loadMoreMessages() },
                    onFileClick = onNavigateToFiles,
                    onMarkdownLinkClick = ::handleAssistantMarkdownLink,
                    onForkFromMessage = { messageId ->
                        state.currentSessionId?.let { sessionId ->
                            viewModel.forkSession(sessionId, messageId)
                        }
                    },
                    onEditFromMessage = viewModel::editFromMessage
                )
            }

            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(stringResource(R.string.common_dismiss))
                        }
                    }
                ) {
                    Text(error)
                }
            }

            linkError?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { linkError = null }) {
                            Text(stringResource(R.string.common_dismiss))
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }

        previewRequest?.let { request ->
            if (request.hostProfileId != state.currentHostProfileId ||
                request.sessionId != state.currentSessionId ||
                request.workspaceDirectory != state.currentSession?.directory
            ) {
                LaunchedEffect(request, state.currentHostProfileId, state.currentSessionId, state.currentSession?.directory) {
                    previewRequest = null
                    linkError = "Workspace changed; reopen the link from the current session."
                }
            } else {
                Dialog(
                    onDismissRequest = { previewRequest = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        FilesScreen(
                            pathToShow = request.path,
                            sessionDirectory = request.workspaceDirectory,
                            onCloseFile = { previewRequest = null }
                        )
                    }
                }
            }
        }

        if (state.currentSessionId != null) {
            ChatInputBar(
                text = state.inputText,
                isBusy = currentSessionIsRunning,
                isRecording = state.isRecording,
                isTranscribing = state.isTranscribing,
                hasPreservedSpeechAudio = state.hasPreservedSpeechAudio,
                isRetryingSpeech = state.isRetryingSpeech,
                speechAudioLevel = state.speechAudioLevel,
                isSpeechConfigured = state.aiBuilderConnectionOK && aiBuilderToken.isNotEmpty(),
                agentActivityText = currentActivity?.text,
                agentStartedAtMillis = currentActivity?.startedAtMillis,
                imageAttachments = state.imageAttachments,
                onTextChange = viewModel::setInputText,
                onSend = { viewModel.sendMessage() },
                onAddImages = { imagePickerLauncher.launch("image/*") },
                onRemoveImage = viewModel::removeImageAttachment,
                onAbort = { viewModel.abortSession() },
                onAbortSpeech = { viewModel.abortSpeechRecognition() },
                onRetrySpeech = { viewModel.retryPreservedSpeechAudio() },
                onDiscardSpeech = { viewModel.discardPreservedSpeechAudio() },
                onToggleRecording = {
                    if (state.isRecording) {
                        viewModel.toggleRecording()
                    } else {
                        val hasRecordAudioPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasRecordAudioPermission) {
                            viewModel.toggleRecording()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            )
        }

        state.speechError?.let { speechError ->
            AlertDialog(
                onDismissRequest = { viewModel.clearSpeechError() },
                title = { Text(stringResource(R.string.settings_speech_recognition)) },
                text = { Text(speechError) },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearSpeechError() }) {
                        Text(stringResource(R.string.common_ok))
                    }
                }
            )
        }

        state.pendingPermissions
            .filter { it.sessionId == state.currentSessionId }
            .firstOrNull()
            ?.let { permission ->
            ChatPermissionCard(
                permission = permission,
                onRespond = { response ->
                    viewModel.respondPermission(permission.sessionId, permission.id, response)
                }
            )
        }

        state.pendingQuestions
            .filter { it.sessionId == state.currentSessionId }
            .firstOrNull()
            ?.let { question ->
                QuestionCardView(
                    question = question,
                    onReply = { answers, onError -> viewModel.replyQuestion(question.id, answers, onError) },
                    onReject = { viewModel.rejectQuestion(question.id) }
                )
            }
    }
}

private data class WorkspacePreviewRequest(
    val path: String,
    val hostProfileId: String?,
    val sessionId: String?,
    val workspaceDirectory: String
)

private data class CurrentSessionActivity(
    val text: String,
    val startedAtMillis: Long?,
)

private fun currentSessionActivity(
    sessionId: String?,
    status: SessionStatus?,
    messages: List<MessageWithParts>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
    sendTimestamp: Long? = null,
): CurrentSessionActivity? {
    val sid = sessionId ?: return null
    val userStartedAt = messages.lastOrNull { it.info.sessionId == sid && it.info.isUser }?.info?.time?.created
    val startedAt = userStartedAt ?: sendTimestamp
    val text = bestSessionActivityText(sid, status, messages, streamingReasoningPart, streamingPartTexts)
    return CurrentSessionActivity(text = text, startedAtMillis = startedAt)
}

private fun bestSessionActivityText(
    sessionId: String,
    status: SessionStatus?,
    messages: List<MessageWithParts>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): String {
    status?.message?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    messages.asReversed().forEach { message ->
        if (message.info.sessionId != sessionId) return@forEach
        message.parts.asReversed().firstOrNull { it.isTool && it.stateDisplay == "running" }
            ?.let { part -> formatStatusFromPart(part)?.let { return it } }
    }

    if (streamingReasoningPart?.sessionId == sessionId) {
        val key = "${streamingReasoningPart.messageId}:${streamingReasoningPart.id}"
        return formatThinkingFromReasoningText(streamingPartTexts[key].orEmpty())
    }

    messages.asReversed().forEach { message ->
        if (message.info.sessionId != sessionId) return@forEach
        message.parts.asReversed().firstOrNull()?.let { part ->
            formatStatusFromPart(part)?.let { return it }
        }
    }

    return status?.takeIf { it.isRetry }?.let { "Retrying" } ?: "Thinking"
}

private fun formatStatusFromPart(part: Part): String? {
    if (part.isTool) {
        val base = when (part.tool) {
            "task" -> "Delegating"
            "todowrite", "todoread" -> "Planning"
            "read" -> "Gathering context"
            "list", "grep", "glob" -> "Searching codebase"
            "webfetch" -> "Searching web"
            "edit", "write" -> "Making edits"
            "bash" -> "Running commands"
            else -> null
        }
        val topic = (part.toolReason ?: part.toolInputSummary)?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            base != null && topic != null -> "$base - $topic"
            base != null -> base
            else -> null
        }
    }

    if (part.isReasoning) return formatThinkingFromReasoningText(part.text.orEmpty())
    if (part.isText) return "Gathering thoughts"
    return null
}

private fun formatThinkingFromReasoningText(text: String): String {
    val topic = Regex("^\\*\\*([^*]+)\\*\\*").find(text.trim())?.groupValues?.getOrNull(1)?.trim()
    return if (!topic.isNullOrEmpty()) "Thinking - $topic" else "Thinking"
}
