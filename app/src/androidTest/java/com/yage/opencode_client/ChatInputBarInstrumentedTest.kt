package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yage.opencode_client.ui.chat.ChatInputBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatInputBarInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inputKeepsSendAndSpeakEnabledWhenIdle() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "hello",
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Tap to speak").assertIsEnabled()
    }

    @Test
    fun readyInputEnablesSendAndSpeechCallbacks() {
        var sendClicks = 0
        var speechClicks = 0

        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "hello",
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
                    onToggleRecording = { speechClicks++ }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Tap to speak").assertIsEnabled().performClick()

        assertEquals(1, sendClicks)
        assertEquals(1, speechClicks)
    }

    @Test
    fun transcribingShowsStopWaitAndKeepsSendDisabled() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "partial transcript",
                    isRecording = false,
                    isTranscribing = true,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {}
                )
            }
        }

        composeRule.onNodeWithText("Stop transcription wait").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun preservedAudioShowsRetryAndDiscardActions() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "",
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = true,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = true,
                    imageAttachments = emptyList(),
                    onTextChange = {},
                    onSend = {},
                    onAddImages = {},
                    onRemoveImage = {},
                    onAbortSpeech = {},
                    onRetrySpeech = {},
                    onDiscardSpeech = {},
                    onToggleRecording = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Retry this segment").assertIsEnabled()
        composeRule.onNodeWithText("Discard audio").assertIsDisplayed()
    }
}
