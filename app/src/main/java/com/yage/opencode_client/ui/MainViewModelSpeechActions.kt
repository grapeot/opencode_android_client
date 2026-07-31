package com.yage.opencode_client.ui

import android.util.Log
import com.yage.opencode_client.util.SettingsManager
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import com.yage.voiceflowkit.VoiceFlowPreservedAudio
import com.yage.voiceflowkit.VoiceFlowSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File

internal data class SpeechInputConfig(
    val token: String,
    val baseURL: String,
    val prompt: String,
    val terminology: String,
    val recordingStrategy: VoiceFlowRecordingStrategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
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
        recordingStrategy = VoiceFlowRecordingStrategy.fromRaw(settingsManager.aiBuilderRecordingStrategy),
    )
}

internal data class SpeechDraftTarget(
    val sessionId: String?,
    val existingInput: String,
)

internal sealed interface PreservedSpeechRecording {
    val strategy: VoiceFlowRecordingStrategy
    val target: SpeechDraftTarget

    data class Realtime(
        val audio: VoiceFlowPreservedAudio,
        override val target: SpeechDraftTarget,
    ) : PreservedSpeechRecording {
        override val strategy: VoiceFlowRecordingStrategy = audio.strategy
    }

    data class FileRecording(
        val file: File,
        override val strategy: VoiceFlowRecordingStrategy,
        override val target: SpeechDraftTarget,
    ) : PreservedSpeechRecording
}

internal class SpeechRecordingFileOwner {
    private enum class Owner { ATTEMPT, CLEANUP, NONE }

    private var owner = Owner.ATTEMPT
    private var file: File? = null

    @Synchronized
    fun record(candidate: File?): File? {
        if (candidate != null) file = candidate
        return file
    }

    @Synchronized
    fun handoffToCleanup() {
        if (owner == Owner.ATTEMPT) owner = Owner.CLEANUP
    }

    @Synchronized
    fun claimForAttempt(candidate: File? = null): File? = claim(Owner.ATTEMPT, candidate)

    @Synchronized
    fun claimForCleanup(candidate: File? = null): File? = claim(Owner.CLEANUP, candidate)

    @Synchronized
    fun current(): File? = file

    @Synchronized
    fun isCleanupOwner(): Boolean = owner == Owner.CLEANUP

    private fun claim(expectedOwner: Owner, candidate: File?): File? {
        if (candidate != null) file = candidate
        if (owner != expectedOwner) return null
        owner = Owner.NONE
        return file.also { file = null }
    }
}

internal class AttemptScopedSpeechTypewriter(
    private val scope: CoroutineScope,
    private val shouldApply: () -> Boolean,
    private val writeSnapshot: (String) -> Unit,
) {
    @Volatile
    private var closed = false
    private var pendingJob: Job? = null

    @Synchronized
    fun submit(snapshot: String) {
        if (closed) return
        pendingJob?.cancel()
        pendingJob = scope.launch {
            // Keep callback threads nonblocking while still checking ownership at write time.
            yield()
            if (!closed && shouldApply()) writeSnapshot(snapshot)
        }
    }

    @Synchronized
    fun cancel() {
        closed = true
        pendingJob?.cancel()
        pendingJob = null
    }
}

internal class SpeechAudioBackpressureException(message: String) : IllegalStateException(message)

