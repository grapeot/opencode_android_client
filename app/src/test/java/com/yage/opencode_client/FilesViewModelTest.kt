package com.yage.opencode_client

import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.model.FileNode
import com.yage.opencode_client.data.model.FileStatusEntry
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.files.FilesViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: OpenCodeRepository

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)

        coEvery { repository.getFileTree(any()) } returns Result.success(emptyList())
        coEvery { repository.getFileStatus() } returns Result.success(emptyList())
        coEvery { repository.getFileContent(any()) } returns Result.success(FileContent(type = "text", content = "file body"))
    }

    @Test
    fun `init loads root files and statuses`() = runTest {
        val files = listOf(
            FileNode(name = "src", path = "src", type = "directory"),
            FileNode(name = "README.md", path = "README.md", type = "file")
        )
        val statuses = listOf(
            FileStatusEntry(path = "README.md", status = "modified"),
            FileStatusEntry(path = "new.txt", status = null)
        )
        coEvery { repository.getFileTree(null) } returns Result.success(files)
        coEvery { repository.getFileStatus() } returns Result.success(statuses)

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        assertEquals(files, viewModel.state.value.files)
        assertEquals(
            mapOf("README.md" to "modified", "new.txt" to "untracked"),
            viewModel.state.value.fileStatuses
        )
        assertEquals("", viewModel.state.value.currentPath)
        coVerify { repository.getFileTree(null) }
        coVerify { repository.getFileStatus() }
    }

    @Test
    fun `resetForHost ignores a file response from the previous host`() = runTest {
        val oldResponse = CompletableDeferred<Result<List<FileNode>>>()
        val oldFiles = listOf(FileNode(name = "old.txt", path = "old.txt", type = "file"))
        val newFiles = listOf(FileNode(name = "new.txt", path = "new.txt", type = "file"))
        var calls = 0
        coEvery { repository.getFileTree(null) } coAnswers {
            if (calls++ == 0) oldResponse.await() else Result.success(newFiles)
        }

        val viewModel = FilesViewModel(repository)
        runCurrent()

        viewModel.resetForHost()
        runCurrent()
        assertEquals(newFiles, viewModel.state.value.files)

        oldResponse.complete(Result.success(oldFiles))
        advanceUntilIdle()

        assertEquals(newFiles, viewModel.state.value.files)
    }

    @Test
    fun `selectFile on directory updates path and loads tree`() = runTest {
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileTree("src") } returns Result.success(
            listOf(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"))
        )

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFile(FileNode(name = "src", path = "src", type = "directory"))
        advanceUntilIdle()

        assertEquals("src", viewModel.state.value.currentPath)
        assertEquals(listOf(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file")), viewModel.state.value.files)
        coVerify { repository.getFileTree("src") }
    }

    @Test
    fun `selectFile on file loads preview and invokes callback`() = runTest {
        val callbackPaths = mutableListOf<String>()
        val content = FileContent(type = "text", content = "hello")
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("src/Main.kt") } returns Result.success(content)

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFile(
            FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"),
            onFileClick = { callbackPaths += it }
        )
        advanceUntilIdle()

        assertEquals(listOf("src/Main.kt"), callbackPaths)
        assertEquals("src/Main.kt", viewModel.state.value.selectedFilePath)
        assertEquals(content, viewModel.state.value.selectedFileContent)
    }

    @Test
    fun `syncPathToShow loads relative file preview when session directory matches`() = runTest {
        val content = FileContent(type = "text", content = "preview")
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("src/Main.kt", "workspace") } returns Result.success(content)

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.syncPathToShow("workspace/src/Main.kt", "workspace")
        advanceUntilIdle()

        coVerify { repository.getFileContent("src/Main.kt", "workspace") }
        assertEquals("workspace/src/Main.kt", viewModel.state.value.selectedFilePath)
        assertEquals(content, viewModel.state.value.selectedFileContent)
    }

    @Test
    fun `syncPathToShow falls back to directory preview when file content is blank`() = runTest {
        val tree = listOf(
            FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"),
            FileNode(name = "util", path = "src/util", type = "directory")
        )
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("src", "workspace") } returns Result.success(FileContent(type = "text", content = ""))
        coEvery { repository.getFileTree("src", "workspace") } returns Result.success(tree)

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.syncPathToShow("workspace/src", "workspace")
        advanceUntilIdle()

        assertEquals("workspace/src", viewModel.state.value.selectedFilePath)
        assertEquals(
            "Directory:\n" + tree.joinToString("\n") { it.path },
            viewModel.state.value.selectedFileContent?.content
        )
    }

    @Test
    fun `syncPathToShow null clears preview state`() = runTest {
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFile(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"))
        advanceUntilIdle()
        assertEquals("src/Main.kt", viewModel.state.value.selectedFilePath)

        viewModel.syncPathToShow(null, "workspace")

        assertNull(viewModel.state.value.selectedFilePath)
        assertNull(viewModel.state.value.selectedFileContent)
        assertTrue(viewModel.state.value.error == null)
    }

    @Test
    fun `refreshPreview reloads selected file content`() = runTest {
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("src/Main.kt") } returnsMany listOf(
            Result.success(FileContent(type = "text", content = "before")),
            Result.success(FileContent(type = "text", content = "after"))
        )

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFile(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"))
        advanceUntilIdle()
        assertEquals("before", viewModel.state.value.selectedFileContent?.content)

        viewModel.refreshPreview(sessionDirectory = null)
        advanceUntilIdle()

        assertEquals("src/Main.kt", viewModel.state.value.selectedFilePath)
        assertEquals("after", viewModel.state.value.selectedFileContent?.content)
        assertEquals(false, viewModel.state.value.isPreviewRefreshing)
        coVerify(exactly = 2) { repository.getFileContent("src/Main.kt") }
    }

    @Test
    fun `refreshPreview reloads directory preview using relative session path`() = runTest {
        val initialTree = listOf(FileNode(name = "Old.kt", path = "src/Old.kt", type = "file"))
        val refreshedTree = listOf(FileNode(name = "New.kt", path = "src/New.kt", type = "file"))
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("src", "workspace") } returns Result.success(FileContent(type = "text", content = ""))
        coEvery { repository.getFileTree("src", "workspace") } returnsMany listOf(
            Result.success(initialTree),
            Result.success(refreshedTree)
        )

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.syncPathToShow("workspace/src", "workspace")
        advanceUntilIdle()
        assertEquals("Directory:\nsrc/Old.kt", viewModel.state.value.selectedFileContent?.content)

        viewModel.refreshPreview(sessionDirectory = "workspace")
        advanceUntilIdle()

        assertEquals("workspace/src", viewModel.state.value.selectedFilePath)
        assertEquals("Directory:\nsrc/New.kt", viewModel.state.value.selectedFileContent?.content)
        assertEquals(false, viewModel.state.value.isPreviewRefreshing)
        coVerify(exactly = 2) { repository.getFileContent("src", "workspace") }
        coVerify(exactly = 2) { repository.getFileTree("src", "workspace") }
    }

    @Test
    fun `same path and directory sync does not refetch but refresh does`() = runTest {
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("notes.md", "/work") } returns Result.success(
            FileContent(type = "text", content = "body")
        )

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.syncPathToShow("notes.md", "/work")
        advanceUntilIdle()
        viewModel.syncPathToShow("notes.md", "/work")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getFileContent("notes.md", "/work") }
        assertEquals("body", viewModel.state.value.selectedFileContent?.content)

        viewModel.refreshPreview("/work")
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getFileContent("notes.md", "/work") }
    }

    @Test
    fun `stale preview cannot overwrite a newer file or reopen after close`() = runTest {
        val oldResponse = CompletableDeferred<Result<FileContent>>()
        val newResponse = CompletableDeferred<Result<FileContent>>()
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("old.md", "/old") } coAnswers { oldResponse.await() }
        coEvery { repository.getFileContent("new.md", "/new") } coAnswers { newResponse.await() }

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()

        viewModel.syncPathToShow("old.md", "/old")
        runCurrent()
        viewModel.syncPathToShow("new.md", "/new")
        runCurrent()

        oldResponse.complete(Result.success(FileContent(type = "text", content = "stale")))
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.selectedFilePath)

        newResponse.complete(Result.success(FileContent(type = "text", content = "fresh")))
        advanceUntilIdle()
        assertEquals("new.md", viewModel.state.value.selectedFilePath)
        assertEquals("fresh", viewModel.state.value.selectedFileContent?.content)

        val lateRefresh = CompletableDeferred<Result<FileContent>>()
        coEvery { repository.getFileContent("new.md", "/new") } coAnswers { lateRefresh.await() }
        viewModel.refreshPreview("/new")
        runCurrent()
        viewModel.closePreview()
        lateRefresh.complete(Result.success(FileContent(type = "text", content = "resurrected")))
        advanceUntilIdle()

        assertNull(viewModel.state.value.selectedFilePath)
        assertNull(viewModel.state.value.selectedFileContent)
    }

    @Test
    fun `stale host epoch skips directory fallback and a new host still reads`() = runTest {
        val oldContent = CompletableDeferred<Result<FileContent>>()
        var epoch = 0L
        every { repository.currentHostEpoch() } answers { epoch }
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("docs/a.md", "/old-host") } coAnswers { oldContent.await() }
        coEvery { repository.getFileTree("docs/a.md", "/old-host") } returns Result.success(
            listOf(FileNode(name = "a.md", path = "docs/a.md", type = "file"))
        )
        coEvery { repository.getFileContent("docs/b.md", "/new-host") } returns Result.success(
            FileContent(type = "text", content = "new body")
        )

        val oldViewModel = FilesViewModel(repository)
        advanceUntilIdle()
        oldViewModel.syncPathToShow("/old-host/docs/a.md", "/old-host")
        runCurrent()

        epoch = 1L
        oldContent.complete(Result.failure(IllegalStateException("missing on the old host")))
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getFileTree("docs/a.md", "/old-host") }
        assertNull(oldViewModel.state.value.selectedFileContent)
        assertNull(oldViewModel.state.value.selectedFilePath)

        val newViewModel = FilesViewModel(repository)
        advanceUntilIdle()
        newViewModel.syncPathToShow("/new-host/docs/b.md", "/new-host")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getFileContent("docs/b.md", "/new-host") }
        assertEquals("/new-host/docs/b.md", newViewModel.state.value.selectedFilePath)
        assertEquals("new body", newViewModel.state.value.selectedFileContent?.content)
    }

    @Test
    fun `stale host epoch skips directory fallback when file content is blank`() = runTest {
        var epoch = 0L
        every { repository.currentHostEpoch() } answers { epoch }
        coEvery { repository.getFileTree(null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent("docs/a.md", "/old-host") } coAnswers {
            epoch = 1L
            Result.success(FileContent(type = "text", content = ""))
        }
        coEvery { repository.getFileTree("docs/a.md", "/old-host") } returns Result.success(emptyList())

        val viewModel = FilesViewModel(repository)
        advanceUntilIdle()
        viewModel.syncPathToShow("/old-host/docs/a.md", "/old-host")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getFileTree("docs/a.md", "/old-host") }
        assertNull(viewModel.state.value.selectedFileContent)
    }
}
