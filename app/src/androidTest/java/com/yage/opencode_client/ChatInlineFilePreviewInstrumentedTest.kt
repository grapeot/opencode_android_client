package com.yage.opencode_client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.chat.ChatFilePreviewRequest
import com.yage.opencode_client.ui.chat.ChatInlineFilePreview
import com.yage.opencode_client.ui.chat.ChatInputBar
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ChatInlineFilePreviewInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dockedPreviewKeepsComposerInteractiveWithoutDialog() {
        val repository = OpenCodeRepository()
        var sendClicks = 0
        val request = ChatFilePreviewRequest(
            path = "notes.txt",
            hostProfileId = "host",
            sessionId = "session-1",
            workspaceDirectory = "/workspace"
        )

        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ChatInlineFilePreview(
                            request = request,
                            repository = repository,
                            onClose = {},
                            onRequestChange = { _, _ -> },
                            onLinkError = {},
                            onOpenExternal = {},
                            loadContent = {
                                Result.success(FileContent(type = "text", content = "Hello preview"))
                            }
                        )
                    }
                    ChatInputBar(
                        text = "steer this",
                        isRecording = false,
                        isTranscribing = false,
                        hasPreservedSpeechAudio = false,
                        isRetryingSpeech = false,
                        speechAudioLevel = 0f,
                        isSpeechConfigured = true,
                        imageAttachments = emptyList(),
                        onTextChange = {},
                        onSend = { sendClicks++ },
                        onAddImages = {},
                        onRemoveImage = {},
                        onAbortSpeech = {},
                        onRetrySpeech = {},
                        onDiscardSpeech = {},
                        onToggleRecording = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("chat-inline-file-preview").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-input").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Dialog").assertDoesNotExist()
        assertEquals(1, sendClicks)
    }

    @Test
    fun recordingKeepsStopControlAndDisablesSendWhilePreviewIsOpen() {
        val repository = OpenCodeRepository()
        val request = ChatFilePreviewRequest(
            path = "notes.md",
            hostProfileId = "host",
            sessionId = "session-1",
            workspaceDirectory = "/workspace"
        )
        var stopClicks = 0

        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ChatInlineFilePreview(
                            request = request,
                            repository = repository,
                            onClose = {},
                            onRequestChange = { _, _ -> },
                            onLinkError = {},
                            onOpenExternal = {},
                            loadContent = { awaitCancellation() }
                        )
                    }
                    ChatInputBar(
                        text = "hello",
                        isRecording = true,
                        isTranscribing = false,
                        hasPreservedSpeechAudio = false,
                        isRetryingSpeech = false,
                        speechAudioLevel = 0.4f,
                        isSpeechConfigured = true,
                        imageAttachments = emptyList(),
                        onTextChange = {},
                        onSend = {},
                        onAddImages = {},
                        onRemoveImage = {},
                        onAbortSpeech = {},
                        onRetrySpeech = {},
                        onDiscardSpeech = {},
                        onToggleRecording = { stopClicks++ }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("chat-inline-file-preview").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-send").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Listening").assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { stopClicks == 1 }
    }

    @Test
    fun closeControlIsAvailableWhilePreviewIsLoading() {
        val repository = OpenCodeRepository()
        val request = ChatFilePreviewRequest(
            path = "pending.md",
            hostProfileId = "host",
            sessionId = "session-1",
            workspaceDirectory = "/workspace"
        )
        var closed: ChatFilePreviewRequest? = request

        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf<ChatFilePreviewRequest?>(request) }
                closed = current
                if (current != null) {
                    ChatInlineFilePreview(
                        request = current!!,
                        repository = repository,
                        onClose = { current = null },
                        onRequestChange = { _, _ -> },
                        onLinkError = {},
                        onOpenExternal = {},
                        loadContent = { awaitCancellation() }
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { closed == null }
        assertNull(closed)
    }
}
