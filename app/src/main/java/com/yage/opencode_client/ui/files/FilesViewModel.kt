package com.yage.opencode_client.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.model.FileNode
import com.yage.opencode_client.data.repository.OpenCodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FilesUiState(
    val currentPath: String = "",
    val files: List<FileNode> = emptyList(),
    val fileStatuses: Map<String, String> = emptyMap(),
    val selectedFileContent: FileContent? = null,
    val selectedFilePath: String? = null,
    val isLoading: Boolean = false,
    val isPreviewRefreshing: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    val repository: OpenCodeRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private var hostGeneration = 0L
    private var previewToken = 0L
    private var settledToken = -1L
    private var settledPath: String? = null
    private var settledDirectory: String? = null

    init {
        refresh()
    }

    fun refresh() {
        loadFiles(_state.value.currentPath)
        loadFileStatuses()
    }

    fun resetForHost() {
        hostGeneration++
        invalidatePreview()
        _state.value = FilesUiState()
        refresh()
    }

    fun navigateUp() {
        val parentPath = _state.value.currentPath.substringBeforeLast("/", "")
        _state.update { it.copy(currentPath = parentPath) }
        loadFiles(parentPath)
    }

    fun selectFile(file: FileNode, onFileClick: (String) -> Unit = {}) {
        if (file.isDirectory) {
            _state.update { it.copy(currentPath = file.path) }
            loadFiles(file.path)
        } else {
            onFileClick(file.path)
            loadFileContent(file.path)
        }
    }

    fun closePreview() {
        invalidatePreview()
        _state.update {
            it.copy(
                selectedFilePath = null,
                selectedFileContent = null,
                isPreviewRefreshing = false
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun openPreviewPath(pathToShow: String, sessionDirectory: String?) {
        syncPathToShow(pathToShow, sessionDirectory)
    }

    fun syncPathToShow(pathToShow: String?, sessionDirectory: String?) {
        if (pathToShow == null) {
            closePreview()
            return
        }
        if (sameSettledPreview(pathToShow, sessionDirectory)) return
        loadPreview(pathToShow, sessionDirectory, isRefresh = false)
    }

    fun refreshPreview(sessionDirectory: String?) {
        val pathToShow = _state.value.selectedFilePath ?: return
        loadPreview(pathToShow, sessionDirectory, isRefresh = true)
    }

    private fun loadPreview(pathToShow: String, sessionDirectory: String?, isRefresh: Boolean) {
        val relPath = resolveRelativePreviewPath(pathToShow, sessionDirectory)
        val token = beginPreview(pathToShow, sessionDirectory)
        val hostToken = hostGeneration
        val epoch = repository.currentHostEpoch()
        viewModelScope.launch {
            if (!previewStillCurrent(token, hostToken, epoch)) return@launch
            if (isRefresh) {
                _state.update { it.copy(isPreviewRefreshing = true, error = null) }
            }
            if (!previewStillCurrent(token, hostToken, epoch)) return@launch
            fetchFileContent(relPath, sessionDirectory)
                .onSuccess { content ->
                    if (!previewStillCurrent(token, hostToken, epoch)) return@onSuccess
                    if (!content.content.isNullOrBlank()) {
                        _state.update {
                            it.copy(
                                selectedFileContent = content,
                                selectedFilePath = pathToShow,
                                isPreviewRefreshing = false,
                                error = null
                            )
                        }
                    } else if (previewStillCurrent(token, hostToken, epoch)) {
                        loadDirectoryPreview(pathToShow, relPath, sessionDirectory, isRefresh, token, hostToken, epoch)
                    }
                }
                .onFailure {
                    if (previewStillCurrent(token, hostToken, epoch)) {
                        loadDirectoryPreview(pathToShow, relPath, sessionDirectory, isRefresh, token, hostToken, epoch)
                    }
                }
        }
    }

    private fun setDirectoryPreview(path: String, relPath: String, tree: List<FileNode>) {
        _state.update {
            it.copy(
                selectedFilePath = path,
                selectedFileContent = FileContent(
                    type = "text",
                    content = buildDirectoryPreviewContent(relPath, tree)
                ),
                isPreviewRefreshing = false,
                error = null
            )
        }
    }

    private suspend fun loadDirectoryPreview(
        path: String,
        relPath: String,
        sessionDirectory: String?,
        isRefresh: Boolean,
        token: Long,
        hostToken: Long,
        epoch: Long,
    ) {
        if (!previewStillCurrent(token, hostToken, epoch)) return
        fetchFileTree(relPath, sessionDirectory)
            .onSuccess { tree ->
                if (previewStillCurrent(token, hostToken, epoch)) setDirectoryPreview(path, relPath, tree)
            }
            .onFailure { throwable ->
                if (previewStillCurrent(token, hostToken, epoch)) {
                    forgetSettledPreview(token)
                    _state.update {
                        it.copy(
                            isPreviewRefreshing = if (isRefresh) false else it.isPreviewRefreshing,
                            error = throwable.message
                        )
                    }
                }
            }
    }

    private fun loadFiles(path: String) {
        val generation = hostGeneration
        viewModelScope.launch {
            if (generation != hostGeneration) return@launch
            _state.update { it.copy(isLoading = true, error = null) }
            repository.getFileTree(path.ifEmpty { null })
                .onSuccess { tree ->
                    if (generation == hostGeneration) {
                        _state.update {
                            it.copy(
                                files = tree,
                                isLoading = false
                            )
                        }
                    }
                }
                .onFailure { throwable ->
                    if (generation == hostGeneration) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                error = throwable.message
                            )
                        }
                    }
                }
        }
    }

    private fun loadFileStatuses() {
        val generation = hostGeneration
        viewModelScope.launch {
            repository.getFileStatus()
                .onSuccess { statuses ->
                    if (generation == hostGeneration) {
                        _state.update {
                            it.copy(
                                fileStatuses = statuses.mapNotNull { entry ->
                                    entry.path?.let { path -> path to (entry.status ?: "untracked") }
                                }.toMap()
                            )
                        }
                    }
                }
        }
    }

    private fun loadFileContent(path: String) {
        val token = beginPreview(path, null)
        val hostToken = hostGeneration
        val epoch = repository.currentHostEpoch()
        viewModelScope.launch {
            if (!previewStillCurrent(token, hostToken, epoch)) return@launch
            fetchFileContent(path, null)
                .onSuccess { content ->
                    if (!previewStillCurrent(token, hostToken, epoch)) return@onSuccess
                    _state.update {
                        it.copy(
                            selectedFileContent = content,
                            selectedFilePath = path,
                            error = null
                        )
                    }
                }
                .onFailure { throwable ->
                    if (previewStillCurrent(token, hostToken, epoch)) {
                        forgetSettledPreview(token)
                        _state.update { it.copy(error = throwable.message) }
                    }
                }
        }
    }

    private fun beginPreview(path: String, directory: String?): Long {
        previewToken += 1
        settledPath = path
        settledDirectory = directory
        settledToken = previewToken
        return previewToken
    }

    private fun invalidatePreview() {
        previewToken += 1
        settledPath = null
        settledDirectory = null
        settledToken = -1
    }

    private fun forgetSettledPreview(token: Long) {
        if (settledToken == token) {
            settledPath = null
            settledDirectory = null
            settledToken = -1
        }
    }

    private fun sameSettledPreview(path: String, directory: String?): Boolean =
        settledToken == previewToken && settledPath == path && settledDirectory == directory

    private fun previewCurrent(token: Long, hostToken: Long): Boolean =
        token == previewToken && hostToken == hostGeneration

    private fun previewStillCurrent(token: Long, hostToken: Long, epoch: Long): Boolean =
        previewCurrent(token, hostToken) && epoch == repository.currentHostEpoch()

    private suspend fun fetchFileContent(path: String, directory: String?) =
        directory.asContentDirectory()?.let { repository.getFileContent(path, it) }
            ?: repository.getFileContent(path)

    private suspend fun fetchFileTree(path: String, directory: String?) =
        directory.asContentDirectory()?.let { repository.getFileTree(path, it) }
            ?: repository.getFileTree(path)
}

private fun String?.asContentDirectory(): String? = this?.takeIf { it.isNotBlank() }
