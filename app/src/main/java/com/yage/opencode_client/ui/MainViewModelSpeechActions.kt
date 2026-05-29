package com.yage.opencode_client.ui

import android.util.Log
import com.yage.opencode_client.util.SettingsManager
import com.yage.voiceflowkit.VoiceFlowSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SpeechInputConfig(
    val token: String,
    val baseURL: String,
    val prompt: String,
    val terminology: String,
) {
    /** Comma-separated terminology split into the VoiceFlowKit `terms` list. */
    val terms: List<String>
        get() = terminology
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

internal fun currentSpeechInputConfig(settingsManager: SettingsManager): SpeechInputConfig {
    return SpeechInputConfig(
        token = sanitizeBearerToken(settingsManager.aiBuilderToken),
        baseURL = settingsManager.aiBuilderBaseURL.trim(),
        prompt = settingsManager.aiBuilderCustomPrompt.trim(),
        terminology = settingsManager.aiBuilderTerminology.trim(),
    )
}

/**
 * Finalize a live VoiceFlowKit session: commit the audio, stream partial deltas into
 * the input field, and write the final transcript. Mirrors the previous
 * `RealtimeSpeechStreamer.commitAndStop` flow 1:1 — the library now owns recovery,
 * cache replay, and finalize retry internally.
 */
internal fun launchRealtimeSpeechStop(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>,
    session: VoiceFlowSession,
    existingInput: String,
    tag: String,
    onFinished: () -> Unit,
) {
    scope.launch {
        try {
            val transcript = session.commitAndStop { partial ->
                state.update { it.copy(inputText = mergedSpeechInput(existingInput, partial)) }
            }
            val cleaned = transcript.trim()
            Log.d(tag, "Realtime transcription success: chars=${cleaned.length}")
            state.update {
                it.copy(
                    inputText = mergedSpeechInput(existingInput, cleaned),
                    isTranscribing = false,
                )
            }
        } catch (error: Exception) {
            Log.e(tag, "Realtime speech processing failed", error)
            state.update {
                it.copy(
                    inputText = speechFailureInput(
                        existingInput = existingInput,
                        currentInput = it.inputText,
                    ),
                    isTranscribing = false,
                    speechError = errorMessageOrFallback(error, "Transcription failed"),
                )
            }
        } finally {
            onFinished()
        }
    }
}
