package com.yage.opencode_client.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.data.audio.AIBuildersAudioClient
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    repository: OpenCodeRepository,
    onNavigateToFiles: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    showSettingsButton: Boolean = true,
    showNewSessionInTopBar: Boolean = true,
    showSessionListInTopBar: Boolean = true
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val aiBuilderToken = AIBuildersAudioClient.sanitizeBearerToken(viewModel.getAIBuilderSettings().token)
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleRecording()
        } else {
            viewModel.setSpeechError("Microphone permission denied. Please allow microphone access in system settings.")
        }
    }

    // Cache last non-null contextUsage so the ring stays visible during streaming
    var cachedContextUsage by remember { mutableStateOf(state.contextUsage) }
    state.contextUsage?.let { cachedContextUsage = it }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            sessions = state.sessions,
            currentSessionId = state.currentSessionId,
            expandedSessionIds = state.expandedSessionIds,
            agents = state.visibleAgents,
            sessionStatuses = state.sessionStatuses,
            hasMoreSessions = state.hasMoreSessions,
            isLoadingMoreSessions = state.isLoadingMoreSessions,
            selectedAgent = state.selectedAgentName,
            availableModels = state.availableModels,
            selectedModelIndex = state.selectedModelIndex,
            contextUsage = cachedContextUsage,
            onSelectSession = viewModel::selectSession,
            onCreateSession = { viewModel.createSession() },
            onDeleteSession = viewModel::deleteSession,
            onLoadMoreSessions = viewModel::loadMoreSessions,
            onToggleSessionExpanded = viewModel::toggleSessionExpanded,
            onSelectAgent = viewModel::selectAgent,
            onSelectModel = viewModel::selectModel,
            onNavigateToSettings = onNavigateToSettings,
            showSettingsButton = showSettingsButton,
            showNewSessionInTopBar = showNewSessionInTopBar,
            showSessionListInTopBar = showSessionListInTopBar,
            onRenameSession = { title ->
                val sessionId = state.currentSessionId ?: return@ChatTopBar
                viewModel.updateSessionTitle(sessionId, title)
            }
        )

        Box(modifier = Modifier.weight(1f)) {
            if (state.currentSessionId == null) {
                ChatEmptyState(
                    isConnected = state.isConnected,
                    onConnect = { viewModel.testConnection() }
                )
            } else {
                ChatMessageList(
                    messages = state.messages,
                    streamingPartTexts = state.streamingPartTexts,
                    streamingReasoningPart = state.streamingReasoningPart,
                    isLoading = state.isLoadingMessages,
                    messageLimit = state.messageLimit,
                    repository = repository,
                    workspaceDirectory = state.currentSession?.directory,
                    onLoadMore = { viewModel.loadMoreMessages() },
                    onFileClick = onNavigateToFiles,
                    onForkFromMessage = { messageId ->
                        state.currentSessionId?.let { sessionId ->
                            viewModel.forkSession(sessionId, messageId)
                        }
                    }
                )
            }

            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }

        if (state.currentSessionId != null) {
            ChatInputBar(
                text = state.inputText,
                isBusy = state.isCurrentSessionBusy,
                isRecording = state.isRecording,
                isTranscribing = state.isTranscribing,
                isSpeechConfigured = state.aiBuilderConnectionOK && aiBuilderToken.isNotEmpty(),
                onTextChange = viewModel::setInputText,
                onSend = { viewModel.sendMessage() },
                onAbort = { viewModel.abortSession() },
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
                title = { Text("Speech Recognition") },
                text = { Text(speechError) },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearSpeechError() }) {
                        Text("OK")
                    }
                }
            )
        }

        state.pendingPermissions.firstOrNull()?.let { permission ->
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
