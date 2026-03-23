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
    val error: String? = null
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    val repository: OpenCodeRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        loadFiles(_state.value.currentPath)
        loadFileStatuses()
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
                selectedFileContent = null
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun syncPathToShow(pathToShow: String?, sessionDirectory: String?) {
        if (pathToShow == null) {
            closePreview()
            return
        }

        val relPath = resolveRelativePreviewPath(pathToShow, sessionDirectory)
        viewModelScope.launch {
            repository.getFileContent(relPath)
                .onSuccess { content ->
                    if (!content.content.isNullOrBlank()) {
                        _state.update {
                            it.copy(
                                selectedFileContent = content,
                                selectedFilePath = pathToShow,
                                error = null
                            )
                        }
                    } else {
                        loadDirectoryPreview(pathToShow, relPath)
                    }
                }
                .onFailure {
                    loadDirectoryPreview(pathToShow, relPath)
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
                error = null
            )
        }
    }

    private suspend fun loadDirectoryPreview(path: String, relPath: String) {
        repository.getFileTree(relPath)
            .onSuccess { tree -> setDirectoryPreview(path, relPath, tree) }
            .onFailure { throwable ->
                _state.update { it.copy(error = throwable.message) }
            }
    }

    private fun loadFiles(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.getFileTree(path.ifEmpty { null })
                .onSuccess { tree ->
                    _state.update {
                        it.copy(
                            files = tree,
                            isLoading = false
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.message
                        )
                    }
                }
        }
    }

    private fun loadFileStatuses() {
        viewModelScope.launch {
            repository.getFileStatus()
                .onSuccess { statuses ->
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

    private fun loadFileContent(path: String) {
        viewModelScope.launch {
            repository.getFileContent(path)
                .onSuccess { content ->
                    _state.update {
                        it.copy(
                            selectedFileContent = content,
                            selectedFilePath = path,
                            error = null
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update { it.copy(error = throwable.message) }
                }
        }
    }
}