internal class OrderedSpeechPcmSender(
    scope: CoroutineScope,
    capacity: Int = 8,
    private val drainTimeoutMillis: Long = 5_000L,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sendChunk: suspend (ByteArray) -> Unit,
) {
    private val channel = Channel<ByteArray>(capacity)
    @Volatile
    private var rejectedAudio = false
    private val worker = scope.async(dispatcher) {
        try {
            for (chunk in channel) sendChunk(chunk)
        } finally {
            channel.cancel()
        }
    }

    init {
        require(capacity > 0) { "PCM sender capacity must be positive" }
    }

    fun trySend(chunk: ByteArray): Boolean {
        if (rejectedAudio) return false
        val accepted = channel.trySend(chunk).isSuccess
        if (!accepted) rejectedAudio = true
        return accepted
    }

    suspend fun closeAndDrain() {
        if (rejectedAudio) {
            cancel()
            throw SpeechAudioBackpressureException(BUFFER_FULL_MESSAGE)
        }
        channel.close()
        try {
            withContext(Dispatchers.IO) {
                withTimeout(drainTimeoutMillis) { worker.await() }
            }
        } catch (error: TimeoutCancellationException) {
            cancel()
            throw SpeechAudioBackpressureException(
                "Live audio delivery stalled; the complete recording was saved for retry.",
            ).apply { initCause(error) }
        } catch (cancelled: CancellationException) {
            cancel()
            throw cancelled
        } catch (error: Exception) {
            cancel()
            throw SpeechAudioBackpressureException(
                "Live audio delivery stalled; the complete recording was saved for retry.",
            ).apply { initCause(error) }
        }
    }

    fun cancel() {
        channel.cancel()
        worker.cancel()
    }

    companion object {
        const val BUFFER_FULL_MESSAGE =
            "Live audio buffer saturated; the complete recording was saved for retry."
    }
}

internal class SpeechSessionOwner(
    val session: VoiceFlowSession,
) {
    private val mutex = Mutex()
    private var terminalResult: Result<VoiceFlowPreservedAudio?>? = null

    suspend fun preserve(): VoiceFlowPreservedAudio? {
        mutex.lock()
        return try {
            val existing = terminalResult
            if (existing != null) {
                existing.getOrThrow()
            } else {
                val result = runCatching { session.abortPreservingAudio() }
                terminalResult = result
                result.getOrThrow()
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun discard() {
        mutex.lock()
        try {
            if (terminalResult == null) {
                val result = runCatching {
                    session.cancel()
                    null
                }
                terminalResult = result
                result.getOrThrow()
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun markCommitted() {
        mutex.lock()
        try {
            if (terminalResult == null) terminalResult = Result.success(null)
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * Finalize a live VoiceFlowKit session: commit the audio, stream partial deltas into
 * the input field, and write the final transcript. Mirrors the previous
 * `RealtimeSpeechStreamer.commitAndStop` flow 1:1. The library owns recovery,
 * cache replay, and strategy-specific finalize behavior.
 */
internal fun launchRealtimeSpeechStop(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>,
    session: VoiceFlowSession,
    existingInput: String,
    tag: String,
    shouldApply: () -> Boolean = { true },
    shouldPreserve: () -> Boolean = shouldApply,
    onPartialTranscript: ((String) -> Unit)? = null,
    onFinalTranscript: (suspend (String) -> Unit)? = null,
    onFailure: (suspend (Throwable) -> Unit)? = null,
    onCommitted: suspend () -> Unit,
    terminateSession: suspend (Boolean) -> Unit,
    onFinished: () -> Unit,
): Job = scope.launch {
    var committed = false
    try {
        val transcript = session.commitAndStop { partial ->
            if (shouldApply()) {
                if (onPartialTranscript != null) {
                    onPartialTranscript(partial)
                } else {
                    state.update { it.copy(inputText = mergedSpeechInput(existingInput, partial)) }
                }
            }
        }
        onCommitted()
        committed = true
        val cleaned = transcript.trim()
        if (!shouldApply()) return@launch
        Log.d(tag, "Realtime transcription success: chars=${cleaned.length}")
        if (onFinalTranscript != null) {
            onFinalTranscript(cleaned)
        } else {
            state.update {
                it.copy(
                    inputText = mergedSpeechInput(existingInput, cleaned),
                    isTranscribing = false,
                    speechError = null,
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (!shouldApply()) return@launch
        Log.e(tag, "Realtime speech processing failed", error)
        if (onFailure != null) {
            onFailure(error)
        } else {
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
        }
    } finally {
        if (!committed) withContext(NonCancellable) {
            terminateSession(shouldPreserve())
        }
        if (shouldApply()) onFinished()
    }
}
