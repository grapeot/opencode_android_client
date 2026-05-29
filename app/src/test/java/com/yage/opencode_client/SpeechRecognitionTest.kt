package com.yage.opencode_client

import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.mergedSpeechInput
import com.yage.opencode_client.ui.sanitizeBearerToken
import com.yage.opencode_client.ui.speechFailureInput
import org.junit.Assert.*
import org.junit.Test

/**
 * Speech-input UI-glue tests. The realtime audio pipeline (URL building, WAV writing,
 * the disk cache, PCM resampling, the WebSocket session) moved into the VoiceFlowKit
 * library and is covered by that module's own unit tests; only the opencode-side glue
 * (token sanitizing + transcript merging + AppState defaults) is exercised here.
 */
class SpeechRecognitionTest {

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
        assertNull(state.speechError)
        assertFalse(state.aiBuilderConnectionOK)
        assertNull(state.aiBuilderConnectionError)
        assertFalse(state.isTestingAIBuilderConnection)
    }

    @Test
    fun `AppState speech fields can be set`() {
        val state = AppState(
            isRecording = true,
            isTranscribing = true,
            speechError = "mic failed",
            aiBuilderConnectionOK = true,
            aiBuilderConnectionError = "timeout",
            isTestingAIBuilderConnection = true
        )
        assertTrue(state.isRecording)
        assertTrue(state.isTranscribing)
        assertEquals("mic failed", state.speechError)
        assertTrue(state.aiBuilderConnectionOK)
        assertEquals("timeout", state.aiBuilderConnectionError)
        assertTrue(state.isTestingAIBuilderConnection)
    }
}
