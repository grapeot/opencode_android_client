package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yage.opencode_client.ui.chat.ChatInputBar
import com.yage.opencode_client.ui.ComposerActionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatInputBarInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun voiceControlsStayTogetherAndMirrorTheMicrophoneSide() {
        val side = mutableStateOf(ComposerActionSide.RIGHT)
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "", isRecording = false, isTranscribing = false, hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false, speechAudioLevel = 0f, isSpeechConfigured = true,
                    imageAttachments = emptyList(), onTextChange = {}, onSend = {}, onAddImages = {},
                    onRemoveImage = {}, onAbortSpeech = {}, onRetrySpeech = {}, onDiscardSpeech = {},
                    onToggleRecording = {}, voiceFirst = true, composerActionSide = side.value,
                )
            }
        }
        fun x(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.x
        fun micX() = composeRule.onNodeWithContentDescription("Tap to speak").fetchSemanticsNode().boundsInRoot.center.x
        fun waveformX() = composeRule.onNodeWithContentDescription("Speech waveform").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue(waveformX() < x("chat-paste") && x("chat-keyboard") < micX())
        composeRule.runOnIdle { side.value = ComposerActionSide.LEFT }
        assertTrue(micX() < x("chat-paste") && x("chat-keyboard") < waveformX())
        assertTrue(x("chat-paste") < x("chat-keyboard"))
    }

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
        composeRule.onNodeWithText("Transcribing").assertDoesNotExist()
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
        composeRule.onNodeWithText("Preserved audio").assertDoesNotExist()
        composeRule.onNodeWithText("Retry this segment").assertDoesNotExist()
    }

    @Test
    fun recordingKeepsListeningOnTransportOnly() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "",
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
                    onToggleRecording = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Listening").assertIsEnabled()
        composeRule.onNodeWithText("Listening").assertDoesNotExist()
    }

    @Test
    fun idleHidesTapToSpeakLabel() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "",
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

        composeRule.onNodeWithContentDescription("Tap to speak").assertIsEnabled()
        composeRule.onNodeWithText("Tap to speak").assertDoesNotExist()
    }

    @Test
    fun unconfiguredSpeechStillShowsConfigureHint() {
        composeRule.setContent {
            MaterialTheme {
                ChatInputBar(
                    text = "",
                    isRecording = false,
                    isTranscribing = false,
                    hasPreservedSpeechAudio = false,
                    isRetryingSpeech = false,
                    speechAudioLevel = 0f,
                    isSpeechConfigured = false,
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

        composeRule.onNodeWithText("Configure speech").assertIsDisplayed()
    }
}
