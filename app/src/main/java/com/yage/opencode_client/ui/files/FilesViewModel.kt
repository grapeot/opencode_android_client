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

    init {
        refresh()
    }

    fun refresh() {
        loadFiles(_state.value.currentPath)
        loadFileStatuses()
    }

    fun resetForHost() {
        hostGeneration++
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
        loadPreview(pathToShow, sessionDirectory, isRefresh = false)
    }

    fun syncPathToShow(pathToShow: String?, sessionDirectory: String?) {
        if (pathToShow == null) {
            closePreview()
            return
        }

        loadPreview(pathToShow, sessionDirectory, isRefresh = false)
    }

    fun refreshPreview(sessionDirectory: String?) {
        val pathToShow = _state.value.selectedFilePath ?: return
        loadPreview(pathToShow, sessionDirectory, isRefresh = true)
    }

    private fun loadPreview(pathToShow: String, sessionDirectory: String?, isRefresh: Boolean) {
        val relPath = resolveRelativePreviewPath(pathToShow, sessionDirectory)
        val generation = hostGeneration
        viewModelScope.launch {
            if (generation != hostGeneration) return@launch
            if (isRefresh) {
                _state.update { it.copy(isPreviewRefreshing = true, error = null) }
            }
            repository.getFileContent(relPath)
                .onSuccess { content ->
                    if (generation != hostGeneration) return@onSuccess
                    if (!content.content.isNullOrBlank()) {
                        _state.update {
                            it.copy(
                                selectedFileContent = content,
                                selectedFilePath = pathToShow,
                                isPreviewRefreshing = false,
                                error = null
                            )
                        }
                    } else {
                        loadDirectoryPreview(pathToShow, relPath, isRefresh, generation)
                    }
                }
                .onFailure {
                    if (generation == hostGeneration) {
                        loadDirectoryPreview(pathToShow, relPath, isRefresh, generation)
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
        isRefresh: Boolean = false,
        generation: Long = hostGeneration
    ) {
        repository.getFileTree(relPath)
            .onSuccess { tree ->
                if (generation == hostGeneration) setDirectoryPreview(path, relPath, tree)
            }
            .onFailure { throwable ->
                if (generation == hostGeneration) {
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
        val generation = hostGeneration
        viewModelScope.launch {
            repository.getFileContent(path)
                .onSuccess { content ->
                    if (generation == hostGeneration) {
                        _state.update {
                            it.copy(
                                selectedFileContent = content,
                                selectedFilePath = path,
                                error = null
                            )
                        }
                    }
                }
                .onFailure { throwable ->
                    if (generation == hostGeneration) {
                        _state.update { it.copy(error = throwable.message) }
                    }
                }
        }
    }
}
