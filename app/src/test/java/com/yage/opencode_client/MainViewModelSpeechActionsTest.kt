package com.yage.opencode_client

import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.AttemptScopedSpeechTypewriter
import com.yage.opencode_client.ui.OrderedSpeechPcmSender
import com.yage.opencode_client.ui.SpeechAudioBackpressureException
import com.yage.opencode_client.ui.SpeechSessionOwner
import com.yage.opencode_client.ui.launchRealtimeSpeechStop
import com.yage.voiceflowkit.VoiceFlowPreservedAudio
import com.yage.voiceflowkit.VoiceFlowSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSpeechActionsTest {

    @Test
    fun `stale realtime attempt cannot overwrite newer input`() = runTest {
        val session = mockk<VoiceFlowSession>()
        val state = MutableStateFlow(AppState(inputText = "newer input", isTranscribing = true))
        var committed = false
        var terminated = false
        coEvery { session.commitAndStop(any()) } coAnswers {
            firstArg<((String) -> Unit)?>()?.invoke("stale partial")
            "stale final"
        }

        val job = launchRealtimeSpeechStop(
            scope = this,
            state = state,
            session = session,
            existingInput = "old prefix",
            tag = "test",
            shouldApply = { false },
            shouldPreserve = { false },
            onCommitted = { committed = true },
            terminateSession = { terminated = true },
            onFinished = {},
        )
        job.join()

        assertEquals("newer input", state.value.inputText)
        assertTrue(committed)
        assertFalse(terminated)
    }

    @Test
    fun `ordered PCM sender drains every chunk before commit`() = runTest {
        val events = mutableListOf<String>()
        val sender = OrderedSpeechPcmSender(
            scope = this,
            capacity = 2,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ) { chunk -> events += "audio:${chunk.first()}" }

        assertTrue(sender.trySend(byteArrayOf(1)))
        assertTrue(sender.trySend(byteArrayOf(2)))
        sender.closeAndDrain()
        events += "commit"

        assertEquals(listOf("audio:1", "audio:2", "commit"), events)
    }

    @Test
    fun `bounded PCM sender rejects immediately and fails drain for WAV retry`() = runTest {
        val sender = OrderedSpeechPcmSender(
            scope = this,
            capacity = 1,
            dispatcher = StandardTestDispatcher(testScheduler),
        ) {}

        assertTrue(sender.trySend(byteArrayOf(1)))
        assertFalse(sender.trySend(byteArrayOf(2)))
        val error = runCatching { sender.closeAndDrain() }.exceptionOrNull()

        assertTrue(error is SpeechAudioBackpressureException)
        assertTrue(error?.message.orEmpty().contains("saved for retry"))
    }

    @Test
    fun `cancelled typewriter cannot apply its queued snapshot after newer final`() = runTest {
        val state = MutableStateFlow(AppState(inputText = "source"))
        val typewriter = AttemptScopedSpeechTypewriter(
            scope = this,
            shouldApply = { true },
        ) { state.value = state.value.copy(inputText = it) }

        typewriter.submit("stale partial")
        typewriter.cancel()
        state.value = state.value.copy(inputText = "new final")
        runCurrent()

        assertEquals("new final", state.value.inputText)
    }

    @Test
    fun `session owner preserves once across competing termination paths`() = runTest {
        val session = mockk<VoiceFlowSession>(relaxed = true)
        val audio = mockk<VoiceFlowPreservedAudio>()
        val abortStarted = CompletableDeferred<Unit>()
        val releaseAbort = CompletableDeferred<Unit>()
        coEvery { session.abortPreservingAudio() } coAnswers {
            abortStarted.complete(Unit)
            releaseAbort.await()
            audio
        }
        val owner = SpeechSessionOwner(session)

        val first = async { owner.preserve() }
        abortStarted.await()
        val second = async { owner.preserve() }
        releaseAbort.complete(Unit)
        owner.discard()

        assertSame(audio, first.await())
        assertSame(audio, second.await())
        coVerify(exactly = 1) { session.abortPreservingAudio() }
        coVerify(exactly = 0) { session.cancel() }
    }
}
