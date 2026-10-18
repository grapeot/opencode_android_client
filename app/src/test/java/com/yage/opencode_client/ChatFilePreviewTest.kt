package com.yage.opencode_client

import androidx.compose.runtime.saveable.SaverScope
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.model.FileNode
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.chat.ChatFilePreviewRequest
import com.yage.opencode_client.ui.chat.ChatFilePreviewRequestSaver
import com.yage.opencode_client.ui.chat.belongsTo
import com.yage.opencode_client.ui.chat.loadChatPreviewContent
import com.yage.opencode_client.ui.chat.shouldAcceptPreviewResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFilePreviewTest {

    @Test
    fun `belongsTo rejects different host session or directory`() {
        val request = ChatFilePreviewRequest(
            path = "README.md",
            hostProfileId = "host-a",
            sessionId = "session-1",
            workspaceDirectory = "/workspace"
        )

        assertTrue(request.belongsTo("host-a", "session-1", "/workspace"))
        assertFalse(request.belongsTo("host-b", "session-1", "/workspace"))
        assertFalse(request.belongsTo("host-a", "session-2", "/workspace"))
        assertFalse(request.belongsTo("host-a", "session-1", "/other"))
        assertFalse(request.belongsTo("host-a", null, "/workspace"))
        assertFalse(request.belongsTo("host-a", "session-1", null))
    }

    @Test
    fun `belongsTo treats null and empty workspace as distinct`() {
        val nullWorkspace = ChatFilePreviewRequest("a.md", "host", "session", null)
        val emptyWorkspace = ChatFilePreviewRequest("a.md", "host", "session", "")

        assertTrue(nullWorkspace.belongsTo("host", "session", null))
        assertFalse(nullWorkspace.belongsTo("host", "session", ""))
        assertTrue(emptyWorkspace.belongsTo("host", "session", ""))
        assertFalse(emptyWorkspace.belongsTo("host", "session", null))
    }

    @Test
    fun `saver roundtrip preserves nullable workspace`() {
        val original = ChatFilePreviewRequest(
            path = "docs/note.md",
            hostProfileId = null,
            sessionId = "ses_1",
            workspaceDirectory = null
        )
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(ChatFilePreviewRequestSaver) { scope.save(original) }
        val restored = ChatFilePreviewRequestSaver.restore(saved!!)

        assertEquals(original, restored)
    }

    @Test
    fun `saver roundtrip preserves empty workspace root`() {
        val original = ChatFilePreviewRequest(
            path = "",
            hostProfileId = "host",
            sessionId = "ses_1",
            workspaceDirectory = ""
        )
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(ChatFilePreviewRequestSaver) { scope.save(original) }
        val restored = ChatFilePreviewRequestSaver.restore(saved!!)

        assertEquals(original, restored)
    }

    @Test
    fun `shouldAcceptPreviewResult requires same instance and generation`() {
        val first = ChatFilePreviewRequest("a.md", "host", "s1", "/ws")
        val reopened = first.copy()

        assertTrue(shouldAcceptPreviewResult(first, first, 2, 2))
        assertFalse(shouldAcceptPreviewResult(first, reopened, 2, 2))
        assertFalse(shouldAcceptPreviewResult(first, first, 1, 2))
        assertFalse(shouldAcceptPreviewResult(first, null, 2, 2))
    }

    @Test
    fun `loadChatPreviewContent returns file body`() = runTest {
        val repository = mockk<OpenCodeRepository>()
        coEvery { repository.getFileContent("README.md", "/workspace") } returns Result.success(
            FileContent(type = "text", content = "# Hello")
        )
        val request = ChatFilePreviewRequest("README.md", "host", "s1", "/workspace")

        val result = loadChatPreviewContent(repository, request)

        assertEquals("# Hello", result.getOrThrow().content)
    }

    @Test
    fun `loadChatPreviewContent falls back to directory listing`() = runTest {
        val repository = mockk<OpenCodeRepository>()
        coEvery { repository.getFileContent("src", "/workspace") } returns Result.success(FileContent(type = "text", content = ""))
        coEvery { repository.getFileTree("src", "/workspace") } returns Result.success(
            listOf(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"))
        )
        val request = ChatFilePreviewRequest("src", "host", "s1", "/workspace")

        val result = loadChatPreviewContent(repository, request)

        assertEquals("Directory:\nsrc/Main.kt", result.getOrThrow().content)
    }

    @Test
    fun `stale result is dropped after close and reopen of same path`() {
        val closed = ChatFilePreviewRequest("a.md", "host", "s1", "/ws")
        val reopened = closed.copy()

        assertFalse(shouldAcceptPreviewResult(closed, reopened, 1, 2))
        assertTrue(shouldAcceptPreviewResult(reopened, reopened, 2, 2))
    }

    @Test
    fun `cancelled load rethrows cancellation`() = runTest {
        val repository = mockk<OpenCodeRepository>()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.getFileContent("slow.md", "/ws") } coAnswers {
            gate.await()
            Result.success(FileContent(type = "text", content = "late"))
        }
        val request = ChatFilePreviewRequest("slow.md", "host", "s1", "/ws")
        val job = async { loadChatPreviewContent(repository, request) }

        job.cancel()
        var thrown: Throwable? = null
        try {
            job.await()
        } catch (error: Throwable) {
            thrown = error
        }
        gate.complete(Unit)

        assertTrue(thrown is CancellationException)
    }
}
