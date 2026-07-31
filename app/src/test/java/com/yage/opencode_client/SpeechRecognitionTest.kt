package com.yage.opencode_client

import com.yage.opencode_client.ui.AIBuilderSettings
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.mergedSpeechInput
import com.yage.opencode_client.ui.sanitizeBearerToken
import com.yage.opencode_client.ui.speechFailureInput
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import org.junit.Assert.*
import org.junit.Test

/**
 * Speech-input UI-glue tests. The realtime audio pipeline (URL building, WAV writing,
 * the disk cache, PCM resampling, the WebSocket session) moved into the VoiceFlowKit
 * library and is covered by that module's own unit tests; only the opencode-side glue
 * (token sanitizing + transcript merging + AppState defaults) is exercised here.
 */
class SpeechRecognitionTest {

    @Test
    fun `recording strategy raw values preserve all supported strategies`() {
        assertEquals(
            VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            VoiceFlowRecordingStrategy.fromRaw("OPENAI_REALTIME"),
        )
        assertEquals(
            VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            VoiceFlowRecordingStrategy.fromRaw("GPT_LIVE_TRANSCRIBE"),
        )
        assertEquals(
            VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            VoiceFlowRecordingStrategy.fromRaw("gptLiveTranscribe"),
        )
        assertEquals(
            VoiceFlowRecordingStrategy.GROK_BATCH,
            VoiceFlowRecordingStrategy.fromRaw("GROK_BATCH"),
        )
    }

    @Test
    fun `unknown recording strategy falls back to GPT Realtime`() {
        assertEquals(
            VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            VoiceFlowRecordingStrategy.fromRaw("future-strategy"),
        )
        assertEquals(
            VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            VoiceFlowRecordingStrategy.fromRaw(null),
        )
    }

    // ─── sanitizeBearerToken ─────────────────────────────────────────

    @Test
    fun `sanitizeBearerToken strips surrounding and hidden whitespace`() {
        val raw = "  abc​123﻿  "
        assertEquals("abc123", sanitizeBearerToken(raw))
    }

    @Test
    fun `sanitizeBearerToken keeps normal token characters`() {
        val raw = "sk-proj_ABC-123.xyz"
        assertEquals(raw, sanitizeBearerToken(raw))
    }

    // ─── mergedSpeechInput ───────────────────────────────────────────

    @Test
    fun `mergedSpeechInput both non-empty`() {
        assertEquals("Hello world", mergedSpeechInput("Hello", "world"))
    }

    @Test
    fun `mergedSpeechInput empty prefix`() {
        assertEquals("world", mergedSpeechInput("", "world"))
    }

    @Test
    fun `mergedSpeechInput empty transcript`() {
        assertEquals("Hello", mergedSpeechInput("Hello", ""))
    }

    @Test
    fun `mergedSpeechInput both empty`() {
        assertEquals("", mergedSpeechInput("", ""))
    }

    @Test
    fun `mergedSpeechInput trims transcript whitespace`() {
        assertEquals("Hello world", mergedSpeechInput("Hello", "  world  "))
    }

    @Test
    fun `mergedSpeechInput whitespace-only transcript returns prefix`() {
        assertEquals("Hello", mergedSpeechInput("Hello", "   "))
    }

    @Test
    fun `speechFailureInput preserves visible partial transcript`() {
        assertEquals("Hello partial result", speechFailureInput("Hello", "Hello partial result"))
        assertEquals("partial result", speechFailureInput("", "partial result"))
    }

    @Test
    fun `speechFailureInput falls back to existing input without partial transcript`() {
        assertEquals("Hello", speechFailureInput("Hello", "   "))
    }

    // ─── AppState speech defaults ────────────────────────────────────

    @Test
    fun `AppState speech fields have correct defaults`() {
        val state = AppState()
        assertFalse(state.isRecording)
        assertFalse(state.isTranscribing)
        assertFalse(state.hasPreservedSpeechAudio)
        assertFalse(state.isRetryingSpeech)
        assertNull(state.speechError)
        assertFalse(state.aiBuilderConnectionOK)
        assertNull(state.aiBuilderConnectionError)
        assertFalse(state.isTestingAIBuilderConnection)
    }

    @Test
    fun `AI Builder settings default to GPT Live Transcribe`() {
        val settings = AIBuilderSettings("", "", "", "")
        assertEquals("GPT_LIVE_TRANSCRIBE", settings.recordingStrategy)
    }

    @Test
    fun `AppState speech fields can be set`() {
        val state = AppState(
            isRecording = true,
            isTranscribing = true,
            hasPreservedSpeechAudio = true,
            isRetryingSpeech = true,
            speechError = "mic failed",
            aiBuilderConnectionOK = true,
            aiBuilderConnectionError = "timeout",
            isTestingAIBuilderConnection = true
        )
        assertTrue(state.isRecording)
        assertTrue(state.isTranscribing)
        assertTrue(state.hasPreservedSpeechAudio)
        assertTrue(state.isRetryingSpeech)
        assertEquals("mic failed", state.speechError)
        assertTrue(state.aiBuilderConnectionOK)
        assertEquals("timeout", state.aiBuilderConnectionError)
        assertTrue(state.isTestingAIBuilderConnection)
    }
}
